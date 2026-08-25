/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.oppaistream

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
import org.jsoup.nodes.Element

/**
 * OppaiStream (https://oppai.stream)
 *
 * A hentai streaming site. The catalogue/search/latest are served by an HTML
 * fragment API at /actions/search.php (params: text, order, page, limit,
 * genres, blacklist, studio). Each search result card is one EPISODE of a
 * series; episodes of the same series share the same `name` attribute.
 *
 * The library groups those cards by series name, so each unique title becomes
 * one anime entry. The episode list is rebuilt by re-querying search.php with
 * the series title as the search text (which returns every episode of it).
 *
 * Video streams: the watch page embeds a direct MP4 source plus a `vsource`
 * object with DASH MPD URLs at 720p/1080p/4K. Availability is signalled by
 * `if("true" == "true")` guards in the page's JS. The video CDN
 * (myspacecat.pictures) requires a Referer header.
 */
class OppaiStream : AnimeHttpSource() {

    override val name = "OppaiStream"

    override val baseUrl = "https://oppai.stream"

    override val lang = "all"

    override val supportsLatest = true

    private val pageSize = 24

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Catalogue (search.php fragment API) ==============================

    private fun catalogueRequest(
        page: Int,
        sort: String? = null,
        search: String? = null,
        genres: List<String> = emptyList(),
        blacklist: List<String> = emptyList(),
        studio: String? = null,
    ): Request {
        val url = "$baseUrl/actions/search.php".toHttpUrl().newBuilder().apply {
            addQueryParameter("text", search.orEmpty())
            addQueryParameter("order", sort.orEmpty())
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", pageSize.toString())
            addQueryParameter("genres", genres.joinToString(","))
            addQueryParameter("blacklist", blacklist.joinToString(","))
            addQueryParameter("studio", studio.orEmpty())
            addQueryParameter("ibt", "0")
            addQueryParameter("swa", "0")
        }.build()
        return GET(url.toString(), headers)
    }

    /** A single episode card from the search results. */
    private class EpisodeCard(
        val seriesName: String,
        val episode: Int,
        val watchPath: String,
        val folder: String,
        val thumbnail: String?,
        val description: String?,
        val tags: List<String>,
        val studio: String?,
    )

    private fun parseCards(response: Response): Pair<List<EpisodeCard>, Boolean> {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val cards = document.select("div.episode-shown").mapNotNull { card ->
            runCatching { cardToEpisode(card) }.getOrNull()
        }

        // Total count is embedded in a hidden div: <div id='amount-full' amo='1872'>
        val total = document.selectFirst("#amount-full")?.attr("amo")?.toIntOrNull()
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNext = total == null || page * pageSize < total
        return cards to hasNext
    }

    private fun cardToEpisode(card: Element): EpisodeCard {
        val seriesName = card.attr("name").trim().ifEmpty {
            card.selectFirst("font.title")?.text()?.trim() ?: ""
        }
        val episode = card.attr("ep").toIntOrNull() ?: 0
        val href = card.selectFirst("a[href*='watch']")?.attr("href") ?: ""
        // Normalise to a relative path: /watch?e=...
        val watchPath = href.substringAfter(baseUrl).substringBefore("&for=").ifEmpty { href }
        val folder = card.attr("folder")
        val thumbnail = card.selectFirst("img.cover-img-in")?.let { img ->
            img.attr("original").ifBlank { img.attr("src") }.ifBlank { null }
        }?.takeIf { it.startsWith("http") }
        val description = card.attr("desc").trim().ifBlank { null }
        val tags = card.attr("tags").split(",").map { it.trim() }.filter { it.isNotBlank() }
        val studio = card.selectFirst("a[href*='studio=']")?.text()?.trim()?.ifBlank { null }
        return EpisodeCard(seriesName, episode, watchPath, folder, thumbnail, description, tags, studio)
    }

    private fun cardsToAnimeList(cards: List<EpisodeCard>): List<SAnime> {
        // Group episode cards by series name -> one anime entry per series.
        return cards.groupBy { it.seriesName }.map { (name, eps) ->
            val first = eps.minByOrNull { it.episode } ?: return@map null
            SAnime.create().apply {
                title = name
                url = first.watchPath
                thumbnail_url = first.thumbnail
                description = first.description
                genre = first.tags.joinToString(", ").ifBlank { null }
                author = first.studio
                status = SAnime.COMPLETED
                initialized = true
            }
        }.filterNotNull()
    }

    // ============================== Popular Anime ==============================

