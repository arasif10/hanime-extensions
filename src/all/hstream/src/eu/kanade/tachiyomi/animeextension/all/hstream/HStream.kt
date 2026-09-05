/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hstream

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException

/**
 * HStream (https://hstream.moe)
 *
 * Server-rendered Laravel + Livewire site; there is no JSON catalogue API, so
 * listings are parsed from the HTML of /search?order=...&page=N (the empty
 * query returns the whole catalogue newest-first, 25 episode cards/page).
 * Every card is one EPISODE of a series; cards are grouped into series
 * entries by their shared image folder slug
 * (/images/hentai/{series-slug}/cover-ep-N.webp).
 *
 * Details come from the series page (/hentai/{slug} without the episode
 * number): og:title / og:description / og:image plus the tag links.
 *
 * Episodes come from the series page's card list, which contains every
 * episode of the series (the base URL itself is episode 1's card).
 *
 * Video: the watch page carries a hidden #e_id. POST /player/api (JSON
 * {"episode_id": N}) returns {stream_domains[], asia_stream_domains[],
 * stream_url, interpolated, interpolated_uhd}. Each domain serves DASH
 * manifests at /{stream_url}/{720|1080|2160|1080i|2160i}/manifest.mpd and a
 * legacy x264.720p.mp4 mirror. The route is CSRF-protected: the watch-page
 * visit plants the XSRF-TOKEN cookie that must be echoed in the X-XSRF-TOKEN
 * header (what the site's axios does), so the watch page must be fetched
 * through the source's client first.
 *
 * Manifests reference plain fMP4 chunks misnamed ".webp" - no DRM - and the
 * CDN requires no Referer. The domains are interchangeable mirrors, so each
 * quality is emitted once on the first domain.
 */
class HStream : AnimeHttpSource() {

    override val name = "HStream"

    override val baseUrl = "https://hstream.moe"

    override val lang = "all"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== Catalogue ==============================

    override fun popularAnimeRequest(page: Int): Request =
        searchRequest(page, order = "most-views")

    override fun popularAnimeParse(response: Response): AnimesPage =
        searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        searchRequest(page, order = "recently-uploaded")

