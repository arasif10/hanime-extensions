/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaish

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

/**
 * HentaiSh (https://hentai.sh)
 *
 * Next.js site where every catalog entry is an episode page at /video/{slug}.
 * Homepage lists "Recent Aired" episodes with /?page=N pagination (176 pages),
 * /trending lists popular episodes, search is /?s=QUERY.
 *
 * Each /video/ page embeds the episode's HLS master playlist
 * (https://edge1.hentai.sh/v/{slug}/master.m3u8?v=TOKEN) directly in the HTML
 * (inside Next.js flight data, backslash-escaped). The same-series episode list
 * is rendered as a.series-ep rows on the detail page.
 */
class HentaiSh : AnimeHttpSource() {

    override val name = "HentaiSh"

    override val baseUrl = "https://hentai.sh"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("hentaish", "all", 1))
    override val id: Long = 8732455434697193948L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/trending", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animes = response.asJsoup().select("a.video-card").mapNotNull(::videoCard)
        return AnimesPage(animes, false)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        paginatedAnimesPage(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        if (query.isNotBlank()) {
            GET(
                "$baseUrl/".toHttpUrl().newBuilder()
                    .addQueryParameter("s", query)
                    .addQueryParameter("page", page.toString())
                    .build(),
                headers,
            )
        } else {
            GET("$baseUrl/?page=$page", headers)
        }

    override fun searchAnimeParse(response: Response): AnimesPage =
        paginatedAnimesPage(response)

    // ============================== Catalogue parsing =====================

    private fun paginatedAnimesPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("a.video-card").mapNotNull(::videoCard)
        val lastPage = doc.select("a[href]").mapNotNull { el ->
            val q = el.attr("href").substringAfter("?page=", "")
            q.takeIf { it.isNotBlank() }?.toIntOrNull()
        }.maxOrNull()
        val hasNext = lastPage?.let { response.page() < it } ?: animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    private fun videoCard(el: Element): SAnime? {
        val href = el.attr("href").takeIf { it.startsWith("/video/") } ?: return null
        val img = el.selectFirst("img")
        val title = el.selectFirst("img")?.attr("alt")?.trim()
            ?: el.ownText().trim()
            ?: return null
        return SAnime.create().apply {
            this.title = title
            this.url = href.trim('/')
            thumbnail_url = img?.attr("src")?.takeIf { it.startsWith("http") }
        }
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifBlank { ogTitle.removeSuffix(" · HentaiSh") }
        return SAnime.create().apply {
            this.title = title
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("video")?.attr("poster")?.takeIf { it.startsWith("http") }
            description = doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val current = animeUrlSlug(doc)
        val episodes = doc.select("a.series-ep").mapNotNull { el ->
            val href = el.attr("href").takeIf { it.startsWith("/video/") } ?: return@mapNotNull null
            val slug = href.trim('/')
            val img = el.selectFirst("img")
            val number = Regex("""-episode-(\d+)""").find(slug)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            SEpisode.create().apply {
                url = slug
                name = "Episode $number"
                episode_number = number.toFloat()
                el.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }?.let {
                    setEpisodeField(this, "preview_url", it)
                }
            }
        }.distinctBy { it.url }
        // If the detail page's series-ep list is missing, fall back to the
        // single current episode so the player is still reachable.
        if (episodes.isEmpty()) {
            val currentEp = episodesFromCurrent(doc, current)
            if (currentEp != null) return listOf(currentEp)
        }
        return episodes
    }

    private fun animeUrlSlug(doc: Document): String =
        doc.selectFirst("link[rel=canonical]")?.attr("href")
            ?.substringAfter("$baseUrl/", "")
            ?.trim('/')
            ?: ""

    private fun episodesFromCurrent(doc: Document, current: String): SEpisode? {
        if (current.isBlank()) return null
        val img = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val number = Regex("""-episode-(\d+)""").find(current)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1
        return SEpisode.create().apply {
            url = current
            name = "Episode $number"
            episode_number = number.toFloat()
            img?.takeIf { it.startsWith("http") }?.let { setEpisodeField(this, "preview_url", it) }
        }
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body?.string().orEmpty()
        val master = MASTER_REGEX.find(body.replace("\\", ""))
            ?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") }
            ?: throw IOException("HentaiSh: HLS master not found in page")
        return listOf(Video(master, "HLS", master, headers = headers))
    }

    // ============================== Helpers ===============================

    private fun Response.asJsoup(): Document = Jsoup.parse(body?.string().orEmpty(), request.url.toString())

    private fun Response.page(): Int =
        request.url.queryParameter("page")?.toIntOrNull() ?: 1

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

        private val MASTER_REGEX = Regex("""https?://[^"\\]+master\.m3u8[^"\\]*""")
    }
}
