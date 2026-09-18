package com.rashaba

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.BufferedOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import kotlin.concurrent.thread

class RashabaProvider : MainAPI() {
    override var name = "rashaba.com"
    override var mainUrl = "https://rashaba.com"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = true

    private val sections = listOf(
        "$mainUrl/" to "نوێترین",
        "$mainUrl/index.php?category=1" to "فیلم",
        "$mainUrl/index.php?category=3" to "زنجیرە",
        "$mainUrl/index.php?category=1012" to "فیلم کارتۆن",
        "$mainUrl/index.php?category=9" to "کۆمیدی",
        "$mainUrl/index.php?category=1013" to "موزیک",
        "$mainUrl/index.php?category=5" to "وەرزش",
        "$mainUrl/index.php?category=6" to "گەشت و گوزار",
        "$mainUrl/index.php?category=8" to "خەڵک و بلۆگەکان",
        "$mainUrl/index.php?category=12" to "فێرکاری",
        "$mainUrl/index.php?category=1016" to "تەندروستی",
        "$mainUrl/index.php?category=7" to "یاری",
        "$mainUrl/index.php?category=10" to "بەڵگەفیلم",
        "$mainUrl/index.php?category=11" to "هەواڵ و سیاسەت",
        "$mainUrl/index.php?category=13" to "پیشەسازی",
        "$mainUrl/index.php?category=1011" to "تەکنۆلۆژیا",
        "$mainUrl/index.php?category=1015" to "ئایین",
        "$mainUrl/index.php?category=4" to "ئاژەڵ و باڵندە",
        "$mainUrl/index.php?category=0" to "ئەوانی تر",
    )

    override val mainPage = mainPageOf(*sections.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionName = request.name ?: ""
        val sectionUrl = request.data

        val detectedUrl: String?
        val detectedName: String
        if (!sectionUrl.isNullOrBlank()) {
            detectedUrl = sectionUrl
            detectedName = sectionName
        } else if (sectionName.contains("||RASHABA::")) {
            detectedUrl = sectionName.substringAfter("||RASHABA::")
            detectedName = sectionName.substringBefore("||RASHABA::")
        } else {
            detectedUrl = null
            detectedName = sectionName
        }

        if (detectedUrl != null) {
            val url = if (page <= 1) detectedUrl else ajaxUrl(detectedUrl, page)
            val html = app.get(url).text
            val cards: List<SearchResponse>
            val hasMore: Boolean
            if (page <= 1) {
                cards = parseCards(html)
                hasMore = cards.isNotEmpty()
            } else {
                val json = org.json.JSONObject(html)
                cards = parseCards(json.getString("html"))
                hasMore = json.optBoolean("hasMore", false)
            }
            return newHomePageResponse(
                listOf(HomePageList(detectedName, cards)),
                hasNext = hasMore
            )
        }

        val lists = mutableListOf<HomePageList>()
        sections.forEach { (base, title) ->
            val html = runCatching { app.get(base).text }.getOrNull() ?: return@forEach
            val cards = parseCards(html)
            if (cards.isNotEmpty()) {
                val list = HomePageList(title, cards)
                attachSectionUrl(list, base)
                lists.add(list)
            }
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/index.php?search=${URLEncoder.encode(query, "utf-8")}"
        return parseCards(app.get(url).text)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page <= 1) {
            return newSearchResponseList(search(query) ?: emptyList(), hasNext = true)
        }
        val base = "$mainUrl/index.php?search=${URLEncoder.encode(query, "utf-8")}"
        val html = app.get(ajaxUrl(base, page)).text
        val json = org.json.JSONObject(html)
        val cards = parseCards(json.getString("html"))
        return newSearchResponseList(cards, hasNext = json.optBoolean("hasMore", false))
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("(\\d+)").find(url)?.value ?: return null
        val html = runCatching { app.get("$mainUrl/$id").text }.getOrNull() ?: return null
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.replace(Regex("\\s*-\\s*رەشەبا.*$"), "")?.trim()
            ?.takeIf { it.isNotBlank() } ?: "Video $id"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        return newMovieLoadResponse(title, id, TvType.Movie, id) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("(\\d+)").find(data)?.value ?: data
        val embedHtml = runCatching { app.get("$mainUrl/e/$id").text }.getOrNull() ?: return false

        var manifest = Regex("player\\.load\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)")
            .find(embedHtml)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            .orEmpty()

        if (manifest.isBlank()) {
            manifest = Regex("loadThumbnails\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)")
                .find(embedHtml)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.replace(Regex("\\.vtt(?=\\?)"), "")
                .orEmpty()
        }
        if (manifest.isBlank()) {
            Log.w(name, "No manifest found on embed page for id $id")
            return false
        }
        manifest = when {
            manifest.startsWith("http://") || manifest.startsWith("https://") -> manifest
            manifest.startsWith("//") -> "https:$manifest"
            manifest.startsWith("/") -> mainUrl + manifest
            else -> "$mainUrl/$manifest"
        }
        Log.d(name, "resolved manifest: $manifest")

        val type = if (manifest.substringBefore('?').endsWith(".mpd"))
            ExtractorLinkType.DASH else ExtractorLinkType.M3U8

        val localUrl = RashabaProxy.rewriteUrl(manifest)
        if (localUrl == null) return false
        callback(
            newExtractorLink(this.name, "Rashaba $id", localUrl, type) {
                this.referer = mainUrl
                this.quality = getQualityFromName(manifest)
            }
        )
        return true
    }

