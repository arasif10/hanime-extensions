/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaiocean

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
import java.io.IOException
import java.net.URLEncoder

/**
 * HentaiOcean (https://hentaiocean.com)
 *
 * Has a documented public API (https://hentaiocean.com/api-docs):
 *  - /api?action=search&query=Q      -> full JSON array of items (id, urlname,
 *     videoname, description, coverimg, ...). Empty query returns nothing, so
 *     the catalog is sourced from rss.xml (682 items) paginated locally.
 *  - /api?action=hentai&slug=SLUG    -> single item with description + genres
 *  - /thumbnail/SLUG.webp            -> 16:9 video thumbnail
 *  - /assets/cover/FILE              -> DVD cover image
 *
 * Popular comes from the homepage's featured row. Watch pages embed a
 * `jsondata = {...}` blob whose `mirrors[].mirrorurl` for the site's own
 * player is `https://wN.hentaiocean.com/play?vid=NAME.mp4`, which is a
 * video.js page whose source is simply `https://wN.hentaiocean.com/video/NAME.mp4`.
 */
class HentaiOcean : AnimeHttpSource() {

    override val name = "HentaiOcean"

    override val baseUrl = "https://hentaiocean.com"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("HentaiOcean", "all", 1))
    override val id: Long = 8773291712009922267L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val body = response.body?.string().orEmpty()
        val animes = Regex("""href="(https://hentaiocean\.com/watch/[^"]+)"""")
            .findAll(body)
            .map { it.groupValues[1] }
            .distinct()
            .map { slugToAnime(it.substringAfter("/watch/")) }
            .toList()
        return AnimesPage(animes, false)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/rss.xml?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val body = response.body?.string().orEmpty()
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val items = RSS_ITEM_REGEX.findAll(body).map { it.groupValues[1] }.toList()
        val from = (page - 1) * PAGE_SIZE
        val animes = items.drop(from).take(PAGE_SIZE).map { slugToAnime(it) }
        return AnimesPage(animes, from + PAGE_SIZE < items.size)
    }

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        if (query.isNotBlank()) {
            GET("$baseUrl/api?action=search&query=${URLEncoder.encode(query, "UTF-8")}", headers)
        } else {
            latestUpdatesRequest(page)
        }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body?.string().orEmpty().trim()
        if (!body.startsWith("[")) return AnimesPage(emptyList(), false)
        val animes = JSON_STRING_REGEX.findAll(body).mapNotNull { m ->
            val urlname = m.groupValues[1]
            val videoname = m.groupValues[2].unescape()
            SAnime.create().apply {
                title = videoname
                url = "watch/$urlname"
                thumbnail_url = "$baseUrl/thumbnail/$urlname.webp"
            }
        }.toList()
        return AnimesPage(animes, false)
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/api?action=hentai&slug=${anime.url.substringAfter("watch/")}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val body = response.body?.string().orEmpty()
        val slug = response.request.url.queryParameter("slug").orEmpty()
        val titleMatch = JSON_STRING_REGEX.find(body)
        val title = titleMatch?.groupValues?.get(2)?.unescape().orEmpty()
        val description = Regex(""""description":"((?:[^"\\]|\\.)*)"""")
            .find(body)?.groupValues?.get(1)?.unescape()
        val genres = Regex(""""genre":"((?:[^"\\]|\\.)*)"""").findAll(body)
            .map { it.groupValues[1] }
            .toList()
        return SAnime.create().apply {
            this.title = title.ifBlank { slugToAnime(slug).title }
            url = "watch/$slug"
            thumbnail_url = "$baseUrl/thumbnail/$slug.webp"
            this.description = buildString {
                description?.let { append(it) }
                if (genres.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Genres: ").append(genres.joinToString(", "))
                }
            }.ifBlank { null }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val body = response.body?.string().orEmpty()
        val slug = response.request.url.pathSegments.lastOrNull { it.isNotBlank() }.orEmpty()
        val epName = Regex(""""videoname":"((?:[^"\\]|\\.)*)"""")
            .find(body)?.groupValues?.get(1)?.unescape()
            ?: slugToAnime(slug).title
        // Episode number: trailing " N" or "-N" in the slug/title.
        val number = Regex("""(\d+)\s*$""").find(epName)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
        return listOf(
            SEpisode.create().apply {
                url = "watch/$slug"
                name = epName
                episode_number = number
                setEpisodeField(this, "preview_url", "$baseUrl/thumbnail/$slug.webp")
            },
        )
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body?.string().orEmpty()
        val videos = mutableListOf<Video>()
        // Site's own player mirror: wN.hentaiocean.com/play?vid=NAME.mp4 -> /video/NAME.mp4
        Regex(""""mirrorurl":"((?:[^"\\]|\\.)*)"""").findAll(body).forEach { m ->
            val url = m.groupValues[1].replace("\\/", "/").replace("\\u002F", "/")
            val vid = url.substringAfter("vid=", "").takeIf { url.contains(".hentaiocean.com/play?") }
            if (vid != null) {
                val host = url.substringBefore("/play?").substringAfter("//")
                val direct = "https://$host/video/" + URLEncoder.encode(vid, "UTF-8")
                    .replace("+", "%20")
                videos.add(Video(direct, "MP4", direct, headers = videoHeaders(host)))
            } else if (url.contains(".hentaiocean.com/universal?")) {
                // Universal embed page - handled by the app's webview fallback
                videos.add(Video(url, "Universal", url, headers = headers))
            }
        }
        if (videos.isEmpty()) throw IOException("HentaiOcean: no playable mirror found")
        return videos
    }

    private fun videoHeaders(host: String): Headers = headers.newBuilder()
        .add("Referer", "https://$host/play")
        .build()

    // ============================== Helpers ===============================

    private fun slugToAnime(slug: String): SAnime = SAnime.create().apply {
        title = slug.replace('-', ' ').replace(Regex("""\b\w""")) { it.value.uppercase() }
        url = "watch/$slug"
        thumbnail_url = "$baseUrl/thumbnail/$slug.webp"
    }

    private fun String.unescape(): String =
        replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\\"", "\"")
            .replace("\\r\\n", "\n")
            .replace("\\\\", "\\")

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
        private const val PAGE_SIZE = 24

        private val RSS_ITEM_REGEX = Regex("<guid>([^<]+)</guid>")
        private val JSON_STRING_REGEX = Regex(""""(urlname|videoname)":"((?:[^"\\]|\\.)*)"""")
    }
}
