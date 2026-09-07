/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hahomoe

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
 * Haho.moe (https://haho.moe)
 *
 * The /anime browse page supports s= rel-d / add-d / vwk-d sorting and q=
 * search, paginated with ?page=N. Anime index pages at /anime/{id} list the
 * episodes, each linking to a watch page /anime/{id}/{n}.
 *
 * The watch page embeds <iframe src="https://haho.moe/embed?v={token}">; the
 * embed page serves <video><source src="https://s1.filegasm.com/..?download_
 * token=.." title="1080p"> quality MP4s.
 */
class HahoMoe : AnimeHttpSource() {

    override val name = "HahoMoe"

    override val baseUrl = "https://haho.moe"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("HahoMoe", "all", 1))
    override val id: Long = 6788476303433097775L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/anime?s=vwk-d&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), hasMoreCards(response))

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/anime?s=rel-d&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), hasMoreCards(response))

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        if (query.isNotBlank()) {
            GET("$baseUrl/anime?q=${URLEncoder.encode(query, "UTF-8")}&page=$page", headers)
        } else {
            latestUpdatesRequest(page)
        }

    override fun searchAnimeParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), hasMoreCards(response))

    // ============================ Catalogue ===============================

    private fun catalogCards(response: Response): List<SAnime> {
        val doc = response.asJsoup()
        return doc.select("li[class*=anime-]").mapNotNull { li ->
            val a = li.selectFirst("a[href*=/anime/]") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            // skip episode links (contain /anime/{id}/{n})
            if (!href.startsWith("http") || Regex("""/anime/[a-z0-9]+/\d+""").containsMatchIn(href)) {
                return@mapNotNull null
            }
            val title = a.attr("title").trim()
                .ifBlank { li.selectFirst("span.thumb-title")?.text()?.trim().orEmpty() }
                .ifBlank { a.attr("alt").trim() }
            if (title.isBlank()) return@mapNotNull null
            SAnime.create().apply {
                this.title = title
                url = href.removePrefix("$baseUrl/").trim('/')
                thumbnail_url = li.selectFirst("img[src^=http]")?.attr("src")
            }
        }.distinctBy { it.url }
    }

    private fun hasMoreCards(response: Response): Boolean {
        val doc = response.asJsoup()
        val current = getPage(response)
        // /anime pagination links are /anime?page=N (with q=/s= when filtering)
        return doc.select("a[href*=page=]").any { a ->
            (a.attr("abs:href").ifBlank { a.attr("href") })
                .substringAfter("page=", "").substringBefore('&').toIntOrNull()?.let { it > current } == true
        }
    }

    private fun getPage(response: Response): Int =
        response.request.url.queryParameter("page")?.toIntOrNull() ?: 1

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
                .ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty() }
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img[src*=/images/anime/]")?.attr("src")
            description = doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val animeId = response.request.url.toString()
            .substringAfter("$baseUrl/anime/").substringBefore('/').takeIf { it.isNotBlank() }
            ?: throw IOException("HahoMoe: bad url ${response.request.url}")
        // Episode rows appear in the playlist; prefer links without a mirror ?v=
        val links = doc.select("a[href*='/anime/$animeId/']").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!href.startsWith("http")) return@mapNotNull null
            href
        }
        // Deduplicate: keep only bare episode pages /anime/{id}/{n} (no query),
        // falling back to the first mirror link when the bare page is missing.
        val bare = links.filter { !it.contains('?') && Regex("""/anime/$animeId/\d+$""").containsMatchIn(it) }
            .distinct()
        val selected = if (bare.isNotEmpty()) {
            bare
        } else {
            links.filter { Regex("""/anime/$animeId/\d+""").containsMatchIn(it) }.distinct()
        }
        if (selected.isEmpty()) {
            throw IOException("HahoMoe: no episodes found")
        }
        return selected.map { href ->
            val num = Regex("""/anime/$animeId/(\d+)""").find(href)?.groupValues?.get(1)
            SEpisode.create().apply {
                url = href.removePrefix("$baseUrl/").trim('/')
                name = "Episode ${num ?: "?"}"
                episode_number = num?.toFloatOrNull() ?: 1f
            }
        }
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val watchHtml = response.body?.string().orEmpty()
        val embed = Regex("""<iframe[^>]+src="(https://haho\.moe/embed\?v=[^"]+)"|src='(https://haho\.moe/embed\?v=[^']+)'""")
            .find(watchHtml)?.let { m -> m.groupValues[1].ifBlank { m.groupValues[2] } }
            ?: throw IOException("HahoMoe: no embed found")
        val embedHtml = client.newCall(GET(embed, headers)).execute()
            .use { it.body?.string().orEmpty() }
        val doc = Jsoup.parse(embedHtml)
        val videos = doc.select("video source").mapNotNull { src ->
            val url = src.attr("abs:src").ifBlank { src.attr("src") }
            if (!url.startsWith("http")) return@mapNotNull null
            val quality = src.attr("title").ifBlank { "Auto" }
            Video(url, quality, url, headers = headers)
        }
        if (videos.isEmpty()) throw IOException("HahoMoe: no video sources")
        // Prefer highest quality first, dedupe by url
        return videos.distinctBy { it.url }.sortedByDescending { it.quality }
    }

    // ============================== Helpers ===============================

    private fun Response.asJsoup() = Jsoup.parse(body?.string().orEmpty(), request.url.toString())

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
