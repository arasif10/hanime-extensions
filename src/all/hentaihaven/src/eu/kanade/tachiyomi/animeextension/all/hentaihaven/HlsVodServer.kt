package eu.kanade.tachiyomi.animeextension.all.hentaihaven

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Minimal loopback HTTP server that re-serves the octopus HLS stream as a clean VOD
 * stream with in-band audio, so the app's player muxes the video + the Japanese audio
 * rendition automatically (no separate external-audio toggle) and can seek forward
 * like it would on any normal file.
 *
 * What it serves:
 *  - `GET /master.m3u8?uuid=…&q=720p`  – a synthesized master playlist referencing the
 *    requested video variant AND the audio rendition through this same server, with the
 *    audio group marked DEFAULT=YES (players pick it up on their own) and subtitles as
 *    an optional group.
 *  - `GET /media.m3u8?uuid=…&path=…`   – a proxied media playlist (variants are
 *    video-only fMP4, the audio playlist is `snd/a.m3u8`). Segment/map/key URIs are
 *    rewritten through this server and `#EXT-X-PLAYLIST-TYPE:VOD` + `#EXT-X-ENDLIST`
 *    are injected so mpv treats it as VOD instead of a live window. That is what fixes
 *    the "skip ahead into an unloaded region → loads forever" bug.
 *  - `GET /seg?uuid=…&url=…`           – proxies a single segment / init / key from the
 *    CDN (with the browser-like Referer/Origin headers the CDN requires).
 *
 * The server binds to 127.0.0.1 only; only the app process can reach it.
 */
