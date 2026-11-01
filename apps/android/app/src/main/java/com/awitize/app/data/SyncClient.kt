package com.awitize.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
data class SyncPayload(
    val aw: String = "",
    val v: Int = 1,
    val host: String = "",
    val port: Int = 0,
    val token: String = "",
    val name: String? = null,
)

data class SyncOutcome(
    val pcName: String,
    val received: SyncStats,
    val sent: SyncStats,
)

// Phone side of the desktop pairing protocol (electron/sync.js):
// 1. GET  /awitize/v1/snapshot?token=…  → the PC's library
// 2. merge into the local library (add-only)
// 3. POST /awitize/v1/merge?token=…     → our library, PC merges it back
class SyncClient(private val http: OkHttpClient) {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun run(
        payloadJson: String,
        local: LibraryFile,
        deviceName: String,
    ): Pair<LibraryFile, SyncOutcome> = withContext(Dispatchers.IO) {
        val payload = try {
            SyncJson.decodeFromString(SyncPayload.serializer(), payloadJson)
        } catch (_: Exception) {
            throw IOException("That QR code isn't an Awitize pairing code.")
        }
        if (payload.aw != "sync" || payload.host.isBlank() || payload.port <= 0 || payload.token.isBlank()) {
            throw IOException("That QR code isn't an Awitize pairing code.")
        }
        if (payload.v > 1) {
            throw IOException("This pairing code is from a newer Awitize — update this app first.")
        }

        val base = "http://${payload.host}:${payload.port}/awitize/v1"

        val snapRoot = SyncJson.parseToJsonElement(get("$base/snapshot?token=${payload.token}"))
        if (snapRoot.pathStr("ok") == "false") {
            throw IOException(snapRoot.pathStr("error") ?: "The PC refused to share its library.")
        }
        val snapEl = snapRoot.path("snapshot")
            ?: throw IOException("The PC sent an unreadable library.")
        val incoming = try {
            SyncJson.decodeFromJsonElement(LibraryFile.serializer(), snapEl)
        } catch (_: Exception) {
            throw IOException("The PC's library couldn't be read (version mismatch?).")
        }

        val (merged, received) = SyncMerge.merge(local, incoming)

        val (name, snap) = SyncMerge.snapshot(deviceName, merged)
        val snapJson = SyncJson.encodeToJsonElement(LibraryFile.serializer(), snap).jsonObject
        val out = JsonObject(
            snapJson +
                ("aw" to JsonPrimitive("sync")) +
                ("v" to JsonPrimitive(1)) +
                ("name" to JsonPrimitive(name)),
        )
        val respRoot = SyncJson.parseToJsonElement(post("$base/merge?token=${payload.token}", out.toString()))
        if (respRoot.pathStr("ok") == "false") {
            throw IOException(respRoot.pathStr("error") ?: "The PC couldn't finish the merge.")
        }
        val statsEl = respRoot.path("stats")
            ?: throw IOException("The PC didn't confirm the merge.")
        val sent = SyncStats(
            likesAdded = statsEl.pathStr("likesAdded")?.toIntOrNull() ?: 0,
            playlistsAdded = statsEl.pathStr("playlistsAdded")?.toIntOrNull() ?: 0,
            playlistsMerged = statsEl.pathStr("playlistsMerged")?.toIntOrNull() ?: 0,
            tracksAdded = statsEl.pathStr("tracksAdded")?.toIntOrNull() ?: 0,
            playsAdded = statsEl.pathStr("playsAdded")?.toIntOrNull() ?: 0,
        )

        merged to SyncOutcome(
            pcName = payload.name?.takeIf { it.isNotBlank() } ?: "the PC",
            received = received,
            sent = sent,
        )
    }

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(errorFor(r.code, text))
            return text
        }
    }

    private fun post(url: String, json: String): String {
        val req = Request.Builder().url(url).post(json.toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(errorFor(r.code, text))
            return text
        }
    }

    private fun errorFor(code: Int, body: String): String {
        try {
            val root = SyncJson.parseToJsonElement(body)
            val msg = root.pathStr("error")
            if (!msg.isNullOrBlank()) return msg
        } catch (_: Exception) { }
        return when (code) {
            401 -> "The pairing window expired. Open Sync on the PC for a fresh QR code."
            404 -> "The PC isn't running an Awitize sync right now."
            else -> "The PC replied with an error (HTTP $code)."
        }
    }
}
