package com.awitize.app

import com.awitize.app.data.LibraryFile
import com.awitize.app.data.PlayEvent
import com.awitize.app.data.Playlist
import com.awitize.app.data.SyncMerge
import com.awitize.app.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {

    private val incoming = LibraryFile(
        liked = listOf("a", "b"),
        playlists = listOf(
            Playlist(id = "", name = "  Road Trip ", tracks = listOf(track("a"), track("c"))),
            Playlist(id = "", name = "road trip", tracks = listOf(track("d"))),
        ),
        plays = listOf(PlayEvent("a", 1000, 3), PlayEvent("b", 2000, 1)),
        trackmap = mapOf("a" to track("a"), "b" to track("b"), "c" to track("c"), "d" to track("d")),
    )

    private fun track(id: String) = Track(videoId = id, title = "Track $id")

    @Test
    fun mergeIsIdempotent() {
        val (once, stats1) = SyncMerge.merge(LibraryFile(), incoming)
        assertTrue("first merge should add things", stats1.total > 0)
        val (twice, stats2) = SyncMerge.merge(once, incoming)
        assertEquals("second merge must change nothing", 0, stats2.total)
        assertEquals(once.liked, twice.liked)
        assertEquals(once.playlists.map { it.name to it.tracks.map { t -> t.videoId } },
            twice.playlists.map { it.name to it.tracks.map { t -> t.videoId } })
    }

    @Test
    fun playlistsMatchByNormalizedName() {
        val (merged, _) = SyncMerge.merge(LibraryFile(), incoming)
        assertEquals("both playlists share one normalized name", 1, merged.playlists.size)
        val ids = merged.playlists[0].tracks.mapNotNull { it.videoId }.toSet()
        assertEquals(setOf("a", "c", "d"), ids)
    }

    @Test
    fun likesUnionAndPlaysTakeMax() {
        val local = LibraryFile(
            liked = listOf("z"),
            plays = listOf(PlayEvent("a", 500, 7)),
        )
        val (merged, _) = SyncMerge.merge(local, incoming)
        assertEquals(listOf("z", "a", "b"), merged.liked)
        val a = merged.plays.first { it.id == "a" }
        assertEquals("max count wins", 7, a.n)
        assertEquals("newest timestamp wins", 1000L, a.ts)
        assertTrue("plays sorted by ts desc", merged.plays.zipWithNext().all { (x, y) -> x.ts >= y.ts })
    }

    @Test
    fun trackmapGapFillOnly() {
        val localTrack = Track(videoId = "a", title = "Local title")
        val local = LibraryFile(trackmap = mapOf("a" to localTrack))
        val (merged, _) = SyncMerge.merge(local, incoming)
        assertEquals("local track data must win", "Local title", merged.trackmap["a"]?.title)
        assertTrue("missing tracks are filled", merged.trackmap.containsKey("b"))
    }

    @Test
    fun snapshotShipsOnlyNeededTracks() {
        val local = LibraryFile(
            liked = listOf("a"),
            playlists = listOf(Playlist(id = "xyz", name = "Mix", tracks = listOf(track("b")))),
            trackmap = mapOf("a" to track("a"), "b" to track("b"), "unused" to track("unused")),
        )
        val (_, snap) = SyncMerge.snapshot("Phone", local)
        assertEquals(setOf("a", "b"), snap.trackmap.keys)
        assertEquals("ids are stripped on the wire", "", snap.playlists[0].id)
    }
}
