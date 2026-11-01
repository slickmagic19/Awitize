package com.awitize.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Calendar

@Composable
fun HomeScreen(ui: UiRefs) {
    val store = ui.container.store
    val plays by store.plays.collectAsState()
    val trackmap by store.trackmap.collectAsState()
    val playlists by store.playlists.collectAsState()
    val likedIds by store.liked.collectAsState()
    val downloads by ui.container.downloads.items.collectAsState()

    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when {
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }

    val recent = remember(plays, trackmap) { plays.mapNotNull { trackmap[it.id] } }
    val liked = remember(likedIds, trackmap) { likedIds.mapNotNull { trackmap[it] } }
    val downloadedTracks = remember(downloads) { downloads.values.map { it.track } }

    val isEmpty = recent.isEmpty() && liked.isEmpty() && playlists.isEmpty() && downloadedTracks.isEmpty()

    LazyColumn(Modifier.fillMaxSize(), state = ui.states.pages.listState("home"), contentPadding =  PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                greeting,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
            )
        }

        if (isEmpty) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    EmptyState(
                        Icons.Rounded.MusicNote,
                        "Welcome to Awitize",
                        "Search for songs, albums and artists — or scan the QR code from the desktop app to bring your library over.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { ui.goTab(2) }) { Text("Search music") }
                }
            }
        }

        if (liked.isNotEmpty() || recent.isNotEmpty() || downloadedTracks.isNotEmpty()) {
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (liked.isNotEmpty()) {
                        QuickTile(
                            title = "Liked songs",
                            subtitle = "${liked.size} songs",
                            artwork = liked.firstOrNull()?.artwork,
                            gradient = true,
                            onClick = { ui.open(Screen.Liked) },
                        )
                    }
                    if (recent.isNotEmpty()) {
                        QuickTile(
                            title = "Recently played",
                            subtitle = recent.firstOrNull()?.title.orEmpty(),
                            artwork = recent.firstOrNull()?.artwork,
                            gradient = false,
                            onClick = { ui.playTracks(recent, 0) },
                        )
                    }
                    if (downloadedTracks.isNotEmpty()) {
                        QuickTile(
                            title = "Offline",
                            subtitle = "${downloadedTracks.size} downloaded",
                            artwork = downloadedTracks.firstOrNull()?.artwork,
                            gradient = false,
                            onClick = { ui.playTracks(downloadedTracks, 0, shuffleOn = true) },
                        )
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            item {
                SectionHeader("Recently played", action = {
                    TextButton(onClick = { ui.playTracks(recent, 0) }) { Text("Play all", color = AwBrandSoft) }
                })
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent.take(14)) { t ->
                        val idx = recent.indexOf(t)
                        PlaylistCard(
                            title = t.title.ifBlank { "Unknown" },
                            subtitle = t.subtitle(),
                            artwork = t.artwork,
                            onClick = { ui.playTracks(recent, idx) },
                        )
                    }
                }
            }
        }

        if (playlists.isNotEmpty()) {
            item { SectionHeader("Your playlists") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(playlists) { pl ->
                        PlaylistCard(
                            title = pl.name,
                            subtitle = "${pl.tracks.size} songs",
                            artwork = pl.tracks.firstOrNull()?.artwork,
                            onClick = { ui.open(Screen.LocalPlaylist(pl.id)) },
                        )
                    }
                }
            }
        }

        if (liked.isNotEmpty()) {
            item {
                SectionHeader("Liked songs", action = {
                    TextButton(onClick = { ui.open(Screen.Liked) }) { Text("See all", color = AwBrandSoft) }
                })
            }
            itemsIndexed(liked.take(6)) { i, t ->
                SongRow(t, ui, onClick = { ui.playTracks(liked, i) })
            }
        }
    }
}
