package com.stardima

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder

class StardimaProvider : MainAPI() {
    override var mainUrl = "https://www.stardima.com"
    override var name = "Stardima"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Movie
    )

    private val ua =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"

    private val pageHeaders = mapOf(
        "User-Agent" to ua,
        "Referer" to "$mainUrl/"
    )

    private val xhrHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    private val v2Origin = "https://v2.hyperwatching.com"

    private val cardRegex =
        Regex("""<img src="([^"]+)"\s+alt="Poster for ([^"]+)"[\s\S]{0,4000}?<a href="(https://www\.stardima\.com/(?:movie|tvshow)/[a-z0-9]+)"""",
            RegexOption.DOT_MATCHES_ALL)

    private val h1Regex = Regex("""<h1[^>]*>([\s\S]{0,300}?)</h1>""")
    private val yearRegex = Regex("""<div class="info-item">(\d{4})</div>""")
    private val genreRegex = Regex("""href="(?:https://www\.stardima\.com)?/search/([a-z0-9-]+)"[^>]*>\s*([^<]{1,60})""")
    private val iframeRegex = Regex("""v2\.hyperwatching\.com/watch/([A-Za-z0-9]+)""")
    private val serversRegex = Regex(
        """&quot;id&quot;:(\d+),&quot;server_id&quot;:(\d+),&quot;name&quot;:&quot;(.*?)&quot;,&quot;logo&quot;:&quot;.*?&quot;,&quot;type&quot;:&quot;([^&]*)&quot;,&quot;status&quot;:&quot;([^&]*)&quot;,&quot;is_vip&quot;:(true|false)"""
    )
    private val m3u8Regex = Regex("""https?[^"'\s<>\\]+?\.m3u8[^"'\s<>\\]*""")
    private val mp4Regex = Regex("""https?[^"'\s<>\\]+?\.mp4[^"'\s<>\\]*""")
    private val seasonIdRegex = Regex("""data-season-id="(\d+)"""")
    private val seasonNumberRegex = Regex("""data-season-number="([^"]+)"""")
    private val initialSeasonRegex = Regex("""data-initial-season-id="(\d+)"""")

    private fun ogMeta(html: String, prop: String): String? {
        val m = Regex("""<meta[^>]+property="og:$prop"[^>]*content="([^"]*)"""").find(html) ?: return null
        return m.groupValues[1].trim().ifBlank { null }
    }

    private fun stripTags(text: String): String {
        return text.replace(Regex("""<[^>]+>"""), " ")
            .replace("&#039;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun buildSearch(title: String, url: String, poster: String, isMovie: Boolean): SearchResponse {
        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.addPoster(poster)
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.addPoster(poster)
            }
        }
    }

    private fun parseVideoArray(text: String): List<SearchResponse> {
        val list = arrayListOf<SearchResponse>()
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return list
        val arr = obj.optJSONArray("videos") ?: return list
        for (i in 0 until arr.length()) {
            val video = arr.optJSONObject(i) ?: continue
            val title = video.optString("title")
            val url = video.optString("url")
            if (title.isBlank() || url.isBlank()) continue
            if (!url.contains("/movie/") && !url.contains("/tvshow/")) continue
            list.add(buildSearch(title, url, video.optString("poster_url"), url.contains("/movie/")))
        }
        return list
    }

    private suspend fun parseJsonListing(path: String, page: Int): List<SearchResponse> {
        if (page < 1) return emptyList()
        val text = app.get("$mainUrl$path?page=$page", headers = xhrHeaders).text
        return parseVideoArray(text)
    }

    private suspend fun parseHtmlListing(path: String): List<SearchResponse> {
        val text = app.get("$mainUrl$path", headers = pageHeaders).text
        val results = arrayListOf<SearchResponse>()
        for (m in cardRegex.findAll(text)) {
            val link = m.groupValues[3]
            if (!link.contains("/movie/") && !link.contains("/tvshow/")) continue
            results.add(buildSearch(m.groupValues[2], link, m.groupValues[1], link.contains("/movie/")))
        }
        return results.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.name) {
            "أفلام" -> newHomePageResponse(listOf(HomePageList("أفلام", parseJsonListing("/aflam", page))))
            "مسلسلات" -> newHomePageResponse(listOf(HomePageList("مسلسلات", parseJsonListing("/mosalsalat", page))))
            else -> {
                if (page > 1) return newHomePageResponse(emptyList())
                val home = arrayListOf<HomePageList>()
                runCatching {
                    val latest = parseHtmlListing("/newrelases")
                    if (latest.isNotEmpty()) home.add(HomePageList("آخر ما تمت إضافته", latest))
                }.onFailure { logError(it) }
                runCatching {
                    val movies = parseJsonListing("/aflam", 1)
                    if (movies.isNotEmpty()) home.add(HomePageList("أفلام", movies))
                }.onFailure { logError(it) }
                runCatching {
                    val series = parseJsonListing("/mosalsalat", 1)
                    if (series.isNotEmpty()) home.add(HomePageList("مسلسلات", series))
                }.onFailure { logError(it) }
                newHomePageResponse(home)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url = "$mainUrl/search?query=${URLEncoder.encode(q, "UTF-8")}"
        val text = runCatching { app.get(url, headers = xhrHeaders).text }.getOrNull() ?: return emptyList()
        if (!text.trimStart().startsWith("{") && !text.trimStart().startsWith("[")) return emptyList()
        return parseVideoArray(text)
    }

    override suspend fun load(url: String): LoadResponse {
        return if (url.contains("/tvshow/")) loadSeries(url) else loadMovie(url)
    }

    private suspend fun loadMovie(url: String): LoadResponse {
        val html = app.get(url, headers = pageHeaders).text
        val title = runCatching {
            stripTags(h1Regex.find(html)?.groupValues?.get(1) ?: "")
        }.getOrDefault("").ifBlank { ogMeta(html, "title")?.substringBefore("|")?.trim() ?: "Stardima" }
        val poster = ogMeta(html, "image")
        val plot = ogMeta(html, "description")
        val year = yearRegex.find(html)?.groupValues?.get(1)?.toIntOrNull()
        val genres = genreRegex.findAll(html).mapNotNull { m ->
            stripTags(m.groupValues[2]).ifBlank { null }
        }.distinct().toList()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
        }
    }

    private suspend fun loadSeries(url: String): LoadResponse {
        val slug = url.trimEnd('/').substringAfterLast("/")
        val html = app.get(url, headers = pageHeaders).text
        val title = runCatching {
            stripTags(h1Regex.find(html)?.groupValues?.get(1) ?: "")
        }.getOrDefault("").ifBlank { ogMeta(html, "title")?.substringBefore("|")?.trim() ?: "Stardima" }
        val poster = ogMeta(html, "image")
        val plot = ogMeta(html, "description")
        val year = yearRegex.find(html)?.groupValues?.get(1)?.toIntOrNull()
        val genres = genreRegex.findAll(html).mapNotNull { m ->
            stripTags(m.groupValues[2]).ifBlank { null }
        }.distinct().toList()

        val firstPlay = ogMeta(html, "video")
        if (firstPlay.isNullOrBlank()) throw ErrorLoadingException("لا يوجد روابط تشغيل")

        val playHtml = app.get(firstPlay, headers = pageHeaders).text

        val idMatches = seasonIdRegex.findAll(playHtml).toList()
        val numberMatches = seasonNumberRegex.findAll(playHtml).toList()
        val seasonPairs = arrayListOf<Pair<Int, String>>()
        val min = minOf(idMatches.size, numberMatches.size)
        for (i in 0 until min) {
            val sid = idMatches[i].groupValues[1].toIntOrNull() ?: continue
            seasonPairs.add(sid to numberMatches[i].groupValues[1])
        }
        if (seasonPairs.isEmpty()) {
            val initSid = initialSeasonRegex.find(playHtml)?.groupValues?.get(1)?.toIntOrNull()
            if (initSid == null) throw ErrorLoadingException("لا توجد حلقات")
            seasonPairs.add(initSid to "")
        }

        val seenSeasons = mutableSetOf<Int>()
        val orderedSeasons = arrayListOf<Pair<Int, String>>()
        for ((sid, num) in seasonPairs) {
            val numVal = num.filter { it.isDigit() }.toIntOrNull()
                ?: (seasonPairs.indexOfFirst { it.first == sid } + 1)
            if (numVal <= 0) continue
            if (seenSeasons.add(numVal)) orderedSeasons.add(sid to num)
        }

        val episodes = arrayListOf<Episode>()
        orderedSeasons.forEachIndexed { index, pair ->
            val (sid, numLabel) = pair
            val seasonNum = numLabel.filter { it.isDigit() }.toIntOrNull() ?: (index + 1)
            val text = runCatching {
                app.get("$mainUrl/series/season/$sid", headers = xhrHeaders).text
            }.getOrNull() ?: return@forEachIndexed
            val arr = runCatching { JSONObject(text).optJSONArray("episodes") }.getOrNull()
                ?: return@forEachIndexed
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val epId = obj.optLong("id", 0)
                if (epId <= 0) continue
                val epNumber = obj.optInt("episode_number", i + 1)
                val epTitle = obj.optString("title")
                episodes.add(newEpisode("$mainUrl/tvshow/$slug/play/$epId") {
                    this.name = epTitle
                    this.season = seasonNum
                    this.episode = epNumber
                    this.posterUrl = poster
                })
            }
        }

        if (episodes.isEmpty()) throw ErrorLoadingException("لا توجد حلقات")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
        val emitted = HashSet<String>()
        val watch = stardimaWatchUrl(data) ?: return true
        resolveV2(watch, emitted, subtitleCallback, callback)
        return true
    }

    private suspend fun stardimaWatchUrl(url: String): String? {
        return when {
            url.contains("v2.hyperwatching.com/watch/") -> url.trim()
            url.contains("/movie/") -> {
                val playUrl = url.trim().replace("/movie/", "/play/")
                scrapeV2Iframe(playUrl)
            }
            url.contains("/tvshow/") && url.contains("/play/") -> {
                val epId = url.trim().substringAfterLast("/")
                val direct = runCatching {
                    JSONObject(app.get("$mainUrl/series/episode/$epId", headers = xhrHeaders).text)
                        .optJSONObject("episode")?.optString("watch_url") ?: ""
                }.getOrDefault("")
                if (direct.startsWith("https://v2.hyperwatching.com/watch/")) direct
                else scrapeV2Iframe(url.trim())
            }
            url.contains("/play/") -> scrapeV2Iframe(url.trim())
            else -> null
        }
    }

    private suspend fun scrapeV2Iframe(pageUrl: String): String? {
        val text = runCatching { app.get(pageUrl, headers = pageHeaders).text }.getOrNull() ?: return null
        val m = iframeRegex.find(text) ?: return null
        return "https://v2.hyperwatching.com/watch/${m.groupValues[1]}"
    }

    private suspend fun resolveV2(
        watchUrl: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hashid = watchUrl.trimEnd('/').substringAfterLast("/")
        val text = runCatching { app.get(watchUrl, headers = pageHeaders).text }.getOrNull() ?: return
        val serverIds = serversRegex.findAll(text)
            .mapNotNull { m ->
                val sid = m.groupValues[1].toIntOrNull()
                    ?: return@mapNotNull null
                val status = m.groupValues[5]
                val vip = m.groupValues[6] == "true"
                if (status != "completed" || vip) null else sid
            }
            .distinct()

        serverIds.forEach { sid ->
            runCatching {
                val body = app.get("$v2Origin/embed/$hashid/server/$sid/url", headers = pageHeaders).text
                val watchUrl = JSONObject(body).optString("watch_url")
                if (watchUrl.isNotBlank()) {
                    resolveHostedStream(watchUrl, emitted, subtitleCallback, callback)
                }
            }.onFailure { logError(it) }
        }
    }

    private suspend fun resolveHostedStream(
        watchUrl: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = watchUrl.trim().replace("\\/", "/")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        if (url.endsWith(".m3u8", ignoreCase = true)) {
            emitLink(url, ExtractorLinkType.M3U8, url.substringBeforeLast("/"), emitted, callback)
            return
        }
        if (url.endsWith(".mp4", ignoreCase = true)) {
            emitLink(url, ExtractorLinkType.VIDEO, url.substringBeforeLast("/"), emitted, callback)
            return
        }

        loadExtractor(url, v2Origin, subtitleCallback, callback)

        runCatching {
            val referer = url.substringBeforeLast("/")
            val page = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to referer,
                    "Accept-Language" to "ar,en;q=0.8"
                )
            ).text
            scanForStreams(page, referer, emitted, callback)
            scanForStreams(page.replace("\\/", "/"), referer, emitted, callback)
        }.onFailure { logError(it) }
    }

    private fun scanForStreams(
        text: String,
        referer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        for (m in m3u8Regex.findAll(text)) {
            emitLink(m.value, ExtractorLinkType.M3U8, referer, emitted, callback)
        }
        for (m in mp4Regex.findAll(text)) {
            emitLink(m.value, ExtractorLinkType.VIDEO, referer, emitted, callback)
        }
    }

    private fun emitLink(
        rawUrl: String,
        type: ExtractorLinkType,
        referer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        var link = rawUrl.trim()
        while (link.isNotEmpty() && (link.last() == '"' || link.last() == '\'' || link.last() == ')' || link.last() == ';')) {
            link = link.dropLast(1)
        }
        if (!link.startsWith("http://") && !link.startsWith("https://")) return
        if (!emitted.add(link)) return
        callback(
            newExtractorLink(
                source = name,
                name = "Stardima - ${if (type == ExtractorLinkType.M3U8) "HLS" else "MP4"}",
                url = link
            ) {
                this.referer = referer
                this.headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to referer
                )
                this.quality = Qualities.Unknown.value
                this.type = type
            }
        )
    }
}