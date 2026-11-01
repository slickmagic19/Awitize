package com.awitize.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

// Ad-free audio URLs via NewPipeExtractor — the same extractor the NewPipe app
// uses. Progressive (non-segmented) audio streams only: they play directly in
// ExoPlayer and can be saved offline as a single file.
data class ResolvedStream(val url: String, val ext: String, val mime: String?)

class StreamResolver(private val http: OkHttpClient) {

    private data class Entry(val stream: ResolvedStream, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val misses = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    companion object {
        private const val TTL_MS = 5 * 3600 * 1000L // URLs live ~6h
        private const val MISS_TTL_MS = 60 * 1000L
        @Volatile private var inited = false

        fun init(http: OkHttpClient) {
            if (inited) return
            synchronized(this) {
                if (inited) return
                NewPipe.init(NpDownloader(http))
                inited = true
            }
        }
    }

    // forceRefresh bypasses both cache and negative-cache — used when a
    // download retry needs a fresh URL (expired or rejected by the CDN).
    fun resolveBlocking(videoId: String, forceRefresh: Boolean = false): ResolvedStream {
        if (!forceRefresh) {
            cache[videoId]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.stream }
            misses[videoId]?.let { if (System.currentTimeMillis() - it < MISS_TTL_MS) throw IOException("Stream unavailable right now") }
        }
        // one extraction per video even under concurrent requests (player + download)
        val mutex = locks.computeIfAbsent(videoId) { Mutex() }
        return runBlocking {
            mutex.withLock {
                if (!forceRefresh) {
                    cache[videoId]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return@withLock it.stream }
                }
                try {
                    val rs = extractWithFallbacks(videoId)
                    cache[videoId] = Entry(rs, System.currentTimeMillis())
                    misses.remove(videoId)
                    rs
                } catch (e: Exception) {
                    misses[videoId] = System.currentTimeMillis()
                    throw if (e is IOException) e else IOException(e.message ?: "Couldn't resolve a stream", e)
                }
            }
        }
    }

    // Forget everything we know about a track so the next resolve fetches a
    // brand-new URL. Called when playback/download hits a 403/410 because the
    // cached URL expired or the CDN rejected it.
    fun invalidate(videoId: String) {
        cache.remove(videoId)
        misses.remove(videoId)
    }

    // Some tracks extract cleanly from the normal YouTube watch page but not
    // from the music watch page (and vice-versa). Try both, twice each, so a
    // single flaky extraction never turns into a skipped song.
    private suspend fun extractWithFallbacks(videoId: String): ResolvedStream {
        val urls = listOf(
            "https://music.youtube.com/watch?v=$videoId",
            "https://www.youtube.com/watch?v=$videoId",
        )
        var last: Exception? = null
        for (url in urls) {
            repeat(2) { attempt ->
                try {
                    return extractFrom(url)
                } catch (e: Exception) {
                    last = e
                    if (attempt == 0) delay(500)
                }
            }
        }
        throw IOException(last?.message ?: "No playable audio stream was offered for this track")
    }

    private fun extractFrom(url: String): ResolvedStream {
        val info = StreamInfo.getInfo(NewPipe.getService(0), url)
        val audio = info.audioStreams
        val progressive = audio.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val best = progressive.maxByOrNull { it.averageBitrate }
            ?: throw IOException("No playable audio stream was offered for this track")
        val mime = best.format?.mimeType
        val ext = when {
            mime?.contains("mp4") == true -> "m4a"
            mime?.contains("webm") == true -> "weba"
            mime?.contains("ogg") == true -> "ogg"
            else -> "m4a"
        }
        val streamUrl = best.url ?: throw IOException("No stream URL was offered for this track")
        return ResolvedStream(streamUrl, ext, mime)
    }
}

private class NpDownloader(private val http: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val method = request.httpMethod().uppercase()
        val data = request.dataToSend()
        val body = if (data != null) {
            val ct = request.headers()["Content-Type"]?.firstOrNull()
                ?: request.headers()["content-type"]?.firstOrNull()
                ?: "application/json"
            data.toRequestBody(ct.toMediaTypeOrNull())
        } else null

        val rb = okhttp3.Request.Builder().url(request.url())
        rb.method(method, if (method == "GET" || method == "HEAD") null else (body ?: ByteArray(0).toRequestBody(null)))
        for ((name, values) in request.headers()) {
            when (name.lowercase()) {
                // OkHttp manages these itself; forwarding them breaks gzip
                // transparency and body framing
                "accept-encoding", "content-length", "transfer-encoding", "host", "connection" -> {}
                else -> for (v in values) rb.addHeader(name, v)
            }
        }
        http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            return Response(r.code, r.message, r.headers.toMultimap(), text, r.request.url.toString())
        }
    }
}
