package com.awitize.app

import com.awitize.app.data.InnerTube
import com.awitize.app.data.StreamResolver
import com.awitize.app.data.copyWithProgress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

// Live end-to-end checks of the exact code paths the phone app runs:
// InnerTube search/browse parsing and NewPipeExtractor stream resolution.
class LiveNetworkTest {

    private fun http() = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    @Test
    fun searchSongsParsesSections() = runBlocking {
        val tube = InnerTube(http())
        val sections = tube.search("shape of you", "songs")
        println("search sections: " + sections.map { "${it.title}=${it.items.size}" })
        assertTrue("expected at least one section", sections.isNotEmpty())
        val items = sections.flatMap { it.items }
        assertTrue("expected playable parsed songs", items.any { it.playable })
        val first = items.first { it.playable }
        println("first song: ${first.title} | ${first.artists} | ${first.duration} | ${first.videoId}")
        assertTrue("song should have a title", first.title.isNotBlank())
        assertTrue("song should have a duration", !first.duration.isNullOrBlank())
    }

    @Test
    fun searchAllTabGroupsResults() = runBlocking {
        val tube = InnerTube(http())
        val sections = tube.search("owl city", null)
        println("all-tab sections: " + sections.map { "${it.title}=${it.items.size}" })
        assertTrue("expected grouped sections for the All tab", sections.isNotEmpty())
        assertTrue("expected a Top result section", sections.any { it.title == "Top result" })
        assertTrue(
            "expected a Songs section with playable tracks",
            sections.any { it.title == "Songs" && it.items.any { t -> t.playable } },
        )
        assertTrue("every section should have items", sections.all { it.items.isNotEmpty() })
        assertTrue("every item should have a title", sections.all { sec -> sec.items.all { it.title.isNotBlank() } })
    }

    @Test
    fun searchAlbumsReturnsCards() = runBlocking {
        val tube = InnerTube(http())
        val sections = tube.search("divide ed sheeran", "albums")
        val items = sections.flatMap { it.items }
        println("album cards: " + items.take(5).map { "${it.title}/${it.kind}/${it.browseId}" })
        assertTrue("expected album cards with browseIds", items.any { it.browseId?.startsWith("MPREb") == true })
    }

    @Test
    fun exploreReturnsShelves() = runBlocking {
        val tube = InnerTube(http())
        val ex = tube.explore()
        println("explore: newReleases=${ex.newReleases.size} moods=${ex.moods.size} trending=${ex.trending.size} videos=${ex.newVideos.size} artists=${ex.chartArtists.size}")
        assertTrue(
            "expected new releases or moods",
            ex.newReleases.isNotEmpty() || ex.moods.isNotEmpty(),
        )
        if (ex.moods.isNotEmpty()) {
            val mood = ex.moods.first()
            println("first mood: ${mood.title}")
            val lists = tube.moodPlaylists(mood.params)
            println("mood playlists: ${lists.size} e.g. " + lists.take(3).map { "${it.title}/${it.kind}/${it.browseId}/${it.playlistId}" })
            assertTrue("expected playlists for mood ${mood.title}", lists.isNotEmpty())
        }
    }

    @Test
    fun streamResolvesAndFetchesAudio() {
        val client = http()
        StreamResolver.init(client)
        val resolver = StreamResolver(client)
        val tube = InnerTube(client)
        val videoId = runBlocking {
            tube.search("shape of you", "songs")
                .flatMap { it.items }
                .firstOrNull { it.playable }?.videoId
        }
        assertTrue("no playable search result to resolve", videoId != null)
        val rs = resolver.resolveBlocking(videoId!!)
        println("resolved: ${rs.ext} ${rs.mime}")
        assertTrue("resolved url empty", rs.url.isNotEmpty())
        val req = Request.Builder().url(rs.url).header("Range", "bytes=0-65535").build()
        client.newCall(req).execute().use { r ->
            val bytes = r.body?.bytes()?.size ?: 0
            println("stream fetch: HTTP ${r.code}, $bytes bytes")
            assertTrue("stream HTTP ${r.code}", r.isSuccessful)
            assertTrue("expected audio bytes, got $bytes", bytes > 1024)
        }
    }

    @Test
    fun fullDownloadCompletesWithProgress() {
        val client = http()
        StreamResolver.init(client)
        val resolver = StreamResolver(client)
        val tube = InnerTube(client)
        val videoId = runBlocking {
            tube.search("shape of you", "songs")
                .flatMap { it.items }
                .firstOrNull { it.playable }?.videoId
        }
        assertTrue("no playable search result to resolve", videoId != null)
        val rs = resolver.resolveBlocking(videoId!!)
        val progress = mutableListOf<Long>()
        val dest = File.createTempFile("awitize-dl-", "." + rs.ext)
        try {
            client.newCall(Request.Builder().url(rs.url).build()).execute().use { r ->
                assertTrue("download HTTP ${r.code}", r.isSuccessful)
                val body = r.body ?: throw IllegalStateException("empty body")
                val total = body.contentLength()
                dest.outputStream().use { out ->
                    copyWithProgress(body.byteStream(), out, total) { progress.add(it) }
                }
                println("full download: ${rs.ext} contentLength=$total fileBytes=${dest.length()} updates=${progress.size}")
                if (total > 0) {
                    assertEquals("file must match the advertised size", total, dest.length())
                    assertEquals("progress must reach the full size", total, progress.lastOrNull())
                } else {
                    assertTrue("no progress events when size is unknown", progress.isEmpty())
                }
            }
            assertTrue("expected a real audio file, got ${dest.length()} bytes", dest.length() > 1_000_000)
            assertTrue("progress must be monotonic", progress.zipWithNext().all { (a, b) -> b >= a })
        } finally {
            dest.delete()
        }
    }