    override fun latestUpdatesParse(response: Response): AnimesPage =
        searchAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val (order, tags) = parseFilters(filters)
        return searchRequest(page, order = order, query = query, tags = tags)
    }

    private fun searchRequest(
        page: Int,
        order: String? = null,
        query: String? = null,
        tags: List<String> = emptyList(),
    ): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("live-search", query.orEmpty())
            addQueryParameter("order", order.orEmpty())
            tags.forEachIndexed { i, tag -> addQueryParameter("tags[$i]", tag) }
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return GET(url.toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string(), baseUrl)

        // Card -> series grouping. A card's image lives in the series' image
        // folder, which is exactly its URL slug: /hentai/{slug} uses
        // /images/hentai/{slug}/... - so the folder doubles as the series id.
        val series = LinkedHashMap<String, SeriesBuilder>()

        document.select("a[href*=/hentai/]").forEach { card ->
            val img = card.selectFirst("img[src*=/images/hentai/]") ?: return@forEach
            val m = IMAGE_PATH.find(img.attr("src")) ?: return@forEach
            val (slug, epNum) = m.destructured

            val builder = series.getOrPut(slug) {
                SeriesBuilder(
                    slug = slug,
                    title = img.attr("alt").substringBeforeLast(" - ").trim(),
                    thumbnail = img.attr("src"),
                )
            }
            builder.episodeNumbers += epNum.toIntOrNull() ?: 0
        }

        val animes = series.values.map { it.build(baseUrl) }
        // The site renders an empty grid past the last page, so
        // hasNextPage = this page produced entries works as the pager.
        return AnimesPage(animes, animes.isNotEmpty())
    }

    /** Accumulates one series entry across its episode cards. */
    private class SeriesBuilder(
        val slug: String,
        val title: String,
        val thumbnail: String,
    ) {
        val episodeNumbers = mutableSetOf<Int>()

        fun build(siteUrl: String): SAnime = SAnime.create().apply {
            url = "/hentai/$slug"
            this.title = this@SeriesBuilder.title
            thumbnail_url = "$siteUrl$thumbnail"
        }
    }

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Ignored when searching with a query"),
        OrderFilter(),
        TagFilter(),
    )

    private class OrderFilter : AnimeFilter.Select<String>(
        "Order",
        arrayOf(
            "Recently Uploaded",
            "Recently Released",
            "Trending",
            "Most Views",
            "Most Likes",
            "Popular Weekly",
            "Popular Monthly",
        ),
        0,
    ) {
        val value: String
            get() = ORDERS[state]
    }

    private class TagFilter : AnimeFilter.Group<TagFilter.Box>(
        "Tags",
        TAGS.map { Box(it.second, it.first) },
    ) {
        class Box(name: String, val slug: String) : AnimeFilter.CheckBox(name)

        val selected: List<String>
            get() = state.filter { it.state }.map { it.slug }
    }

    private fun parseFilters(filters: AnimeFilterList): Pair<String, List<String>> {
        var order = "recently-uploaded"
        var tags: List<String> = emptyList()
        filters.forEach { filter ->
            when (filter) {
                is OrderFilter -> order = filter.value
                is TagFilter -> tags = filter.selected
                else -> {}
            }
        }
        return order to tags
    }

    // ============================== Anime Details ==============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string(), baseUrl)

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" - Watch All Episodes")?.trim().orEmpty()

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val tags = document.select("a[href*='tags%5B0%5D='], a[href*='tags[0]']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return SAnime.create().apply {
            this.title = title
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            this.description = description
            genre = tags.takeIf { it.isNotEmpty() }?.joinToString(", ")
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val seriesSlug = response.request.url.encodedPath.substringAfter("/hentai/").trimEnd('/')
        val ownEpisodePrefix = "$seriesSlug-"

        // Collect this series' episode cards: the base slug (episode 1) plus
        // "{slug}-N" siblings. The page also recommends other series - those
        // slugs never reduce to a bare episode number and are dropped.
        // Base slug and "-1" are the same episode; the numbered card wins.
        val byNumber = sortedMapOf<Int, Element>()
        document.select("a[href*=/hentai/]").forEach { card ->
            val slug = card.absUrl("href").substringAfter("$baseUrl/hentai/").substringBefore('?')
            val epNum = when {
                slug == seriesSlug -> 1
                slug.startsWith(ownEpisodePrefix) -> slug.removePrefix(ownEpisodePrefix).toIntOrNull() ?: return@forEach
                else -> return@forEach
            }
            byNumber[epNum] = card
        }
        if (byNumber.isEmpty()) byNumber[1] = document.selectFirst("a[href*=/hentai/]") ?: Element("a")

        return byNumber.entries.map { (epNum, card) ->
            SEpisode.create().apply {
                url = "/hentai/${cardSlug(card, seriesSlug, epNum)}"
                name = "Episode $epNum"
                episode_number = epNum.toFloat()
                // Episode cover from the card image (AniZen's runtime SEpisode
                // has preview_url; the lib-14 stub this compiles against does not).
                card.selectFirst("img[src*=/images/hentai/]")?.attr("src")?.let { src ->
                    val abs = if (src.startsWith("http")) src else "$baseUrl$src"
                    setEpisodeField(this, "preview_url", abs)
                }
            }
        }.sortedByDescending { it.episode_number }
    }

    /** Recovers the episode slug from a card's href (fallback: numbered slug). */
    private fun cardSlug(card: Element, seriesSlug: String, epNum: Int): String =
        card.absUrl("href").substringAfter("$baseUrl/hentai/").substringBefore('?')
            .takeIf { it.isNotBlank() }
            ?: if (epNum == 1) seriesSlug else "$seriesSlug-$epNum"

    // ============================== Video Streams ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        // The watch page - also the request that seeds the XSRF-TOKEN cookie.
        val slug = episode.url.substringAfter("/hentai/")
        return GET("$baseUrl/hentai/$slug", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        if (!response.isSuccessful) {
            throw IOException("HStream: failed to load the watch page (HTTP ${response.code})")
        }
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val episodeId = document.selectFirst("input#e_id")?.attr("value")
            ?: throw IOException("HStream: episode id not found on the watch page")

        val apiResponse = client.newCall(playerApiRequest(episodeId)).execute()
        apiResponse.use {
            if (!it.isSuccessful) {
                throw IOException("HStream: /player/api failed (HTTP ${it.code})")
            }
            return parseStreamJson(it.body?.string().orEmpty())
        }
    }

    /** Mirrors the site's axios call: JSON body + CSRF + XHR headers. */
    private fun playerApiRequest(episodeId: String): Request {
        val xsrf = client.cookieJar.loadForRequest("$baseUrl/".toHttpUrl())
            .firstOrNull { it.name == "XSRF-TOKEN" }?.value
            ?: throw IOException("HStream: XSRF-TOKEN cookie missing (watch page did not seed it)")

        val body = """{"episode_id": "$episodeId"}"""
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        return Request.Builder()
            .url("$baseUrl/player/api")
            .post(body)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("X-XSRF-TOKEN", java.net.URLDecoder.decode(xsrf, "UTF-8"))
            .headers(headers)
            .build()
    }

    private fun parseStreamJson(body: String): List<Video> {
        val json = org.json.JSONObject(body)

        val domains = json.optJSONArray("stream_domains")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty()
        if (domains.isEmpty()) throw IOException("HStream: no stream domains returned")

        val streamUrl = json.optString("stream_url").replace(" ", "%20")
        val interpolated = json.optInt("interpolated", 0) == 1
        val interpolatedUhd = json.optInt("interpolated_uhd", 0) == 1
        val primary = domains.first()

        // Highest resolution first; 1080i/2160i are the 48fps interpolated
        // encodes (the site labels them 1080p48/2160p48).
        val videos = buildList {
            add("2160" to "4K")
            if (interpolatedUhd) add("2160i" to "4K 48fps")
            add("1080" to "1080p")
            if (interpolated) add("1080i" to "1080p 48fps")
            add("720" to "720p")
        }.map { (variant, label) ->
            val url = "$primary/$streamUrl/$variant/manifest.mpd"
            Video(url, label, url, headers = videoHeaders())
        }

        // Legacy direct MP4 mirror (always exists, most compatible).
        val mp4 = "$primary/$streamUrl/x264.720p.mp4"
        return videos + Video(mp4, "720p (MP4)", mp4, headers = videoHeaders())
    }

    private fun videoHeaders(): Headers = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .build()

    // ============================== Helpers ==============================

    /**
     * Sets a field on SEpisode that exists in AniZen's runtime (lib v16+)
     * but not in the lib-14 stub this extension compiles against.
     */
    private fun setEpisodeField(episode: SEpisode, fieldName: String, value: String) {
        try {
            val setter = episode.javaClass.getMethod(
                "set${fieldName.replaceFirstChar { it.uppercase() }}",
                String::class.java,
            )
            setter.invoke(episode, value)
        } catch (_: NoSuchMethodException) {
        } catch (_: Exception) {
        }
    }

    private companion object {
        /** /images/hentai/{series-slug}/(gallery|cover)-ep-{N}... on any card image. */
        private val IMAGE_PATH = Regex("""/images/hentai/([a-z0-9-]+)/(?:gallery|cover)-ep-(\d+)""")

        private val ORDERS = arrayOf(
            "recently-uploaded",
            "recently-released",
            "trending",
            "most-views",
            "most-likes",
            "popular-weekly",
            "popular-monthly",
        )

        // (slug, display) pairs from the site's tag sidebar.
        private val TAGS = arrayOf(
            "3d" to "3D",
            "48fps" to "48fps",
            "4k" to "4K",
            "4k-48fps" to "4K 48fps",
            "ahegao" to "Ahegao",
            "anal" to "Anal",
            "bdsm" to "BDSM",
            "bestiality" to "Bestiality",
            "big-boobs" to "Big Boobs",
            "blow-job" to "Blow Job",
            "bondage" to "Bondage",
            "boob-job" to "Boob Job",
            "censored" to "Censored",
            "comedy" to "Comedy",
            "cosplay" to "Cosplay",
            "creampie" to "Creampie",
            "dark-skin" to "Dark Skin",
            "elf" to "Elf",
            "facial" to "Facial",
            "fantasy" to "Fantasy",
            "filmed" to "Filmed",
            "foot-job" to "Foot Job",
            "futanari" to "Futanari",
            "gangbang" to "Gangbang",
            "glasses" to "Glasses",
            "gore" to "Gore",
            "hand-job" to "Hand Job",
            "harem" to "Harem",
            "horror" to "Horror",
            "incest" to "Incest",
            "inflation" to "Inflation",
            "lactation" to "Lactation",
            "lq" to "LQ",
            "maid" to "Maid",
            "masturbation" to "Masturbation",
            "milf" to "MILF",
            "mind-break" to "Mind Break",
            "mind-control" to "Mind Control",
            "monster" to "Monster",
            "nekomimi" to "Nekomimi",
            "netorare" to "Netorare",
            "nurse" to "Nurse",
            "ogre" to "Ogre",
            "orc" to "Orc",
            "plot" to "Plot",
            "pregnant" to "Pregnant",
            "reverse-gangbang" to "Reverse Gangbang",
            "rimjob" to "Rimjob",
            "school-girl" to "School Girl",
            "scat" to "Scat",
            "shota" to "Shota",
            "softcore" to "Softcore",
            "stockings" to "Stockings",
            "swimsuit" to "Swimsuit",
            "tentacle" to "Tentacle",
            "threesome" to "Threesome",
            "toys" to "Toys",
            "trap" to "Trap",
            "tsundere" to "Tsundere",
            "uncensored" to "Uncensored",
            "vanilla" to "Vanilla",
            "virgin" to "Virgin",
            "voyeurism" to "Voyeurism",
            "x-ray" to "X-Ray",
            "yaoi" to "Yaoi",
            "yuri" to "Yuri",
        )
    }
}
