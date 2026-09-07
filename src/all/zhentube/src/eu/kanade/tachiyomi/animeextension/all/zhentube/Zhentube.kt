/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.zhentube

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder

/**
 * Zhentube (https://zhentube.com)
 *
 * WordPress Dooplay theme. Catalog pages are /page/N/ and
 * /category/{slug}/page/N/?filter=latest for the New Releases row; search is
 * /?s=QUERY. Cards are article.loop-video with a direct post link and a
 * data-src thumbnail.
 *
 * Every post embeds a javbest.cc FirePlayer iframe. Resolving it:
 *  1. GET the post page, read embedUrl (https://javbest.cc/video/{id})
 *  2. GET https://javbest.cc/download/{id} and unpack the packed JS to find
 *     the AES key ("ck")
 *  3. POST https://javbest.cc/video/{id}?do=getVideo with hash={id}
 *     -> JSON { securedLink: "...master.m3u8?md5=..&expires=.." }
 *  4. The securedLink is a standard HLS master playlist.
 */
class Zhentube : AnimeHttpSource() {

    override val name = "Zhentube"

    override val baseUrl = "https://zhentube.com"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("Zhentube", "all", 1))
    override val id: Long = 6339511979130855530L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET(
            if (page == 1) {
                "$baseUrl/?filter=most-viewed&cat=2001"
            } else {
                "$baseUrl/category/new-release-hentai/page/$page/?filter=most-viewed"
            },
            headers,
        )

    override fun popularAnimeParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), hasMoreCards(response))

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET(
            if (page == 1) {
                "$baseUrl/?filter=latest&cat=2001"
            } else {
                "$baseUrl/category/new-release-hentai/page/$page/?filter=latest"
            },
            headers,
        )

    override fun latestUpdatesParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), hasMoreCards(response))

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        if (query.isNotBlank()) {
            GET(
                "$baseUrl/page/$page/?s=${URLEncoder.encode(query, "UTF-8")}",
                headers,
            )
        } else {
            latestUpdatesRequest(page)
        }

    override fun searchAnimeParse(response: Response): AnimesPage =
        AnimesPage(catalogCards(response), hasMoreCards(response))

    // ============================== Catalogue parsing =====================

    private fun catalogCards(response: Response): List<SAnime> {
        val doc = response.asJsoup()
        return doc.select("article.loop-video").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!href.startsWith("http")) return@mapNotNull null
            val title = a.attr("title").trim().ifBlank {
                el.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }
            if (title.isBlank()) return@mapNotNull null
            SAnime.create().apply {
                this.title = title
                url = href.removePrefix("$baseUrl/").trim('/')
                thumbnail_url = el.selectFirst("img[data-src]")?.attr("data-src")
                    ?.takeIf { it.startsWith("http") }
                    ?: el.selectFirst("img[src^=http]")?.attr("src")
            }
        }
    }

    private fun hasMoreCards(response: Response): Boolean {
        val doc = response.asJsoup()
        return doc.selectFirst("a[rel=next], .pagination a:containsOwn(Next), .pagination a:containsOwn(›)") != null
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
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            genre = doc.select(".sgeneros a, .genres a, a[href*=/genre/]").eachText()
                .distinct().take(10).joinToString(", ").ifBlank { null }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val slug = response.request.url.pathSegments.lastOrNull { it.isNotBlank() }.orEmpty()
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        val embedUrl = doc.selectFirst("meta[property=og:video:url], iframe[src*=javbest]")?.let {
            it.absUrl("content").ifBlank { it.absUrl("src") }
        }.orEmpty()
        val javbestId = Regex("javbest\\.cc/video/([a-f0-9]+)").find(embedUrl)?.groupValues?.get(1)
            ?: throw IOException("Zhentube: no javbest embed found")
        val number = Regex("""(?:episode|ep)[-\s]?(\d+)""", RegexOption.IGNORE_CASE)
            .find(slug)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
        return listOf(
            SEpisode.create().apply {
                url = javbestId
                name = title.ifBlank { slug }
                episode_number = number
                doc.selectFirst("meta[property=og:image]")?.attr("content")?.let {
                    setEpisodeField(this, "preview_url", it)
                }
            },
        )
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request {
        // Step 1: fetch the download page to extract the AES key.
        return GET("$JAVBEST/download/${episode.url}", javbestHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val dlBody = response.body?.string().orEmpty()
        val id = Regex("""javbest\.cc/download/([a-f0-9]+)""")
            .find(response.request.url.toString())?.groupValues?.get(1)
            ?: throw IOException("Zhentube: missing video id")
        // Unpack the Dean Edwards packer payload to find the ck key.
        val packer = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
            .find(dlBody) ?: throw IOException("Zhentube: player packer not found")
        val key = extractCk(packer.groupValues[1], packer.groupValues[4].split("|"))
            ?: throw IOException("Zhentube: AES key not found")

        // Step 2: POST getVideo to get the secured HLS link.
        val postBody = FormBody.Builder().add("hash", id).build()
        val req = Request.Builder()
            .url("$JAVBEST/video/$id?do=getVideo")
            .headers(javbestHeaders)
            .addHeader("Referer", "$JAVBEST/download/$id")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .post(postBody)
            .build()
        val json = client.newCall(req).execute().use { it.body?.string().orEmpty() }
        val secured = Regex(""""securedLink":"((?:[^"\\]|\\.)*)"""").find(json)
            ?.groupValues?.get(1)?.replace("\\/", "/")
            ?: throw IOException("Zhentube: no securedLink in getVideo response")
        return listOf(Video(secured, "HLS", secured, headers = javbestHeaders))
    }

    private fun extractCk(payload: String, dict: List<String>): String? {
        // Base-62 Dean Edwards unpack of the packed payload, then find "ck".
        val unpacked = unpackPacker(payload, dict)
        val ckRaw = Regex(""""ck":"([^"]+)"""").find(unpacked)?.groupValues?.get(1) ?: return null
        // ck is a hex-escaped string that base64-decodes to the AES passphrase.
        val unescaped = ckRaw.replace("\\\\", "\\")
            .let { HEX_ESC.replace(it) { m -> m.groupValues[1].toInt(16).toChar().toString() } }
        return try {
            val padded = unescaped + "=".repeat((4 - unescaped.length % 4) % 4)
            String(java.util.Base64.getDecoder().decode(padded))
        } catch (_: Exception) {
            null
        }
    }

    private fun unpackPacker(payload: String, dict: List<String>): String {
        val sb = StringBuilder()
        val wordRegex = Regex("""\w+""")
        var last = 0
        for (m in wordRegex.findAll(payload)) {
            sb.append(payload.substring(last, m.range.first))
            val tok = m.value
            val idx = tok.fold(0L) { acc, c ->
                val v = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'z' -> c - 'a' + 10
                    in 'A'..'Z' -> c - 'A' + 36
                    else -> return@fold acc * 100 // bail
                }
                acc * 62 + v
            }.toInt()
            sb.append(if (idx in dict.indices) dict[idx] else tok)
            last = m.range.last + 1
        }
        sb.append(payload.substring(last))
        return sb.toString()
    }

    private val javbestHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Referer", "$JAVBEST/")
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
        private const val JAVBEST = "https://javbest.cc"

        private val HEX_ESC = Regex("""\\x([0-9a-fA-F]{2})""")
    }
}
