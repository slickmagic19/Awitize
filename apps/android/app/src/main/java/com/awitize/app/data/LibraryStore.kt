package com.awitize.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Single-file library persistence (atomic tmp+rename). Mirrors the desktop
// data model 1:1 so phone sync is a straight union. Caps match the desktop:
// 300 remembered tracks, 120 recent plays.
class LibraryStore(
    private val file: java.io.File,
    private val scope: CoroutineScope,
) {
    companion object {
        const val TRACK_CAP = 300
        const val PLAY_CAP = 120
    }

    private val _liked = MutableStateFlow<List<String>>(emptyList())
    val liked: StateFlow<List<String>> = _liked.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _plays = MutableStateFlow<List<PlayEvent>>(emptyList())
    val plays: StateFlow<List<PlayEvent>> = _plays.asStateFlow()

    private val _trackmap = MutableStateFlow<Map<String, Track>>(emptyMap())
    val trackmap: StateFlow<Map<String, Track>> = _trackmap.asStateFlow()

    private val mutex = Mutex()
    @Volatile private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        try {
            if (file.exists()) {
                val data = SyncJson.decodeFromString(LibraryFile.serializer(), file.readText())
                _liked.value = data.liked
                _playlists.value = data.playlists
                _plays.value = data.plays
                _trackmap.value = data.trackmap
            }
        } catch (_: Exception) {
            // corrupted file — keep the backup and start fresh rather than crash
            try { file.renameTo(java.io.File(file.parentFile, "library.corrupt.json")) } catch (_: Exception) {}
        }
    }

    fun snapshot(): LibraryFile = LibraryFile(
        liked = _liked.value,
        playlists = _playlists.value,
        plays = _plays.value,
        trackmap = _trackmap.value,
    )

    private fun persist() {
        val data = snapshot()
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val tmp = java.io.File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(SyncJson.encodeToString(LibraryFile.serializer(), data))
                    if (!tmp.renameTo(file)) {
                        file.writeText(tmp.readText())
                        tmp.delete()
                    }
                } catch (_: Exception) { /* next mutation retries */ }
            }
        }
    }

    fun apply(next: LibraryFile) {
        _liked.value = next.liked
        _playlists.value = next.playlists
        _plays.value = next.plays
        _trackmap.value = next.trackmap
        persist()
    }

    fun remember(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val map = LinkedHashMap(_trackmap.value)
        for (t in tracks) {
            val id = t.videoId ?: continue
            if (map[id]?.artwork == null || map[id]?.title.isNullOrEmpty()) map[id] = t
        }
        while (map.size > TRACK_CAP) {
            val first = map.keys.firstOrNull() ?: break
            map.remove(first)
        }
        _trackmap.value = map
        persist()
    }

    fun notePlay(track: Track) {
        val id = track.videoId ?: return
        remember(listOf(track))
        val now = System.currentTimeMillis()
        val hit = _plays.value.find { it.id == id }
        val next = if (hit != null) {
            listOf(hit.copy(ts = now, n = hit.n + 1)) + _plays.value.filter { it.id != id }
        } else {
            listOf(PlayEvent(id, now, 1)) + _plays.value
        }
        _plays.value = next.take(PLAY_CAP)
        persist()
    }

    fun toggleLike(track: Track) {
        val id = track.videoId ?: return
        if (id in _liked.value) {
            _liked.value = _liked.value - id
        } else {
            remember(listOf(track))
            _liked.value = _liked.value + id
        }
        persist()
    }

    fun addPlaylist(name: String): Playlist {
        val clean = name.trim().ifEmpty { "New playlist" }
        val pl = Playlist(id = java.util.UUID.randomUUID().toString(), name = clean)
        _playlists.value = _playlists.value + pl
        persist()
        return pl
    }

    fun addPlaylistWithTracks(name: String, tracks: List<Track>): Playlist {
        val clean = name.trim().ifEmpty { "New playlist" }
        val items = tracks.filter { !it.videoId.isNullOrEmpty() }.distinctBy { it.videoId }
        val pl = Playlist(id = java.util.UUID.randomUUID().toString(), name = clean, tracks = items)
        _playlists.value = _playlists.value + pl
        persist()
        return pl
    }

    fun renamePlaylist(id: String, name: String) {
        val clean = name.trim().ifEmpty { return }
        _playlists.value = _playlists.value.map { if (it.id == id) it.copy(name = clean) else it }
        persist()
    }

    fun deletePlaylist(id: String) {
        _playlists.value = _playlists.value.filterNot { it.id == id }
        persist()
    }

    fun addToPlaylist(playlistId: String, track: Track) {
        val id = track.videoId ?: return
        remember(listOf(track))
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id != playlistId) pl
            else if (pl.tracks.any { it.videoId == id }) pl
            else pl.copy(tracks = pl.tracks + track)
        }
        persist()
    }

    fun removeFromPlaylist(playlistId: String, videoId: String) {
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id != playlistId) pl else pl.copy(tracks = pl.tracks.filterNot { it.videoId == videoId })
        }
        persist()
    }
}
