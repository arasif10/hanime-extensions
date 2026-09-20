/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaverse

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
import rx.Observable
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Hentaverse (https://hentaverse.com)
 *
 * Next.js frontend backed by a public JSON API at apiv2.hentaverse.com:
 *  - GET /api/v1/content/series?page=N       -> paged series list (20/page, ~10 pages)
 *  - GET /api/v1/content/series/SLUG         -> series + videos[] (episodes)
 *  - GET /api/v1/content/search?q=QUERY      -> { videos, categories, series, users }
 *
 * Episode videos live on the CDN as MP4 renditions:
 *   https://cdn.hentaverse.com/{videoPath}/{quality}.mp4
 * where videoPath ends in /renditions and quality is one of 1080p/720p/480p/360p.
 * Thumbnails: https://cdn.hentaverse.com/{episode.thumbnail} (webp).
 */
class Hentaverse : AnimeHttpSource() {

    override val name = "Hentaverse"

    override val baseUrl = "https://hentaverse.com"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("Hentaverse", "all", 1))
    override val id: Long = 5675634213405246461L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    private fun apiRequest(path: String): Request = GET("$API_BASE$path", headers)

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = apiRequest("/series?page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage = seriesPage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = apiRequest("/series?page=$page")

    override fun latestUpdatesParse(response: Response): AnimesPage = seriesPage(response)

    // ============================== Search ================================

    // Multi-select merges one request per checked category, so the parse step
    // needs to know which ones were checked and which page it is on.
    private var lastFilters: AnimeFilterList? = null
    private var lastPage: Int = 1

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        lastFilters = filters
        lastPage = page
        if (query.isNotBlank()) {
            return apiRequest("/search?q=${URLEncoder.encode(query, "UTF-8")}")
        }
        val slugs = selectedCategories(filters)
        if (slugs.isEmpty()) {
            val sort = sortSlug(filters)
            val suffix = if (sort.isEmpty()) "" else "&sort=$sort"
            return apiRequest("/series?page=$page$suffix")
        }
        // Only the first category is fetched here; the rest are merged in
        // searchAnimeParse, because a single request can carry one response.
        return apiRequest("/categories/${slugs.first()}/series?page=$page")
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.toString().contains("/search?")) return searchResultsPage(response)
        val first = seriesPage(response)
        val rest = selectedCategories(lastFilters).drop(1)
        if (rest.isEmpty()) return first
        // Multi-select is OR: fetch each remaining category and merge by slug.
        val merged = LinkedHashMap<String, SAnime>()
        first.animes.forEach { merged[it.url] = it }
        var hasMore = first.hasNextPage
        rest.forEach { slug ->
            client.newCall(apiRequest("/categories/$slug/series?page=$lastPage")).execute().use { resp ->
                val page = seriesPage(resp)
                page.animes.forEach { merged.putIfAbsent(it.url, it) }
                hasMore = hasMore || page.hasNextPage
            }
        }
        return AnimesPage(merged.values.toList(), hasMore)
    }

    /** Search answers with individual videos, so group them by series slug. */
    private fun searchResultsPage(response: Response): AnimesPage {
        val body = response.body?.string().orEmpty()
        if (body.isBlank()) return AnimesPage(emptyList(), false)
        val json = JSONObject(body).optJSONObject("data")?.optJSONObject("results")
        // Search returns individual videos; group them by their series slug.
        val series = json?.optJSONArray("series")
        val animes: List<SAnime> = series?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val slug = obj.optString("slug").ifBlank { return@mapNotNull null }
                SAnime.create().apply {
                    this.url = slug
                    title = obj.optString("name").ifBlank { slug }
                    thumbnail_url = obj.optString("image").takeIf { it.isNotBlank() }?.let { cdn(it) }
                }
            }
        } ?: emptyList()
        return AnimesPage(animes, false)
    }

    // ============================== Catalogue parsing =====================

    private fun seriesPage(response: Response): AnimesPage {
        val body = response.body?.string().orEmpty()
        if (body.isBlank()) return AnimesPage(emptyList(), false)
        val root = JSONObject(body)
        val items = root.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
        val animes = (0 until items.length()).mapNotNull { i ->
            val obj = items.optJSONObject(i) ?: return@mapNotNull null
            val slug = obj.optString("slug").ifBlank { return@mapNotNull null }
            SAnime.create().apply {
                title = obj.optString("name").ifBlank { slug }
                url = slug
                thumbnail_url = obj.optString("image").takeIf { it.isNotBlank() }?.let { cdn(it) }
                description = obj.optString("description").ifBlank { null }
                initialized = true
            }
        }
        // /categories/{slug}/series reports real pagination; /series?page=N does not.
        val hasMore = root.optJSONObject("pagination")?.optBoolean("hasMore") ?: animes.isNotEmpty()
        return AnimesPage(animes, hasMore)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryGroup(),
        AnimeFilter.Header("Sorting"),
        SortFilter(),
    )

    // The lib's AnimeFilter.CheckBox is abstract, so a concrete subclass is
    // required (same pattern as the other extensions).
    private class CategoryCheckBox(name: String, state: Boolean = false) :
        AnimeFilter.CheckBox(name, state)

    private class CategoryGroup : AnimeFilter.Group<AnimeFilter.CheckBox>(
        "Categories",
        CATEGORY_NAMES.map { CategoryCheckBox(it) },
    )

    private class SortFilter : AnimeFilter.Select<String>("Sort by", SORT_NAMES, 0)

    private fun flatFilters(filters: AnimeFilterList?): List<AnimeFilter<*>> = filters.orEmpty().flatMap { filter ->
        if (filter is AnimeFilter.Group<*>) {
            filter.state.filterIsInstance<AnimeFilter<*>>()
        } else {
            listOf(filter)
        }
    }

    private fun selectedCategories(filters: AnimeFilterList?): List<String> =
        flatFilters(filters).filterIsInstance<AnimeFilter.CheckBox>()
            .filter { it.state }
            .mapNotNull { box -> CATEGORY_NAMES.indexOf(box.name).takeIf { it >= 0 } }
            .map { CATEGORY_SLUGS[it] }

    private fun sortSlug(filters: AnimeFilterList): String {
        val index = flatFilters(filters).filterIsInstance<SortFilter>().firstOrNull()?.state ?: 0
        return SORT_SLUGS.getOrNull(index) ?: ""
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        apiRequest("/series/${anime.url}")

    override fun animeDetailsParse(response: Response): SAnime {
        val body = response.body?.string().orEmpty()
        val data = JSONObject(body).optJSONObject("data") ?: JSONObject()
        return SAnime.create().apply {
            title = data.optString("name")
            url = data.optString("slug")
            thumbnail_url = data.optString("image").takeIf { it.isNotBlank() }?.let { cdn(it) }
            description = buildString {
                data.optString("description").takeIf { it.isNotBlank() }?.let(::append)
                val cats = data.optJSONArray("categories")
                if (cats != null && cats.length() > 0) {
                    if (isNotEmpty()) append("\n\n")
                    append("Genres: ")
                    append(
                        (0 until cats.length()).mapNotNull { i ->
                            (cats.opt(i) as? JSONObject)?.optString("name")?.ifBlank { null }
                        }.joinToString(", "),
                    )
                }
            }.ifBlank { null }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        apiRequest("/series/${anime.url}")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val body = response.body?.string().orEmpty()
        val data = JSONObject(body).optJSONObject("data") ?: return emptyList()
        val videos = data.optJSONArray("videos") ?: JSONArray()
        return (0 until videos.length()).mapNotNull { i ->
            val ep = videos.optJSONObject(i) ?: return@mapNotNull null
            val number = ep.optInt("episode", 0)
            if (number <= 0) return@mapNotNull null
            // Store the rendition path directly so playback needs no extra API call.
            val videoPath = ep.optString("videoPath").trimEnd('/')
            if (videoPath.isBlank()) return@mapNotNull null
            SEpisode.create().apply {
                this.url = videoPath
                name = ep.optString("title").ifBlank { "Episode $number" }
                episode_number = number.toFloat()
                // Each video carries its own createdAt (ISO-8601 UTC).
                date_upload = parseDate(ep.optString("createdAt"))
                ep.optString("thumbnail").takeIf { it.isNotBlank() }?.let {
                    setEpisodeField(this, "preview_url", cdn(it))
                }
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================== Video =================================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        // episode.url = "uploads/videos/{uuid}/renditions"
        val base = cdn(episode.url)
        val videos = QUALITIES.mapNotNull { (q, label) ->
            val url = "$base/$q.mp4"
            try {
                val req = okhttp3.Request.Builder().url(url)
                    .headers(headers.newBuilder().add("Range", "bytes=0-16").build())
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) Video(url, label, url, headers = headers) else null
                }
            } catch (_: Exception) {
                null
            }
        }
        if (videos.isEmpty()) throw IOException("Hentaverse: no playable renditions")
        return Observable.just(videos)
    }

    // ============================== Helpers ===============================

    private fun cdn(path: String): String = "$CDN/${path.trimStart('/')}"

    /** "2025-06-12T06:04:03.877Z" -> epoch millis; day precision is enough. */
    private fun parseDate(text: String?): Long {
        val day = Regex("""(\d{4}-\d{2}-\d{2})""").find(text.orEmpty())?.groupValues?.get(1)
            ?: return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
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

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val API_BASE = "https://apiv2.hentaverse.com/api/v1/content"
        private const val CDN = "https://cdn.hentaverse.com"

        private val QUALITIES = listOf(
            "1080p" to "FHD - 1080p",
            "720p" to "HD - 720p",
            "480p" to "SD - 480p",
            "360p" to "360p",
        )
    }
}
