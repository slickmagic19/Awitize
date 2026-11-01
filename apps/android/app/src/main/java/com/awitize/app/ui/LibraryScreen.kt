package com.awitize.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.awitize.app.data.DlStatus
import com.awitize.app.data.DownloadTask
import kotlin.math.roundToInt

private val LIB_TABS = listOf("Playlists", "Liked", "Downloads", "Recent")

@Composable
fun LibraryScreen(ui: UiRefs) {
    val store = ui.container.store
    val playlists by store.playlists.collectAsState()
    val likedIds by store.liked.collectAsState()
    val trackmap by store.trackmap.collectAsState()
    val plays by store.plays.collectAsState()
    val downloads by ui.container.downloads.items.collectAsState()
    val tasks by ui.container.downloads.tasks.collectAsState()
    val online by ui.container.net.online.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showCreate by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var themeOpen by remember { mutableStateOf(false) }

    val liked = remember(likedIds, trackmap) { likedIds.mapNotNull { trackmap[it] } }
    val recent = remember(plays, trackmap) { plays.mapNotNull { trackmap[it.id] } }
    val downloaded = remember(downloads) { downloads.values.map { it.track } }

    // offline: downloads are the only part of the library that can work
    LaunchedEffect(online) { if (!online) tab = 2 }
    val shownTab = if (online) tab else 2

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Your Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { themeOpen = true }) {
                Icon(Icons.Rounded.Palette, "Theme", tint = AwBrandSoft)
            }
            IconButton(onClick = { ui.openSync() }) {
                Icon(Icons.Rounded.QrCode2, "Sync with your PC", tint = AwBrandSoft)
            }
        }
        if (themeOpen) {
            ThemePickerDialog(ui.container, onDismiss = { themeOpen = false })
        }
        if (!online) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AwSurfaceHi)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CloudOff, null, tint = AwMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "You're offline — showing your downloads",
                    style = MaterialTheme.typography.labelMedium,
                    color = AwMuted,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val tabs = if (online) LIB_TABS else listOf("Downloads")
            tabs.forEachIndexed { i, label ->
                val selected = if (online) tab == i else true
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) AwText else AwMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = online) { tab = i }
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                )
            }
        }

        when (shownTab) {
            0 -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = ui.states.pages.gridState("library:playlists"),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(AwSurfaceHi)
                                .clickable { showCreate = true }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Add, null, tint = AwBrandSoft)
                            Spacer(Modifier.width(12.dp))
                            Text("New playlist", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    items(playlists, key = { it.id }) { pl ->
                        Box {
                            var menuOpen by remember { mutableStateOf(false) }
                            PlaylistCard(
                                title = pl.name,
                                subtitle = "${pl.tracks.size} songs",
                                artwork = pl.tracks.firstOrNull()?.artwork,
                                onClick = { ui.open(Screen.LocalPlaylist(pl.id)) },
                                menu = {
                                    Box {
                                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                                            Icon(Icons.Rounded.MoreVert, "Playlist options", tint = AwText)
                                        }
                                        DropdownMenu(menuOpen, { menuOpen = false }) {
                                            DropdownMenuItem(
                                                text = { Text("Rename") },
                                                onClick = { menuOpen = false; renameId = pl.id },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Delete") },
                                                onClick = { menuOpen = false; deleteId = pl.id },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            1 -> {
                if (liked.isEmpty()) {
                    EmptyState(Icons.Rounded.FavoriteBorder, "No liked songs yet", "Tap the heart on any song and it will land here.")
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = ui.states.pages.listState("library:liked"), contentPadding =  PaddingValues(bottom = 24.dp)) {
                        item {
                            DetailActions(
                                onPlay = { ui.playTracks(liked, 0) },
                                onShuffle = { ui.playTracks(liked, 0, shuffleOn = true) },
                            )
                        }
                        itemsIndexed(liked, key = { _, t -> t.videoId ?: t.title }) { i, t ->
                            SongRow(t, ui, onClick = { ui.playTracks(liked, i) })
                        }
                    }
                }
            }

            2 -> {
                val activeList = remember(tasks) { tasks.values.toList() }
                if (downloaded.isEmpty() && activeList.isEmpty()) {
                    EmptyState(
                        Icons.Rounded.Download,
                        "No downloads",
                        if (!online) {
                            "You're offline and nothing is downloaded yet. Go back online and download songs to listen anywhere."
                        } else {
                            "Use the ⋮ menu on a song and choose Download to keep it offline."
                        },
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = ui.states.pages.listState("library:downloads"), contentPadding =  PaddingValues(bottom = 24.dp)) {
                        if (activeList.isNotEmpty()) {
                            item(key = "dl-active-header") {
                                Text(
                                    if (activeList.any { it.status == DlStatus.ACTIVE }) "Downloading" else "Downloads",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
                                )
                            }
                            items(activeList, key = { "dl-active-${it.track.videoId ?: it.track.title}" }) { d ->
                                ActiveDownloadRow(
                                    d,
                                    onPause = { d.track.videoId?.let { ui.container.downloads.pause(it) } },
                                    onResume = { d.track.videoId?.let { ui.container.downloads.resume(it) } },
                                    onCancel = { d.track.videoId?.let { ui.container.downloads.cancel(it) } },
                                )
                            }
                        }
                        if (downloaded.isNotEmpty()) {
                            if (activeList.isNotEmpty()) {
                                item(key = "dl-completed-header") {
                                    Text(
                                        "Offline",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 2.dp),
                                    )
                                }
                            }
                            item(key = "dl-actions") {
                                DetailActions(
                                    onPlay = { ui.playTracks(downloaded, 0) },
                                    onShuffle = { ui.playTracks(downloaded, 0, shuffleOn = true) },
                                )
                            }
                            itemsIndexed(downloaded, key = { _, t -> t.videoId ?: t.title }) { i, t ->
                                SongRow(
                                    t,
                                    ui,
                                    onClick = { ui.playTracks(downloaded, i) },
                                    trailing = {
                                        val bytes = t.videoId?.let { id -> downloads[id]?.bytes } ?: 0L
                                        Text(
                                            fmtBytes(bytes),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = AwMuted,
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                if (recent.isEmpty()) {
                    EmptyState(Icons.Rounded.MusicNote, "Nothing played yet", "Songs you listen to will show up here.")
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = ui.states.pages.listState("library:recent"), contentPadding =  PaddingValues(bottom = 24.dp)) {
                        item {
                            DetailActions(
                                onPlay = { ui.playTracks(recent, 0) },
                                onShuffle = { ui.playTracks(recent, 0, shuffleOn = true) },
                            )
                        }
                        itemsIndexed(recent, key = { _, t -> t.videoId ?: t.title }) { i, t ->
                            SongRow(t, ui, onClick = { ui.playTracks(recent, i) })
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreatePlaylistDialog(ui, onDismiss = { showCreate = false }, onCreated = { ui.open(Screen.LocalPlaylist(it)) })
    }
    renameId?.let { id ->
        val pl = playlists.find { it.id == id }
        if (pl != null) {
            RenamePlaylistDialog(
                initial = pl.name,
                onDismiss = { renameId = null },
                onRename = { store.renamePlaylist(id, it) },
            )
        } else {
            renameId = null
        }
    }
    deleteId?.let { id ->
        val pl = playlists.find { it.id == id }
        if (pl != null) {
            AlertDialog(
                onDismissRequest = { deleteId = null },
                containerColor = AwSurfaceHi,
                title = { Text("Delete \"${pl.name}\"?") },
                text = { Text("The playlist disappears from this device. The songs themselves stay untouched — and sync never deletes anything, so nothing else is affected.") },
                confirmButton = {
                    TextButton(onClick = { store.deletePlaylist(id); deleteId = null }) { Text("Delete", color = AwDanger) }
                },
                dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } },
            )
        } else {
            deleteId = null
        }
    }
}

// One tracked download: title, artist, live progress and controls to pause,
// resume/retry or cancel it. Determinate bar once the size is known, spinning
// bar while the stream is still being resolved.
@Composable
private fun ActiveDownloadRow(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(
            task.track.title.ifBlank { "Unknown track" },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (task.track.subtitle().isNotBlank()) {
            Text(
                task.track.subtitle(),
                style = MaterialTheme.typography.bodySmall,
                color = AwMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (task.status == DlStatus.ACTIVE && task.indeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier.weight(1f),
                    color = AwBrandSoft,
                    trackColor = AwSurfaceHi,
                )
            } else {
                LinearProgressIndicator(
                    progress = { task.progress.coerceAtLeast(0f) },
                    modifier = Modifier.weight(1f),
                    color = if (task.status == DlStatus.FAILED) AwDanger else AwBrandSoft,
                    trackColor = AwSurfaceHi,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    task.status == DlStatus.ACTIVE && task.indeterminate -> "…"
                    task.status == DlStatus.ACTIVE -> "${(task.progress * 100).roundToInt()}%"
                    task.status == DlStatus.PAUSED -> "Paused"
                    else -> "Failed"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (task.status) {
                    DlStatus.FAILED -> AwDanger
                    DlStatus.PAUSED -> AwMuted
                    DlStatus.ACTIVE -> AwBrandSoft
                },
            )
            Spacer(Modifier.width(4.dp))
            when (task.status) {
                DlStatus.ACTIVE -> IconButton(onClick = onPause, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.Pause, "Pause download", tint = AwBrandSoft, modifier = Modifier.size(20.dp))
                }
                DlStatus.PAUSED -> IconButton(onClick = onResume, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.PlayArrow, "Resume download", tint = AwBrandSoft, modifier = Modifier.size(22.dp))
                }
                DlStatus.FAILED -> IconButton(onClick = onResume, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.Refresh, "Retry download", tint = AwBrandSoft, modifier = Modifier.size(20.dp))
                }
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.Close, "Cancel download", tint = AwMuted, modifier = Modifier.size(18.dp))
            }
        }
        if (task.status == DlStatus.FAILED && task.error != null) {
            Text(
                task.error,
                style = MaterialTheme.typography.labelSmall,
                color = AwDanger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
