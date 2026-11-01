package com.awitize.app.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val FILTERS = listOf(
    null to "All",
    "songs" to "Songs",
    "albums" to "Albums",
    "artists" to "Artists",
    "playlists" to "Playlists",
)

@Composable
fun SearchScreen(ui: UiRefs) {
    val st = ui.states.search
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = ui.states.pages.listState("search")

    LaunchedEffect(st.query, st.filter, st.attempt) {
        val q = st.query.trim()
        if (q.length < 2) {
            st.sections = emptyList()
            st.error = null
            st.loading = false
            return@LaunchedEffect
        }
        delay(350)
        st.loading = true
        st.error = null
        try {
            st.sections = ui.container.innerTube.search(q, st.filter)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            st.error = e.message ?: "Search failed"
            st.sections = emptyList()
        }
        st.loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Search",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
        )
        OutlinedTextField(
            value = st.query,
            onValueChange = { st.query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Songs, albums, artists…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = AwMuted) },
            trailingIcon = {
                if (st.query.isNotEmpty()) {
                    IconButton(onClick = { st.query = "" }) { Icon(Icons.Rounded.Clear, "Clear", tint = AwMuted) }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FILTERS.forEach { (value, label) ->
                FilterChip(
                    selected = st.filter == value,
                    onClick = { st.filter = if (st.filter == value) null else value },
                    label = { Text(label) },
                )
            }
        }

        when {
            st.query.trim().length < 2 -> {
                EmptyState(
                    Icons.Rounded.Search,
                    "Find your next favorite",
                    "Search across YouTube Music — songs, albums, artists and playlists.",
                )
            }
            st.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AwBrandSoft)
                }
            }
            st.error != null -> {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Search hit a snag", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(st.error ?: "", style = MaterialTheme.typography.bodyMedium, color = AwMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { st.attempt += 1 }) { Text("Try again", color = AwBrandSoft) }
                }
            }
            st.sections.isEmpty() -> {
                EmptyState(
                    Icons.Rounded.Search,
                    "No results",
                    "Nothing matched \"${st.query.trim()}\" — try a different spelling or filter.",
                )
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                    st.sections.forEach { sec ->
                        val first = sec.items.firstOrNull()
                        val asCards = first != null && first.kind in setOf("album", "artist", "playlist", "single", "ep", "audiobook")
                        if (!sec.title.isNullOrBlank()) {
                            item(key = "h-${sec.title}") { SectionHeader(sec.title) }
                        }
                        if (asCards) {
                            item(key = "c-${sec.title}") {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(sec.items) { t ->
                                        PlaylistCard(
                                            title = t.title.ifBlank { "Unknown" },
                                            subtitle = t.views ?: t.subtitle().ifBlank { null },
                                            artwork = t.artwork,
                                            onClick = { openItem(ui, t) },
                                        )
                                    }
                                }
                            }
                        } else {
                            val playable = sec.items.filter { it.playable }
                            itemsIndexed(playable) { i, t ->
                                SongRow(t, ui, onClick = { ui.playTracks(playable, i) })
                            }
                        }
                    }
                }
            }
        }
    }
}

fun openItem(ui: UiRefs, t: com.awitize.app.data.Track) {
    when (t.kind) {
        "album", "single", "ep" -> t.browseId?.let { ui.open(Screen.Album(it)) } ?: ui.playTracks(listOf(t), 0)
        "artist" -> t.browseId?.let { ui.open(Screen.Artist(it)) } ?: ui.snack("Couldn't open this artist")
        "playlist" -> {
            val id = t.playlistId ?: t.browseId?.removePrefix("VL")
            if (id != null) ui.open(Screen.YtPlaylist(id)) else ui.snack("Couldn't open this playlist")
        }
        else -> {
            if (t.playable) ui.playTracks(listOf(t), 0)
        }
    }
}