internal class HlsVodServer(
    private val client: OkHttpClient,
    private val baseHeaders: Headers,
    private val uuid: String,
    private val masterText: String,
) {
    companion object {
        @Volatile
        private var current: HlsVodServer? = null

        private const val M3U8 = "application/vnd.apple.mpegurl"
        private val MEDIA = Regex("#EXT-X-MEDIA:(.+)")
        private val STREAM_INF = Regex("#EXT-X-STREAM-INF:(.+)\r?\n(.+)")

        /** One live server per extension instance; a fresh one closes the old one. */
        fun forMaster(client: OkHttpClient, headers: Headers, uuid: String, master: String): HlsVodServer {
            current?.close()
            return HlsVodServer(client, headers, uuid, master).also { current = it }
        }
    }

    private val cdnBase = "https://octopusmanifest.org/$uuid/"
    private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()

    init {
        executor.execute {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    executor.execute { handle(socket) }
                } catch (_: IOException) {
                    break
                }
            }
        }
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}/"

    /** Public play URL for a given quality label ("720p"). */
    fun vodUrl(quality: String): String = "${baseUrl}master.m3u8?uuid=$uuid&q=${enc(quality)}"

    fun close() {
        try {
            server.close()
        } catch (_: IOException) {
        }
        executor.shutdownNow()
    }

    // ============================== request handling ==============================

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val reader = s.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                var line = reader.readLine()
                while (!line.isNullOrBlank()) line = reader.readLine()

                val rawTarget = parts[1]
                val qMark = rawTarget.indexOf('?')
                val path = if (qMark >= 0) rawTarget.substring(0, qMark) else rawTarget
                val query = parseQuery(if (qMark >= 0) rawTarget.substring(qMark + 1) else "")

                val (code, body, type) = route(path, query)
                val out = s.getOutputStream()
                var header = "HTTP/1.1 $code ${statusText(code)}\r\n"
                header += "Content-Type: $type\r\n"
                header += "Content-Length: ${body.size}\r\n"
                header += "Cache-Control: no-store\r\n"
                header += "Connection: close\r\n\r\n"
                out.write(header.toByteArray(StandardCharsets.UTF_8))
                if (method != "HEAD") out.write(body)
                out.flush()
            } catch (_: Exception) {
                // Never let one bad request take down future playback opens.
            }
        }
    }

    private fun route(path: String, query: Map<String, String>): Triple<Int, ByteArray, String> {
        val id = query["uuid"] ?: uuid
        return when (path) {
            "/master.m3u8" -> synthMaster(id, query["q"] ?: "")
            "/media.m3u8" -> proxyPlaylist(id, query["path"] ?: "")
            "/seg" -> proxySegment(dec(query["url"] ?: ""))
            else -> Triple(404, ByteArray(0), "text/plain")
        }
    }

    // ============================== playlist synthesis ==============================

    private data class Media(val attrs: String, val uri: String)
    private data class Variant(val attrs: String, val uri: String, val height: Int)

    private val TYPE_AUDIO = "AUDIO"
    private val TYPE_SUBTITLES = "SUBTITLES"

    /** Build a VOD master that carries the requested variant plus the Japanese audio. */
    private fun synthMaster(id: String, quality: String): Triple<Int, ByteArray, String> {
        val cdn = "https://octopusmanifest.org/$id/"
        val audio = mediaOf(TYPE_AUDIO).firstOrNull()
        val subs = mediaOf(TYPE_SUBTITLES)
        val all = variants(cdn)

        // Prefer the requested quality; otherwise the highest resolution.
        val chosen = if (quality.isNotEmpty()) {
            all.firstOrNull { "${it.height}p" == quality } ?: all.maxByOrNull { it.height }
        } else {
            all.maxByOrNull { it.height }
        } ?: return Triple(404, ByteArray(0), "text/plain")

        val sb = StringBuilder()
        sb.append("#EXTM3U\n#EXT-X-VERSION:7\n")
        if (audio != null) {
            val audioPath = audio.uri.removePrefix(cdn)
            sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aac\",NAME=\"Japanese\",LANGUAGE=\"ja\",")
                .append("DEFAULT=YES,AUTOSELECT=YES,URI=\"media.m3u8?uuid=").append(id)
                .append("&path=").append(enc(audioPath)).append("\"\n")
        }
        subs.forEach { s ->
            val subPath = s.uri.removePrefix(cdn)
            sb.append("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"")
                .append(attr(s.attrs, "NAME") ?: "Subs").append("\",LANGUAGE=\"")
                .append(attr(s.attrs, "LANGUAGE") ?: "und")
                .append("\",DEFAULT=NO,AUTOSELECT=NO,FORCED=NO,URI=\"media.m3u8?uuid=")
                .append(id).append("&path=").append(enc(subPath)).append("\"\n")
        }
        var streamInf = chosen.attrs + if (audio != null) ",AUDIO=\"aac\"" else ""
        if (subs.isNotEmpty()) streamInf += ",SUBTITLES=\"subs\""
        sb.append("#EXT-X-STREAM-INF:").append(streamInf).append("\n")
        sb.append("media.m3u8?uuid=").append(id)
            .append("&path=").append(enc(chosen.uri.removePrefix(cdn))).append("\n")
        return Triple(200, sb.toString().toByteArray(), M3U8)
    }

    /**
     * Proxy a variant/audio playlist: rewrite every segment/map/key reference through
     * `/seg`, then inject VOD markers so mpv seeks like a normal file instead of
     * treating the stream as a live window.
     */
    private fun proxyPlaylist(id: String, path: String): Triple<Int, ByteArray, String> {
        if (path.isEmpty()) return Triple(404, ByteArray(0), "text/plain")
        val playlistUrl = cdnBase + path
        val (code, body) = fetch(playlistUrl)
        if (code != 200) return Triple(code, body, M3U8)
        val playlistBase = playlistUrl.substringBeforeLast('/') + "/"
        val out = StringBuilder()
        for (rawLine in String(body, StandardCharsets.UTF_8).split("\n")) {
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-MAP:") || line.startsWith("#EXT-X-KEY:") -> {
                    val uri = attr(line, "URI")
                    out.append(
                        if (uri != null) line.replaceFirst(uri, segLink(id, resolve(playlistBase, uri))) else line,
                    ).append('\n')
                }
                line.startsWith("#") -> out.append(line).append('\n')
                line.isNotBlank() -> out.append(segLink(id, resolve(playlistBase, line))).append('\n')
                else -> out.append('\n')
            }
        }
        // The upstream playlist already carries #EXT-X-PLAYLIST-TYPE:VOD in most
        // cases, so only inject it when missing. Duplicating the tag confuses mpv.
        var result = out.toString().trimEnd()
        if (!result.contains("#EXT-X-PLAYLIST-TYPE:VOD")) {
            result = result.replaceFirst("#EXT-X-VERSION:", "#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-VERSION:")
        }
        if (!result.contains("#EXT-X-ENDLIST")) {
            result += "\n#EXT-X-ENDLIST"
        }
        return Triple(200, "$result\n".toByteArray(), M3U8)
    }

    private fun proxySegment(url: String): Triple<Int, ByteArray, String> {
        if (url.isEmpty()) return Triple(404, ByteArray(0), "text/plain")
        val (code, body) = fetch(url)
        return Triple(code, body, contentTypeFor(url))
    }

    private fun segLink(id: String, absUrl: String): String = "/seg?uuid=$id&url=${enc(absUrl)}"

    // ============================== master parsing ==============================

    private fun mediaOf(type: String): List<Media> = MEDIA.findAll(masterText).mapNotNull { m ->
        val attrs = m.groupValues[1]
        val uri = attr(attrs, "URI")
        if (attr(attrs, "TYPE") == type && uri != null) Media(attrs, resolve(cdnBase, uri)) else null
    }.toList()

    private fun variants(base: String): List<Variant> = STREAM_INF.findAll(masterText).mapNotNull { m ->
        val uri = m.groupValues[2].trim().takeIf { it.isNotBlank() && !it.startsWith("#") }
            ?: return@mapNotNull null
        val attrs = m.groupValues[1]
        Variant(attrs, resolve(base, uri), attr(attrs, "RESOLUTION")?.substringAfter('x')?.toIntOrNull() ?: 0)
    }.toList()

    private fun attr(input: String, key: String): String? =
        Regex("$key=\"([^\"]+)\"").find(input)?.groupValues?.get(1)

    /** Resolves a possibly-relative playlist URI against the CDN base for the episode. */
    private fun resolve(base: String, uri: String): String =
        if (uri.startsWith("http")) uri else base.trimEnd('/') + "/" + uri.trimStart('/')

    // ============================== plumbing ==============================

    private fun fetch(url: String): Pair<Int, ByteArray> = try {
        val req = Request.Builder().url(url).apply {
            baseHeaders.forEach { (k, v) -> addHeader(k, v) }
        }.build()
        client.newCall(req).execute().use { res -> res.code to res.body.bytes() }
    } catch (_: Exception) {
        500 to ByteArray(0)
    }

    private fun parseQuery(q: String): Map<String, String> = if (q.isEmpty()) {
        emptyMap()
    } else {
        q.split("&").mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) null else it.substring(0, eq) to dec(it.substring(eq + 1))
        }.toMap()
    }

    private fun contentTypeFor(url: String): String = when {
        url.endsWith(".m3u8") -> M3U8
        url.endsWith(".ts") -> "video/mp2t"
        url.endsWith(".vtt") -> "text/vtt"
        // The CDN disguises fMP4 segments as .html to dodge hotlink checks.
        url.endsWith(".mp4") || url.endsWith(".m4s") || url.endsWith(".m4a") ||
            url.endsWith(".html") -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8.toString())
    private fun dec(s: String): String = URLDecoder.decode(s, StandardCharsets.UTF_8.toString())
}
