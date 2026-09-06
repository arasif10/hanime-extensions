/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaimama

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * HentaiMama (https://hentaimama.io)
 *
 * WordPress + DooPlay theme; everything is server-rendered HTML.
 *
 * Listings:
 *  - Popular: /trending/page/N (the site's "Trending now" ranking)
 *  - Latest:  /tvshows/page/N  (newest series first, 24 cards/page)
 *  - Search:  /?s=QUERY&page=N
 *  - Filters: /genre/{slug}/page/N and /studio/{slug}/page/N
 * Archive cards are article.item.tvshows; search pages use a different
 * shape (article.series-card) - both are handled.
 *
 * Details come from the series page /tvshows/{slug}: h1 title, og:image,
 * meta description (episode count + status + genres), genre/studio links.
 *
 * Episodes are the series page's a.dt-se-item rows (the whole list is in
 * the DOM, long series included) with per-episode thumbnail, summary and
 * date.
 *
 * Video: the episode page carries its post id (body class postid-N).
 * POST /wp-admin/admin-ajax.php {action=get_player_contents, a=postId,
 * i=mirror} returns a JSON array of mirror player HTML where entry i-1 is
 * mirror i's content: an iframe to /?dt_embed={rtmp|cu}&p={base64 path}.
 * That iframe page is a jwplayer shell with direct MP4 sources
 * (https://gdvid.info/... un-expiring, plus time-signed mirrors).
 */
class HentaiMama : AnimeHttpSource() {

    override val name = "HentaiMama"

    override val baseUrl = "https://hentaimama.io"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("hentaimama", "all", 1)) so the app maps the
    // index entry to the installed extension across version bumps.
    override val id: Long = 4792016095198521599L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/trending/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        archiveAnimesPage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/tvshows/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        archiveAnimesPage(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var genre: String? = null
        var studio: String? = null
        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> genre = GENRES.getOrNull(filter.state)?.second?.ifBlank { null }
                is StudioFilter -> studio = STUDIOS.getOrNull(filter.state)?.second?.ifBlank { null }
                else -> {}
            }
        }
        return when {
            !genre.isNullOrBlank() -> GET("$baseUrl/genre/$genre/page/$page/", headers)
            !studio.isNullOrBlank() -> GET("$baseUrl/studio/$studio/page/$page/", headers)
            query.isNotBlank() -> GET(
                "$baseUrl/".toHttpUrl().newBuilder()
                    .addQueryParameter("s", query)
                    .addQueryParameter("page", page.toString())
                    .build(),
                headers,
            )
            else -> GET("$baseUrl/tvshows/page/$page/", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        archiveAnimesPage(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Ignored when searching with a query"),
        GenreFilter(),
        StudioFilter(),
    )

    private class GenreFilter : AnimeFilter.Select<String>("Genre", GENRES.map { it.first }.toTypedArray())

    private class StudioFilter : AnimeFilter.Select<String>("Studio", STUDIOS.map { it.first }.toTypedArray())

    // ============================== Catalogue parsing =====================

    private fun archiveAnimesPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("article").mapNotNull(::seriesCard)
        val pages = doc.selectFirst("div.pagination")?.attr("data-pages")?.toIntOrNull()
        val hasNext = if (pages != null) response.page() < pages else animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    /** Handles both archive cards (article.item.tvshows) and search rows (article.series-card). */
    private fun seriesCard(el: Element): SAnime? {
        val link = el.selectFirst("a[href*=/tvshows/]") ?: return null
        val url = link.attr("href").takeIf { it.contains("/tvshows/") } ?: return null
        val img = el.selectFirst("img")
        val title = el.selectFirst("h3 a")?.text()?.trim()
            ?: el.selectFirst("h3")?.text()?.trim()
            ?: img?.attr("alt")?.trim()
            ?: return null
        return SAnime.create().apply {
            this.title = title
            this.url = url.substringAfter("/tvshows/").trim('/')
            thumbnail_url = img?.attr("src")?.takeIf { it.isNotBlank() }
        }
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/tvshows/${anime.url}/", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val metaDesc = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        val genres = doc.select("a[href*=/genre/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(", ")
            .ifBlank { null }
        val studio = doc.select("a[href*=/studio/]").firstOrNull()?.text()?.trim()?.ifBlank { null }
        val status = when {
            metaDesc.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
            metaDesc.contains("Ongoing", ignoreCase = true) -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
                .ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty() }
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            description = metaDesc.ifBlank { null }
            genre = genres
            author = studio
            this.status = status
            initialized = true
        }
    }

    // ============================== Recommendations ======================
    // AniZen fills the "Recommended" row of its detail screen only when the
    // extension advertises support and implements `fetchRelatedAnimeList`.
    // Those members exist on AniZen's runtime source API but not on the older
    // lib-14 stub we compile against, so they are declared without `override`
    // — the JVM dispatches the runtime interface methods to them anyway.
    // The source of truth is the site's own "Similar titles" block, which the
    // series page renders as a.ep-sim-card rows (six per page).

    val supportsRelatedAnimes: Boolean get() = true

    suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        return runCatching {
            val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl/tvshows/${anime.url}/"
            client.newCall(GET(url, headers)).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val doc = response.asJsoup()
                doc.select("a.ep-sim-card").mapNotNull { card ->
                    val href = card.attr("href").takeIf { it.contains("/tvshows/") }
                        ?: return@mapNotNull null
                    val img = card.selectFirst("img")
                    val title = card.selectFirst(".ep-sim-name")?.text()?.trim()
                        ?: img?.attr("alt")?.trim()
                        ?: return@mapNotNull null
                    SAnime.create().apply {
                        this.title = title
                        this.url = href.substringAfter("/tvshows/").trim('/')
                        thumbnail_url = img?.attr("src")?.takeIf { it.startsWith("http") }
                    }
                }.distinctBy { it.url }
            }
        }.getOrDefault(emptyList())
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/tvshows/${anime.url}/", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        return doc.select("a.dt-se-item").mapNotNull { el ->
            val href = el.attr("href").takeIf { it.contains("/episodes/") } ?: return@mapNotNull null
            val number = href.substringAfterLast("/episodes/", "")
                .substringAfterLast("-episode-")
                .substringBefore('/').toIntOrNull() ?: return@mapNotNull null
            val title = el.selectFirst(".dt-se-title")?.text()?.trim()
            val summary = el.selectFirst(".dt-se-desc")?.text()?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals(NO_SYNOPSIS, ignoreCase = true) }
            SEpisode.create().apply {
                this.url = href.substringAfter("/episodes/").trim('/')
                name = title?.takeIf { it.isNotBlank() && !it.equals("Episode $number", ignoreCase = true) }
                    ?.let { "Episode $number · $it" }
                    ?: "Episode $number"
                episode_number = number.toFloat()
                date_upload = el.selectFirst(".dt-se-date")?.text()?.trim()?.parseDate() ?: 0L
                // Episode thumbnails/summaries render in AniZen's episode picker;
                // the lib-14 stub has no setters so go through the runtime methods.
                el.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }?.let {
                    setEpisodeField(this, "preview_url", it)
                }
                summary?.let { setEpisodeField(this, "summary", it) }
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/episodes/${episode.url}/", headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val postId = doc.body()?.className()?.split(' ')?.firstOrNull { it.startsWith("postid-") }
            ?.removePrefix("postid-")
            ?: doc.selectFirst("article[id^=post-]")?.id()?.removePrefix("post-")
            ?: throw IOException("HentaiMama: episode post id not found")

        val videos = mutableListOf<Video>()
        val seen = mutableSetOf<String>()
        mirrors(postId).forEachIndexed { index, mirrorHtml ->
            val embedUrl = Jsoup.parse(mirrorHtml).selectFirst("iframe[src]")?.attr("src")
                ?: return@forEachIndexed
            val embedBody = runCatching {
                client.newCall(GET(embedUrl, headers)).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string().orEmpty()
                }
            }.getOrNull() ?: return@forEachIndexed

            SOURCE_REGEX.findAll(embedBody.replace("\\/", "/")).forEach { m ->
                val type = m.groupValues[1].uppercase()
                val file = m.groupValues[2]
                if (file.startsWith("http") && seen.add(file)) {
                    videos += Video(file, "$type · Mirror ${index + 1}", file, headers = headers)
                }
            }
        }
        if (videos.isEmpty()) throw IOException("HentaiMama: no playable streams found")
        return videos
    }

    /**
     * Fetches every mirror's player HTML. The first call (i=1) also reveals
     * the mirror count from the response array length; the remaining mirrors
     * are fetched individually (each response only fills its own entry).
     */
    private fun mirrors(postId: String): List<String> {
        val first = fetchMirror(postId, 1)
        if (first.isEmpty()) return emptyList()
        val result = MutableList(first.size) { i -> if (i == 0) first[0] else "" }
        for (n in 2..first.size) {
            result[n - 1] = fetchMirror(postId, n).getOrNull(n - 1).orEmpty()
        }
        return result
    }

    private fun fetchMirror(postId: String, index: Int): List<String> {
        val form = FormBody.Builder()
            .add("action", "get_player_contents")
            .add("a", postId)
            .add("i", index.toString())
            .build()
        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("$baseUrl/wp-admin/admin-ajax.php")
                    .post(form)
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .headers(headers)
                    .build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) emptyList() else parseJsonArray(resp.body?.string().orEmpty())
            }
        }.getOrDefault(emptyList())
    }

    private fun parseJsonArray(body: String): List<String> = runCatching {
        val arr = org.json.JSONArray(body)
        (0 until arr.length()).map { arr.optString(it) }
    }.getOrDefault(emptyList())

    // ============================== Helpers ===============================

    private fun Response.asJsoup(): Document = Jsoup.parse(body?.string().orEmpty(), request.url.toString())

    /** Current page for pagination checks: ?page=N (search), /page/N/ (archives). */
    private fun Response.page(): Int =
        request.url.queryParameter("page")?.toIntOrNull()
            ?: request.url.queryParameter("paged")?.toIntOrNull()
            ?: request.url.pathSegments.lastOrNull { it.toIntOrNull() != null }?.toIntOrNull()
            ?: 1

    private fun String.parseDate(): Long = runCatching {
        DATE_FORMAT.parse(this)?.time ?: 0L
    }.getOrDefault(0L)

    /**
     * Sets a field on SEpisode that exists in AniZen's runtime model but not
     * in the lib-14 stub this extension compiles against (preview_url, summary).
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

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private const val NO_SYNOPSIS = "No synopsis added for this episode yet"

        private val DATE_FORMAT = SimpleDateFormat("MMM d, yyyy", Locale.US)

        // jwplayer setup: sources: [{type: "mp4", file: "https://..."}, ...]
        private val SOURCE_REGEX = Regex("""sources:\s*\[\s*\{\s*type:\s*"([^"]+)",\s*file:\s*"([^"]+)"""")

        private val GENRES = listOf(
            "Any" to "",
            "3D" to "3d",
            "Ahegao" to "ahegao",
            "Anal" to "anal",
            "BDSM" to "bdsm",
            "Blackmail" to "blackmail",
            "Blowjob" to "blowjob",
            "Brainwashed" to "brainwashed",
            "Creampie" to "creampie",
            "Cute/Funny" to "cutefunny",
            "Deepthroat" to "deepthroat",
            "Domination" to "domination",
            "Futanari" to "futanari",
            "Harem" to "harem",
            "Horny Slut" to "horny-slut",
            "Housewife" to "housewife",
            "Humiliation" to "humiliation",
            "Internal Cumshot" to "internal-cumshot",
            "Large Breasts" to "large-breasts",
            "Megane" to "megane",
            "MILF" to "milf",
            "Mind Break" to "mind-break",
            "Molestation" to "molestation",
            "Office Ladies" to "office-ladies",
            "Public Sex" to "public-sex",
            "Rape" to "rape",
            "School Girls" to "school-girls",
            "Small Breasts" to "small-breasts",
            "Stocking" to "stocking",
            "Strap-on" to "strap-on",
            "Swimsuit" to "swimsuit",
            "Threesome" to "three-some",
            "Tits Fuck" to "tits-fuck",
            "Toys" to "toys",
            "Tsundere" to "tsundere",
            "Ugly Bastard" to "ugly-bastard",
            "Uncensored" to "uncensored",
            "Urination" to "urination",
            "Virgins" to "virgins",
            "X-Ray" to "x-ray",
            "Yuri" to "yuri",
        )

        private val STUDIOS = listOf(
            "Any" to "",
            "Bunnywalker" to "bunnywalker",
            "Collaboration Works" to "collaboration-works",
            "Majin" to "majin",
            "Mary Jane" to "mary-jane",
            "NuR" to "nur",
            "Pink Pineapple" to "pink-pineapple",
        )
    }
}