    private fun parseCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        val root = doc.selectFirst("#videos-container") ?: doc
        val items = mutableListOf<SearchResponse>()
        root.select(".video-preview-wrap").forEach { wrap ->
            runCatching {
                val anchor = wrap.parent() ?: throw Exception("no anchor")
                val href = anchor.attr("href")
                if (href.contains("short", ignoreCase = true) || href.contains("@")) throw Exception("short")
                val id = Regex("(\\d+)").find(href)?.value ?: throw Exception("no id")
                val card = wrap.parents().firstOrNull { it.hasClass("mb-5") || it.hasClass("mob_07") }
                    ?: (anchor.parent() ?: throw Exception("no card"))
                val title = card.select("h3").firstOrNull()?.text()
                    ?.takeIf { it.isNotBlank() }
                    ?: wrap.select("img").firstOrNull()?.attr("alt")
                    ?: throw Exception("no title")
                val img = wrap.select("img").firstOrNull()
                val poster = img?.attr("src")?.ifBlank { img.attr("data-src") }
                items.add(
                    newMovieSearchResponse(title, "$mainUrl/$id", TvType.Movie) {
                        this.posterUrl = if (poster.isNullOrBlank()) null else fixUrl(poster)
                    }
                )
            }
        }
        return items
    }

    private fun attachSectionUrl(hp: HomePageList, base: String): HomePageList {
        val candidateFields = listOf("data", "requestData", "request", "pageUrl", "url", "extra", "nextPage", "params", "metadata")
        for (fName in candidateFields) {
            try {
                val f = hp.javaClass.getDeclaredField(fName)
                f.isAccessible = true
                f.set(hp, base)
                return hp
            } catch (_: NoSuchFieldException) {
            } catch (_: Exception) {
            }
        }
        return HomePageList("${hp.name ?: ""}||RASHABA::$base", hp.list)
    }

    private fun ajaxUrl(base: String, page: Int): String {
        val cleaned = base.trimEnd('/', '&', '?')
        val sep = if (cleaned.contains('?')) '&' else '?'
        return "$cleaned${sep}ajax=1&page=$page"
    }
}

private object RashabaProxy {
    private const val TAG = "RashabaProxy"
    private const val CDN_REFERER = "https://rashaba.com/"

    private var server: ServerSocket? = null
    @Volatile
    private var port = 0

