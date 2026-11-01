package com.awitize.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.awitize.app.Container
import com.awitize.app.data.DlEvent
import com.awitize.app.data.Track
import com.awitize.app.player.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface Screen {
    data class Album(val browseId: String) : Screen
    data class Artist(val browseId: String) : Screen
    data class YtPlaylist(val id: String) : Screen
    data class LocalPlaylist(val id: String) : Screen
    data class Mood(val title: String, val params: String) : Screen
    data object Liked : Screen
}

class UiRefs(
    val container: Container,
    val player: PlaybackController,
    val scope: CoroutineScope,
    val snackbar: SnackbarHostState,
    val toasts: ToastState = ToastState(),
    val states: UiStateCache,
    private val onOpen: (Screen) -> Unit,
    private val onBack: () -> Unit,
    private val onOpenPlayer: () -> Unit,
    private val onSync: () -> Unit,
    private val onGoTab: (Int) -> Unit,
) {
    fun open(s: Screen) = onOpen(s)
    fun back() = onBack()
    fun openPlayer() = onOpenPlayer()
    fun openSync() = onSync()
    fun goTab(i: Int) = onGoTab(i)

    fun snack(msg: String) {
        scope.launch { snackbar.showSnackbar(msg, duration = SnackbarDuration.Short) }
    }

    fun toast(msg: String) = toasts.show(msg)

    fun playTracks(tracks: List<Track>, start: Int, shuffleOn: Boolean = false) {
        if (tracks.none { it.playable }) {
            snack("Nothing to play here yet")
            return
        }
        player.setQueue(tracks, start, play = true, shuffleOn = shuffleOn)
    }
}

@Composable
fun AwitizeApp(container: Container, player: PlaybackController) {
    val nav = remember { NavState() }
    val stateCache = remember { UiStateCache() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    var syncOpen by remember { mutableStateOf(false) }

    val ui = remember {
        UiRefs(
            container = container,
            player = player,
            scope = scope,
            snackbar = snackbar,
            states = stateCache,
            onOpen = { nav.push(it) },
            onBack = { nav.pop() },
            onOpenPlayer = { nowPlayingOpen = true },
            onSync = { syncOpen = true },
            onGoTab = { nav.select(it) },
        )
    }

    val playerError by player.error.collectAsState()
    LaunchedEffect(playerError) {
        val msg = playerError
        if (msg != null) {
            player.clearError()
            snackbar.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    // transient playback notices ("Reconnecting…") ride the toast layer
    val playerNotice by player.notice.collectAsState()
    LaunchedEffect(playerNotice) {
        val msg = playerNotice
        if (msg != null) {
            player.clearNotice()
            ui.toast(msg)
        }
    }

    // download lifecycle → toasts; the toast layer renders above Now Playing
    // and the sync flow, so these are visible wherever the user happens to be
    LaunchedEffect(Unit) {
        container.downloads.events.collect { ev ->
            when (ev) {
                is DlEvent.Started -> ui.toast("Downloading \"${shortTitle(ev.track.title)}\"")
                is DlEvent.Finished -> ui.toast("\"${shortTitle(ev.track.title)}\" saved offline")
                is DlEvent.Failed -> ui.toast("Download failed: ${ev.message}")
                is DlEvent.Paused -> ui.toast("Download paused — \"${shortTitle(ev.track.title)}\"")
                is DlEvent.Resumed -> ui.toast("Resuming \"${shortTitle(ev.track.title)}\"")
                is DlEvent.Cancelled -> ui.toast("Download cancelled")
            }
        }
    }

    BackHandler(enabled = nowPlayingOpen || syncOpen || nav.current.isNotEmpty() || nav.tab != 0) {
        when {
            nowPlayingOpen -> nowPlayingOpen = false
            syncOpen -> syncOpen = false
            nav.current.isNotEmpty() -> nav.pop()
            else -> nav.tab = 0
        }
    }

    Box(Modifier.fillMaxSize().background(AwBg)) {
        Scaffold(
            containerColor = AwBg,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!nowPlayingOpen && !syncOpen) {
                    Column {
                        MiniPlayer(ui)
                        NavigationBar(containerColor = AwSurface, tonalElevation = 0.dp) {
                            NavigationBarItem(
                                selected = nav.tab == 0,
                                onClick = { nav.select(0) },
                                icon = { Icon(Icons.Rounded.Home, "Home") },
                                label = { Text("Home") },
                            )
                            NavigationBarItem(
                                selected = nav.tab == 1,
                                onClick = { nav.select(1) },
                                icon = { Icon(Icons.Rounded.Explore, "Explore") },
                                label = { Text("Explore") },
                            )
                            NavigationBarItem(
                                selected = nav.tab == 2,
                                onClick = { nav.select(2) },
                                icon = { Icon(Icons.Rounded.Search, "Search") },
                                label = { Text("Search") },
                            )
                            NavigationBarItem(
                                selected = nav.tab == 3,
                                onClick = { nav.select(3) },
                                icon = { Icon(Icons.Rounded.LibraryMusic, "Library") },
                                label = { Text("Library") },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
                val top = nav.current.lastOrNull()
                when (top) {
                    null -> when (nav.tab) {
                        0 -> HomeScreen(ui)
                        1 -> ExploreScreen(ui)
                        2 -> SearchScreen(ui)
                        else -> LibraryScreen(ui)
                    }
                    is Screen.Album -> AlbumScreen(ui, top.browseId)
                    is Screen.Artist -> ArtistScreen(ui, top.browseId)
                    is Screen.YtPlaylist -> YtPlaylistScreen(ui, top.id)
                    is Screen.LocalPlaylist -> LocalPlaylistScreen(ui, top.id)
                    is Screen.Mood -> MoodScreen(ui, top.title, top.params)
                    Screen.Liked -> LikedScreen(ui)
                }
            }
        }

        if (nowPlayingOpen) {
            NowPlayingScreen(ui, onClose = { nowPlayingOpen = false })
        }
        if (syncOpen) {
            SyncFlow(ui, onClose = { syncOpen = false })
        }

        ToastHost(
            ui.toasts,
            Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(bottom = 10.dp),
        )
    }
}
