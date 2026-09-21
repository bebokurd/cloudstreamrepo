package com.cartoontime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLEncoder

class CartoonTimeProvider : MainAPI() {
    override var mainUrl = "https://cartoontime.net"
    override var name = "CartoonTime"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "home" to "أحدث الأفلام والحلقات",
        "dub-cartoon" to "أفلام كرتون مدبلج",
        "series-dub" to "مسلسلات كرتون مدبلج"
    )

    private val episodeUrlRegex = Regex("""-e\d+/?$""", RegexOption.IGNORE_CASE)

    private fun isEpisodeUrl(url: String): Boolean {
        return episodeUrlRegex.containsMatchIn(url.trimEnd('/'))
            || Regex("""الحلقة\s*\d+""").containsMatchIn(url)
    }

    private fun episodeNumberFromUrl(url: String): Int? {
        return Regex("""-e(\d+)/?$""", RegexOption.IGNORE_CASE)
            .find(url.trimEnd('/'))?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun listUrl(selector: String, page: Int): String {
        return when (selector) {
            "home" -> if (page == 1) mainUrl else "$mainUrl/page/$page/"
            else -> {
                val base = "$mainUrl/category/$selector/"
                if (page == 1) base else "$base" + "page/$page/"
            }
        }
    }

    private fun toSearchResponse(li: org.jsoup.nodes.Element): SearchResponse? {
        val link = li.selectFirst("h2.post-title a") ?: return null
        val href = link.absUrl("href")
        if (href.isBlank()) return null
        val title = link.text().trim()
        if (title.isBlank()) return null
        val poster = li.selectFirst("a.post-thumb img")?.attr("abs:src")
        return if (isEpisodeUrl(href) || title.contains("الحلقة", true)) {
            newTvSeriesSearchResponse(title, href, TvType.Cartoon) {
                addPoster(poster)
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                addPoster(poster)
                this.posterUrl = poster
            }
        }
    }

    private fun parseList(doc: Document): List<SearchResponse> {
        return doc.select("ul.posts-items li.post-item").mapNotNull { toSearchResponse(it) }
    }

    private fun hasNextPage(doc: Document, page: Int): Boolean {
        return doc.html().contains("/page/${page + 1}/")
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(listUrl(request.data, page)).document
        val items = parseList(doc).distinctBy { it.url }
        return newHomePageResponse(
            request.name,
            items,
            hasNext = hasNextPage(doc, page)
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page == 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        val doc = app.get(url).document
        val items = parseList(doc).distinctBy { it.url }
        return newSearchResponseList(items, hasNext = hasNextPage(doc, page))
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url)
        val doc = response.document

        val title = doc.selectFirst("h1.post-title, h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
                ?.substringBefore(" - " )?.substringBefore(" –")?.trim()
            ?: return null

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")

        val seriesCategory = doc.selectFirst(
            "div.entry-content a[href*='category/series-dub/'], a[href*='cartoontime.net/category/series-dub/']"
        )?.attr("abs:href")

        if (!seriesCategory.isNullOrBlank()) {
            val episodes = scrapeSeriesEpisodes(seriesCategory)
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(cleanSeriesTitle(title), url, TvType.Cartoon, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Cartoon, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun cleanSeriesTitle(title: String): String {
        val cleaned = title
            .replace(Regex("""\s*الحلقة\s*\d+.*$"""), "")
            .trim()
            .trimEnd('-', '–', ':', '،', ',', '.')
        return cleaned.ifBlank { title }
    }

    private suspend fun scrapeSeriesEpisodes(categoryUrl: String): List<Episode> {
        val episodes = sortedMapOf<Int, String>()
        var page = 1
        while (page <= 100) {
            val pageUrl = if (page == 1) categoryUrl else categoryUrl.trimEnd('/') + "/page/$page/"
            val doc = app.get(pageUrl).document
            var found = 0
            doc.select("ul.posts-items li.post-item").forEach { li ->
                val link = li.selectFirst("h2.post-title a") ?: return@forEach
                val href = link.absUrl("href")
                val number = episodeNumberFromUrl(href) ?: return@forEach
                if (episodes.putIfAbsent(number, href) == null) found++
            }
            if (!hasNextPage(doc, page) || found == 0) break
            page++
        }
        return episodes.map { (num, url) ->
            newEpisode(url) {
                this.episode = num
                this.name = "الحلقة $num"
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        val iframes = doc.select("div.entry-content iframe[src]")
        if (iframes.isNotEmpty()) {
            iframes.forEach { iframe ->
                val src = iframe.attr("src").trim().replace("&amp;", "&")
                if (src.isBlank()) return@forEach
                runCatching {
                    resolveEmbed(src, data, subtitleCallback, callback)
                }.onFailure { logError(it) }
            }
            return true
        }

        doc.select("div.entry-content video source, div.entry-content video[src]").forEach { source ->
            val videoUrl = source.attr("abs:src")
                .ifBlank { source.attr("src") }
                .trim()
            if (videoUrl.isBlank()) return@forEach
            runCatching {
                emitDirect(videoUrl, "CartoonTime", data, subtitleCallback, callback)
            }.onFailure { logError(it) }
        }

        return true
    }

    private suspend fun resolveEmbed(
        src: String,
        refererPage: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            src.contains("odysee.com", ignoreCase = true) -> resolveOdysee(src, subtitleCallback, callback)
            src.contains("bitchute.com", ignoreCase = true) -> resolveBitchute(src, subtitleCallback, callback)
            src.contains("dzen.ru", ignoreCase = true) -> resolveDzen(src, subtitleCallback, callback)
            src.contains("drive.google.com", ignoreCase = true) -> resolveGoogleDrive(src, callback)
            else -> runCatching { loadExtractor(src, refererPage, subtitleCallback, callback) }
        }
    }

    private suspend fun resolveOdysee(
        src: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val text = app.get(src).text
        val contentUrl = Regex("""(?i)"contentUrl"\s*:\s*"(https?:\\?/\\?/[^"]+)"""")
            .find(text)?.groupValues?.get(1)?.replace("\\/", "/")

        if (!contentUrl.isNullOrBlank()) {
            emitDirect(contentUrl, "Odysee", "https://odysee.com/", subtitleCallback, callback)
            return
        }

        val canonical = Regex("""(?i)rel="canonical"\s+href="(https?:\\?/\\?/[^"]+)"""")
            .find(text)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: Regex("""(?i)<link\s+rel="canonical"[^>]*href="([^"]+)"""").find(text)?.groupValues?.get(1)

        if (!canonical.isNullOrBlank()) {
            val claim = canonical.removePrefix("https://odysee.com/").replace("/", ":")
            val uri = "lbry://" + claim.replace(":", "#")
            val resp = app.post(
                "https://api.lbry.tv/api/v1/proxy",
                headers = mapOf("Content-Type" to "application/json"),
                json = mapOf(
                    "jsonrpc" to "2.0",
                    "method" to "get",
                    "params" to mapOf("uri" to uri),
                    "id" to 1
                )
            )
            val streamUrl = Regex(""""streaming_url"\s*:\s*"([^"]+)"""")
                .find(resp.text)?.groupValues?.get(1)?.replace("\\/", "/")
            if (!streamUrl.isNullOrBlank()) {
                emitDirect(streamUrl, "Odysee", "https://odysee.com/", subtitleCallback, callback)
            }
        }
    }

    private suspend fun resolveBitchute(
        src: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val text = app.get(src).text
        val mediaUrl = Regex("""var media_url\s*=\s*'([^']+)'""").find(text)?.groupValues?.get(1)
        if (!mediaUrl.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "BitChute",
                    url = mediaUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    referer = "https://www.bitchute.com/"
                    quality = Qualities.Unknown.value
                }
            )
        }
    }

    private suspend fun resolveDzen(
        src: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val clean = src.substringBefore("?")
        val text = app.get(clean).text
        val unescaped = text.replace("\\/", "/")

        val hls = Regex("""https?:[^"]*video\.m3u8[^"]*""").find(unescaped)?.value
        val dash = Regex("""https?:[^"]*dzen_dash=dash[^"]*""").find(unescaped)?.value
        val anyMp4 = Regex("""("url"\s*:\s*")(https?:[^"]*)""").find(unescaped)?.groupValues?.get(2)

        val url = listOfNotNull(hls, dash, anyMp4).firstOrNull() ?: return
        callback(
            newExtractorLink(
                source = name,
                name = "Dzen",
                url = url,
                type = if (url.contains(".m3u8") || url.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.DASH
            ) {
                referer = "https://dzen.ru/"
                quality = Qualities.Unknown.value
            }
        )
    }

    private suspend fun resolveGoogleDrive(
        src: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = Regex("""/file/d/([0-9A-Za-z_-]{10,})""").find(src)?.groupValues?.get(1)
        if (!fileId.isNullOrBlank()) {
            val direct = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
            callback(
                newExtractorLink(
                    source = name,
                    name = "Google Drive",
                    url = direct,
                    type = ExtractorLinkType.VIDEO
                ) {
                    referer = "https://drive.google.com/"
                    quality = Qualities.Unknown.value
                }
            )
        }
    }

    private suspend fun emitDirect(
        url: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val lower = url.lowercase()
        val type = when {
            lower.contains(".m3u8") -> ExtractorLinkType.M3U8
            lower.contains(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = url,
                type = type
            ) {
                this.referer = referer
                quality = Qualities.Unknown.value
            }
        )
    }
}