/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaish

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

/**
 * HentaiSh (https://hentai.sh)
 *
 * A Next.js site where every catalogue entry is an episode page at /video/{slug}.
 *   - /                   "recent" feed, /?page=N (153 pages)
 *   - /trending           the site's popularity ranking (also /browse?sort=trending)
 *   - /browse?q=QUERY     the real search (an empty ?s= just returns the home feed)
 *   - /browse?tags=TAG    the site's own tag filter, one tag per request
 *   - /browse?series=NAME every episode of one series, which is what the episode
 *                         list is built from
 *
 * Cards are `div.video-card > a.video-card-link[href=/video/...]` with the title in
 * an `<h3>`; the thumbnail is `img[src]` (whose `alt` is always empty) or the
 * preview `<video poster>`. Each /video/ page embeds the episode's HLS master
 * playlist (https://edge1.hentai.sh/v/{slug}/master.m3u8?v=TOKEN) in Next.js
 * flight data, backslash-escaped.
 */
class HentaiSh : AnimeHttpSource() {

    override val name = "HentaiSh"

    override val baseUrl = "https://hentai.sh"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("hentaish", "all", 1))
    override val id: Long = 8732455434697193948L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/trending?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        cataloguePage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        cataloguePage(response)

    // ============================== Search ================================

    // /browse?tags=TAG takes a single tag, so a multi-select is served by fetching
    // one listing per ticked tag and merging them (OR). The parse step therefore
    // needs to remember which tags were ticked and which page it is on.
    private var lastFilters: AnimeFilterList? = null
    private var lastPage: Int = 1

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        lastFilters = filters
        lastPage = page
        val tags = selectedTags(filters)
        val sort = sortSlug(filters)
        if (query.isNotBlank() || tags.isNotEmpty() || sort.isNotEmpty()) {
            // Only the first tag is requested here; the rest are merged in the parse step.
            return GET(browseUrl(page, query, tags.firstOrNull(), sort), headers)
        }
        return GET("$baseUrl/?page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (!response.request.url.encodedPath.startsWith("/browse")) return cataloguePage(response)
        val first = cataloguePage(response)
        val rest = selectedTags(lastFilters).drop(1)
        if (rest.isEmpty()) return first
        val query = response.request.url.queryParameter("q").orEmpty()
        val sort = sortSlug(lastFilters ?: AnimeFilterList())
        val merged = LinkedHashMap<String, SAnime>()
        first.animes.forEach { merged[it.url] = it }
        var hasMore = first.hasNextPage
        rest.forEach { tag ->
            client.newCall(GET(browseUrl(lastPage, query, tag, sort), headers)).execute().use { resp ->
                val page = cataloguePage(resp)
                page.animes.forEach { merged.putIfAbsent(it.url, it) }
                hasMore = hasMore || page.hasNextPage
            }
        }
        return AnimesPage(merged.values.toList(), hasMore)
    }

    private fun browseUrl(page: Int, query: String, tag: String?, sort: String): okhttp3.HttpUrl {
        val builder = "$baseUrl/browse".toHttpUrl().newBuilder()
        if (query.isNotBlank()) builder.addQueryParameter("q", query)
        tag?.let { builder.addQueryParameter("tags", it) }
        if (sort.isNotEmpty()) builder.addQueryParameter("sort", sort)
        builder.addQueryParameter("page", page.toString())
        return builder.build()
    }

    // ============================== Catalogue parsing =====================

    private fun cataloguePage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("div.video-card").mapNotNull(::videoCard)
        // Every listing carries pager links as ?page=N, so the highest one is the last page.
        val lastPage = doc.select("a[href]").flatMap { el ->
            PAGE_PARAM.findAll(el.attr("href")).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        }.maxOrNull()
        val hasNext = lastPage?.let { response.page() < it } ?: animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    /**
     * A card is `div.video-card` wrapping `a.video-card-link`; the `<a>` does not
     * itself carry the `video-card` class, which is what used to make every
     * listing come back empty.
     */
    private fun videoCard(card: Element): SAnime? {
        if (card.hasClass("skeleton-card")) return null
        val link = card.selectFirst("a[href^=/video/]") ?: return null
        val slug = link.attr("href").trim('/')
        if (!slug.startsWith("video/")) return null
        val title = card.selectFirst("h3")?.text()?.trim().orEmpty()
            .ifBlank {
                card.selectFirst(".card-menu-btn")?.attr("aria-label")
                    ?.removePrefix("More options for ")?.trim().orEmpty()
            }
            .ifBlank { slugToTitle(slug) }
        val thumb = card.selectFirst("img[src^=http]")?.attr("src")?.takeIf { it.startsWith("http") }
            ?: card.selectFirst("video[poster]")?.attr("poster")?.takeIf { it.startsWith("http") }
        return SAnime.create().apply {
            this.title = title
            this.url = slug
            thumbnail_url = thumb
        }
    }

    private fun slugToTitle(slug: String): String =
        slug.substringAfterLast('/').replace('-', ' ')
            .replaceFirstChar { it.uppercase() }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifBlank { ogTitle.removeSuffix(" · HentaiSh") }
        return SAnime.create().apply {
            this.title = title
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("video")?.attr("poster")?.takeIf { it.startsWith("http") }
            description = doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    // A /video/ page belongs to a series and links it as /browse?series=NAME, so
    // the episode list is every episode of that series (each with its own
    // thumbnail). If the series link is missing, fall back to the page's own
    // prev/next navigation plus the current episode.

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val seriesHref = doc.selectFirst("a[href^=/browse?series=]")?.attr("href")
            ?.takeIf { it.isNotBlank() }
        if (seriesHref != null) {
            val episodes = seriesEpisodes(seriesHref)
            if (episodes.isNotEmpty()) return episodes
        }
        return fallbackEpisodes(doc, response.request.url.toString())
    }

    /** Walks /browse?series=NAME pages until the listing runs out. */
    private fun seriesEpisodes(seriesHref: String): List<SEpisode> {
        val episodes = LinkedHashMap<String, SEpisode>()
        var page = 1
        while (page <= MAX_SERIES_PAGES) {
            val seriesPageUrl = seriesHref.toHttpUrl().newBuilder()
                .setQueryParameter("page", page.toString())
                .build()
            val doc = client.newCall(GET(seriesPageUrl, headers)).execute().use { it.asJsoup() }
            val cards = doc.select("div.video-card").mapNotNull { card ->
                val link = card.selectFirst("a[href^=/video/]") ?: return@mapNotNull null
                val slug = link.attr("href").trim('/')
                if (!slug.startsWith("video/")) return@mapNotNull null
                val title = card.selectFirst("h3")?.text()?.trim().orEmpty().ifBlank { slugToTitle(slug) }
                val thumb = card.selectFirst("img[src^=http]")?.attr("src")?.takeIf { it.startsWith("http") }
                    ?: card.selectFirst("video[poster]")?.attr("poster")?.takeIf { it.startsWith("http") }
                Pair(slug, Pair(title, thumb))
            }
            if (cards.isEmpty()) break
            cards.forEach { (slug, info) ->
                if (episodes.containsKey(slug)) return@forEach
                val number = EPISODE_NUMBER.find(slug)?.groupValues?.get(1)?.toIntOrNull()
                    ?: TITLE_NUMBER.find(info.first)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (episodes.size + 1)
                episodes[slug] = SEpisode.create().apply {
                    url = slug
                    name = info.first
                    episode_number = number.toFloat()
                    info.second?.let { setEpisodeField(this, "preview_url", it) }
                }
            }
            val maxPage = doc.select("a[href]").flatMap { el ->
                PAGE_PARAM.findAll(el.attr("href")).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
            }.maxOrNull()
            if (maxPage == null || page >= maxPage) break
            page++
        }
        return episodes.values.sortedBy { it.episode_number }
    }

    private fun fallbackEpisodes(doc: Document, pageUrl: String): List<SEpisode> {
        val current = doc.selectFirst("link[rel=canonical]")?.attr("href")
            ?.substringAfter("$baseUrl/", "")?.trim('/')
            ?.takeIf { it.startsWith("video/") }
            ?: pageUrl.substringAfter("$baseUrl/", "").trim('/').takeIf { it.startsWith("video/") }
            ?: return emptyList()
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val candidates = LinkedHashSet<String>()
        candidates.add(current)
        doc.select("a[href^=/video/]").forEach { el ->
            el.attr("href").trim('/').takeIf { it.startsWith("video/") }?.let { candidates.add(it) }
        }
        return candidates.map { slug ->
            val number = EPISODE_NUMBER.find(slug)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            SEpisode.create().apply {
                url = slug
                name = doc.selectFirst("h1")?.text()?.trim()
                    ?.takeIf { slug == current && it.isNotBlank() }
                    ?: "Episode $number"
                episode_number = number.toFloat()
                ogImage?.takeIf { slug == current && it.startsWith("http") }
                    ?.let { setEpisodeField(this, "preview_url", it) }
            }
        }.distinctBy { it.url }.sortedBy { it.episode_number }
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body?.string().orEmpty()
        val master = MASTER_REGEX.find(body.replace("\\", ""))
            ?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") }
            ?: throw IOException("HentaiSh: HLS master not found in page")
        return listOf(Video(master, "HLS", master, headers = headers))
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply to browse (leave search blank)"),
        TagGroup(),
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

    private class SortFilter : AnimeFilter.Select<String>("Sort by", SORT_NAMES, 0)

    private fun flatFilters(filters: AnimeFilterList?): List<AnimeFilter<*>> = filters.orEmpty().flatMap { filter ->
        if (filter is AnimeFilter.Group<*>) {
            filter.state.filterIsInstance<AnimeFilter<*>>()
        } else {
            listOf(filter)
        }
    }

    private fun selectedTags(filters: AnimeFilterList?): List<String> =
        flatFilters(filters).filterIsInstance<AnimeFilter.CheckBox>()
            .filter { it.state }
            .map { it.name }
            .filter { TAG_NAMES.contains(it) }

    private fun sortSlug(filters: AnimeFilterList): String {
        val index = flatFilters(filters).filterIsInstance<SortFilter>().firstOrNull()?.state ?: 0
        return SORT_SLUGS.getOrNull(index) ?: ""
    }

    // ============================== Helpers ===============================

    private fun Response.asJsoup(): Document = Jsoup.parse(body?.string().orEmpty(), request.url.toString())

    private fun Response.page(): Int =
        request.url.queryParameter("page")?.toIntOrNull() ?: 1

    private fun setEpisodeField(episode: SEpisode, fieldName: String, value: String) {
        try {
            val setter = episode.javaClass.getMethod(
                "set${fieldName.replaceFirstChar { it.uppercase() }}",
                String::class.java,
            )
            setter.invoke(episode, value)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private const val MAX_SERIES_PAGES = 5

        private val MASTER_REGEX = Regex("""https?://[^"\\]+master\.m3u8[^"\\]*""")
        private val PAGE_PARAM = Regex("""[?&]page=(\d+)""")
        private val EPISODE_NUMBER = Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE)
        private val TITLE_NUMBER = Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE)
    }
}
