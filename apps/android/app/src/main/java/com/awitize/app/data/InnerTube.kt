package com.awitize.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// YouTube Music metadata via InnerTube (WEB_REMIX) — the same endpoint family
// ytmusicapi talks to on the desktop backend. Parsing is deliberately
// layout-tolerant: known paths first, then a bounded tree scan, so a renderer
// reshuffle degrades gracefully instead of breaking search.

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private val UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0"

private val DURATION_RE = Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?$")
private val YEAR_RE = Regex("^(19|20)\\d{2}$")
private val VIEWS_RE = Regex("([\\d.,]+\\s*(views|plays|listeners))", RegexOption.IGNORE_CASE)
private val COUNT_RE = Regex("^([\\d,]+)\\s+(songs?|tracks?|items?|videos?)$", RegexOption.IGNORE_CASE)
private val TYPE_WORDS = setOf(
    "song", "songs", "video", "videos", "album", "albums", "single", "ep", "playlist",
    "artist", "music video", "live", "audiobook", "podcast", "episode", "profile",
)

// order of the grouped sections the signed-out "All" search tab produces
private val GROUP_ORDER = listOf(
    "Songs", "Videos", "Albums & singles", "Artists", "Playlists", "Episodes", "Audiobooks",
)

const val PAGE_TYPE_ALBUM = "MUSIC_PAGE_TYPE_ALBUM"
const val PAGE_TYPE_ARTIST = "MUSIC_PAGE_TYPE_ARTIST"
const val PAGE_TYPE_PLAYLIST = "MUSIC_PAGE_TYPE_PLAYLIST"
const val PAGE_TYPE_AUDIOBOOK = "MUSIC_PAGE_TYPE_AUDIOBOOK"

data class Section(val title: String?, val items: List<Track>)
data class Mood(val title: String, val params: String)
data class ExploreData(
    val newReleases: List<Track>,
    val moods: List<Mood>,
    val trending: List<Track>,
    val newVideos: List<Track>,
    val chartArtists: List<Track>,
)
data class AlbumDetail(
    val title: String,
    val artists: List<String>,
    val year: String?,
    val artwork: String?,
    val tracks: List<Track>,
)
data class ArtistDetail(
    val name: String,
    val artwork: String?,
    val songs: List<Track>,
    val albums: List<Track>,
)

// ---- JSON navigation helpers ------------------------------------------------

fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)

fun JsonElement?.obj(key: String): JsonObject? = field(key) as? JsonObject

fun JsonElement?.arr(key: String): JsonArray? = field(key) as? JsonArray

fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

fun JsonElement?.path(vararg keys: Any): JsonElement? {
    var cur: JsonElement? = this
    for (k in keys) {
        cur = when (k) {
            is Int -> (cur as? JsonArray)?.getOrNull(k)
            is String -> (cur as? JsonObject)?.get(k)
            else -> null
        } ?: return null
    }
    return cur
}

fun JsonElement?.pathStr(vararg keys: Any): String? {
    val v = path(*keys) as? JsonPrimitive ?: return null
    return if (v is JsonNull) null else v.contentOrNull
}

// Bounded breadth-first scan for every value stored under `key` — the safety
// net for when Google reshuffles renderer nesting.
fun JsonElement?.findAll(key: String, maxDepth: Int = 14, limit: Int = 60): List<JsonElement> {
    val out = ArrayList<JsonElement>()
    if (this == null) return out
    var current = listOf(this)
    var depth = 0
    while (current.isNotEmpty() && depth <= maxDepth && out.size < limit) {
        val next = ArrayList<JsonElement>()
        for (el in current) {
            when (el) {
                is JsonObject -> {
                    el[key]?.let { if (out.size < limit) out.add(it) }
                    for (v in el.values) next.add(v)
                }
                is JsonArray -> for (v in el) next.add(v)
                else -> {}
            }
        }
        current = next
        depth++
    }
    return out
}

