/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaverse

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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        if (query.isNotBlank()) {
            apiRequest("/search?q=${URLEncoder.encode(query, "UTF-8")}")
        } else {
            apiRequest("/series?page=$page")
        }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url.toString()
        if (!url.contains("/search?")) return seriesPage(response)
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
        val items = JSONObject(body).optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
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
        return AnimesPage(animes, animes.isNotEmpty())
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
