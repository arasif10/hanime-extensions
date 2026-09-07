/*lint:disable:standard:filename*/
package eu.kanade.tachiyomi.animeextension.all.hentaidb

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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * HentaiDB (https://hentaidb.xyz)
 *
 * The whole catalog (2067 titles) is embedded as a JS array in a hashed chunk
 * loaded from the homepage (`const D=[{s:slug,t:title,e:episodes,a:cover,...}]`).
 * Each title is a series; its episodes are /watch/{slug}-{ep} pages that embed
 * direct MP4 renditions at /v/N/{slug}-{ep}/{quality}.mp4.
 *
 * Because the catalog is a single in-memory array, the slug is threaded through
 * each request via a `slug` query parameter (the request body/HTML is unused).
 */
class HentaiDB : AnimeHttpSource() {

    override val name = "HentaiDB"

    override val baseUrl = "https://hentaidb.xyz"

    override val lang = "all"

    override val supportsLatest = true

    // Fixed source id (generateId("HentaiDB", "all", 1))
    override val id: Long = 4248040043938781823L

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")

    @Volatile private var catalogCache: List<JSONObject>? = null

    // ============================== Catalog fetch ========================

    private fun fetchCatalog(): List<JSONObject> {
        catalogCache?.let { return it }
        synchronized(this) {
            catalogCache?.let { return it }
            val home = client.newCall(GET("$baseUrl/", headers)).execute().use { it.body?.string().orEmpty() }
            val chunk = Regex("""src="(/cat-[^"]+\.js)"""")
                .find(home)?.groupValues?.get(1)
                ?: throw IOException("HentaiDB: catalog chunk not found")
            val js = client.newCall(GET("$baseUrl$chunk", headers)).execute().use { it.body?.string().orEmpty() }
            val items = extractJsonArray(js, "const D=")
                ?: throw IOException("HentaiDB: catalog array not found")
            val list = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            catalogCache = list
            return list
        }
    }

    private fun extractJsonArray(js: String, marker: String): JSONArray? {
        val start = js.indexOf(marker)
        if (start < 0) return null
        val arrStart = js.indexOf('[', start + marker.length)
        if (arrStart < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in arrStart until js.length) {
            val c = js[i]
            if (inStr) {
                if (esc) {
                    esc = false
                } else if (c == '\\') {
                    esc = true
                } else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '[', '{' -> depth++
                    ']', '}' -> {
                        depth--
                        if (depth == 0) return JSONArray(js.substring(arrStart, i + 1))
                    }
                }
            }
        }
        return null
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val items = fetchCatalog().sortedByDescending { it.optInt("n", 0) }
        return slicePage(items, response.page())
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        slicePage(fetchCatalog(), response.page())

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/?page=$page&q=${java.net.URLEncoder.encode(query, "UTF-8")}", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val query = response.request.url.queryParameter("q") ?: ""
        val all = if (query.isNotBlank()) {
            fetchCatalog().filter { it.optString("t").contains(query, ignoreCase = true) }
        } else {
            fetchCatalog()
        }
        return slicePage(all, response.page())
    }

    private fun slicePage(items: List<JSONObject>, page: Int): AnimesPage {
        val from = (page - 1) * PAGE_SIZE
        val animes = items.drop(from).take(PAGE_SIZE).map(::toAnime)
        return AnimesPage(animes, from + PAGE_SIZE < items.size)
    }

    private fun toAnime(obj: JSONObject): SAnime = SAnime.create().apply {
        title = obj.optString("t")
        url = obj.optString("s")
        obj.optString("a").takeIf { it.isNotBlank() }?.let { thumbnail_url = "$baseUrl$it" }
        initialized = false
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/".toHttpUrl().newBuilder().addQueryParameter("slug", anime.url).build(), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val slug = response.request.url.queryParameter("slug") ?: throw IOException("HentaiDB: no slug")
        val obj = fetchCatalog().firstOrNull { it.optString("s") == slug } ?: JSONObject()
        return toAnime(obj).apply { initialized = true }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/".toHttpUrl().newBuilder().addQueryParameter("slug", anime.url).build(), headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val slug = response.request.url.queryParameter("slug") ?: return emptyList()
        val obj = fetchCatalog().firstOrNull { it.optString("s") == slug }
        val count = obj?.optInt("e", 1) ?: 1
        return (1..count).map { ep ->
            SEpisode.create().apply {
                url = "$slug-$ep"
                name = "Episode $ep"
                episode_number = ep.toFloat()
                obj?.optString("a")?.takeIf { it.isNotBlank() }?.let {
                    setEpisodeField(this, "preview_url", "$baseUrl$it")
                }
            }
        }
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl/watch/${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body?.string().orEmpty()
        val mp4s = Regex("""(/v/\d+/[^"'\s]+?\.mp4[^"'\s]*)""")
            .findAll(body).map { it.groupValues[1] }.distinct().toList()
        if (mp4s.isEmpty()) throw IOException("HentaiDB: no mp4 found")
        return mp4s.map { path ->
            val name = Regex("""(\d{3,4}p?|[0-9]+p)$""").find(path)?.groupValues?.get(1) ?: "MP4"
            Video("$baseUrl$path", name, "$baseUrl$path", headers = headers)
        }
    }

    // ============================== Helpers ===============================

    private fun Response.page(): Int =
        request.url.queryParameter("page")?.toIntOrNull() ?: 1

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
    }
}
