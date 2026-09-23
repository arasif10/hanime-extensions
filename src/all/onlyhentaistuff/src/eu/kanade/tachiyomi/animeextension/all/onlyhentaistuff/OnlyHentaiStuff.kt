/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.onlyhentaistuff

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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder

/**
 * OnlyHentaiStuff (https://www.onlyhentaistuff.com)
 *
 * KVS-based per-video catalog. Each listing card is a standalone episode
 * post (/videos/{id}/{slug}/) with its own stream.
 *
 * Video resolution:
 *  1. The video page embeds `var flashvars = {...}` with
 *     `license_code: '$848068927979278'` and
 *     `video_url: 'function/0/https://.../get_file/1/<sha1>/.../file.mp4/'`.
 *  2. The kt_player runtime scrambles the first 32 chars of the sha1 with a
 *     fixed permutation derived from the license hotkeys (the remaining 8
 *     chars pass through), then appends `?rnd=<epoch ms>`.
 *  3. The scrambled URL 302-redirects to a signed CDN link.
 *
 * The flashvars hashes are long-lived; the `?rnd=` cache-buster and Referer
 * are what the CDN requires. License code verified constant across videos.
 */
class OnlyHentaiStuff : AnimeHttpSource() {

    override val name = "OnlyHentaiStuff"

