/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.watchhentai

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
import java.util.Base64

/**
 * WatchHentai (https://watchhentai.net)
 *
 * WordPress Dooplay theme. The /videos/ catalogue (and /?s= search results)
 * lists episode posts. Each episode post and each /series/{slug} page carries a
 * <ul class="episodios"> block listing the series' episodes, each linking to a
 * per-episode video post.
 *
 * Video resolution:
 *  1. The episode post embeds <iframe data-primary-player-url=
 *     "https://watchhentai.net/player/{post-id}/1/mp4/">.
 *  2. GET that player page; it declares  var jw = {"file":"<enc>"}.
 *  3. <enc> decodes (base64 -> XOR chain -> reversed base64) to a direct MP4 on
 *     hstorage.xyz.
 */
class WatchHentai : AnimeHttpSource() {

    override val name = "WatchHentai"

    override val baseUrl = "https://watchhentai.net"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("WatchHentai", "all", 1))
    override val id: Long = 5265206503077835376L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET(
            if (page == 1) "$baseUrl/videos/" else "$baseUrl/videos/page/$page/",
            headers,
        )

    override fun popularAnimeParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), hasMoreCards(response))

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET(
            if (page == 1) "$baseUrl/videos/" else "$baseUrl/videos/page/$page/",
            headers,
        )

    override fun latestUpdatesParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), hasMoreCards(response))

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        if (query.isNotBlank()) {
            GET("$baseUrl/page/$page/?s=${URLEncoder.encode(query, "UTF-8")}", headers)
        } else {
            latestUpdatesRequest(page)
        }

    override fun searchAnimeParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), hasMoreCards(response))

    // ============================ Catalogue ===============================

    private fun catalogCards(response: Response): List<SAnime> {
        val doc = response.asJsoup()
        return doc.select("article.item, article.post").mapNotNull { el ->
            val a = el.selectFirst("a[href*=/videos/], a[href*=/series/]") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!href.startsWith("http") ||
                (!href.contains("/videos/") && !href.contains("/series/"))
            ) {
                return@mapNotNull null
            }
            val anchorTitle = a.attr("title").trim()
                .substringBefore(" Watch Hentai")
            val title = if (anchorTitle.isNotBlank()) {
                anchorTitle
            } else {
                el.selectFirst("h3, h2")?.text()?.trim() ?: return@mapNotNull null
            }
            SAnime.create().apply {
                this.title = title
                url = href.removePrefix("$baseUrl/").trim('/')
                thumbnail_url = el.selectFirst("img[data-src]")?.attr("data-src")
                    ?.takeIf { it.startsWith("http") }
                    ?: el.selectFirst("img[src^=http]")?.attr("src")
            }
        }.distinctBy { it.url }
    }

    private fun hasMoreCards(response: Response): Boolean {
        val doc = response.asJsoup()
        return doc.selectFirst("a[rel=next]") != null ||
            doc.selectFirst(".pagination a:containsOwn(Next)") != null
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val ogImg = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val seriesName = ogTitle.substringBefore(" - Watch Hentai").trim()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim()
                ?: seriesName.ifBlank { ogTitle }
            thumbnail_url = doc.selectFirst("img.tv-single-hero__poster, img[src*=/poster]")?.let { img ->
                val thumb = img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src")
                thumb.takeIf { it.startsWith("http") }
            } ?: ogImg
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("div.tv-single-about p")?.text()
            genre = doc.select("a[href*=/genre/]").eachText().distinct().take(10)
                .joinToString(", ").ifBlank { null }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        // Both series pages and episode posts carry the series episode list in
        // <ul class="episodios"> (single-episode posts just list one entry).
        val items = doc.select("ul.episodios li a[href*=/videos/]")
        if (items.isEmpty()) {
            throw IOException("WatchHentai: no episode list found")
        }
        return items.mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!href.contains("/videos/")) return@mapNotNull null
            val title = a.attr("title").substringBefore(" Watch Hentai").trim()
                .ifBlank { a.text().trim() }
            SEpisode.create().apply {
                url = href.removePrefix("$baseUrl/").trim('/')
                name = title.ifBlank { "Episode 1" }
                episode_number = Regex("""(?:episode|ep)[-\s]?(\d+)""", RegexOption.IGNORE_CASE)
                    .find(title + " " + href)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
                a.parent()?.selectFirst("img")?.let { img ->
                    val thumb = img.attr("data-src").ifBlank { img.attr("src") }
                    if (thumb.startsWith("http")) setEpisodeField(this, "preview_url", thumb)
                }
            }
        }.distinctBy { it.url }
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val html = response.body?.string().orEmpty()
        // Player URL is exposed on the iframe of the episode post.
        val playerUrl = Jsoup.parse(html).selectFirst("iframe[data-primary-player-url]")
            ?.attr("data-primary-player-url")
            ?.takeIf { it.startsWith("http") }
        val player = if (playerUrl != null) {
            client.newCall(GET(playerUrl, headers)).execute()
                .use { it.body?.string().orEmpty() }
        } else {
            html
        }
        val enc = Regex("""var jw\s*=\s*\{[^}]*?"file":"([^"]+)"[^}]*?\}""").find(player)
            ?.groupValues?.get(1)
            ?: throw IOException("WatchHentai: no player config found")
        val direct = decodeMediaUrl(enc)
        return listOf(Video(direct, "WatchHentai", direct, headers = headers))
    }

    // whDecodeMediaUrl(s): base64 -> XOR(13+i%17) -> reversed base64.
    private fun decodeMediaUrl(s: String): String {
        val normalized = s.replace('-', '+').replace('_', '/').let {
            it + "=".repeat((4 - it.length % 4) % 4)
        }
        val x = String(Base64.getDecoder().decode(normalized), Charsets.ISO_8859_1)
        val r = StringBuilder(x.length)
        for (i in x.indices) {
            r.append((x[i].code xor ((13 + i % 17) and 0xFF)).toChar())
        }
        return String(Base64.getDecoder().decode(r.reverse().toString()), Charsets.ISO_8859_1)
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
