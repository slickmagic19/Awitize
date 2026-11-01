package com.awitize.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.awitize.app.data.DlStatus
import com.awitize.app.data.Track
import kotlin.math.roundToInt

fun fmtMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun fmtBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

fun parseDurationSec(s: String?): Double? {
    if (s.isNullOrBlank()) return null
    val parts = s.trim().split(":")
    if (parts.size !in 2..3) return null
    val nums = parts.map { it.trim().toIntOrNull() ?: return null }
    return when (nums.size) {
        2 -> nums[0] * 60.0 + nums[1]
        else -> nums[0] * 3600.0 + nums[1] * 60.0 + nums[2]
    }
}

@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 10.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(AwSurfaceHi),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Icon(Icons.Rounded.MusicNote, null, tint = AwMuted, modifier = Modifier.size(iconSize))
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = AwMuted, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(message, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = AwMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun DetailActions(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    saveLabel: String? = null,
    onSave: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledIconButton(
            onClick = onPlay,
            modifier = Modifier.size(58.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = AwBrand, contentColor = AwText),
        ) {
            Icon(Icons.Rounded.PlayArrow, "Play", modifier = Modifier.size(32.dp))
        }
        FilledTonalIconButton(
            onClick = onShuffle,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = AwSurfaceHi, contentColor = AwText),
        ) {
            Icon(Icons.Rounded.Shuffle, "Shuffle")
        }
        if (onSave != null && saveLabel != null) {
            TextButton(onClick = onSave) { Text(saveLabel, color = AwBrandSoft) }
        }
    }
}

@Composable
fun SongRow(
    track: Track,
    ui: UiRefs,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    showArtwork: Boolean = true,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "Remove",
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArtwork) {
            Artwork(track.artwork, Modifier.size(52.dp), corner = 9.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title.ifBlank { "Unknown title" },
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (ui.player.current.collectAsState().value?.videoId == track.videoId && track.videoId != null) AwBrandSoft else AwText,
            )
            val sub = listOfNotNull(track.subtitle().takeIf { it.isNotBlank() }, track.album?.takeIf { it != track.subtitle() }).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = AwMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke() ?: track.duration?.let {
            Text(it, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = AwMuted, modifier = Modifier.padding(start = 8.dp))
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.MoreVert, "More options", tint = AwMuted)
            }
            TrackMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                track = track,
                ui = ui,
                onRemove = onRemove,
                removeLabel = removeLabel,
            )
        }
    }
}

