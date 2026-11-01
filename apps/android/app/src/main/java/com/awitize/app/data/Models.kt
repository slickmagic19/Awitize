package com.awitize.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val videoId: String? = null,
    val browseId: String? = null,
    val playlistId: String? = null,
    val title: String = "",
    val artists: List<String> = emptyList(),
    val artistIds: List<String> = emptyList(),
    val album: String? = null,
    val albumId: String? = null,
    val year: String? = null,
    val views: String? = null,
    val artwork: String? = null,
    val duration: String? = null,
    val kind: String? = null, // song | video | album | artist | playlist | single | ep
) {
    val playable: Boolean get() = !videoId.isNullOrEmpty()
    fun subtitle(): String {
        val a = artists.joinToString(", ")
        return when {
            a.isNotEmpty() -> a
            album != null -> album
            kind != null -> kind.replaceFirstChar { it.uppercase() }
            else -> ""
        }
    }
}

@Serializable
data class Playlist(
    val id: String = "",
    val name: String,
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class PlayEvent(val id: String, val ts: Long, val n: Int)

// Wire shape identical to the desktop syncCore payload: liked[], plays[],
// playlists[] (no ids on the wire) and `tracks` keyed by videoId.
@Serializable
data class LibraryFile(
    val liked: List<String> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val plays: List<PlayEvent> = emptyList(),
    @SerialName("tracks") val trackmap: Map<String, Track> = emptyMap(),
)

@Serializable
data class SavedDownload(val track: Track, val bytes: Long, val at: Long, val fileName: String = "")

// Persisted spine of an in-flight download. Written when a download starts
// and updated as the size becomes known, it lets a download that was killed
// with the process come back as a resumable paused task instead of vanishing.
@Serializable
data class QueueEntry(val track: Track, val totalBytes: Long = 0, val at: Long = 0)

fun normName(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")
