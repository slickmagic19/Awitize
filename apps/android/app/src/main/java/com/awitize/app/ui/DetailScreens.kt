package com.awitize.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.awitize.app.data.AlbumDetail
import com.awitize.app.data.normName

@Composable
fun DetailTopBar(ui: UiRefs, actions: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { ui.back() }) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = AwText)
        }
        Spacer(Modifier.weight(1f))
        actions?.invoke()
    }
}

// Loads a page once and remembers the result (plus its scroll position) in the
// page cache, so navigating back to an album or artist is instant and lands at
// the same offset instead of showing a spinner again.
@Composable
private fun <T : Any> Loaded(
    ui: UiRefs,
    key: String,
    load: suspend () -> T,
    content: @Composable (T) -> Unit,
) {
    val pages = ui.states.pages
    val cached = pages.get(key) as? T
    var data by remember(key) { mutableStateOf(cached) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var loading by remember(key) { mutableStateOf(cached == null) }
    var attempt by remember(key) { mutableIntStateOf(0) }

    LaunchedEffect(key, attempt) {
        if (attempt == 0 && pages.get(key) != null) return@LaunchedEffect
        loading = true
        error = null
        try {
            val loaded = load()
            pages.put(key, loaded)
            data = loaded
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this page"
        }
        loading = false
    }

    when {
        data != null -> content(data!!)
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AwBrandSoft)
        }
        else -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(error ?: "", style = MaterialTheme.typography.bodyMedium, color = AwMuted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { attempt += 1 }) { Text("Try again", color = AwBrandSoft) }
        }
    }
}

