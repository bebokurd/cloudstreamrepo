package com.kartonikurde

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

class KartonikurdeProvider : MainAPI() {
    override var name = "Kartonikurde"
    override var mainUrl = "https://www.kartonikurde.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Cartoon)

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "فیلم",
        "$mainUrl/subtitle-movies" to "فیلمی وەرگێڕدراو",
        "$mainUrl/series" to "زنجیرە",
        "$mainUrl/subtitle-series" to "زنجیرەی وەرگێڕدراو"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data ?: return newHomePageResponse(request.name ?: name, emptyList())
        if (page > 1) return newHomePageResponse(request.name ?: name, emptyList(), hasNext = false)
        val html = runCatching { app.get(url).text }.getOrNull().orEmpty()
        return newHomePageResponse(
            listOf(HomePageList(request.name ?: name, parseCards(html))),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()
        return runCatching { searchLivewire(query) }.getOrElse { emptyList() }
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
        val formSpot = doc.select("div[wire\\\\:snapshot]").firstOrNull {
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
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("p.text-sm")?.text()
        val year = Regex("""(\d{4})""").findAll(html).map { it.groupValues[1].toInt() }
            .firstOrNull { it in 1900..2100 }
        val duration = Regex("""(\d+)\s*خولەک""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        val genres = doc.select("a[href*='/genre/']").mapNotNull { it.text().trim().takeIf(String::isNotBlank) }

        return if (pageUrl.contains("/serie/")) {
            val episodes = mutableListOf<Episode>()
            doc.select("button[wire\\\\:click*='selectEpisode']").forEach { btn ->
                val raw = btn.attr("wire:click").substringAfter("selectEpisode(").trim()
                if (raw.endsWith(")")) {
                    val jsonString = raw.dropLast(1)
                    val node = runCatching { mapper.readTree(jsonString) }.getOrNull() ?: return@forEach
                    val number = node.path("episode_number").asInt(0)
                    if (number > 0 && node.path("servers").isArray) {
                        episodes += newEpisode(jsonString) {
                            name = "ئەڵقەی $number"
                            episode = number
                        }
                    }
                }
            }
            if (episodes.isEmpty()) return null
            episodes.sortBy { it.episode ?: 0 }
            newTvSeriesLoadResponse(title, pageUrl, TvType.Cartoon, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        } else {
            val servers = mutableListOf<String>()
            doc.select("button[wire\\\\:click*='selectServer']").forEach { btn ->
                val raw = btn.attr("wire:click").substringAfter("selectServer(").trim()
                if (raw.endsWith(")")) {
                    val jsonString = raw.dropLast(1)
                    val node = runCatching { mapper.readTree(jsonString) }.getOrNull()
                    if (node != null && node.path("link").asText().isNotBlank()) {
                        servers += jsonString
                    }
                }
            }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val links = mutableListOf<String>()
        runCatching {
            val root = mapper.readTree(data)
            when {
                root.isArray -> root.forEach { serverLinks(it, links) }
                else -> root.path("servers").forEach { serverLinks(it, links) }
            }
        }

        // Broadcast servers – always appended
        links += broadcastServers

        if (links.isEmpty()) return false
        var found = false
        for (link in links) {
            val full = if (link.startsWith("//")) "https:$link" else link
            val ok = runCatching {
                loadExtractor(full, referer = mainUrl, subtitleCallback, callback) == true
                    || resolveSourcesPage(full, subtitleCallback, callback)
            }.getOrDefault(false)
            if (ok) found = true
        }
        return found
    }

    private suspend fun resolveSourcesPage(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
        )
        val page = runCatching { app.get(url, headers = headers).text }.getOrNull() ?: return false
        val sourcesMatch = Regex("""sources\s*:\s*\[(.*?)\]""", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(page) ?: return false
        val sources = sourcesMatch.groupValues[1]
        val files = Regex("""file\s*:\s*["']([^"']+)["']""").findAll(sources)
            .map { it.groupValues[1] }.toList()
        val labels = Regex("""label\s*:\s*["']([^"']+)["']""").findAll(sources)
            .map { it.groupValues[1] }.toList()
        var found = false
        files.forEachIndexed { index, rawFile ->
            val file = if (rawFile.startsWith("//")) "https:$rawFile" else rawFile
            if (file.isBlank()) return@forEachIndexed
            val label = labels.getOrNull(index).orEmpty()
            val isM3u8 = file.substringAfterLast('.').substringBefore('?').equals("m3u8", true)
            callback.invoke(
                newExtractorLink(
                    name,
                    "${if (isM3u8) "HLS" else "MP4"}${if (label.isNotBlank()) " $label" else ""}",
                    file,
                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    if (label.isNotBlank()) this.quality = getQualityFromName(label)
                }
            )
            found = true
        }
        return found
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
                val poster = link.parent()?.selectFirst("img[src]")?.attr("src")?.takeIf { it.isNotBlank() }
                val type = if (href.contains("/serie/")) TvType.Cartoon else TvType.Movie
                results.add(
                    newMovieSearchResponse(title, href, type) {
                        this.posterUrl = poster
                    }
                )
            }
        }
        return results
    }

    private fun jsonElement(text: String) =
        kotlinx.serialization.json.Json.parseToJsonElement(text)

    private companion object {
        val mapper = ObjectMapper()

        /** Hardcoded broadcast server embeds appended to every loadLinks call. */
        val broadcastServers = listOf(
            "https://vidmoly.org/embed-zd7ymvfj17ul.html",
            "https://player.abyssplayer.com/m6WUkwsY9",
            "https://morencius.com/embed/k6b5ubdcpj5n"
        )
    }
}