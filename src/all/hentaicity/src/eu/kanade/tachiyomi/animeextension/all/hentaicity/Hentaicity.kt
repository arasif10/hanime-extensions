/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaicity

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
import java.net.URLEncoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * HentaiCity (https://www.hentaicity.com)
 *
 * Catalog pages live under /videos/straight/ and follow the
 * {category}-{sort}[-{page}].html pattern, e.g. hentai-recent.html,
 * hentai-popular-2.html, teen-view-3.html (sorts: recent/popular/view/
 * rate/length). Search is /search/video/{query}/{page}/ — the old
 * /videos/search/?q= endpoint 404s. Cards are a.thumb-img with hrefs pointing
 * at a /click/N-1/video/... tracking URL that 302s to /video/SLUG.html.
 *
 * Video pages embed an HLS master playlist (hls.hentaicity.com, token-
 * qualified) plus direct MP4 fallbacks (www.hentaicity.com/flv/... MP4s).
 * Titles are single videos, so each "anime" is one video and its episode list
 * is that single video. Real metadata (uploader, upload date, duration, views)
 * comes from the page's JSON-LD VideoObject; tags live in #taglink.
 */
class Hentaicity : AnimeHttpSource() {

    override val name = "Hentaicity"

    override val baseUrl = "https://www.hentaicity.com"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("Hentaicity", "all", 1))
    override val id: Long = 2033150425394675082L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        catalogRequest(SORT_POPULAR, CATEGORY_DEFAULT, page)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseCatalog(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        catalogRequest(SORT_RECENT, CATEGORY_DEFAULT, page)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseCatalog(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        var sort = SORT_RECENT
        var category = CATEGORY_DEFAULT
        var tag = ""
        var duration = ""
        var hdOnly = false
        filterList.forEach { f ->
            when (f) {
                is SortFilter -> sort = f.sortValue
                is CategoryFilter -> category = f.category
                is TagFilter -> tag = f.state.trim()
                is DurationFilter -> duration = f.durationValue
                is HdFilter -> hdOnly = f.state
                else -> {}
            }
        }

        // min_width/min_duration are supported on the catalog and tag browse
        // pages (verified against the site's filter form); the text-search
        // endpoint is left parameter-free.
        val params = buildList {
            if (hdOnly) add("min_width" to "1280")
            if (duration.isNotBlank()) add("min_duration" to duration)
        }

        val text = query.trim()
        if (text.isNotEmpty()) {
            // /search/video/{query}/{page}/ — page 1 omits the trailing segment
            val encoded = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
            val path = if (page > 1) "$baseUrl/search/video/$encoded/$page/" else "$baseUrl/search/video/$encoded/"
            return GET(path, headers)
        }

        if (tag.isNotEmpty()) {
            // Tag browse: /tags/video/{tag} (page 1, NO trailing slash) and
            // /tags/video/{tag}/{page}/ for deeper pages. Sort/category do not
            // apply on tag pages.
            val encoded = URLEncoder.encode(tag, "UTF-8").replace("+", "%20")
            val base = if (page > 1) "$baseUrl/tags/video/$encoded/$page/" else "$baseUrl/tags/video/$encoded"
            return GET(addParams(base, params), headers)
        }

        return catalogRequest(sort, category, page, params)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseCatalog(response)

    /**
     * Parses the response body exactly once — okhttp bodies are one-shot and a
     * second body.string() throws "closed".
     */
    private fun parseCatalog(response: Response): AnimesPage {
        val doc = response.asJsoup()
        return AnimesPage(catalogCards(doc), hasMoreCards(doc))
    }

    /** Page 1 is {cat}-{sort}.html; deeper pages append -{page} before .html. */
    private fun catalogRequest(
        sort: String,
        category: String,
        page: Int,
        params: List<Pair<String, String>> = emptyList(),
    ): Request {
        val name = if (page == 1) "$category-$sort.html" else "$category-$sort-$page.html"
        return GET(addParams("$baseUrl/videos/straight/$name", params), headers)
    }

    private fun addParams(url: String, params: List<Pair<String, String>>): String =
        url.toHttpUrl().newBuilder()
            .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
            .toString()

    // ============================== Catalogue parsing =====================

    private fun catalogCards(doc: Document): List<SAnime> =
        doc.select("a.thumb-img").mapNotNull(::card)

    private fun card(el: Element): SAnime? {
        val href = el.absUrl("href").ifBlank { el.attr("href") }
        if (!href.contains("/click/")) return null
        val img = el.selectFirst("img") ?: return null
        val title = img.attr("alt").trim().ifBlank { img.attr("title").trim() }
        if (title.isBlank()) return null
        return SAnime.create().apply {
            this.title = title
            // Store the /video/ slug derived from the click link; the details
            // request resolves the redirect itself.
            url = clickToVideoPath(href)
            thumbnail_url = img.attr("src").takeIf { it.startsWith("http") }
                ?: img.attr("data-src").takeIf { it.startsWith("http") }
        }
    }

    private fun clickToVideoPath(href: String): String {
        // https://www.hentaicity.com/click/1-1/video/SLUG.html -> video/SLUG.html
        val idx = href.indexOf("/click/")
        if (idx < 0) return href
        val after = href.substring(idx + "/click/".length)
        val slash = after.indexOf('/')
        return if (slash >= 0) after.substring(slash + 1) else after
    }

    private fun hasMoreCards(doc: Document): Boolean {
        // Every catalog/search page ends with a "Next" pagination link
        // (class "next") unless the last page is reached.
        return doc.selectFirst("a.next:not(.disabled)") != null &&
            doc.select("a.thumb-img").isNotEmpty()
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val html = response.body?.string().orEmpty()
        val doc = Jsoup.parse(html, response.request.url.toString())

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            .ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
        val thumbnail = doc.selectFirst("meta[property=og:image]")?.attr("content")

        // #taglink holds the uploader profile link, the primary category links
        // (/videos/straight/{cat}-*.html) and the fine-grained tag links
        // (/tags/video/{tag}). Sidebar/related links are outside of it.
        val tagBox = doc.selectFirst("#taglink")
        val uploader = tagBox?.selectFirst("a[href*=/profile/]")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val categories = tagBox?.select("a[href*=/videos/straight/]")?.eachText()
            .orEmpty().map { it.trim() }.filter { it.isNotBlank() }
        val tags = tagBox?.select("a[href*=/tags/video/]")?.eachText()
            .orEmpty().map { it.trim() }.filter { it.isNotBlank() }

        // The og:description/meta description are fixed SEO boilerplate
        // ("Watch X ... Uploaded by Y to Hentai City. Tons of free ..."),
        // so the description is rebuilt from the JSON-LD VideoObject instead.
        val uploadDate = REGEX_UPLOAD_DATE.find(html)?.groupValues?.get(1)
        val views = REGEX_VIEWS.find(html)?.groupValues?.get(1)
        val duration = REGEX_DURATION.find(html)?.let { m ->
            listOf(
                m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }?.let { "${it}h" },
                m.groupValues[2].toIntOrNull()?.takeIf { it > 0 }?.let { "${it}m" },
                m.groupValues[3].takeIf { it != "00" }?.let { "${it}s" },
            ).filterNotNull().joinToString(" ").takeIf { it.isNotBlank() }
        }

        val genre = (categories + tags).distinct()
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

        val description = buildString {
            if (uploader != null) append("Uploaded by $uploader")
            uploadDate?.let { append(if (isEmpty()) "" else " • ").append("on ").append(formatUploadDate(it)) }
            views?.let { append(if (isEmpty()) "" else " • ").append("$it views") }
            duration?.let { append(if (isEmpty()) "" else " • ").append(it) }
            if (categories.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Categories: ").append(categories.joinToString(", "))
            }
            if (tags.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Tags: ").append(tags.joinToString(", "))
            }
        }.trim().takeIf { it.isNotBlank() }

        return SAnime.create().apply {
            this.title = title
            this.thumbnail_url = thumbnail
            this.description = description
            this.genre = genre
            author = uploader
            // Every entry is a single standalone video
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    /** "2026-09-04T00:00:00-07:00" -> "04 Sep 2026"; falls back to the raw text. */
    private fun formatUploadDate(iso: String): String = try {
        val parsed = UPLOAD_DATE_FORMAT.parse(iso)
        if (parsed == null) iso else OUT_DATE_FORMAT.format(parsed)
    } catch (_: ParseException) {
        iso
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val html = response.body?.string().orEmpty()
        val doc = Jsoup.parse(html, response.request.url.toString())
        val url = doc.selectFirst("link[rel=canonical]")?.attr("href")
            ?: response.request.url.toString()
        val slug = url.substringAfter("$baseUrl/").trim('/')
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        val img = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val uploadDate = REGEX_UPLOAD_DATE.find(html)?.groupValues?.get(1)
        return listOf(
            SEpisode.create().apply {
                this.url = slug
                name = title.ifBlank { "Episode 1" }
                episode_number = 1f
                date_upload = uploadDate?.let { parseUploadDate(it) } ?: 0L
                img?.takeIf { it.startsWith("http") }?.let { setEpisodeField(this, "preview_url", it) }
            },
        )
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body?.string().orEmpty()
        val videos = mutableListOf<Video>()

        // HLS master (best quality ladder)
        Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(body)?.groupValues?.get(0)?.let {
            videos.add(Video(it, "HLS", it, headers = videoHeaders))
        }
        // Direct MP4 fallback(s) — skip the hover-preview trailer clips
        Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""").findAll(body)
            .map { it.value }
            .distinct()
            .filterNot { it.contains("/trailer.mp4") }
            .forEach { videos.add(Video(it, "MP4", it, headers = videoHeaders)) }

        if (videos.isEmpty()) throw IOException("Hentaicity: no video sources found")
        return videos
    }

    private val videoHeaders: Headers by lazy {
        headers.newBuilder()
            .add("Referer", "$baseUrl/")
            .build()
    }

    // ============================== Helpers ===============================

    private fun Response.asJsoup() = Jsoup.parse(body?.string().orEmpty(), request.url.toString())

    private fun parseUploadDate(iso: String): Long = try {
        UPLOAD_DATE_FORMAT.parse(iso)?.time ?: 0L
    } catch (_: ParseException) {
        0L
    }

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

    // ============================== Filters ===============================

    private class SortFilter :
        AnimeFilter.Select<String>("Sort by", SORTS.map { it.first }.toTypedArray(), 0) {
        val sortValue: String get() = SORTS[state].second
    }

    private class CategoryFilter :
        AnimeFilter.Select<String>("Category", CATEGORIES.map { it.second }.toTypedArray(), 0) {
        val category: String get() = CATEGORIES[state].first
    }

    private class TagFilter : AnimeFilter.Text("Tag (e.g. teacher)")

    /** (min_duration value, label) — values from the site's filter form. */
    private class DurationFilter :
        AnimeFilter.Select<String>("Duration", DURATIONS.map { it.second }.toTypedArray(), 0) {
        val durationValue: String get() = DURATIONS[state].first
    }

    private class HdFilter : AnimeFilter.CheckBox("HD only")

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        CategoryFilter(),
        DurationFilter(),
        HdFilter(),
        AnimeFilter.Separator(),
        TagFilter(),
        AnimeFilter.Header("Text search overrides all filters; tag browsing"),
        AnimeFilter.Header("ignores sort/category (site limitation)"),
    )

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private const val SORT_RECENT = "recent"
        private const val SORT_POPULAR = "popular"
        private const val CATEGORY_DEFAULT = "hentai"

        /** (url slug, label) — order follows the site's sort nav. */
        private val SORTS = listOf(
            "Most Recent" to SORT_RECENT,
            "Most Popular" to SORT_POPULAR,
            "Most Viewed" to "view",
            "Top Rated" to "rate",
            "Longest" to "length",
        )

        /** (url slug, label) — the site's category taxonomy. */
        private val CATEGORIES = listOf(
            "hentai" to "Hentai (default)",
            "all" to "All",
            "3d" to "3D",
            "anal" to "Anal",
            "babe" to "Babe",
            "bigdick" to "Big Dick",
            "bigtits" to "Big Tits",
            "blowjob" to "Blowjob",
            "cartoon" to "Cartoon",
            "comics" to "Comics",
            "cumshot" to "Cumshot",
            "fetish" to "Fetish",
            "futanari" to "Futanari",
            "gay" to "Gay",
            "groupsex" to "Groupsex",
            "lesbian" to "Lesbian",
            "masturbation" to "Masturbation",
            "mature" to "Mature",
            "monster" to "Monster",
            "roughsex" to "Rough Sex",
            "teen" to "Teen",
            "toys" to "Toys",
            "voyeur" to "Voyeur",
        )

        /** (min_duration value, label) — values from the site's filter form. */
        private val DURATIONS = listOf(
            "" to "Any duration",
            "1-480" to "0-8 minutes",
            "480-1200" to "8-20 minutes",
            "1200" to "20+ minutes",
        )

        private val REGEX_UPLOAD_DATE = Regex("\"uploadDate\"\\s*:\\s*\"([^\"]+)\"")
        private val REGEX_VIEWS = Regex("\"userInteractionCount\"\\s*:\\s*(\\d+)")
        private val REGEX_DURATION = Regex("\"duration\"\\s*:\\s*\"PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?\"")

        private val UPLOAD_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val OUT_DATE_FORMAT = SimpleDateFormat("dd MMM yyyy", Locale.US)
    }
}
