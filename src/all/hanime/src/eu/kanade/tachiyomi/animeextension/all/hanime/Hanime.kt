package eu.kanade.tachiyomi.animeextension.all.hanime

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import rx.Observable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Hanime : AnimeHttpSource() {

    override val name = "Hanime (𝕬𝕽)"

    override val baseUrl = "https://hanime.tv"

    override val lang = "all"

    override val supportsLatest = true

    private val searchApiUrl = "https://guest.freeanimehentai.net/api/v11/search_hvs"

    private val trendingUrl = "$baseUrl/browse/trending"

    private val resultsPerPage = 24

    private val trailingEpisodeRegex = Regex("""-\d+$""")

    private val trailingSeasonRegex = Regex("""-season-\d+$""", RegexOption.IGNORE_CASE)

    private val trailingNumberRegex = Regex("""\s+\d+\s*$""")

    private val episodeMarkerRegex =
        Regex("""\s+(?:ep\.?|episode|episodes|ova|part|vol)\s*$""", RegexOption.IGNORE_CASE)

    private val seasonSuffixRegex = Regex("""(?i)\bseason\s+\d+\s*$""")

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Cookie", "inter=1")
        .add("Origin", "https://hanime.tv")
        .add("Referer", "$baseUrl/")

    private fun streamHeaders(): Headers = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", "$baseUrl")
        .build()

    // ============================== Catalog (search API) ==============================
    // The guest search API returns the whole catalog as a JSON array (one entry per
    // episode, in id ascending order). It is fetched once and cached in memory with a
    // short TTL so pagination does not re-download it, yet "Latest" stays fresh.

    private data class CatalogEntry(
        val slug: String,
        val name: String,
        val searchTitles: String,
        val coverUrl: String?,
        val tags: List<String>,
        val brand: String?,
        val views: Long,
        val likes: Long,
        val releasedAt: Long,
        val createdAt: Long,
    )

    @Volatile
    private var catalogCache: List<CatalogEntry>? = null

    @Volatile
    private var catalogFetchedAt: Long = 0L

    private val catalogLock = Any()

    private val catalogTtlMillis = 10 * 60 * 1000L

    private fun catalogFresh(): Boolean =
        catalogCache != null && System.currentTimeMillis() - catalogFetchedAt < catalogTtlMillis

    /**
     * Returns the catalog, downloading and caching it on first use (or when stale).
     */
    private fun getCatalog(): List<CatalogEntry> {
        synchronized(catalogLock) {
            if (catalogFresh()) return catalogCache!!
            val request = GET("$searchApiUrl?search_text=&page=0", headers)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Search API error: ${response.code}")
                }
                catalogCache = parseCatalog(response.body.string())
                catalogFetchedAt = System.currentTimeMillis()
            }
            return catalogCache!!
        }
    }

    private fun parseCatalog(body: String): List<CatalogEntry> {
        // The guest search API now wraps the list in {"data":[...],"ads":{...}};
        // older responses were a bare array. Tolerate both.
        val jsonArray = if (body.trimStart().startsWith("{")) {
            JSONObject(body).optJSONArray("data") ?: JSONArray()
        } else {
            JSONArray(body)
        }
        return buildList {
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val tagsArray = obj.optJSONArray("tags")
                add(
                    CatalogEntry(
                        slug = obj.optString("slug"),
                        name = obj.optString("name"),
                        searchTitles = obj.optString("search_titles"),
                        coverUrl = obj.optString("cover_url").ifEmpty {
                            obj.optString("poster_url")
                        }.ifEmpty { null },
                        tags = if (tagsArray == null) {
                            emptyList()
                        } else {
                            buildList {
                                for (j in 0 until tagsArray.length()) {
                                    add(tagsArray.optString(j))
                                }
                            }
                        },
                        brand = obj.optString("brand").ifEmpty { null },
                        views = obj.optLong("views", 0L),
                        likes = obj.optLong("likes", 0L),
                        releasedAt = obj.optLong("released_at_unix", 0L),
                        createdAt = obj.optLong("created_at_unix", 0L).takeIf { it > 0 }
                            ?: obj.optLong("released_at_unix", 0L),
                    ),
                )
            }
        }
    }

    /**
     * Resolves the catalog from the current response. If the response is the search
     * API dump it is parsed and cached; otherwise the cached catalog is reused.
     */
    private fun resolveCatalog(response: Response): List<CatalogEntry> {
        val url = response.request.url.toString()
        return if (url.contains("search_hvs")) {
            val parsed = parseCatalog(response.body.string())
            synchronized(catalogLock) {
                if (!catalogFresh()) {
                    catalogCache = parsed
                    catalogFetchedAt = System.currentTimeMillis()
                }
            }
            catalogCache!!
        } else {
            response.body.close()
            getCatalog()
        }
    }

    /**
     * Groups slugs belonging to the same series. Trailing episode numbers are
     * stripped ("foo-2" -> "foo"), but a trailing "-season-N" is kept intact so
     * each season stays its own tile instead of all seasons merging into one.
     */
    private fun baseSlug(slug: String): String =
        if (trailingSeasonRegex.containsMatchIn(slug)) {
            slug
        } else {
            slug.replace(trailingEpisodeRegex, "")
        }

    private fun episodeNumber(slug: String): Int =
        trailingEpisodeRegex.find(slug)?.groupValues?.get(0)?.removePrefix("-")?.toIntOrNull() ?: 0

    private fun yearOf(epochSeconds: Long): Int {
        if (epochSeconds <= 0) return 0
        return Calendar.getInstance().apply { timeInMillis = epochSeconds * 1000 }.get(Calendar.YEAR)
    }

    /**
     * Cleans a series title by dropping the trailing episode number (and any episode
     * markers such as "Ep."/"OVA") so the catalog shows the bare series name
     * (e.g. "Kaifuku Jutsushi no Yarinaoshi 1" -> "Kaifuku Jutsushi no Yarinaoshi").
     * Only applied to multi-episode series; single-video titles are kept verbatim.
     * A trailing "Season N" is preserved ("... Season 2" is not reduced to
     * "... Season") so season tiles keep their number.
     */
    private fun seriesTitle(name: String): String {
        val trimmed = name.trim()
        if (seasonSuffixRegex.containsMatchIn(trimmed)) return trimmed
        var cleaned = trimmed
            .replace(trailingNumberRegex, "")
            .replace(episodeMarkerRegex, "")
            .trim()
        if (cleaned.length < 3) cleaned = trimmed
        return cleaned
    }

    /**
     * The search API's `search_titles` field concatenates every known name of a
     * video in several scripts without separators (e.g. "...Website of Darkness
     * ヤバい! -復讐・闇サイト- 위험해! -복수・암흑 Site- 危机！-复仇·暗黑网站").
     * Splits it back into the distinct alternative names, dropping the main
     * title itself.
     */
    private fun extractAlternativeNames(searchTitles: String, name: String): List<String> {
        val raw = Jsoup.parse(searchTitles).text().trim()
        if (raw.isBlank()) return emptyList()

        // Remove occurrences of the main title and its episode-less form so the
        // remaining text contains only the "other" names.
        var cleaned = raw.replace('\u3000', ' ')
        for (variant in listOf(name.trim(), seriesTitle(name))) {
            if (variant.isNotBlank() && variant.length >= 3) {
                cleaned = cleaned.replace(variant, " ", ignoreCase = true)
            }
        }

        // Split wherever the writing script changes; punctuation, spaces and
        // symbols extend the current segment instead of starting a new one.
        // Kanji (CJK ideographs) embedded in a kana run (e.g. "ヤバい! -復讐・闇サイト-")
        // extend that run rather than splitting it.
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var currentClass = -1
        for (c in cleaned) {
            var cls = scriptClass(c)
            if (cls == 3 && currentClass == 1) cls = 1
            if (cls >= 0 && currentClass >= 0 && cls != currentClass) {
                segments.add(current.toString().trim())
                current.setLength(0)
            }
            current.append(c)
            if (cls >= 0) currentClass = cls
        }
        if (current.isNotBlank()) segments.add(current.toString().trim())

        return segments
            .map { it.trim().trim(' ', '-', ':', ';', ',', '!', '?', '(', ')', '\u3000', '\u00A0') }
            .filter { it.length >= 2 }
            .filter { segment ->
                // Keep multi-word English variants but drop stray single tokens
                // left over from mixed-script names (e.g. "Site-") and
                // repeated placeholder text such as "OVA OVA".
                val purelyLatin = segment.all { scriptClass(it) == 0 || it == '-' }
                val words = segment.split(" ").filter { it.isNotBlank() }
                !purelyLatin || (words.size >= 2 && words.distinct().size > 1)
            }
            .filterNot { it.equals(name, ignoreCase = true) }
            .distinct()
            .take(8)
    }

    private fun scriptClass(c: Char): Int = when {
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> 0
        c in '\u3040'..'\u30FA' -> 1
        c in '\uAC00'..'\uD7AF' || c in '\u1100'..'\u11FF' -> 2
        c in '\u4E00'..'\u9FFF' -> 3
        else -> -1
    }

    /**
     * Groups catalog entries into series by their base slug, keeping the lowest
     * episode as the series representative so each series shows up only once.
     * The representative's title is cleaned to the bare series name when the
     * series has more than one episode.
     */
    private fun groupedCatalog(catalog: List<CatalogEntry>): List<CatalogEntry> =
        catalog.groupBy { baseSlug(it.slug) }.values.map { group ->
            val representative = group.minByOrNull { episodeNumber(it.slug) }!!
            if (group.size > 1) {
                representative.copy(name = seriesTitle(representative.name))
            } else {
                representative
            }
        }

    private fun pageCatalog(catalog: List<CatalogEntry>, page: Int): AnimesPage {
        val start = (page - 1) * resultsPerPage
        if (start >= catalog.size) return AnimesPage(emptyList(), false)
        val end = minOf(start + resultsPerPage, catalog.size)
        val items = catalog.subList(start, end).map { it.toSAnime() }
        return AnimesPage(items, end < catalog.size)
    }

    private fun CatalogEntry.toSAnime(): SAnime = SAnime.create().apply {
        title = name.trim()
        url = "/videos/hentai/$slug"
        thumbnail_url = coverUrl
    }

    // ============================== Popular Anime (site trending page) ==============================
    // The browse/trending page is server-rendered with 24 cards per page, so we parse
    // it directly. This matches the trending list shown on hanime.tv itself.

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$trendingUrl?page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string())
        val seen = HashSet<String>()
        val items = mutableListOf<SAnime>()

        // Base slugs that correspond to multi-episode series, so their card titles
        // can have the trailing episode number cleaned off. The catalog is cached,
        // so this does not add extra network requests.
        val multiEpisodeBases = runCatching {
            getCatalog()
                .groupBy { baseSlug(it.slug) }
                .filterValues { it.size > 1 }
                .keys
        }.getOrDefault(emptySet())

        document.select("a[href*=/videos/hentai/][title^=Watch]").forEach { card ->
            val href = card.attr("href").substringBefore("?")
            val slug = href.substringAfterLast("/")
            if (slug.isBlank()) return@forEach

            // Group episodes of the same series so each series appears once.
            if (!seen.add(baseSlug(slug))) return@forEach

            val img = card.selectFirst("img")
            val rawTitle = img?.attr("alt")?.trim().orEmpty().ifEmpty {
                card.attr("title").removePrefix("Watch ").substringBefore(" hentai").trim()
            }
            val cleanTitle = if (baseSlug(slug) in multiEpisodeBases) {
                seriesTitle(rawTitle)
            } else {
                rawTitle
            }
            items.add(
                SAnime.create().apply {
                    url = "/videos/hentai/$slug"
                    title = cleanTitle.ifEmpty { slug }
                    thumbnail_url = img?.attr("src")?.ifBlank { null }
                },
            )
        }

        // The pagination component renders a rel="next" link only while more
        // pages exist, so use it as the hasNextPage signal.
        val hasNextPage = document.selectFirst("a[rel=next]") != null
        return AnimesPage(items, hasNextPage)
    }

    // ============================== Latest Updates ==============================
    override fun latestUpdatesRequest(page: Int): Request {
        return if (page == 1 && !catalogFresh()) {
            GET("$searchApiUrl?search_text=&page=$page", headers)
        } else {
            GET("$baseUrl/favicon.ico?page=$page", headers)
        }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        // The API dump is ordered by id (oldest first); sort by upload time to get
        // actual recent releases.
        val catalog = groupedCatalog(resolveCatalog(response)).sortedByDescending { it.createdAt }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return pageCatalog(catalog, page)
    }

    // ============================== Search ==============================
    @Volatile
    private var searchQuery: String = ""

    @Volatile
    private var searchFilters: AnimeFilterList = AnimeFilterList()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        searchQuery = query
        searchFilters = filters
        return if (page == 1 && !catalogFresh()) {
            GET("$searchApiUrl?search_text=&page=$page", headers)
        } else {
            GET("$baseUrl/favicon.ico?page=$page", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val catalog = groupedCatalog(applyFilters(resolveCatalog(response), searchQuery, searchFilters))
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return pageCatalog(catalog, page)
    }

    private fun applyFilters(
        catalog: List<CatalogEntry>,
        query: String,
        filters: AnimeFilterList,
    ): List<CatalogEntry> {
        var list = catalog

        if (query.isNotBlank()) {
            // AniZen's "Similar Media" sends the title as space-separated
            // keywords (e.g. "yabai fukushuu yami site"), so match each token
            // independently instead of requiring the whole phrase (which fails
            // on punctuation like "Yabai!"). Single-character tokens (mostly
            // digits such as episode numbers) are dropped so they don't match
            // unrelated titles containing that digit.
            var tokens = query.lowercase().split(" ").filter { it.length > 1 }
            // A query that is entirely short tokens (e.g. "1 2") must not match
            // the whole catalog, so fall back to matching the raw phrase.
            if (tokens.isEmpty()) tokens = listOf(query.lowercase())
            list = list.filter { entry ->
                val haystack = "${entry.name} ${entry.slug} ${entry.searchTitles}".lowercase()
                tokens.all { haystack.contains(it) }
            }
        }

        // Genres and studios live inside collapsible Groups, so flatten the
        // group children before looking for their filter instances.
        val flatFilters = filters.flatMap { filter ->
            if (filter is AnimeFilter.Group<*>) {
                filter.state.filterIsInstance<AnimeFilter<*>>()
            } else {
                listOf(filter)
            }
        }
        val genreFilters = flatFilters.filterIsInstance<GenreFilter>()
        val includedGenres = genreFilters
            .filter { it.state == AnimeFilter.TriState.STATE_INCLUDE }
            .map { it.name.lowercase() }
        val excludedGenres = genreFilters
            .filter { it.state == AnimeFilter.TriState.STATE_EXCLUDE }
            .map { it.name.lowercase() }
        if (includedGenres.isNotEmpty() || excludedGenres.isNotEmpty()) {
            list = list.filter { entry ->
                val tags = entry.tags.map { it.lowercase() }
                includedGenres.all { tags.contains(it) } && excludedGenres.none { tags.contains(it) }
            }
        }

        val studioFilters = flatFilters.filterIsInstance<StudioFilter>()
        val includedStudios = studioFilters
            .filter { it.state == AnimeFilter.TriState.STATE_INCLUDE }
            .map { it.name.lowercase() }
        val excludedStudios = studioFilters
            .filter { it.state == AnimeFilter.TriState.STATE_EXCLUDE }
            .map { it.name.lowercase() }
        if (includedStudios.isNotEmpty() || excludedStudios.isNotEmpty()) {
            list = list.filter { entry ->
                val brand = (entry.brand ?: "").trim().lowercase()
                includedStudios.all { brand == it } && excludedStudios.none { brand == it }
            }
        }

        val yearFilter = filters.filterIsInstance<YearFilter>().firstOrNull()
        val year = yearFilter?.state?.let { YEAR_VALUES.getOrNull(it) }
        if (year != null && year != "All") {
            val yearInt = year.toIntOrNull()
            if (yearInt != null) {
                list = list.filter { entry -> entry.releasedAt > 0 && yearOf(entry.releasedAt) == yearInt }
            }
        }

        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()
        list = when (sortFilter?.state?.let { SORT_VALUES.getOrNull(it) }) {
            SORT_NEWEST -> list.sortedByDescending { it.createdAt }
            SORT_VIEWS -> list.sortedByDescending { it.views }
            SORT_LIKES -> list.sortedByDescending { it.likes }
            SORT_NAME_ASC -> list.sortedBy { it.name.lowercase() }
            SORT_NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
            SORT_OLDEST -> list.sortedBy { it.createdAt }
            else -> list
        }

        return list
    }

    // ============================== Filters ==============================
    private class FilterGroup(name: String, vararg filters: AnimeFilter<*>) :
        AnimeFilter.Group<AnimeFilter<*>>(name, filters.toList())

    private class GenreFilter(name: String) : AnimeFilter.TriState(name, AnimeFilter.TriState.STATE_IGNORE)

    private class StudioFilter(name: String) : AnimeFilter.TriState(name, AnimeFilter.TriState.STATE_IGNORE)

    private class YearFilter : AnimeFilter.Select<String>("Release Year", YEAR_VALUES, 0)

    private class SortFilter : AnimeFilter.Select<String>("Sorting", SORT_VALUES, 0)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        // Groups render as collapsible sections in AniZen (closed by default),
        // unlike a plain list of TriState filters which stays expanded.
        FilterGroup("Genres", *GENRES.map { GenreFilter(it) }.toTypedArray()),
        FilterGroup("Studios", *STUDIOS.map { StudioFilter(it) }.toTypedArray()),
        AnimeFilter.Header("Release Year"),
        YearFilter(),
        AnimeFilter.Header("Sorting"),
        SortFilter(),
    )

    // ============================== Details ==============================
    // The app (AniZen) requires the returned SAnime to have every mandatory
    // field set. `url` is a lateinit property in the app's models and reading it
    // uninitialized crashes with "lateinit property url/name has not been
    // initialized" when opening an entry, so we always carry over the original
    // url onto the parsed details.
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return super.fetchAnimeDetails(anime)
            .map { it.apply { url = anime.url } }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return super.getAnimeDetails(anime).apply { url = anime.url }
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
        return GET(url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string())

        // The details page lists every episode of the series (plus related videos
        // from other series). Count the entries sharing this series' base slug so
        // we only strip the trailing episode number from multi-episode series
        // titles ("Sister Breeder 1" -> "Sister Breeder") and never from a
        // standalone video whose name legitimately ends in a number.
        val slug = response.request.url.toString()
            .substringBefore("?")
            .substringAfterLast("/")
        val base = baseSlug(slug)
        val seriesEpisodeCount = document.select("[data-video-href*=/videos/hentai/]")
            .mapNotNull { element ->
                element.attr("data-video-href")
                    .substringBefore("?")
                    .substringAfterLast("/")
                    .ifBlank { null }
            }
            .distinct()
            .count { baseSlug(it) == base }

        val rawTitle = document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringAfter("Watch ")
                ?.substringBefore(" Hentai Video")
                ?.trim()
            ?: ""

        return SAnime.create().apply {
            title = if (seriesEpisodeCount > 1) seriesTitle(rawTitle) else rawTitle

            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img.hvpi-cover, img.cover")?.attr("src")

            // The real synopsis lives in its own container (div[data-expand-content]);
            // the site-wide SEO / promo paragraphs ("Our fans' community Discord...",
            // "What is Hentai?...") appear elsewhere on the page and are skipped.
            val synopsisParagraphs = document.select("div[data-expand-content] p").mapNotNull { p ->
                val text = p.text().trim()
                if (
                    text.length > 20 &&
                    !text.contains("Watch ", ignoreCase = true) &&
                    !text.contains("online", ignoreCase = true) &&
                    !text.contains("account", ignoreCase = true) &&
                    !text.contains("download", ignoreCase = true) &&
                    !text.contains("Share a bug", ignoreCase = true) &&
                    !text.contains("Session data", ignoreCase = true) &&
                    !text.contains("refresh the page", ignoreCase = true) &&
                    !text.contains("playlists", ignoreCase = true) &&
                    !text.contains("cookie", ignoreCase = true)
                ) {
                    text
                } else {
                    null
                }
            }

            // Alternative names come straight from the page's "Alternate Names"
            // section (one chip per name, e.g. "Custom Dorei", "Custom Reido",
            // "Custom Slave", ...). That is far more reliable than re-splitting
            // the search API's concatenated search_titles field, which merges
            // consecutive Latin names ("Custom Dorei Custom Reido Custom Slave")
            // and drops names that match the episode-less title. Fall back to the
            // catalog-based splitter only when the page has no such section.
            val alternativeNames = runCatching {
                val pageNames = document.select("div[data-expand-content] h3")
                    .mapNotNull { it.text().trim().ifBlank { null } }
                    .distinct()
                if (pageNames.isNotEmpty()) {
                    pageNames
                } else {
                    getCatalog()
                        .filter { baseSlug(it.slug) == base }
                        .flatMap { extractAlternativeNames(it.searchTitles, it.name) }
                        .distinct()
                }
            }.getOrDefault(emptyList())

            description = if (synopsisParagraphs.isNotEmpty()) {
                val synopsis = synopsisParagraphs.joinToString("\n\n")
                if (alternativeNames.isNotEmpty()) {
                    "$synopsis\n\nAlternative names: ${alternativeNames.joinToString(", ")}"
                } else {
                    synopsis
                }
            } else {
                document.selectFirst("meta[name=description]")?.attr("content")
                    ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            }

            // The video's own tags come from the catalog, which matches the tag row
            // shown on hanime.tv exactly. The details page also contains tag
            // *category* links ("Incest Hentai", "Milf Hentai", ...) elsewhere in
            // the layout, so selecting every a[href*=/browse/tags/] over-collects.
            val catalogTags = runCatching {
                getCatalog()
                    .filter { baseSlug(it.slug) == base }
                    .flatMap { it.tags }
                    .distinct()
                    .joinToString(", ")
            }.getOrNull()

            genre = if (!catalogTags.isNullOrBlank()) {
                catalogTags
            } else {
                document.select("a[href*=/browse/tags/]").joinToString(", ") { it.text() }
            }
            author = document.selectFirst("a[href*=/browse/brands/] strong")?.text()
                ?: document.selectFirst("a[href*=/browse/brands/]")?.text()
                    ?.removePrefix("Studio")
                    ?.trim()
            status = SAnime.COMPLETED
        }
    }

    // ============================== Recommendations ==============================
    // AniZen populates its "See Recommendations" screen with several sections.
    // The "Recommended" section is only filled when the extension declares
    // `supportsRelatedAnimes` and implements `fetchRelatedAnimeList`. Those
    // members exist on AniZen's runtime source API but not on the older lib-14
    // stub we compile against, so they are declared without `override` — the
    // JVM dispatches the runtime interface default methods to them anyway.
    // We recommend other series sharing the most genre tags with the current
    // anime, using the already-cached catalog (no extra network download).

    val supportsRelatedAnimes: Boolean get() = true

    suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        var tags = (anime.genre ?: "")
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }

        if (tags.isEmpty()) {
            // Genre not populated yet — pull the tag list from the details page.
            tags = runCatching {
                val request = animeDetailsRequest(anime)
                client.newCall(request).execute().use { response ->
                    Jsoup.parse(response.body.string())
                        .select("a[href*=/browse/tags/]")
                        .mapNotNull { it.text().trim().lowercase().ifBlank { null } }
                }
            }.getOrDefault(emptyList())
        }
        if (tags.isEmpty()) return emptyList()

        val currentSlug = anime.url.substringAfterLast("/")
        val currentBase = baseSlug(currentSlug)

        return runCatching {
            groupedCatalog(getCatalog())
                .filter { baseSlug(it.slug) != currentBase }
                .map { entry -> entry to entry.tags.count { it in tags } }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(24)
                .map { it.first.toSAnime() }
        }.getOrDefault(emptyList())
    }

    // ============================== Episodes ==============================
    // Episodes are parsed from the video's details page, which lists every
    // episode of the series via data-video-href attributes. This avoids the
    // multi-megabyte full-catalog download the search API needs (which can time
    // out on mobile, leaving the episode list empty).
    override fun episodeListRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
        return GET(url, headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body.string())
        val slug = response.request.url.toString()
            .substringBefore("?")
            .substringAfterLast("/")
        val base = baseSlug(slug)

        // Per-episode release dates from the cached search catalog. AniZen shows
        // each episode's date_upload in the list, so use the original release
        // date (released_at_unix) rather than the hanime upload date. The map is
        // only read from an already-cached catalog so this path never triggers
        // the multi-megabyte catalog download.
        val releaseDates = if (catalogFresh()) {
            runCatching { getCatalog().associate { it.slug to it.releasedAt } }
                .getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        val seen = HashSet<String>()
        val episodes = mutableListOf<SEpisode>()

        // The details page also lists "related videos" from other series, so only
        // keep slugs sharing the same base slug as the opened entry.
        document.select("[data-video-href*=/videos/hentai/]").forEach { element ->
            val epSlug = element.attr("data-video-href")
                .substringBefore("?")
                .substringAfterLast("/")
            if (epSlug.isBlank()) return@forEach
            if (baseSlug(epSlug) != base) return@forEach
            if (!seen.add(epSlug)) return@forEach

            val number = episodeNumber(epSlug)
            episodes.add(
                SEpisode.create().apply {
                    name = if (number > 0) "Episode $number" else epSlug
                    episode_number = number.toFloat().coerceAtLeast(1f)
                    url = "/videos/hentai/$epSlug"
                    // The API returns unix seconds; date_upload is epoch millis.
                    date_upload = (releaseDates[epSlug] ?: 0L) * 1000
                    // Episode cards carry that video's own cover image; action
                    // buttons (playlist/download/report) don't, so only real
                    // cards get a preview. AniZen's runtime SEpisode exposes it
                    // via preview_url (no episode-level thumbnail_url field).
                    element.selectFirst("img[src]")?.attr("src")
                        ?.takeIf { it.startsWith("http") }
                        ?.let { setEpisodeField(this, "preview_url", it) }
                },
            )
        }

        // Newest episode on top (4, 3, 2, 1), matching the convention used by
        // other anime extensions.
        episodes.sortByDescending { it.episode_number }

        return episodes.ifEmpty {
            val fallbackNumber = episodeNumber(slug)
            listOf(
                SEpisode.create().apply {
                    name = if (fallbackNumber > 0) "Episode $fallbackNumber" else "Episode 1"
                    episode_number = fallbackNumber.toFloat().coerceAtLeast(1f)
                    url = "/videos/hentai/$slug"
                    date_upload = (releaseDates[slug] ?: 0L) * 1000
                },
            )
        }
    }

    // ============================== Video Streams ==============================

    /**
     * Sets a field on SEpisode that exists in AniZen's runtime (preview_url,
     * summary) but not in the lib-14 stub this extension compiles against.
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

    private val handshakeUrl = "https://auth.hanime.tv/api/v11/handshake"
    private val handshakeSecret = "htv-insecure-handshake-v1"
    private val handshakeAad = "htv-insecure-v1"

    private fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP).replace("=", "")

    private fun base64UrlDecode(value: String): ByteArray {
        val padded = when (value.length % 4) {
            2 -> value + "=="
            3 -> value + "="
            else -> value
        }
        return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    /**
     * Encrypts the handshake payload the same way the site's player does:
     * AES-256-GCM with key = SHA-256(handshakeSecret), random 12-byte IV and
     * "htv-insecure-v1" as additional authenticated data. Result is a base64url
     * JSON envelope {v, alg, iv, tag, data}.
     */
    private fun encryptHandshake(payload: JSONObject): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sha256(handshakeSecret), "AES"),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(handshakeAad.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        val data = encrypted.copyOfRange(0, encrypted.size - 16)
        val tag = encrypted.copyOfRange(encrypted.size - 16, encrypted.size)
        val envelope = JSONObject().apply {
            put("v", 1)
            put("alg", "AES-256-GCM")
            put("iv", base64UrlEncode(iv))
            put("tag", base64UrlEncode(tag))
            put("data", base64UrlEncode(data))
        }
        return base64UrlEncode(envelope.toString().toByteArray(Charsets.UTF_8))
    }

    /** Decrypts the x-token response header produced by the handshake endpoint. */
    private fun decryptHandshake(token: String): String {
        val envelope = JSONObject(String(base64UrlDecode(token), Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sha256(handshakeSecret), "AES"),
            GCMParameterSpec(128, base64UrlDecode(envelope.getString("iv"))),
        )
        cipher.updateAAD(handshakeAad.toByteArray(Charsets.UTF_8))
        val data = base64UrlDecode(envelope.getString("data"))
        val tag = base64UrlDecode(envelope.getString("tag"))
        return String(cipher.doFinal(data + tag), Charsets.UTF_8)
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val url = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
        val slug = url.substringAfterLast("/").trim()

        val timestamp = System.currentTimeMillis() / 1000L
        val payload = JSONObject().apply {
            put("timestamp_unix", timestamp)
            put("directive", "htv_player_handshake")
            put("slug", slug)
        }

        val apiHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .set("Origin", "https://hanime.tv")
            .set("Referer", "$baseUrl/")
            .add("X-Signature-Version", "web2")
            .add("X-Signature", hex(sha256("$timestamp,Xkdi29,https://hanime.tv,mn2,$timestamp")))
            .add("X-Time", timestamp.toString())
            .build()

        val body = JSONObject().apply { put("token", encryptHandshake(payload)) }
            .toString()
            .toRequestBody("application/json".toMediaType())

        return POST(handshakeUrl, apiHeaders, body)
    }

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()

        // The stream sources are carried in the handshake response's x-token
        // header. Consume the small response body so the connection is reused.
        response.body.string()

        val xToken = response.header("x-token")
        if (!xToken.isNullOrBlank()) {
            try {
                val sources = JSONObject(decryptHandshake(xToken)).optJSONArray("sources")
                    ?: return videoList
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    val src = source.optString("src")
                    val kind = source.optString("kind")
                    // Skip preroll ads / promotions and empty sources
                    if (src.isBlank() || kind == "promotion") continue
                    val rawUrl = if (src.startsWith("http")) src else "$baseUrl$src"
                    // The handshake returns extension-less HLS URLs; append .m3u8 so
                    // aniyomi's player detects the stream as HLS (the server ignores
                    // the suffix and still serves the playlist).
                    val streamUrl = if (rawUrl.endsWith(".m3u8")) rawUrl else "$rawUrl.m3u8"
                    val height = source.optInt("height", 0)
                    val quality = if (height > 0) "${height}p" else source.optString("label", "HLS")
                    videoList.add(Video(streamUrl, quality, streamUrl, headers = streamHeaders()))
                }
            } catch (_: Exception) {
            }
        }

        return videoList.distinctBy { it.videoUrl }
    }

    override fun videoUrlRequest(video: Video): Request {
        val url = video.videoUrl?.ifBlank { baseUrl } ?: baseUrl
        return GET(url, headers)
    }

    override fun videoUrlParse(response: Response): String = response.request.url.toString()

    // ============================== Filter data ==============================
    companion object {
        private val STUDIOS = listOf(
            "Pink Pineapple", "MS Pictures", "PoRO", "Queen Bee",
            "Vanilla", "Bunnywalker", "Green Bunny", "Anime Antenna Iinkai",
            "Suzuki Mirano", "Mary Jane", "Magin Label", "Discovery",
            "nur", "MediaBank", "Collaboration Works", "NuTech Digital",
            "Five Ways", "New Generation", "Showten", "T-Rex",
            "Central Park Media", "Antechinus", "ChiChinoya", "SELFISH",
            "Edge", "Media Blasters", "Passione", "Milky",
            "Daiei", "Majin Petit", "Pashmina", "Arms",
            "ZIZ", "Pixy Soft", "Studio Houkiboshi", "Digital Works",
            "Lune Pictures", "Amour", "Schoolzone", "Studio 9 Maiami",
            "Lemon Heart", "Y.O.U.C.", "BOMB! CUTE! BOMB!", "Marigold",
            "Alpha Polis", "TNK", "X City", "Animac",
            "Soft on Demand", "Studio Gokumi", "Bootleg", "Comic Media",
            "Mousou Senka", "D3", "King Bee", "Jellyfish",
            "Torudaya", "White Bear", "Blue Eyes", "Obtain Future",
            "@ OZ", "Hot Bear", "Juicy Mango", "Knack",
            "Nihikime no Dozeu", "SoftCell", "Umemaro-3D", "Valkyria",
            "Hykobo", "Seven", "Studio FOW", "Chocolat",
            "evee", "Front Line", "Hoods Entertainment", "KENZsoft",
            "Magic Bus", "ROJIURA JACK", "Shadow Prod. Co.", "Shelf",
            "Studio LEO", "Suiseisha", "U-Jin", "Adult Source Media",
            "BugBug", "Celeb", "Cosmos", "Cranberry",
            "Echo", "J.C.", "Kitty Media", "L.",
            "Pix", "Rabbit Gate", "Studio Fantasia", "t japan",
            "TDK Core", "Toranoana", "Triple X", "AIC",
            "Bishop", "Crimson", "Fanza", "Friends Media Station",
            "Ivory Tower", "KoaLa", "MiMiA Cute", "Moon Rock",
            "Muse", "Pastel", "Pocomo Premium", "seismic",
            "Shinyusha", "ShoSai", "Studio Deen", "studio GGB",
            "Triangle", "Ajia-Do", "Almond Collective", "Ameliatie",
            "APPP", "BreakBottle", "ChuChu", "Circle Tribute",
            "CoCoans", "Comet", "demodemon", "Dollhouse",
            "EBIMARU-DO", "ECOLONUN", "Erozuki", "FINAL FUCK 7",
            "fruit", "Godoy", "gomasioken", "Groover",
            "IRONBELL", "Jewel", "Jumondo", "kate_sai",
            "Kuril", "Lilix", "Metro Notes", "N43",
            "Otodeli", "Peach Pie", "Pinkbell", "Project No.9",
            "sakamotoJ", "Sakura Purin", "SPEED", "STARGATE3D",
            "Studio Akai Shohosen", "Studio Zealot", "SurviveMore", "SYLD",
            "TOHO", "Trimax", "TYS Work", "Union Cho",
            "yosino", "Zyc",
        )

        private val GENRES = listOf(
            "3D", "Ahegao", "Anal", "BDSM", "Big Boobs", "Blow Job", "Boob Job", "Bondage",
            "Censored", "Comedy", "Cosplay", "Creampie", "Dark Skin", "Fantasy", "Facial",
            "Filmed", "Foot Job", "Futanari", "Gangbang", "Glasses", "Hand Job", "Harem",
            "HD", "Horror", "Incest", "Inflation", "Lactation", "Maid", "Masturbation",
            "Milf", "Mind Break", "Mind Control", "Monster", "Nekomimi", "NTR", "Nurse",
            "Orgy", "Plot", "POV", "Pregnant", "Public Sex", "Rimjob", "Scat", "School Girl",
            "Softcore", "Swimsuit", "Teacher", "Tentacle", "Threesome", "Toys", "Trap",
            "Tsundere", "Ugly Bastard", "Uncensored", "Vanilla", "Virgin", "Watersports",
            "X-Ray", "Yaoi", "Yuri",
        )

        private val YEAR_VALUES = arrayOf(
            "All",
            "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019", "2018",
            "2017", "2016", "2015", "2014", "2013", "2012", "2011", "2010", "2009",
            "2008", "2007", "2006", "2005", "2004", "2003", "2002", "2001", "2000",
            "1999", "1998", "1997", "1996", "1995", "1994", "1993", "1992", "1991",
            "1990", "1989", "1988", "1987", "1986",
        )

        private const val SORT_NEWEST = "Newest"
        private const val SORT_VIEWS = "Most Viewed"
        private const val SORT_LIKES = "Most Liked"
        private const val SORT_NAME_ASC = "A-Z"
        private const val SORT_NAME_DESC = "Z-A"
        private const val SORT_OLDEST = "Oldest"

        private val SORT_VALUES = arrayOf(
            SORT_NEWEST,
            SORT_VIEWS,
            SORT_LIKES,
            SORT_NAME_ASC,
            SORT_NAME_DESC,
            SORT_OLDEST,
        )
    }
}