@Composable
fun TrackMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    track: Track,
    ui: UiRefs,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "Remove",
) {
    val likedIds by ui.container.store.liked.collectAsState()
    val liked = track.videoId != null && track.videoId in likedIds
    val downloads = ui.container.downloads
    val dlItems by downloads.items.collectAsState()
    val dlTasks by downloads.tasks.collectAsState()
    val downloaded = track.videoId?.let { dlItems.containsKey(it) } == true
    val dl = track.videoId?.let { dlTasks[it] }
    var showAdd by remember { mutableStateOf(false) }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (track.playable) {
            MenuItem("Play now", Icons.Rounded.PlayArrow) {
                onDismiss()
                ui.playTracks(listOf(track), 0)
            }
        }
        if (track.videoId != null) {
            MenuItem(if (liked) "Remove from Liked" else "Add to Liked", if (liked) Icons.Rounded.FavoriteBorder else Icons.Rounded.Favorite) {
                ui.container.store.toggleLike(track)
                onDismiss()
            }
        }
        MenuItem("Add to playlist", Icons.Rounded.PlaylistAdd) {
            showAdd = true
        }
        if (track.albumId != null) {
            MenuItem("Go to album", Icons.Rounded.Album) {
                onDismiss()
                ui.open(Screen.Album(track.albumId))
            }
        }
        if (track.artistIds.isNotEmpty()) {
            MenuItem("Go to artist", Icons.Rounded.Person) {
                onDismiss()
                ui.open(Screen.Artist(track.artistIds.first()))
            }
        }
        if (track.videoId != null) {
            val id = track.videoId
            when {
                downloaded -> MenuItem("Remove download", Icons.Rounded.Delete) {
                    onDismiss()
                    downloads.delete(id)
                    ui.snack("Removed download")
                }
                dl != null -> {
                    val pct = (dl.progress * 100).roundToInt()
                    val info = when (dl.status) {
                        DlStatus.ACTIVE -> if (dl.indeterminate) "Downloading…" else "Downloading $pct%"
                        DlStatus.PAUSED -> if (dl.indeterminate) "Paused" else "Paused · $pct%"
                        DlStatus.FAILED -> "Download failed"
                    }
                    MenuItem(info, Icons.Rounded.Download, enabled = false) {}
                    when (dl.status) {
                        DlStatus.ACTIVE -> MenuItem("Pause download", Icons.Rounded.Pause) {
                            onDismiss()
                            downloads.pause(id)
                        }
                        DlStatus.PAUSED -> MenuItem("Resume download", Icons.Rounded.PlayArrow) {
                            onDismiss()
                            downloads.resume(id)
                        }
                        DlStatus.FAILED -> MenuItem("Retry download", Icons.Rounded.Refresh) {
                            onDismiss()
                            downloads.resume(id)
                        }
                    }
                    MenuItem("Cancel download", Icons.Rounded.Close) {
                        onDismiss()
                        downloads.cancel(id)
                    }
                }
                else -> MenuItem("Download", Icons.Rounded.Download) {
                    onDismiss()
                    downloads.start(track)
                }
            }
        }
        if (onRemove != null) {
            MenuItem(removeLabel, Icons.Rounded.Delete) {
                onDismiss()
                onRemove()
            }
        }
    }

    if (showAdd) {
        AddToPlaylistDialog(track = track, ui = ui) { showAdd = false }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, null, tint = AwMuted) },
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
fun AddToPlaylistDialog(track: Track, ui: UiRefs, onDismiss: () -> Unit) {
    val playlists by ui.container.store.playlists.collectAsState()
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AwSurfaceHi,
        title = { Text("Add to playlist") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("New playlist") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = newName.isNotBlank(),
                        onClick = {
                            val pl = ui.container.store.addPlaylist(newName)
                            ui.container.store.addToPlaylist(pl.id, track)
                            ui.snack("Added to ${pl.name}")
                            onDismiss()
                        },
                    ) { Text("Create") }
                }
                Spacer(Modifier.height(8.dp))
                playlists.forEach { pl ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                ui.container.store.addToPlaylist(pl.id, track)
                                ui.snack("Added to ${pl.name}")
                                onDismiss()
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.QueueMusic, null, tint = AwMuted)
                        Spacer(Modifier.width(12.dp))
                        Text(pl.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${pl.tracks.size}", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = AwMuted)
                    }
                }
                if (playlists.isEmpty()) {
                    Text(
                        "No playlists yet — name one above.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = AwMuted,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
fun LikeButton(track: Track, ui: UiRefs, size: Dp = 24.dp) {
    val likedIds by ui.container.store.liked.collectAsState()
    val liked = track.videoId != null && track.videoId in likedIds
    val enabled = track.videoId != null
    IconButton(enabled = enabled, onClick = { ui.container.store.toggleLike(track) }) {
        Icon(
            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = if (liked) "Remove from Liked" else "Add to Liked",
            tint = if (liked) AwBrandSoft else AwMuted,
            modifier = Modifier.size(size),
        )
    }
}

@Composable
fun PlaylistCard(
    title: String,
    subtitle: String?,
    artwork: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menu: (@Composable () -> Unit)? = null,
) {
    Column(modifier.width(148.dp).clickable(onClick = onClick)) {
        Box {
            Artwork(artwork, Modifier.size(148.dp), corner = 14.dp, iconSize = 34.dp)
            if (menu != null) {
                Box(Modifier.align(Alignment.TopEnd)) { menu() }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = AwMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun QuickTile(
    title: String,
    subtitle: String,
    artwork: String?,
    gradient: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .width(200.dp)
            .height(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (gradient) Brush.linearGradient(AwGradient) else Brush.linearGradient(listOf(AwSurfaceHi, AwSurfaceHi)))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = AwText)
            Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = if (gradient) AwText.copy(alpha = 0.85f) else AwMuted, maxLines = 1)
        }
        if (artwork != null) {
            Artwork(artwork, Modifier.size(56.dp).align(Alignment.TopEnd).padding(0.dp), corner = 10.dp, iconSize = 20.dp)
        }
    }
}

@Composable
fun MiniPlayer(ui: UiRefs) {
    val track by ui.player.current.collectAsState()
    val playing by ui.player.playing.collectAsState()
    val buffering by ui.player.buffering.collectAsState()
    val position by ui.player.position.collectAsState()
    val duration by ui.player.duration.collectAsState()
    val t = track ?: return

    Column(
        Modifier
            .fillMaxWidth()
            .background(AwSurfaceHi)
            .clickable { ui.openPlayer() },
    ) {
        LinearProgressIndicator(
            progress = { if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = AwBrandSoft,
            trackColor = AwSurfaceHi,
        )
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(t.artwork, Modifier.size(44.dp), corner = 8.dp, iconSize = 18.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title.ifBlank { "Unknown track" }, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.subtitle(), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = AwMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { ui.player.togglePlayPause() }) {
                if (buffering) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = AwBrandSoft)
                } else {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = AwText)
                }
            }
            IconButton(onClick = { ui.player.next() }) {
                Icon(Icons.Rounded.SkipNext, "Next", tint = AwText)
            }
        }
    }
}

@Composable
fun CreatePlaylistDialog(ui: UiRefs, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AwSurfaceHi,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Playlist name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val pl = ui.container.store.addPlaylist(name)
                    onCreated(pl.id)
                    onDismiss()
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RenamePlaylistDialog(initial: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AwSurfaceHi,
        title = { Text("Rename playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onRename(name); onDismiss() }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PlusIcon(description: String) {
    Icon(Icons.Rounded.Add, description, tint = AwMuted)
}

@Composable
fun LibraryIcon() {
    Icon(Icons.Rounded.LibraryMusic, null, tint = AwMuted)
}

@Composable
fun DownloadDoneIcon() {
    Icon(Icons.Rounded.DownloadDone, null, tint = AwBrandSoft)
}
