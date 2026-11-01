package com.awitize.app.data

import kotlinx.serialization.json.Json

val SyncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

data class SyncStats(
    var likesAdded: Int = 0,
    var playlistsAdded: Int = 0,
    var playlistsMerged: Int = 0,
    var tracksAdded: Int = 0,
    var playsAdded: Int = 0,
) {
    val total: Int get() = likesAdded + playlistsAdded + playlistsMerged + tracksAdded + playsAdded
}

// Add-only merge — exact mirror of desktop syncCore.ts. Nothing local is ever
// removed or overwritten: likes union, playlists matched by normalized name
// (tracks union), plays take max count / newest timestamp, trackmap fills gaps
// only. Idempotent: merging the same payload twice changes nothing.
object SyncMerge {

    fun merge(local: LibraryFile, incoming: LibraryFile): Pair<LibraryFile, SyncStats> {
        val stats = SyncStats()

        val liked = LinkedHashSet(local.liked)
        for (id in incoming.liked) {
            if (id.isNotEmpty() && liked.add(id)) stats.likesAdded++
        }

        val trackmap = LinkedHashMap(local.trackmap)
        for ((id, t) in incoming.trackmap) {
            if (!trackmap.containsKey(id) && t.videoId == id) trackmap[id] = t
        }
        while (trackmap.size > LibraryStore.TRACK_CAP) {
            val first = trackmap.keys.firstOrNull() ?: break
            trackmap.remove(first)
        }

        val playlists = local.playlists.map { it.copy(tracks = it.tracks.toMutableList()) }.toMutableList()
        val byName = HashMap<String, Int>()
        playlists.forEachIndexed { i, pl -> byName.putIfAbsent(normName(pl.name), i) }

        for (inc in incoming.playlists) {
            val name = inc.name.trim()
            if (name.isEmpty()) continue
            val incTracks = inc.tracks.filter { !it.videoId.isNullOrEmpty() }
            val idx = byName[normName(name)]
            if (idx == null) {
                val tracks = mutableListOf<Track>()
                for (t in incTracks) {
                    if (tracks.none { it.videoId == t.videoId }) {
                        tracks.add(t)
                        stats.tracksAdded++
                    }
                }
                playlists.add(Playlist(id = java.util.UUID.randomUUID().toString(), name = name, tracks = tracks))
                byName[normName(name)] = playlists.size - 1
                stats.playlistsAdded++
            } else {
                val pl = playlists[idx]
                val have = pl.tracks.mapNotNull { it.videoId }.toHashSet()
                var added = 0
                val mergedTracks = pl.tracks.toMutableList()
                for (t in incTracks) {
                    val id = t.videoId ?: continue
                    if (have.add(id)) {
                        mergedTracks.add(t)
                        added++
                    }
                }
                if (added > 0) {
                    playlists[idx] = pl.copy(tracks = mergedTracks)
                    stats.playlistsMerged++
                    stats.tracksAdded += added
                }
            }
        }

        val playsMap = LinkedHashMap<String, PlayEvent>()
        for (e in local.plays) playsMap[e.id] = e
        for (inc in incoming.plays) {
            if (inc.id.isEmpty()) continue
            val n = maxOf(1, inc.n)
            val hit = playsMap[inc.id]
            if (hit == null) {
                playsMap[inc.id] = PlayEvent(inc.id, inc.ts, n)
                stats.playsAdded++
            } else {
                playsMap[inc.id] = hit.copy(n = maxOf(hit.n, n), ts = maxOf(hit.ts, inc.ts))
            }
        }
        val plays = playsMap.values.sortedByDescending { it.ts }.take(LibraryStore.PLAY_CAP)

        return LibraryFile(
            liked = liked.toList(),
            playlists = playlists,
            plays = plays,
            trackmap = trackmap,
        ) to stats
    }

    // Only ship tracks the receiving side actually needs (same trimming as
    // the desktop snapshot builder).
    fun snapshot(name: String, local: LibraryFile): Pair<String, LibraryFile> {
        val ids = LinkedHashSet<String>(local.liked)
        local.playlists.forEach { pl -> pl.tracks.forEach { t -> t.videoId?.let { ids.add(it) } } }
        local.plays.forEach { ids.add(it.id) }
        val tracks = LinkedHashMap<String, Track>()
        for (id in ids) local.trackmap[id]?.let { tracks[id] = it }
        val snap = LibraryFile(
            liked = local.liked,
            playlists = local.playlists.map { Playlist(id = "", name = it.name, tracks = it.tracks.filter { t -> !t.videoId.isNullOrEmpty() }) },
            plays = local.plays,
            trackmap = tracks,
        )
        return name to snap
    }
}
