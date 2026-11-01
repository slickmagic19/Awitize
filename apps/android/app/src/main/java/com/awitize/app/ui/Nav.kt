package com.awitize.app.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.awitize.app.data.Section

const val TAB_COUNT = 4

// What the app is showing, per bottom-bar tab. Every tab owns its own back
// stack, so opening an album from Search and then switching to Home and back
// lands you exactly where you left — and Back always walks that tab's history.
class NavState {
    var tab by mutableIntStateOf(0)

    private val stacks: List<SnapshotStateList<Screen>> = List(TAB_COUNT) { mutableStateListOf() }

    val current: SnapshotStateList<Screen> get() = stacks[tab]

    fun push(screen: Screen) {
        current.add(screen)
    }

    fun pop(): Boolean {
        val c = current
        if (c.isEmpty()) return false
        c.removeAt(c.lastIndex)
        return true
    }

    // tapping the already-selected tab pops it back to its root, like Android
    fun select(index: Int) {
        if (index == tab) current.clear() else tab = index
    }
}

// The Search tab is the root of its own stack, but opening a result pushes on
// top — so its query, results and filter must outlive the composable. Hoisted
// here so Back returns to the exact same results, instantly.
class SearchState {
    var query by mutableStateOf("")
    var filter by mutableStateOf<String?>(null)
    var sections by mutableStateOf<List<Section>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var attempt by mutableIntStateOf(0)
}

// Loaded pages (albums, artists, playlists, moods, explore) plus their scroll
// positions, keyed per screen. Returning to a page shows the same content at
// the same offset instead of a fresh spinner.
class PageCache {
    private val data = HashMap<String, Any>()
    private val scrolls = HashMap<String, LazyListState>()
    private val grids = HashMap<String, LazyGridState>()

    fun get(key: String): Any? = data[key]

    fun put(key: String, value: Any) {
        data[key] = value
    }

    @Composable
    fun listState(key: String): LazyListState = remember(key) {
        scrolls.getOrPut(key) { LazyListState() }
    }

    @Composable
    fun gridState(key: String): LazyGridState = remember(key) {
        grids.getOrPut(key) { LazyGridState() }
    }
}

class UiStateCache {
    val search = SearchState()
    val pages = PageCache()
}
