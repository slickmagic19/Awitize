package com.awitize.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

data class WordTag(val t: Double, val text: String)
data class LyricLine(val t: Double, val text: String, val words: List<WordTag> = emptyList())
data class LyricsResult(val synced: Boolean, val source: String, val lines: List<LyricLine>)

// Synced lyrics from lrclib.net — same source and scoring the desktop backend
// uses, ported 1:1 (clean title, duration-distance penalty, synced > plain).
class LyricsClient(private val http: OkHttpClient) {

    private val lineTag = Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]")
    private val wordTag = Regex("<(\\d+):(\\d+(?:\\.\\d+)?)>")
    private val noiseTag = Regex("\\[(?:offset|ar|ti|al|by|re|length):[^\\]]*\\]", RegexOption.IGNORE_CASE)
    private val bracketNoise = Regex(
        "\\s*[\\\\(\\[]\\s*(?:official|lyrics?|lyric\\s+video|music\\s+video|video|audio|visuali[sz]er|mv|hd|hq|4k|8k|explicit|clean)\\b[^\\\\)\\]]*[\\\\)\\]]",
        RegexOption.IGNORE_CASE,
    )

    suspend fun fetch(
        title: String,
        artists: List<String>,
        album: String?,
        durationSec: Double,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val rawTitle = title.trim()
        if (rawTitle.isEmpty()) return@withContext null
        val firstArtist = artists.firstOrNull()?.trim().orEmpty()
        val clean = if (firstArtist.isNotEmpty()) cleanTitle(rawTitle, firstArtist) else rawTitle

        var best: LyricsResult? = null
        if (firstArtist.isNotEmpty()) {
            best = get(clean, firstArtist, album, durationSec)
            if (best?.synced != true) {
                val rawTry = get(rawTitle, firstArtist, album, durationSec)
                if (rawTry != null && (rawTry.synced || best == null)) best = rawTry
            }
        }
        if (best?.synced != true) {
            val query = listOf(rawTitle, firstArtist).filter { it.isNotBlank() }.joinToString(" ")
            val search = search(query, durationSec)
            if (search != null && (best == null || (search.synced && !best.synced))) best = search
        }
        best
    }

    private fun cleanTitle(title: String, artist: String): String {
        var t = title.trim()
        val a = artist.trim().lowercase()
        if (a.isNotEmpty()) {
            for (sep in listOf(" - ", " – ", " — ", " | ", ": ")) {
                if (t.lowercase().startsWith(a + sep)) {
                    t = t.substring(a.length + sep.length)
                    break
                }
            }
        }
        t = bracketNoise.replace(t, "")
        t = t.replace(Regex("\\s{2,}"), " ").trim(' ', '-', '–', '—', '|')
        return t.ifEmpty { title.trim() }
    }

    private suspend fun get(track: String, artist: String, album: String?, duration: Double): LyricsResult? {
        val params = buildList {
            add("track_name=${enc(track)}")
            add("artist_name=${enc(artist)}")
            if (!album.isNullOrBlank()) add("album_name=${enc(album)}")
            if (duration > 0) add("duration=${duration.toInt()}")
        }
        val body = requestWithRetry("https://lrclib.net/api/get?${params.joinToString("&")}") ?: return null
        val root = try { SyncJson.parseToJsonElement(body) } catch (_: Exception) { return null }
        val synced = root.pathStr("syncedLyrics")
        val plain = root.pathStr("plainLyrics")
        if (!synced.isNullOrBlank()) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return LyricsResult(true, "lrclib", lines)
        }
        if (!plain.isNullOrBlank()) {
            val lines = plain.lines().filter { it.isNotBlank() }.map { LyricLine(0.0, it.trim()) }
            if (lines.isNotEmpty()) return LyricsResult(false, "lrclib (plain)", lines)
        }
        return null
    }

    private suspend fun search(query: String, duration: Double): LyricsResult? {
        if (query.isBlank()) return null
        val body = requestWithRetry("https://lrclib.net/api/search?q=${enc(query)}") ?: return null
        val root = try { SyncJson.parseToJsonElement(body) } catch (_: Exception) { return null }
        val arr = root as? kotlinx.serialization.json.JsonArray ?: return null
        var bestScore = Int.MAX_VALUE
        var bestSynced = false
        var best: LyricsResult? = null
        for (el in arr.take(8)) {
            val syncedText = el.pathStr("syncedLyrics")
            val plainText = el.pathStr("plainLyrics")
            val d = el.pathStr("duration")?.toDoubleOrNull()
            val penalty = when {
                duration <= 0 || d == null -> 0
                kotlin.math.abs(d - duration) <= 8 -> 0
                kotlin.math.abs(d - duration) <= 25 -> 1
                else -> 2
            }
            val synced = !syncedText.isNullOrBlank()
            val clause = if (synced) 0 else 1
            val score = clause * 10 + penalty
            if (score >= bestScore) continue
            if (synced) {
                val lines = parseLrc(syncedText!!)
                if (lines.isNotEmpty()) {
                    bestScore = score; bestSynced = true
                    best = LyricsResult(true, "lrclib", lines)
                }
            } else if (!plainText.isNullOrBlank()) {
                val lines = plainText.lines().filter { it.isNotBlank() }.map { LyricLine(0.0, it.trim()) }
                if (lines.isNotEmpty()) {
                    bestScore = score; bestSynced = false
                    best = LyricsResult(false, "lrclib (plain)", lines)
                }
            }
        }
        if (!bestSynced && best == null) return null
        return best
    }

    private suspend fun requestWithRetry(url: String): String? {
        for (attempt in 0..1) {
            if (attempt > 0) delay(1200)
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) return r.body?.string()
                    if (r.code == 404) return null
                    // 503 and friends — fall through to the retry
                }
            } catch (_: Exception) {
                // network hiccup — retry once
            }
        }
        return null
    }

    fun parseLrc(s: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in s.lines()) {
            if (noiseTag.containsMatchIn(raw)) {
                val stripped = noiseTag.replace(raw, "")
                if (stripped.isBlank()) continue
            }
            val tags = lineTag.findAll(raw).mapNotNull { m ->
                val mm = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val ss = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                mm * 60 + ss
            }.toList()
            if (tags.isEmpty()) continue
            val inner = lineTag.replace(raw, "")
            val text = wordTag.replace(inner, "").trim()
            if (text.isEmpty()) continue

            var words = emptyList<WordTag>()
            val chunks = wordTag.split(inner)
            if (chunks.size >= 4) {
                val tmp = ArrayList<WordTag>()
                var ok = true
                var k = 1
                while (k + 2 < chunks.size) {
                    val mm = chunks[k].trim().toIntOrNull()
                    val ss = chunks[k + 1].trim().toDoubleOrNull()
                    if (mm == null || ss == null) { ok = false; break }
                    tmp.add(WordTag(mm * 60 + ss, chunks[k + 2]))
                    k += 3
                }
                if (ok && tmp.size >= 2) words = tmp
            }
            for (t in tags) out.add(LyricLine(t, text, words))
        }
        return out.sortedBy { it.t }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
