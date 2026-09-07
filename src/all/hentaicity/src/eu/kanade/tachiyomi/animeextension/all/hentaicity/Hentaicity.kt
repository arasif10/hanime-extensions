/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaicity

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
import org.jsoup.nodes.Element
import java.io.IOException

/**
 * HentaiCity (https://www.hentaicity.com)
 *
 * Catalog pages live under /videos/straight/ (hentai-recent.html, hentai-popular.html,
 * hentai-recent-N.html pagination). Search is /videos/search/?q=QUERY&page=N. Cards are
 * a.thumb-img with href pointing at a /click/N-1/video/... tracking URL that redirects
 * (302) to the real /video/SLUG.html page.
 *
 * Video pages embed an HLS master playlist in a <video><source> tag
 * (hls.hentaicity.com, token-qualified) plus a direct MP4 fallback
 * (www.hentaicity.com/flv/.../mobile.mp4). Titles are single videos, so each
 * "anime" is one video and its episode list is that single video.
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
        GET("$baseUrl/videos/straight/hentai-popular.html", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), false)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET(
            if (page == 1) {
                "$baseUrl/videos/straight/hentai-recent.html"
            } else {
                "$baseUrl/videos/straight/hentai-recent-$page.html"
            },
            headers,
        )

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animes = catalogCards(response)
        return AnimesPage(animes, animes.isNotEmpty())
    }

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET(
            if (query.isNotBlank()) {
                "$baseUrl/videos/search/?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                    if (page > 1) "&page=$page" else ""
            } else {
                if (page == 1) {
                    "$baseUrl/videos/straight/hentai-recent.html"
                } else {
                    "$baseUrl/videos/straight/hentai-recent-$page.html"
                }
            },
            headers,
        )

    override fun searchAnimeParse(response: Response): AnimesPage {
        val animes = catalogCards(response)
        return AnimesPage(animes, animes.isNotEmpty())
    }

    // ============================== Catalogue parsing =====================

    private fun catalogCards(response: Response): List<SAnime> =
        response.asJsoup().select("a.thumb-img").mapNotNull(::card)

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

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
                .ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            description = doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val url = doc.selectFirst("link[rel=canonical]")?.attr("href")
            ?: response.request.url.toString()
        val slug = url.substringAfter("$baseUrl/").trim('/')
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val img = doc.selectFirst("meta[property=og:image]")?.attr("content")
        return listOf(
            SEpisode.create().apply {
                this.url = slug
                name = title.ifBlank { "Video" }
                episode_number = 1f
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
        // Direct MP4 fallback(s)
        Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""").findAll(body)
            .map { it.value }
            .distinct()
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
    }
}