fun JsonElement?.thumbUrl(): String? {
    if (this == null) return null
    val list = this.path("musicThumbnailRenderer", "thumbnail", "thumbnails")
        ?: this.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
        ?: this.path("thumbnail", "croppedSquareThumbnailRenderer", "thumbnail", "thumbnails")
        ?: this.path("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails")
        ?: this.path("thumbnail", "thumbnails")
        ?: findAll("thumbnail", maxDepth = 4, limit = 8).firstNotNullOfOrNull { t ->
            t.path("musicThumbnailRenderer", "thumbnail", "thumbnails") ?: t.path("thumbnails")
        }
    return (list as? JsonArray)?.mapNotNull { it.pathStr("url") }?.lastOrNull()
}

private class Run(val text: String, val nav: JsonElement?) {
    val pageType: String? get() = nav.pathStr(
        "browseEndpoint", "browseEndpointContextSupportedConfigs",
        "browseEndpointContextMusicConfig", "pageType",
    )
    val browseId: String? get() = nav.pathStr("browseEndpoint", "browseId")
    val watchVideoId: String? get() = nav.pathStr("watchEndpoint", "videoId")
    val isChannel: Boolean get() = browseId?.startsWith("UC") == true
}

private fun JsonElement?.runsToList(): List<Run> {
    val runs = path("musicResponsiveListItemFlexColumnRenderer", "text", "runs") as? JsonArray ?: return emptyList()
    return runs.mapNotNull { r ->
        val t = r.pathStr("text") ?: return@mapNotNull null
        Run(t, r.field("navigationEndpoint"))
    }
}

class InnerTube(private val http: OkHttpClient) {

    private data class Cfg(val key: String, val version: String, val visitor: String?)

    @Volatile private var cfg: Cfg? = null
    private val cfgMutex = Mutex()

    private val country: String = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase() ?: "US"
    private val lang: String = Locale.getDefault().language.takeIf { it.isNotEmpty() }?.lowercase() ?: "en"

    private suspend fun config(): Cfg {
        cfg?.let { return it }
        return withContext(Dispatchers.IO) {
            cfgMutex.withLock {
                cfg?.let { return@withLock it }
                var key = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
                var version = "1.${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.01.00"
                var visitor: String? = null
                try {
                    val html = execute("https://music.youtube.com/", "GET", null, null)
                    Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { key = it }
                    Regex("\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { version = it }
                    visitor = Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                } catch (_: Exception) { /* fall back to constants */ }
                val c = Cfg(key, version, visitor)
                cfg = c
                c
            }
        }
    }

    private fun execute(url: String, method: String, body: String?, c: Cfg?): String {
        val rb = Request.Builder().url(url)
            .header("User-Agent", UA_DESKTOP)
            .header("Accept", "*/*")
            .header("Origin", "https://music.youtube.com")
        if (c != null) {
            rb.header("X-YouTube-Client-Name", "67")
            rb.header("X-YouTube-Client-Version", c.version)
            c.visitor?.let { rb.header("X-Goog-Visitor-Id", it) }
        }
        if (body != null) rb.method(method, body.toRequestBody(JSON_MEDIA)) else rb.method(method, null)
        http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("YouTube Music returned HTTP ${r.code}")
            return text
        }
    }

