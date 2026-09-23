/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaihaven

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * HentaiHaven (https://hentaihaven.xxx)
 *
 * Next.js frontend backed by a WordPress CMS (cms.hentaihaven.xxx).
 *
 * Catalogue, search, sorting and taxonomy filtering are all served by the site's
 * own JSON API at /api/manga/. Accepted params: page, per_page, search,
 * sort (views|rating|latest) and the taxonomy term IDs genre, tag, author, release.
 *
 * API quirks confirmed by probing the live site:
 *  - `sort` may only be combined with `genre`. Mixing it with `search`, `tag`,
 *    `author` or `release` returns HTTP 400 ("These filters cannot be combined
 *    with a catalogue sort"), so the sort is dropped in those cases.
 *  - The API takes exactly ONE value per taxonomy facet (`genre=1,2` -> HTTP 400,
 *    `genre[]=` -> HTTP 403), so several picks inside one filter group are fetched
 *    as separate requests and merged (OR), while picks in different groups pin the
 *    first value of each and therefore combine with each other (AND).
 *  - Every API row carries its own taxonomy id arrays (wp-manga-genre/-author/
 *    -release/-tag), which is what makes exclusion exact: a title is dropped when
 *    any of its ids is on an excluded list. No guesswork, no extra requests.
 *  - per_page is capped at 48; filter IDs must be positive integers.
 *
 * Video streams: the watch page embeds an octopus stream UUID (a different one per
 * episode) and the HLS master playlist lives at
 * octopusmanifest.org/{uuid}/playlist.m3u8. Its video renditions are video-only
 * (fMP4/avc1) with audio and subtitles served as separate renditions, so every
 * quality entry has to ship the audio and subtitle tracks alongside it.
 */
class HentaiHaven : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "HentaiHaven"

    override val baseUrl = "https://hentaihaven.xxx"

    override val lang = "all"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val catalogueApiUrl = "$baseUrl/api/manga/"
    private val imageBaseUrl = "https://img.hentaihaven.xxx"

    // The site itself renders 25 entries per page, and matching that keeps our
    // Popular/Trending ordering identical to /browse/trending (the ranking metric has
    // ties, so a different page size can transpose neighbouring entries).
    private val pageSize = 25

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Catalogue (JSON API) ==============================

    private fun catalogueRequest(
        page: Int,
        sort: String? = null,
        search: String? = null,
        genre: Int? = null,
        tag: Int? = null,
        studio: Int? = null,
        year: Int? = null,
        trendingPeriod: String? = null,
    ): Request {
        val url = catalogueApiUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("per_page", pageSize.toString())
            addQueryParameter("page", page.toString())
            if (trendingPeriod != null) {
                // The ranking endpoint is exclusive: combining trending_period with a
                // sort, a search or any taxonomy filter returns HTTP 400
                // ("Trending filters are not supported by the ranking endpoint").
                addQueryParameter("trending_period", trendingPeriod)
            } else {
                if (!sort.isNullOrBlank()) addQueryParameter("sort", sort)
                if (!search.isNullOrBlank()) addQueryParameter("search", search)
                genre?.let { addQueryParameter("genre", it.toString()) }
                tag?.let { addQueryParameter("tag", it.toString()) }
                studio?.let { addQueryParameter("author", it.toString()) }
                year?.let { addQueryParameter("release", it.toString()) }
            }
        }.build()
        return GET(url.toString(), headers)
    }

    private fun parseCatalogue(response: Response): AnimesPage {
        val (rows, hasNextPage) = parseRows(response)
        return AnimesPage(rows.map { it.anime }, hasNextPage)
    }

    /**
     * One catalogue row plus the taxonomy ids the API attaches to it.
     *
     * The ids are what exclusion runs on: a row is filtered out when any of its
     * genre/studio/year/tag ids appears on the corresponding exclude list, which is
     * the only way to honour an exclude tap on an API that cannot express "not".
     */
    private class CatalogueRow(
        val anime: SAnime,
        val genres: Set<Int>,
        val studios: Set<Int>,
        val years: Set<Int>,
        val tags: Set<Int>,
    ) {
        fun excludedBy(excluded: Map<Facet, Set<Int>>): Boolean =
            excluded[Facet.GENRE].orEmpty().any { it in genres } ||
                excluded[Facet.STUDIO].orEmpty().any { it in studios } ||
                excluded[Facet.YEAR].orEmpty().any { it in years } ||
                excluded[Facet.TAG].orEmpty().any { it in tags }
    }

    /** Parses one catalogue response into rows; the body may only be read once. */
    private fun parseRows(response: Response): Pair<List<CatalogueRow>, Boolean> {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val body = response.body?.string().orEmpty()
        if (body.isBlank()) return emptyList<CatalogueRow>() to false

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList<CatalogueRow>() to false
        root["error"]?.jsonPrimitive?.content?.let { error ->
            throw Exception("HentaiHaven: $error")
        }
        val entries = root["data"]?.jsonArray ?: return emptyList<CatalogueRow>() to false
        val rows = entries.mapNotNull { entry ->
            runCatching {
                val hit = entry.jsonObject
                CatalogueRow(
                    anime = hitToAnime(hit),
                    genres = hit.termIds("wp-manga-genre"),
                    studios = hit.termIds("wp-manga-author"),
                    years = hit.termIds("wp-manga-release"),
                    tags = hit.termIds("wp-manga-tag"),
                )
            }.getOrNull()
        }
        val totalPages = root["totalPages"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        return rows to (page < totalPages)
    }

    private fun JsonObject.termIds(key: String): Set<Int> =
        (this[key] as? JsonArray)
            ?.mapNotNull { runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() }
            ?.toSet()
            .orEmpty()

    private fun hitToAnime(hit: JsonObject): SAnime = SAnime.create().apply {
        val slug = hit["slug"]?.jsonPrimitive?.content ?: ""
        url = "/watch/$slug/"
        // The CMS renders titles as HTML, so they contain entities such as
        // &#8230; (ellipsis) and &#038; (ampersand) that must be unescaped.
        title = hit["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content
            ?.let { Parser.unescapeEntities(it, false).trim() }
            ?.takeIf { it.isNotBlank() }
            ?: slug
        thumbnail_url = hit["meta"]?.jsonObject?.get("vraven_remote_thumbnail")
            ?.jsonPrimitive?.contentOrNull
            ?.toThumbnailUrl()
    }

    /**
     * Builds a loadable cover URL.
     *
     * The CMS returns either a full URL or a path relative to the image host, and the
     * paths are raw filesystem names: they can contain spaces and non-ASCII characters
     * (typographic ellipsis, en dash). Those have to be percent-encoded or the image
     * request is malformed and the cover silently fails to load, which is why some
     * posters were blank in the grid.
     *
     * Covers whose only asset is AVIF are dropped: Android cannot decode AVIF before
     * API 31, and returning null lets the app fall back to a placeholder instead of
     * showing a broken tile.
     */
    private fun String.toThumbnailUrl(): String? {
        val raw = trim().takeIf { it.isNotEmpty() } ?: return null
        if (raw.endsWith(".avif", ignoreCase = true)) return null

        val absolute = if (raw.startsWith("http")) raw else "$imageBaseUrl/${raw.removePrefix("/")}"
        // Already-encoded input must not be double-encoded, so only touch the path
        // when it still holds characters that are illegal in a URL.
        return absolute.encodeUrlPath()
    }

    /** Percent-encodes every path character that is not URL-safe, leaving %XX intact. */
    private fun String.encodeUrlPath(): String {
        val schemeEnd = indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return this
        val pathStart = indexOf('/', schemeEnd).takeIf { it >= 0 } ?: return this
        val origin = substring(0, pathStart)
        val path = substring(pathStart)

        val encoded = StringBuilder(path.length)
        var index = 0
        while (index < path.length) {
            val char = path[index]
            when {
                // Keep existing percent-escapes as they are.
                char == '%' && index + 2 < path.length &&
                    path[index + 1].isHexDigit() && path[index + 2].isHexDigit() -> {
                    encoded.append(path, index, index + 3)
                    index += 2
                }
                char in URL_PATH_SAFE_CHARS -> encoded.append(char)
                else -> char.toString().toByteArray(Charsets.UTF_8).forEach { byte ->
                    encoded.append('%').append("%02X".format(byte.toInt() and 0xFF))
                }
            }
            index++
        }
        return origin + encoded
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    // ============================== Popular Anime ==============================

    /**
     * Mirrors the website's Trending rail.
     *
     * The homepage "Trending" section and /browse/trending/ are served by the API's
     * ranking endpoint (`trending_period`), not by `sort=views`. Verified: the first
     * 25 entries of /browse/trending/ match `trending_period=monthly` in exact order,
     * whereas `sort=views` gives the all-time most-viewed list instead - which is why
     * Popular did not look like the site's trending page.
     *
     * `trending_period=all` is the all-time ranking; monthly is what the site shows by
     * default, and the user-facing sort filter can switch to the other orderings.
     */
    override fun popularAnimeRequest(page: Int): Request =
        catalogueRequest(page, trendingPeriod = TRENDING_PERIOD_MONTHLY)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseCatalogue(response)

    // ============================== Latest Updates ==============================

    override fun latestUpdatesRequest(page: Int): Request =
        catalogueRequest(page, sort = "latest")

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseCatalogue(response)

    // ============================== Search ==============================

    // The picker lives in AniZen, the merge happens in the parse step, so the
    // selection has to survive between the two calls.
    private var lastFilters: AnimeFilterList? = null
    private var lastPage: Int = 1

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        lastFilters = filters
        lastPage = page

        val trimmed = query.trim()
        val genres = selected(filters, Facet.GENRE)
        val studios = selected(filters, Facet.STUDIO)
        val years = selected(filters, Facet.YEAR)
        val tags = selected(filters, Facet.TAG)
        val unfiltered = trimmed.isEmpty() &&
            genres.isEmpty() && studios.isEmpty() && years.isEmpty() && tags.isEmpty()

        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()

        // The two trending orderings come from the ranking endpoint, which rejects any
        // other parameter, so they only apply to plain browsing.
        sortFilter?.trendingPeriod?.takeIf { unfiltered }?.let { period ->
            return catalogueRequest(page, trendingPeriod = period)
        }

        // `sort` itself may only travel with `genre`; pairing it with a text search or
        // with the tag/studio/year facets is rejected (HTTP 400), so it is dropped.
        val sortable = trimmed.isEmpty() && studios.isEmpty() && years.isEmpty() && tags.isEmpty()
        val sort = sortFilter?.sortValue?.takeIf { sortable }

        // One value per facet in the request; any further pick is merged in the parse.
        return catalogueRequest(
            page = page,
            sort = sort,
            search = trimmed.ifEmpty { null },
            genre = genres.firstOrNull(),
            tag = tags.firstOrNull(),
            studio = studios.firstOrNull(),
            year = years.firstOrNull(),
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val (rows, firstHasNext) = parseRows(response)
        val merged = LinkedHashMap<String, CatalogueRow>()
        rows.forEach { merged[it.anime.url] = it }
        var hasNextPage = firstHasNext

        extraSelectionRequests().forEach { request ->
            runCatching {
                client.newCall(request).execute().use { extra ->
                    val (extraRows, extraHasNext) = parseRows(extra)
                    extraRows.forEach { merged.putIfAbsent(it.anime.url, it) }
                    hasNextPage = hasNextPage || extraHasNext
                }
            }
        }

        val filters = lastFilters
        val excluded = Facet.values().associateWith { excludedTerms(filters, it) }
        val animes = if (excluded.values.all { it.isEmpty() }) {
            merged.values.map { it.anime }
        } else {
            merged.values.filterNot { it.excludedBy(excluded) }.map { it.anime }
        }
        return AnimesPage(animes, hasNextPage)
    }

    /**
     * One request per extra pick, with every other facet pinned to its first pick.
     *
     * The API cannot take two values for the same facet, so a second ticked genre has
     * to be fetched separately and merged. Capped so a large selection cannot turn a
     * single page load into dozens of requests.
     */
    private fun extraSelectionRequests(): List<Request> {
        val filters = lastFilters ?: return emptyList()
        val genres = selected(filters, Facet.GENRE)
        val studios = selected(filters, Facet.STUDIO)
        val years = selected(filters, Facet.YEAR)
        val tags = selected(filters, Facet.TAG)

        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()
        val sortable = studios.isEmpty() && years.isEmpty() && tags.isEmpty()
        val sort = sortFilter?.sortValue?.takeIf { sortable }

        val requests = mutableListOf<Request>()
        fun pin(facet: Facet, id: Int) {
            if (requests.size >= MAX_MERGE_REQUESTS) return
            requests += catalogueRequest(
                page = lastPage,
                sort = sort,
                genre = if (facet == Facet.GENRE) id else genres.firstOrNull(),
                tag = if (facet == Facet.TAG) id else tags.firstOrNull(),
                studio = if (facet == Facet.STUDIO) id else studios.firstOrNull(),
                year = if (facet == Facet.YEAR) id else years.firstOrNull(),
            )
        }
        genres.drop(1).forEach { pin(Facet.GENRE, it) }
        studios.drop(1).forEach { pin(Facet.STUDIO, it) }
        years.drop(1).forEach { pin(Facet.YEAR, it) }
        tags.drop(1).forEach { pin(Facet.TAG, it) }
        return requests
    }

    // ============================== Filters ==============================

    private class SortFilter : AnimeFilter.Select<String>(
        "Sort by",
        arrayOf("Trending (monthly)", "Trending (all time)", "Latest", "Most viewed", "Top rated"),
        0,
    ) {
        /** Non-null for the two rankings served by the ranking endpoint. */
        val trendingPeriod: String?
            get() = when (state) {
                0 -> TRENDING_PERIOD_MONTHLY
                1 -> TRENDING_PERIOD_ALL
                else -> null
            }

        /** Used for everything the plain catalogue endpoint can sort. */
        val sortValue: String
            get() = when (state) {
                3 -> "views"
                4 -> "rating"
                else -> "latest"
            }
    }

    /** Which taxonomy a row belongs to; exclusion and pinning both key off this. */
    private enum class Facet { GENRE, STUDIO, YEAR, TAG }

    /** One row per taxonomy term: tap to include, tap again to exclude. */
    private class TermFilter(name: String, val termId: Int, val facet: Facet) :
        AnimeFilter.TriState(name, AnimeFilter.TriState.STATE_IGNORE)

    private class TermGroup(name: String, terms: List<Pair<Int, String>>, facet: Facet) :
        AnimeFilter.Group<AnimeFilter<*>>(name, terms.map { TermFilter(it.second, it.first, facet) })

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Tap to include (blue), tap again to exclude (red)"),
        AnimeFilter.Header("Picks inside a group are combined; groups AND together"),
        AnimeFilter.Header("Exclusions are exact - matched on each title's own terms"),
        TermGroup("Genres", GENRES, Facet.GENRE),
        TermGroup("Studios", STUDIOS, Facet.STUDIO),
        TermGroup("Released year", YEARS, Facet.YEAR),
        TermGroup("Tags", TAGS, Facet.TAG),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Trending sorts apply to plain browsing only; picking any filter falls back to Latest/Most viewed/Top rated"),
    )

    private fun flatFilters(filters: AnimeFilterList?): List<AnimeFilter<*>> =
        filters.orEmpty().flatMap { filter ->
            if (filter is AnimeFilter.Group<*>) {
                filter.state.filterIsInstance<AnimeFilter<*>>()
            } else {
                listOf(filter)
            }
        }

    /** Ids ticked into this facet. */
    private fun selected(filters: AnimeFilterList?, facet: Facet): List<Int> =
        termsInState(filters, facet, AnimeFilter.TriState.STATE_INCLUDE)

    /** Ids crossed out in this facet. */
    private fun excludedTerms(filters: AnimeFilterList?, facet: Facet): Set<Int> =
        termsInState(filters, facet, AnimeFilter.TriState.STATE_EXCLUDE).toSet()

    private fun termsInState(filters: AnimeFilterList?, facet: Facet, state: Int): List<Int> =
        flatFilters(filters)
            .filterIsInstance<TermFilter>()
            .filter { it.facet == facet && it.state == state }
            .map { it.termId }

    // ============================== Anime Details ==============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string(), baseUrl)

        return SAnime.create().apply {
            // og:title carries the clean title plus a fixed site suffix.
            title = document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - Hentai Haven")
                ?.trim()
                .orEmpty()
                .ifEmpty { document.selectFirst("h1")?.text().orEmpty() }

            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

            // The real per-title synopsis is the line-clamped paragraph in the info
            // block; the meta description is boilerplate SEO text, so it is not used.
            description = document.selectFirst("p[class*=line-clamp]")?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            // Genres are the /series/ chips in the info block. Other /series/ links on
            // the page belong to related-title cards, hence the badge restriction.
            // Case-insensitive de-dup: a repeated badge under different casing collides
            // in AniZen's genre-chip keys and crashes the details screen.
            genre = document.select("a[data-slot=badge][href^=\"/series/\"]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .joinToString(", ")
                .takeIf { it.isNotBlank() }

            author = document.selectFirst("a[data-slot=badge][href^=\"/studio/\"]")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }

            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    // ============================== Recommendations ==============================
    // AniZen fills its "Recommended" rail from `supportsRelatedAnimes` +
    // `fetchRelatedAnimeList`. Those members exist on AniZen's runtime source API but
    // not on the lib-14 stub this compiles against, so they are declared without
    // `override`; the JVM still dispatches the runtime interface's default methods
    // to them. Without these the rail stays empty.

    val supportsRelatedAnimes: Boolean get() = true

    suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        // The watch page already carries a site-curated "See More" rail of other
        // titles, so prefer it: one request, and it matches what the site shows.
        val fromPage = runCatching {
            client.newCall(animeDetailsRequest(anime)).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                relatedFromWatchPage(response.body.string(), anime.url)
            }
        }.getOrDefault(emptyList())

        if (fromPage.isNotEmpty()) return fromPage

        // Fallback: same-genre titles from the catalogue API.
        val genreId = anime.genre
            ?.split(",")
            ?.asSequence()
            ?.map { it.trim() }
            ?.mapNotNull { name -> GENRES.firstOrNull { it.second.equals(name, true) }?.first }
            ?.firstOrNull { it > 0 }
            ?: return emptyList()

        return runCatching {
            client.newCall(catalogueRequest(1, genre = genreId)).execute().use { response ->
                parseCatalogue(response).animes.filterNot { it.url == anime.url }
            }
        }.getOrDefault(emptyList())
    }

    /** Reads the "See More" rail: poster cards linking to other titles. */
    private fun relatedFromWatchPage(html: String, currentUrl: String): List<SAnime> {
        val document = Jsoup.parse(html, baseUrl)
        return document.select("article a[href^=\"/watch/\"]:has(img)")
            .mapNotNull { anchor ->
                val href = anchor.attr("href")
                if (href.isBlank() || href == currentUrl) return@mapNotNull null
                // Skip episode links; the rail links to title pages.
                if (EPISODE_REGEX.containsMatchIn(href)) return@mapNotNull null
                val image = anchor.selectFirst("img") ?: return@mapNotNull null
                val name = image.attr("alt").trim().ifBlank { return@mapNotNull null }
                SAnime.create().apply {
                    url = href
                    title = name
                    thumbnail_url = image.attr("src").takeIf { it.isNotBlank() }
                        ?.let { if (it.endsWith(".avif", true)) null else it }
                }
            }
            .distinctBy { it.url }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val watchPath = response.request.url.encodedPath
        // Only episodes of THIS title: the watch page also lists related titles,
        // whose cards link to other anime's episodes.
        val currentSlug = SLUG_REGEX.find(watchPath)?.groupValues?.get(1)
            ?: return emptyList()
        val document = Jsoup.parse(response.body.string(), baseUrl)

        val episodes = document.select("a[href^=\"/watch/$currentSlug/episode-\"]")
            .mapNotNull { anchor ->
                val number = EPISODE_REGEX.find(anchor.attr("href"))
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                SEpisode.create().apply {
                    url = "/watch/$currentSlug/episode-$number/"
                    name = "Episode $number"
                    episode_number = number.toFloat()
                    date_upload = anchor.episodeDate()
                    // Episode card thumbnails come from coverlanyvd.org storage.
                    // AniZen's runtime SEpisode exposes these via preview_url
                    // (there is no episode-level thumbnail_url field).
                    anchor.selectFirst("img[src]")?.attr("src")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { setEpisodeField(this, "preview_url", it) }
                }
            }
            .groupBy { it.url }
            // Each episode appears twice: as a card (which carries the dated thumbnail)
            // and as a "Watch Ep" button (which does not). Keep the dated one.
            .map { (_, duplicates) -> duplicates.maxBy { it.date_upload } }
            .sortedByDescending { it.episode_number }

        return episodes.ifEmpty {
            listOf(
                SEpisode.create().apply {
                    url = watchPath
                    name = "Episode 1"
                    episode_number = 1f
                },
            )
        }
    }

    /**
     * Episode cards embed a thumbnail served from a dated storage path
     * (.../storage/2026/08/20/slug-6/s_thumbnail.webp) which is the episode's
     * publish date. Returns 0 when the anchor carries no thumbnail.
     */
    private fun Element.episodeDate(): Long {
        val src = selectFirst("img[src]")?.attr("src") ?: return 0L
        val match = THUMB_DATE_REGEX.find(src) ?: return 0L
        return runCatching { DATE_FORMATTER.parse(match.groupValues[1])?.time }.getOrNull() ?: 0L
    }

    // ============================== Video Streams ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        // The watch page embeds the octopus stream UUID, so fetch it to resolve the
        // HLS manifest on octopusmanifest.org.
        val url = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
        return GET(url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        if (!response.isSuccessful) {
            throw Exception(
                "HentaiHaven: failed to fetch the watch page (HTTP ${response.code}). " +
                    "If this persists, the site's video CDN may be blocking your region.",
            )
        }

        val uuid = UUID_REGEX.find(response.body.string())?.value
            ?: throw Exception("HentaiHaven: no video source found for this episode")

        return videosFromMaster("https://octopusmanifest.org/$uuid/playlist.m3u8")
    }

    /**
     * Builds the playable video list from the HLS master playlist.
     *
     * How this stream is laid out (all verified against the live CDN):
     *  - The per-quality variant playlists (`7sop/v.m3u8`, `3cop/v.m3u8`) are
     *    **video-only** fMP4: their init segment carries a single `vide` handler with
     *    an `avc1` sample entry and no `mp4a`, despite `EXT-X-STREAM-INF` advertising
     *    `CODECS="avc1.4d4028,mp4a.40.2"`.
     *  - Audio is one separate rendition (`snd/a.m3u8`, `soun`/`mp4a`) and subtitles
     *    are separate WebVTT renditions; both are only referenced by the *master*.
     *  - There is no per-quality master: `?res=`/`?quality=`/`?max=` return a
     *    byte-identical master and `7sop/playlist.m3u8` is 404.
     *
     * So each selectable quality is a variant playlist plus the audio and subtitle
     * renditions attached as external tracks, which is what makes the quality list
     * populate at all; the variants are video-only, so the "Japanese" external audio
     * attaches cleanly and the audio rack gets the right label.
     *
     * The octopus "master" playlist is also offered, as "Auto". It muxes the audio
     * in-band, so it is the only entry whose sound plays out of the box - mpv's player
     * ships the external audio track toggle off by default, and a video-only variant
     * with no enabled audio is silent. That in-band track cannot be relabelled, which
     * is why "Auto" keeps the manifest's own track while the fixed qualities are
     * labelled correctly; on the phone the muxed track shows as the site's default name
     * ("#1: Proudly served by MuchoHentai.com") which is what the site itself calls it.
     */
    private fun videosFromMaster(masterUrl: String): List<Video> {
        val playbackHeaders = videoHeaders()

        // Last-resort entry: the raw master. Its audio muxes in-band, so sound always
        // works, but mpv treats it as a live window and forward skips can stall.
        val plainAuto = listOf(Video(masterUrl, "Auto", masterUrl, headers = playbackHeaders))

        val playlist = runCatching {
            client.newCall(GET(masterUrl, playbackHeaders)).execute().use { res ->
                if (res.isSuccessful) res.body.string() else null
            }
        }.getOrNull()
        if (playlist.isNullOrBlank()) return plainAuto

        val uuid = UUID_REGEX.find(masterUrl)?.value ?: return plainAuto

        // Every fixed quality is served through a loopback VOD server: the chosen video
        // variant and the Japanese audio rendition are re-joined into one normal VOD
        // stream, so sound plays out of the box AND skipping ahead behaves like a file.
        val server = runCatching {
            HlsVodServer.forMaster(client, playbackHeaders, uuid, playlist)
        }.getOrNull() ?: return plainAuto

        val variants = STREAM_INF_REGEX.findAll(playlist).mapNotNull { match ->
            val attributes = match.groupValues[1]
            match.groupValues[2].trim()
                .takeIf { it.isNotBlank() && !it.startsWith("#") }
                ?: return@mapNotNull null
            val label = attributes.qualityLabel()
            Video(
                url = server.vodUrl(label),
                quality = label,
                videoUrl = server.vodUrl(label),
                headers = playbackHeaders,
            )
        }.toList()

        return variants.ifEmpty { plainAuto }
    }

    /** "1280x720" -> "720p", falling back to the bitrate when there is no resolution. */
    private fun String.qualityLabel(): String =
        hlsAttr("RESOLUTION")?.substringAfter('x', "")?.takeIf { it.isNotBlank() }?.let { "${it}p" }
            ?: hlsAttr("BANDWIDTH")?.toLongOrNull()?.let { "${it / 1000} kbps" }
            ?: "Auto"

    /** Reads a quoted or unquoted attribute out of an HLS attribute list. */
    private fun String.hlsAttr(name: String): String? =
        Regex("""(?:^|[,:])$name=(?:"([^"]*)"|([^,"]*))""").find(this)
            ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            ?.takeIf { it.isNotBlank() }

    override fun List<Video>.sort(): List<Video> {
        val preferred = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(preferred, true) }
                .thenByDescending { it.quality.videoHeight() },
        )
    }

    private fun String.videoHeight(): Int =
        HEIGHT_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

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

    // Playback headers: the video CDN expects a browser-like Referer/Origin.
    private fun videoHeaders(): Headers = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .add("Accept", "*/*")
        .build()

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = PREF_QUALITY_VALUES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    private companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"

        // Every quality now plays through the loopback VOD server, which re-joins the
        // video variant with the Japanese audio rendition as one normal VOD stream - so
        // the highest quality is both watchable AND seekable. The raw master ("Auto")
        // stays in the list purely as a fallback if the local server ever fails.
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private val PREF_QUALITY_VALUES = arrayOf("1080p", "720p", "480p", "360p", "Auto")

        // The ranking endpoint's only accepted periods (everything else is rejected
        // with "Unsupported trending period").
        private const val TRENDING_PERIOD_MONTHLY = "monthly"
        private const val TRENDING_PERIOD_ALL = "all"

        /** Upper bound on merge requests for one page load. */
        private const val MAX_MERGE_REQUESTS = 12

        private val SLUG_REGEX = Regex("""/watch/([^/]+)""")
        private val EPISODE_REGEX = Regex("""episode-(\d+)""")
        private val HEIGHT_REGEX = Regex("""(\d+)p""")

        // Characters that may appear unescaped in a URL path (RFC 3986 pchar).
        private val URL_PATH_SAFE_CHARS: Set<Char> =
            (('a'..'z') + ('A'..'Z') + ('0'..'9') + "/-._~!\$&'()*+,;=:@".toList()).toSet()

        // Thumbnails live under /storage/<yyyy>/<MM>/<dd>/<slug-episode>/…
        private val THUMB_DATE_REGEX = Regex("""/storage/(\d{4}/\d{2}/\d{2})/""")

        private val UUID_REGEX =
            Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}""")

        private val STREAM_INF_REGEX = Regex("""#EXT-X-STREAM-INF:(.+)\r?\n(.+)""")

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy/MM/dd", Locale.ENGLISH)
        }
    }
}
