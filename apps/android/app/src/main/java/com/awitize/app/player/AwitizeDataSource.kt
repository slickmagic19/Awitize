package com.awitize.app.player

import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import com.awitize.app.Container
import java.io.IOException

// URI scheme for the player:
//   awitize://stream/{videoId}  → resolve a fresh ad-free URL (NewPipeExtractor)
//   awitize://offline/{videoId} → play the saved file, else fall back to stream
// Everything else falls through to plain HTTP(S).
class AwitizeDataSource(private val container: Container) : BaseDataSource(true) {

    private var upstream: DataSource? = null
    private var uri: Uri? = null
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val scheme = dataSpec.uri.scheme ?: ""
        val last = dataSpec.uri.lastPathSegment

        transferInitializing(dataSpec)

        if (scheme == "awitize") {
            if (last.isNullOrBlank()) throw IOException("Malformed media URI")
            // a saved copy plays straight from disk — no resolver, works offline
            val offline = container.downloads.file(last)
            if (offline != null) {
                val file = FileDataSource()
                val len = file.open(dataSpec.withUri(Uri.fromFile(offline)))
                upstream = file
                started = true
                transferStarted(dataSpec)
                return len
            }
            return openStream(last, dataSpec, forceRefresh = false)
        }

        val http = httpSource()
        val len = http.open(dataSpec)
        upstream = http
        started = true
        transferStarted(dataSpec)
        return len
    }

    // Opens the resolved URL. YouTube's media URLs expire and can be rejected
    // mid-session (403/410); when that happens we throw, drop the cached URL
    // and retry once with a freshly extracted one before giving up.
    private fun openStream(videoId: String, dataSpec: DataSpec, forceRefresh: Boolean): Long {
        val resolved = container.resolver.resolveBlocking(videoId, forceRefresh)
        val http = httpSource()
        return try {
            val len = http.open(dataSpec.withUri(Uri.parse(resolved.url)))
            upstream = http
            started = true
            transferStarted(dataSpec)
            len
        } catch (e: IOException) {
            runCatching { http.close() }
            if (forceRefresh) throw e
            container.resolver.invalidate(videoId)
            openStream(videoId, dataSpec, forceRefresh = true)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream?.read(buffer, offset, length) ?: -1
        if (n > 0) bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            upstream?.close()
        } finally {
            upstream = null
            uri = null
            if (started) {
                started = false
                transferEnded()
            }
        }
    }

    class Factory(private val container: Container) : DataSource.Factory {
        override fun createDataSource(): DataSource = AwitizeDataSource(container)
    }

    private fun httpSource(): DataSource = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .createDataSource()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"
    }
}
