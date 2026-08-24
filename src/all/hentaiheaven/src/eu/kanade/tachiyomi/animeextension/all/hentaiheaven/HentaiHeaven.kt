package eu.kanade.tachiyomi.animeextension.all.hentaiheaven

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy

class HentaiHeaven : AnimeHttpSource() {

    override val name = "HentaiHeaven"

    override val baseUrl = "https://hentaiheaven.xxx"

    override val lang = "all"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    private val json: Json by injectLazy()

    // HentaiHeaven is a Hanime-family frontend: it shares the same
    // search + members API used by the other ("all") Hanime mirrors.
    private val apiBaseUrl = "https://members.hanime.tv/api/v8"
    private val searchApiUrl = "https://search.htv-services.workers.dev"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("X-Signature-Version", "web2")
        .add("Referer", "$baseUrl/")

    // ============================== Popular Anime ==============================
    override fun popularAnimeRequest(page: Int): Request {
        val payload = """
            {
                "search_text": "",
                "tags": [],
                "brands": [],
                "blacklist": [],
                "order_by": "views",
                "ordering": "desc",
                "page": ${page - 1}
            }
        """.trimIndent()
        val body = payload.toRequestBody("application/json".toMediaType())
        return POST(searchApiUrl, headers, body)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseSearchJson(response.body.string())
    }

    // ============================== Latest Updates ==============================
    override fun latestUpdatesRequest(page: Int): Request {
        val payload = """
            {
                "search_text": "",
                "tags": [],
                "brands": [],
                "blacklist": [],
                "order_by": "created_at_unix",
                "ordering": "desc",
                "page": ${page - 1}
            }
        """.trimIndent()
        val body = payload.toRequestBody("application/json".toMediaType())
        return POST(searchApiUrl, headers, body)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseSearchJson(response.body.string())
    }

    // ============================== Search ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val payload = """
            {
                "search_text": "$query",
                "tags": [],
                "brands": [],
                "blacklist": [],
                "order_by": "created_at_unix",
                "ordering": "desc",
                "page": ${page - 1}
            }
        """.trimIndent()
        val body = payload.toRequestBody("application/json".toMediaType())
        return POST(searchApiUrl, headers, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseSearchJson(response.body.string())
    }

    private fun parseSearchJson(jsonString: String): AnimesPage {
        val parsed = json.parseToJsonElement(jsonString).jsonObject
        val hitsRaw = parsed["hits"] ?: return AnimesPage(emptyList(), false)
        val animeList = mutableListOf<SAnime>()

        val hitsArray = try {
            if (hitsRaw.jsonPrimitive.isString) {
                json.parseToJsonElement(hitsRaw.jsonPrimitive.content).jsonArray
            } else {
                hitsRaw.jsonArray
            }
        } catch (e: Exception) {
            null
        }

        hitsArray?.forEach { element ->
            val obj = element.jsonObject
            val anime = SAnime.create().apply {
                val slug = obj["slug"]?.jsonPrimitive?.content ?: ""
                url = "/hentai-videos/$slug"
                title = obj["name"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = obj["cover_url"]?.jsonPrimitive?.content ?: obj["poster_url"]?.jsonPrimitive?.content
            }
            animeList.add(anime)
        }

        val page = parsed["page"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val nbPages = parsed["nbPages"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val hasNextPage = (page + 1) < nbPages

        return AnimesPage(animeList, hasNextPage)
    }

// ============================== Details ==============================
    override fun animeDetailsRequest(anime: SAnime): Request {
        val slug = anime.url.substringAfterLast("/")
        return GET("$apiBaseUrl/video?id=$slug", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val body = response.body.string()

        if (body.trimStart().startsWith("<")) {
            return parseAnimeDetailsFromHtml(body)
        }

        val jsonObj = json.parseToJsonElement(body).jsonObject
        val videoObj = jsonObj["hentai_video"]?.jsonObject ?: return SAnime.create()

        return SAnime.create().apply {
            title = videoObj["name"]?.jsonPrimitive?.content ?: ""
            thumbnail_url = videoObj["poster_url"]?.jsonPrimitive?.content
            description = videoObj["description"]?.jsonPrimitive?.content?.let {
                Jsoup.parse(it).text()
            }
            author = videoObj["brand"]?.jsonPrimitive?.content
            genre = videoObj["hentai_tags"]?.jsonArray?.joinToString {
                it.jsonObject["name"]?.jsonPrimitive?.content ?: ""
            }
            status = SAnime.COMPLETED
        }
    }

    private fun parseAnimeDetailsFromHtml(html: String): SAnime {
        val document = Jsoup.parse(html)
        return SAnime.create().apply {
            title = document.selectFirst("h1.tv-title")?.text() ?: ""
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.selectFirst("div.hvp-description")?.text()
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        return animeDetailsRequest(anime)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val body = response.body.string()
        var slug = ""

        if (!body.trimStart().startsWith("<")) {
            val jsonObj = json.parseToJsonElement(body).jsonObject
            val videoObj = jsonObj["hentai_video"]?.jsonObject
            slug = videoObj?.get("slug")?.jsonPrimitive?.content ?: ""
        }

        val episode = SEpisode.create().apply {
            name = "Episode 1"
            episode_number = 1f
            url = if (slug.isNotEmpty()) "/hentai-videos/$slug" else response.request.url.encodedPath
        }

        return listOf(episode)
    }

    // ============================== Video Streams ==============================
    override fun videoListRequest(episode: SEpisode): Request {
        val slug = episode.url.substringAfterLast("/")
        return GET("$apiBaseUrl/video?id=$slug", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body.string()
        val videoList = mutableListOf<Video>()

        if (body.trimStart().startsWith("<")) {
            return videoList
        }

        val jsonObj = json.parseToJsonElement(body).jsonObject
        val manifestObj = jsonObj["videos_manifest"]?.jsonObject ?: return videoList
        val serversArray = manifestObj["servers"]?.jsonArray ?: return videoList

        serversArray.forEach { serverElem ->
            val serverObj = serverElem.jsonObject
            val serverName = serverObj["name"]?.jsonPrimitive?.content ?: "HentaiHeaven"
            val streamsArray = serverObj["streams"]?.jsonArray ?: return@forEach

            streamsArray.forEach { streamElem ->
                val streamObj = streamElem.jsonObject
                val streamUrl = streamObj["url"]?.jsonPrimitive?.content ?: return@forEach
                val height = streamObj["height"]?.jsonPrimitive?.content ?: "720"

                if (streamUrl.isNotBlank()) {
                    val quality = "$serverName - ${height}p"
                    videoList.add(Video(streamUrl, quality, streamUrl))
                }
            }
        }

        return videoList.sortedByDescending { it.quality }
    }
}
