/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaiht

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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

    // Every request builder must confirm the age cookie first. Without it the API
    // answers 403 {"error":"age_confirmation_required"}, and AniZen's Cloudflare
    // interceptor swallows that 403 and reports "Failed to bypass Cloudflare" -
    // which is exactly how this source used to break on browse.
    override fun popularAnimeRequest(page: Int): Request {
        confirmAge()
        return GET("$baseUrl/api/v1/catalog?page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseCatalog(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request {
        confirmAge()
        return GET("$baseUrl/api/v1/catalog?page=$page&sort=recent", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseCatalog(response)

    // ============================== Search ================================

    // The catalogue API takes one value per facet, so a multi-select is served by
    // fetching one catalogue page per ticked value and merging them (OR). Tag
    // values are tag names the API validates; studio values come from the rows'
    // own `studio` field.

    private var lastFilters: AnimeFilterList? = null
    private var lastPage: Int = 1

    /** Studio printed on each catalogue row, so exclude taps can be honoured. */
    private val studioFacets = HashMap<String, String>()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        lastFilters = filters
        lastPage = page
        confirmAge()
        if (query.isNotBlank()) {
            return GET("$baseUrl/api/v1/catalog/search?q=${URLEncoder.encode(query, "UTF-8")}", headers)
        }
        return GET(
            catalogUrl(
                page,
                filters,
                selectedTagNames(filters).firstOrNull(),
                selectedStudioNames(filters, AnimeFilter.TriState.STATE_INCLUDE).firstOrNull(),
            ),
            headers,
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.encodedPath.contains("/catalog/search")) return searchResultsPage(response)
        val first = parseCatalog(response)
        val extraTags = selectedTagNames(lastFilters).drop(1)
        val extraStudios = selectedStudioNames(lastFilters, AnimeFilter.TriState.STATE_INCLUDE).drop(1)
        if (extraTags.isEmpty() && extraStudios.isEmpty()) return applyStudioExcludes(first)
        val merged = LinkedHashMap<String, SAnime>()
        first.animes.forEach { merged[it.url] = it }
        var hasMore = first.hasNextPage
        val extra: List<Pair<String?, String?>> =
            extraTags.map { Pair(it, null) } + extraStudios.map { Pair(null, it) }
        extra.forEach { (tag, studio) ->
            client.newCall(GET(catalogUrl(lastPage, lastFilters, tag, studio), headers)).execute().use { resp ->
                val page = parseCatalog(resp)
                page.animes.forEach { merged.putIfAbsent(it.url, it) }
                hasMore = hasMore || page.hasNextPage
            }
        }
        return applyStudioExcludes(AnimesPage(merged.values.toList(), hasMore))
    }

    /** Search answers with the full match set; no pagination. */
    private fun searchResultsPage(response: Response): AnimesPage {
        val data = json(response.body?.string().orEmpty())
        val titles = data.optJSONArray("titles") ?: JSONArray()
        return AnimesPage(titles.toObjectList().mapNotNull { titleFrom(it) }, false)
    }

    private fun applyStudioExcludes(page: AnimesPage): AnimesPage {
        val excluded = selectedStudioNames(lastFilters, AnimeFilter.TriState.STATE_EXCLUDE)
        if (excluded.isEmpty()) return page
        return AnimesPage(page.animes.filter { studioFacets[it.url].orEmpty() !in excluded }, page.hasNextPage)
    }

    /** Builds /api/v1/catalog from the ticked filters, overriding tag/studio when given. */
    private fun catalogUrl(page: Int, filters: AnimeFilterList?, tag: String?, studio: String?): String {
        val builder = StringBuilder("$baseUrl/api/v1/catalog?page=$page")
        tag?.let { builder.append("&tag=").append(URLEncoder.encode(it, "UTF-8")) }
        studio?.let { builder.append("&studio=").append(URLEncoder.encode(it, "UTF-8")) }
        appendOption(builder, SORT_PARAM, SORT_SLUGS.getOrNull(selectedIndex<SortFilter>(filters)))
        appendOption(builder, YEAR_PARAM, YEAR_SLUGS.getOrNull(selectedIndex<YearFilter>(filters)))
        appendOption(builder, STATUS_PARAM, STATUS_SLUGS.getOrNull(selectedIndex<StatusFilter>(filters)))
        appendOption(builder, FORMAT_PARAM, FORMAT_SLUGS.getOrNull(selectedIndex<FormatFilter>(filters)))
        return builder.toString()
    }

    private fun appendOption(builder: StringBuilder, param: String, slug: String?) {
        if (slug.isNullOrEmpty()) return
        builder.append('&').append(param).append('=').append(slug)
    }

    // ============================== Catalog ===============================

    private fun parseCatalog(response: Response): AnimesPage {
        val data = json(response.body?.string().orEmpty())
        val titles = data.optJSONArray("titles") ?: JSONArray()
        val items = titles.toObjectList().mapNotNull { titleFrom(it) }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val totalPages = data.optInt("totalPages", page)
        return AnimesPage(items, page < totalPages)
    }

    private fun titleFrom(t: JSONObject): SAnime? {
        val slug = t.optString("slug").takeIf { it.isNotBlank() } ?: return null
        val routeId = t.optString("routeId").takeIf { it.isNotBlank() } ?: return null
        val path = "titles/$routeId/$slug"
        // Remember the row's own studio so an exclude tap can be honoured.
        t.optString("studio").takeIf { it.isNotBlank() }?.let { studioFacets[path] = it }
        return SAnime.create().apply {
            title = t.optString("name").ifBlank { t.optString("english") }.trim()
            // details/episodes endpoint keyed by route id; slug rides along for playback
            url = path
            thumbnail_url = t.optString("coverFull").ifBlank { t.optString("cover") }
        }
    }

    // ============================== Details ===============================

    /** Copies a JSONArray of strings into a comma-separated line on the builder. */
    private fun StringBuilder.appendLine(label: String, arr: JSONArray) {
        append(label)
        for (i in 0 until arr.length()) {
            append(arr.optString(i))
            if (i < arr.length() - 1) append(", ")
        }
        append('\n')
    }

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
        t.optJSONArray("synonyms")?.takeIf { it.length() > 0 }?.let { sb.appendLine("Synonyms: ", it) }
        t.optJSONArray("studios")?.takeIf { it.length() > 0 }?.let { sb.appendLine("Studio: ", it) }
        t.optString("format").takeIf { it.isNotBlank() }?.let { sb.append("Format: ").append(it).append('\n') }
        t.optString("status").takeIf { it.isNotBlank() }?.let { sb.append("Status: ").append(it).append('\n') }
        t.optInt("year", 0).takeIf { it > 0 }?.let { sb.append("Year: ").append(it).append('\n') }
        t.optString("bestQuality").takeIf { it.isNotBlank() }?.let { sb.append("Quality: ").append(it).append('\n') }
        t.optJSONArray("languages")?.takeIf { it.length() > 0 }?.let { sb.appendLine("Audio/Subs: ", it) }
        t.optJSONArray("tags")?.takeIf { it.length() > 0 }?.let { sb.appendLine("Tags: ", it) }
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

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply to browse (leave search blank)"),
        TagGroup(),
        StudioGroup(),
        AnimeFilter.Header("Released"),
        YearFilter(),
        AnimeFilter.Header("Type & status"),
        FormatFilter(),
        StatusFilter(),
        AnimeFilter.Header("Sorting"),
        SortFilter(),
    )

    // The lib's AnimeFilter.CheckBox is abstract, so a concrete subclass is required.
    private class TagCheckBox(name: String, state: Boolean = false) :
        AnimeFilter.CheckBox(name, state)

    private class TagGroup : AnimeFilter.Group<AnimeFilter.CheckBox>(
        "Genres",
        TAG_NAMES.map { TagCheckBox(it) },
    )

    // TriState: one tap includes the studio, a second tap excludes it (the rows
    // carry their own studio, so the exclude is enforced on the parsed result).
    private class StudioTriState(name: String) :
        AnimeFilter.TriState(name, AnimeFilter.TriState.STATE_IGNORE)

    private class StudioGroup : AnimeFilter.Group<AnimeFilter.TriState>(
        "Studios",
        STUDIO_NAMES.map { StudioTriState(it) },
    )

    private class YearFilter : AnimeFilter.Select<String>("Year", YEAR_NAMES, 0)
    private class StatusFilter : AnimeFilter.Select<String>("Status", STATUS_NAMES, 0)
    private class FormatFilter : AnimeFilter.Select<String>("Format", FORMAT_NAMES, 0)
    private class SortFilter : AnimeFilter.Select<String>("Sort by", SORT_NAMES, 0)

    private fun flatFilters(filters: AnimeFilterList?): List<AnimeFilter<*>> = filters.orEmpty().flatMap { filter ->
        if (filter is AnimeFilter.Group<*>) {
            filter.state.filterIsInstance<AnimeFilter<*>>()
        } else {
            listOf(filter)
        }
    }

    private fun selectedTagNames(filters: AnimeFilterList?): List<String> =
        flatFilters(filters).filterIsInstance<TagCheckBox>()
            .filter { it.state }
            .map { it.name }
            .filter { TAG_NAMES.contains(it) }

    private fun selectedStudioNames(filters: AnimeFilterList?, state: Int): List<String> =
        flatFilters(filters).filterIsInstance<StudioTriState>()
            .filter { it.state == state }
            .map { it.name }
            .filter { STUDIO_NAMES.contains(it) }

    private inline fun <reified T : AnimeFilter.Select<String>> selectedIndex(filters: AnimeFilterList?): Int =
        flatFilters(filters).filterIsInstance<T>().firstOrNull()?.state ?: 0

    // ============================== Helpers ===============================

    /** JSONArray -> List<JSONObject> (org.json arrays are not Kotlin iterables). */
    private fun JSONArray.toObjectList(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

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

        private const val SORT_PARAM = "sort"
        private const val YEAR_PARAM = "year"
        private const val STATUS_PARAM = "status"
        private const val FORMAT_PARAM = "format"

        // Values accepted by /api/v1/catalog (verified live against the API).
        private val SORT_NAMES = arrayOf("Default", "Newest", "Score", "Year", "A-Z")
        private val SORT_SLUGS = arrayOf("", "recent", "score", "year", "alpha")

        private val STATUS_NAMES = arrayOf("Any", "Airing", "Finished")
        private val STATUS_SLUGS = arrayOf("", "airing", "finished")

        private val FORMAT_NAMES = arrayOf("Any", "OVA", "ONA", "Special")
        private val FORMAT_SLUGS = arrayOf("", "OVA", "ONA", "SPECIAL")
    }
}
