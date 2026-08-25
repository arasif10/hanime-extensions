/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaihaven

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * HentaiHaven (https://hentaihaven.xxx)
 *
 * Next.js frontend backed by a WordPress CMS (cms.hentaihaven.xxx).
 *
 * Catalogue, search, sorting and taxonomy filtering are all served by the site's
 * own JSON API at /api/manga/. Accepted params: page, per_page, search,
 * sort (views|rating|latest) and the taxonomy term IDs genre, tag, author, release.
 *
 * API quirks confirmed by probing the live site:
 *  - `sort` may only be combined with `genre`. Mixing it with `search`, `tag`,
 *    `author` or `release` returns HTTP 400 ("These filters cannot be combined
 *    with a catalogue sort"), so the sort is dropped in those cases.
 *  - Multiple taxonomy filters are AND-ed together.
 *  - per_page is capped at 48; filter IDs must be positive integers.
 *
 * Video streams: the watch page embeds an octopus stream UUID (a different one per
 * episode) and the HLS master playlist lives at
 * octopusmanifest.org/{uuid}/playlist.m3u8. Its video renditions are video-only
 * (fMP4/avc1) with audio and subtitles served as separate renditions, so every
 * quality entry has to ship the audio and subtitle tracks alongside it.
 */
class HentaiHaven : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "HentaiHaven"

    override val baseUrl = "https://hentaihaven.xxx"

    override val lang = "all"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val catalogueApiUrl = "$baseUrl/api/manga/"
    private val imageBaseUrl = "https://img.hentaihaven.xxx"

    private val pageSize = 24

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Catalogue (JSON API) ==============================

    private fun catalogueRequest(
        page: Int,
        sort: String? = null,
        search: String? = null,
        genre: Int? = null,
        tag: Int? = null,
        studio: Int? = null,
        year: Int? = null,
    ): Request {
        val url = catalogueApiUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("per_page", pageSize.toString())
            addQueryParameter("page", page.toString())
            if (!sort.isNullOrBlank()) addQueryParameter("sort", sort)
            if (!search.isNullOrBlank()) addQueryParameter("search", search)
            genre?.let { addQueryParameter("genre", it.toString()) }
            tag?.let { addQueryParameter("tag", it.toString()) }
            studio?.let { addQueryParameter("author", it.toString()) }
            year?.let { addQueryParameter("release", it.toString()) }
        }.build()
        return GET(url.toString(), headers)
    }

    private fun parseCatalogue(response: Response): AnimesPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1

        val root = json.parseToJsonElement(response.body.string()).jsonObject
        root["error"]?.jsonPrimitive?.content?.let { error ->
            throw Exception("HentaiHaven: $error")
        }
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
        thumbnail_url = hit["meta"]?.jsonObject?.get("vraven_remote_thumbnail")
            ?.jsonPrimitive?.content
            ?.toThumbnailUrl()
    }

    /**
     * The CMS returns either a full URL or a path relative to the image host, and
     * some paths contain literal spaces that have to be percent-encoded.
     */
    private fun String.toThumbnailUrl(): String? = takeIf { it.isNotBlank() }
        ?.replace(" ", "%20")
        ?.let { if (it.startsWith("http")) it else "$imageBaseUrl/${it.removePrefix("/")}" }

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
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selectedId
        val tag = filters.filterIsInstance<TagFilter>().firstOrNull()?.selectedId
        val studio = filters.filterIsInstance<StudioFilter>().firstOrNull()?.selectedId
        val year = filters.filterIsInstance<YearFilter>().firstOrNull()?.selectedId
        val trimmed = query.trim()

        // The API only accepts `sort` on its own or alongside `genre`; combining it
        // with a text search or with the tag/studio/year filters is a hard error.
        val sortable = trimmed.isEmpty() && tag == null && studio == null && year == null
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.sortValue
            ?.takeIf { sortable }

        return catalogueRequest(
            page = page,
            sort = sort,
            search = trimmed.ifEmpty { null },
            genre = genre,
            tag = tag,
            studio = studio,
            year = year,
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseCatalogue(response)

    // ============================== Filters ==============================

    private class SortFilter : AnimeFilter.Select<String>(
        "Sort by",
        arrayOf("Latest", "Most viewed", "Top rated"),
        0,
    ) {
        val sortValue: String
            get() = when (state) {
                1 -> "views"
                2 -> "rating"
                else -> "latest"
            }
    }

    /** Base class for the taxonomy dropdowns; state 0 always means "no filter". */
    private open class TermFilter(
        name: String,
        private val terms: Array<Pair<Int, String>>,
    ) : AnimeFilter.Select<String>(name, terms.map { it.second }.toTypedArray(), 0) {
        val selectedId: Int?
            get() = terms.getOrNull(state)?.first?.takeIf { it > 0 }
    }

    private class GenreFilter : TermFilter("Genre", GENRES)

    private class TagFilter : TermFilter("Tag", TAGS)

    private class StudioFilter : TermFilter("Studio", STUDIOS)

    private class YearFilter : TermFilter("Release year", YEARS)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("The filters below are combined (AND)"),
        GenreFilter(),
        TagFilter(),
        StudioFilter(),
        YearFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Sorting applies to plain browsing and to Genre only - a text search or a Tag/Studio/Year filter replaces it"),
    )

    // ============================== Anime Details ==============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string(), baseUrl)

        return SAnime.create().apply {
            // og:title carries the clean title plus a fixed site suffix.
            title = document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - Hentai Haven")
                ?.trim()
                .orEmpty()
                .ifEmpty { document.selectFirst("h1")?.text().orEmpty() }

            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

            // The real per-title synopsis is the line-clamped paragraph in the info
            // block; the meta description is boilerplate SEO text, so it is not used.
            description = document.selectFirst("p[class*=line-clamp]")?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            // Genres are the /series/ chips in the info block. Other /series/ links on
            // the page belong to related-title cards, hence the badge restriction.
            genre = document.select("a[data-slot=badge][href^=\"/series/\"]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
                .takeIf { it.isNotBlank() }

            author = document.selectFirst("a[data-slot=badge][href^=\"/studio/\"]")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }

            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val watchPath = response.request.url.encodedPath
        // Only episodes of THIS title: the watch page also lists related titles,
        // whose cards link to other anime's episodes.
        val currentSlug = SLUG_REGEX.find(watchPath)?.groupValues?.get(1)
            ?: return emptyList()
        val document = Jsoup.parse(response.body.string(), baseUrl)

        val episodes = document.select("a[href^=\"/watch/$currentSlug/episode-\"]")
            .mapNotNull { anchor ->
                val number = EPISODE_REGEX.find(anchor.attr("href"))
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                SEpisode.create().apply {
                    url = "/watch/$currentSlug/episode-$number/"
                    name = "Episode $number"
                    episode_number = number.toFloat()
                    date_upload = anchor.episodeDate()
                }
            }
            .groupBy { it.url }
            // Each episode appears twice: as a card (which carries the dated thumbnail)
            // and as a "Watch Ep" button (which does not). Keep the dated one.
            .map { (_, duplicates) -> duplicates.maxBy { it.date_upload } }
            .sortedByDescending { it.episode_number }

        return episodes.ifEmpty {
            listOf(
                SEpisode.create().apply {
                    url = watchPath
                    name = "Episode 1"
                    episode_number = 1f
                },
            )
        }
    }

    /**
     * Episode cards embed a thumbnail served from a dated storage path
     * (.../storage/2026/08/20/slug-6/s_thumbnail.webp) which is the episode's
     * publish date. Returns 0 when the anchor carries no thumbnail.
     */
    private fun Element.episodeDate(): Long {
        val src = selectFirst("img[src]")?.attr("src") ?: return 0L
        val match = THUMB_DATE_REGEX.find(src) ?: return 0L
        return runCatching { DATE_FORMATTER.parse(match.groupValues[1])?.time }.getOrNull() ?: 0L
    }

    // ============================== Video Streams ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        // The watch page embeds the octopus stream UUID, so fetch it to resolve the
        // HLS manifest on octopusmanifest.org.
        val url = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
        return GET(url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        if (!response.isSuccessful) {
            throw Exception(
                "HentaiHaven: failed to fetch the watch page (HTTP ${response.code}). " +
                    "If this persists, the site's video CDN may be blocking your region.",
            )
        }

        val uuid = UUID_REGEX.find(response.body.string())?.value
            ?: throw Exception("HentaiHaven: no video source found for this episode")

        return videosFromMaster("https://octopusmanifest.org/$uuid/playlist.m3u8")
    }

    /**
     * Parses the HLS master playlist so each rendition becomes its own quality entry.
     * The video renditions carry no audio, so the separate audio and subtitle
     * renditions are attached to every entry.
     */
    private fun videosFromMaster(masterUrl: String): List<Video> {
        val playbackHeaders = videoHeaders()
        val playlist = runCatching {
            client.newCall(GET(masterUrl, playbackHeaders)).execute().use { res ->
                if (res.isSuccessful) res.body.string() else null
            }
        }.getOrNull()

        // Hand the master playlist straight to the player if the manifest cannot be
        // read (for example a transient CDN error) - the player can parse it itself.
        if (playlist.isNullOrBlank()) {
            return listOf(Video(masterUrl, "Auto", masterUrl, headers = playbackHeaders))
        }

        val mediaTracks = MEDIA_REGEX.findAll(playlist).mapNotNull { match ->
            val attributes = match.groupValues[1]
            val type = attributes.hlsAttr("TYPE") ?: return@mapNotNull null
            val uri = attributes.hlsAttr("URI") ?: return@mapNotNull null
            val name = attributes.hlsAttr("NAME") ?: attributes.hlsAttr("LANGUAGE") ?: type
            type to Track(masterUrl.resolveUri(uri), name)
        }.toList()

        val audioTracks = mediaTracks.filter { it.first == "AUDIO" }.map { it.second }
        val subtitleTracks = mediaTracks.filter { it.first == "SUBTITLES" }.map { it.second }

        val videos = STREAM_INF_REGEX.findAll(playlist).mapNotNull { match ->
            val attributes = match.groupValues[1]
            val uri = match.groupValues[2].trim().ifEmpty { return@mapNotNull null }
            val videoUrl = masterUrl.resolveUri(uri)
            Video(
                videoUrl,
                attributes.qualityLabel(),
                videoUrl,
                headers = playbackHeaders,
                subtitleTracks = subtitleTracks,
                audioTracks = audioTracks,
            )
        }.toList()

        return videos.ifEmpty {
            listOf(
                Video(
                    masterUrl,
                    "Auto",
                    masterUrl,
                    headers = playbackHeaders,
                    subtitleTracks = subtitleTracks,
                    audioTracks = audioTracks,
                ),
            )
        }
    }

    /** "1280x720" -> "720p", falling back to the bitrate when there is no resolution. */
    private fun String.qualityLabel(): String =
        hlsAttr("RESOLUTION")?.substringAfter('x', "")?.takeIf { it.isNotBlank() }?.let { "${it}p" }
            ?: hlsAttr("BANDWIDTH")?.toLongOrNull()?.let { "${it / 1000} kbps" }
            ?: "Auto"

    /** Reads a quoted or unquoted attribute out of an HLS attribute list. */
    private fun String.hlsAttr(name: String): String? =
        Regex("""(?:^|[,:])$name=(?:"([^"]*)"|([^,"]*))""").find(this)
            ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            ?.takeIf { it.isNotBlank() }

    private fun String.resolveUri(uri: String): String =
        if (uri.startsWith("http")) uri else toHttpUrl().resolve(uri)?.toString() ?: uri

    override fun List<Video>.sort(): List<Video> {
        val preferred = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(preferred, true) }
                .thenByDescending { it.quality.videoHeight() },
        )
    }

    private fun String.videoHeight(): Int =
        HEIGHT_REGEX.find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    // Playback headers: the video CDN expects a browser-like Referer/Origin.
    private fun videoHeaders(): Headers = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .add("Accept", "*/*")
        .build()

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = PREF_QUALITY_VALUES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    private companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_VALUES = arrayOf("1080p", "720p", "480p", "360p")

        private val SLUG_REGEX = Regex("""/watch/([^/]+)""")
        private val EPISODE_REGEX = Regex("""episode-(\d+)""")
        private val HEIGHT_REGEX = Regex("""(\d+)p""")

        // Thumbnails live under /storage/<yyyy>/<MM>/<dd>/<slug-episode>/…
        private val THUMB_DATE_REGEX = Regex("""/storage/(\d{4}/\d{2}/\d{2})/""")

        private val UUID_REGEX =
            Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}""")

        private val MEDIA_REGEX = Regex("""#EXT-X-MEDIA:(.+)""")
        private val STREAM_INF_REGEX = Regex("""#EXT-X-STREAM-INF:(.+)\r?\n(.+)""")

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy/MM/dd", Locale.ENGLISH)
        }

        // wp-manga-genre term IDs, from cms.hentaihaven.xxx/wp-json/wp/v2/wp-manga-genre
        private val GENRES = arrayOf(
            0 to "Any genre",
            2245 to "3D Hentai",
            11709 to "Ahegao",
            222 to "Anal",
            2433 to "BBW",
            133 to "BDSM",
            252 to "Beastiality",
            11713 to "Big Boobs",
            11719 to "Big Breasts",
            11730 to "Big Tits",
            11711 to "Blow Job",
            11720 to "Blowjob",
            11712 to "Censored",
            11729 to "Cheating",
            11725 to "Comedy",
            11726 to "Corruption",
            11710 to "Creampie",
            2594 to "Ecchi",
            11734 to "Elf",
            11738 to "Erotic Game",
            11718 to "Exhibitionism",
            11716 to "Fantasy",
            2673 to "FemBoy",
            2676 to "Femdom",
            165 to "Furry",
            229 to "Futanari",
            2582 to "Gender Bender Hentai",
            11740 to "Group Sex",
            11736 to "Gyaru",
            105 to "Harem",
            11721 to "HD",
            11724 to "Hentai",
            815 to "Hentai School",
            1606 to "Horror",
            11735 to "Impregnation",
            64 to "Incest Hentai",
            11715 to "Masturbation",
            208 to "Milf",
            11733 to "Mind Break",
            290 to "Monster",
            11728 to "NTR",
            11717 to "Nudity",
            11727 to "Office Lady",
            11722 to "Paizuri",
            65 to "Rape",
            103 to "Romance",
            11714 to "School",
            11732 to "Schoolgirl",
            11731 to "Sex Toys",
            2627 to "Softcore",
            11737 to "Teasing",
            66 to "Teen Hentai",
            244 to "Tentacle",
            106 to "Tsundere",
            2605 to "Umemaro 3D",
            107 to "Uncensored Hentai",
            104 to "Yaoi",
            67 to "Young Hentai",
            102 to "Yuri",
        )

        // wp-manga-author term IDs (the site labels these "Studio")
        private val STUDIOS = arrayOf(
            0 to "Any studio",
            1709 to "@Oz",
            1301 to "Amour",
            2200 to "Animac",
            1027 to "Arms",
            2427 to "BOMB! CUTE! BOMB!",
            1048 to "Bootleg",
            199 to "Bunnywalker",
            2576 to "Central Park Media",
            936 to "ChiChinoya",
            209 to "Collaboration Works",
            2243 to "Comic Media",
            1710 to "Digital Works",
            883 to "Discovery",
            223 to "Edge",
            816 to "Five Ways",
            2447 to "GOLD BEAR",
            1019 to "Green Bunny",
            2130 to "Hoods Entertainment",
            2421 to "Hot Bear",
            2762 to "Jellyfish",
            2620 to "King Bee",
            1146 to "Lune Pictures",
            1939 to "Magic Bus",
            134 to "Magin Label",
            2349 to "Majin Petit",
            2153 to "Marigold",
            301 to "Mary Jane",
            228 to "MediaBank",
            782 to "Milky",
            231 to "MS Pictures",
            2581 to "Nihikime no Dozeu",
            2579 to "nur",
            2601 to "NuTech Digital",
            923 to "Pashmina",
            167 to "Pink Pineapple",
            262 to "Pixy Soft",
            69 to "PoRO",
            472 to "Queen Bee",
            2641 to "Rabbit Gate",
            1035 to "Schoolzone",
            275 to "SELFISH",
            2631 to "Seven",
            1714 to "Showten",
            2645 to "Soft on Demand",
            920 to "Studio 9 Maiami",
            2592 to "Studio FOW",
            1711 to "Studio Hokiboshi",
            2678 to "Suiseisha",
            864 to "Suzuki Mirano",
            2663 to "t japan",
            1041 to "T-Rex",
            2634 to "Toranoana",
            2829 to "Torudaya",
            2608 to "Umemaro 3D",
            2544 to "Valkyria",
            829 to "Vanilla",
            2583 to "White Bear",
            2644 to "Y.O.U.C",
            345 to "ZIZ",
        )

        // wp-manga-release term IDs
        private val YEARS = arrayOf(
            0 to "Any year",
            11708 to "2026",
            11700 to "2025",
            11308 to "2024",
            2681 to "2023",
            2656 to "2022",
            2593 to "2021",
            1930 to "2020",
            879 to "2019",
            198 to "2018",
            142 to "2017",
            113 to "2016",
            111 to "2015",
            166 to "2014",
            112 to "2013",
            361 to "2012",
            114 to "2011",
            110 to "2010",
            68 to "2009",
            261 to "2008",
            318 to "2007",
            1199 to "2006",
            1026 to "2005",
            882 to "2004",
            1018 to "2003",
            1118 to "2002",
            781 to "2001",
            2610 to "2000",
            828 to "1999",
            2643 to "1998",
            1579 to "1997",
            1614 to "1996",
            2604 to "1995",
            2759 to "1994",
            2575 to "1992",
            2625 to "1991",
        )

        // wp-manga-tag term IDs. The taxonomy also holds ~30 SEO tags applied to the
        // whole catalogue ("Hentai Stream", "nHentai", ...) plus near-duplicates; only
        // the useful content tags are listed here.
        private val TAGS = arrayOf(
            0 to "Any tag",
            2249 to "3D",
            176 to "Ahegao",
            225 to "Anal",
            153 to "Ass",
            135 to "BDSM",
            155 to "Big Ass",
            144 to "Big Boobs",
            76 to "Big Tits",
            207 to "Black Women",
            2689 to "Blackmail",
            87 to "Blow Job",
            139 to "Bondage",
            1365 to "Boob Job",
            2623 to "Breasts",
            2695 to "Bukkake",
            2635 to "Cheating",
            177 to "Cosplay",
            205 to "Cum in Pussy",
            363 to "Demon",
            2688 to "Doggy Style",
            2685 to "Dominatrix",
            362 to "Elf",
            2693 to "Erotic Asphyxiation",
            2721 to "Erotic Game",
            2683 to "Exhibitionism",
            136 to "Extreme",
            148 to "Facial",
            172 to "Fantasy",
            2666 to "Femdom",
            1500 to "Foot Job",
            175 to "Furry",
            230 to "Futanari",
            233 to "Gangbang",
            2070 to "Gay",
            820 to "Glasses",
            403 to "Hair Pussy",
            819 to "Hand Job",
            77 to "Harem",
            2758 to "High School",
            256 to "Horror",
            33 to "Incest",
            243 to "Inflation",
            2704 to "Internal Shots",
            364 to "Interracial",
            214 to "Lactation",
            2725 to "Large Breasts",
            868 to "Lesbian",
            2690 to "Lingerie",
            922 to "Little Girl",
            84 to "Maid",
            2702 to "Mammary Intercourse",
            1902 to "Massage",
            81 to "Masturbation",
            147 to "Mature",
            154 to "Milf",
            2652 to "Milk",
            1931 to "Mind Break",
            88 to "Mind Control",
            2692 to "Mother-Son Incest",
            247 to "Monster",
            224 to "NTR",
            2691 to "Nudity",
            937 to "Nurse",
            203 to "Oral Sex",
            204 to "Orgy",
            2654 to "Paizuri",
            297 to "POV",
            294 to "Pregnant",
            171 to "Public Sex",
            70 to "Rape",
            810 to "Redhead",
            255 to "Reverse Rape",
            2469 to "Riding",
            1278 to "RimJob",
            78 to "Romance",
            83 to "School",
            74 to "School Girl",
            2682 to "Sexual Fantasies",
            79 to "Shotacon",
            1829 to "Sister",
            309 to "Small Tits",
            2637 to "Softcore",
            2438 to "Squirt",
            2694 to "Submission",
            818 to "Swimsuit",
            2730 to "Teacher x Student",
            108 to "Teens",
            246 to "Tentacle",
            293 to "Tentacle Rape",
            2400 to "Threesome",
            82 to "Tits",
            170 to "Toys",
            1390 to "Trap",
            865 to "Ugly Bastard",
            7 to "Uncensored",
            830 to "Vanilla",
            2679 to "Violation",
            80 to "Virgin",
            426 to "Warrior",
            206 to "Wet Pussy",
            1601 to "X Ray",
            85 to "Yaoi",
            72 to "Young",
            75 to "Yuri",
        )
    }
}
