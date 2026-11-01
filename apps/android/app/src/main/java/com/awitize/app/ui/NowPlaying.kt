package com.awitize.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.awitize.app.data.DlStatus
import com.awitize.app.data.LyricsResult
import com.awitize.app.data.Track
import kotlin.math.roundToInt

@Composable
fun NowPlayingScreen(ui: UiRefs, onClose: () -> Unit) {
    val track by ui.player.current.collectAsState()
    val playing by ui.player.playing.collectAsState()
    val buffering by ui.player.buffering.collectAsState()
    val position by ui.player.position.collectAsState()
    val duration by ui.player.duration.collectAsState()
    val shuffleOn by ui.player.shuffle.collectAsState()
    val repeatMode by ui.player.repeat.collectAsState()
    val downloads by ui.container.downloads.items.collectAsState()
    val dlTasks by ui.container.downloads.tasks.collectAsState()

    val t = track
    LaunchedEffect(t) {
        if (t == null) onClose()
    }
    if (t == null) return

    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var showLyrics by remember { mutableStateOf(false) }

    val dur = duration
    val shownPos = if (dragging && dur > 0) (dragValue * dur).toLong() else position
    val frac = if (dragging) dragValue else if (dur > 0) (position.toFloat() / dur).coerceIn(0f, 1f) else 0f

    val downloaded = t.videoId?.let { downloads.containsKey(it) } == true
    val dlTask = t.videoId?.let { dlTasks[it] }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2A5C), AwBg.copy(alpha = 1f)))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Close", tint = AwText)
                }
                Spacer(Modifier.weight(1f))
                LikeButton(t, ui, size = 26.dp)
            }

            // artwork takes the leftover space; side is derived from BOTH
            // constraints so it can never inflate past the visible area
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight, 400.dp) * 0.96f
                Artwork(
                    t.artwork,
                    Modifier.size(side).shadow(20.dp, RoundedCornerShape(20.dp)),
                    corner = 20.dp,
                    iconSize = 64.dp,
                )
            }
            Spacer(Modifier.height(20.dp))

            Text(
                t.title.ifBlank { "Unknown track" },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (t.subtitle().isNotBlank()) {
                Text(
                    t.subtitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AwMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(4.dp))
            Slider(
                value = frac,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    if (dur > 0) ui.player.seekTo((dragValue * dur).toLong())
                    dragging = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = AwBrandSoft,
                    activeTrackColor = AwBrandSoft,
                    inactiveTrackColor = AwSurfaceHi,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtMs(shownPos), style = MaterialTheme.typography.labelMedium, color = AwMuted)
                Text(fmtMs(dur), style = MaterialTheme.typography.labelMedium, color = AwMuted)
            }

            Spacer(Modifier.height(2.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { ui.player.toggleShuffle() }) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (shuffleOn) AwBrandSoft else AwMuted)
                }
                IconButton(onClick = { ui.player.previous() }) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", tint = AwText, modifier = Modifier.size(34.dp))
                }
                FilledIconButton(
                    onClick = { ui.player.togglePlayPause() },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = AwText, contentColor = AwBg),
                ) {
                    if (buffering) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp, color = AwBg)
                    } else {
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (playing) "Pause" else "Play",
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                IconButton(onClick = { ui.player.next() }) {
                    Icon(Icons.Rounded.SkipNext, "Next", tint = AwText, modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = { ui.player.cycleRepeat() }) {
                    Icon(
                        if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Repeat",
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) AwBrandSoft else AwMuted,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showLyrics = true }) {
                    Icon(Icons.Rounded.Article, null, tint = AwMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Lyrics", color = AwMuted)
                }
                if (t.videoId != null) {
                    val id = t.videoId
                    when {
                        dlTask != null -> {
                            IconButton(
                                onClick = {
                                    if (dlTask.status == DlStatus.ACTIVE) {
                                        ui.container.downloads.pause(id)
                                    } else {
                                        ui.container.downloads.resume(id)
                                    }
                                },
                                modifier = Modifier.size(38.dp),
                            ) {
                                Icon(
                                    when (dlTask.status) {
                                        DlStatus.ACTIVE -> Icons.Rounded.Pause
                                        DlStatus.FAILED -> Icons.Rounded.Refresh
                                        else -> Icons.Rounded.PlayArrow
                                    },
                                    when (dlTask.status) {
                                        DlStatus.ACTIVE -> "Pause download"
                                        DlStatus.FAILED -> "Retry download"
                                        else -> "Resume download"
                                    },
                                    tint = AwBrandSoft,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (dlTask.status == DlStatus.ACTIVE) {
                                    if (dlTask.indeterminate) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = AwBrandSoft,
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            progress = { dlTask.progress },
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = AwBrandSoft,
                                        )
                                    }
                                    Spacer(Modifier.size(6.dp))
                                }
                                Text(
                                    when {
                                        dlTask.status == DlStatus.ACTIVE && dlTask.indeterminate -> "Saving…"
                                        dlTask.status == DlStatus.ACTIVE -> "Saving ${(dlTask.progress * 100).roundToInt()}%"
                                        dlTask.status == DlStatus.PAUSED && !dlTask.indeterminate -> "Paused ${(dlTask.progress * 100).roundToInt()}%"
                                        dlTask.status == DlStatus.PAUSED -> "Paused"
                                        else -> "Failed"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = when (dlTask.status) {
                                        DlStatus.ACTIVE -> AwBrandSoft
                                        DlStatus.FAILED -> AwDanger
                                        else -> AwMuted
                                    },
                                )
                            }
                            IconButton(
                                onClick = { ui.container.downloads.cancel(id) },
                                modifier = Modifier.size(38.dp),
                            ) {
                                Icon(Icons.Rounded.Close, "Cancel download", tint = AwMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                        downloaded -> TextButton(onClick = {
                            ui.container.downloads.delete(id)
                            ui.snack("Removed download")
                        }) {
                            Icon(Icons.Rounded.DownloadDone, null, tint = AwBrandSoft, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Saved", color = AwBrandSoft)
                        }
                        else -> TextButton(onClick = { ui.container.downloads.start(t) }) {
                            Icon(Icons.Rounded.Download, null, tint = AwMuted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Download", color = AwMuted)
                        }
                    }
                }
            }
            dlTask?.takeIf { it.status == DlStatus.FAILED }?.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = AwDanger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        if (showLyrics) {
            LyricsOverlay(ui, t, onClose = { showLyrics = false })
        }
    }
}

@Composable
private fun LyricsOverlay(ui: UiRefs, track: Track, onClose: () -> Unit) {
    var result by remember { mutableStateOf<LyricsResult?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(track.videoId) {
        loading = true
        val durSec = parseDurationSec(track.duration) ?: (ui.player.duration.value / 1000.0)
        result = try {
            ui.container.lyrics.fetch(track.title, track.artists, track.album, durSec)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        loading = false
    }

    val position by ui.player.position.collectAsState()
    val state = rememberLazyListState()

    val lines = result?.lines ?: emptyList()
    val activeIdx = if (result?.synced == true) {
        lines.indexOfLast { it.t <= position / 1000.0 }
    } else -1

    LaunchedEffect(activeIdx) {
        if (activeIdx > 1) {
            state.animateScrollToItem((activeIdx - 2).coerceAtLeast(0))
        }
    }

    Box(Modifier.fillMaxSize().background(AwBg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Close lyrics", tint = AwText)
                }
                Text("Lyrics", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                result?.source?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = AwMuted,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AwBrandSoft)
                }
                result == null -> EmptyState(
                    Icons.Rounded.Article,
                    "No lyrics found",
                    "We couldn't find lyrics for \"${track.title}\".",
                )
                else -> LazyColumn(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(lines) { i, line ->
                        val active = i == activeIdx
                        Text(
                            line.text,
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                active -> AwBrandSoft
                                result?.synced == true -> AwMuted
                                else -> AwText
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = result?.synced == true && line.t > 0) {
                                    ui.player.seekTo((line.t * 1000).toLong())
                                },
                        )
                    }
                }
            }
        }
    }
}
