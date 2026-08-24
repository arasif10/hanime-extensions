/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaiheaven

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy

/**
 * HentaiHaven (https://hentaihaven.xxx)
 *
 * Next.js frontend backed by a WordPress CMS (cms.hentaihaven.xxx).
 * Catalogue, search and sorting are served by the site's own JSON API at
 * /api/manga/ (params: page, per_page, search, sort, trending_period, live).
 * Genre listings are server-rendered pages at /series/<genre-slug>/.
 *
 * Video streams: POST /api/stream/ with the video slug + its CMS permalink
 * returns HLS manifests. The site sits behind Cloudflare, so requests go
 * through the app's cloudflareClient.
 */
class HentaiHeaven : AnimeHttpSource() {

    override val name = "HentaiHeaven"

    override val baseUrl = "https://hentaiheaven.xxx"

    override val lang = "all"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val catalogueApiUrl = "$baseUrl/api/manga/"
    private val streamApiUrl = "$baseUrl/api/stream/"
    private val cmsBaseUrl = "https://cms.hentaihaven.xxx"
    private val imageBaseUrl = "https://img.hentaihaven.xxx"

    private val pageSize = 24

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Catalogue (JSON API) ==============================

    private fun catalogueRequest(page: Int, sort: String? = null, search: String? = null): Request {
        val url = catalogueApiUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("per_page", pageSize.toString())
            addQueryParameter("page", page.toString())
            if (!sort.isNullOrBlank()) addQueryParameter("sort", sort)
            if (!search.isNullOrBlank()) addQueryParameter("search", search)
        }.build()
        return GET(url.toString(), headers)
    }

    private fun parseCatalogue(response: Response): AnimesPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1

        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val entries = root["data"]?.jsonArray ?: return AnimesPage(emptyList(), false)
        val animeList = entries.mapNotNull { entry ->
            runCatching { hitToAnime(entry.jsonObject) }.getOrNull()
        }
        val totalPages = root["totalPages"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        return AnimesPage(animeList, page < totalPages)
    }

    private fun hitToAnime(hit: JsonObject): SAnime = SAnime.create().apply {
        val slug = hit["slug"]?.jsonPrimitive?.content ?: ""
        url = "/watch/$slug/"
        title = hit["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content ?: slug
        // Thumbnail: use vraven_remote_thumbnail from CMS, fallback to empty
        val thumbPath = hit["meta"]?.jsonObject?.get("vraven_remote_thumbnail")
            ?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?.let { "$imageBaseUrl/${it.removePrefix("/")}" }
        thumbnail_url = thumbPath ?: ""
        initialized = true
    }

    // ============================== Popular Anime ==============================

    override fun popularAnimeRequest(page: Int): Request =
        catalogueRequest(page, sort = "views")

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseCatalogue(response)

    // ============================== Latest Updates ==============================

    override fun latestUpdatesRequest(page: Int): Request =
        catalogueRequest(page, sort = "latest")

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseCatalogue(response)

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selected
        if (genre != null) {
            return GET("$baseUrl/series/$genre/", headers)
        }

        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.sortValue
        val trimmed = query.trim()
        return catalogueRequest(page, sort = sort, search = trimmed.ifEmpty { null })
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.pathSegments.firstOrNull() == "series") {
            return parseSeriesPage(response)
        }
        return parseCatalogue(response)
    }

    // Genre pages are server-rendered HTML without pagination.
    private fun parseSeriesPage(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val animeList = document.select("a[href^=\"/watch/\"] img[alt]").mapNotNull { img ->
            val link = img.closest("a[href]") ?: return@mapNotNull null
            val href = link.attr("href")
            val slug = href.substringAfter("/watch/").trimEnd('/').takeIf { it.isNotBlank() && !it.contains('/') }
                ?: return@mapNotNull null
            SAnime.create().apply {
                url = "/watch/$slug/"
                title = img.attr("alt").trim()
                thumbnail_url = img.absUrl("src").takeIf { it.isNotBlank() }
                initialized = true
            }
        }.distinctBy { it.url }
        return AnimesPage(animeList, false)
    }

    // ============================== Filters ==============================

    private class SortFilter : AnimeFilter.Select<String>(
        "Sort by",
        arrayOf("Most viewed", "Top rated", "Trending", "Newest", "Alphabetical"),
        0,
    ) {
        val sortValue: String
            get() = when (state) {
                1 -> "rating"
                2 -> "trending"
                3 -> "latest"
                4 -> "views"
                else -> "alpha"
            }
    }

    private class GenreFilter(genres: Array<Pair<String, String>>) : AnimeFilter.Select<String>(
        "Genre",
        genres.map { it.second }.toTypedArray(),
        0,
    ) {
        private val genreList = genres

        val selected: String?
            get() = if (state == 0) null else genreList[state].first
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Genre browsing ignores text search and sorting"),
        SortFilter(),
        GenreFilter(GENRE_LIST),
    )

    // ============================== Anime Details ==============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string(), baseUrl)

        // Title: og:title strips the " - Hentai Haven | Watch free Hentai HD" suffix
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" - Hentai Haven")
            ?: document.selectFirst("h1")?.text()
            ?: ""

        // Thumbnail: og:image
        val thumbnail = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: ""

        // Description: meta description
        val description = document.selectFirst("meta[name=description]")?.attr("content")

        // Genre: all series link texts
        val genre = document.select("a[href^=\"/series/\"]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

        // Author/Studio: find Studio badge
        val author = document.selectFirst("span:containsOwn(Studio)")?.nextElementSibling()
            ?.let { node ->
                if (node instanceof org.jsoup.nodes.Element) {
                    node.selectFirst("a")?.text()?.takeIf { it.isNotBlank() }
                        ?: node.text()?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }?.takeIf { it.isNotBlank() }
            ?: ""

        // Release date: from the "Released" badge
        val releasedText = document.select("span:containsOwn(Released)")?.text()
        val releaseDate = releasedText
            ?.replace("Released ", "")
            ?.takeWhile { it != ',' && it != '(' }
            ?.trim()
            ?: ""

        return SAnime.create().apply {
            this.title = title
            this.thumbnail_url = thumbnail
            this.description = description
            this.genre = genre
            this.author = author
            this.status = SAnime.COMPLETED
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val watchPath = response.request.url.encodedPath

        // Find episode links within the watch page's episode container
        val episodes = document.select("a[href*=\"episode-\"]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href")
                val number = Regex("episode-(\\d+)").find(href)?.groupValues?.get(1)
                    ?.toFloatOrNull() ?: return@mapNotNull null
                // Only accept episodes if the href is under the current watch path
                val slug = Regex("/watch/([^/]+)/episode-").find(href)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                SEpisode.create().apply {
                    url = "/watch/$slug/episode-${number.toInt()}/"
                    name = "Episode ${number.toInt()}"
                    episode_number = number
                }
            }
            // Deduplicate by episode number and sort descending (newest first)
            .distinctBy { it.episode_number }
            .sortedByDescending { it.episode_number }

        return episodes.ifEmpty {
            // Fallback: single episode from the watch page URL
            listOf(
                SEpisode.create().apply {
                    url = watchPath
                    name = "Episode 1"
                    episode_number = 1f
                },
            )
        }
    }

    // ============================== Video Streams ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        val slug = Regex("/watch/([^/]+)").find(episode.url)?.groupValues?.get(1)
            ?: episode.url.trimEnd('/').substringAfterLast('/')
        val payload = buildJsonObject {
            put("slug", slug)
            put("permalink", "$cmsBaseUrl/watch/$slug/")
        }.toString()
        val body = payload.toRequestBody("application/json".toMediaType())
        return POST(streamApiUrl, headers, body)
    }

    override fun videoListParse(response: Response): List<Video> {
        if (!response.isSuccessful) {
            throw Exception("HentaiHaven: failed to fetch stream manifest (HTTP ${response.code})")
        }

        val root = json.parseToJsonElement(response.body.string()).jsonObject
        if (root["status"]?.jsonPrimitive?.content != "true") {
            throw Exception("HentaiHaven: no video sources available for this title")
        }
        val data = root["data"]?.jsonObject ?: return emptyList()

        val videos = mutableListOf<Video>()
        val seen = mutableSetOf<String>()

        fun addSources(key: String) {
            data[key]?.jsonArray?.forEach { sourceElem ->
                val source = runCatching { sourceElem.jsonObject }.getOrNull() ?: return@forEach
                val streamUrl = source["src"]?.jsonPrimitive?.content ?: return@forEach
                if (streamUrl.isBlank() || !seen.add(streamUrl)) return@forEach
                val label = source["label"]?.jsonPrimitive?.content ?: "Auto"
                videos.add(Video(streamUrl, label, streamUrl))
            }
        }

        addSources("sources")
        addSources("fallbackSources")

        if (videos.isEmpty()) throw Exception("HentaiHeaven: no video sources available for this title")
        return videos
    }

    private companion object {
        // (slug, display name) pairs from the site's genre taxonomy (wp-manga-genre).
        private val GENRE_LIST = arrayOf(
            "any" to "Any genre",
            "3d-hentai" to "3D Hentai",
            "ahegao" to "Ahegao",
            "anal" to "Anal",
            "bbw" to "BBW",
            "bdsm" to "BDSM",
            "beastiality" to "Beastiality",
            "big-boobs" to "Big Boobs",
            "big-breasts" to "Big Breasts",
            "big-tits" to "Big Tits",
            "blow-job" to "Blow Job",
            "blowjob" to "Blowjob",
            "censored" to "Censored",
            "cheating" to "Cheating",
            "comedy" to "Comedy",
            "corruption" to "Corruption",
            "creampie" to "Creampie",
            "ecchi" to "Ecchi",
            "elf" to "Elf",
            "erotic-game" to "Erotic Game",
            "exhibitionism" to "Exhibitionism",
            "fantasy" to "Fantasy",
            "femboy" to "Femboy",
            "femdom" to "Femdom",
            "furry" to "Furry",
            "futanari" to "Futanari",
            "gender-bender-hentai" to "Gender Bender",
            "group-sex" to "Group Sex",
            "gyaru" to "Gyaru",
            "harem" to "Harem",
            "hd" to "HD",
            "hentai" to "Hentai",
            "hentai-school" to "Hentai School",
            "horror" to "Horror",
            "impregnation" to "Impregnation",
            "incest" to "Incest",
            "masturbation" to "Masturbation",
            "milf" to "MILF",
            "mind-break" to "Mind Break",
            "monster" to "Monster",
            "ntr" to "NTR",
            "nudity" to "Nudity",
            "office-lady" to "Office Lady",
            "paizuri" to "Paizuri",
            "rape" to "Rape",
            "romance" to "Romance",
            "school" to "School",
            "schoolgirl" to "Schoolgirl",
            "sex-toys" to "Sex Toys",
            "softcore" to "Softcore",
            "teasing" to "Teasing",
            "teen-hentai" to "Teen",
            "tentacle" to "Tentacle",
            "tsundere" to "Tsundere",
            "umemaro-3d" to "Umemaro 3D",
            "uncensored" to "Uncensored",
            "yaoi" to "Yaoi",
            "young" to "Young",
            "yuri" to "Yuri",
        )
    }
}