    fun rewriteUrl(originUrl: String): String? {
        startServer()
        if (port == 0) return null
        val enc = Base64.encodeToString(
            originUrl.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "http://127.0.0.1:$port/r/$enc"
    }

    @Synchronized
    private fun startServer() {
        if (server != null && !server!!.isClosed) return
        try {
            val s = ServerSocket(0)
            server = s
            port = s.localPort
            thread {
                try {
                    while (!s.isClosed) {
                        val client = s.accept()
                        thread { handle(client) }
                    }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "start server failed: ${e.message}")
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val out = BufferedOutputStream(socket.getOutputStream())
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2 || parts[0] != "GET") {
                    out.write(plainResponse(405, "Method Not Allowed"))
                    out.flush()
                    return
                }
                val path = parts[1].substringBefore('?')
                if (!path.startsWith("/r/")) {
                    out.write(plainResponse(404, "Not Found"))
                    out.flush()
                    return
                }
                val enc = path.removePrefix("/r/")
                val originUrl = String(Base64.decode(enc, Base64.URL_SAFE), Charsets.UTF_8)
                val resp = runBlocking { app.get(originUrl, referer = CDN_REFERER) }
                if (resp.code !in 200..299) {
                    out.write(plainResponse(404, "Not Found"))
                    out.flush()
                    return
                }
                val body = runCatching { resp.body.bytes() }.getOrNull()
                if (body == null) {
                    out.write(plainResponse(500, "Empty Body"))
                    out.flush()
                    return
                }
                val pathPart = originUrl.substringBefore('?')
                when {
                    pathPart.endsWith(".m3u8") -> {
                        val rewritten = rewriteM3u8(String(body, Charsets.UTF_8), originUrl)
                        out.write(binaryResponse("application/vnd.apple.mpegurl", rewritten.toByteArray(Charsets.UTF_8)))
                    }
                    pathPart.endsWith(".mpd") -> {
                        val rewritten = rewriteMpd(String(body, Charsets.UTF_8), originUrl)
                        out.write(binaryResponse("application/dash+xml", rewritten.toByteArray(Charsets.UTF_8)))
                    }
                    else -> {
                        out.write(binaryResponse(contentTypeOf(pathPart), body))
                    }
                }
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle error: ${e.message}")
        }
    }

    private fun rewriteM3u8(text: String, manifestUrl: String): String {
        val baseDir = manifestUrl.substringBefore('?').substringBeforeLast('/') + "/"
        val tokenQuery = manifestUrl.substringAfter('?', "")
        val sb = StringBuilder()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            val next = when {
                line.isEmpty() -> line
                line.startsWith("#") && line.contains("URI=") -> {
                    line.replace(Regex("URI\\s*=\\s*\"([^\"]+)\"")) { m ->
                        val child = rewriteNested(resolve(m.groupValues[1], baseDir), tokenQuery)
                        "URI=\"$child\""
                    }
                }
                line.startsWith("#") -> line
                else -> rewriteNested(resolve(line, baseDir), tokenQuery)
            }
            sb.append(next).append('\n')
        }
        return sb.toString()
    }

    private fun rewriteNested(url: String, tokenQuery: String): String {
        val withToken = appendQuery(url, tokenQuery)
        return if (withToken.substringBefore('?').endsWith(".m3u8")) {
            rewriteUrl(withToken) ?: withToken
        } else {
            withToken
        }
    }

    private fun rewriteMpd(xml: String, mpdUrl: String): String {
        val baseDir = mpdUrl.substringBefore('?').substringBeforeLast('/') + "/"
        val tokenQuery = mpdUrl.substringAfter('?', "")
        return try {
            val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
            doc.select("*").forEach { el ->
                val updates = mutableListOf<Pair<String, String>>()
                el.attributes().forEach { attr ->
                    val key = attr.key
                    if (key == "initialization" || key == "media" || key.endsWith("URL")) {
                        val value = attr.value
                        if (!value.startsWith("http")) {
                            updates.add(key to appendQuery(resolve(value, baseDir), tokenQuery))
                        }
                    }
                }
                updates.forEach { (k, v) -> el.attr(k, v) }
                if (el.tagName() == "BaseURL" && !el.text().startsWith("http")) {
                    el.text(appendQuery(resolve(el.text(), baseDir), tokenQuery))
                }
            }
            doc.outerHtml()
        } catch (e: Exception) {
            xml
        }
    }

    private fun resolve(url: String, baseDir: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> originOf(baseDir) + url
            else -> baseDir + url
        }
    }

    private fun originOf(baseDir: String): String {
        val withoutScheme = baseDir.removePrefix("https://").removePrefix("http://")
        return if (withoutScheme.contains('/')) "https://" + withoutScheme.substringBefore('/') else baseDir
    }

    private fun appendQuery(url: String, query: String): String {
        if (query.isEmpty()) return url
        return if (url.contains('?')) "$url&$query" else "$url?$query"
    }

    private fun contentTypeOf(url: String): String {
        return when {
            url.endsWith(".vtt") -> "text/vtt"
            url.endsWith(".ts") -> "video/mp2t"
            url.endsWith(".m4s") -> "video/iso.segment"
            url.endsWith(".mp4") -> "video/mp4"
            url.endsWith(".aac") -> "audio/aac"
            url.endsWith(".mp3") -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }

    private fun plainResponse(code: Int, text: String): ByteArray =
        responseBytes("HTTP/1.1 $code OK", "text/plain", text.toByteArray(Charsets.UTF_8))

    private fun binaryResponse(contentType: String, body: ByteArray): ByteArray =
        responseBytes("HTTP/1.1 200 OK", contentType, body)

    private fun responseBytes(status: String, contentType: String, body: ByteArray): ByteArray {
        val header = StringBuilder()
            .append(status).append("\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n")
            .append("Content-Length: ").append(body.size).append("\r\n")
            .append("Connection: close\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("\r\n")
            .toString()
        return header.toByteArray(Charsets.UTF_8) + body
    }
}