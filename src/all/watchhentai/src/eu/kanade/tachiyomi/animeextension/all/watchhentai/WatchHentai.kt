/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.watchhentai

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale

/**
 * WatchHentai (https://watchhentai.net)
 *
 * WordPress Dooplay theme. The /videos/ catalogue (and /?s= search results)
 * lists episode posts. Each episode post and each /series/{slug} page carries a
 * <ul class="episodios"> block listing the series' episodes, each linking to a
 * per-episode video post.
 *
 * Video resolution:
 *  1. The episode post embeds <iframe data-primary-player-url=
 *     "https://watchhentai.net/player/{post-id}/1/mp4/">.
 *  2. GET that player page; it declares  var jw = {"file":"<enc>"}.
 *  3. <enc> decodes (base64 -> XOR chain -> reversed base64) to a direct MP4 on
 *     hstorage.xyz.
 */
class WatchHentai : AnimeHttpSource() {

    override val name = "WatchHentai"

    override val baseUrl = "https://watchhentai.net"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("WatchHentai", "all", 1))
    override val id: Long = 5265206503077835376L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    // /videos/ is date-ordered, so it is the *latest* feed. Popular must use the
    // site's own /trending/ ranking, otherwise both rows render the same entries.
    override fun popularAnimeRequest(page: Int): Request =
        GET(
            if (page == 1) "$baseUrl/trending/" else "$baseUrl/trending/page/$page/",
            headers,
        )

    // okhttp bodies are one-shot: parse the page once and hand the document to
    // both helpers. Reading response.body twice threw
    // "IllegalStateException: closed", which broke this whole source.
    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        return AnimesPage(catalogCards(doc), hasMoreCards(doc, pageOf(response)))
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET(
            if (page == 1) "$baseUrl/videos/" else "$baseUrl/videos/page/$page/",
            headers,
        )

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        return AnimesPage(catalogCards(doc), hasMoreCards(doc, pageOf(response)))
    }

    // ============================== Search ================================

    // Genre browsing is a path (one genre per URL), so the first ticked genre
    // drives the request and the rest are fetched and merged in the parse step.
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        lastFilters = filters
        lastPage = page
        if (query.isNotBlank()) {
            return GET("$baseUrl/page/$page/?s=${URLEncoder.encode(query, "UTF-8")}", headers)
        }
        val slugs = selectedGenres(filters)
        if (slugs.isEmpty()) return latestUpdatesRequest(page)
        return genreRequest(slugs.first(), page)
    }

    private fun genreRequest(slug: String, page: Int): Request =
        GET(
            if (page == 1) "$baseUrl/genre/$slug/" else "$baseUrl/genre/$slug/page/$page/",
            headers,
        )

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val merged = LinkedHashMap<String, SAnime>()
        catalogCards(doc).forEach { merged[it.url] = it }
        var hasMore = hasMoreCards(doc, pageOf(response))

        // Ticking several genres is an OR: one request per genre, merged by URL.
        selectedGenres(lastFilters).drop(1).forEach { slug ->
            client.newCall(genreRequest(slug, lastPage)).execute().use { resp ->
                val extra = resp.asJsoup()
                catalogCards(extra).forEach { merged.putIfAbsent(it.url, it) }
                hasMore = hasMore || hasMoreCards(extra, lastPage)
            }
        }
        return AnimesPage(merged.values.toList(), hasMore)
    }

    // ============================ Catalogue ===============================

    private fun catalogCards(doc: Document): List<SAnime> {
        return doc.select("article.item, article.post").mapNotNull { el ->
            val a = el.selectFirst("a[href*=/videos/], a[href*=/series/]") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!href.startsWith("http") ||
                (!href.contains("/videos/") && !href.contains("/series/"))
            ) {
                return@mapNotNull null
            }
            val anchorTitle = a.attr("title").trim()
                .substringBefore(" Watch Hentai")
            val title = if (anchorTitle.isNotBlank()) {
                anchorTitle
            } else {
                el.selectFirst("h3, h2")?.text()?.trim() ?: return@mapNotNull null
            }
            SAnime.create().apply {
                this.title = title
                url = href.removePrefix("$baseUrl/").trim('/')
                thumbnail_url = el.selectFirst("img[data-src]")?.attr("data-src")
                    ?.takeIf { it.startsWith("http") }
                    ?: el.selectFirst("img[src^=http]")?.attr("src")
            }
        }.distinctBy { it.url }
    }

    /** "2026-08-11T04:12:35+00:00" (JSON-LD) to a timestamp, or 0 when absent. */
    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val pattern = if (raw.length > 10) "yyyy-MM-dd'T'HH:mm:ss" else "yyyy-MM-dd"
        return runCatching {
            SimpleDateFormat(pattern, Locale.US).parse(raw.take(pattern.length + 10))!!.time
        }.getOrDefault(0L)
    }

    /** The page number the app asked for, taken from the request path. */
    private fun pageOf(response: Response): Int =
        Regex("""/page/(\d+)/""").find(response.request.url.encodedPath)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun hasMoreCards(doc: Document, page: Int): Boolean {
        if (doc.selectFirst("a[rel=next]") != null) return true
        if (doc.selectFirst(".pagination a:containsOwn(Next)") != null) return true
        // Genre listings paginate without a rel=next arrow, so compare the
        // highest page number the pagination block offers with the current one.
        val highest = doc.select(".pagination__pages a, .pagination__pages span")
            .mapNotNull { el -> el.text().trim().toIntOrNull() }
            .maxOrNull() ?: 0
        return highest > page
    }

    // ============================== Filters ===============================

    // WatchHentai's catalogue rows carry no per-title genres, so an exclude
    // option could not be honoured without fetching every row's details. Ticking
    // several genres unions their /genre/<slug>/ listings instead.
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Applies to browsing - leave the search box empty"),
        GenreFilter(),
    )

    private var lastFilters: AnimeFilterList? = null
    private var lastPage: Int = 1

    private class GenreFilter : AnimeFilter.Group<AnimeFilter.CheckBox>(
        "Genres",
        GENRE_NAMES.drop(1).map { GenreCheckBox(it) },
    )

    private class GenreCheckBox(name: String) : AnimeFilter.CheckBox(name, false)

    private fun flatFilters(filters: AnimeFilterList?): List<AnimeFilter<*>> = filters.orEmpty().flatMap { filter ->
        if (filter is AnimeFilter.Group<*>) {
            filter.state.filterIsInstance<AnimeFilter<*>>()
        } else {
            listOf(filter)
        }
    }

    private fun selectedGenres(filters: AnimeFilterList?): List<String> =
        flatFilters(filters).filterIsInstance<AnimeFilter.CheckBox>()
            .filter { it.state }
            .mapNotNull { box -> GENRE_NAMES.indexOf(box.name).takeIf { it > 0 } }
            .map { GENRE_SLUGS[it] }
            .distinct()

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val ogImg = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val seriesName = ogTitle.substringBefore(" - Watch Hentai").trim()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim()
                ?: seriesName.ifBlank { ogTitle }
            thumbnail_url = doc.selectFirst("img.tv-single-hero__poster, img[src*=/poster]")?.let { img ->
                val thumb = img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src")
                thumb.takeIf { it.startsWith("http") }
            } ?: ogImg
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("div.tv-single-about p")?.text()
            genre = doc.select("a[href*=/genre/]").eachText().distinct().take(10)
                .joinToString(", ").ifBlank { null }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        // Read the body once: okhttp bodies are one-shot, and the JSON-LD block
        // with the publish date is not part of the episode anchors.
        val html = response.body?.string().orEmpty()
        val doc = Jsoup.parse(html, response.request.url.toString())
        val published = DATE_PUBLISHED.find(html)?.groupValues?.get(1)?.let(::parseDate) ?: 0L
        // This site models every entry as a single-episode post: a series page
        // links exactly one /videos/<slug>/ URL and has no episode-list markup at
        // all. Falling back to that link is what keeps episodes from vanishing
        // (the old code required ul.episodios and threw when it was absent).
        val listMarkup = doc.select("ul.episodios li a[href*=/videos/]")
        val items = if (listMarkup.isNotEmpty()) {
            listMarkup
        } else {
            // Jsoup's Elements.filter takes a NodeFilter, so go through a plain
            // Kotlin list to filter on the href instead.
            doc.select("a[href*=/videos/]").toList()
                .filter { EPISODE_PATH.containsMatchIn(it.attr("href")) }
        }
        if (items.isEmpty()) {
            throw IOException("WatchHentai: no episode link found")
        }
        return items.mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!href.contains("/videos/")) return@mapNotNull null
            val title = a.attr("title").substringBefore(" Watch Hentai").trim()
                .ifBlank { a.text().trim() }
            SEpisode.create().apply {
                url = href.removePrefix("$baseUrl/").trim('/')
                name = title.ifBlank { "Episode 1" }
                date_upload = published
                episode_number = Regex("""(?:episode|ep)[-\s]?(\d+)""", RegexOption.IGNORE_CASE)
                    .find(title + " " + href)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
                a.parent()?.selectFirst("img")?.let { img ->
                    val thumb = img.attr("data-src").ifBlank { img.attr("src") }
                    if (thumb.startsWith("http")) setEpisodeField(this, "preview_url", thumb)
                }
            }
        }.distinctBy { it.url }
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val html = response.body?.string().orEmpty()
        // Player URL is exposed on the iframe of the episode post.
        val playerUrl = Jsoup.parse(html).selectFirst("iframe[data-primary-player-url]")
            ?.attr("data-primary-player-url")
            ?.takeIf { it.startsWith("http") }
        val player = if (playerUrl != null) {
            client.newCall(GET(playerUrl, headers)).execute()
                .use { it.body?.string().orEmpty() }
        } else {
            html
        }
        val enc = Regex("""var jw\s*=\s*\{[^}]*?"file":"([^"]+)"[^}]*?\}""").find(player)
            ?.groupValues?.get(1)
            ?: throw IOException("WatchHentai: no player config found")
        val direct = decodeMediaUrl(enc)
        return listOf(Video(direct, "WatchHentai", direct, headers = headers))
    }

    // whDecodeMediaUrl(s): base64 -> XOR(13+i%17) -> reversed base64.
    private fun decodeMediaUrl(s: String): String {
        val normalized = s.replace('-', '+').replace('_', '/').let {
            it + "=".repeat((4 - it.length % 4) % 4)
        }
        val x = String(Base64.getDecoder().decode(normalized), Charsets.ISO_8859_1)
        val r = StringBuilder(x.length)
        for (i in x.indices) {
            r.append((x[i].code xor ((13 + i % 17) and 0xFF)).toChar())
        }
        return String(Base64.getDecoder().decode(r.reverse().toString()), Charsets.ISO_8859_1)
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

        /** Label|slug pairs taken from the site's genre menu. */
        private const val GENRE_PAIRS =
            "3D|3d,Action|action,Adventure|adventure,Ahegao|ahegao,Anal|anal,Animal Ears|animal-ears" +
                ",Animation|animation,BDSM|bdsm,Beastiality|beastiality,Big Boobs|big-boobs,Blackmail|blackmail" +
                ",Blowjob|blowjob,Bondage|bondage,Brainwashed|brainwashed,Bukakke|bukakke,Cat Girl|cat-girl" +
                ",Censored|censored,Comedy|comedy,Cosplay|cosplay,Creampie|creampie,Dark Skin|dark-skin" +
                ",DeepThroat|deepthroat,Demons|demons,Doctor|doctor,Double Penatration|double-penatration" +
                ",Drama|drama,Dubbed Hentai|dubbed,Ecchi|ecchi,Elf|elf,Eroge|eroge,Facesitting|facesitting" +
                ",Facial|facial,Family|family,Fantasy|fantasy,Female Doctor|female-doctor" +
                ",Female Teacher|female-teacher,Femdom|femdom,Footjob|footjob,Futanari|futanari" +
                ",Gangbang|gangbang,Gore|gore,Gyaru|gyaru,Harem|harem,Historical|historical" +
                ",Horny Slut|horny-slut,Housewife|housewife,Humiliation|humiliation,Incest|incest" +
                ",Inflation|inflation,Internal Cumshot|internal-cumshot,Lactation|lactation" +
                ",Large Breasts|large-breasts,Magical Girls|magical-girls,Maid|maid" +
                ",Martial Arts|martial-arts,Megane|megane,MILF|milf,Mind Break|mind-break" +
                ",Molestation|molestation,NTR|ntr,Nuns|nuns,Nurses|nurses,Office Ladies|office-ladies" +
                ",Police|police,POV|pov,Pregnant|pregnant,Princess|princess,Public Sex|public-sex" +
                ",Rape|rape,Reality|reality,Rim job|rim-job,Romance|romance,Scat|scat" +
                ",School Girls|school-girls,Sci-Fi|sci-fi,Shimapan|shimapan,Short|short,Slaves|slaves" +
                ",Soap|soap,Sports|sports,Squirting|squirting,Stocking|stocking,Strap-on|strap-on" +
                ",Strapped On|strapped-on,Succubus|succubus,Super Power|super-power" +
                ",Supernatural|supernatural,Swimsuit|swimsuit,Tentacles|tentacles" +
                ",Three some|three-some,Tits Fuck|tits-fuck,Torture|torture,Toys|toys" +
                ",Train Molestation|train-molestation,Tsundere|tsundere,Uncensored|uncensored" +
                ",Upcoming|upcoming,Urination|urination,Vampire|vampire,Vanilla|vanilla,Virgins|virgins" +
                ",Widow|widow,X-Ray|x-ray,Yaoi|yaoi,Yuri|yuri"

        // A real episode path (/videos/<slug>/), not the bare /videos/ nav link.
        private val EPISODE_PATH = Regex("""/videos/[^/]+/""")

        private val DATE_PUBLISHED =
            Regex(""""datePublished"\s*:\s*"([^"]+)"""")

        private val GENRE_SLUGS = arrayOf("") +
            GENRE_PAIRS.split(",").map { it.substringAfter('|') }.toTypedArray()

        private val GENRE_NAMES = arrayOf("Any") +
            GENRE_PAIRS.split(",").map { it.substringBefore('|') }.toTypedArray()
    }
}
