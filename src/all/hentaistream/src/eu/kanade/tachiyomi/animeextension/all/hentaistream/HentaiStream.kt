/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaistream

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
import java.io.IOException
import java.net.URLEncoder

/**
 * HentaiStream (https://tube.hentaistream.com)
 *
 * An old WordPress theme where every episode is its own post
 * (/muchuu-no-tou-episode-02). Home and /page/N/ list episode cards
 * (div.post), and search is /?s=QUERY. The site groups episodes under
 * series (/hentaidvd/{slug}), but each post is independently playable, so
 * the catalogue is surfaced post-by-post exactly like the site's own
 * "Latest Episode Updates" listing.
 *
 * Video: each episode post embeds a source frame iframe
 * (/frames/sNN_<Title>.html) whose body carries a direct <video> MP4
 * (cdnN.streamhentai.org/...). Fetch the post, take the first /frames/
 * iframe, then read the MP4 out of that frame.
 */
class HentaiStream : AnimeHttpSource() {

    override val name = "HentaiStream"

    override val baseUrl = "https://tube.hentaistream.com"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("hentaistream/all/1"))
    override val id: Long = 3175594623790874405L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), hasMoreCards(response))

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), hasMoreCards(response))

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        if (query.isNotBlank()) {
            GET("$baseUrl/?s=${URLEncoder.encode(query, "UTF-8")}", headers)
        } else {
            GET("$baseUrl/", headers)
        }

    override fun searchAnimeParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), hasMoreCards(response))

    // =========================== Card parsing =============================

    private fun catalogCards(response: Response): List<SAnime> {
        val doc = response.asJsoup()
        return doc.select("div.post").mapNotNull { card ->
            val a = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!href.startsWith("http") || "/frames/" in href) return@mapNotNull null
            // Skip non-episode chrome links (tags, download, games, blog posts).
            val path = href.removePrefix("$baseUrl/").trim('/')
            val first = path.substringBefore('/')
            if (path.startsWith("list/") || path.startsWith("feed") || path.startsWith("downloadvid") ||
                first in SKIP_SECTIONS
            ) {
                return@mapNotNull null
            }
            val img = card.selectFirst("img[src^=http]") ?: return@mapNotNull null
            val imgTitle = img.attr("title").trim()
            val alt = img.attr("alt").trim()
            val title = card.selectFirst(".posttitle a, .posttitle ins")?.text()?.trim()
                ?.ifBlank { null }
                ?: imgTitle.removePrefix("Watch ").ifBlank { alt.removePrefix("HentaiStream.com ") }
            if (title.isBlank()) return@mapNotNull null
            SAnime.create().apply {
                this.title = title
                url = path
                thumbnail_url = img.attr("src")
            }
        }.distinctBy { it.url }
    }

    private fun hasMoreCards(response: Response): Boolean {
        val doc = response.asJsoup()
        return doc.selectFirst("a[rel=next], .pagination a:containsOwn(Next), a[href*=/page/][href$=/]") != null
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
                .ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
                .ifBlank { doc.selectFirst(".posttitle ins")?.text()?.trim().orEmpty() }
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            genre = doc.select("p.posttags a[rel=tag]").eachText()
                .distinct().take(10).joinToString(", ").ifBlank { null }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            .ifBlank { doc.selectFirst(".posttitle ins")?.text()?.trim().orEmpty() }
        val slug = response.request.url.pathSegments.lastOrNull { it.isNotBlank() }.orEmpty()
        // Map "Episode 02" / "Ep 3" / bare -> 1
        val number = Regex("""(?:episode|ep|e)[-.\s]?(\d+)""", RegexOption.IGNORE_CASE)
            .find(slug)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
        return listOf(
            SEpisode.create().apply {
                url = slug
                name = title.ifBlank { slug }
                episode_number = number
                doc.selectFirst("meta[property=og:image]")?.attr("content")?.let {
                    setEpisodeField(this, "preview_url", it)
                }
            },
        )
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val frame = doc.selectFirst("iframe[src*=/frames/]")
            ?: doc.selectFirst("iframe[src]")
            ?: throw IOException("HentaiStream: no player frame found")
        val frameUrl = frame.absUrl("src").ifBlank { frame.attr("src") }
        if (!frameUrl.startsWith("http")) {
            throw IOException("HentaiStream: player frame has no absolute url")
        }
        // The frame is plain HTML served by the same host; fetch through the client.
        val frameBody = client.newCall(GET(frameUrl, headers)).execute().use { it.body?.string().orEmpty() }
        val mp4 = Regex("""(?:<video[^>]*src="([^"]*)"|src="([^"]*\.mp4[^"]*)")""")
            .find(frameBody)
            ?.let { m -> m.groupValues.firstOrNull { it.isNotBlank() } }
            ?: throw IOException("HentaiStream: no video source in player frame")
        return listOf(Video(mp4, "MP4", mp4, headers = frameHeaders(frameUrl)))
    }

    private fun frameHeaders(frameUrl: String): Headers =
        headers.newBuilder()
            .set("Referer", frameUrl.substringBeforeLast('/'))
            .build()

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
        private val SKIP_SECTIONS = setOf(
            "porn-games", "porn-news", "hentai-news", "anime-porn-reviews",
            "hentai-top-lists", "genres", "contact-us", "redirect", "genres2",
            "new", "wp-content",
        )
    }
}