    // Pause/resume and the "connection reset near the end" recovery both rely
    // on ranged continuation: this walks the exact two-leg flow the app uses.
    @Test
    fun resumableRangeDownloadCompletes() {
        val client = http()
        StreamResolver.init(client)
        val resolver = StreamResolver(client)
        val tube = InnerTube(client)
        val videoId = runBlocking {
            tube.search("shape of you", "songs")
                .flatMap { it.items }
                .firstOrNull { it.playable }?.videoId
        }
        assertTrue("no playable search result to resolve", videoId != null)
        val rs = resolver.resolveBlocking(videoId!!)
        val dest = File.createTempFile("awitize-resume-", "." + rs.ext)
        try {
            // leg 1 — the pause point
            client.newCall(Request.Builder().url(rs.url).header("Range", "bytes=0-65535").build()).execute().use { r ->
                assertEquals("ranged request should return 206", 206, r.code)
                r.body!!.byteStream().use { ins -> dest.outputStream().use { ins.copyTo(it) } }
            }
            val firstLeg = dest.length()
            assertTrue("first leg should write something", firstLeg > 0)

            // leg 2 — resume exactly like Downloads does after a pause or a
            // mid-transfer reset, reporting progress from the resume offset
            client.newCall(Request.Builder().url(rs.url).header("Range", "bytes=$firstLeg-").build()).execute().use { r ->
                assertTrue("resume HTTP ${r.code}", r.isSuccessful)
                val total = r.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                assertTrue("Content-Range should carry the total size", total != null && total > 0)
                val progress = mutableListOf<Long>()
                FileOutputStream(dest, true).use { out ->
                    copyWithProgress(r.body!!.byteStream(), out, total!!, firstLeg) { progress.add(it) }
                }
                println("resume test: firstLeg=$firstLeg total=${dest.length()} expected=$total updates=${progress.size}")
                assertEquals("resumed file must match the full size", total, dest.length())
                assertEquals("progress must end at the full size", total, progress.lastOrNull())
                assertTrue("progress must never go backwards", progress.zipWithNext().all { (a, b) -> b >= a })
            }
        } finally {
            dest.delete()
        }
    }

    @Test
    fun ytmPlaylistAndAlbumAndArtistParse() = runBlocking {
        val tube = InnerTube(http())
        val playlist = tube.search("top hits", "playlists").flatMap { it.items }
            .firstOrNull { it.browseId?.startsWith("VL") == true }
        if (playlist != null) {
            val page = tube.ytmPlaylist(playlist.browseId!!)
            println("playlist '${page.title}' tracks=${page.tracks.size}")
            assertTrue("playlist should have playable tracks", page.tracks.any { it.playable })
        }
        val album = tube.search("divide ed sheeran", "albums").flatMap { it.items }
            .firstOrNull { it.browseId?.startsWith("MPREb") == true }
        if (album != null) {
            val page = tube.album(album.browseId!!)
            println("album '${page.title}' by ${page.artists} tracks=${page.tracks.size}")
            assertTrue("album should have playable tracks", page.tracks.any { it.playable })
        }
        val artist = tube.search("ed sheeran", "artists").flatMap { it.items }
            .firstOrNull { it.browseId?.startsWith("UC") == true }
        if (artist != null) {
            val page = tube.artist(artist.browseId!!)
            println("artist '${page.name}' songs=${page.songs.size} albums=${page.albums.size}")
            assertTrue("artist page should have songs or albums", page.songs.isNotEmpty() || page.albums.isNotEmpty())
        }
    }

    // The "song just skips instead of playing" fix: when a cached URL goes stale
    // the player drops it and resolves again. This walks that exact flow —
    // invalidate() then re-resolve — and proves the refreshed URL is immediately
    // playable, including through the music/www extractor fallback chain.
    @Test
    fun invalidatedStreamResolvesAgainAndPlays() {
        val client = http()
        StreamResolver.init(client)
        val resolver = StreamResolver(client)
        val tube = InnerTube(client)
        val videoId = runBlocking {
            tube.search("shape of you", "songs")
                .flatMap { it.items }
                .firstOrNull { it.playable }?.videoId
        }
        assertTrue("no playable search result to resolve", videoId != null)
        val first = resolver.resolveBlocking(videoId!!)
        assertTrue("first resolve returned no url", first.url.isNotEmpty())

        resolver.invalidate(videoId) // exactly what PlaybackController does on error
        val second = resolver.resolveBlocking(videoId, forceRefresh = true)
        println("refresh: first=${first.ext} second=${second.ext} urlChanged=${first.url != second.url}")
        assertTrue("refreshed url empty", second.url.isNotEmpty())

        client.newCall(
            Request.Builder().url(second.url).header("Range", "bytes=0-32767").build(),
        ).execute().use { r ->
            val bytes = r.body?.bytes()?.size ?: 0
            println("refreshed stream fetch: HTTP ${r.code}, $bytes bytes")
            assertTrue("refreshed stream HTTP ${r.code}", r.isSuccessful)
            assertTrue("refreshed stream returned no audio, got $bytes bytes", bytes > 1024)
        }
    }
}
