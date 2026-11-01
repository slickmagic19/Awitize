package com.awitize.app.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class DlStatus { ACTIVE, PAUSED, FAILED }

// One tracked download. bytesDone/totalBytes are the truth; progress is 0f..1f
// once the size is known, negative while the stream is still being resolved.
data class DownloadTask(
    val track: Track,
    val status: DlStatus,
    val bytesDone: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
) {
    val progress: Float get() = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f) else -1f
    val indeterminate: Boolean get() = totalBytes <= 0
}

// Broadcast so any layer (or none at all) can surface toasts/notifications.
sealed interface DlEvent {
    data class Started(val track: Track) : DlEvent
    data class Finished(val track: Track) : DlEvent
    data class Failed(val track: Track, val message: String) : DlEvent
    data class Paused(val track: Track) : DlEvent
    data class Resumed(val track: Track) : DlEvent
    data class Cancelled(val track: Track) : DlEvent
}

private enum class Intent { NONE, PAUSE, CANCEL }

// Offline downloads: one progressive audio file per track. The writer streams
// into a .part file and resumes with HTTP Range requests, so pausing, network
// hiccups mid-transfer (connection resets near the end) and app restarts all
// continue from where they stopped instead of restarting or failing.
// A tiny queue file marks in-flight work so a killed process comes back as a
// resumable paused task instead of losing it.
class Downloads(
    context: Context,
    private val resolver: StreamResolver,
    private val http: OkHttpClient,
    private val online: () -> Boolean = { true },
) {
    private val dir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "downloads.json")
    private val queueFile = File(context.filesDir, "download_queue.json")
    private val indexSerializer = MapSerializer(String.serializer(), SavedDownload.serializer())
    private val queueSerializer = MapSerializer(String.serializer(), QueueEntry.serializer())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow<Map<String, SavedDownload>>(emptyMap())
    val items: StateFlow<Map<String, SavedDownload>> = _items.asStateFlow()

    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks.asStateFlow()

    private val _events = MutableSharedFlow<DlEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<DlEvent> = _events.asSharedFlow()

    private val jobs = HashMap<String, Job>()
    private val calls = HashMap<String, Call>()
    private val intents = HashMap<String, Intent>()
    private var queue: Map<String, QueueEntry> = emptyMap()

    init {
        load()
        restoreQueue()
        cleanOrphans()
    }

    private companion object {
        const val MAX_ATTEMPTS = 6
        const val FRESH_FILE_MS = 60_000L
    }

    // ---- index (completed downloads) ------------------------------------

    private fun load() {
        try {
            if (indexFile.exists()) {
                _items.value = SyncJson.decodeFromString(indexSerializer, indexFile.readText())
            }
        } catch (_: Exception) {
            // unreadable index — keep files on disk, rebuild lazily
        }
        val pruned = _items.value.filterValues { it.fileName.isNotEmpty() && File(dir, it.fileName).exists() }
        if (pruned.size != _items.value.size) {
            _items.value = pruned
            saveIndex()
        }
    }

    private fun saveIndex() {
        writeAtomic(indexFile, SyncJson.encodeToString(indexSerializer, _items.value))
    }

    // ---- queue (in-flight downloads) ------------------------------------

    private fun loadQueue() {
        queue = try {
            if (queueFile.exists()) SyncJson.decodeFromString(queueSerializer, queueFile.readText()) else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveQueue() {
        writeAtomic(queueFile, SyncJson.encodeToString(queueSerializer, queue))
    }

    private fun writeAtomic(target: File, text: String) {
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        } catch (_: Exception) { }
    }

    // Downloads interrupted by a kill (or a crash) come back as PAUSED tasks
    // backed by their .part file, so the user can resume with one tap.
    private fun restoreQueue() {
        loadQueue()
        val loaded = queue.size
        val keep = LinkedHashMap<String, QueueEntry>()
        val restored = LinkedHashMap<String, DownloadTask>()
        for ((id, entry) in queue) {
            val part = findPart(id)
            if (!_items.value.containsKey(id) && part != null && part.length() > 0) {
                restored[id] = DownloadTask(
                    track = entry.track,
                    status = DlStatus.PAUSED,
                    bytesDone = part.length(),
                    totalBytes = entry.totalBytes,
                )
                keep[id] = entry
            }
        }
        queue = keep
        if (restored.isNotEmpty()) _tasks.value = _tasks.value + restored
        if (keep.size != loaded) saveQueue()
    }

    private fun cleanOrphans() {
        try {
            val known = _items.value.values.map { it.fileName }.toHashSet()
            val queued = synchronized(this) { queue.keys.toSet() }
            dir.listFiles()?.forEach { f ->
                val orphan = if (f.name.endsWith(".part")) {
                    f.name.substringBefore('.') !in queued
                } else {
                    f.name !in known
                }
                // keep very fresh files — a download may be in flight right now
                if (orphan && System.currentTimeMillis() - f.lastModified() > FRESH_FILE_MS) f.delete()
            }
        } catch (_: Exception) { }
    }

    private fun findPart(id: String): File? = try {
        dir.listFiles()?.firstOrNull { it.name.startsWith("$id.") && it.name.endsWith(".part") }
    } catch (_: Exception) {
        null
    }

    // ---- public state ----------------------------------------------------

    fun entry(videoId: String): SavedDownload? = _items.value[videoId]

    fun file(videoId: String): File? = _items.value[videoId]?.let { e ->
        if (e.fileName.isEmpty()) null else File(dir, e.fileName).takeIf { it.exists() }
    }

    fun isDownloaded(videoId: String): Boolean = file(videoId) != null

    fun isActive(videoId: String): Boolean = _tasks.value.containsKey(videoId)

    // ---- controls ----------------------------------------------------------

    fun start(track: Track) {
        val id = track.videoId ?: return
        if (!online()) {
            _events.tryEmit(DlEvent.Failed(track, "You're offline"))
            return
        }
        synchronized(this) {
            if (_items.value.containsKey(id) || _tasks.value.containsKey(id)) return
            intents[id] = Intent.NONE
            queue = queue + (id to QueueEntry(track, 0, System.currentTimeMillis()))
            saveQueue()
            _tasks.value = _tasks.value + (id to DownloadTask(track, DlStatus.ACTIVE))
        }
        _events.tryEmit(DlEvent.Started(track))
        launchWorker(id, track)
    }

    fun pause(id: String) {
        val call: Call?
        synchronized(this) {
            val task = _tasks.value[id] ?: return
            if (task.status != DlStatus.ACTIVE) return
            intents[id] = Intent.PAUSE
            call = calls[id]
        }
        call?.cancel()
    }

    fun resume(id: String) {
        val track: Track
        synchronized(this) {
            val task = _tasks.value[id] ?: return
            if (task.status == DlStatus.ACTIVE || jobs.containsKey(id)) return
            if (!online()) {
                _events.tryEmit(DlEvent.Failed(task.track, "You're offline"))
                return
            }
            intents[id] = Intent.NONE
            _tasks.value = _tasks.value + (id to task.copy(status = DlStatus.ACTIVE, error = null))
            track = task.track
        }
        _events.tryEmit(DlEvent.Resumed(track))
        launchWorker(id, track)
    }

    fun cancel(id: String) {
        val track: Track
        val call: Call?
        val workerAlive: Boolean
        synchronized(this) {
            val task = _tasks.value[id] ?: return
            track = task.track
            call = calls[id]
            workerAlive = jobs.containsKey(id)
            if (workerAlive) intents[id] = Intent.CANCEL
        }
        if (workerAlive) {
            call?.cancel()
        } else {
            // paused/failed task with no worker — clean up right here
            finishCancel(id, track, findPart(id))
        }
    }

    fun delete(videoId: String) {
        val e = _items.value[videoId] ?: return
        try { if (e.fileName.isNotEmpty()) File(dir, e.fileName).delete() } catch (_: Exception) { }
        synchronized(this) {
            _items.value = _items.value - videoId
            saveIndex()
        }
    }

    // ---- worker --------------------------------------------------------------

    private fun launchWorker(id: String, track: Track) {
        // LAZY so the job cannot complete (and remove itself) before it is
        // registered — otherwise a fast failure could leave a stale job entry
        // that would block resume/cancel forever
        val job = scope.launch(start = CoroutineStart.LAZY) { runWorker(id, track) }
        synchronized(this) { jobs[id] = job }
        job.start()
    }

    private fun intent(id: String): Intent = synchronized(this) { intents[id] ?: Intent.NONE }

    private suspend fun runWorker(id: String, track: Track) {
        var attempt = 0
        var part: File? = null
        try {
            while (true) {
                synchronized(this) { if (!_tasks.value.containsKey(id)) return }
                when (intent(id)) {
                    Intent.CANCEL -> { finishCancel(id, track, part ?: findPart(id)); return }
                    Intent.PAUSE -> { finishPause(id, track, part ?: findPart(id)); return }
                    Intent.NONE -> {}
                }
                try {
                    // after a failure a fresh URL helps: cached ones may have expired
                    val rs = resolver.resolveBlocking(id, forceRefresh = attempt > 0)
                    val existing = findPart(id)
                    part = existing ?: File(dir, "$id.${rs.ext}.part")
                    var copied = part.length()
                    var total = queueTotal(id)
                    val req = Request.Builder().url(rs.url)
                    if (copied > 0) req.header("Range", "bytes=$copied-")
                    val call = http.newCall(req.build())
                    synchronized(this) { calls[id] = call }
                    // a pause/cancel that arrived before the call was registered
                    if (intent(id) != Intent.NONE) call.cancel()
                    call.execute().use { r ->
                        if (r.code == 416 && total > 0 && copied >= total) {
                            // resume raced past the end — everything is on disk
                            complete(id, track, part)
                            return
                        }
                        if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
                        val body = r.body ?: throw IOException("Download failed (empty response)")
                        val contentRangeTotal = r.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                        val contentLength = body.contentLength()
                        if (r.code == 200) {
                            // server ignored the range (or fresh start) — never append twice
                            if (copied > 0) {
                                part.delete()
                                copied = 0
                            }
                            if (contentLength > 0) total = contentLength
                        } else if (contentRangeTotal != null && contentRangeTotal > 0) {
                            total = contentRangeTotal
                        }
                        persistTotal(id, track, total)
                        updateTask(id) { it.copy(status = DlStatus.ACTIVE, totalBytes = total, bytesDone = copied, error = null) }
                        FileOutputStream(part, true).use { out ->
                            copyWithProgress(body.byteStream(), out, total, copied) { done ->
                                updateTask(id) { it.copy(bytesDone = done) }
                            }
                        }
                        val have = part.length()
                        if (total <= 0 || have >= total) {
                            // clean EOF: either the size matched or the server
                            // never declared one (chunked) — nothing left to fetch
                            complete(id, track, part)
                            return
                        }
                        // short read with no error — try again from the current offset
                        attempt++
                        if (attempt > MAX_ATTEMPTS) throw IOException("Download stalled near the end")
                    }
                } catch (e: CancellationException) {
                    // the process is going away — keep the .part and queue entry
                    // so this download comes back as a resumable paused task
                    throw e
                } catch (e: Exception) {
                    when (intent(id)) {
                        Intent.CANCEL -> { finishCancel(id, track, part ?: findPart(id)); return }
                        Intent.PAUSE -> { finishPause(id, track, part ?: findPart(id)); return }
                        Intent.NONE -> {}
                    }
                    attempt++
                    if (!online()) {
                        finishFailed(id, track, "You're offline")
                        return
                    }
                    if (attempt > MAX_ATTEMPTS) {
                        finishFailed(id, track, e.message ?: "Download failed")
                        return
                    }
                    delay(650L * attempt)
                } finally {
                    synchronized(this) { calls.remove(id) }
                }
            }
        } finally {
            synchronized(this) {
                jobs.remove(id)
                intents.remove(id)
            }
        }
    }

    private fun queueTotal(id: String): Long = synchronized(this) { queue[id]?.totalBytes ?: 0L }

    private fun persistTotal(id: String, track: Track, total: Long) {
        if (total <= 0) return
        synchronized(this) {
            val e = queue[id] ?: QueueEntry(track, 0, System.currentTimeMillis())
            if (e.totalBytes == total && queue.containsKey(id)) return
            queue = queue + (id to e.copy(track = track, totalBytes = total))
            saveQueue()
        }
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        synchronized(this) {
            val t = _tasks.value[id] ?: return
            _tasks.value = _tasks.value + (id to transform(t))
        }
    }

    private fun complete(id: String, track: Track, part: File) {
        val target = File(dir, part.name.removeSuffix(".part"))
        try {
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        } catch (e: Exception) {
            finishFailed(id, track, e.message ?: "Couldn't save the file")
            return
        }
        val dl = SavedDownload(
            track = track,
            bytes = target.length(),
            at = System.currentTimeMillis(),
            fileName = target.name,
        )
        synchronized(this) {
            _items.value = _items.value + (id to dl)
            _tasks.value = _tasks.value - id
            queue = queue - id
            saveIndex()
            saveQueue()
        }
        _events.tryEmit(DlEvent.Finished(track))
    }

    private fun finishPause(id: String, track: Track, part: File?) {
        val bytes = part?.takeIf { it.exists() }?.length() ?: findPart(id)?.length() ?: 0L
        synchronized(this) {
            val t = _tasks.value[id] ?: return
            _tasks.value = _tasks.value + (id to t.copy(status = DlStatus.PAUSED, bytesDone = bytes, error = null))
        }
        _events.tryEmit(DlEvent.Paused(track))
    }

    private fun finishCancel(id: String, track: Track, part: File?) {
        try { part?.delete() } catch (_: Exception) { }
        synchronized(this) {
            _tasks.value = _tasks.value - id
            queue = queue - id
            saveQueue()
        }
        _events.tryEmit(DlEvent.Cancelled(track))
    }

    private fun finishFailed(id: String, track: Track, message: String) {
        synchronized(this) {
            val t = _tasks.value[id] ?: return
            _tasks.value = _tasks.value + (id to t.copy(status = DlStatus.FAILED, error = message))
        }
        _events.tryEmit(DlEvent.Failed(track, message))
    }
}

// Streams input to output, reporting the ABSOLUTE number of bytes done at most
// once per whole percent (plus one final call) so listeners see at most ~101
// updates per download. startOffset is the number of bytes already on disk when
// resuming from a Range request. When totalBytes is unknown (<= 0) no progress
// is reported and the UI stays indeterminate. Returns bytes copied this call.
internal fun copyWithProgress(
    input: InputStream,
    output: OutputStream,
    totalBytes: Long,
    startOffset: Long = 0,
    onProgress: (Long) -> Unit,
): Long {
    val buf = ByteArray(64 * 1024)
    var copied = 0L
    var lastPct = -1
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        output.write(buf, 0, n)
        copied += n
        if (totalBytes > 0) {
            val done = startOffset + copied
            val pct = ((done * 100) / totalBytes).toInt()
            if (pct > lastPct) {
                lastPct = pct
                onProgress(done)
            }
        }
    }
    if (totalBytes > 0) onProgress(startOffset + copied)
    return copied
}
