/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hahomoe

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
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Haho.moe (https://haho.moe)
 *
 * Browse/search share the /anime index: `s=` picks the sort order, `q=` a free
 * text search that also accepts tokens (genre:yuri, censorship:unc, ...) and
 * `page=` paginates. The taxonomy pages (e.g. /type/ova, /status/ongoing,
 * /censorship/censored, /source/dvd, /resolution/720p) accept the same query
 * string, so the filters simply swap the request path.
 *
 * Details pages carry the real synopsis (section.entry-description), the tag
 * taxonomy (a[href*="/genre/"]) and production/group metadata. Episode lists
 * live in ul.episode-loop; each episode links to /anime/{id}/{n}.
 *
 * The watch page embeds <iframe src="https://haho.moe/embed?v={token}"> (one
 * per mirror; the same tokens are exposed by the mirror dropdown's ?v= links).
 * The embed page serves <video><source src="https://s1.filegasm.com/..?
 * download_token=.." title="1080p"> quality MP4s.
 */
class HahoMoe : AnimeHttpSource() {

    override val name = "HahoMoe"

    override val baseUrl = "https://haho.moe"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("HahoMoe", "all", 1))
    override val id: Long = 6788476303433097775L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        browseRequest(page, sort = "vwk-d")

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseCatalog(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        browseRequest(page, sort = "rel-d")

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseCatalog(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        var sort = "rel-d"
        var path: String? = null
        var genre = ""
        var tag = ""
        filterList.forEach { f ->
            when (f) {
                is SortFilter -> sort = f.sortValue
                is PathFilter -> if (path == null) path = f.selectedPath
                is GenreTokenFilter -> genre = f.state.trim()
                is TagTokenFilter -> tag = f.state.trim()
                else -> {}
            }
        }

        // Free text plus the genre/tag tokens all go into q=; the site's search
        // syntax only supports single-word genre:/tag: tokens — multi-word
        // tokens (and the unsupported studio:/group:/year: tokens) return
        // nothing. Dropdown filters swap the browse path; the first selected
        // one wins because taxonomy paths cannot be combined in one URL.
        val terms = buildList {
            if (query.isNotBlank()) add(query.trim())
            if (genre.isNotBlank()) add("genre:$genre")
            if (tag.isNotBlank()) add("tag:$tag")
        }
        return browseRequest(page, sort = sort, path = path, q = terms.joinToString(" "))
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseCatalog(response)

    /**
     * Parses the response body exactly once — okhttp bodies are one-shot and a
     * second body.string() throws "closed", which broke the whole extension.
     */
    private fun parseCatalog(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val current = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return AnimesPage(catalogCards(doc), hasMoreCards(doc, current))
    }

    private fun browseRequest(page: Int, sort: String, path: String? = null, q: String? = null): Request {
        val url = "$baseUrl/${path ?: "anime"}".toHttpUrl().newBuilder()
            .addQueryParameter("s", sort)
        if (!q.isNullOrBlank()) url.addQueryParameter("q", q)
        url.addQueryParameter("page", page.toString())
        return GET(url.build().toString(), headers)
    }

    // ============================ Catalogue ===============================

    private fun catalogCards(doc: Document): List<SAnime> {
        return doc.select("li[class*=anime-]").mapNotNull { li ->
            val a = li.selectFirst("a[href*=/anime/]") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            // skip episode links (contain /anime/{id}/{n})
            if (!href.startsWith("http") || Regex("""/anime/[a-z0-9]+/\d+""").containsMatchIn(href)) {
                return@mapNotNull null
            }
            val title = a.attr("title").trim()
                .ifBlank { a.selectFirst("span.text-primary")?.text()?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            SAnime.create().apply {
                this.title = title
                url = href.removePrefix("$baseUrl/").trim('/')
                thumbnail_url = li.selectFirst("img[src^=http]")?.attr("src")
                // The card's data-content carries the real synopsis; shown until
                // the details load re-parses it from the entry page.
                val blurb = a.attr("data-content").trim()
                if (blurb.isNotBlank() && !blurb.equals("No description.", ignoreCase = true)) {
                    description = blurb
                }
            }
        }.distinctBy { it.url }
    }

    private fun hasMoreCards(doc: Document, current: Int): Boolean {
        // /anime and taxonomy pagination links all carry ?page=N
        return doc.select("a[href*=page=]").any { a ->
            (a.attr("abs:href").ifBlank { a.attr("href") })
                .substringAfter("page=", "").substringBefore('&').toIntOrNull()?.let { it > current } == true
        }
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()

        val synopsis = doc.selectFirst("section.entry-description .card-body")?.let { el ->
            el.select("br").append("\\n")
            el.text().trim().takeIf { it.isNotBlank() }
        }

        val genres = doc.select("a[href*=/genre/]")
            .map { (it.attr("title").ifBlank { it.text() }).trim() }
            .filter { it.isNotBlank() && !it.equals("No description.", ignoreCase = true) }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

        val productions = metaValues(doc, "production")

        val description = buildString {
            synopsis?.let { append(it).append("\\n\\n") }
            metaLine("Type", metaValues(doc, "type"))
            metaLine("Status", metaValues(doc, "status"))
            metaLine("Released", metaValues(doc, "release-date"))
            metaLine("Censorship", metaValues(doc, "censorship"))
            metaLine("Source", metaValues(doc, "source"))
            metaLine("Resolution", metaValues(doc, "resolution"))
            metaLine("Group", metaValues(doc, "group"))
            metaLine("Production", productions)
            // Audio/subtitle lis render flag icons instead of .value spans
            val audio = doc.select("li.audio.meta-data a[title]").eachAttr("title")
            val subtitle = doc.select("li.subtitle.meta-data a[title]").eachAttr("title")
            metaLine("Audio", audio)
            metaLine("Subtitles", subtitle)
            metaLine("Genres", genres?.split(", "))
        }.trim().takeIf { it.isNotBlank() }

        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
                .ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty() }
            thumbnail_url = doc.selectFirst("img.cover-image")?.attr("abs:src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            this.description = description
            this.genre = genres
            author = productions.joinToString(", ").takeIf { it.isNotBlank() }
            status = when (metaValues(doc, "status").firstOrNull()?.lowercase(Locale.ROOT)) {
                "ongoing" -> SAnime.ONGOING
                "completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            initialized = true
        }
    }

    /** Texts of every `.value` inside the info-list li with the given class. */
    private fun metaValues(doc: Document, key: String): List<String> =
        doc.select("li.$key.meta-data .value").map { it.text().trim() }.filter { it.isNotBlank() }

    private fun StringBuilder.metaLine(label: String, values: List<String>?) {
        val joined = values.orEmpty().filter { it.isNotBlank() }.joinToString(", ")
        if (joined.isNotBlank()) {
            if (isNotEmpty()) append("\n")
            append(label).append(": ").append(joined)
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val animeId = response.request.url.encodedPath
            .substringAfter("/anime/").substringBefore('/')
            .takeIf { it.isNotBlank() }
            ?: throw IOException("HahoMoe: bad url ${response.request.url}")

        // The details page lists episodes in ul.episode-loop; the watch page
        // playlist (ol.playlist-episodes) is the fallback shape. Mirror links
        // carrying ?v= are never bare episode pages and are skipped.
        val anchors = doc.select("ul.episode-loop li a[href*=/anime/$animeId/]").ifEmpty {
            doc.select("ol.playlist-episodes li a[href*=/anime/$animeId/]")
        }.ifEmpty { doc.select("a[href^=$baseUrl/anime/$animeId/]") }

        val byNumber = LinkedHashMap<Int, SEpisode>()
        for (a in anchors) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!href.startsWith("http") || href.contains('?')) continue
            val num = Regex("""/anime/$animeId/(\d+)""").find(href)
                ?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (byNumber.containsKey(num)) continue
            val label = a.selectFirst(".label .text-primary, .eps_ttl")?.text()?.trim().orEmpty()
            byNumber[num] = SEpisode.create().apply {
                url = href.removePrefix("$baseUrl/").trim('/')
                name = if (label.isBlank() || label.equals("No Title", ignoreCase = true)) {
                    "Episode $num"
                } else {
                    "Episode $num - $label"
                }
                episode_number = num.toFloat()
                // e.g. "31st of Jan, 2025" rendered next to the episode
                date_upload = a.selectFirst(".date")?.text()?.trim()?.parseHahoDate() ?: 0L
            }
        }
        if (byNumber.isEmpty()) throw IOException("HahoMoe: no episodes found")
        return byNumber.values.sortedBy { it.episode_number }
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val watchHtml = response.body?.string().orEmpty()

        // Primary: the iframe embed. Fallback: the mirror dropdown's ?v= links
        // (used when the player iframe is injected by JS or absent).
        val embedUrl = Regex("""https://haho\.moe/embed\?v=[A-Za-z0-9]+""").find(watchHtml)?.value
            ?: Regex("""https://haho\.moe/anime/[^"'?]+\?v=([A-Za-z0-9]+)""")
                .findAll(watchHtml).firstOrNull()
                ?.let { "$baseUrl/embed?v=${it.groupValues[1]}" }
            ?: throw IOException("HahoMoe: no embed found")

        val embedHtml = client.newCall(GET(embedUrl, headers)).execute()
            .use { it.body?.string().orEmpty() }
        val doc = Jsoup.parse(embedHtml)
        val videos = doc.select("video source").mapNotNull { src ->
            val url = src.attr("abs:src").ifBlank { src.attr("src") }
            if (!url.startsWith("http")) return@mapNotNull null
            val quality = src.attr("title").ifBlank { "Auto" }
            Video(url, quality, url, headers = headers)
        }
        if (videos.isEmpty()) throw IOException("HahoMoe: no video sources")
        // Prefer highest numeric resolution first ("1080p" > "720p"), dedupe by url
        return videos.distinctBy { it.url }
            .sortedByDescending { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
    }

    // ============================== Helpers ===============================

    private fun Response.asJsoup() = Jsoup.parse(body?.string().orEmpty(), request.url.toString())

    /** Parses "31st of Jan, 2025" into epoch millis; 0 when unparsable. */
    private fun String.parseHahoDate(): Long {
        val m = DATE_REGEX.find(this) ?: return 0L
        val day = m.groupValues[1].toIntOrNull() ?: return 0L
        val month = MONTHS[m.groupValues[2].lowercase(Locale.ROOT).take(3)] ?: return 0L
        val year = m.groupValues[3].toIntOrNull() ?: return 0L
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }.timeInMillis
    }

    // ============================== Filters ===============================

    private class SortFilter :
        AnimeFilter.Select<String>("Sort by", SORTS.map { it.first }.toTypedArray(), SORT_DEFAULT_INDEX) {
        val sortValue: String get() = SORTS[state].second
    }

    /** Taxonomy dropdown; state 0 ("Any") means "browse all of /anime". */
    private open class PathFilter(
        name: String,
        private val prefix: String,
        entries: List<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, entries.map { it.second }.toTypedArray(), 0) {
        private val slugs = entries.map { it.first }
        val selectedPath: String?
            get() = slugs[state].takeIf { it.isNotBlank() }?.let { "$prefix/$it" }
    }

    private class TypeFilter : PathFilter("Type", "type", TYPES)
    private class StatusFilter : PathFilter("Status", "status", STATUSES)
    private class CensorshipFilter : PathFilter("Censorship", "censorship", CENSORSHIPS)
    private class SourceFilter : PathFilter("Source", "source", SOURCES)
    private class ResolutionFilter : PathFilter("Resolution", "resolution", RESOLUTIONS)
    private class ContentRatingFilter : PathFilter("Content Rating", "content-rating", CONTENT_RATINGS)

    /** Single-word tokens (site limitation: multi-word tokens match nothing). */
    private class GenreTokenFilter : AnimeFilter.Text("Genre (single word, e.g. yuri)")
    private class TagTokenFilter : AnimeFilter.Text("Tag (single word, e.g. netorare)")

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Dropdown = browse filter (first selected wins);"),
        AnimeFilter.Header("Genre/Tag tokens combine with the search text"),
        TypeFilter(),
        StatusFilter(),
        CensorshipFilter(),
        SourceFilter(),
        ResolutionFilter(),
        ContentRatingFilter(),
        GenreTokenFilter(),
        TagTokenFilter(),
    )

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private val DATE_REGEX = Regex("""(\d{1,2})(?:st|nd|rd|th)?\s+of\s+(\w+),?\s+(\d{4})""")

        private val MONTHS = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
        )

        /** (label, s= value) — order follows the site's sort dropdown. */
        private val SORTS = listOf(
            "Latest Released" to "rel-d",
            "Earliest Released" to "rel-a",
            "A to Z" to "az-a",
            "Z to A" to "az-d",
            "First Added" to "add-a",
            "Last Added" to "add-d",
            "Most Bookmarked" to "bkm-d",
            "Least Bookmarked" to "bkm-a",
            "Highest Rated" to "rtg-d",
            "Lowest Rated" to "rtg-a",
            "Most Popular" to "vtt-d",
            "Least Popular" to "vtt-a",
            "Most Popular Today" to "vdy-d",
            "Least Popular Today" to "vdy-a",
            "Most Popular This Week" to "vwk-d",
            "Least Popular This Week" to "vwk-a",
            "Most Popular This Month" to "vmt-d",
            "Least Popular This Month" to "vmt-a",
            "Most Popular This Year" to "vyr-d",
            "Least Popular This Year" to "vyr-a",
        )
        private const val SORT_DEFAULT_INDEX = 0

        private val TYPES = listOf(
            "" to "Any", "unknown" to "Unknown", "tv-series" to "TV Series",
            "ova" to "OVA", "movie" to "Movie", "web" to "Web",
            "music-video" to "Music Video", "tv-special" to "TV Special", "other" to "Other",
        )

        private val STATUSES = listOf(
            "" to "Any",
            "unknown" to "Unknown",
            "ongoing" to "Ongoing",
            "completed" to "Completed",
            "stalled" to "Stalled",
        )

        private val CENSORSHIPS = listOf(
            "" to "Any",
            "n-a" to "N/A",
            "censored" to "Censored",
            "uncensored" to "Uncensored",
        )

        private val SOURCES = listOf(
            "" to "Any", "n-a" to "N/A", "tv" to "TV", "web" to "Web", "dvd" to "DVD",
            "bd" to "Blu-ray", "vhs" to "VHS", "vcd" to "VCD", "ld" to "LD",
        )

        private val RESOLUTIONS = listOf(
            "" to "Any",
            "n-a" to "N/A",
            "360p" to "360p",
            "480p" to "480p",
            "576p" to "576p",
            "720p" to "720p",
            "1080p" to "1080p",
        )

        private val CONTENT_RATINGS = listOf(
            "" to "Any",
            "unknown" to "Unknown",
            "g" to "G - All Ages",
            "pg" to "PG - Children",
            "pg13" to "PG-13 - Teens 13+",
            "rplus" to "R+ - Mild Nudity",
            "r17plus" to "R - 17+ (violence & profanity)",
            "rx" to "Rx - Hentai",
        )
    }
}
