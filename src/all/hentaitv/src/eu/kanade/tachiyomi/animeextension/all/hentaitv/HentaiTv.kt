/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaitv

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * HENTAI.TV (https://hentai.tv)
 *
 * Next.js site. Catalog data lives in JSON endpoints:
 *   - GET /api/browse?page=N&genres=A,B&brands=C&sort=Most Viewed|Top Rated|A-Z
 *     -> { videos: [...], total, pages } (28/page; default sort = most recent)
 *   - GET /api/search?q=... -> { videos: [...] } (full match set, no pagination)
 * Card objects: { slug, title, titleSlug, ep, tags[], cover, duration, quality, ... }
 *
 * Series detail: GET /series/{titleSlug}/ -> flight payload holds that title's
 * episode list (slugs are "{titleSlug}-episode-{n}").
 *
 * Video: the watch page embeds an nhplayer.com iframe. The player runs a
 * client-side challenge before handing out the stream:
 *   1. GET /player.php?vid=... -> inline _pV={vid,ct,pid,st}, DOM challenge
 *      parts under per-request randomized element ids, and a player-core-v2.php
 *      script URL that carries the server tokens (sc/rid).
 *   2. The core script reads 5 challenge parts (8 hex chars each), computes a
 *      SHA-256 proof-of-work (first hash byte zero) and calls
 *      /get-video-url-v2.php with everything.
 *   3. The server answers { url: "https://...mp4?verify=<sig>" } - a signed
 *      direct MP4 (valid ~24h). The unsigned base64 `vid` target is 403.
 * This extension replicates the challenge with MessageDigest.
 */
class HentaiTv : AnimeHttpSource() {

    override val name = "HentaiTv"

    override val baseUrl = "https://hentai.tv"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("HentaiTv", "all", 1))
    override val id: Long = 1494142395610847787L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    private fun httpGet(url: String, referer: String? = null, ajax: Boolean = false): Response {
        val builder = Request.Builder().url(url).headers(headers)
        if (referer != null) builder.header("Referer", referer)
        if (ajax) builder.header("X-Requested-With", "XMLHttpRequest")
        return client.newCall(builder.build()).execute()
    }

    private fun body(response: Response): String = response.use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IOException("HentaiTv: HTTP ${resp.code}")
        text
    }

    /** One level of JSON-string unescaping (flight payloads are double-escaped). */
    private fun unescape(html: String): String = html.replace("\\\"", "\"")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/api/browse?page=$page&sort=Most%20Viewed", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseBrowse(response)

    // ============================== Latest ================================

    // The API default sort is most-recent, so latest omits the sort param.
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/api/browse?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseBrowse(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = mutableListOf<String>()
        filters.firstOrNull { it is GenreFilter }?.let {
            val checked = (it as GenreFilter).state.filter { box -> box.state }.map { box -> box.name }
            if (checked.isNotEmpty()) {
                params.add("genres=" + checked.joinToString(",") { g -> URLEncoder.encode(g, "UTF-8") })
            }
        }
        filters.firstOrNull { it is SortFilter }?.let {
            val token = SORTS.values.toList()[(it as SortFilter).state]
            if (token.isNotBlank()) params.add("sort=" + URLEncoder.encode(token, "UTF-8"))
        }
        if (query.isNotBlank()) {
            return GET("$baseUrl/api/search?q=${URLEncoder.encode(query, "UTF-8")}", headers)
        }
        params.add(0, "page=$page")
        return GET("$baseUrl/api/browse?${params.joinToString("&")}", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseBrowse(response, paginated = false)

    /** JSONArray -> List<JSONObject> (org.json arrays are not Kotlin iterables). */
    private fun JSONArray.toObjectList(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    private fun parseBrowse(response: Response, paginated: Boolean = true): AnimesPage {
        val data = JSONObject(body(response))
        val videos = data.optJSONArray("videos") ?: JSONArray()
        val items = videos.toObjectList().mapNotNull { animeFrom(it) }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasMore = paginated && page < data.optInt("pages", page)
        return AnimesPage(items, hasMore)
    }

    private fun animeFrom(v: JSONObject): SAnime? {
        val slug = v.optString("slug").takeIf { it.isNotBlank() } ?: return null
        return SAnime.create().apply {
            title = v.optString("title").trim().ifBlank { slug }
            url = "hentai/$slug"
            thumbnail_url = v.optString("cover").takeIf { it.isNotBlank() }?.let { "$baseUrl$it" }
            genre = v.optJSONArray("tags")?.joinTags()
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Details ===============================

    private fun JSONArray.joinTags(): String {
        val out = StringBuilder()
        for (i in 0 until length()) {
            out.append(optString(i))
            if (i < length() - 1) out.append(", ")
        }
        return out.toString()
    }

    /**
     * Episode pages carry the full video object (titleSlug, description,
     * studio, tags) in the flight payload; the series page holds the episode
     * list, so details re-points the anime url at /series/{titleSlug}/.
     */
    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}/", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val html = unescape(body(response))
        val titleSlug = TITLE_SLUG.find(html)?.groupValues?.get(1)
            ?: throw IOException("HentaiTv: no series data on ${response.request.url}")
        val meta = firstVideoObject(html, titleSlug)
        return SAnime.create().apply {
            title = meta?.optString("title")?.trim()?.ifBlank { titleSlug } ?: titleSlug
            url = "series/$titleSlug"
            thumbnail_url = meta?.optString("cover")?.takeIf { it.isNotBlank() }?.let { "$baseUrl$it" }
            genre = meta?.optJSONArray("tags")?.joinTags()
            description = listOfNotNull(
                meta?.optString("description")?.takeIf { it.isNotBlank() },
                meta?.optString("brand")?.takeIf { it.isNotBlank() }?.let { "Studio: $it" },
            ).joinToString("\n\n")
            status = SAnime.UNKNOWN
        }
    }

    /** Extracts the first flight JSON object of the given series (balanced braces). */
    private fun firstVideoObject(html: String, titleSlug: String): JSONObject? {
        var from = 0
        while (true) {
            val slugAt = html.indexOf("\"titleSlug\":\"$titleSlug\"", from)
            if (slugAt < 0) return null
            val start = html.lastIndexOf("{\"id\"", slugAt)
            if (start < 0) return null
            runCatching { JSONObject(sliceJson(html, start)) }
                .getOrNull()
                ?.takeIf { it.optString("titleSlug") == titleSlug }
                ?.let { return it }
            from = slugAt + 1
        }
    }

    /** Returns the balanced JSON object starting at [start]. */
    private fun sliceJson(html: String, start: Int): String {
        var depth = 0
        var inStr = false
        var prev = ' '
        for (i in start until html.length) {
            val c = html[i]
            if (inStr) {
                if (c == '"' && prev != '\\') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(start, i + 1)
                    }
                }
            }
            prev = c
        }
        return html.substring(start)
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}/", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        body(response) // ensure the fetch itself succeeded
        val titleSlug = response.request.url.pathSegments.lastOrNull { it.isNotBlank() }
            ?: throw IOException("HentaiTv: bad series url")
        val html = unescape(body(httpGet("$baseUrl/series/$titleSlug/", response.request.url.toString())))
        val epRegex = Regex("\"slug\":\"(" + Regex.escape(titleSlug) + "-episode-(\\d+))\"")
        val matches = epRegex.findAll(html).toList()
        if (matches.isEmpty()) throw IOException("HentaiTv: no episodes for $titleSlug")
        return matches.map { m ->
            val slug = m.groupValues[1]
            val num = m.groupValues[2].toIntOrNull() ?: 1
            SEpisode.create().apply {
                url = "hentai/$slug"
                name = "Episode $num"
                episode_number = num.toFloat()
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/${episode.url}/", headers)

    override fun videoListParse(response: Response): List<Video> {
        val html = unescape(body(response))
        val embed = EMBED_RE.find(html)?.groupValues?.get(1)
            ?: throw IOException("HentaiTv: no player embed on ${response.request.url}")
        val videoUrl = resolveNhPlayer(embed, response.request.url.toString())
        return listOf(Video(videoUrl, "MP4", videoUrl, headers = videoHeaders()))
    }

    private fun videoHeaders(): Headers = headers.newBuilder()
        .set("Referer", "https://nhplayer.com/")
        .build()

    /**
     * Replicates the nhplayer browser challenge:
     * /v/{id}/ -> player.php (DOM parts + _pV) -> player-core-v2.php (sc/rid)
     * -> SHA-256 PoW -> get-video-url-v2.php -> signed mp4 url.
     */
    private fun resolveNhPlayer(embedUrl: String, pageRef: String): String {
        val vPage = body(httpGet(embedUrl, pageRef))
        val playerPath = PLAYER_DATAID.find(vPage)?.groupValues?.get(1)
            ?: throw IOException("HentaiTv: player.php link missing")
        val playerUrl = "https://nhplayer.com$playerPath"
        val started = System.currentTimeMillis()
        val playerHtml = body(httpGet(playerUrl, embedUrl))

        val pv = PV_BLOCK.find(playerHtml)?.groupValues?.get(1)
            ?: throw IOException("HentaiTv: _pV missing")
        val vid = pvParam(pv, "vid") ?: throw IOException("HentaiTv: vid missing")
        val ct = pvParam(pv, "ct").orEmpty()
        val pid = pvParam(pv, "pid").orEmpty()
        val st = pvParam(pv, "st").orEmpty()

        val corePath = CORE_SCRIPT.find(playerHtml)?.groupValues?.get(1)
            ?: throw IOException("HentaiTv: core script missing")
        val coreJs = body(httpGet("https://nhplayer.com/$corePath", playerUrl))

        val sc = SERVER_TOKEN.find(coreJs)?.groupValues?.getOrNull(1).orEmpty()
        val rid = REQUEST_ID.find(coreJs)?.groupValues?.getOrNull(1).orEmpty()
        val ids = ELEMENT_IDS.findAll(coreJs).map { it.groupValues[1] }.toList()
        val attrs = ELEMENT_ATTRS.findAll(coreJs).map { it.groupValues[1] }.toList()
        if (ids.size < 5 || attrs.size < 3) throw IOException("HentaiTv: challenge selectors missing")

        fun elementAttr(id: String, attr: String): String {
            val idx = playerHtml.indexOf("id=\"$id\"")
            if (idx < 0) return ""
            val tagStart = playerHtml.lastIndexOf('<', idx)
            val tagEnd = playerHtml.indexOf('>', idx)
            if (tagStart < 0 || tagEnd < 0) return ""
            val tag = playerHtml.substring(tagStart, tagEnd + 1)
            return Regex("$attr=\"([^\"]*)\"").find(tag)?.groupValues?.getOrNull(1).orEmpty()
        }
        fun templateValue(id: String): String {
            val m = Regex("<template[^>]*id=\"$id\"[^>]*>(.*?)</template>", RegexOption.DOT_MATCHES_ALL)
                .find(playerHtml) ?: return ""
            return HEX8.find(m.groupValues[1])?.groupValues?.getOrNull(1).orEmpty()
        }

        // finder order in the core script: [p1, p2(input), p3, p4(template), ts]
        val p1 = elementAttr(ids[0], attrs[0])
        val p2 = elementAttr(ids[1], "value")
        val p3 = elementAttr(ids[2], attrs[1])
        val p4 = templateValue(ids[3])
        val ts = elementAttr(ids[4], attrs[2])
        if (p1.length != 8 || p2.length != 8 || p3.length != 8 || p4.length != 8 || ts.length < 8) {
            throw IOException("HentaiTv: challenge parts incomplete")
        }

        // Proof of work: sha256(p1+p2+p3+p4+ts + hex(n)) must start with 0x00.
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = (p1 + p2 + p3 + p4 + ts).toByteArray(Charsets.US_ASCII)
        var n = 0L
        val pow: String
        while (true) {
            digest.reset()
            digest.update(prefix)
            val hash = digest.digest(n.toString(16).toByteArray(Charsets.US_ASCII))
            if (hash[0].toInt() == 0) {
                pow = n.toString(16)
                break
            }
            n++
            if (n > 10_000_000L) throw IOException("HentaiTv: PoW exhausted")
        }

        // The server checks time-on-page and analyzes the fingerprint payload;
        // a short plausible session (small mouse trajectory) passes it.
        val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(0L)
        if (elapsed < 1000) {
            try { Thread.sleep(1000 - elapsed) } catch (_: InterruptedException) { }
        }
        val query = buildQuery(vid, ct, p1, p2, p3, p4, ts, sc, rid, pow, pid, st)
        val resp = body(httpGet("https://nhplayer.com/get-video-url-v2.php?$query", playerUrl, ajax = true))
        val url = try {
            JSONObject(resp).optString("url")
        } catch (_: JSONException) {
            throw IOException("HentaiTv: challenge rejected (${resp.take(120)})")
        }
        if (url.isBlank()) throw IOException("HentaiTv: no video url (challenge rejected)")
        return url
    }

    private fun buildQuery(
        vid: String,
        ct: String,
        p1: String,
        p2: String,
        p3: String,
        p4: String,
        ts: String,
        sc: String,
        rid: String,
        pow: String,
        pid: String,
        st: String,
    ): String {
        val fp = fingerprint()
        val params = linkedMapOf(
            "vid" to vid, "c" to ct, "p1" to p1, "p2" to p2, "p3" to p3, "p4" to p4,
            "t" to ts, "sc" to sc, "rid" to rid, "fp" to fp, "df" to "",
            "pow" to pow, "pid" to pid, "st" to st,
        )
        return params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
    }

    /** Base64 fingerprint matching the site's expected shape (idle session). */
    private fun fingerprint(): String = try {
        var x = 400 + Random.nextInt(0, 200)
        var y = 800 + Random.nextInt(0, 200)
        var t = 900
        val moves = StringBuilder("[")
        repeat(20) { i ->
            x += Random.nextInt(-60, 61)
            y += Random.nextInt(-40, 41)
            t += Random.nextInt(30, 91)
            if (i > 0) moves.append(",")
            moves.append("[$x,$y,$t]")
        }
        moves.append("]")
        val payload = "{\"t\":2400,\"mm\":$moves,\"tm\":[],\"cl\":[[$x,$y,$t]],\"kp\":[],\"sc\":[]," +
            "\"i\":1,\"mc\":24,\"tc\":0,\"cc\":1,\"kc\":0," +
            "\"b\":{\"sw\":1080,\"sh\":2340,\"aw\":1080,\"ah\":2280,\"cd\":24,\"pd\":24," +
            "\"tz\":0,\"hc\":8,\"dm\":8,\"pl\":\"Linux armv8l\",\"lang\":\"en-US\"," +
            "\"langs\":\"en-US,en\",\"dpr\":2.75,\"ww\":1080,\"wh\":2280,\"touch\":true}}"
        Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    } catch (_: Exception) {
        ""
    }

    private fun pvParam(pv: String, key: String): String? =
        Regex("\\\"$key\\\":\\\"([^\"]*)\\\"").find(pv)?.groupValues?.getOrNull(1)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply to browse (leave search blank)"),
        GenreFilter("Genres", GENRES),
        SortFilter("Sort by"),
    )

    private class GenreFilter(name: String, values: List<String>) :
        AnimeFilter.Group<GenreCheckbox>(name, values.map { GenreCheckbox(it) })

    private class GenreCheckbox(name: String) : AnimeFilter.CheckBox(name, false)

    private class SortFilter(name: String) :
        AnimeFilter.Select<String>(name, SORTS.keys.toTypedArray())

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private val TITLE_SLUG = Regex("\\\"titleSlug\\\":\\\"([\\w\\-]+)\\\"")
        private val EMBED_RE = Regex("<iframe[^>]+src=\\\"(https://nhplayer\\.com/v/[^\\\"]+)\\\"")
        private val PLAYER_DATAID = Regex("data-id=\\\"(/player\\.php\\?[^\"]+)\\\"")
        private val PV_BLOCK = Regex("_pV=\\{(.+?)\\}\\s*;?\\s*</", RegexOption.DOT_MATCHES_ALL)
        private val CORE_SCRIPT = Regex("src=\\\"(player-core[^\\\"]+)\\\"")
        private val SERVER_TOKEN = Regex("var _\\w{6,9}='([0-9a-f]+\\.[0-9a-f]+)'")
        private val REQUEST_ID = Regex("id:'([0-9a-f]{16})'")
        private val ELEMENT_IDS = Regex("getElementById\\('([^']+)'\\)")
        private val ELEMENT_ATTRS = Regex("getAttribute\\('([^']+)'\\)")
        private val HEX8 = Regex("([0-9a-f]{8})")

        private val GENRES = listOf(
            "Vanilla", "anal", "Romance", "Big Boobs", "Maid", "incest", "Comedy", "Glasses",
            "School", "Swimsuit", "loli", "virgin", "Uncensored", "bondage", "Censored",
            "Blow Job", "Oral", "Creampie", "Masturbation", "fantasy", "Ahegao", "teacher",
            "harem", "Threesome", "milf", "Cosplay", "ntr", "Tsundere", "nurse", "tentacle",
            "monster", "orgy", "boob job", "toys", "rape", "Short", "yaoi", "plot",
            "mind control", "facial", "pregnant", "hand job", "HD", "lactation", "rimjob",
            "x-ray", "Doggy Style", "foot job", "yuri", "gangbang", "filmed", "horror",
            "public sex", "ugly bastard", "reverse rape", "watersports", "nekomimi", "shota",
            "dark skin", "BDSM", "inflation", "mind break", "futanari", "pov", "Nudity",
            "Blowjob", "Exhibitionism", "Big Breasts", "Paizuri", "Hentai", "Voyeurism",
            "Corruption", "Magic", "Huge Breasts", "Cheating", "High School", "Schoolgirl",
            "Big Tits", "First Kiss", "Drama", "office lady", "Sex Toys", "Group Sex",
            "FFM Threesome", "Humiliation", "scat", "Impregnation", "Housewife", "Elf",
            "Gyaru", "Erotic Game", "Teasing", "softcore", "3d", "Cream Pie", "Sex",
            "Small tits", "School Life", "trap", "Tentacles", "Public", "Femdom",
            "monster girl", "Blackmail",
        )

        private val SORTS = linkedMapOf(
            "Latest" to "",
            "Most Viewed" to "Most Viewed",
            "Top Rated" to "Top Rated",
            "A-Z" to "A-Z",
        )
    }
}
