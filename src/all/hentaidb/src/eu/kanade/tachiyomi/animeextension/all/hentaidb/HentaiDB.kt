/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaidb

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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import rx.Observable
import java.io.IOException

// HentaiDB (https://hentaidb.xyz)
//
// The whole catalog is shipped as one JSON blob inside a hashed chunk loaded
// from the homepage (assigned to self.CAT). It holds the tag list, the studio
// list and one row per series, and each row carries slug, title, cover, year,
// episode count, type, status, censorship, popularity rank, resolution and the
// indices of its tags. Everything is therefore computed locally after a single
// catalog download, which is cached with a short TTL.
//
// Episodes live at /watch/<slug>~<n> and embed direct MP4 renditions under
// /v/<mirror>/... ; older entries use /ep<nn>-<quality>.mp4 instead of
// <quality>.mp4.
class HentaiDB : AnimeHttpSource() {

    override val name = "HentaiDB"

    override val baseUrl = "https://hentaidb.xyz"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("HentaiDB", "all", 1))
    override val id: Long = 4248040043938781823L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "$baseUrl/")

    // ============================== Catalog model ========================

    private data class Entry(
        val slug: String,
        val title: String,
        val cover: String?,
        val year: Int,
        val episodes: Int,
        val typeIndex: Int,
        val statusIndex: Int,
        val censored: Int,
        val quality: Int,
        val genres: List<Int>,
        val popularity: Int,
        val keywords: String,
        val studios: List<String>,
    )

    private data class Catalog(
        val entries: List<Entry>,
        val tags: List<String>,
    )

    @Volatile
    private var catalogCache: Catalog? = null

    @Volatile
    private var catalogFetchedAt: Long = 0L

    private val catalogLock = Any()

    private fun catalogFresh(): Boolean =
        catalogCache != null && System.currentTimeMillis() - catalogFetchedAt < CATALOG_TTL

    private fun getCatalog(): Catalog {
        synchronized(catalogLock) {
            if (catalogFresh()) return catalogCache!!
            val home = client.newCall(GET("$baseUrl/", headers)).execute()
                .use { it.body?.string().orEmpty() }
            val chunkPath = CHUNK_REGEX.find(home)?.groupValues?.get(1)
                ?: throw IOException("HentaiDB: catalog chunk not found")
            val js = client.newCall(GET("$baseUrl$chunkPath", headers)).execute()
                .use { it.body?.string().orEmpty() }
            val catalog = parseCatalog(js)
            catalogCache = catalog
            catalogFetchedAt = System.currentTimeMillis()
            return catalog
        }
    }

    private fun parseCatalog(js: String): Catalog {
        val root = JSONObject(extractCatalogObject(js))
        val tags = root.optJSONArray("tags").toStringList()
        val studios = root.optJSONArray("studios").toStringList()
        val rows = root.optJSONArray("rows") ?: throw IOException("HentaiDB: catalog rows not found")
        val entries = ArrayList<Entry>(rows.length())
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i) ?: continue
            val slug = o.optString("s")
            if (slug.isBlank()) continue
            entries.add(
                Entry(
                    slug = slug,
                    title = o.optString("t").ifBlank { slug },
                    cover = o.optString("a").takeIf { it.isNotBlank() }?.let { absolute(it) },
                    year = o.optInt("y"),
                    episodes = o.optInt("e", 1).coerceAtLeast(1),
                    typeIndex = o.optInt("ty"),
                    statusIndex = o.optInt("sa"),
                    censored = o.optInt("c"),
                    quality = o.optInt("q"),
                    genres = o.optJSONArray("g").toIntList(),
                    popularity = o.optInt("p"),
                    keywords = o.optString("k"),
                    studios = o.optInt("st", -1).let { idx ->
                        studios.getOrNull(idx)?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                            ?: emptyList()
                    },
                ),
            )
        }
        return Catalog(entries, tags)
    }

    /**
     * Pulls the JSON object assigned to `self.CAT=` out of the catalog chunk by
     * matching braces (the file is JS, so the object is not the whole file).
     */
    private fun extractCatalogObject(js: String): String {
        var start = js.indexOf(CATALOG_MARKER)
        start = if (start >= 0) js.indexOf('{', start + CATALOG_MARKER.length) else js.indexOf('{')
        if (start < 0) throw IOException("HentaiDB: catalog object not found")
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until js.length) {
            val c = js[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) return js.substring(start, i + 1)
                    }
                }
            }
        }
        throw IOException("HentaiDB: unterminated catalog object")
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = catalogRequest(page)

    override fun popularAnimeParse(response: Response): AnimesPage =
        pageOf(getCatalog().entries.byPopularity(), response.page())

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = catalogRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        pageOf(getCatalog().entries.byNewest(), response.page())

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        searchQuery = query
        searchFilters = filters
        return catalogRequest(page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val catalog = getCatalog()
        val filtered = applyFilters(catalog, searchQuery, searchFilters)
        return pageOf(filtered, response.page())
    }

    @Volatile
    private var searchQuery: String = ""

    @Volatile
    private var searchFilters: AnimeFilterList = AnimeFilterList()

    /**
     * The catalog is a single in-memory blob, so page 1 downloads it (via the
     * homepage) and later pages reuse the cache through a request whose body is
     * ignored — the same trick AniZen's pagination needs to keep working.
     */
    private fun catalogRequest(page: Int): Request =
        if (page == 1 && !catalogFresh()) {
            GET("$baseUrl/?page=$page", headers)
        } else {
            GET("$baseUrl/favicon.ico?page=$page", headers)
        }

    private fun applyFilters(catalog: Catalog, query: String, filters: AnimeFilterList): List<Entry> {
        var list = catalog.entries

        if (query.isNotBlank()) {
            // AniZen also sends titles as space-separated keywords, so match each
            // token independently; single-character tokens are dropped so a query
            // like "1 2" cannot match unrelated numbers.
            var tokens = query.lowercase().split(" ").filter { it.length > 1 }
            if (tokens.isEmpty()) tokens = listOf(query.lowercase())
            list = list.filter { entry ->
                val haystack = "${entry.title} ${entry.slug} ${entry.keywords}".lowercase()
                tokens.all { haystack.contains(it) }
            }
        }

        val flat = filters.flatMap { filter ->
            if (filter is AnimeFilter.Group<*>) {
                filter.state.filterIsInstance<AnimeFilter<*>>()
            } else {
                listOf(filter)
            }
        }

        // Genres are TriState: a tap includes, a second taps excludes.
        val genreFilters = flat.filterIsInstance<GenreFilter>()
        val includedGenres = genreFilters
            .filter { it.state == AnimeFilter.TriState.STATE_INCLUDE }
            .mapNotNull { catalog.tags.indexOf(it.name).takeIf { index -> index >= 0 } }
        val excludedGenres = genreFilters
            .filter { it.state == AnimeFilter.TriState.STATE_EXCLUDE }
            .mapNotNull { catalog.tags.indexOf(it.name).takeIf { index -> index >= 0 } }
        if (includedGenres.isNotEmpty() || excludedGenres.isNotEmpty()) {
            list = list.filter { entry ->
                includedGenres.all { entry.genres.contains(it) } &&
                    excludedGenres.none { entry.genres.contains(it) }
            }
        }

        val studioFilters = flat.filterIsInstance<StudioFilter>()
        val includedStudios = studioFilters
            .filter { it.state == AnimeFilter.TriState.STATE_INCLUDE }
            .map { it.name }
        val excludedStudios = studioFilters
            .filter { it.state == AnimeFilter.TriState.STATE_EXCLUDE }
            .map { it.name }
        if (includedStudios.isNotEmpty() || excludedStudios.isNotEmpty()) {
            list = list.filter { entry ->
                includedStudios.all { entry.studios.contains(it) } &&
                    excludedStudios.none { entry.studios.contains(it) }
            }
        }

        (flat.filterIsInstance<TypeFilter>().firstOrNull()?.state ?: 0).takeIf { it > 0 }?.let { t ->
            list = list.filter { it.typeIndex == t }
        }
        (flat.filterIsInstance<StatusFilter>().firstOrNull()?.state ?: 0).takeIf { it > 0 }?.let { s ->
            list = list.filter { it.statusIndex == s }
        }
        (flat.filterIsInstance<CensoredFilter>().firstOrNull()?.state ?: 0).takeIf { it > 0 }?.let { c ->
            list = list.filter { it.censored == c }
        }
        (flat.filterIsInstance<YearFilter>().firstOrNull()?.state ?: 0).takeIf { it > 0 }?.let { index ->
            YEAR_VALUES.getOrNull(index)?.toIntOrNull()?.let { year ->
                list = list.filter { it.year == year }
            }
        }

        val sort = SORT_VALUES.getOrNull(flat.filterIsInstance<SortFilter>().firstOrNull()?.state ?: 0)
        return when (sort) {
            SORT_TITLE_ASC -> list.sortedBy { it.title.lowercase() }
            SORT_TITLE_DESC -> list.sortedByDescending { it.title.lowercase() }
            SORT_YEAR_NEW -> list.byNewest()
            SORT_YEAR_OLD -> list.sortedWith(
                compareBy({ it.year <= 0 }, { it.year }, { it.title.lowercase() }),
            )
            SORT_EPISODES -> list.sortedWith(
                compareByDescending<Entry> { it.episodes }
                    .thenBy { if (it.popularity <= 0) Int.MAX_VALUE else it.popularity },
            )
            else -> list.byPopularity()
        }
    }

    /** The site ranks popularity with `p` (1 = most popular, 0 = unranked). */
    private fun List<Entry>.byPopularity(): List<Entry> = sortedWith(
        compareBy({ it.popularity <= 0 }, { it.popularity }, { it.title.lowercase() }),
    )

    /** The site has no "added at" stamp, so the newest listed year leads. */
    private fun List<Entry>.byNewest(): List<Entry> = sortedWith(
        compareBy(
            { it.year <= 0 },
            { -it.year },
            { it.popularity <= 0 },
            { it.popularity },
            { it.title.lowercase() },
        ),
    )

    private fun pageOf(entries: List<Entry>, page: Int): AnimesPage {
        val from = (page - 1) * PAGE_SIZE
        val animes = entries.drop(from).take(PAGE_SIZE).map { it.toSAnime() }
        return AnimesPage(animes, from + PAGE_SIZE < entries.size)
    }

    private fun Entry.toSAnime(): SAnime = SAnime.create().apply {
        title = this@toSAnime.title
        url = "/s/$slug"
        thumbnail_url = cover
    }

    // ============================== Details ===============================

    // AniZen's models need `url` on every returned SAnime (`url` is lateinit in
    // the app), so always carry the original URL across the details fetch.
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> =
        super.fetchAnimeDetails(anime).map { it.apply { url = anime.url } }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime =
        super.getAnimeDetails(anime).apply { url = anime.url }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(absolute(anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val slug = response.request.url.pathSegments.lastOrNull().orEmpty()
        val document = response.asJsoup()
        val catalog = runCatching { getCatalog() }.getOrNull()
        val entry = catalog?.entries?.firstOrNull { it.slug == slug }
        val genreNames = entry?.genres
            ?.mapNotNull { catalog?.tags?.getOrNull(it) }
            .orEmpty()
            .joinToString(", ")
        return SAnime.create().apply {
            url = "/s/$slug"
            title = entry?.title
                ?: document.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { slug }
            thumbnail_url = entry?.cover
            description = document.selectFirst("#syn")?.text()?.trim()?.takeIf { it.isNotBlank() }
            genre = genreNames.takeIf { it.isNotBlank() }
            author = entry?.studios?.takeIf { it.isNotEmpty() }?.joinToString(", ")
            status = when (entry?.statusIndex) {
                1 -> SAnime.COMPLETED
                2 -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(absolute(anime.url), headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val slug = response.request.url.pathSegments.lastOrNull().orEmpty()
        val document = response.asJsoup()

        // Every episode row carries its own thumbnail. Two shapes exist on the
        // site: /i/320/v/N/{slug}-{n}/cover.webp and /i/320/v/N/{slug}/ep{nn}.webp.
        val thumbnails = HashMap<Int, String>()
        val thumbPatterns = episodeThumbPatterns(slug)
        document.select("img").forEach { img ->
            val src = img.absUrl("src").ifBlank { img.absUrl("data-src") }
            if (src.isBlank()) return@forEach
            for (pattern in thumbPatterns) {
                val n = pattern.find(src)?.groupValues?.get(1)?.toIntOrNull()
                if (n != null) {
                    // Prefer the site's resized copy (/i/320/..) over the full
                    // size poster, which is listed first but is far heavier.
                    val existing = thumbnails[n]
                    if (existing == null || (!existing.contains("/i/") && src.contains("/i/"))) {
                        thumbnails[n] = src
                    }
                    break
                }
            }
        }

        // Only this series' own episode links count: recommendation cards can
        // point at other titles, and their episode numbers must not leak in.
        val episodeLink = Regex("""/watch/${Regex.escape(slug)}~(\d+)""")
        val numbers = sortedSetOf<Int>()
        document.select("a[href*=/watch/]").forEach { link ->
            episodeLink.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                ?.let { numbers.add(it) }
        }

        val entry = runCatching { getCatalog().entries.firstOrNull { it.slug == slug } }.getOrNull()
        if (numbers.isEmpty()) {
            val count = entry?.episodes ?: 0
            if (count <= 0) return emptyList()
            (1..count).forEach { numbers.add(it) }
        }

        return numbers.map { n ->
            SEpisode.create().apply {
                url = "/watch/$slug~$n"
                name = "Episode $n"
                episode_number = n.toFloat()
                (thumbnails[n] ?: entry?.cover)?.let { setEpisodeField(this, "preview_url", it) }
            }
        }
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request = GET(absolute(episode.url), headers)

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body?.string().orEmpty()
        if (body.isBlank()) throw IOException("HentaiDB: empty watch page")

        // The watch page lists every mirror of every rendition; keep the first
        // URL per quality so the player shows one entry per resolution.
        val byQuality = LinkedHashMap<String, String>()
        val unlabeled = LinkedHashSet<String>()
        MP4_REGEX.findAll(body).forEach { match ->
            val path = match.groupValues[1]
            val label = qualityLabel(path)
            if (label != null) {
                byQuality.putIfAbsent(label, path)
            } else {
                unlabeled.add(path)
            }
        }
        // Older entries only publish filenames without a resolution; use one of
        // those only when the page lists no labelled rendition at all.
        if (byQuality.isEmpty()) {
            unlabeled.firstOrNull()?.let { byQuality["MP4"] = it }
        }
        if (byQuality.isEmpty()) throw IOException("HentaiDB: no mp4 found")

        // Highest resolution first; at equal height the progressive copy beats
        // the interlaced one.
        return byQuality.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, String>> { qualityHeight(it.key) }
                    .thenBy { it.key.endsWith("i") },
            )
            .map { (label, path) ->
                Video("$baseUrl$path", label, "$baseUrl$path", headers = headers)
            }
    }

    /** "1080p" for 1080.mp4 / ep01-1080p.mp4, "1080i" for an interlaced copy. */
    private fun qualityLabel(path: String): String? {
        val match = QUALITY_REGEX.find(path.substringAfterLast("/")) ?: return null
        val height = match.groupValues[1]
        return if (match.groupValues[2].isEmpty()) "${height}p" else "${height}i"
    }

    private fun qualityHeight(label: String): Int =
        Regex("""\d+""").find(label)?.value?.toIntOrNull() ?: 0

    // ============================== Recommendations =======================
    // AniZen fills its "Recommended" section only when the source declares
    // `supportsRelatedAnimes` and implements `fetchRelatedAnimeList`. Those
    // members exist on AniZen's runtime API but not on the lib-14 stub this
    // extension compiles against, so they are declared without `override`.
    // Matching is done against the cached catalog, so no extra download.

    val supportsRelatedAnimes: Boolean get() = true

    suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val slug = anime.url.substringAfterLast("/")
        return runCatching {
            val entries = getCatalog().entries
            val current = entries.firstOrNull { it.slug == slug } ?: return@runCatching emptyList()
            val genres = current.genres.toHashSet()
            if (genres.isEmpty()) return@runCatching emptyList()
            entries.asSequence()
                .filter { it.slug != slug }
                .map { it to it.genres.count { g -> g in genres } }
                .filter { it.second > 0 }
                .sortedWith(
                    compareByDescending<Pair<Entry, Int>> { it.second }
                        .thenBy { if (it.first.popularity <= 0) Int.MAX_VALUE else it.first.popularity },
                )
                .take(RELATED_LIMIT)
                .map { it.first.toSAnime() }
                .toList()
        }.getOrDefault(emptyList())
    }

    // ============================== Filters ==============================

    private class FilterGroup(name: String, vararg filters: AnimeFilter<*>) :
        AnimeFilter.Group<AnimeFilter<*>>(name, filters.toList())

    private class GenreFilter(name: String) : AnimeFilter.TriState(name, AnimeFilter.TriState.STATE_IGNORE)

    private class StudioFilter(name: String) : AnimeFilter.TriState(name, AnimeFilter.TriState.STATE_IGNORE)

    private class TypeFilter : AnimeFilter.Select<String>("Type", TYPE_VALUES, 0)

    private class StatusFilter : AnimeFilter.Select<String>("Status", STATUS_VALUES, 0)

    private class CensoredFilter : AnimeFilter.Select<String>("Censorship", CENSOR_VALUES, 0)

    private class YearFilter : AnimeFilter.Select<String>("Released Year", YEAR_VALUES, 0)

    private class SortFilter : AnimeFilter.Select<String>("Sorting", SORT_VALUES, 0)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        // Groups render as collapsible sections in AniZen (closed by default);
        // a plain list of TriState filters would stay expanded.
        FilterGroup("Genres (include/exclude)", *TAGS.map { GenreFilter(it) }.toTypedArray()),
        FilterGroup("Studios (include/exclude)", *STUDIOS.map { StudioFilter(it) }.toTypedArray()),
        AnimeFilter.Header("Type"),
        TypeFilter(),
        AnimeFilter.Header("Status"),
        StatusFilter(),
        AnimeFilter.Header("Censorship"),
        CensoredFilter(),
        AnimeFilter.Header("Released Year"),
        YearFilter(),
        AnimeFilter.Header("Sorting"),
        SortFilter(),
    )

    // ============================== Helpers ===============================

    private fun Response.page(): Int =
        request.url.queryParameter("page")?.toIntOrNull() ?: 1

    /** Jsoup.parse without a base URI makes absUrl() return "", so pass it. */
    private fun Response.asJsoup(): Document = Jsoup.parse(body?.string().orEmpty(), request.url.toString())

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("/") -> "$baseUrl$url"
        else -> "$baseUrl/$url"
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private fun JSONArray?.toIntList(): List<Int> {
        if (this == null) return emptyList()
        return (0 until length()).map { optInt(it) }
    }

    /**
     * Sets a field on SEpisode that exists in AniZen's runtime (preview_url,
     * summary) but not in the lib-14 stub this extension compiles against.
     */
    private fun episodeThumbPatterns(slug: String): List<Regex> {
        val escaped = Regex.escape(slug)
        return listOf(
            Regex("""$escaped-(\d+)/(?:cover|poster)\.(?:webp|jpe?g|png)""", RegexOption.IGNORE_CASE),
            Regex("""$escaped/ep(\d+)\.(?:webp|jpe?g|png)""", RegexOption.IGNORE_CASE),
        )
    }

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
        private const val PAGE_SIZE = 24
        private const val RELATED_LIMIT = 24
        private const val CATALOG_TTL = 10 * 60 * 1000L
        private const val CATALOG_MARKER = "self.CAT="

        private val CHUNK_REGEX = Regex("""src="(/cat-[^"]+\.js)"""")
        private val MP4_REGEX = Regex("""(/v/\d+/[^"'\s)]+?\.mp4)""")
        private val QUALITY_REGEX = Regex("""(\d{3,4})(i?)p?\.mp4$""", RegexOption.IGNORE_CASE)

        private const val SORT_TITLE_ASC = "Title (A-Z)"
        private const val SORT_TITLE_DESC = "Title (Z-A)"
        private const val SORT_YEAR_NEW = "Year (newest)"
        private const val SORT_YEAR_OLD = "Year (oldest)"
        private const val SORT_EPISODES = "Episodes (most)"

        private val TYPE_VALUES = arrayOf("All", "OVA", "Movie", "TV Series", "Web", "Other")
        private val STATUS_VALUES = arrayOf("All", "Completed", "Ongoing", "Stalled")

        // The site stores `c:1` for uncensored and `c:2` for censored.
        private val CENSOR_VALUES = arrayOf("All", "Uncensored", "Censored")

        private val YEAR_VALUES = arrayOf("All") +
            (2026 downTo 1990).map { it.toString() }.toTypedArray()

        private val SORT_VALUES = arrayOf(
            "Popularity",
            SORT_TITLE_ASC,
            SORT_TITLE_DESC,
            SORT_YEAR_NEW,
            SORT_YEAR_OLD,
            SORT_EPISODES,
        )

        // Tag and studio names come from the site's own catalog (indices are
        // resolved by name at filter time, so re-ordering upstream is safe).
        private val TAGS = arrayOf(

            "Blow Job", "Big Boobs", "Creampie", "Sex",
            "Nudity", "School Girl", "Rape", "Anal",
            "Masturbation", "Boob Job", "Virgin", "Public Sex",
            "Hand Job", "Harem", "Fellatio", "Facial",
            "Plot", "Bondage", "Large Breasts", "Toys",
            "Gangbang", "Erotic Game", "School", "Ahegao",
            "Yuri", "Fantasy", "Threesome", "Cunnilingus",
            "Present", "Game", "Vanilla", "BDSM",
            "Japan", "Deflowering", "Female Student", "Incest",
            "Doggy Style", "Earth", "Asia", "Glasses",
            "X Ray", "Romance", "Mammary Intercourse", "Cosplay",
            "Double Penetration", "Urination", "Dildos & Vibrators", "Manga",
            "Monster", "Internal Shots", "Comedy", "Bukkake",
            "Tentacle", "Teacher", "Milf", "Maid",
            "NTR", "Swimsuit", "Outdoor Sex", "Tits Fuck",
            "Deepthroat", "Loli", "Demons", "Orgy",
            "Filmed", "Visual Novel", "Femdom", "Watersports",
            "Sixty Nine", "Lactation", "Enjoyable Rape", "Violence",
            "Blackmail", "Throat Fucking", "Dark Skin", "Female Teacher",
            "POV", "Horny Slut", "School Life", "Mind Break",
            "Plot Continuity", "Housewives", "Foot Job", "Nurse",
            "Futanari", "Horror", "Oral", "Small Breasts",
            "Supernatural", "Voyeurism", "Half Length Episodes", "Erotic Torture",
            "Squirting", "Netorare", "Ugly Bastard", "Cum Play",
            "Rimming", "Action", "Gang Rape", "Sex Toys",
            "Mind Control", "FFM Threesome", "Cute And Funny", "Inflation",
            "Office Lady", "Rimjob", "Sex Tape", "Pregnant",
            "Brother Sister Incest", "Double Fellatio", "Prostitution", "Strapon",
            "Tsundere", "Gokkun", "Enema", "Gigantic Breasts",
            "Submission", "Foot Fetish", "Internal Cumshot", "Hardcore",
            "Shota", "Scat", "Impregnation", "Elf",
            "Past", "Plot With Porn", "Shibari", "Reverse Rape",
            "Foursome", "Dubbed", "Facesitting", "Vanilla Series",
            "Magic", "Stockings", "Window Fuck", "Gyaru",
            "Safer Sex", "Super Power", "Dark Skinned Girl", "Whip",
            "Male Rape Victim", "Triple Penetration", "Water Sex", "3D",
            "High School", "Fictional Location", "Pussy Sandwich", "Teacher X Student",
            "Exhibitionism", "Stomach Stretch", "Futa X Female", "Hidden Vibrator",
            "Megane", "Threesome With Sisters", "Gore", "Female Rapes Female",
            "French Kiss", "Science Fiction", "Succubus", "Scissoring",
            "Nekomimi", "Nun", "Lingerie", "Stomach Bulge",
            "Tragedy", "MMF Threesome", "Magical Girl", "Dominatrix",
            "Murder", "Princess", "Shimapan", "Slapstick",
            "Master Servant Relationship", "Waitress", "Double Sided Dildo", "Infidelity",
            "Pregnant Sex", "Bestiality", "Cosplaying", "Love Polygon",
            "Netori", "Pantyjob", "Trap", "Whipping",
            "Girl Rapes Girl", "Sex While On The Phone", "Swordplay", "Train Molestation",
            "Cross Dressing", "Doctor", "Japanese Production", "Point Of View",
            "Revenge", "Sci Fi", "Golden Shower", "Cervix Penetration",
            "Slavery", "Uniform Fetish", "Spanking", "Yaoi",
            "Brainwashing", "Novel", "Long Episodes", "Oyakodon",
            "Juujin", "Slaves", "Torture", "Angst",
            "Prostate Massage", "Sister Sister Incest", "Crime", "Fisting",
            "Molestation", "Sexual Fantasies", "Slide Show Animation", "Adventure",
            "Female Doctor", "Urophagia", "Brainwashed", "Drugs",
            "Martial Arts", "Parody", "Softcore", "Aphrodisiac",
            "Future", "Sports", "Android", "Dark Fantasy",
            "Father Daughter Incest", "Henshin", "Hospital", "Vampire",
            "Betrayal", "Dungeon", "Mother Son Incest", "Detective",
            "Law And Order", "Mecha", "Mother Daughter Incest", "Breast Fondling",
            "Corrupt Nobility", "Idol", "Strappado Bondage", "Alcohol",
            "Alien", "Angel", "Ninja", "Super Deformed",
            "Cat Girl", "Drama", "Ecchi", "Hentai",
            "Horny Nosebleed", "Big Breasts", "Corrupt Church", "Historical",
            "Humiliation", "Music", "Psychological Manipulation", "School Clubs",
            "Strong Female Lead", "All Girls School", "Everybody Has Sex", "Futa X Male",
            "Gunfights", "Mafia", "Nipple Penetration", "Paizuri",
            "Parental Abandonment", "Psychoactive Drugs", "Thigh Sex", "Under One Roof",
            "CG Collection", "Daily Life", "Futa X Futa", "Girly Tears",
            "Pegging", "Predominantly Female Cast", "Skimpy Clothing", "Space",
            "Suicide", "Contemporary Fantasy", "Impregnation With Larvae", "Kidnapping",
            "Mechanical Tentacle", "Nostril Hook", "Paper Clothes", "Short",
            "Tennis", "The Arts", "Age Difference Romance", "Body Takeover",
            "Branching Story", "Breast Expansion", "Bullying", "Cum Swapping",
            "Female Protagonist", "Ghost", "Intercrural Sex", "Magic Circles",
            "Military", "Shipboard", "Strappado", "Summoning",
            "Animal Ears", "Calling Your Attacks", "Huge Breasts", "Multi Segment Episodes",
            "Parallel World", "Stereotypes", "Student Government", "Summer",
            "Wooden Horse", "Anal Fingering", "Anal Pissing", "Coming Of Age",
            "Excessive Censoring", "Human Sacrifice", "Mutilation", "Nyotaimori",
            "Police", "Sleeping Sex", "Time Travel", "Twincest",
            "Unrequited Love", "Alternative Present", "Conspiracy", "Countryside",
            "Dark Elf", "Deity", "Dragon", "Europe",
            "Furry", "Gender Bender", "Injuu Hentai Series", "Male Protagonist",
            "Medium Awareness", "Reverse Spitroast", "RPG", "Sudden Girlfriend Appearance",
            "Swimming", "Amnesia", "Big Tits", "Friendship",
            "High Fantasy", "Human Enhancement", "Humanoid Alien", "Island",
            "Jealousy", "Master Slave Relation", "Onahole", "Pillory",
            "Piloted Robot", "Special Squads", "Sumata", "Unintentional Comedy",
            "Urethra Penetration", "Visible Aura", "2000-Year-Old Dragon Girl", "Baseball",
            "CGI", "Cheating", "Condom", "Cops",
            "Corruption", "Cyborg", "Delinquent", "Episodic",
            "Exorcism", "Genetic Modification", "Grandiose Displays Of Wealth", "Guro",
            "Massacre", "Necrophilia", "Photography", "Shoutacon",
            "Space Travel", "Thriller", "Uncle Niece Incest", "Alternative Past",
            "Black Humour", "Borderline Porn", "Cybersex", "Damsel In Distress",
            "Dementia", "Disturbing", "Drastic Change Of Life", "Eating Of Humans",
            "First Love", "Heroic Sacrifice", "Important Haircut", "Live Action Imagery",
            "Mind Fuck", "Orc", "Other Planet", "Parricide",
            "Photographic Backgrounds", "Red Light District", "Rotten World", "Short Episodes",
            "Small Boobs", "Trapped", "United States", "Violent Retribution For Accidental Infringement",
            "Volleyball", "Wax Play", "Anthropomorphism", "Body Exchange",
            "Enjo Kousai", "Erotic Asphyxiation", "Forbidden Love", "Gainax Bounce",
            "Glory Hole", "Gymnastics", "Human Cannibalism", "Immortality",
            "Magic Weapons", "Murder Of Family Members", "Narration", "Navy",
            "Open Ended", "Pantsu", "Poverty", "Psychological",
            "Recycled Animation", "Samurai", "Soapland", "South Korean Production",
            "Sudden Naked Girl Appearance", "Tokugawa Period", "Tokyo", "Training",
            "Undead", "Virtual World", "Wakamezake", "Zombie",
            "Adapted Into Japanese Movie", "Animal Abuse", "Animerama", "Ass Kicking Girls",
            "Assjob", "Association Football", "Aunt Nephew Incest", "Autofellatio",
            "Bishoujo", "Bishounen", "Boxing", "Boy Meets Girl",
            "Call My Name", "Collateral Damage", "Competition", "Curse",
            "Demonic Power", "Disaster", "Discontinued", "Dreams And Reality",
            "Dystopia", "Eroge", "Evil Military", "Eye Penetration",
            "Facial Distortion", "Felching", "Funny Expressions", "Germany",
            "Hostage Situation", "Killing Criminals", "Library", "Misunderstanding",
            "Mother And Son", "Multiple Couples", "Not For Kids", "Omnibus Format",
            "Parasite", "Performance", "Power Suit", "Predominantly Male Cast",
            "Reincarnation", "Reverse Trap", "Rivalry", "Sex Change",
            "Shoujo Ai", "Some Weird Shit Goin` On", "Stand Alone Movie", "Strong Male Lead",
            "The Power Of Love", "Thievery", "University", "Widow",
            "Youji Play", "3D CG Animation", "Absurdist Humour", "Air Force",
            "Americas", "Archery", "Basketball", "Be Careful What You Wish For",
            "Board Games", "Catholic School", "Christianity", "Classical Music",
            "Cockring", "Combat", "Contraband", "Cooking",
            "Cyberpunk", "Demon Hunt", "Despair", "Earthquake",
            "Engrish", "Everybody Dies", "Experimental Animation", "Foreskin Sex",
            "Greek Mythology", "Group Sex", "Happy Ending", "Hell",
            "Hong Kong", "Human Android Love", "Human Experimentation", "Improbable Physics",
            "Inter Dimensional Schoolgirl", "Isekai", "Japanese Mythology", "Journey To The West",
            "Main Character Dies", "Manipulation", "Merchandising Show", "Middle East",
            "Middle School", "MMM Threesome", "Monster Of The Week", "Musical Band",
            "Mystery", "Navel Fuck", "Nearly Almighty Protagonist", "Nurse Office",
            "Off Model Animation", "Older Female Younger Male", "Onmyoudou", "Orgasm Denial",
            "Otaku Culture", "Pirate", "Plot Twists", "Police Are Useless",
            "Post Apocalyptic", "Real World Location", "Religion", "Restaurant",
            "Robot", "School Dormitory", "Short Story Collection", "Shounen Ai",
            "Spellcasting", "Spirits", "Spiritual Powers", "Spitroast",
            "Wardrobe Malfunction", "Winter", "Wrestling", "Action Game",
            "Adapted Into JDrama", "Adults Are Useless", "Akihabara", "Alien Invasion",
            "All Boys School", "Alternating Animation Style", "Animal Protagonist", "Animation",
            "Attempted Rape", "Autumn", "Bad Cooking", "Bakumatsu - Meiji Period",
            "Bitter Sweet", "Buddhism", "Car Crash", "Castaway",
            "Child Abuse", "China", "Colour Coded", "ComicFesta Anime Zone",
            "Cram School", "Dancing", "Dark", "Dark Atmosphere",
            "Defeat Means Friendship", "Desert", "Divorce", "Doujin",
            "Dreams", "Dutch Wife", "Dysfunctional Family", "Egypt",
            "Emotions Awaken Superpowers", "European Stylised", "Extrasensory Perception", "Faceless Background Characters",
            "Fairy", "Fake Relationship", "Family", "Family Without Mother",
            "Feminism", "Feudal Warfare", "Fighting", "Fingering",
            "Fire", "First Kiss", "Fishing", "Footage Reuse",
            "France", "Gangs", "Ghost Hunting", "Giant Insects",
            "Go", "Goblin", "God Is A Girl", "Group Love",
            "Heaven", "Hidden Agenda", "Hyperspace Mallet", "I Got A Crush On You",
            "Ice Skating", "Imperial Stormtrooper Marksmanship Academy", "In Medias Res", "India",
            "Just As Planned", "Kamikaze", "Kendo", "Korea",
            "Light Hearted", "Love At First Sight", "Love Between Enemies", "Macabre",
            "Medieval", "Mermaid", "Military Is Useless", "Movie",
            "Mutation", "Mythology", "Nagasaki", "Nervous Breakdown",
            "Non Linear", "Norse Mythology", "Occult", "Ocean",
            "One Thousand And One Nights", "Out Of Body Experience", "Painting", "Power Corrupts",
            "Predominantly Adult Cast", "Prison", "Promise", "Proxy Battles",
            "Rebellion", "Reverse Harem", "Rugby", "Running Gag",
            "Sakura", "School For The Rich Elite", "Secret Anima", "Secret Anima Series",
            "Seiyuu", "Self Parody", "Sex Doll", "Shinjuku",
            "Shinsengumi", "Short Movie", "Sibling Rivalry", "Sibling Yin Yang",
            "Slow When It Comes To Love", "Slums", "Small Tits", "Social Class Issues",
            "Social Commentary", "Softball", "Space Pirates", "Spacing Out",
            "Spring", "Strap On Dildo", "Surreal", "Survival",
            "Table Tennis", "Tank Warfare", "Teasing", "Three Kingdoms",
            "Time Loop", "Tournament", "Track And Field", "Tragic Beginning",
            "Transforming Craft", "Transforming Weapons", "Tsunami", "Ukiyo E",
            "Unexpected Inheritance", "Unrequited Shounen Ai", "Video Game Development", "Watercolour Style",
            "Weekly Shounen Jump", "Working Life", "World Domination", "World War II",
            "Yokohama", "Zero To Hero",
        )

        private val STUDIOS = arrayOf(

            "Pink Pineapple", "Suiseisha", "Agent 21, J.C.Staff, Toei Video", "BugBug",
            "King Bee", "Milky Animation Label", "NuTech Digital", "Comic Media",
            "MS Pictures", "Moonseong Animation", "nur", "@ OZ",
            "Vanilla", "Green Bunny", "Discovery", "Mary Jane",
            "demodemon", "Milky, NuTech Digital", "Orada Company, Pink Pineapple, PP Project", "Queen Bee",
            "Antechinus", "Frontier Works", "ChiChinoya", "T-Rex",
            "Bunnywalker", "Magic Bus", "Pashmina", "White Bear",
            "New Generation", "PoRO", "Anime Antenna Iinkai", "Studio 9 Maiami",
            "JapanAnime", "Digital Works", "Blue Eyes", "Moon Rock",
            "Circle Tribute", "Flavors Soft, SugarBoy", "Flavors Soft, SOD Create, SugarBoy", "Goldenboy, SugarBoy, zyc",
            "Y.O.U.C.", "Showten", "Kuril", "MediaBank",
            "Diomedéa", "INTERFACEDOGS", "Schoolzone", "Five Ways",
            "Collaboration Works", "Media Blasters", "Hot Bear", "Studio G7",
            "Active", "Marigold", "Office AO", "Shinkuukan",
            "Pumpkin Pie", "X City", "Bandai", "GP Museum Soft, Image House, Milky",
            "Blue Cat", "Blue Cat, NATURAL HIGH, SugarBoy", "Kitty Film", "ZIZ",
            "Jumondo", "Knack", "studio GGB", "Edge",
            "AniMan, MS Pictures", "Suzuki Mirano", "Studio Kyuuma", "Blue Cat, Milky, MS Pictures",
            "Torudaya", "Lune Pictures", "Soft on Demand", "SELFISH",
            "Studio Sign", "Animac", "Shinyusha", "Mushi Production",
            "Succubus", "gomasioken", "Mousou Senka", "Toranoana",
            "APPP, AIC, Fairy Dust", "Rabbit Gate", "Adult Source Media", "Green Bunny, Shinkuukan",
            "Echo", "Bootleg", "Seven", "Comet",
            "Phoenix Entertainment", "Zexcs", "D3", "PoRO petit",
            "Studio Kikan, Studio Marine", "Kitty Media", "Obtain Future", "Arms",
            "sakamotoJ", "Pink Pineapple, Triple X", "Umemaro-3D", "Elf, Just, Polystar",
            "Trimax", "Ripple Film", "fruit", "ShoSai",
            "Pixy", "AIC", "Himajin, Pink Pineapple", "Majin Petit",
            "Langmaor", "Flavors Soft, Pink Pineapple", "Alpha Polis", "Magin Label",
            "G-lam, Pink Pineapple", "SYLD", "Sakura Purin", "Office Takeout, Pink Pineapple",
            "Digital Works, Pashmina Ace, Studio 1st", "Studio FOW", "t japan", "APPP",
            "Fanza", "Groover", "SoftCell", "AT-2 Project, Discovery",
            "Studio Signal", "Pixy Soft", "Collaboration Works petit", "Blue bread",
            "Trinet Entertainment", "Toei Video", "Lemon Heart", "Chaos Project, Pink Pineapple",
            "Majin", "Amour", "Muse", "Bishop",
            "Studio Dolphin Night", "Ameliatie", "FINAL FUCK 7", "Peach Pie",
            "Crimson", "Union Cho", "Grouper Productions, OB Planning, Toho", "Prime Time",
            "Juicy Mango", "STARGATE3D", "Moonstone Cherry", "AniMan, MS Pictures, Studio 9 Maiami",
            "Bootleg, MS Pictures", "Momoi Planning", "Daiei", "Central Park Media",
            "Marigold, Schoolzone", "Himajin Planning", "Shion", "Passione",
            "Knack Productions", "Studio Hibari", "Digital Works, Y.O.U.C.", "Studio Soul",
            "Pix", "Studio Houkiboshi", "Image House", "Public & Basic, Ripple Film, TDK Core",
            "Studio Fantasia", "Madhouse", "Museum Pictures, Ripple Film", "Pink Pineapple, Studio 1st",
            "Hoods Entertainment", "Shadow Prod. Co.", "TNK", "Studio LEO",
            "Flavors Soft", "J.C.Staff", "Milky", "Dynamic Planning, Nippon Columbia",
            "Rojiura Jack", "Fuji TV", "Asahi Production", "GAGA",
            "BOMB! CUTE! BOMB!", "Nihikime no Dozeu", "U-Jin", "An DerCen",
            "Hokiboshi", "Pocomo Premium", "Juicy Mango, T-Rex", "Valkyria",
            "Toho", "CherryLips", "Studio Kelmadick", "Seismic",
            "Nikkatsu Video", "Nikkatsu", "evee", "Orada Company",
            "Cranberry", "ECOLONUN", "Triangle", "Beam Entertainment",
            "Angelfish, Lune Pictures, Schoolzone", "Teatro Nishi Tokyo Studio, ZIZ", "TDK Core", "J.C.",
            "Godoyg", "Gold Bear", "Bootleg, MS Pictures, T-Rex", "IRONBELL",
            "Poly Animation", "Jam", "DThree, MediaBank", "CoCoans",
            "Dollhouse", "KoaLa", "yosino", "Bunnywalker, T-Rex",
            "Manglobe", "Atorie A.B.C.", "Pinkbell", "TYS Work",
            "Animax", "Shouten", "Collaboration Works, TY Network", "KADOKAWA",
            "AC Create", "Ark", "Chocolat", "Studio AWAKE",
            "Magic Bus, Picante Circus, Suiseisha", "L.", "Celeb", "Pastel",
            "Otodeli", "Exnoa", "Cosmos", "kate_sai",
            "Front Line", "Shelf", "MediaBank, Showten", "Friends Media Station",
            "PP Project", "Ivory Tower", "Sonsan Kikaku", "Avex Entertainment",
            "EBIMARU-DO", "ALL PRODUCTS, J.C.Staff", "SPEED", "Seven Arcs",
            "Pink Pineapple, Seven", "KENZsoft", "Ajia-Do", "MiMiA Cute",
            "JCF", "Godoy", "Studio Max", "Media Station, Studio Max",
            "Production D.M.H", "Studio Gokumi", "Jewel", "ChuChu",
            "37c-Binetsu", "BreakBottle", "Studio Akai Shohosen", "J.C.Staff, Jam Creation",
            "Sandwichworks", "Hykobo", "Shin-Ei Animation", "Melissa",
            "N43", "Erozuki", "Chaos Project", "Metro Notes",
            "Tsubo Production", "AIC, Green Bunny", "At 2", "Toei Animation",
            "Anime Antenna Iinkai, MediaBank", "Studio Zealot",
        )
    }
}