    override fun popularAnimeRequest(page: Int): Request =
        catalogueRequest(page, sort = "views")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val (cards, hasNext) = parseCards(response)
        return AnimesPage(cardsToAnimeList(cards), hasNext)
    }

    // ============================== Latest Updates ==============================

    override fun latestUpdatesRequest(page: Int): Request =
        catalogueRequest(page, sort = "uploaded")

    override fun latestUpdatesParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val flatFilters = filters.flatMap { filter ->
            if (filter is AnimeFilter.Group<*>) {
                filter.state.filterIsInstance<AnimeFilter<*>>()
            } else {
                listOf(filter)
            }
        }
        val sort = flatFilters.filterIsInstance<SortFilter>().firstOrNull()?.sortValue
        val includedGenres = flatFilters.filterIsInstance<GenreFilter>()
            .filter { it.state == AnimeFilter.TriState.STATE_INCLUDE }
            .map { it.name.lowercase() }
        val excludedGenres = flatFilters.filterIsInstance<GenreFilter>()
            .filter { it.state == AnimeFilter.TriState.STATE_EXCLUDE }
            .map { it.name.lowercase() }
        val studio = flatFilters.filterIsInstance<StudioFilter>()
            .firstOrNull { it.state == AnimeFilter.TriState.STATE_INCLUDE }
            ?.name
        return catalogueRequest(
            page,
            sort = sort,
            search = query.trim(),
            genres = includedGenres,
            blacklist = excludedGenres,
            studio = studio,
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // ============================== Filters ==============================

    private class FilterGroup(name: String, vararg filters: AnimeFilter<*>) :
        AnimeFilter.Group<AnimeFilter<*>>(name, filters.toList())

    private class GenreFilter(name: String) : AnimeFilter.TriState(name, AnimeFilter.TriState.STATE_IGNORE)

    private class StudioFilter(name: String) : AnimeFilter.TriState(name, AnimeFilter.TriState.STATE_IGNORE)

    private class SortFilter : AnimeFilter.Select<String>(
        "Sort by",
        arrayOf("Newest", "Most viewed", "Top rated", "A-Z", "Z-A", "Oldest", "Random"),
        0,
    ) {
        val sortValue: String?
            get() = when (state) {
                0 -> "uploaded"
                1 -> "views"
                2 -> "rating"
                3 -> "az"
                4 -> "za"
                5 -> "old"
                6 -> "random"
                else -> null
            }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        // Groups render as collapsible sections in AniZen (closed by default),
        // matching the user's other extensions.
        FilterGroup("Genres", *GENRE_LIST.map { GenreFilter(it.second) }.toTypedArray()),
        FilterGroup("Studios", *STUDIO_LIST.map { StudioFilter(it.second) }.toTypedArray()),
        AnimeFilter.Header("Sorting"),
        SortFilter(),
    )

    // ============================== Anime Details ==============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val card = document.selectFirst("div.episode-shown")

        val title = card?.attr("name")?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" EP ")?.substringBefore(" in HD")
            ?: ""

        val thumbnail = card?.selectFirst("img.cover-img-in")?.let { img ->
            img.attr("original").ifBlank { img.attr("src") }
        }?.takeIf { it.startsWith("http") }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = card?.attr("desc")?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val tags = card?.attr("tags")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        val studio = card?.selectFirst("a[href*='studio=']")?.text()?.trim()?.ifBlank { null }

        return SAnime.create().apply {
            this.title = title
            this.thumbnail_url = thumbnail
            this.description = description
            this.genre = tags?.joinToString(", ")
            this.author = studio
            this.status = SAnime.COMPLETED
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        // Re-query the search API with the series title; it returns every
        // episode card of that series (the watch page only shows ~6).
        return catalogueRequest(1, search = anime.title, sort = null)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val title = response.request.url.queryParameter("text") ?: return emptyList()
        val (cards, _) = parseCards(response)
        return cards
            .filter { it.seriesName == title }
            .distinctBy { it.episode }
            .sortedByDescending { it.episode }
            .map { card ->
                SEpisode.create().apply {
                    url = card.watchPath
                    name = "Episode ${card.episode}"
                    episode_number = card.episode.toFloat()
                }
            }
    }

    // ============================== Video Streams ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        val url = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
        return GET(url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        if (!response.isSuccessful) {
            throw Exception(
                "OppaiStream: failed to fetch the watch page (HTTP ${response.code}). " +
                    "If this persists, the site may be blocking your region.",
            )
        }
        val html = response.body.string()
        val document = Jsoup.parse(html, baseUrl)

        // Direct 720p MP4 source (always present, the site's fallback).
        val sourceUrl = document.selectFirst("video#episode source")?.attr("src") ?: ""
        if (sourceUrl.isBlank()) {
            throw Exception("OppaiStream: no video source found for this episode")
        }
        val folder = sourceUrl.substringBefore("/720/").substringAfter("pictures/")
        val epFile = sourceUrl.substringAfterLast('/')

        // Available resolutions from the vsource block:
        //   vsource = { "r-720":"...", "r-1080":"", "r-4k":"" };
        //   if("true" == "true") { vsource["r-1080"] = "..."; }
        //   if("" == "true")     { vsource["r-4k"] = "..."; }
        val available = mutableListOf("720")
        val dashUrls = mutableMapOf<String, String>()
        // Base object literal:   "r-720":"...", "r-1080":"", "r-4k":""
        Regex(""""r-(\d+)":\s*"([^"]*)"""")
            .findAll(html)
            .forEach { m ->
                val res = m.groupValues[1]
                val url = m.groupValues[2]
                if (url.isNotBlank()) dashUrls[res] = url
            }
        // Availability guards:   if("true" == "true") { vsource["r-1080"] = "..." }
        Regex("""vsource\["r-(\d+)"]\s*=\s*"([^"]*)"""")
            .findAll(html)
            .forEach { m ->
                val res = m.groupValues[1]
                val url = m.groupValues[2]
                if (url.isNotBlank()) dashUrls[res] = url
            }
        Regex("""if\("true" == "true"\)\s*\{\s*vsource\["r-(\d+)"]""")
            .findAll(html)
            .forEach { m ->
                val res = m.groupValues[1]
                if (!available.contains(res)) available.add(res)
            }

        val videos = mutableListOf<Video>()
        val videoHeaders = videoHeaders()

        // DASH MPDs first (preferred, adaptive) for each available resolution.
        // 4K encodes are sometimes VP9 level 5.x (e.g. Harem-tou e Youkoso!),
        // which hard-crashes the native decoder on many devices, so those are probed and dropped.
        dashUrls.entries
            .sortedByDescending { it.key.toIntOrNull() ?: 0 }
            .forEach { (res, url) ->
                val mpd = url.replace(" ", "%20")
                if (isDecodableMpd(mpd)) {
                    videos += Video(mpd, "${res}p (DASH)", mpd, headers = videoHeaders)
                }
            }

        // Direct MP4s at each available resolution (fallback, most compatible).
        available.sortedByDescending { it.toIntOrNull() ?: 0 }.forEach { res ->
            val mp4 = "https://myspacecat.pictures/$folder/$res/$epFile"
                .replace(" ", "%20")
            videos += Video(mp4, "${res}p", mp4, headers = videoHeaders)
        }

        return videos.distinctBy { it.url }
    }

    // Playback headers: the video CDN expects a browser-like Referer/Origin.
    private fun videoHeaders(): Headers = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .add("Accept", "*/*")
        .build()

    /**
     * Probes a DASH manifest and returns false when its video codec is known to
     * crash device decoders: VP9 profile level 5.0+ (codec strings like
     * "vp09.00.50.08"). Everything else (H.264, lower VP9 levels) is fine.
     */
    private fun isDecodableMpd(mpdUrl: String): Boolean = runCatching {
        val body = client.newCall(GET(mpdUrl, videoHeaders())).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching true
            resp.body.string()
        }
        val vp9Level = Regex("""codecs="vp09\.\d+\.(\d+)\.\d+"""")
            .find(body)?.groupValues?.get(1)
            ?.toIntOrNull()
            ?: return@runCatching true
        vp9Level < 50
    }.getOrDefault(true)

    private companion object {
        // (value, display) pairs scraped from the site's genre filter.
        private val GENRE_LIST = arrayOf(
            "3d" to "3D",
            "4k" to "4k",
            "ahegao" to "Ahegao",
            "beach" to "Beach",
            "blood" to "Blood",
            "censored" to "Censored",
            "comedy" to "Comedy",
            "fantasy" to "Fantasy",
            "filmed" to "Filmed",
            "gore" to "Gore",
            "hd" to "HD",
            "harem" to "Harem",
            "horror" to "Horror",
            "incest" to "Incest",
            "inflation" to "Inflation",
            "lactation" to "Lactation",
            "mindbreak" to "Mind Break",
            "mindcontrol" to "Mind Control",
            "monster" to "Monster",
            "pov" to "POV",
            "plot" to "Plot",
            "scat" to "Scat",
            "softcore" to "Softcore",
            "tentacle" to "Tentacle",
            "uncensored" to "Uncensored",
            "vanilla" to "Vanilla",
            "watersports" to "Watersports",
            "x-ray" to "X-Ray",
            "yaoi" to "Yaoi",
            "yuri" to "Yuri",
            "anal" to "Anal",
            "armpitmasturbation" to "Armpit Masturbation",
            "bdsm" to "BDSM",
            "blowjob" to "BlowJob",
            "bondage" to "Bondage",
            "boobjob" to "BoobJob",
            "cowgirl" to "Cowgirl",
            "creampie" to "Creampie",
            "doggy" to "Doggy",
            "doublepenetration" to "Double Penetration",
            "facial" to "Facial",
            "footjob" to "FootJob",
            "gangbang" to "Gangbang",
            "girlsonly" to "Girls Only",
            "handjob" to "HandJob",
            "masturbation" to "Masturbation",
            "missionary" to "Missionary",
            "ntr" to "NTR",
            "orgy" to "Orgy",
            "publicsex" to "Public Sex",
            "rape" to "Rape",
            "reversegangbang" to "Reverse Gangbang",
            "reverserape" to "Reverse Rape",
            "rimjob" to "Rimjob",
            "threesome" to "Threesome",
            "toys" to "Toys",
            "tripplepenetration" to "Tripple Penetration",
            "voyeurism" to "Voyeurism",
            "bigboobs" to "Big Boobs",
            "blackhair" to "Black Hair",
            "blondehair" to "Blonde Hair",
            "bluehair" to "Blue Hair",
            "brownhair" to "Brown Hair",
            "cosplay" to "Cosplay",
            "darkskin" to "Dark Skin",
            "demon" to "Demon",
            "dominantgirl" to "Dominant Girl",
            "elf" to "Elf",
            "futanari" to "Futanari",
            "glasses" to "Glasses",
            "greenhair" to "Green Hair",
            "gyaru" to "Gyaru",
            "invertednipples" to "Inverted Nipples",
            "loli" to "Loli",
            "maid" to "Maid",
            "milf" to "Milf",
            "muscles" to "Muscles",
            "nekomimi" to "Nekomimi",
            "nurse" to "Nurse",
            "pinkhair" to "Pink Hair",
            "ponytail" to "Ponytail",
            "pregnant" to "Pregnant",
            "purplehair" to "Purple Hair",
            "redhair" to "Red Hair",
            "schoolgirl" to "School Girl",
            "shorthair" to "Short Hair",
            "smallboobs" to "Small Boobs",
            "succubus" to "Succubus",
            "swimsuit" to "Swimsuit",
            "teacher" to "Teacher",
            "tomboy" to "Tomboy",
            "tsundere" to "Tsundere",
            "vampire" to "Vampire",
            "virgin" to "Virgin",
            "whitehair" to "White Hair",
            "old" to "Old",
            "shota" to "Shota",
            "trap" to "Trap",
            "uglybastard" to "Ugly Bastard",
        )

        // (value, display) pairs scraped from the site's studio filter.
        private val STUDIO_LIST = arrayOf(
            "44℃ Baidoku" to "44℃ Baidoku",
            "Active" to "Active",
            "AIC" to "AIC",
            "Alice Soft" to "Alice Soft",
            "An♥Tekinus" to "An♥Tekinus",
            "Animac" to "Animac",
            "AniMan" to "AniMan",
            "Anime Antenna Group" to "Anime Antenna Group",
            "Anime Antenna Iinkai" to "Anime Antenna Iinkai",
            "Antechinus" to "Antechinus",
            "Armor" to "Armor",
            "Arms" to "Arms",
            "Asahi Production" to "Asahi Production",
            "AT-2" to "AT-2",
            "Avaco Creative Studios" to "Avaco Creative Studios",
            "Blue Bread" to "Blue Bread",
            "Bomb! Cute! Bomb!" to "Bomb! Cute! Bomb!",
            "BOOTLEG" to "BOOTLEG",
            "BREAKBOTTLE" to "BREAKBOTTLE",
            "BreakBottle" to "BreakBottle",
            "Breakbottle" to "Breakbottle",
            "Bunny Walker" to "Bunny Walker",
            "ChiChinoya" to "ChiChinoya",
            "CHIPPAI" to "CHIPPAI",
            "ChuChu" to "ChuChu",
            "Circle Tribute" to "Circle Tribute",
            "Collaboration Works" to "Collaboration Works",
            "Collaboration Works petit" to "Collaboration Works petit",
            "Collabration Works" to "Collabration Works",
            "Cosmos" to "Cosmos",
            "Cotton Doll" to "Cotton Doll",
            "D3" to "D3",
            "Digital Works" to "Digital Works",
            "Discovery" to "Discovery",
            "EDGE" to "EDGE",
            "Edge" to "Edge",
            "erozuki" to "erozuki",
            "feel." to "feel.",
            "Flavors Soft" to "Flavors Soft",
            "Front Line" to "Front Line",
            "Frontier Works" to "Frontier Works",
            "G-lam" to "G-lam",
            "GOLD BEAR" to "GOLD BEAR",
            "Green Bunny" to "Green Bunny",
            "Happinet Pictures" to "Happinet Pictures",
            "HiLLS" to "HiLLS",
            "Himajin Planning" to "Himajin Planning",
            "Hoods Entertainment" to "Hoods Entertainment",
            "JapanAnime" to "JapanAnime",
            "Jellyfish" to "Jellyfish",
            "Juicy Mango" to "Juicy Mango",
            "Jumondou" to "Jumondou",
            "King Bee" to "King Bee",
            "Kitty Film" to "Kitty Film",
            "Kitty Media" to "Kitty Media",
            "L." to "L.",
            "Lune Pictures" to "Lune Pictures",
            "Magic Bus" to "Magic Bus",
            "Magin Label" to "Magin Label",
            "Majin" to "Majin",
            "Majin Petit" to "Majin Petit",
            "Maplestar" to "Maplestar",
            "Mary Jane" to "Mary Jane",
            "MC Pictures" to "MC Pictures",
            "Media Blasters" to "Media Blasters",
            "Mediabank" to "Mediabank",
            "Milky Animation Label" to "Milky Animation Label",
            "Mitsu" to "Mitsu",
            "Moonstone Cherry" to "Moonstone Cherry",
            "Mousou Senka" to "Mousou Senka",
            "MS Pictures" to "MS Pictures",
            "MS pictures" to "MS pictures",
            "Natural High" to "Natural High",
            "NewGeneration" to "NewGeneration",
            "Nihikime no Dozeu" to "Nihikime no Dozeu",
            "No Future" to "No Future",
            "Nur" to "Nur",
            "NuTech Digital" to "NuTech Digital",
            "Office Takeout" to "Office Takeout",
            "OZ Inc." to "OZ Inc.",
            "Pashima" to "Pashima",
            "Pashmina" to "Pashmina",
            "Passione" to "Passione",
            "Peak Hunt" to "Peak Hunt",
            "Pink Pineapple" to "Pink Pineapple",
            "Pixy" to "Pixy",
            "Pixy Soft" to "Pixy Soft",
            "PoRo" to "PoRo",
            "PoRO petit" to "PoRO petit",
            "Queen Bee" to "Queen Bee",
            "Rabbit Gate" to "Rabbit Gate",
            "Rojiura Jack" to "Rojiura Jack",
            "Schoolzone" to "Schoolzone",
            "Selfish" to "Selfish",
            "SELFISH" to "SELFISH",
            "Seven" to "Seven",
            "Shion" to "Shion",
            "Show-Ten" to "Show-Ten",
            "Soft on Demand" to "Soft on Demand",
            "Studio 1st" to "Studio 1st",
            "Studio 9 Maiami" to "Studio 9 Maiami",
            "Studio Eromatick" to "Studio Eromatick",
            "Studio Fantasia" to "Studio Fantasia",
            "Studio G-1Neo" to "Studio G-1Neo",
            "Studio Hokiboshi" to "Studio Hokiboshi",
            "Studio Houkiboshi" to "Studio Houkiboshi",
            "Studio Jam" to "Studio Jam",
            "Studio Kyuuma" to "Studio Kyuuma",
            "Studio Ten Carat" to "Studio Ten Carat",
            "Suiseisha" to "Suiseisha",
            "Suzuki Mirano" to "Suzuki Mirano",
            "Suzuki Mirano petit" to "Suzuki Mirano petit",
            "T-Re" to "T-Re",
            "T-Rex" to "T-Rex",
            "TEATRO Nishi Tokyo Studio" to "TEATRO Nishi Tokyo Studio",
            "TNK" to "TNK",
            "TOHO" to "TOHO",
            "Toranoana" to "Toranoana",
            "Torudaya" to "Torudaya",
            "Union Cho" to "Union Cho",
            "unknown" to "unknown",
            "Unknown" to "Unknown",
            "Valkyria" to "Valkyria",
            "White Bear" to "White Bear",
            "WHITE BEAR" to "WHITE BEAR",
            "XTER" to "XTER",
            "Y.O.U.C" to "Y.O.U.C",
            "ZIZ" to "ZIZ",
            "ZIZ Entertainment" to "ZIZ Entertainment",
        )
    }
}
