/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaistream

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
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder

/**
 * HentaiStream (https://tube.hentaistream.com)
 *
 * An old WordPress theme with three different listings:
 *   - /hentai-most-viewed-episodes-all -> series cards (div.post2, "Most Viewed")
 *   - /page/{n}                        -> episode cards (div.post, latest updates)
 *   - /?s={query}                      -> search results (episode cards)
 * A series card points at /hentaidvd/{slug}; that page lists its episodes as
 * /{slug}-episode-{nn} links, and an episode page embeds one iframe per mirror
 * (/frames/sNN_{Title}.html). Each frame is a video.js player holding a direct
 * MP4 on cdnN.streamhentai.org; some frames only answer "This Source NN link is
 * unavailable", so every frame is tried until one yields a playable source.
 */
class HentaiStream : AnimeHttpSource() {

    override val name = "HentaiStream"

    override val baseUrl = "https://tube.hentaistream.com"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("hentaistream/all/1"))
    override val id: Long = 3175594623790874405L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    // "Most Viewed Episodes" lists series cards, which is the site's own
    // popularity ranking.

    private val popularPath = "/hentai-most-viewed-episodes-all"

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl$popularPath", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        return AnimesPage(seriesCards(doc), false)
    }

    // ============================== Latest ================================
    // The paginated listing (up to /page/202) carries one card per episode and
    // is ordered newest first.

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/page/$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        return AnimesPage(episodeCards(doc), hasNextPage(doc))
    }

    // ============================== Search ================================

    // Genre browse needs to remember the ticked boxes, because the site takes a
    // single genre per request and returns the whole match set in one page.
    private var lastFilters: AnimeFilterList? = null

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        lastFilters = filters
        if (query.isNotBlank()) {
            return if (page > 1) {
                GET("$baseUrl/page/$page/?s=${URLEncoder.encode(query, "UTF-8")}", headers)
            } else {
                GET("$baseUrl/?s=${URLEncoder.encode(query, "UTF-8")}", headers)
            }
        }
        val includes = selectedGenres(filters, AnimeFilter.TriState.STATE_INCLUDE)
        val excludes = selectedGenres(filters, AnimeFilter.TriState.STATE_EXCLUDE)
        if (includes.isEmpty() && excludes.isEmpty()) return GET("$baseUrl/page/$page", headers)
        // Only the first included genre is requested here; the rest are merged
        // in the parse step, since one request carries one response.
        if (includes.isNotEmpty()) return GET("$baseUrl/genres?genre=${includes.first()}", headers)
        // Excludes on their own still need a series listing to filter down.
        return GET("$baseUrl$popularPath", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = episodeCards(doc) + seriesCards(doc)
        val path = response.request.url.encodedPath
        if (!path.startsWith("/genres") && !path.startsWith(popularPath)) {
            return AnimesPage(animes.distinctBy { it.url }, hasNextPage(doc))
        }
        val merged = LinkedHashMap<String, SAnime>()
        animes.forEach { merged[it.url] = it }
        selectedGenres(lastFilters, AnimeFilter.TriState.STATE_INCLUDE).drop(1).forEach { slug ->
            client.newCall(GET("$baseUrl/genres?genre=$slug", headers)).execute().use { resp ->
                seriesCards(resp.asJsoup()).forEach { merged.putIfAbsent(it.url, it) }
            }
        }
        val excludes = selectedGenres(lastFilters, AnimeFilter.TriState.STATE_EXCLUDE)
        val rows = if (excludes.isEmpty()) {
            merged.values.toList()
        } else {
            // Honour the exclude taps against each row's own "Genre(s):" line.
            merged.values.filter { anime ->
                genreFacets[anime.url].orEmpty().none { it in excludes }
            }
        }
        return AnimesPage(rows, hasNextPage(doc))
    }

    // =========================== Card parsing =============================
    // okhttp bodies are one-shot: every parser takes the already-parsed
    // document, because reading response.body twice threw
    // "IllegalStateException: closed" and broke this whole source.

    private fun episodeCards(doc: Document): List<SAnime> =
        doc.select("div.post:not(.post2)").mapNotNull { card -> card.toEpisode() }

    private fun seriesCards(doc: Document): List<SAnime> =
        doc.select("div.post2").mapNotNull { card -> card.toSeries() }

    private fun Element.toEpisode(): SAnime? {
        val link = selectFirst(".posttitle a, .postimg a, a[href]") ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        val path = pathOf(href) ?: return null
        if (path.substringBefore('/') in SKIP_SECTIONS) return null
        val img = selectFirst(".postimg img") ?: selectFirst("img[src^=http]")
        val title = selectFirst(".posttitle ins")?.text()?.trim().orEmpty()
            .ifBlank { link.attr("title").removePrefix("Watch ").trim() }
            .ifBlank { img?.attr("alt")?.removePrefix("HentaiStream.com ")?.trim().orEmpty() }
        if (title.isBlank()) return null
        return SAnime.create().apply {
            this.title = title
            url = path
            thumbnail_url = img?.attr("src")?.takeIf { it.startsWith("http") }
            genre = select("p.posttags a[rel=tag]").eachText()
                .distinct().take(8).joinToString(", ").ifBlank { null }
        }
    }

    private fun Element.toSeries(): SAnime? {
        val link = selectFirst("h3.title a") ?: selectFirst("a[href*=/hentaidvd/]") ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        val path = pathOf(href) ?: return null
        if (!path.startsWith("hentaidvd/")) return null
        val img = selectFirst("img[src^=http]")
        val title = link.text().trim()
            .ifBlank { img?.attr("alt")?.removePrefix("HentaiStream.com ")?.trim().orEmpty() }
        if (title.isBlank()) return null
        // The card prints the site's genre labels verbatim, which is what makes
        // the filter's exclude taps enforceable without extra requests.
        val genreList = select("p.tags").text()
            .substringAfter("Genre(s):", "")
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
        genreFacets[path] = genreList.toSet()
        return SAnime.create().apply {
            this.title = title
            url = path
            thumbnail_url = img?.attr("src")?.takeIf { it.startsWith("http") }
            description = selectFirst("div.views")?.text()?.substringAfter("Description:", "")?.trim()
                ?.takeIf { it.isNotBlank() }
            genre = genreList.take(10).joinToString(", ").ifBlank { null }
        }
    }

    private fun pathOf(href: String): String? {
        if (!href.startsWith("http")) return null
        val path = href.removePrefix("$baseUrl/").trim('/').substringBefore('?')
        if (path.isBlank() || path.startsWith("wp-content") || path.startsWith("frames") ||
            path.startsWith("list/") || path.startsWith("downloadvid")
        ) {
            return null
        }
        return path
    }

    private fun hasNextPage(doc: Document, currentPage: Int = 1): Boolean {
        if (doc.selectFirst("a[rel=next]") != null) return true
        return doc.select("a[href*=/page/]").any { link ->
            val page = PAGER.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            page != null && page > currentPage
        }
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET(absolute(anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val slug = response.request.url.pathSegments.lastOrNull().orEmpty()
        return SAnime.create().apply {
            url = "/" + response.request.url.pathSegments.joinToString("/")
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
                .ifBlank { doc.selectFirst("h2, h1, .posttitle ins")?.text()?.trim().orEmpty() }
                .ifBlank { slug }
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("#description img, #seriescover img, .postimg img")?.attr("src")
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("#description, div.series_desc")?.text()?.trim()
            genre = doc.select("p.posttags a[rel=tag], a[href*=/list/]").eachText()
                .distinct().take(10).joinToString(", ").ifBlank { null }
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    // A series entry (/hentaidvd/{slug}) lists every episode page under it; an
    // episode entry is a single playable post, so it returns itself.

    override fun episodeListRequest(anime: SAnime): Request =
        GET(absolute(anime.url), headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val path = response.request.url.pathSegments.joinToString("/")
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")

        if (!path.startsWith("hentaidvd")) {
            val slug = response.request.url.pathSegments.lastOrNull().orEmpty()
            val number = EPISODE_NUMBER.find(slug)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
            return listOf(
                SEpisode.create().apply {
                    url = "/$path"
                    name = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                        ?.ifBlank { null }
                        ?: doc.selectFirst(".posttitle ins, h2")?.text()?.trim().orEmpty()
                            .ifBlank { slug }
                    episode_number = number
                    cover?.let { setEpisodeField(this, "preview_url", it) }
                },
            )
        }

        val seriesSlug = path.removePrefix("hentaidvd/").trim('/')
        return doc.select("a[href]").mapNotNull { link ->
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val episodePath = pathOf(href) ?: return@mapNotNull null
            val number = EPISODE_NUMBER.find(episodePath)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            if (!episodePath.startsWith("$seriesSlug-episode-")) return@mapNotNull null
            SEpisode.create().apply {
                url = "/$episodePath"
                name = link.text().trim().ifBlank { "Episode $number" }
                episode_number = number.toFloat()
                cover?.let { setEpisodeField(this, "preview_url", it) }
            }
        }.distinctBy { it.url }.sortedBy { it.episode_number }
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET(absolute(episode.url), headers)

    override fun videoListParse(response: Response): List<Video> {
        val pageUrl = response.request.url.toString()
        val doc = response.asJsoup()
        val frames = doc.select("iframe[src*=/frames/]")
            .map { it.absUrl("src").ifBlank { it.attr("src") } }
            .filter { it.startsWith("http") }
            .distinct()
        if (frames.isEmpty()) throw IOException("HentaiStream: no player frame found")

        val videos = ArrayList<Video>()
        var lastError: String? = null
        for (frameUrl in frames) {
            val label = FRAME_LABEL.find(frameUrl.substringAfterLast('/'))
                ?.groupValues?.get(1)?.let { "Source $it" } ?: "Source"
            try {
                val body = client.newCall(GET(frameUrl, headers)).execute()
                    .use { it.body?.string().orEmpty() }
                if (body.contains("link is unavailable", ignoreCase = true)) {
                    lastError = "$label unavailable"
                    continue
                }
                val source = MP4_REGEX.find(body)?.groupValues?.drop(1)
                    ?.firstOrNull { it.isNotBlank() }
                if (source == null) {
                    lastError = "$label has no direct source"
                    continue
                }
                videos.add(Video(source, label, source, headers = frameHeaders(pageUrl)))
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName} on $label"
            }
        }
        if (videos.isEmpty()) {
            throw IOException("HentaiStream: no playable source (${lastError ?: "unknown"})")
        }
        return videos
    }

    private fun frameHeaders(pageUrl: String): Headers =
        headers.newBuilder()
            .set("Referer", pageUrl)
            .build()

    // ============================== Filters ===============================

    /** Genre labels per series slug, captured while parsing the listing. */
    private val genreFacets = HashMap<String, Set<String>>()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply to browse (leave search blank)"),
        GenreFilter("Genres", GENRE_NAMES.toList()),
    )

    private class GenreFilter(name: String, values: List<String>) :
        AnimeFilter.Group<GenreCheckbox>(name, values.map { GenreCheckbox(it) })

    // TriState: one tap includes the genre, a second tap excludes it.
    private class GenreCheckbox(name: String) :
        AnimeFilter.TriState(name, AnimeFilter.TriState.STATE_IGNORE)

    private fun flatFilters(filters: AnimeFilterList?): List<AnimeFilter<*>> = filters.orEmpty().flatMap { filter ->
        if (filter is AnimeFilter.Group<*>) {
            filter.state.filterIsInstance<AnimeFilter<*>>()
        } else {
            listOf(filter)
        }
    }

    private fun selectedGenres(filters: AnimeFilterList?, state: Int): List<String> =
        flatFilters(filters).filterIsInstance<AnimeFilter.TriState>()
            .filter { it.state == state }
            .map { it.name }
            .mapNotNull { name -> GENRE_NAMES.indexOf(name).takeIf { it >= 0 } }
            .map { GENRE_SLUGS[it] }

    // ============================== Helpers ===============================

    private fun absolute(path: String): String =
        if (path.startsWith("http")) path else "$baseUrl/${path.trimStart('/')}"

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

        private val SKIP_SECTIONS = setOf(
            "porn-games", "porn-news", "hentai-news", "anime-porn-reviews",
            "hentai-top-lists", "genres", "genres2", "contact-us", "redirect",
            "new", "hentai-series-list-full-shows", "hentai-most-viewed-episodes-all",
        )

        private val PAGER = Regex("""/page/(\d+)""")
        private val EPISODE_NUMBER = Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE)
        private val FRAME_LABEL = Regex("""^s(\d+)_""")
        private val MP4_REGEX = Regex("""<video[^>]*src="([^"]+\.mp4[^"]*)"|src="([^"]+\.mp4[^"]*)"""")
    }
}
