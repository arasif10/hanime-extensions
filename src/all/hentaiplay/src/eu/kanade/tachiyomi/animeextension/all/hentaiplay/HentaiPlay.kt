/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaiplay

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
 * HentaiPlay (https://hentaiplay.net)
 *
 * WordPress site where every post is a single episode
 * (https://hentaiplay.net/{slug}/). The homepage with ?orderby=views is the
 * popularity sort, /hentai/episodes/new-release/ is newest (paginated /page/N/),
 * search is /?s=QUERY.
 *
 * Each episode page carries the direct MP4 in a <video><source> tag
 * (hosted on hentaiplanet.info).
 */
class HentaiPlay : AnimeHttpSource() {

    override val name = "HentaiPlay"

    override val baseUrl = "https://hentaiplay.net"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("hentaiplay", "all", 1))
    override val id: Long = 5386965955723884187L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/?orderby=views&paged=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        paginatedAnimesPage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/hentai/episodes/new-release/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        paginatedAnimesPage(response)

    // ============================== Search ================================

    // Exclusion is enforced while parsing the rows, and AniZen only hands the
    // filter list to the *request* builder, so keep the last one.
    private var lastFilters: AnimeFilterList? = null

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        lastFilters = filters
        if (query.isNotBlank()) {
            return GET(
                "$baseUrl/".toHttpUrl().newBuilder()
                    .addQueryParameter("s", query)
                    .addQueryParameter("paged", page.toString())
                    .build(),
                headers,
            )
        }
        // Included genres and the year are ANDed by the site's "+" term
        // separator, which keeps result pages dense; excludes are dropped after
        // parsing (they cannot be expressed in a WordPress taxonomy URL).
        val terms = includedTerms(filters)
        val path = when {
            terms.isEmpty() && page == 1 -> "$baseUrl/"
            terms.isEmpty() -> "$baseUrl/page/$page/"
            page == 1 -> "$baseUrl/genre/${terms.joinToString("+")}/"
            else -> "$baseUrl/genre/${terms.joinToString("+")}/page/$page/"
        }
        return GET("$path?orderby=${sortSlug(filters)}", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val parsed = paginatedAnimesPage(response)
        val included = includedSlugs(lastFilters)
        val excluded = excludedSlugs(lastFilters)
        if (included.isEmpty() && excluded.isEmpty()) return parsed
        return AnimesPage(
            parsed.animes.filter { anime ->
                val tags = tagsOf(anime)
                included.all { it in tags } && excluded.none { it in tags }
            },
            parsed.hasNextPage,
        )
    }

    // ============================== Catalogue parsing =====================

    private fun paginatedAnimesPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("a.clip-link").mapNotNull(::videoCard)

        // Pagination links look like /page/2/ (the sorted view adds a query
        // string: /page/2/?orderby=views, so match the path segment). The
        // current page comes from the request itself.
        val current = response.request.url.queryParameter("paged")?.toIntOrNull()
            ?: PAGE_REGEX.find(response.request.url.encodedPath)
                ?.groupValues?.get(1)?.toIntOrNull()
            ?: 1
        val linked = doc.select("a[href]").mapNotNull { el ->
            PAGE_REGEX.find(el.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }

        // Prefer the site's own pager; only fall back to "this page had items"
        // when a view renders no pagination links at all.
        val hasNext = if (linked.isNotEmpty()) linked.any { it > current } else animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    private fun videoCard(el: Element): SAnime? {
        val href = el.attr("href").takeIf { it.startsWith("$baseUrl/") && it != "$baseUrl/" }
            ?: return null
        val title = el.attr("title").trim().ifBlank { null }
            ?: el.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val img = el.selectFirst("img")
        return SAnime.create().apply {
            this.title = title
            this.url = href.substringAfter("$baseUrl/").trim('/')
            thumbnail_url = img?.attr("src")?.takeIf { it.startsWith("http") }
            // The post wrapper carries the genre taxonomy as tag-<slug>
            // classes; keep them on the row so include/exclude can be enforced.
            genre = cardTags(el).joinToString(", ").ifBlank { null }
        }
    }

    /** The wrapper element above a card holds its `tag-<slug>` classes. */
    private fun cardTags(el: Element): List<String> {
        val wrapper = el.parents().firstOrNull { parent ->
            parent.classNames().any { it.startsWith("tag-") }
        } ?: return emptyList()
        // tag-1492 style numeric classes are tag IDs, not slugs.
        return wrapper.classNames()
            .filter { it.startsWith("tag-") && it.removePrefix("tag-").any(Char::isLetter) }
            .map { it.removePrefix("tag-") }
    }

    private fun tagsOf(anime: SAnime): List<String> =
        anime.genre?.split(", ")?.filter { it.isNotBlank() }.orEmpty()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        // Groups render as collapsible sections in AniZen; a bare list of
        // TriState rows would stay expanded and bury the other filters.
        FilterGroup("Genres (include/exclude)", *genreRows(GENRE_NAMES, GENRE_SLUGS)),
        FilterGroup("Studios (include/exclude)", *genreRows(STUDIO_NAMES, STUDIO_SLUGS)),
        AnimeFilter.Header("Released year"),
        YearFilter(),
        AnimeFilter.Header("Sorting"),
        SortFilter(),
    )

    private fun genreRows(names: Array<String>, slugs: Array<String>): Array<GenreFilter> =
        Array(names.size) { index -> GenreFilter(names[index], slugs[index]) }

    private class FilterGroup(name: String, vararg filters: AnimeFilter<*>) :
        AnimeFilter.Group<AnimeFilter<*>>(name, filters.toList())

    private class GenreFilter(name: String, val slug: String) :
        AnimeFilter.TriState(name, AnimeFilter.TriState.STATE_IGNORE)

    private class YearFilter : AnimeFilter.Select<String>("Year", YEAR_NAMES, 0)

    private class SortFilter : AnimeFilter.Select<String>("Sort by", SORT_NAMES, 0)

    private fun flatFilters(filters: AnimeFilterList?): List<AnimeFilter<*>> = filters.orEmpty().flatMap { filter ->
        if (filter is AnimeFilter.Group<*>) {
            filter.state.filterIsInstance<AnimeFilter<*>>()
        } else {
            listOf(filter)
        }
    }

    private fun includedSlugs(filters: AnimeFilterList?): List<String> =
        flatFilters(filters).filterIsInstance<GenreFilter>()
            .filter { it.state == AnimeFilter.TriState.STATE_INCLUDE }
            .map { it.slug }

    private fun excludedSlugs(filters: AnimeFilterList?): Set<String> =
        flatFilters(filters).filterIsInstance<GenreFilter>()
            .filter { it.state == AnimeFilter.TriState.STATE_EXCLUDE }
            .map { it.slug }
            .toSet()

    /** Genre includes plus the selected year, which the site ANDs via "+". */
    private fun includedTerms(filters: AnimeFilterList): List<String> {
        val year = flatFilters(filters).filterIsInstance<YearFilter>().firstOrNull()
            ?.let { YEAR_NAMES.getOrNull(it.state) }
            ?.takeIf { it != "All" }
        return includedSlugs(filters) + listOfNotNull(year)
    }

    private fun sortSlug(filters: AnimeFilterList): String {
        val index = flatFilters(filters).filterIsInstance<SortFilter>().firstOrNull()?.state ?: 0
        return SORT_SLUGS.getOrNull(index) ?: SORT_SLUGS[0]
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}/", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
                .ifBlank { ogTitle.removeSuffix(" - Hentai Play") }
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            description = doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}/", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val img = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val ep = SEpisode.create().apply {
            url = response.request.url.toString().substringAfter("$baseUrl/").trim('/')
            name = title
            episode_number = 1f
            img?.takeIf { it.startsWith("http") }?.let { setEpisodeField(this, "preview_url", it) }
        }
        return listOf(ep)
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val body = response.body?.string().orEmpty()
        // direct <video><source src="..."> is the primary source
        val direct = doc.select("video source[src]").firstOrNull()?.attr("src")
            ?: doc.selectFirst("video[id=my-video]")?.selectFirst("source")?.attr("src")
        val url = direct?.takeIf { it.startsWith("http") }
            ?: MP4_REGEX.find(body.replace("\\", ""))?.groupValues?.get(1)
            ?: throw IOException("HentaiPlay: no playable stream found")
        return listOf(Video(url, "MP4", url, headers = headers))
    }

    // ============================== Helpers ===============================

    private fun Response.asJsoup(): Document = Jsoup.parse(body?.string().orEmpty(), request.url.toString())

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

        // /page/N/ - only the page number is captured (the previous pattern had
        // a single group while the caller read group 2, which threw
        // IndexOutOfBoundsException: No group 2 and broke the whole source).
        private val PAGE_REGEX = Regex("""/page/(\d+)/""")

        private val MP4_REGEX = Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""")
    }
}