    override val baseUrl = "https://www.onlyhentaistuff.com"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("OnlyHentaiStuff", "all", 1))
    override val id: Long = 8472081291031779782L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    private val ajaxHeaders: Headers = headersBuilder()
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET(if (page == 1) "$baseUrl/most-popular/" else "$baseUrl/most-popular/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseCatalog(response)

    // ============================== Latest ================================

    // /latest-updates/ is server-rendered with plain page URLs (/latest-updates/2/).
    override fun latestUpdatesRequest(page: Int): Request =
        GET(if (page == 1) "$baseUrl/latest-updates/" else "$baseUrl/latest-updates/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseCatalog(response)

    // ============================== Search ================================

    // The picker runs in the app and the merge runs in the parse step, so the
    // selection has to survive between the two calls.
    private var pendingMerge: MergePlan? = null

    private class MergePlan(val urls: List<String>)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected() ?: SORTS[0].second
        val categories = picked(filters).filterIsInstance<CategoryFilter>().map { it.slug }
        val tags = picked(filters).filterIsInstance<TagFilter>().map { it.slug }
        val models = picked(filters).filterIsInstance<ModelFilter>().map { it.slug }
        val q = query.trim()

        if (q.isBlank()) {
            // Tag, model and category browsing are all plain URLs on this site, so
            // every ticked box contributes its own listing and the results are merged.
            val bases = buildList {
                tags.forEach { add("$baseUrl/tags/$it") }
                models.forEach { add("$baseUrl/models/$it") }
                categories.forEach { add("$baseUrl/categories/$it") }
            }
            if (bases.isNotEmpty()) {
                pendingMerge = if (bases.size > 1) {
                    MergePlan(bases.drop(1).map { browseUrl(it, sort, page) })
                } else {
                    null
                }
                return GET(browseUrl(bases.first(), sort, page), headers)
            }
            // Sorting on its own still goes through the async block.
            if (sort.isNotEmpty()) return GET(buildSearchUrl("", "", sort, 1), ajaxHeaders)
            return popularAnimeRequest(page)
        }

        // A text query searches the whole catalogue. Category picks still narrow it,
        // through the numeric ids the async block expects, for the categories whose id
        // is known; the rest simply do not apply to a text search.
        val categoryIds = categories
            .mapNotNull { slug -> CATEGORY_SLUGS.entries.firstOrNull { it.value == slug }?.key }
            .joinToString(",")

        if (page > 1) {
            // The ?mode=async search block ignores from_pages; the working
            // page cursor is `from_videos` (verified: returns page 2 content).
            return GET(buildSearchUrl(q, categoryIds, sort, page), ajaxHeaders)
        }
        // Page 1: plain server-rendered results unless sort/category requested.
        if (sort.isEmpty() && categoryIds.isEmpty()) {
            return GET("$baseUrl/search/${URLEncoder.encode(q, "UTF-8")}/", headers)
        }
        return GET(buildSearchUrl(q, categoryIds, sort, 1), ajaxHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val first = parseCatalog(response, isSearch = true)
        val plan = pendingMerge ?: return first

        val merged = LinkedHashMap<String, SAnime>()
        first.animes.forEach { merged[it.url] = it }
        var hasNextPage = first.hasNextPage

        plan.urls.forEach { url ->
            runCatching {
                client.newCall(GET(url, headers)).execute().use { extra ->
                    val extraPage = parseCatalog(extra, isSearch = true)
                    extraPage.animes.forEach { merged.putIfAbsent(it.url, it) }
                    hasNextPage = hasNextPage || extraPage.hasNextPage
                }
            }
        }
        return AnimesPage(merged.values.toList(), hasNextPage)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Every ticked box adds its own listing to the results"),
        AnimeFilter.Header("A text query searches the catalogue (and ignores"),
        AnimeFilter.Header("tags/models); ticked categories still narrow it"),
        CategoryGroup(),
        TagGroup(),
        ModelGroup(),
    )

    private class SortFilter : AnimeFilter.Select<String>(
        "Sort by",
        SORTS.map { it.first }.toTypedArray(),
    ) {
        fun selected(): String = SORTS[state].second
    }

    private class CategoryFilter(name: String, val slug: String) : AnimeFilter.CheckBox(name)

    private class TagFilter(name: String, val slug: String) : AnimeFilter.CheckBox(name)

    private class ModelFilter(name: String, val slug: String) : AnimeFilter.CheckBox(name)

    private class CategoryGroup : AnimeFilter.Group<AnimeFilter<*>>(
        "Categories",
        CATEGORY_TERMS.map { CategoryFilter(it.second, it.first) },
    )

    private class TagGroup : AnimeFilter.Group<AnimeFilter<*>>(
        "Tags",
        TAG_TERMS.map { TagFilter(it.second, it.first) },
    )

    private class ModelGroup : AnimeFilter.Group<AnimeFilter<*>>(
        "Studios / directors",
        MODEL_TERMS.map { ModelFilter(it.second, it.first) },
    )

    private fun picked(filters: AnimeFilterList): List<AnimeFilter<*>> =
        filters.flatMap { filter ->
            if (filter is AnimeFilter.Group<*>) {
                filter.state.filterIsInstance<AnimeFilter<*>>().filter { it.state == true }
            } else {
                listOf(filter)
            }
        }

    // ============================= Catalogue ==============================

    private fun parseCatalog(response: Response, isSearch: Boolean = false): AnimesPage {
        if (!response.isSuccessful) {
            // Search pages past the result set 404 — treat as end of results.
            if (isSearch && response.code == 404) return AnimesPage(emptyList(), false)
            throw IOException("HTTP ${response.code} for ${response.request.url}")
        }
        val doc = response.asJsoup()
        return AnimesPage(catalogCards(doc), hasMoreCards(doc))
    }

    private fun catalogCards(doc: Document): List<SAnime> {
        return doc.select("div.item:has(a[href*=/videos/])").mapNotNull { el ->
            val a = el.selectFirst("a[href*=/videos/]") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!href.contains(Regex("/videos/\\d+/"))) return@mapNotNull null
            val title = (
                a.attr("title")
                    .takeIf { it.isNotBlank() }
                    ?: el.selectFirst("strong.title")?.text()?.trim()
                )
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SAnime.create().apply {
                this.title = title
                url = href.removePrefix("$baseUrl/").trim('/')
                thumbnail_url = el.selectFirst("img[data-original]")?.attr("data-original")
                    ?.takeIf { it.startsWith("http") }
                    ?: el.selectFirst("img[src^=http]")?.attr("src")
            }
        }.distinctBy { it.url }
    }

    private fun hasMoreCards(doc: Document): Boolean {
        // KVS renders div.pagination with anchors for further pages; on the
        // first/last page Back/First are <span>, not <a>.
        val pagination = doc.selectFirst("div.pagination") ?: return false
        return pagination.selectFirst("a[href]") != null
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}/", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.title().substringBefore(" Hentai Online").trim()
            author = doc.select("a[href*=/models/]").firstOrNull()?.text()?.trim()
            status = SAnime.UNKNOWN
            description = buildString {
                doc.selectFirst("#tab_video_info .info .item em")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }?.let { append(it) }
                val tags = doc.select("a[href*=/tags/]").eachText()
                    .map { it.trim() }.filter { it.isNotBlank() && it != "..." }
                if (tags.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Tags: ").append(tags.distinct().joinToString(", "))
                }
                doc.selectFirst("#tab_video_info .info .item")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }?.let {
                        if (isNotEmpty()) append("\n\n")
                        append(it)
                    }
            }
            genre = doc.select("a[href*=/categories/], a[href*=/tags/]")
                .eachText().map { it.trim() }
                .filter { it.isNotBlank() && it != "..." && it != "Categories" }
                .distinct().take(15).joinToString(", ")
        }
    }

    // ========================== Related / Recommended =====================

    // AniZen fills the "Recommended" row of its detail screen only when the
    // extension advertises support and implements `fetchRelatedAnimeList`.
    // Those members exist on AniZen's runtime source API but not on the older
    // lib-14 stub we compile against, so they are declared without `override`
    // — the JVM dispatches the runtime interface methods to them anyway.
    val supportsRelatedAnimes: Boolean get() = true

    suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        return runCatching {
            val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl/${anime.url}/"
            client.newCall(GET(url, headers)).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val doc = response.asJsoup()
                doc.select("div.related-videos div.item").mapNotNull { el ->
                    val a = el.selectFirst("a[href*=/videos/]") ?: return@mapNotNull null
                    val href = a.absUrl("href").ifBlank { a.attr("href") }
                    val title = a.attr("title").trim()
                        .ifBlank { el.selectFirst("strong.title")?.text()?.trim() }
                        ?: return@mapNotNull null
                    SAnime.create().apply {
                        this.title = title
                        this.url = href.removePrefix("$baseUrl/").trim('/')
                        thumbnail_url = el.selectFirst("img[data-original]")?.attr("data-original")
                            ?.takeIf { it.startsWith("http") }
                    }
                }.distinctBy { it.url }
            }
        }.getOrDefault(emptyList())
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}/", headers)

    // The catalog is per-video: every entry is its own episode.
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val slug = response.request.url.encodedPath.trim('/').substringAfterLast('/')
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().substringBefore(" Hentai Online").trim()
        val number = title.extractEpisodeNumber() ?: 1f
        return listOf(
            SEpisode.create().apply {
                url = slug
                name = title
                episode_number = number
                date_upload = 0L
                doc.selectFirst("meta[property=og:image]")?.attr("content")?.let {
                    setEpisodeField(this, "preview_url", it)
                }
            },
        )
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/${episode.url}/", headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val html = doc.outerHtml()
        val referer = response.request.url.toString()

        val license = REGEX_LICENSE.find(html)?.groupValues?.get(1) ?: ""
        if (license != LICENSE_CODE) {
            // The permutation below is derived from the site's license hotkeys;
            // if the license ever changes the scramble changes with it.
            throw IOException("Unexpected license_code '$license' — extension needs an update")
        }

        return buildList {
            addAll(videoFromFlashvar(html, "video_url", "video_url_text", referer))
            addAll(videoFromFlashvar(html, "video_alt_url", "video_alt_url_text", referer))
            addAll(videoFromFlashvar(html, "video_alt_url2", "video_alt_url2_text", referer))
        }.ifEmpty { throw IOException("No video URLs found in flashvars") }
    }

    private fun videoFromFlashvar(html: String, urlKey: String, qualityKey: String, referer: String): List<Video> {
        val raw = Regex("$urlKey\\s*:\\s*'([^']+)'").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val quality = Regex("$qualityKey\\s*:\\s*'([^']+)'").find(html)?.groupValues?.get(1)
            ?: "Default"
        if (!raw.contains("/get_file/1/")) return emptyList()

        val url = raw.replace("function/0/", "")
        // hash is 42 chars: 32 scrambled by the player + 10 untouched tail
        val hashMatch = Regex("/get_file/1/([0-9a-f]{42})/").find(url)
            ?: return emptyList()
        val hash = hashMatch.groupValues[1]
        val scrambled = buildString {
            for (i in 0 until 32) append(hash[PERMUTATION[i]])
            append(hash.substring(32))
        }
        val videoUrl = url.replace(hash, scrambled) + "?rnd=" + System.currentTimeMillis()

        return listOf(
            Video(videoUrl, quality, videoUrl, headers = Headers.Builder().add("Referer", referer).build()),
        )
    }

    // ============================== Utilities =============================

    private fun browseUrl(base: String, sort: String, page: Int): String = buildString {
        append(base)
        if (page > 1) append("/$page")
        append("/")
        if (sort.isNotEmpty()) append("?sort_by=").append(sort)
    }

    private fun buildSearchUrl(query: String, categoryId: String, sort: String, page: Int): String {
        val q = URLEncoder.encode(query, "UTF-8")
        return buildString {
            append(baseUrl)
            append("/search/?mode=async&function=get_block")
            append("&block_id=list_videos_videos_list_search_result")
            append("&q=").append(q)
            append("&category_ids=").append(categoryId)
            append("&sort_by=").append(sort)
            if (page > 1) append("&from_videos=").append(page)
        }
    }

    private fun Response.asJsoup(): Document =
        Jsoup.parse(body?.string().orEmpty(), request.url.toString())

    private fun String.extractEpisodeNumber(): Float? =
        Regex("(?:^|[^0-9])ep\\.?\\s*(\\d{1,3})(?:\$|[^0-9])", RegexOption.IGNORE_CASE)
            .find(this)?.groupValues?.get(1)?.toFloatOrNull()
            ?: Regex("(?:^|\\s)(\\d{1,3})$").find(this)?.groupValues?.get(1)?.toFloatOrNull()

    private fun setEpisodeField(episode: SEpisode, fieldName: String, value: String) {
        runCatching {
            val field = SEpisode::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(episode, value)
        }
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private const val LICENSE_CODE = "\$848068927979278"

        private val REGEX_LICENSE = Regex("license_code\\s*:\\s*'([^']+)'")

        // Derived from two (flashvars-hash, player-hash) pairs captured from
        // the live player; verified against a third video. player_hash[i] =
        // fv_hash[PERMUTATION[i]] for i < 32; the tail passes through.
        private val PERMUTATION = intArrayOf(
            12, 31, 28, 5, 11, 7, 20, 27, 17, 0, 6, 30, 9, 4, 15, 13,
            8, 3, 16, 25, 10, 19, 14, 29, 24, 18, 23, 22, 2, 21, 26, 1,
        )

        // (display, sort_by) — verified against the site's own sort lists.
        private val SORTS = listOf(
            "Latest" to "",
            "Most Viewed" to "video_viewed",
            "Top Rated" to "rating",
            "Longest" to "duration",
            "Most Commented" to "most_commented",
            "Most Favorited" to "most_favourited",
        )

        // category_ids -> /categories/{slug}/ for filter-only browsing.
        // Verified live: card href slug + thumbnail id in /categories/.
        private val CATEGORY_SLUGS = mapOf(
            1 to "oral-sex",
            2 to "anal-sex",
            3 to "rape",
            4 to "big-tits",
            5 to "hardcore",
            6 to "bdsm",
            7 to "students",
            8 to "erotic",
            9 to "group-sex",
            10 to "nurse",
            11 to "school",
            12 to "maids",
            13 to "incest",
            14 to "fantasy",
            15 to "tentacles",
            16 to "yuri",
            17 to "yaoi",
            18 to "futanari",
            19 to "cassic-sex",
            20 to "masturbation",
            21 to "lolicon",
            22 to "twins",
            23 to "shotacon",
            24 to "stuff-sex",
            25 to "no-censor",
            26 to "shounen-ai",
            27 to "virgin",
            28 to "blowjob",
            29 to "licking",
            30 to "swimsuit",
            31 to "magical-girl",
            32 to "kimono",
            33 to "public-outdoor",
            34 to "animal-girls",
            35 to "small-tits",
            41 to "sci-fi",
        )
    }
}