@Composable
private fun DetailHeader(
    artwork: String?,
    title: String,
    meta: String,
    circle: Boolean = false,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(artwork, Modifier.size(if (circle) 148.dp else 186.dp), corner = if (circle) 80.dp else 18.dp, iconSize = 48.dp)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(meta, style = MaterialTheme.typography.bodySmall, color = AwMuted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AlbumScreen(ui: UiRefs, browseId: String) {
    val key = "album:$browseId"
    Loaded(ui, key, load = { ui.container.innerTube.album(browseId) }) { album ->
        AlbumLikeContent(ui, album, saveLabel = null, onSave = null, scrollKey = key)
    }
}

@Composable
private fun AlbumLikeContent(
    ui: UiRefs,
    album: AlbumDetail,
    saveLabel: String?,
    onSave: (() -> Unit)?,
    scrollKey: String,
) {
    val playable = album.tracks.filter { it.playable }
    val meta = listOfNotNull(
        album.artists.joinToString(", ").takeIf { it.isNotBlank() },
        album.year,
        "${playable.size} songs",
    ).joinToString(" · ")

    LazyColumn(Modifier.fillMaxSize(), state = ui.states.pages.listState(scrollKey), contentPadding =  PaddingValues(bottom = 130.dp)) {
        item { DetailTopBar(ui) }
        item {
            DetailHeader(artwork = album.artwork, title = album.title, meta = meta)
            DetailActions(
                onPlay = { ui.playTracks(playable, 0) },
                onShuffle = { ui.playTracks(playable, 0, shuffleOn = true) },
                saveLabel = saveLabel,
                onSave = onSave,
            )
        }
        if (playable.isEmpty()) {
            item {
                EmptyState(Icons.Rounded.Album, "No playable songs", "Nothing here can be streamed right now.")
            }
        } else {
            itemsIndexed(playable, key = { _, t -> t.videoId ?: t.title }) { i, t ->
                SongRow(t, ui, onClick = { ui.playTracks(playable, i) })
            }
        }
    }
}

@Composable
fun ArtistScreen(ui: UiRefs, browseId: String) {
    val key = "artist:$browseId"
    Loaded(ui, key, load = { ui.container.innerTube.artist(browseId) }) { artist ->
        val songs = artist.songs.filter { it.playable }
        var showAll by remember { mutableStateOf(false) }
        val visible = if (showAll) songs else songs.take(8)
        val meta = buildString {
            append("${songs.size} songs")
            if (artist.albums.isNotEmpty()) append(" · ${artist.albums.size} releases")
        }

        LazyColumn(Modifier.fillMaxSize(), state = ui.states.pages.listState(key), contentPadding =  PaddingValues(bottom = 130.dp)) {
            item { DetailTopBar(ui) }
            item {
                DetailHeader(artwork = artist.artwork, title = artist.name, meta = meta, circle = true)
                DetailActions(
                    onPlay = { ui.playTracks(songs, 0) },
                    onShuffle = { ui.playTracks(songs, 0, shuffleOn = true) },
                )
            }
            if (songs.isNotEmpty()) {
                item { SectionHeader("Songs") }
                itemsIndexed(visible, key = { _, t -> t.videoId ?: t.title }) { i, t ->
                    SongRow(t, ui, onClick = { ui.playTracks(songs, i) })
                }
                if (songs.size > 8) {
                    item {
                        TextButton(
                            onClick = { showAll = !showAll },
                            modifier = Modifier.padding(start = 12.dp),
                        ) {
                            Text(if (showAll) "Show less" else "Show all ${songs.size} songs", color = AwBrandSoft)
                        }
                    }
                }
            }
            if (artist.albums.isNotEmpty()) {
                item { SectionHeader("Albums & singles") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(artist.albums) { a ->
                            PlaylistCard(
                                title = a.title.ifBlank { "Unknown" },
                                subtitle = a.year ?: a.kind?.replaceFirstChar { it.uppercase() },
                                artwork = a.artwork,
                                onClick = { a.browseId?.let { ui.open(Screen.Album(it)) } },
                            )
                        }
                    }
                }
            }
            if (songs.isEmpty() && artist.albums.isEmpty()) {
                item { EmptyState(Icons.Rounded.Person, "Nothing to show", "This artist page came back empty.") }
            }
        }
    }
}

@Composable
fun YtPlaylistScreen(ui: UiRefs, id: String) {
    val key = "ytpl:$id"
    Loaded(ui, key, load = { ui.container.innerTube.ytmPlaylist(id) }) { detail ->
        val playable = detail.tracks.filter { it.playable }
        val store = ui.container.store
        val playlists by store.playlists.collectAsState()
        val alreadySaved = playlists.any { normName(it.name) == normName(detail.title) }
        AlbumLikeContent(
            ui = ui,
            album = detail,
            saveLabel = if (alreadySaved) "In your library" else "Save to library",
            onSave = {
                if (alreadySaved) {
                    ui.snack("\"${detail.title}\" is already in your library")
                } else {
                    val pl = store.addPlaylistWithTracks(detail.title, detail.tracks)
                    ui.snack("Saved \"${pl.name}\" to your library")
                }
            },
            scrollKey = key,
        )
    }
}

@Composable
fun LocalPlaylistScreen(ui: UiRefs, id: String) {
    val store = ui.container.store
    val playlists by store.playlists.collectAsState()
    val pl = playlists.find { it.id == id }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    if (pl == null) {
        Column(Modifier.fillMaxSize()) {
            DetailTopBar(ui)
            EmptyState(Icons.Rounded.QueueMusic, "Playlist not found", "It may have been deleted.")
        }
        return
    }

    val playable = pl.tracks.filter { it.playable }
    LazyColumn(Modifier.fillMaxSize(), state = ui.states.pages.listState("localpl:${pl.id}"), contentPadding =  PaddingValues(bottom = 130.dp)) {
        item {
            DetailTopBar(ui, actions = {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, "Playlist options", tint = AwMuted)
                    }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; renameOpen = true })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; deleteOpen = true })
                    }
                }
            })
        }
        item {
            DetailHeader(
                artwork = pl.tracks.firstOrNull()?.artwork,
                title = pl.name,
                meta = "${pl.tracks.size} songs",
            )
            DetailActions(
                onPlay = { ui.playTracks(playable, 0) },
                onShuffle = { ui.playTracks(playable, 0, shuffleOn = true) },
            )
        }
        if (playable.isEmpty()) {
            item {
                EmptyState(
                    Icons.Rounded.QueueMusic,
                    "No songs yet",
                    "Use the ⋮ menu on any song and choose \"Add to playlist\".",
                )
            }
        } else {
            itemsIndexed(playable, key = { _, t -> t.videoId ?: t.title }) { i, t ->
                SongRow(
                    t,
                    ui,
                    onClick = { ui.playTracks(playable, i) },
                    onRemove = { t.videoId?.let { vid -> store.removeFromPlaylist(pl.id, vid) } },
                    removeLabel = "Remove from playlist",
                )
            }
        }
    }

    if (renameOpen) {
        RenamePlaylistDialog(
            initial = pl.name,
            onDismiss = { renameOpen = false },
            onRename = { store.renamePlaylist(pl.id, it) },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            containerColor = AwSurfaceHi,
            title = { Text("Delete \"${pl.name}\"?") },
            text = { Text("The playlist disappears from this device. The songs themselves stay untouched.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    store.deletePlaylist(pl.id)
                    ui.back()
                }) { Text("Delete", color = AwDanger) }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun LikedScreen(ui: UiRefs) {
    val store = ui.container.store
    val likedIds by store.liked.collectAsState()
    val trackmap by store.trackmap.collectAsState()
    val liked = remember(likedIds, trackmap) { likedIds.mapNotNull { trackmap[it] } }

    LazyColumn(Modifier.fillMaxSize(), state = ui.states.pages.listState("liked"), contentPadding =  PaddingValues(bottom = 130.dp)) {
        item { DetailTopBar(ui) }
        item {
            DetailHeader(
                artwork = liked.firstOrNull()?.artwork,
                title = "Liked songs",
                meta = "${liked.size} songs",
            )
            DetailActions(
                onPlay = { ui.playTracks(liked, 0) },
                onShuffle = { ui.playTracks(liked, 0, shuffleOn = true) },
            )
        }
        if (liked.isEmpty()) {
            item {
                EmptyState(Icons.Rounded.FavoriteBorder, "No liked songs yet", "Tap the heart on any song and it will land here.")
            }
        } else {
            itemsIndexed(liked, key = { _, t -> t.videoId ?: t.title }) { i, t ->
                SongRow(t, ui, onClick = { ui.playTracks(liked, i) })
            }
        }
    }
}
