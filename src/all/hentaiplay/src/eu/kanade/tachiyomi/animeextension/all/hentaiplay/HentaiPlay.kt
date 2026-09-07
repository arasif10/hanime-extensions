/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaiplay

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
 * HentaiPlay (https://hentaiplay.net)
 *
 * WordPress site where every post is a single episode
 * (https://hentaiplay.net/{slug}/). The homepage with ?orderby=views is the
 * popularity sort, /hentai/episodes/new-release/ is newest (paginated /page/N/),
 * search is /?s=QUERY.
 *
 * Each episode page carries the direct MP4 in a <video><source> tag
 * (hosted on hentaiplanet.info).
 */
class HentaiPlay : AnimeHttpSource() {

    override val name = "HentaiPlay"

    override val baseUrl = "https://hentaiplay.net"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("hentaiplay", "all", 1))
    override val id: Long = 5386965955723884187L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/?orderby=views&paged=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        paginatedAnimesPage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/hentai/episodes/new-release/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        paginatedAnimesPage(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        if (query.isNotBlank()) {
            GET(
                "$baseUrl/".toHttpUrl().newBuilder()
                    .addQueryParameter("s", query)
                    .addQueryParameter("paged", page.toString())
                    .build(),
                headers,
            )
        } else {
            GET("$baseUrl/?orderby=views&paged=$page", headers)
        }

    override fun searchAnimeParse(response: Response): AnimesPage =
        paginatedAnimesPage(response)

    // ============================== Catalogue parsing =====================

    private fun paginatedAnimesPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("a.clip-link").mapNotNull(::videoCard)
        val pages = doc.select("a[href]").mapNotNull { el ->
            val href = el.attr("href")
            val m = PAGE_REGEX.find(href) ?: return@mapNotNull null
            val page = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            if (m.groupValues[2] == "orderby") page else null
        }
        val hasNext = animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    private fun videoCard(el: Element): SAnime? {
        val href = el.attr("href").takeIf { it.startsWith("$baseUrl/") && it != "$baseUrl/" }
            ?: return null
        val title = el.attr("title").trim().ifBlank { null }
            ?: el.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val img = el.selectFirst("img")
        return SAnime.create().apply {
            this.title = title
            this.url = href.substringAfter("$baseUrl/").trim('/')
            thumbnail_url = img?.attr("src")?.takeIf { it.startsWith("http") }
        }
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}/", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
                .ifBlank { ogTitle.removeSuffix(" - Hentai Play") }
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            description = doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}/", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val img = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val ep = SEpisode.create().apply {
            url = response.request.url.toString().substringAfter("$baseUrl/").trim('/')
            name = title
            episode_number = 1f
            img?.takeIf { it.startsWith("http") }?.let { setEpisodeField(this, "preview_url", it) }
        }
        return listOf(ep)
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val body = response.body?.string().orEmpty()
        // direct <video><source src="..."> is the primary source
        val direct = doc.select("video source[src]").firstOrNull()?.attr("src")
            ?: doc.selectFirst("video[id=my-video]")?.selectFirst("source")?.attr("src")
        val url = direct?.takeIf { it.startsWith("http") }
            ?: MP4_REGEX.find(body.replace("\\", ""))?.groupValues?.get(1)
            ?: throw IOException("HentaiPlay: no playable stream found")
        return listOf(Video(url, "MP4", url, headers = headers))
    }

    // ============================== Helpers ===============================

    private fun Response.asJsoup(): Document = Jsoup.parse(body?.string().orEmpty(), request.url.toString())

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

        private val PAGE_REGEX = Regex("""/(?:page/)?(\d+)/?(?:\?|$)""")

        private val MP4_REGEX = Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""")
    }
}
