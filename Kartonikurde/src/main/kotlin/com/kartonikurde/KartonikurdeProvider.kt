package com.kartonikurde

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class KartonikurdeProvider : MainAPI() {
    override var name = "Kartonikurde"
    override var mainUrl = "https://www.kartonikurde.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Cartoon, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "فیلم",
        "$mainUrl/subtitle-movies" to "فیلمی وەرگێڕدراو",
        "$mainUrl/series" to "زنجیرە",
        "$mainUrl/subtitle-series" to "زنجیرەی وەرگێڕدراو"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data ?: return newHomePageResponse(request.name ?: name, emptyList())
        val pageUrl = if (page <= 1) {
            baseUrl
        } else if (baseUrl.contains("?")) {
            "$baseUrl&page=$page"
        } else {
            "$baseUrl?page=$page"
        }

        val html = runCatching { app.get(pageUrl).text }.getOrNull().orEmpty()
        val cards = parseCards(html)
        return newHomePageResponse(
            listOf(HomePageList(request.name ?: name, cards)),
            hasNext = cards.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()
        val livewireResults = runCatching { searchLivewire(query) }.getOrElse { emptyList() }
        if (livewireResults.isNotEmpty()) return livewireResults
        val fallbackHtml = runCatching { app.get("$mainUrl/movies").text }.getOrNull().orEmpty()
        val filtered = parseCards(fallbackHtml).filter {
            it.name.contains(query, ignoreCase = true)
        }
        return filtered
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return null
        val results = search(query) ?: return null
        return newSearchResponseList(results, hasNext = false)
    }

    private suspend fun searchLivewire(query: String): List<SearchResponse> {
        val html = app.get("$mainUrl/movies").text
        val doc = Jsoup.parse(html)
        val csrf = Regex("""data-csrf="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: doc.selectFirst("meta[name=csrf-token]")?.attr("content")
            ?: return emptyList()
        val formSpot = doc.select("div[wire\\:snapshot]").firstOrNull {
            it.attr("wire:snapshot").contains("search-form")
        } ?: return emptyList()
        val snapshot = formSpot.attr("wire:snapshot")
        val wireId = formSpot.attr("wire:id")
        if (snapshot.isBlank() || wireId.isBlank()) return emptyList()

        val data = mutableMapOf<String, String>()
        data["_token"] = csrf
        data["components[0][snapshot]"] = snapshot
        data["components[0][id]"] = wireId
        data["components[0][locale]"] = "ku"
        data["components[0][updates][0][type]"] = "syncInput"
        data["components[0][updates][0][payload][name]"] = "searchText"
        data["components[0][updates][0][payload][value]"] = query

        val headers = mapOf(
            "X-Livewire" to "true",
            "X-CSRF-TOKEN" to csrf
        )
        val response = app.post(
            "$mainUrl/livewire/update",
            data = data,
            headers = headers,
            referer = "$mainUrl/movies",
            timeout = 30
        ).text
        val rootObj = runCatching { jsonElement(response).jsonObject }.getOrNull() ?: return emptyList()
        val htmlOut = rootObj["components"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("effects")?.jsonObject?.get("html")?.jsonPrimitive?.content
            ?: return emptyList()
        return parseCards(htmlOut)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = url.trim()
        val html = runCatching { app.get(pageUrl).text }.getOrNull().orEmpty()
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img.poster, div.poster img")?.attr("src")
        val plot = doc.selectFirst("p.text-sm")?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val year = Regex("""(\d{4})""").findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }
            .firstOrNull { it in 1900..2100 }
        val duration = Regex("""(\d+)\s*خولەک""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        val genres = doc.select("a[href*='/genre/']").mapNotNull { it.text().trim().takeIf(String::isNotBlank) }

        return if (pageUrl.contains("/serie/")) {
            val episodes = mutableListOf<Episode>()
            doc.select("button[wire\\:click*='selectEpisode']").forEach { btn ->
                val clickAttr = btn.attr("wire:click")
                val jsonString = clickAttr.substringAfter("selectEpisode(").substringBeforeLast(")").trim()
                val node = runCatching { mapper.readTree(jsonString) }.getOrNull() ?: return@forEach
                val number = node.path("episode_number").asInt(0).takeIf { it > 0 }
                    ?: Regex("""\d+""").find(btn.text())?.value?.toIntOrNull()
                    ?: (episodes.size + 1)
                episodes += newEpisode(jsonString) {
                    name = "ئەڵقەی $number"
                    episode = number
                }
            }
            if (episodes.isEmpty()) {
                val servers = extractServers(doc, html)
                if (servers.isNotEmpty()) {
                    val dataString = servers.joinToString(prefix = "[", postfix = "]", separator = ",")
                    episodes += newEpisode(dataString) {
                        name = "ئەڵقەی 1"
                        episode = 1
                    }
                } else {
                    return null
                }
            }
            episodes.sortBy { it.episode ?: 0 }
            newTvSeriesLoadResponse(title, pageUrl, TvType.Cartoon, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        } else {
            val servers = extractServers(doc, html)
            val dataString = if (servers.isNotEmpty()) {
                servers.joinToString(prefix = "[", postfix = "]", separator = ",")
            } else {
                ""
            }
            newMovieLoadResponse(title, pageUrl, TvType.Movie, dataString) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.duration = duration
                this.tags = genres
            }
        }
    }

    private fun extractServers(doc: org.jsoup.nodes.Document, html: String): List<String> {
        val servers = mutableListOf<String>()
        val seenLinks = mutableSetOf<String>()
        doc.select("button[wire\\:click*='selectServer']").forEach { btn ->
            val clickAttr = btn.attr("wire:click")
            val jsonString = clickAttr.substringAfter("selectServer(").substringBeforeLast(")").trim()
            val node = runCatching { mapper.readTree(jsonString) }.getOrNull()
            val link = node?.path("link")?.asText()?.trim().orEmpty()
            if (link.isNotBlank() && seenLinks.add(link)) {
                servers += jsonString
            }
        }
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank() && !src.contains("youtube.com", true) && !src.contains("youtu.be", true)) {
                if (seenLinks.add(src)) {
                    val name = when {
                        src.contains("vidmoly", true) -> "vidmoly"
                        src.contains("abyss", true) -> "abyss"
                        src.contains("morencius", true) || src.contains("earnvids", true) -> "earnvids"
                        else -> "server"
                    }
                    servers += """{"name":"$name","link":"$src"}"""
                }
            }
        }
        val patterns = listOf(
            Regex("""https?://vidmoly\.[a-z]+/embed-[a-zA-Z0-9_-]+\.html"""),
            Regex("""https?://player\.abyssplayer\.com/[a-zA-Z0-9_-]+"""),
            Regex("""https?://morencius\.com/embed/[a-zA-Z0-9_-]+""")
        )
        for (pattern in patterns) {
            pattern.findAll(html).forEach { match ->
                val link = match.value
                if (seenLinks.add(link)) {
                    val name = when {
                        link.contains("vidmoly", true) -> "vidmoly"
                        link.contains("abyss", true) -> "abyss"
                        link.contains("morencius", true) -> "earnvids"
                        else -> "server"
                    }
                    servers += """{"name":"$name","link":"$link"}"""
                }
            }
        }

        return servers
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val links = mutableListOf<String>()

        if (data.trim().startsWith("http")) {
            links += data.trim()
        } else {
            runCatching {
                val root = mapper.readTree(data)
                when {
                    root.isArray -> root.forEach { serverLinks(it, links) }
                    else -> root.path("servers").forEach { serverLinks(it, links) }
                }
            }
        }
        if (links.isEmpty()) return false

        var found = false
        for (rawLink in links.distinct()) {
            val link = if (rawLink.startsWith("//")) "https:$rawLink" else rawLink
            val ok = runCatching {
                when {
                    link.contains("vidmoly", ignoreCase = true) -> {
                        resolveVidmoly(link, subtitleCallback, callback)
                            || loadExtractor(link, referer = "$mainUrl/", subtitleCallback, callback)
                    }
                    link.contains("abyss", ignoreCase = true) -> {
                        resolveAbyss(link, subtitleCallback, callback)
                            || loadExtractor(link, referer = "$mainUrl/", subtitleCallback, callback)
                    }
                    link.contains("morencius", ignoreCase = true)
                        || link.contains("earnvids", ignoreCase = true)
                        || link.contains("fastvid", ignoreCase = true)
                        || link.contains("fastved", ignoreCase = true)
                        || link.contains("vidhide", ignoreCase = true) -> {
                        resolveMorencius(link, subtitleCallback, callback)
                            || loadExtractor(link, referer = "$mainUrl/", subtitleCallback, callback)
                    }
                    else -> {
                        loadExtractor(link, referer = "$mainUrl/", subtitleCallback, callback)
                            || resolveGeneric(link, subtitleCallback, callback)
                    }
                }
            }.getOrDefault(false)
            if (ok) found = true
        }
        return found
    }

    private suspend fun resolveVidmoly(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        val html = runCatching { app.get(url, headers = headers).text }.getOrNull().orEmpty()
        if (html.isBlank()) return false

        var content = html
        if (content.contains("eval(function")) {
            val unpacked = unpackPackerSimple(content)
            if (!unpacked.isNullOrBlank()) {
                content += "\n" + unpacked
            }
        }

        var found = false
        val sourcesMatch = Regex("""sources\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(content)
        if (sourcesMatch != null) {
            val sources = sourcesMatch.groupValues[1]
            val files = Regex("""file\s*:\s*['"]([^'"]+)['"]""").findAll(sources)
                .map { it.groupValues[1].replace("\\/", "/") }.toList()
            val labels = Regex("""label\s*:\s*['"]([^'"]+)['"]""").findAll(sources)
                .map { it.groupValues[1] }.toList()
            files.forEachIndexed { index, rawFile ->
                val file = if (rawFile.startsWith("//")) "https:$rawFile" else rawFile
                if (file.isNotBlank()) {
                    val label = labels.getOrNull(index).orEmpty()
                    val isM3u8 = file.contains(".m3u8", true)
                    callback.invoke(
                        newExtractorLink(
                            "Vidmoly",
                            "Vidmoly${if (label.isNotBlank()) " $label" else ""}",
                            file,
                            if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://vidmoly.org/"
                            if (label.isNotBlank()) this.quality = getQualityFromName(label)
                        }
                    )
                    found = true
                }
            }
        }

        if (!found) {
            val m3u8Match = Regex("""https?://[^'"\s>]+\.m3u8[^'"\s>]*""").find(content)
            if (m3u8Match != null) {
                val file = m3u8Match.value.replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        "Vidmoly",
                        "Vidmoly HLS",
                        file,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://vidmoly.org/"
                    }
                )
                found = true
            }
        }

        return found
    }

    private suspend fun resolveMorencius(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        val response = runCatching { app.get(url, headers = headers) }.getOrNull() ?: return false
        val html = response.text.orEmpty()
        val finalUrl = response.url

        var streamUrl = findStreamUrl(html, finalUrl)
        if (streamUrl == null && html.contains("eval(function")) {
            var working = html
            var unpacked: String? = null
            for (i in 1..4) {
                unpacked = unpackPackerSimple(working)
                if (unpacked.isNullOrBlank()) break
                working = unpacked
                if (!unpacked.contains("eval(function")) break
            }
            if (!unpacked.isNullOrBlank()) {
                val cleaned = unpacked.replace("\\/", "/")
                val match = Regex("""var\s+links\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL).find(cleaned)
                if (match != null) {
                    val jsonRaw = match.groupValues[1]
                    val map = mutableMapOf<String, String>()
                    val pairRegex = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""")
                    for (m in pairRegex.findAll(jsonRaw)) {
                        map[m.groupValues[1]] = m.groupValues[2]
                    }
                    streamUrl = map["hls4"] ?: map["hls"] ?: map["hls2"] ?: map["hls3"] ?: map["file"]
                }
                if (streamUrl.isNullOrBlank()) {
                    streamUrl = findStreamUrl(cleaned, finalUrl)
                }
            }
        }

        if (!streamUrl.isNullOrBlank()) {
            val fullStream = resolveRelative(streamUrl, finalUrl)
            callback.invoke(
                newExtractorLink(
                    "EarnVids",
                    "EarnVids",
                    fullStream,
                    if (fullStream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://morencius.com/"
                }
            )
            return true
        }
        return false
    }

    private suspend fun resolveAbyss(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        val html = runCatching { app.get(url, headers = headers).text }.getOrNull().orEmpty()
        if (html.isBlank()) return false

        var found = false
        val datasMatch = Regex("""const\s+datas\s*=\s*["']([^"']+)["']""").find(html)
        if (datasMatch != null) {
            runCatching {
                val b64 = datasMatch.groupValues[1]
                val rawBytes = Base64.decode(b64, Base64.DEFAULT)
                val jsonStr = String(rawBytes, Charsets.ISO_8859_1)
                val root = mapper.readTree(jsonStr)
                val slug = root.path("slug").asText()
                val userId = root.path("user_id").asText()
                val md5Id = root.path("md5_id").asText()
                val media = root.path("media").asText()

                if (slug.isNotBlank() && userId.isNotBlank() && md5Id.isNotBlank() && media.isNotBlank()) {
                    val keyStr = "$userId:$slug:$md5Id"
                    val md5Hex = MessageDigest.getInstance("MD5")
                        .digest(keyStr.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    val keyBytes = md5Hex.toByteArray(Charsets.US_ASCII)
                    val ivBytes = keyBytes.copyOfRange(0, 16)
                    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
                    val ciphertext = ByteArray(media.length) { media[it].code.toByte() }
                    val decryptedBytes = cipher.doFinal(ciphertext)
                    val decryptedJson = String(decryptedBytes, Charsets.UTF_8)
                    val decNode = mapper.readTree(decryptedJson)

                    val mp4Sources = decNode.path("mp4").path("sources")
                    if (mp4Sources.isArray) {
                        for (s in mp4Sources) {
                            val directFile = s.path("file").asText()
                            val label = s.path("label").asText()
                            if (directFile.isNotBlank()) {
                                val fullFile = if (directFile.startsWith("//")) "https:$directFile" else directFile
                                callback.invoke(
                                    newExtractorLink(
                                        "Abyss",
                                        "Abyss${if (label.isNotBlank()) " $label" else ""}",
                                        fullFile,
                                        if (fullFile.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "https://abysscdn.com/"
                                        if (label.isNotBlank()) this.quality = getQualityFromName(label)
                                    }
                                )
                                found = true
                            }
                        }
                    }

                    val hlsSources = decNode.path("hls").path("sources")
                    if (hlsSources.isArray) {
                        for (s in hlsSources) {
                            val file = s.path("file").asText()
                            val label = s.path("label").asText()
                            if (file.isNotBlank()) {
                                val fullFile = if (file.startsWith("//")) "https:$file" else file
                                callback.invoke(
                                    newExtractorLink(
                                        "Abyss",
                                        "Abyss HLS${if (label.isNotBlank()) " $label" else ""}",
                                        fullFile,
                                        ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "https://abysscdn.com/"
                                    }
                                )
                                found = true
                            }
                        }
                    }
                }
            }
        }

        val direct = findStreamUrl(html, url)
        if (direct != null) {
            callback.invoke(
                newExtractorLink(
                    "Abyss",
                    "Abyss",
                    direct,
                    if (direct.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://abysscdn.com/"
                }
            )
            found = true
        }

        return found
    }

    private suspend fun resolveGeneric(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching { app.get(url).text }.getOrNull().orEmpty()
        val stream = findStreamUrl(html, url) ?: return false
        val isM3u8 = stream.contains(".m3u8", true)
        callback.invoke(
            newExtractorLink(
                name,
                if (isM3u8) "HLS" else "MP4",
                stream,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = url
            }
        )
        return true
    }

    private fun findStreamUrl(html: String, pageUrl: String): String? {
        val m3u8 = Regex("""https?://[^'"\s>]+\.m3u8[^'"\s>]*""", RegexOption.IGNORE_CASE)
            .find(html)?.value?.replace("\\/", "/")
        if (m3u8 != null) return resolveRelative(m3u8, pageUrl)

        val video = Regex("""https?://[^'"\s>]+\.(?:mp4|mkv|webm|avi)(?:\?[^'"\s>]*)?""", RegexOption.IGNORE_CASE)
            .find(html)?.value?.replace("\\/", "/")
        if (video != null) return resolveRelative(video, pageUrl)

        val file = Regex("""["']file["']\s*:\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        if (!file.isNullOrBlank() &&
            (file.contains(".m3u8") || file.endsWith(".mp4") || file.endsWith(".mkv"))
        ) {
            return resolveRelative(file, pageUrl)
        }
        return null
    }

    private fun resolveRelative(link: String, pageUrl: String): String {
        if (link.startsWith("//")) return "https:$link"
        return if (link.startsWith("/") || link.startsWith("./") || !link.startsWith("http")) {
            try {
                URI(pageUrl).resolve(link).toString()
            } catch (_: Exception) {
                link
            }
        } else {
            link
        }
    }

    private fun unpackPackerSimple(js: String): String? {
        try {
            val regex = Regex(
                """eval\(function\(p,a,c,k,e,d\)\{.*?\}\(\s*['"](.+?)['"]\s*,\s*(\d+)\s*,\s*\d+\s*,\s*['"](.*?)['"]\.split\('\|'\)""",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = regex.find(js) ?: return null
            val (payloadRaw, radixStr, sympipe) = match.destructured
            val radix = radixStr.toIntOrNull() ?: 36
            val symtab = sympipe.split("|")

            var payload = payloadRaw
            for (i in (symtab.size - 1) downTo 0) {
                val word = symtab[i]
                if (word.isNotEmpty()) {
                    val token = Integer.toString(i, radix)
                    payload = payload.replace(Regex("""\b$token\b"""), word)
                }
            }

            return payload
        } catch (_: Exception) {
            return null
        }
    }

    private fun serverLinks(node: JsonNode, out: MutableList<String>) {
        val link = node.path("link").asText().trim()
        if (link.isNotBlank()) out += link
    }

    private fun parseCards(html: String): List<SearchResponse> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        val results = mutableListOf<SearchResponse>()
        doc.select("a[href][aria-label]").forEach { link ->
            runCatching {
                val href = link.attr("href")
                if (!href.contains("/movie/") && !href.contains("/serie/")) return@runCatching
                val title = link.attr("aria-label").trim()
                if (title.isBlank()) return@runCatching
                val poster = link.parent()?.selectFirst("img[src]")?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: link.selectFirst("img[src]")?.attr("src")?.takeIf { it.isNotBlank() }
                val type = if (href.contains("/serie/")) TvType.Cartoon else TvType.Movie
                results.add(
                    newMovieSearchResponse(title, href, type) {
                        this.posterUrl = poster
                    }
                )
            }
        }
        return results.distinctBy { it.url }
    }

    private fun jsonElement(text: String) =
        kotlinx.serialization.json.Json.parseToJsonElement(text)

    private companion object {
        val mapper = ObjectMapper()
    }
}