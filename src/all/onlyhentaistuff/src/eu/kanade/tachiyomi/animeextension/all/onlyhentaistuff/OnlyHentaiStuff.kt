/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.onlyhentaistuff

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
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder

/**
 * OnlyHentaiStuff (https://www.onlyhentaistuff.com)
 *
 * KVS-based per-video catalog. Each listing card is a standalone episode
 * post (/videos/{id}/{slug}/) with its own stream.
 *
 * Video resolution:
 *  1. The video page embeds `var flashvars = {...}` with
 *     `license_code: '$848068927979278'` and
 *     `video_url: 'function/0/https://.../get_file/1/<sha1>/.../file.mp4/'`.
 *  2. The kt_player runtime scrambles the first 32 chars of the sha1 with a
 *     fixed permutation derived from the license hotkeys (the remaining 8
 *     chars pass through), then appends `?rnd=<epoch ms>`.
 *  3. The scrambled URL 302-redirects to a signed CDN link.
 *
 * The flashvars hashes are long-lived; the `?rnd=` cache-buster and Referer
 * are what the CDN requires. License code verified constant across videos.
 */
class OnlyHentaiStuff : AnimeHttpSource() {

    override val name = "OnlyHentaiStuff"

    override val baseUrl = "https://www.onlyhentaistuff.com"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("OnlyHentaiStuff", "all", 1))
    override val id: Long = 8472081291031779782L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    private val ajaxHeaders: Headers = headersBuilder()
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET(if (page == 1) "$baseUrl/most-popular/" else "$baseUrl/most-popular/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), hasMoreCards(response))

    // ============================== Latest ================================

    // /latest-updates/ is server-rendered with plain page URLs (/latest-updates/2/).
    override fun latestUpdatesRequest(page: Int): Request =
        GET(if (page == 1) "$baseUrl/latest-updates/" else "$baseUrl/latest-updates/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), hasMoreCards(response))

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return popularAnimeRequest(page)
        val url = buildString {
            append(baseUrl)
            append("/search/").append(URLEncoder.encode(query.trim(), "UTF-8"))
            append("/?mode=async&function=get_block")
            append("&block_id=list_videos_videos_list_search_result")
            append("&q=").append(URLEncoder.encode(query.trim(), "UTF-8"))
            append("&category_ids=&sort_by=")
            if (page > 1) append("&from_videos+from_albums=").append(page)
        }
        return GET(url, ajaxHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), hasMoreCards(response))

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // ============================= Catalogue ==============================

    private fun catalogCards(response: Response): List<SAnime> {
        val doc = response.asJsoup()
        return doc.select("div.item:has(a[href*=/videos/])").mapNotNull { el ->
            val a = el.selectFirst("a[href*=/videos/]") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!href.contains(Regex("/videos/\\d+/"))) return@mapNotNull null
            val title = (
                a.attr("title")
                    .takeIf { it.isNotBlank() }
                    ?: el.selectFirst("strong.title")?.text()?.trim()
                )
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SAnime.create().apply {
                this.title = title
                url = href.removePrefix("$baseUrl/").trim('/')
                thumbnail_url = el.selectFirst("img[data-original]")?.attr("data-original")
                    ?.takeIf { it.startsWith("http") }
                    ?: el.selectFirst("img[src^=http]")?.attr("src")
            }
        }.distinctBy { it.url }
    }

    private fun hasMoreCards(response: Response): Boolean {
        val doc = response.asJsoup()
        // Async block pagination: any further page link (KVS renders
        // li.page-current for the active page and li.page/a for the rest).
        if (doc.selectFirst("ul.pagination li.page a") != null) return true
        // Server-rendered fallbacks
        if (doc.selectFirst(".pagination a:containsOwn(Next)") != null) return true
        if (doc.selectFirst("a[rel=next]") != null) return true
        return false
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}/", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.title().substringBefore(" Hentai Online").trim()
            author = doc.select("a[href*=/models/]").firstOrNull()?.text()?.trim()
            status = SAnime.UNKNOWN
            description = buildString {
                doc.selectFirst("meta[name=description]")?.attr("content")?.let { append(it.trim()) }
                val tags = doc.select("a[href*=/tags/]").eachText()
                    .map { it.trim() }.filter { it.isNotBlank() && it != "..." }
                if (tags.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Tags: ").append(tags.distinct().joinToString(", "))
                }
            }
            genre = doc.select("a[href*=/categories/], a[href*=/tags/]")
                .eachText().map { it.trim() }
                .filter { it.isNotBlank() && it != "..." }
                .distinct().take(10).joinToString(", ")
        }
    }

    // ========================== Related / Recommended =====================

    // AniZen fills the "Recommended" row of its detail screen only when the
    // extension advertises support and implements `fetchRelatedAnimeList`.
    // Those members exist on AniZen's runtime source API but not on the older
    // lib-14 stub we compile against, so they are declared without `override`
    // — the JVM dispatches the runtime interface methods to them anyway.
    val supportsRelatedAnimes: Boolean get() = true

    suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        return runCatching {
            val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl/${anime.url}/"
            client.newCall(GET(url, headers)).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val doc = response.asJsoup()
                doc.select("div.related-videos div.item").mapNotNull { el ->
                    val a = el.selectFirst("a[href*=/videos/]") ?: return@mapNotNull null
                    val href = a.absUrl("href").ifBlank { a.attr("href") }
                    val title = a.attr("title").trim()
                        .ifBlank { el.selectFirst("strong.title")?.text()?.trim() }
                        ?: return@mapNotNull null
                    SAnime.create().apply {
                        this.title = title
                        this.url = href.removePrefix("$baseUrl/").trim('/')
                        thumbnail_url = el.selectFirst("img[data-original]")?.attr("data-original")
                            ?.takeIf { it.startsWith("http") }
                    }
                }.distinctBy { it.url }
            }
        }.getOrDefault(emptyList())
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}/", headers)

    // The catalog is per-video: every entry is its own episode.
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val slug = response.request.url.encodedPath.trim('/').substringAfterLast('/')
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().substringBefore(" Hentai Online").trim()
        val number = title.extractEpisodeNumber() ?: 1f
        return listOf(
            SEpisode.create().apply {
                url = slug
                name = title
                episode_number = number
                date_upload = 0L
                doc.selectFirst("meta[property=og:image]")?.attr("content")?.let {
                    setEpisodeField(this, "preview_url", it)
                }
            },
        )
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/${episode.url}/", headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val html = doc.outerHtml()
        val referer = response.request.url.toString()

        val license = REGEX_LICENSE.find(html)?.groupValues?.get(1) ?: ""
        if (license != LICENSE_CODE) {
            // The permutation below is derived from the site's license hotkeys;
            // if the license ever changes the scramble changes with it.
            throw IOException("Unexpected license_code '$license' — extension needs an update")
        }

        return buildList {
            addAll(videoFromFlashvar(html, "video_url", "video_url_text", referer))
            addAll(videoFromFlashvar(html, "video_alt_url", "video_alt_url_text", referer))
            addAll(videoFromFlashvar(html, "video_alt_url2", "video_alt_url2_text", referer))
        }.ifEmpty { throw IOException("No video URLs found in flashvars") }
    }

    private fun videoFromFlashvar(html: String, urlKey: String, qualityKey: String, referer: String): List<Video> {
        val raw = Regex("$urlKey\\s*:\\s*'([^']+)'").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val quality = Regex("$qualityKey\\s*:\\s*'([^']+)'").find(html)?.groupValues?.get(1)
            ?: "Default"
        if (!raw.contains("/get_file/1/")) return emptyList()

        val url = raw.replace("function/0/", "")
        // hash is 42 chars: 32 scrambled by the player + 10 untouched tail
        val hashMatch = Regex("/get_file/1/([0-9a-f]{42})/").find(url)
            ?: return emptyList()
        val hash = hashMatch.groupValues[1]
        val scrambled = buildString {
            for (i in 0 until 32) append(hash[PERMUTATION[i]])
            append(hash.substring(32))
        }
        val videoUrl = url.replace(hash, scrambled) + "?rnd=" + System.currentTimeMillis()

        return listOf(
            Video(videoUrl, quality, videoUrl, headers = Headers.Builder().add("Referer", referer).build()),
        )
    }

    // ============================== Utilities =============================

    private fun Response.asJsoup(): Document =
        Jsoup.parse(body?.string().orEmpty(), request.url.toString())

    private fun String.extractEpisodeNumber(): Float? =
        Regex("(?:^|[^0-9])ep\\.?\\s*(\\d{1,3})(?:\$|[^0-9])", RegexOption.IGNORE_CASE)
            .find(this)?.groupValues?.get(1)?.toFloatOrNull()
            ?: Regex("(?:^|\\s)(\\d{1,3})$").find(this)?.groupValues?.get(1)?.toFloatOrNull()

    private fun setEpisodeField(episode: SEpisode, fieldName: String, value: String) {
        runCatching {
            val field = SEpisode::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(episode, value)
        }
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private const val LICENSE_CODE = "\$848068927979278"

        private val REGEX_LICENSE = Regex("license_code\\s*:\\s*'([^']+)'")

        // Derived from two (flashvars-hash, player-hash) pairs captured from
        // the live player; verified against a third video. player_hash[i] =
        // fv_hash[PERMUTATION[i]] for i < 32; the tail passes through.
        private val PERMUTATION = intArrayOf(
            12, 31, 28, 5, 11, 7, 20, 27, 17, 0, 6, 30, 9, 4, 15, 13,
            8, 3, 16, 25, 10, 19, 14, 29, 24, 18, 23, 22, 2, 21, 26, 1,
        )
    }
}
