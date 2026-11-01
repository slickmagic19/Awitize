package com.awitize.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.awitize.app.data.Track

@Composable
fun ExploreScreen(ui: UiRefs) {
    val pages = ui.states.pages
    var data by remember { mutableStateOf(pages.get("explore") as? com.awitize.app.data.ExploreData) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(data == null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        if (attempt == 0 && pages.get("explore") != null) return@LaunchedEffect
        loading = true
        error = null
        try {
            val loaded = ui.container.innerTube.explore()
            pages.put("explore", loaded)
            data = loaded
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load Explore"
        }
        loading = false
    }

    val d = data
    when {
        d != null -> ExploreContent(ui, d)
        loading -> Column(Modifier.fillMaxSize()) {
            Text(
                "Explore",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AwBrandSoft)
            }
        }
        else -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Explore couldn't load", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(error ?: "", style = MaterialTheme.typography.bodyMedium, color = AwMuted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { attempt += 1 }) { Text("Try again", color = AwBrandSoft) }
        }
    }
}

@Composable
private fun ExploreContent(ui: UiRefs, data: com.awitize.app.data.ExploreData) {
    val trending = remember(data) { data.trending.filter { it.playable } }
    val empty = data.newReleases.isEmpty() && data.moods.isEmpty() && trending.isEmpty() &&
        data.newVideos.isEmpty() && data.chartArtists.isEmpty()

    LazyColumn(Modifier.fillMaxSize(), state = ui.states.pages.listState("explore"), contentPadding =  PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                "Explore",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
            )
        }

        if (empty) {
            item {
                EmptyState(
                    Icons.Rounded.Explore,
                    "Nothing to explore yet",
                    "Pull a fresh connection and try again in a moment.",
                )
            }
        }

        if (data.moods.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(data.moods) { m ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(AwSurfaceHi)
                                .clickable { ui.open(Screen.Mood(m.title, m.params)) }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                        ) {
                            Text(m.title, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        if (data.newReleases.isNotEmpty()) {
            item { SectionHeader("New releases") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(data.newReleases) { t ->
                        PlaylistCard(
                            title = t.title.ifBlank { "Unknown" },
                            subtitle = t.subtitle().ifBlank { null },
                            artwork = t.artwork,
                            onClick = { openItem(ui, t) },
                        )
                    }
                }
            }
        }

        if (trending.isNotEmpty()) {
            item { SectionHeader("Trending") }
            itemsIndexed(trending) { i, t ->
                SongRow(t, ui, onClick = { ui.playTracks(trending, i) })
            }
        }

        if (data.newVideos.isNotEmpty()) {
            item { SectionHeader("New videos") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(data.newVideos) { t ->
                        PlaylistCard(
                            title = t.title.ifBlank { "Unknown" },
                            subtitle = t.subtitle().ifBlank { null },
                            artwork = t.artwork,
                            onClick = { openItem(ui, t) },
                        )
                    }
                }
            }
        }

        if (data.chartArtists.isNotEmpty()) {
            item { SectionHeader("Top artists") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(data.chartArtists) { t ->
                        ArtistCircleCard(
                            title = t.title.ifBlank { "Unknown" },
                            subtitle = t.views,
                            artwork = t.artwork,
                            onClick = { openItem(ui, t) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistCircleCard(title: String, subtitle: String?, artwork: String?, onClick: () -> Unit) {
    Column(
        Modifier.width(118.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(artwork, Modifier.width(112.dp).aspectRatio(1f), corner = 56.dp, iconSize = 30.dp)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AwMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun MoodScreen(ui: UiRefs, title: String, params: String) {
    val pages = ui.states.pages
    val key = "mood:$params"
    var items by remember { mutableStateOf(pages.get(key) as? List<Track>) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val grid = ui.states.pages.gridState(key)

    LaunchedEffect(params, attempt) {
        if (attempt == 0 && pages.get(key) != null) return@LaunchedEffect
        items = null
        error = null
        try {
            val loaded = ui.container.innerTube.moodPlaylists(params)
            pages.put(key, loaded)
            items = loaded
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this mood"
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailTopBar(ui)
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
        )
        val list = items
        when {
            list != null -> if (list.isEmpty()) {
                EmptyState(Icons.Rounded.Explore, "No playlists here yet", "This shelf is empty right now — try another mood.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = grid,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(list) { t ->
                        Column(Modifier.fillMaxWidth().clickable { openItem(ui, t) }) {
                            Artwork(t.artwork, Modifier.fillMaxWidth().aspectRatio(1f), corner = 14.dp, iconSize = 34.dp)
                            Spacer(Modifier.height(7.dp))
                            Text(t.title.ifBlank { "Unknown" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val sub = t.views ?: t.subtitle().ifBlank { null }
                            if (sub != null) {
                                Text(sub, style = MaterialTheme.typography.bodySmall, color = AwMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            error != null -> Column(
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
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AwBrandSoft)
            }
        }
    }
}
