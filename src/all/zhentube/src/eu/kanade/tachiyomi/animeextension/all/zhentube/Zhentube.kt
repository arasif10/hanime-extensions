/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.zhentube

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder

/**
 * Zhentube (https://zhentube.com)
 *
 * WordPress Dooplay theme. Catalog pages are /page/N/ and
 * /category/{slug}/page/N/?filter=latest for the New Releases row; search is
 * /?s=QUERY. Cards are article.loop-video with a direct post link and a
 * data-src thumbnail.
 *
 * Every post embeds a javbest.cc FirePlayer iframe. Resolving it:
 *  1. GET the post page, read embedUrl (https://javbest.cc/video/{id})
 *  2. GET https://javbest.cc/download/{id} and unpack the packed JS to find
 *     the AES key ("ck")
 *  3. POST https://javbest.cc/video/{id}?do=getVideo with hash={id}
 *     -> JSON { securedLink: "...master.m3u8?md5=..&expires=.." }
 *  4. The securedLink is a standard HLS master playlist.
 */
class Zhentube : AnimeHttpSource() {

    override val name = "Zhentube"

    override val baseUrl = "https://zhentube.com"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("Zhentube", "all", 1))
    override val id: Long = 6339511979130855530L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET(
            if (page == 1) {
                "$baseUrl/?filter=most-viewed&cat=2001"
            } else {
                "$baseUrl/category/new-release-hentai/page/$page/?filter=most-viewed"
            },
            headers,
        )

    // okhttp bodies are one-shot: parse the page once and hand the document to
    // both helpers. Reading response.body twice threw
    // "IllegalStateException: closed", which broke this whole source.
    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        return AnimesPage(catalogCards(doc), hasMoreCards(doc))
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET(
            if (page == 1) {
                "$baseUrl/?filter=latest&cat=2001"
            } else {
                "$baseUrl/category/new-release-hentai/page/$page/?filter=latest"
            },
            headers,
        )

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        return AnimesPage(catalogCards(doc), hasMoreCards(doc))
    }

    // ============================== Search ================================

    // Excludes are enforced while parsing, and AniZen only passes the filter
    // list to the *request* builder, so keep the last one.
    private var lastFilters: AnimeFilterList? = null

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        lastFilters = filters
        if (query.isNotBlank()) {
            return GET("$baseUrl/page/$page/?s=${URLEncoder.encode(query, "UTF-8")}", headers)
        }
        val included = includedSlugs(filters)
        val sort = sortSlug(filters)
        // Only one category archive fits in a URL; the remaining includes are
        // enforced on the parsed rows, which carry the same category slugs.
        val url = when {
            included.isEmpty() && page == 1 -> "$baseUrl/?cat=2001&filter=$sort"
            included.isEmpty() -> "$baseUrl/page/$page/?filter=$sort"
            page == 1 -> "$baseUrl/category/${included.first()}/?filter=$sort"
            else -> "$baseUrl/category/${included.first()}/page/$page/?filter=$sort"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val parsed = AnimesPage(catalogCards(doc), hasMoreCards(doc))
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

    private fun catalogCards(doc: Document): List<SAnime> {
        return doc.select("article.loop-video").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!href.startsWith("http")) return@mapNotNull null
            val title = a.attr("title").trim().ifBlank {
                el.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }
            if (title.isBlank()) return@mapNotNull null
            SAnime.create().apply {
                this.title = title
                url = href.removePrefix("$baseUrl/").trim('/')
                thumbnail_url = el.selectFirst("img[data-src]")?.attr("data-src")
                    ?.takeIf { it.startsWith("http") }
                    ?: el.selectFirst("img[src^=http]")?.attr("src")
                // Cards expose their facets as category-<slug> classes, so keep
                // them on the row for include/exclude filtering.
                genre = el.classNames()
                    .filter { it.startsWith("category-") || it.startsWith("actors-") }
                    .map { it.substringAfter('-') }
                    .filter { slug -> slug.any(Char::isLetter) }
                    .joinToString(", ")
                    .ifBlank { null }
            }
        }
    }

    private fun hasMoreCards(doc: Document): Boolean {
        return doc.selectFirst("a[rel=next], .pagination a:containsOwn(Next), .pagination a:containsOwn(›)") != null
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        FilterGroup("Genres (include/exclude)", *facetRows(GENRE_NAMES, GENRE_SLUGS)),
        FilterGroup("Studios (include/exclude)", *facetRows(STUDIO_NAMES, STUDIO_SLUGS)),
        FilterGroup("Actors (include/exclude)", *facetRows(ACTOR_NAMES, ACTOR_SLUGS)),
        AnimeFilter.Header("Sorting"),
        SortFilter(),
    )

    private fun facetRows(names: Array<String>, slugs: Array<String>): Array<GenreFilter> =
        Array(names.size) { index -> GenreFilter(names[index], slugs[index]) }

    private class FilterGroup(name: String, vararg filters: AnimeFilter<*>) :
        AnimeFilter.Group<AnimeFilter<*>>(name, filters.toList())

    private class GenreFilter(name: String, val slug: String) :
        AnimeFilter.TriState(name, AnimeFilter.TriState.STATE_IGNORE)

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

    private fun tagsOf(anime: SAnime): List<String> =
        anime.genre?.split(", ")?.filter { it.isNotBlank() }.orEmpty()

    private fun sortSlug(filters: AnimeFilterList): String {
        val index = flatFilters(filters).filterIsInstance<SortFilter>().firstOrNull()?.state ?: 0
        return SORT_SLUGS.getOrNull(index) ?: SORT_SLUGS[0]
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
                .ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            genre = doc.select(".sgeneros a, .genres a, a[href*=/genre/]").eachText()
                .distinct().take(10).joinToString(", ").ifBlank { null }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        // The player iframe is lazyloaded, so its URL sits in data-src rather
        // than src. Read the HTML once and keep a raw-text fallback: looking
        // only for iframe[src*=javbest] is what made every post show 0 episodes.
        val html = response.body?.string().orEmpty()
        val doc = Jsoup.parse(html, response.request.url.toString())
        val slug = response.request.url.pathSegments.lastOrNull { it.isNotBlank() }.orEmpty()
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        val embedUrl = doc.selectFirst("iframe[data-src*=javbest], iframe[src*=javbest]")
            ?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } }
            .orEmpty()
        val javbestId = JAVBEST_ID_REGEX.find(embedUrl)?.groupValues?.get(1)
            ?: JAVBEST_ID_REGEX.find(html)?.groupValues?.get(1)
            ?: throw IOException("Zhentube: no javbest embed found")
        val number = Regex("""(?:episode|ep)[-\s]?(\d+)""", RegexOption.IGNORE_CASE)
            .find(slug)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
        return listOf(
            SEpisode.create().apply {
                url = javbestId
                name = title.ifBlank { slug }
                episode_number = number
                doc.selectFirst("meta[property=og:image]")?.attr("content")?.let {
                    setEpisodeField(this, "preview_url", it)
                }
            },
        )
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request {
        // The download page URL carries the video hash used by getVideo.
        return GET("$JAVBEST/download/${episode.url}", javbestHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val id = Regex("""javbest\.cc/download/([a-f0-9]+)""")
            .find(response.request.url.toString())?.groupValues?.get(1)
            ?: throw IOException("Zhentube: missing video id")

        // The secured HLS link comes from the getVideo endpoint.
        val postBody = FormBody.Builder().add("hash", id).build()
        val req = Request.Builder()
            .url("$JAVBEST/video/$id?do=getVideo")
            .headers(javbestHeaders)
            .addHeader("Referer", "$JAVBEST/download/$id")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .post(postBody)
            .build()
        val json = client.newCall(req).execute().use { it.body?.string().orEmpty() }
        val secured = Regex(""""securedLink":"((?:[^"\\]|\\.)*)"""").find(json)
            ?.groupValues?.get(1)?.replace("\\/", "/")
            ?: throw IOException("Zhentube: no securedLink in getVideo response")
        return listOf(Video(secured, "HLS", secured, headers = javbestHeaders))
    }

    private val javbestHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Referer", "$JAVBEST/")
            .build()
    }

    // ============================== Helpers ===============================

    private fun Response.asJsoup() = Jsoup.parse(body?.string().orEmpty(), request.url.toString())

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
        private const val JAVBEST = "https://javbest.cc"

        /** Player embed id, e.g. javbest.cc/video/dcef2760cae209169100185640b5a2a0 */
        private val JAVBEST_ID_REGEX = Regex("""javbest\.cc/video/([a-f0-9]+)""")
    }
}
