package com.awitize.app.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.awitize.app.Container
import com.awitize.app.data.LibraryStore
import com.awitize.app.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// UI-facing playback facade. Talks to PlaybackService through a MediaController
// so the notification/lock-screen controls and the in-app UI stay in lockstep.
class PlaybackController(
    private val context: Context,
    private val container: Container,
) {
    private val store: LibraryStore = container.store
    private val resolver = container.resolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var controller: MediaController? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeat: StateFlow<Int> = _repeat.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // non-fatal, transient messages ("Reconnecting…", "Skipped …") — the UI
    // shows these as toasts rather than error banners
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val current: StateFlow<Track?> = combine(_queue, _index) { q, i -> q.getOrNull(i) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private var lastErrorShownAt = 0L
    private var consecutiveErrors = 0
    private var pendingPlay = false

    // how many times the current item has been re-resolved after a playback
    // error — a stale YouTube URL usually works again with a fresh one
    private val retries = HashMap<String, Int>()

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                try {
                    val c = future.get()
                    controller = c
                    attach(c)
                    _connected.value = true
                } catch (_: Exception) {
                    _error.value = "The playback engine couldn't start. Try reopening the app."
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        scope.launch {
            while (true) {
                delay(500)
                val c = controller ?: continue
                _position.value = c.currentPosition.coerceAtLeast(0)
                val d = c.duration
                _duration.value = if (d > 0) d else 0
            }
        }
    }

    private fun attach(c: MediaController) {
        c.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playing.value = isPlaying
                if (isPlaying) {
                    consecutiveErrors = 0
                    retries.remove(c.currentMediaItem?.mediaId)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _buffering.value = playbackState == Player.STATE_BUFFERING
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = c.currentMediaItemIndex
                _index.value = idx
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    _queue.value.getOrNull(idx)?.let { store.notePlay(it) }
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _shuffle.value = shuffleModeEnabled
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _repeat.value = repeatMode
            }

            override fun onPlayerError(error: PlaybackException) {
                consecutiveErrors += 1
                // A failed track is usually a stale URL (403/410) — before
                // skipping anything, drop the cached URL, rebuild the item and
                // resume from where we were. Only skip once retries run out.
                val item = c.currentMediaItem
                val videoId = item?.mediaId
                val idx = c.currentMediaItemIndex
                val track = _queue.value.getOrNull(idx)
                if (videoId != null && track?.videoId == videoId && item.localConfiguration?.uri?.scheme == "awitize" && container.downloads.file(videoId) == null) {
                    val used = retries[videoId] ?: 0
                    if (used < 2 && idx in 0 until c.mediaItemCount) {
                        retries[videoId] = used + 1
                        resolver.invalidate(videoId)
                        val resume = c.currentPosition.coerceAtLeast(0L)
                        _notice.value = "Reconnecting…"
                        c.replaceMediaItem(idx, toMediaItem(track))
                        c.seekTo(idx, resume)
                        c.prepare()
                        c.play()
                        return
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastErrorShownAt > 5000) {
                    lastErrorShownAt = now
                    _error.value = friendlyError(error)
                }
                if (consecutiveErrors <= 5 && c.mediaItemCount > 1) {
                    scope.launch {
                        delay(1200)
                        controller?.seekToNextMediaItem()
                    }
                }
            }
        })

        // queue set before the controller finished connecting (cold start tap)
        if (_queue.value.isNotEmpty() && c.mediaItemCount == 0) {
            val items = _queue.value.map { toMediaItem(it) }
            c.setMediaItems(items, _index.value.coerceAtLeast(0), 0)
            c.prepare()
            if (pendingPlay) c.play()
        }
        pendingPlay = false
    }

    fun clearError() {
        _error.value = null
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun setQueue(tracks: List<Track>, startIndex: Int, play: Boolean, shuffleOn: Boolean = false) {
        val playable = tracks.filter { it.playable }
        if (playable.isEmpty()) return
        val start = startIndex.coerceIn(0, playable.lastIndex)
        _queue.value = playable
        _index.value = start
        val c = controller ?: run {
            pendingPlay = play
            return
        }
        c.setMediaItems(playable.map { toMediaItem(it) }, start, 0)
        c.shuffleModeEnabled = shuffleOn
        c.prepare()
        if (play) c.play()
    }

    fun playTrack(track: Track) {
        setQueue(listOf(track), 0, true)
        controller?.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun toMediaItem(t: Track): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(t.title.ifBlank { "Unknown track" })
            .setArtist(t.subtitle())
            .setAlbumTitle(t.album)
            .apply { t.artwork?.let { setArtworkUri(it.toUri()) } }
            .build()
        return MediaItem.Builder()
            .setMediaId(t.videoId ?: t.title)
            .setUri("awitize://stream/${t.videoId}")
            .setMediaMetadata(meta)
            .build()
    }

    private fun friendlyError(e: PlaybackException): String {
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            -> "Couldn't fetch this track — skipping."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> "No connection to YouTube Music right now."
            else -> "This track wouldn't play — skipping."
        }
    }
}
