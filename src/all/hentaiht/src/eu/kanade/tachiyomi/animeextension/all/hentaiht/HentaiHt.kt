/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaiht

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * HENTAI.HT (https://hentai.ht)
 *
 * Everything is behind a signed age-confirmation cookie:
 *   1. POST /api/v1/age-confirmation  (empty JSON body) -> 204 + __Secure-hht_age
 *   2. GET  /api/v1/catalog?page=N[&sort=recent|score|year|alpha]  (60/page, default popularity)
 *   3. GET  /api/v1/catalog/search?q=...
 *   4. GET  /api/v1/catalog/titles/{routeId}  -> title + episodes[] (with previewUrl)
 *   5. GET  /api/v1/playback/{slug}/{ep}?language=ENG SUB&censorship=censored
 *      -> asset.manifestUrl "/stream/v1/{id}/media.m3u8" (+ eng/fra .vtt subtitles)
 *
 * The cookie is fetched once per process and reused; the playback manifest and
 * its segments are only served to requests carrying it (nginx 403 otherwise).
 */
class HentaiHt : AnimeHttpSource() {

    override val name = "HentaiHt"

    override val baseUrl = "https://hentai.ht"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("HentaiHt", "all", 1))
    override val id: Long = 5901363947030062327L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== Age cookie ============================

    @Volatile private var ageConfirmed = false

    private fun confirmAge() {
        if (ageConfirmed) return
        synchronized(this) {
            if (ageConfirmed) return
            val body = "{}".toRequestBody("application/json".toMediaType())
            client.newCall(
                Request.Builder()
                    .url("$baseUrl/api/v1/age-confirmation")
                    .post(body)
                    .headers(headers)
                    .build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HentaiHt: age confirmation failed (${resp.code})")
            }
            ageConfirmed = true
        }
    }

    private fun apiGetBody(url: String): String {
        confirmAge()
        return client.newCall(GET(url, headers)).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HentaiHt: HTTP ${resp.code} for $url")
            text
        }
    }

    private fun json(body: String): JSONObject =
        JSONObject(body.ifBlank { throw IOException("HentaiHt: empty body") })

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/v1/catalog?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseCatalog(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/api/v1/catalog?page=$page&sort=recent", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseCatalog(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/api/v1/catalog/search?q=${URLEncoder.encode(query, "UTF-8")}", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = json(response.body?.string().orEmpty())
        val titles = data.optJSONArray("titles") ?: JSONArray()
        // The search endpoint returns the full match set; no pagination.
        return AnimesPage(titles.mapNotNull { titleFrom(it as JSONObject) }, false)
    }

    // ============================== Catalog ===============================

    private fun parseCatalog(response: Response): AnimesPage {
        val data = json(response.body?.string().orEmpty())
        val titles = data.optJSONArray("titles") ?: JSONArray()
        val items = titles.mapNotNull { titleFrom(it as JSONObject) }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val totalPages = data.optInt("totalPages", page)
        return AnimesPage(items, page < totalPages)
    }

    private fun titleFrom(t: JSONObject): SAnime? {
        val slug = t.optString("slug").takeIf { it.isNotBlank() } ?: return null
        val routeId = t.optString("routeId").takeIf { it.isNotBlank() } ?: return null
        return SAnime.create().apply {
            title = t.optString("name").ifBlank { t.optString("english") }.trim()
            // details/episodes endpoint keyed by route id; slug rides along for playback
            url = "titles/$routeId/$slug"
            thumbnail_url = t.optString("coverFull").ifBlank { t.optString("cover") }
        }
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        confirmAge()
        val url = anime.url.substringBeforeLast('/')
        return GET("$baseUrl/api/v1/catalog/$url", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val data = json(response.body?.string().orEmpty())
        val t = data.optJSONObject("title") ?: throw IOException("HentaiHt: no title in details")
        val sb = StringBuilder()
        t.optString("native").takeIf { it.isNotBlank() && it != t.optString("name") }?.let { sb.append(it).append('\n') }
        t.optJSONArray("synonyms")?.takeIf { it.length() > 0 }?.let { arr ->
            sb.append("Synonyms: ")
            for (i in 0 until arr.length()) sb.append(arr.optString(i)).append(", ")
            sb.setLength(sb.length - 2).append('\n')
        }
        t.optJSONArray("studios")?.takeIf { it.length() > 0 }?.let { arr ->
            val list = (0 until arr.length()).joinToString(", ") { arr.optString(it) }
            sb.append("Studio: ").append(list).append('\n')
        }
        t.optString("format").takeIf { it.isNotBlank() }?.let { sb.append("Format: ").append(it).append('\n') }
        t.optString("status").takeIf { it.isNotBlank() }?.let { sb.append("Status: ").append(it).append('\n') }
        t.optInt("year", 0).takeIf { it > 0 }?.let { sb.append("Year: ").append(it).append('\n') }
        t.optString("bestQuality").takeIf { it.isNotBlank() }?.let { sb.append("Quality: ").append(it).append('\n') }
        t.optJSONArray("languages")?.takeIf { it.length() > 0 }?.let { arr ->
            sb.append("Audio/Subs: ")
            for (i in 0 until arr.length()) sb.append(arr.optString(i)).append(", ")
            sb.setLength(sb.length - 2).append('\n')
        }
        t.optJSONArray("tags")?.takeIf { it.length() > 0 }?.let { arr ->
            sb.append("Tags: ")
            for (i in 0 until arr.length()) sb.append(arr.optString(i)).append(", ")
            sb.setLength(sb.length - 2).append('\n')
        }
        val synopsis = data.optString("synopsis")
        return SAnime.create().apply {
            title = t.optString("name").ifBlank { t.optString("english") }.trim()
            thumbnail_url = t.optString("coverFull").ifBlank { t.optString("cover") }
            description = listOf(synopsis, sb.toString().trim()).filter { it.isNotBlank() }
                .joinToString("\n\n")
            status = when (t.optString("status")) {
                "finished" -> SAnime.COMPLETED
                "ongoing", "airing" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = json(response.body?.string().orEmpty())
        val t = data.optJSONObject("title") ?: throw IOException("HentaiHt: no title in episodes")
        val slug = t.optString("slug")
        val eps = t.optJSONArray("episodes") ?: throw IOException("HentaiHt: no episodes")
        return (0 until eps.length()).map { i ->
            val e = eps.getJSONObject(i)
            val num = e.optInt("number", i + 1)
            SEpisode.create().apply {
                // slug is what /api/v1/playback/{slug}/{ep} wants
                url = "playback/$slug/$num"
                name = e.optString("label").ifBlank { "Episode $num" }
                e.optString("titleEnglish").takeIf { it.isNotBlank() }?.let { name += " — $it" }
                episode_number = num.toFloat()
                // Per-episode preview thumbnails (site-rendered WebP previews).
                e.optString("previewUrl").takeIf { it.isNotBlank() }?.let {
                    setEpisodeField(this, "preview_url", baseUrl + it)
                }
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request {
        confirmAge()
        // episode.url = playback/{slug}/{ep}
        return GET("$baseUrl/api/v1/${episode.url}?language=ENG%20SUB&censorship=censored", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val data = json(response.body?.string().orEmpty())
        val asset = data.optJSONObject("asset") ?: throw IOException("HentaiHt: no asset (stream may be down)")
        val manifest = asset.optString("manifestUrl")
        if (manifest.isBlank()) throw IOException("HentaiHt: empty manifest")
        val quality = data.optString("quality").ifBlank { "HLS" }
        val lang = data.optString("language").ifBlank { "SUB" }
        // Subtitle tracks (eng/fra .vtt) come as side files on the same stream id;
        // AniZen's ExoPlayer picks up sidecar subs via its subtitle selector.
        return listOf(Video("$baseUrl$manifest", "$quality $lang", "$baseUrl$manifest", headers = videoHeaders()))
    }

    private fun videoHeaders(): Headers = headers.newBuilder()
        .set("Referer", "$baseUrl/watch")
        .set("Origin", baseUrl)
        .build()

    // ============================== Helpers ===============================

    /**
     * Sets a field on SEpisode that exists in AniZen's runtime (lib v16+)
     * but not in the lib-14 stub this extension compiles against.
     */
    private fun setEpisodeField(episode: SEpisode, fieldName: String, value: String) {
        try {
            val setter = episode.javaClass.getMethod(
                "set${fieldName.replaceFirstChar { it.uppercase() }}",
                String::class.java,
            )
            setter.invoke(episode, value)
        } catch (_: NoSuchMethodException) {
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
