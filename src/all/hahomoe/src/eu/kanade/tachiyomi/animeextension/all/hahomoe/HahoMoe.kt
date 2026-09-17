/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hahomoe

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Haho.moe (https://haho.moe)
 *
 * Browse/search share the /anime index: `s=` picks the sort order, `q=` a free
 * text search that also accepts tokens (genre:yuri, censorship:unc, ...) and
 * `page=` paginates. The taxonomy pages (e.g. /type/ova, /status/ongoing,
 * /censorship/censored, /source/dvd, /resolution/720p) accept the same query
 * string, so the filters simply swap the request path.
 *
 * Details pages carry the real synopsis (section.entry-description), the tag
 * taxonomy (a[href*="/genre/"]) and production/group metadata. Episode lists
 * live in ul.episode-loop; each episode links to /anime/{id}/{n}.
 *
 * The watch page embeds <iframe src="https://haho.moe/embed?v={token}"> (one
 * per mirror; the same tokens are exposed by the mirror dropdown's ?v= links).
 * The embed page serves <video><source src="https://s1.filegasm.com/..?
 * download_token=.." title="1080p"> quality MP4s.
 */
class HahoMoe : AnimeHttpSource() {

    override val name = "HahoMoe"

    override val baseUrl = "https://haho.moe"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("HahoMoe", "all", 1))
    override val id: Long = 6788476303433097775L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")
        // The site's default list view renders cards WITHOUT any <img>; the
        // thumbnail view (loop-view=thumb cookie) is the one that carries
        // <img class=image> posters.
        .add("Cookie", "loop-view=thumb")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        browseRequest(page, sort = "vwk-d")

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseCatalog(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        browseRequest(page, sort = "rel-d")

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseCatalog(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        var sort = "rel-d"
        var path: String? = null
        var genre = ""
        var tag = ""
        val genreTokens = mutableSetOf<String>()
        filterList.forEach { f ->
            when (f) {
                is SortFilter -> sort = f.sortValue
                is PathFilter -> if (path == null) path = f.selectedPath
                is GenreTokenFilter -> genre = f.state.trim()
                is TagTokenFilter -> tag = f.state.trim()
                is GenreGroup -> f.state.filter { it.state }.forEach { box ->
                    genreTokens.add(box.name)
                }
                else -> {}
            }
        }

        // Free text plus the genre/tag tokens all go into q=. Tokens AND
        // together; genre names may contain spaces ("large breasts") - only
        // the unsupported studio:/group:/year: tokens return nothing.
        // Dropdown filters swap the browse path; the first selected one wins
        // because taxonomy paths cannot be combined in one URL.
        val terms = buildList {
            if (query.isNotBlank()) add(query.trim())
            if (genre.isNotBlank()) add("genre:${genre.canonicalGenre()}")
            if (tag.isNotBlank()) add("tag:$tag")
            genreTokens.forEach { add("genre:$it") }
        }
        return browseRequest(page, sort = sort, path = path, q = terms.joinToString(" "))
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseCatalog(response)

    /**
     * Parses the response body exactly once — okhttp bodies are one-shot and a
     * second body.string() throws "closed", which broke the whole extension.
     */
    private fun parseCatalog(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val current = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return AnimesPage(catalogCards(doc), hasMoreCards(doc, current))
    }

    private fun browseRequest(page: Int, sort: String, path: String? = null, q: String? = null): Request {
        val url = "$baseUrl/${path ?: "anime"}".toHttpUrl().newBuilder()
            .addQueryParameter("s", sort)
        if (!q.isNullOrBlank()) url.addQueryParameter("q", q)
        url.addQueryParameter("page", page.toString())
        return GET(url.build().toString(), headers)
    }

    // ============================ Catalogue ===============================

    private fun catalogCards(doc: Document): List<SAnime> {
        return doc.select("li[class*=anime-]").mapNotNull { li ->
            val a = li.selectFirst("a[href*=/anime/]") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            // skip episode links (contain /anime/{id}/{n})
            if (!href.startsWith("http") || Regex("""/anime/[a-z0-9]+/\d+""").containsMatchIn(href)) {
                return@mapNotNull null
            }
            val title = a.attr("title").trim()
                .ifBlank { a.selectFirst("span.text-primary")?.text()?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            SAnime.create().apply {
                this.title = title
                url = href.removePrefix("$baseUrl/").trim('/')
                thumbnail_url = li.selectFirst("img[src^=http]")?.attr("src")
                // The card's data-content carries the real synopsis; shown until
                // the details load re-parses it from the entry page.
                val blurb = a.attr("data-content").trim()
                if (blurb.isNotBlank() && !blurb.equals("No description.", ignoreCase = true)) {
                    description = blurb
                }
            }
        }.distinctBy { it.url }
    }

    private fun hasMoreCards(doc: Document, current: Int): Boolean {
        // /anime and taxonomy pagination links all carry ?page=N
        return doc.select("a[href*=page=]").any { a ->
            (a.attr("abs:href").ifBlank { a.attr("href") })
                .substringAfter("page=", "").substringBefore('&').toIntOrNull()?.let { it > current } == true
        }
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()

        val synopsis = doc.selectFirst("section.entry-description .card-body")
            ?.textWithNewlines()

        val genres = doc.select("a[href*=/genre/]")
            .map { (it.attr("title").ifBlank { it.text() }).trim() }
            .filter { it.isNotBlank() && !it.equals("No description.", ignoreCase = true) }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

        val productions = metaValues(doc, "production")

        // Keep only the synopsis in the description: every field of the
        // site's info table is already mapped to its own SAnime property
        // below (status, author, genre), so repeating it here just
        // duplicated the text.
        val description = synopsis

        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
                .ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty() }
            thumbnail_url = doc.selectFirst("img.cover-image")?.attr("abs:src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            this.description = description
            this.genre = genres
            author = productions.joinToString(", ").takeIf { it.isNotBlank() }
            status = when (metaValues(doc, "status").firstOrNull()?.lowercase(Locale.ROOT)) {
                "ongoing" -> SAnime.ONGOING
                "completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            initialized = true
        }
    }

    /** Texts of every `.value` inside the info-list li with the given class. */
    private fun metaValues(doc: Document, key: String): List<String> =
        doc.select("li.$key.meta-data .value").map { it.text().trim() }.filter { it.isNotBlank() }

    /**
     * Element text with <br> rendered as newline and block children separated
     * by a blank line. (Element.text() collapses whitespace, which is why the
     * old code ended up shipping literal backslash-n into the UI.)
     */
    private fun Element.textWithNewlines(): String? = buildString {
        childNodes().forEach { node ->
            when (node) {
                is TextNode -> append(node.text())
                is Element -> when (node.tagName().lowercase(Locale.ROOT)) {
                    "br" -> append("\n")
                    "p", "div" -> {
                        if (isNotEmpty()) append("\n\n")
                        append(node.text())
                    }
                    else -> append(node.text())
                }
                else -> {}
            }
        }
    }
        .replace(Regex(" {2,}"), " ")
        .trim()
        .takeIf { it.isNotBlank() }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val cover = doc.selectFirst("img.cover-image")?.attr("abs:src")
        val animeId = response.request.url.encodedPath
            .substringAfter("/anime/").substringBefore('/')
            .takeIf { it.isNotBlank() }
            ?: throw IOException("HahoMoe: bad url ${response.request.url}")

        // The details page lists episodes in ul.episode-loop; the watch page
        // playlist (ol.playlist-episodes) is the fallback shape. Mirror links
        // carrying ?v= are never bare episode pages and are skipped.
        val anchors = doc.select("ul.episode-loop li a[href*=/anime/$animeId/]").ifEmpty {
            doc.select("ol.playlist-episodes li a[href*=/anime/$animeId/]")
        }.ifEmpty { doc.select("a[href^=$baseUrl/anime/$animeId/]") }

        val byNumber = LinkedHashMap<Int, SEpisode>()
        for (a in anchors) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!href.startsWith("http") || href.contains('?')) continue
            val num = Regex("""/anime/$animeId/(\d+)""").find(href)
                ?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (byNumber.containsKey(num)) continue
            val label = a.selectFirst(".label .text-primary, .eps_ttl")?.text()?.trim().orEmpty()
            byNumber[num] = SEpisode.create().apply {
                url = href.removePrefix("$baseUrl/").trim('/')
                name = if (label.isBlank() || label.equals("No Title", ignoreCase = true)) {
                    "Episode $num"
                } else {
                    "Episode $num - $label"
                }
                episode_number = num.toFloat()
                // e.g. "31st of Jan, 2025" rendered next to the episode
                date_upload = a.selectFirst(".date")?.text()?.trim()?.parseHahoDate() ?: 0L
                // The episode row's .desc data-content carries a summary;
                // the cover falls back to the series' own (haho renders no
                // per-episode image). Both fields exist only in AniZen's
                // runtime SEpisode, hence the reflective setter.
                a.selectFirst(".desc[data-content]")?.attr("data-content")?.trim()
                    ?.takeIf { it.isNotBlank() && !it.equals("No description.", ignoreCase = true) }
                    ?.let { setEpisodeField(this, "summary", it) }
                cover?.takeIf { it.isNotBlank() }?.let {
                    setEpisodeField(this, "preview_url", it)
                }
            }
        }
        if (byNumber.isEmpty()) throw IOException("HahoMoe: no episodes found")
        return byNumber.values.sortedBy { it.episode_number }
    }

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

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val watchHtml = response.body?.string().orEmpty()

        // Primary: the iframe embed. Fallback: the mirror dropdown's ?v= links
        // (used when the player iframe is injected by JS or absent).
        val embedUrl = Regex("""https://haho\.moe/embed\?v=[A-Za-z0-9]+""").find(watchHtml)?.value
            ?: Regex("""https://haho\.moe/anime/[^"'?]+\?v=([A-Za-z0-9]+)""")
                .findAll(watchHtml).firstOrNull()
                ?.let { "$baseUrl/embed?v=${it.groupValues[1]}" }
            ?: throw IOException("HahoMoe: no embed found")

        val embedHtml = client.newCall(GET(embedUrl, headers)).execute()
            .use { it.body?.string().orEmpty() }
        val doc = Jsoup.parse(embedHtml)
        val videos = doc.select("video source").mapNotNull { src ->
            val url = src.attr("abs:src").ifBlank { src.attr("src") }
            if (!url.startsWith("http")) return@mapNotNull null
            val quality = src.attr("title").ifBlank { "Auto" }
            Video(url, quality, url, headers = headers)
        }
        if (videos.isEmpty()) throw IOException("HahoMoe: no video sources")
        // Prefer highest numeric resolution first ("1080p" > "720p"), dedupe by url
        return videos.distinctBy { it.url }
            .sortedByDescending { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
    }

    // ============================== Helpers ===============================

    private fun Response.asJsoup() = Jsoup.parse(body?.string().orEmpty(), request.url.toString())

    /** Canonical spelling of a genre name (case-insensitive), when known. */
    private fun String.canonicalGenre(): String =
        GENRE_NAMES.firstOrNull { it.equals(this, ignoreCase = true) } ?: this

    /** Parses "31st of Jan, 2025" into epoch millis; 0 when unparsable. */
    private fun String.parseHahoDate(): Long {
        val m = DATE_REGEX.find(this) ?: return 0L
        val day = m.groupValues[1].toIntOrNull() ?: return 0L
        val month = MONTHS[m.groupValues[2].lowercase(Locale.ROOT).take(3)] ?: return 0L
        val year = m.groupValues[3].toIntOrNull() ?: return 0L
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }.timeInMillis
    }

    // =========================== Recommendations ==========================

    // AniZen's runtime source API (lib v16+) declares `supportsRelatedAnimes`
    // and `fetchRelatedAnimeList`. Those members exist at runtime but not on
    // the older lib-14 stub this extension compiles against, so they are
    // declared without `override` - the JVM still dispatches the runtime
    // interface default methods to them.
    //
    // Related entries come from the site's own catalogue: everything sharing
    // the entry's first genres. The search syntax ANDs genre: tokens, so a
    // single query with several of them returns the strict intersection.

    val supportsRelatedAnimes: Boolean get() = true

    suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val wanted = 12
        val related = LinkedHashMap<String, SAnime>()

        fun collect(q: String) {
            if (related.size >= wanted) return
            runCatching {
                client.newCall(browseRequest(1, sort = "vwk-d", q = q)).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    catalogCards(response.asJsoup())
                        .filter { it.title != anime.title && it.url != anime.url }
                        .forEach { entry -> related.getOrPut(entry.url) { entry } }
                }
            }
        }

        anime.genre?.split(", ")?.take(3)?.forEach { g -> collect("genre:$g") }

        return related.values.take(wanted)
    }

    // ============================== Filters ===============================

    private class SortFilter :
        AnimeFilter.Select<String>("Sort by", SORTS.map { it.first }.toTypedArray(), SORT_DEFAULT_INDEX) {
        val sortValue: String get() = SORTS[state].second
    }

    /** Taxonomy dropdown; state 0 ("Any") means "browse all of /anime". */
    private open class PathFilter(
        name: String,
        private val prefix: String,
        entries: List<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, entries.map { it.second }.toTypedArray(), 0) {
        private val slugs = entries.map { it.first }
        val selectedPath: String?
            get() = slugs[state].takeIf { it.isNotBlank() }?.let { "$prefix/$it" }
    }

    private class TypeFilter : PathFilter("Type", "type", TYPES)
    private class StatusFilter : PathFilter("Status", "status", STATUSES)
    private class CensorshipFilter : PathFilter("Censorship", "censorship", CENSORSHIPS)
    private class SourceFilter : PathFilter("Source", "source", SOURCES)
    private class ResolutionFilter : PathFilter("Resolution", "resolution", RESOLUTIONS)
    private class ContentRatingFilter : PathFilter("Content Rating", "content-rating", CONTENT_RATINGS)

    /** Single-word tokens (site limitation: multi-word tokens match nothing). */
    private class GenreTokenFilter : AnimeFilter.Text("Genre (single word, e.g. yuri)")
    private class TagTokenFilter : AnimeFilter.Text("Tag (single word, e.g. netorare)")

    /** One checkbox inside a GENRE_GROUPS group. */
    private class GenreCheckbox(name: String) : AnimeFilter.CheckBox(name)

    /** Categorized genre checkboxes mirroring the site's genre tree. */
    private class GenreGroup(name: String, values: List<String>) :
        AnimeFilter.Group<GenreCheckbox>(name, values.map { GenreCheckbox(it) })

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Dropdown = browse filter (first selected wins);"),
        AnimeFilter.Header("Genre/Tag tokens combine with the search text"),
        TypeFilter(),
        StatusFilter(),
        CensorshipFilter(),
        SourceFilter(),
        ResolutionFilter(),
        ContentRatingFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Genres - the site's categorized tree; selections AND with the search"),
        *GENRE_GROUPS.map { (parent, names) -> GenreGroup(parent, names) }.toTypedArray(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Free-text tokens; combine with the search text"),
        GenreTokenFilter(),
        TagTokenFilter(),
    )

    /** Canonical genre names, for case-normalizing free-text tokens. */
    private val GENRE_NAMES = listOf(
        "3D CG animation",
        "Africa",
        "Akihabara",
        "Americas",
        "Animerama",
        "Asia",
        "BDSM",
        "Bakumatsu - Meiji Period",
        "Buddhism",
        "CG collection",
        "CGI",
        "China",
        "Christianity",
        "ComicFesta Anime Zone",
        "DESCRIPTION NEEDS IMPROVEMENT",
        "Earth",
        "Egypt",
        "Engrish",
        "Europe",
        "European stylised",
        "FFM threesome",
        "France",
        "French kiss",
        "Gainax bounce",
        "Germany",
        "Greek mythology",
        "Hong Kong",
        "India",
        "Injuu Hentai Series",
        "Japan",
        "Japanese mythology",
        "Japanese production",
        "Journey to the West",
        "Korea",
        "MMF threesome",
        "MMM threesome",
        "Middle East",
        "Nagasaki",
        "Norse mythology",
        "One Thousand and One Nights",
        "Path",
        "RPG",
        "Secret Anima",
        "Secret Anima Series",
        "Shinjuku",
        "South Korean production",
        "TV censoring",
        "Three Kingdoms",
        "Tokugawa period",
        "Tokyo",
        "United States",
        "Vanilla Series",
        "Weekly Shounen Jump",
        "World War II",
        "Yokohama",
        "absurdist humour",
        "action",
        "action game",
        "adapted into JDrama",
        "adapted into Japanese movie",
        "adapted into other media",
        "adults are useless",
        "adventure",
        "age difference romance",
        "ahegao",
        "air force",
        "alcohol",
        "alien",
        "alien invasion",
        "all-boys school",
        "all-girls school",
        "alternating animation style",
        "alternative past",
        "alternative present",
        "amnesia",
        "anal",
        "anal fingering",
        "anal pissing",
        "android",
        "angel",
        "angst",
        "animal abuse",
        "animal protagonist",
        "anthropomorphism",
        "aphrodisiac",
        "archery",
        "ass-kicking girls",
        "assjob",
        "association football",
        "attempted rape",
        "aunt-nephew incest",
        "autofellatio",
        "autumn",
        "bad cooking",
        "baseball",
        "basketball",
        "be careful what you wish for",
        "bestiality",
        "betrayal",
        "bishoujo",
        "bishounen",
        "bitter-sweet",
        "black humour",
        "blackmail",
        "board games",
        "body and host",
        "body exchange",
        "body takeover",
        "bondage",
        "borderline porn",
        "boxing",
        "boy meets girl",
        "brainwashing",
        "branching story",
        "breast expansion",
        "breast fondling",
        "breasts",
        "brother-sister incest",
        "bukkake",
        "bullying",
        "call my name",
        "calling your attacks",
        "car crash",
        "cast",
        "castaway",
        "catholic school",
        "censored uncensored version",
        "cervix penetration",
        "cheating",
        "chikan",
        "child abuse",
        "classical music",
        "cockring",
        "collateral damage",
        "colour coded",
        "combat",
        "comedy",
        "coming of age",
        "competition",
        "conspiracy",
        "contemporary fantasy",
        "content indicators",
        "contraband",
        "cooking",
        "cops",
        "corrupt church",
        "corrupt nobility",
        "cosplaying",
        "countryside",
        "cram school",
        "creampie",
        "crime",
        "cross-dressing",
        "cum play",
        "cum swapping",
        "cunnilingus",
        "curse",
        "cyberpunk",
        "cybersex",
        "cyborg",
        "daily life",
        "damsel in distress",
        "dancing",
        "dark",
        "dark atmosphere",
        "dark elf",
        "dark fantasy",
        "dark-skinned girl",
        "death",
        "defeat means friendship",
        "deflowering",
        "deity",
        "delinquent",
        "dementia",
        "demon",
        "demon hunt",
        "demonic power",
        "desert",
        "despair",
        "detective",
        "dildos - vibrators",
        "disaster",
        "discontinued",
        "disturbing",
        "divorce",
        "doggy style",
        "dominatrix",
        "double fellatio",
        "double penetration",
        "double-sided dildo",
        "doujin",
        "dragon",
        "drastic change of life",
        "dreams",
        "dreams and reality",
        "drugs",
        "dungeon",
        "dutch wife",
        "dynamic",
        "dysfunctional family",
        "dystopia",
        "earthquake",
        "eating of humans",
        "ecchi",
        "elements",
        "elf",
        "emotions awaken superpowers",
        "ending",
        "enema",
        "enjo-kousai",
        "enjoyable rape",
        "entertainment industry",
        "episodic",
        "erotic asphyxiation",
        "erotic game",
        "erotic torture",
        "everybody dies",
        "everybody has sex",
        "evil military",
        "excessive censoring",
        "exhibitionism",
        "exorcism",
        "experimental animation",
        "extrasensory perception",
        "eye penetration",
        "faceless background characters",
        "facesitting",
        "facial distortion",
        "fairy",
        "fake relationship",
        "family life",
        "family without mother",
        "fantasy",
        "father-daughter incest",
        "felching",
        "fellatio",
        "female protagonist",
        "female rapes female",
        "female student",
        "female teacher",
        "femdom",
        "feminism",
        "fetishes",
        "feudal warfare",
        "fictional location",
        "fighting",
        "fingering",
        "fire",
        "first love",
        "fishing",
        "fisting",
        "foot fetish",
        "footage reuse",
        "footjob",
        "forbidden love",
        "foreskin sex",
        "foursome",
        "friendship",
        "full HD version available",
        "funny expressions",
        "futa x female",
        "futa x futa",
        "futa x male",
        "futanari",
        "future",
        "game",
        "gang bang",
        "gang rape",
        "gangs",
        "gender bender",
        "genetic modification",
        "ghost",
        "ghost hunting",
        "giant insects",
        "gigantic breasts",
        "girl rapes girl",
        "girly tears",
        "glory hole",
        "go",
        "goblin",
        "god is a girl",
        "gokkun",
        "golden shower",
        "gore",
        "grandiose displays of wealth",
        "groping",
        "group love",
        "group sex",
        "gunfights",
        "guro",
        "gymnastics",
        "half-length episodes",
        "handjob",
        "happy ending",
        "harem",
        "heaven",
        "hell",
        "henshin",
        "heroic sacrifice",
        "hidden agenda",
        "hidden vibrator",
        "high fantasy",
        "high school",
        "historical",
        "horny nosebleed",
        "horror",
        "hospital",
        "hostage situation",
        "housewives",
        "huge breasts",
        "human cannibalism",
        "human enhancement",
        "human experimentation",
        "human sacrifice",
        "human-android love",
        "humanoid alien",
        "hyperspace mallet",
        "i got a crush on you",
        "ice skating",
        "idol",
        "immortality",
        "imperial stormtrooper marksmanship academy",
        "important haircut",
        "impregnation",
        "impregnation with larvae",
        "improbable physics",
        "in medias res",
        "incest",
        "infidelity",
        "inter-dimensional schoolgirl",
        "intercrural sex",
        "internal shots",
        "isekai",
        "island",
        "jealousy",
        "just as planned",
        "juujin",
        "kamikaze",
        "kendo",
        "kidnapping",
        "killing criminals",
        "lactation",
        "large breasts",
        "law and order",
        "library",
        "light-hearted",
        "lingerie",
        "live-action imagery",
        "loli",
        "long episodes",
        "love at first sight",
        "love between enemies",
        "love polygon",
        "macabre",
        "mafia",
        "magic",
        "magic circles",
        "magic weapons",
        "magical girl",
        "maid",
        "main character dies",
        "maintenance tags",
        "male protagonist",
        "male rape victim",
        "mammary intercourse",
        "manga",
        "manipulation",
        "martial arts",
        "massacre",
        "master-servant relationship",
        "master-slave relation",
        "masturbation",
        "mecha",
        "mechanical tentacle",
        "medieval",
        "medium awareness",
        "merchandising show",
        "mermaid",
        "meta tags",
        "middle school",
        "military",
        "military is useless",
        "mind fuck",
        "misunderstanding",
        "molestation",
        "money",
        "monster of the week",
        "mother-daughter incest",
        "mother-son incest",
        "movie",
        "multi-anime projects",
        "multi-segment episodes",
        "multiple couples",
        "murder",
        "murder of family members",
        "music",
        "musical band",
        "mutation",
        "mutilation",
        "mystery",
        "mythology",
        "narration",
        "navel fuck",
        "navy",
        "nearly almighty protagonist",
        "necrophilia",
        "nervous breakdown",
        "netorare",
        "netori",
        "new",
        "ninja",
        "nipple penetration",
        "non-linear",
        "nostril hook",
        "not for kids",
        "novel",
        "nudity",
        "nun",
        "nurse",
        "nurse office",
        "nyotaimori",
        "occult",
        "occupation and career",
        "ocean",
        "off-model animation",
        "office lady",
        "older female younger male",
        "omnibus format",
        "onahole",
        "onmyoudou",
        "open-ended",
        "oral",
        "orgasm denial",
        "orgy",
        "origin",
        "original work",
        "otaku culture",
        "other planet",
        "out-of-body experience",
        "outdoor sex",
        "oyakodon",
        "painting",
        "pantsu",
        "panty theft",
        "pantyjob",
        "paper clothes",
        "parallel world",
        "parasite",
        "parental abandonment",
        "parody",
        "parricide",
        "past",
        "pegging",
        "performance",
        "photographic backgrounds",
        "photography",
        "pillory",
        "piloted robot",
        "pirate",
        "place",
        "plot continuity",
        "plot twists",
        "plot with porn",
        "point of view",
        "police",
        "police are useless",
        "pornography",
        "post-apocalyptic",
        "poverty",
        "power corrupts",
        "power suit",
        "predominantly adult cast",
        "predominantly female cast",
        "predominantly male cast",
        "pregnant sex",
        "present",
        "prison",
        "promise",
        "prostate massage",
        "prostitution",
        "proxy battles",
        "psychoactive drugs",
        "psychological",
        "psychological manipulation",
        "psychological sexual abuse",
        "public sex",
        "pussy sandwich",
        "rape",
        "real-world location",
        "rebellion",
        "recycled animation",
        "red-light district",
        "reincarnation",
        "religion",
        "remastered version available",
        "restaurant",
        "revenge",
        "reverse harem",
        "reverse spitroast",
        "reverse trap",
        "rimming",
        "rivalry",
        "robot",
        "romance",
        "rotten world",
        "rugby",
        "running gag",
        "safer sex",
        "sakura",
        "samurai",
        "scat",
        "school clubs",
        "school dormitory",
        "school for the rich elite",
        "school life",
        "science fiction",
        "scissoring",
        "season",
        "seiyuu",
        "self-parody",
        "setting",
        "sex",
        "sex change",
        "sex doll",
        "sex tape",
        "sex toys",
        "sex while on the phone",
        "sexual abuse",
        "sexual fantasies",
        "shibari",
        "shinsengumi",
        "shipboard",
        "short episodes",
        "short movie",
        "short story collection",
        "shota",
        "shoujo ai",
        "shounen ai",
        "sibling rivalry",
        "sibling yin yang",
        "sister-sister incest",
        "sixty-nine",
        "skimpy clothing",
        "slapstick",
        "slavery",
        "sleeping sex",
        "slide show animation",
        "slow when it comes to love",
        "slums",
        "small breasts",
        "soapland",
        "social class issues",
        "social commentary",
        "softball",
        "some weird shit goin` on",
        "space",
        "space pirates",
        "space travel",
        "spacing out",
        "spanking",
        "special squads",
        "speculative fiction",
        "spellcasting",
        "spirit realm",
        "spirits",
        "spiritual powers",
        "spitroast",
        "sports",
        "spring",
        "squirting",
        "stand-alone movie",
        "stereotypes",
        "stomach bulge",
        "stomach stretch",
        "storytelling",
        "strap-on dildo",
        "strapon",
        "strappado",
        "strappado bondage",
        "strong female lead",
        "strong male lead",
        "student government",
        "submission",
        "succubus",
        "sudden girlfriend appearance",
        "sudden naked girl appearance",
        "suicide",
        "sumata",
        "summer",
        "summoning",
        "super deformed",
        "super power",
        "superhero",
        "surreal",
        "survival",
        "suspension bondage",
        "swimming",
        "swordplay",
        "table tennis",
        "tales",
        "tank warfare",
        "teacher x student",
        "technical aspects",
        "tennis",
        "tentacle",
        "the arts",
        "the power of love",
        "themes",
        "thievery",
        "thigh sex",
        "threesome",
        "threesome with sisters",
        "thriller",
        "throat fucking",
        "time",
        "time loop",
        "time travel",
        "torture",
        "tournament",
        "track and field",
        "tragedy",
        "tragic beginning",
        "training",
        "transforming craft",
        "transforming weapons",
        "trap",
        "trapped",
        "triple penetration",
        "tropes",
        "tsunami",
        "twincest",
        "ukiyo-e",
        "uncle-niece incest",
        "undead",
        "under one roof",
        "unexpected inheritance",
        "uniform fetish",
        "unintentional comedy",
        "university",
        "unrequited love",
        "unrequited shounen ai",
        "unsorted",
        "urethra penetration",
        "urination",
        "urophagia",
        "vampire",
        "video game development",
        "violence",
        "violent retribution for accidental infringement",
        "virtual world",
        "visible aura",
        "visual novel",
        "volleyball",
        "voyeurism",
        "waitress",
        "wakamezake",
        "wardrobe malfunction",
        "water sex",
        "watercolour style",
        "wax play",
        "whip",
        "whipping",
        "window fuck",
        "winter",
        "wooden horse",
        "working life",
        "world domination",
        "wrestling",
        "yaoi",
        "youji play",
        "yuri",
        "zero to hero",
        "zombie",
    )

    /**
     * The site's genre tree (247 genres grouped under 10 top-level parents,
     * crawled from the details-page genre tree), plus the full /genre index
     * (673 names) for case-normalizing free-text tokens. Slugs are opaque
     * hashes, so filters query by name: "genre:<name>".
     */
    private val GENRE_GROUPS = listOf(
        "elements" to listOf(
            "BDSM",
            "FFM threesome",
            "French kiss",
            "Gainax bounce",
            "MMF threesome",
            "action",
            "ahegao",
            "anal",
            "anal fingering",
            "angel",
            "angst",
            "assjob",
            "bestiality",
            "blackmail",
            "bondage",
            "borderline porn",
            "boy meets girl",
            "brainwashing",
            "brother-sister incest",
            "bukkake",
            "call my name",
            "cervix penetration",
            "comedy",
            "creampie",
            "cum play",
            "cunnilingus",
            "dark elf",
            "dark fantasy",
            "demon",
            "detective",
            "dildos - vibrators",
            "doggy style",
            "double fellatio",
            "double penetration",
            "double-sided dildo",
            "ecchi",
            "elf",
            "enema",
            "enjo-kousai",
            "enjoyable rape",
            "erotic asphyxiation",
            "erotic torture",
            "everybody has sex",
            "exhibitionism",
            "facesitting",
            "fake relationship",
            "fantasy",
            "father-daughter incest",
            "fellatio",
            "female rapes female",
            "femdom",
            "fisting",
            "foursome",
            "gang bang",
            "gang rape",
            "girl rapes girl",
            "god is a girl",
            "gokkun",
            "golden shower",
            "gunfights",
            "handjob",
            "harem",
            "henshin",
            "hidden vibrator",
            "horny nosebleed",
            "horror",
            "impregnation",
            "incest",
            "internal shots",
            "lactation",
            "lingerie",
            "love polygon",
            "magic",
            "magic circles",
            "male rape victim",
            "mammary intercourse",
            "master-servant relationship",
            "masturbation",
            "mother-daughter incest",
            "mother-son incest",
            "nipple penetration",
            "nostril hook",
            "orgy",
            "outdoor sex",
            "oyakodon",
            "pantsu",
            "pantyjob",
            "paper clothes",
            "pegging",
            "pillory",
            "plot with porn",
            "point of view",
            "pregnant sex",
            "prostate massage",
            "prostitution",
            "public sex",
            "pussy sandwich",
            "rape",
            "reverse spitroast",
            "rimming",
            "romance",
            "safer sex",
            "scat",
            "scissoring",
            "sex tape",
            "sex toys",
            "sex while on the phone",
            "sexual fantasies",
            "shibari",
            "sister-sister incest",
            "sixty-nine",
            "skimpy clothing",
            "slapstick",
            "spanking",
            "squirting",
            "stomach bulge",
            "stomach stretch",
            "strapon",
            "strappado",
            "submission",
            "succubus",
            "sumata",
            "summoning",
            "teacher x student",
            "thigh sex",
            "threesome",
            "threesome with sisters",
            "throat fucking",
            "tragedy",
            "triple penetration",
            "twincest",
            "under one roof",
            "urination",
            "urophagia",
            "vampire",
            "visible aura",
            "voyeurism",
            "water sex",
            "whip",
            "window fuck",
            "youji play",
            "yuri",
            "zero to hero",
        ),
        "original work" to listOf(
            "CG collection",
            "erotic game",
            "game",
            "manga",
            "new",
            "novel",
            "visual novel",
        ),
        "content indicators" to listOf(
            "gore",
            "nudity",
            "sex",
            "violence",
        ),
        "fetishes" to listOf(
            "breast expansion",
            "cross-dressing",
            "dark-skinned girl",
            "deflowering",
            "female student",
            "female teacher",
            "foot fetish",
            "footjob",
            "futa x female",
            "futanari",
            "gigantic breasts",
            "housewives",
            "huge breasts",
            "juujin",
            "large breasts",
            "loli",
            "maid",
            "nun",
            "nurse",
            "office lady",
            "shota",
            "small breasts",
            "tentacle",
            "trap",
            "uniform fetish",
            "waitress",
        ),
        "technical aspects" to listOf(
            "ComicFesta Anime Zone",
            "Vanilla Series",
            "full HD version available",
            "half-length episodes",
            "live-action imagery",
            "short episodes",
        ),
        "setting" to listOf(
            "Asia",
            "Earth",
            "Japan",
            "fictional location",
            "island",
            "nurse office",
            "ocean",
            "past",
            "present",
            "summer",
            "winter",
        ),
        "themes" to listOf(
            "alcohol",
            "betrayal",
            "bullying",
            "castaway",
            "cops",
            "corrupt church",
            "corrupt nobility",
            "cosplaying",
            "crime",
            "drugs",
            "everybody dies",
            "high school",
            "human sacrifice",
            "infidelity",
            "isekai",
            "law and order",
            "murder",
            "murder of family members",
            "netorare",
            "netori",
            "otaku culture",
            "promise",
            "revenge",
            "rotten world",
            "school clubs",
            "school life",
            "sports",
            "tennis",
            "university",
            "video game development",
        ),
        "unsorted" to listOf(
            "cheating",
            "girly tears",
            "library",
            "merchandising show",
            "psychological manipulation",
            "tragic beginning",
            "trapped",
        ),
        "dynamic" to listOf(
            "branching story",
            "female protagonist",
            "male protagonist",
            "narration",
            "omnibus format",
            "open-ended",
            "plot continuity",
            "predominantly adult cast",
            "short story collection",
            "strong female lead",
            "time loop",
        ),
        "origin" to listOf(
            "Japanese production",
            "South Korean production",
        ),
    )

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private val DATE_REGEX = Regex("""(\d{1,2})(?:st|nd|rd|th)?\s+of\s+(\w+),?\s+(\d{4})""")

        private val MONTHS = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
        )

        /** (label, s= value) — order follows the site's sort dropdown. */
        private val SORTS = listOf(
            "Latest Released" to "rel-d",
            "Earliest Released" to "rel-a",
            "A to Z" to "az-a",
            "Z to A" to "az-d",
            "First Added" to "add-a",
            "Last Added" to "add-d",
            "Most Bookmarked" to "bkm-d",
            "Least Bookmarked" to "bkm-a",
            "Highest Rated" to "rtg-d",
            "Lowest Rated" to "rtg-a",
            "Most Popular" to "vtt-d",
            "Least Popular" to "vtt-a",
            "Most Popular Today" to "vdy-d",
            "Least Popular Today" to "vdy-a",
            "Most Popular This Week" to "vwk-d",
            "Least Popular This Week" to "vwk-a",
            "Most Popular This Month" to "vmt-d",
            "Least Popular This Month" to "vmt-a",
            "Most Popular This Year" to "vyr-d",
            "Least Popular This Year" to "vyr-a",
        )
        private const val SORT_DEFAULT_INDEX = 0

        private val TYPES = listOf(
            "" to "Any", "unknown" to "Unknown", "tv-series" to "TV Series",
            "ova" to "OVA", "movie" to "Movie", "web" to "Web",
            "music-video" to "Music Video", "tv-special" to "TV Special", "other" to "Other",
        )

        private val STATUSES = listOf(
            "" to "Any",
            "unknown" to "Unknown",
            "ongoing" to "Ongoing",
            "completed" to "Completed",
            "stalled" to "Stalled",
        )

        private val CENSORSHIPS = listOf(
            "" to "Any",
            "n-a" to "N/A",
            "censored" to "Censored",
            "uncensored" to "Uncensored",
        )

        private val SOURCES = listOf(
            "" to "Any", "n-a" to "N/A", "tv" to "TV", "web" to "Web", "dvd" to "DVD",
            "bd" to "Blu-ray", "vhs" to "VHS", "vcd" to "VCD", "ld" to "LD",
        )

        private val RESOLUTIONS = listOf(
            "" to "Any",
            "n-a" to "N/A",
            "360p" to "360p",
            "480p" to "480p",
            "576p" to "576p",
            "720p" to "720p",
            "1080p" to "1080p",
        )

        private val CONTENT_RATINGS = listOf(
            "" to "Any",
            "unknown" to "Unknown",
            "g" to "G - All Ages",
            "pg" to "PG - Children",
            "pg13" to "PG-13 - Teens 13+",
            "rplus" to "R+ - Mild Nudity",
            "r17plus" to "R - 17+ (violence & profanity)",
            "rx" to "Rx - Hentai",
        )
    }
}
