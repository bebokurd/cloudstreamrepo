package com.animezid

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeZidProvider : MainAPI() {
    override var name = "AnimeZid"
    override var mainUrl = "https://animezid.cam"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Cartoon, TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/category.php?cat=movies" to "افلام",
        "$mainUrl/category.php?cat=new-movies" to "افلام حديثة",
        "$mainUrl/category.php?cat=dubbed-animation" to "انمي مدبلج",
        "$mainUrl/category.php?cat=subbed-animation" to "انمي مترجم",
        "$mainUrl/category.php?cat=english-movies" to "افلام اجنبية"
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
        val html = runCatching {
            app.post("$mainUrl/index.php?search=$query").text
        }.getOrNull() ?: return emptyList()
        return parseCards(html)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return null
        val results = search(query) ?: return null
        return newSearchResponseList(results, hasNext = false)
    }

    private fun parseCards(html: String): List<SearchResponse> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        val results = mutableListOf<SearchResponse>()
        doc.select("a[href]").forEach { link ->
            runCatching {
                val href = link.attr("href")
                if (!href.contains("/movie/")) return@runCatching
                val title = link.selectFirst("img")?.attr("alt")?.trim()
                    ?: link.text().trim()
                if (title.isBlank()) return@runCatching
                val poster = link.selectFirst("img[src]")?.attr("src")
                results.add(
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                    }
                )
            }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = url.trim()
        if (pageUrl.isBlank()) return null
        val html = runCatching { app.get(pageUrl).text }.getOrNull() ?: return null
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("p.text-muted")?.text() ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val year = Regex("""(20\d{2})""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        val genres = doc.select("a[href*='/category']").mapNotNull { it.text().trim().takeIf(String::isNotBlank) }

        val isSerie = pageUrl.contains("/serie/") || doc.select("a[href*='/episode']").isNotEmpty()
        if (!isSerie) {
            val servers = mutableListOf<String>()
            doc.select("a[href*='/movie/']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && href.contains("/server")) servers += href
            }
            val dataString = if (servers.isNotEmpty()) {
                servers.joinToString(prefix = "[", postfix = "]", separator = ",")
            } else {
                ""
            }
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, dataString) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        }

        val episodes = mutableListOf<Episode>()
        doc.select("a[href*='/episode/'], a[href*='/serie/']").forEach { link ->
            runCatching {
                val href = link.attr("href")
                val num = Regex("""(\d+)""").find(link.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@runCatching
                episodes += newEpisode(href) {
                    name = link.text().trim()
                    episode = num
                }
            }
        }
        if (episodes.isEmpty()) return null
        episodes.sortBy { it.episode ?: 0 }
        return newTvSeriesLoadResponse(title, pageUrl, TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
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
        if (links.isEmpty()) return false
        var found = false
        val seen = mutableSetOf<String>()
        for (link in links) {
            val full = if (link.startsWith("//")) "https:$link" else link
            if (!seen.add(full)) continue
            val ok = runCatching {
                loadExtractor(full, referer = mainUrl, subtitleCallback, callback) == true
                    || resolveSourcesPage(full, subtitleCallback, callback)
            }.getOrDefault(false)
            if (ok) found = true
        }
        return found
    }

    private fun serverLinks(node: JsonNode, out: MutableList<String>) {
        val link = node.path("link").asText().trim()
        if (link.isNotBlank()) out += link
    }

    private suspend fun resolveSourcesPage(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (url.isBlank()) return false
        val headers = mapOf(
            "User-Agent" to UA,
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
            val isM3u8 = file.substringAfterLast('.').substringBefore('?')
                .equals("m3u8", true)
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

    private companion object {
        val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        val mapper = ObjectMapper()
    }
}