    private suspend fun post(endpoint: String, body: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val c = config()
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val url = "https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false&alt=json&key=${c.key}"
                return@withContext SyncJson.parseToJsonElement(execute(url, "POST", body.toString(), c))
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) delay(1500)
            }
        }
        throw lastError ?: IOException("request failed")
    }

    private fun clientJson(c: Cfg): JsonObject = buildJsonObject {
        put("clientName", "WEB_REMIX")
        put("clientVersion", c.version)
        put("hl", lang)
        put("gl", country)
    }

    // ---- search -------------------------------------------------------------

    private val filterParams = mapOf(
        "songs" to "EgWKAQIIAWoMEA4QChADEAQQCRAF",
        "videos" to "EgWKAQIQAWoMEA4QChADEAQQCRAF",
        "albums" to "EgWKAQIYAWoMEA4QChADEAQQCRAF",
        "artists" to "EgWKAQIgAWoMEA4QChADEAQQCRAF",
        "playlists" to "Eg-KAQwIABAAGAAgACgBMABqChAEEAMQCRAFEAo%3D",
    )

    suspend fun search(query: String, filter: String?, limit: Int = 40): List<Section> {
        val c = config()
        val body = buildJsonObject {
            putJsonObject("context") { put("client", clientJson(c)); putJsonObject("user") {} }
            put("query", query)
            filterParams[filter]?.let { put("params", it) }
        }
        val root = post("search", body)
        val out = ArrayList<Section>()
        for (shelf in collectShelves(root)) {
            val list = shelf.arr("contents") ?: continue
            val items = list.mapNotNull { parseRow(it) }.filter { it.title.isNotEmpty() && (it.playable || it.browseId != null) }
            if (items.isEmpty()) continue
            val title = shelf.pathStr("title", "runs", 0, "text")
            out.add(Section(title, items.take(limit)))
        }
        if (out.isEmpty()) out.addAll(allTabSections(root, limit))
        return out
    }

    private fun collectShelves(root: JsonElement): List<JsonElement> {
        val list = (root.path("contents", "tabbedSearchResultsRenderer", "tabs", 0, "tabRenderer", "content", "sectionListRenderer", "contents")
            ?: root.path("contents", "sectionListRenderer", "contents")) as? JsonArray
            ?: return root.findAll("musicShelfRenderer", maxDepth = 10, limit = 12)
        val out = ArrayList<JsonElement>()
        for (entry in list) {
            entry.obj("musicShelfRenderer")?.let { out.add(it) }
            entry.obj("itemSectionRenderer")?.let { isr ->
                (isr.arr("contents") ?: return@let) .forEach { inner ->
                    inner.obj("musicShelfRenderer")?.let { out.add(it) }
                }
            }
        }
        if (out.isEmpty()) out.addAll(root.findAll("musicShelfRenderer", maxDepth = 10, limit = 12))
        return out
    }

    // The signed-out "All" tab carries no musicShelfRenderer at all: one
    // top-result card followed by flat itemSections holding bare rows. Group
    // those rows by type so search still shows a titled overview.
    private fun allTabSections(root: JsonElement, limit: Int): List<Section> {
        val list = root.path(
            "contents", "tabbedSearchResultsRenderer", "tabs", 0,
            "tabRenderer", "content", "sectionListRenderer", "contents",
        ) as? JsonArray ?: return emptyList()
        val top = ArrayList<Track>()
        val groups = LinkedHashMap<String, MutableList<Track>>()
        for (entry in list) {
            entry.obj("musicCardShelfRenderer")?.let { card ->
                parseTopCard(card)?.let { top.add(it) }
            }
            val section = entry.obj("itemSectionRenderer") ?: continue
            for (inner in section.arr("contents") ?: continue) {
                val t = parseRow(inner) ?: continue
                if (t.title.isEmpty() || (!t.playable && t.browseId == null)) continue
                val key = groupFor(t.kind) ?: continue
                groups.getOrPut(key) { ArrayList() }.add(t)
            }
        }
        val out = ArrayList<Section>()
        if (top.isNotEmpty()) out.add(Section("Top result", top))
        for (name in GROUP_ORDER) {
            groups[name]?.let { out.add(Section(name, it.take(limit))) }
        }
        return out
    }

    private fun groupFor(kind: String?): String? = when (kind) {
        "song" -> "Songs"
        "video" -> "Videos"
        "album", "single", "ep" -> "Albums & singles"
        "artist" -> "Artists"
        "playlist" -> "Playlists"
        "episode" -> "Episodes"
        "audiobook" -> "Audiobooks"
        else -> null
    }

    private fun parseTopCard(card: JsonElement): Track? {
        val title = card.pathStr("title", "runs", 0, "text")?.trim().orEmpty()
        if (title.isEmpty()) return null
        val kindWord = ((card.path("subtitle", "runs") as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { it.pathStr("text")?.trim()?.lowercase() }
            .firstOrNull { TYPE_WORDS.contains(it) }
        val nav = card.field("onTap")
        val browseId = nav?.pathStr("browseEndpoint", "browseId")
        val pageType = nav?.pathStr(
            "browseEndpoint", "browseEndpointContextSupportedConfigs",
            "browseEndpointContextMusicConfig", "pageType",
        )
        val videoId = nav?.pathStr("watchEndpoint", "videoId")
        val kind = when (pageType) {
            PAGE_TYPE_ALBUM -> kindWord ?: "album"
            PAGE_TYPE_ARTIST -> "artist"
            PAGE_TYPE_PLAYLIST -> "playlist"
            PAGE_TYPE_AUDIOBOOK -> "audiobook"
            else -> kindWord ?: if (videoId != null) "song" else null
        } ?: return null
        val playlistId = if (pageType == PAGE_TYPE_PLAYLIST && browseId != null) {
            browseId.removePrefix("VL")
        } else {
            nav?.pathStr("watchEndpoint", "playlistId")
        }
        return Track(
            videoId = videoId,
            browseId = browseId,
            playlistId = playlistId,
            title = title,
            artwork = card.thumbUrl(),
            kind = kind,
        )
    }

    // ---- detail pages -------------------------------------------------------

    suspend fun album(browseId: String): AlbumDetail {
        val c = config()
        val body = buildJsonObject {
            putJsonObject("context") { put("client", clientJson(c)); putJsonObject("user") {} }
            put("browseId", browseId)
        }
        val root = post("browse", body)

        val header = root.findAll("musicResponsiveHeaderRenderer", maxDepth = 12, limit = 6).firstOrNull()
            ?: root.findAll("musicDetailHeaderRenderer", maxDepth = 12, limit = 6).firstOrNull()
        val title = header.pathStr("title", "runs", 0, "text").orEmpty().ifEmpty { "Album" }

        val straplineRuns = (header.path("straplineTextOne", "runs") as? JsonArray)?.mapNotNull { r ->
            val t = r.pathStr("text") ?: return@mapNotNull null
            Run(t, r.field("navigationEndpoint"))
        }.orEmpty()
        val subtitleRuns = (header.path("subtitle", "runs") as? JsonArray)?.mapNotNull { r ->
            val t = r.pathStr("text") ?: return@mapNotNull null
            Run(t, r.field("navigationEndpoint"))
        }.orEmpty()
        val metaRuns = if (straplineRuns.isNotEmpty()) subtitleRuns else subtitleRuns.drop(1)

        val artists = (straplineRuns + metaRuns).filter { it.pageType == PAGE_TYPE_ARTIST || it.isChannel }
            .map { it.text }.distinct().take(3)
        val year = subtitleRuns.map { it.text }.firstOrNull { YEAR_RE.matches(it) }
        val artwork = header.thumbUrl()

        val shelves = root.findAll("musicShelfRenderer", maxDepth = 12, limit = 12) +
            root.findAll("musicPlaylistShelfRenderer", maxDepth = 12, limit = 6)
        val tracks = bestTrackShelf(shelves)
            ?.map { if (it.album == null) it.copy(album = title, artwork = it.artwork ?: artwork) else it }
            .orEmpty()

        return AlbumDetail(title, artists, year, artwork, tracks.take(120))
    }

    suspend fun artist(browseId: String): ArtistDetail {
        val c = config()
        val body = buildJsonObject {
            putJsonObject("context") { put("client", clientJson(c)); putJsonObject("user") {} }
            put("browseId", browseId)
        }
        val root = post("browse", body)

        val header = root.findAll("musicImmersiveHeaderRenderer", maxDepth = 12, limit = 4).firstOrNull()
            ?: root.findAll("musicVisualHeaderRenderer", maxDepth = 12, limit = 4).firstOrNull()
        val name = header.pathStr("title", "runs", 0, "text").orEmpty().ifEmpty { "Artist" }
        val artwork = header.thumbUrl()

        val shelves = root.findAll("musicShelfRenderer", maxDepth = 12, limit = 10)
        val songs = bestTrackShelf(shelves).orEmpty()

        val cards = collectCards(root)
        val albums = cards.filter { it.kind == "album" || it.kind == "single" || it.kind == "ep" }
        return ArtistDetail(name, artwork, songs.take(100), albums.distinctBy { it.browseId ?: it.title }.take(30))
    }

    suspend fun ytmPlaylist(browseIdOrListId: String): AlbumDetail {
        val c = config()
        val browseId = if (browseIdOrListId.startsWith("VL")) browseIdOrListId else "VL$browseIdOrListId"
        val body = buildJsonObject {
            putJsonObject("context") { put("client", clientJson(c)); putJsonObject("user") {} }
            put("browseId", browseId)
        }
        val root = post("browse", body)

        val header = root.findAll("musicResponsiveHeaderRenderer", maxDepth = 12, limit = 6).firstOrNull()
            ?: root.findAll("musicDetailHeaderRenderer", maxDepth = 12, limit = 6).firstOrNull()
        val title = header.pathStr("title", "runs", 0, "text").orEmpty().ifEmpty { "Playlist" }
        val artwork = header.thumbUrl()
        val subtitleRuns = header.path("subtitle", "runs") as? JsonArray
        val author = subtitleRuns?.mapNotNull { it.pathStr("text") }?.firstOrNull { t -> !TYPE_WORDS.contains(t.lowercase()) && !Regex("^[\\d,]+\\s").containsMatchIn(t) }
        val countText = subtitleRuns?.mapNotNull { it.pathStr("text") }?.firstOrNull { COUNT_RE.matches(it) }

        val shelves = root.findAll("musicPlaylistShelfRenderer", maxDepth = 12, limit = 6) +
            root.findAll("musicShelfRenderer", maxDepth = 12, limit = 10)
        val tracks = bestTrackShelf(shelves)
            ?.map { if (it.artwork == null) it.copy(artwork = artwork) else it }
            .orEmpty()

        val meta = listOfNotNull(author, countText)
        return AlbumDetail(title, meta, null, artwork, tracks.take(150))
    }

    // ---- explore (mirrors the desktop backend /explore + /mood endpoints) ----

    private fun singleColumnSections(root: JsonElement): List<JsonElement>? = root.path(
        "contents", "singleColumnBrowseResultsRenderer", "tabs", 0,
        "tabRenderer", "content", "sectionListRenderer", "contents",
    ) as? JsonArray

    suspend fun explore(): ExploreData = withContext(Dispatchers.IO) {
        val c = config()
        val body = buildJsonObject {
            putJsonObject("context") { put("client", clientJson(c)); putJsonObject("user") {} }
            put("browseId", "FEmusic_explore")
        }
        val root = post("browse", body)
        val sections = singleColumnSections(root)
            ?: root.findAll("musicCarouselShelfRenderer", maxDepth = 8, limit = 20)

        var newReleases: List<Track> = emptyList()
        var moods: List<Mood> = emptyList()
        var trending: List<Track> = emptyList()
        var newVideos: List<Track> = emptyList()
        for (section in sections) {
            val car = section.obj("musicCarouselShelfRenderer") ?: continue
            val shelfId = car.pathStr(
                "header", "musicCarouselShelfBasicHeaderRenderer",
                "title", "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId",
            )
            val contents = car.arr("contents") ?: continue
            when {
                shelfId == "FEmusic_new_releases_albums" -> newReleases = contents.mapNotNull { parseCard(it) }
                shelfId == "FEmusic_moods_and_genres" -> moods = contents.mapNotNull { parseMood(it) }
                shelfId == "FEmusic_new_releases_videos" -> newVideos = contents.mapNotNull { parseCard(it) }
                shelfId?.startsWith("VL") == true -> {
                    val rows = contents.mapNotNull { parseRow(it) }
                    if (rows.isNotEmpty()) trending = rows
                }
            }
        }
        val chartArtists = runCatching { chartArtists() }.getOrDefault(emptyList())
        ExploreData(newReleases, moods, trending, newVideos, chartArtists)
    }

    private fun parseMood(el: JsonElement): Mood? {
        val title = el.pathStr("musicNavigationButtonRenderer", "buttonText", "runs", 0, "text") ?: return null
        val params = el.pathStr("musicNavigationButtonRenderer", "clickCommand", "browseEndpoint", "params") ?: return null
        return Mood(title, params)
    }

    suspend fun moodPlaylists(params: String): List<Track> = withContext(Dispatchers.IO) {
        val c = config()
        val body = buildJsonObject {
            putJsonObject("context") { put("client", clientJson(c)); putJsonObject("user") {} }
            put("browseId", "FEmusic_moods_and_genres_category")
            put("params", params)
        }
        val root = post("browse", body)
        val out = ArrayList<Track>()
        val sections = singleColumnSections(root) ?: JsonArray(emptyList())
        for (section in sections) {
            section.obj("gridRenderer")?.arr("items")?.forEach { parseCard(it)?.let { t -> out.add(t) } }
            section.obj("musicCarouselShelfRenderer")?.arr("contents")?.forEach { parseCard(it)?.let { t -> out.add(t) } }
            section.obj("musicImmersiveCarouselShelfRenderer")?.arr("contents")?.forEach { parseCard(it)?.let { t -> out.add(t) } }
        }
        if (out.isEmpty()) {
            root.findAll("musicTwoRowItemRenderer", maxDepth = 12, limit = 48)
                .forEach { parseCard(it)?.let { t -> out.add(t) } }
        }
        out.distinctBy { it.playlistId ?: it.browseId ?: it.title }
    }

    private suspend fun chartArtists(): List<Track> {
        val c = config()
        val body = buildJsonObject {
            putJsonObject("context") { put("client", clientJson(c)); putJsonObject("user") {} }
            put("browseId", "FEmusic_charts")
            putJsonObject("formData") { putJsonArray("selectedValues") { add(JsonPrimitive(country)) } }
        }
        val root = post("browse", body)
        val sections = singleColumnSections(root) ?: return emptyList()
        for (section in sections) {
            val items = section.obj("musicCarouselShelfRenderer")?.arr("contents") ?: continue
            if (items.isEmpty()) continue
            if (items.any { it.obj("musicResponsiveListItemRenderer") == null }) continue
            val rows = items.mapNotNull { parseRow(it) }
            if (rows.isNotEmpty()) return rows.take(30)
        }
        return emptyList()
    }

    // pick the shelf that actually holds playable rows (a page often carries
    // "related"/"you might like" shelves too)
    private fun bestTrackShelf(shelves: List<JsonElement>): List<Track>? = shelves
        .map { shelf -> (shelf.arr("contents") ?: JsonArray(emptyList())).mapNotNull { parseRow(it) } }
        .filter { list -> list.any { it.playable } }
        .maxByOrNull { it.size }

    // ---- row/card parsing ---------------------------------------------------

    private fun parseRow(el: JsonElement): Track? {
        val body = el.obj("musicResponsiveListItemRenderer") ?: return null
        val cols = body.arr("flexColumns")

        val col0 = cols?.getOrNull(0).runsToList()
        val col1 = cols?.getOrNull(1).runsToList()
        val col2 = cols?.getOrNull(2).runsToList()
        val allAfter = col1 + col2

        val title = col0.firstOrNull()?.text?.trim().orEmpty()

        // YTM prefixes rows with an unlinked "Song"/"Video"/"Album" chip
        val kindWord = col1.firstOrNull { it.nav == null }
            ?.text?.lowercase()?.takeIf { TYPE_WORDS.contains(it) }

        var artistRuns = allAfter.filter { it.pageType == PAGE_TYPE_ARTIST }
        if (artistRuns.isEmpty()) artistRuns = allAfter.filter { it.isChannel }
        val artists = artistRuns.map { it.text }.distinct().take(4)
        val artistIds = artistRuns.mapNotNull { it.browseId }.distinct().take(4)

        val albumRun = allAfter.firstOrNull { it.pageType == PAGE_TYPE_ALBUM }
        val duration = allAfter.map { it.text }.firstOrNull { DURATION_RE.matches(it) }
        val year = allAfter.map { it.text }.firstOrNull { YEAR_RE.matches(it) }
        val views = allAfter.map { it.text }.firstOrNull { VIEWS_RE.containsMatchIn(it) }
        val itemCount = allAfter.map { it.text }.firstOrNull { COUNT_RE.matches(it) }

        val videoId = body.pathStr("playlistItemData", "videoId")
            ?: body.pathStr(
                "overlay", "musicItemThumbnailOverlayRenderer", "content",
                "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId",
            )
            ?: body.pathStr("navigationEndpoint", "watchEndpoint", "videoId")
            ?: col0.firstNotNullOfOrNull { it.watchVideoId }
            ?: allAfter.firstNotNullOfOrNull { it.watchVideoId }

        val browseId = body.pathStr("navigationEndpoint", "browseEndpoint", "browseId")
        val pageType = body.pathStr(
            "navigationEndpoint", "browseEndpoint",
            "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType",
        )

        val kind = when (pageType) {
            PAGE_TYPE_ALBUM -> if (kindWord == "single" || kindWord == "ep") kindWord else "album"
            PAGE_TYPE_ARTIST -> "artist"
            PAGE_TYPE_PLAYLIST -> "playlist"
            PAGE_TYPE_AUDIOBOOK -> "audiobook"
            else -> kindWord ?: if (videoId != null) "song" else null
        }

        val playlistId = when {
            pageType == PAGE_TYPE_PLAYLIST && browseId != null && browseId.startsWith("VL") -> browseId.removePrefix("VL")
            else -> body.pathStr("navigationEndpoint", "watchEndpoint", "playlistId")
        }

        if (title.isEmpty() && browseId == null && videoId == null) return null
        return Track(
            videoId = videoId,
            browseId = browseId,
            playlistId = playlistId,
            title = title,
            artists = artists.distinct().take(4),
            artistIds = artistIds,
            album = albumRun?.text,
            albumId = albumRun?.browseId,
            year = year,
            views = views ?: itemCount,
            artwork = body.field("thumbnail").thumbUrl(),
            duration = duration,
            kind = kind,
        )
    }

    private fun collectCards(root: JsonElement): List<Track> {
        val out = ArrayList<Track>()
        for (carousel in root.findAll("musicCarouselShelfRenderer", maxDepth = 12, limit = 20)) {
            val items = carousel.arr("contents") ?: continue
            for (item in items) parseCard(item)?.let { out.add(it) }
        }
        if (out.isEmpty()) {
            for (grid in root.findAll("gridRenderer", maxDepth = 12, limit = 6)) {
                val items = grid.arr("items") ?: continue
                for (item in items) parseCard(item)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun parseCard(el: JsonElement): Track? {
        val c = el.obj("musicTwoRowItemRenderer") ?: return null
        val title = c.pathStr("title", "runs", 0, "text")?.trim().orEmpty()
        val subtitleRuns = (c.path("subtitle", "runs") as? JsonArray)?.mapNotNull { r ->
            val t = r.pathStr("text") ?: return@mapNotNull null
            Run(t, r.field("navigationEndpoint"))
        }.orEmpty()

        val artistRuns = subtitleRuns.filter { it.pageType == PAGE_TYPE_ARTIST || it.isChannel }
        val artists = artistRuns.map { it.text }.distinct().take(4)
        val artistIds = artistRuns.mapNotNull { it.browseId }.distinct().take(4)
        val year = subtitleRuns.map { it.text }.firstOrNull { YEAR_RE.matches(it) }
        val itemCount = subtitleRuns.map { it.text }.firstOrNull { COUNT_RE.matches(it) }
        val kindWord = subtitleRuns.firstOrNull { it.nav == null && TYPE_WORDS.contains(it.text.lowercase()) }?.text?.lowercase()

        val browseId = c.pathStr("navigationEndpoint", "browseEndpoint", "browseId")
        val pageType = c.pathStr(
            "navigationEndpoint", "browseEndpoint",
            "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType",
        )
        val videoId = c.pathStr(
            "overlay", "musicItemThumbnailOverlayRenderer", "content",
            "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId",
        ) ?: c.pathStr("navigationEndpoint", "watchEndpoint", "videoId")
        val playlistId = c.pathStr("navigationEndpoint", "watchPlaylistEndpoint", "playlistId")
            ?: c.pathStr(
                "thumbnailOverlay", "musicItemThumbnailOverlayRenderer", "content",
                "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "playlistId",
            )

        if (title.isEmpty() || (browseId == null && videoId == null)) return null

        val kind = when (pageType) {
            PAGE_TYPE_ALBUM -> kindWord ?: "album"
            PAGE_TYPE_ARTIST -> "artist"
            PAGE_TYPE_PLAYLIST -> "playlist"
            PAGE_TYPE_AUDIOBOOK -> "audiobook"
            else -> kindWord
        }
        return Track(
            videoId = videoId,
            browseId = browseId,
            playlistId = playlistId,
            title = title,
            artists = artists,
            artistIds = artistIds,
            year = year,
            views = itemCount,
            artwork = c.field("thumbnailRenderer").thumbUrl() ?: c.field("thumbnail").thumbUrl(),
            duration = null,
            kind = kind,
        )
    }
}
