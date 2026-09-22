package com.kurdora

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class KurdoraProvider : MainAPI() {
    override var name = "Kurdora"
    override var mainUrl = "https://kurdora.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiBase = "https://api.kurdora.com/api"
    private val searchBase = "$mainUrl/api/search"

    override val mainPage = mainPageOf(
        "$apiBase/movies" to "فیلمەکان",
        "$apiBase/series" to "زنجیرەکان"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data ?: "$apiBase/movies"
        val url = if (page <= 1) base else "$base?page=$page"
        val json = AppUtils.tryParseJson<KurdoraListResponse>(app.get(url).text)
            ?: return newHomePageResponse(request.name ?: "Kurdora", emptyList())
        val items = (json.movies ?: json.series ?: emptyList()).mapNotNull { it.toSearchResponse() }
        val hasMore = (json.totalPages ?: 1) > page
        return newHomePageResponse(
            listOf(HomePageList(request.name ?: "Kurdora", items)),
            hasNext = hasMore
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$searchBase?q=${URLEncoder.encode(query, "UTF-8")}"
        val json = AppUtils.tryParseJson<KurdoraSearchResult>(app.get(url).text) ?: return emptyList()
        val items = (json.movies.orEmpty() + json.series.orEmpty())
            .mapNotNull { it.toSearchResponse() }
        return items
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return null
        return newSearchResponseList(search(query) ?: emptyList(), hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse? {
        return if (url.contains("/series/")) loadSeries(url) else loadMovie(url)
    }

    private suspend fun loadMovie(url: String): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val json = AppUtils.tryParseJson<KurdoraMovieDetail>(app.get("$apiBase/movies/$slug").text) ?: return null
        val title = (json.title ?: json.titleEnglish)?.takeIf { it.isNotBlank() } ?: return null
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = json.poster?.let { fixUrl(it) }
            this.year = json.year
            this.plot = json.synopsis
            this.score = json.rating?.let { Score.from10(it) }
            this.duration = json.durationMinutes
            this.tags = json.genres.orEmpty()
        }
    }

    private suspend fun loadSeries(url: String): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val json = AppUtils.tryParseJson<KurdoraSeriesDetail>(app.get("$apiBase/series/$slug").text) ?: return null
        val title = (json.title ?: json.titleEnglish)?.takeIf { it.isNotBlank() } ?: return null
        val seriesId = json.id ?: return null

        val episodes = mutableListOf<Episode>()
        json.seasons.orEmpty().forEach { season ->
            val seasonNum = season.seasonNumber ?: 1
            season.episodes.orEmpty().forEach { ep ->
                val epNum = ep.episodeNumber ?: (episodes.size + 1)
                val episodeUrl = "$mainUrl/series/$slug?s_id=$seriesId&s=$seasonNum&e=$epNum"
                episodes += newEpisode(episodeUrl) {
                    this.name = (ep.title ?: "Episode $epNum").ifBlank { "Episode $epNum" }
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = ep.thumbnail?.let { fixUrl(it) }
                    this.runTime = ep.durationMinutes
                    this.description = ep.synopsis
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = json.poster?.let { fixUrl(it) }
            this.year = json.year
            this.plot = json.synopsis
            this.score = json.rating?.let { Score.from10(it) }
            this.tags = json.genres.orEmpty()
            this.seasonNames = json.seasons.orEmpty().map {
                SeasonData(it.seasonNumber ?: 1, it.title ?: "Season ${it.seasonNumber}")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return if (data.contains("/series/")) {
            loadSeriesLinks(data, subtitleCallback, callback)
        } else {
            loadMovieLinks(data, callback)
        }
    }

    private suspend fun loadMovieLinks(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val detail = AppUtils.tryParseJson<KurdoraMovieDetail>(app.get("$apiBase/movies/$slug").text)

        val wasabiId = detail?.id
        if (wasabiId != null && emitWasabiStreams(wasabiId, callback)) {
            return true
        }

        if (emitServers(detail?.videoServers.orEmpty(), callback)) {
            return true
        }

        val html = runCatching { app.get("$mainUrl/film/$slug").text }.getOrNull()
        val rscServers = html?.let { parseVideoServers(it) }.orEmpty()
        if (emitServers(rscServers, callback)) {
            return true
        }
        return false
    }

    private suspend fun emitServers(servers: List<KurdoraVideoServer>, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        servers.forEach { server ->
            val streamUrl = server.url?.takeIf { it.isNotBlank() && it.startsWith("http") } ?: return@forEach
            found = true
            val isHls = streamUrl.contains(".m3u8")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = (server.name ?: "Kurdora") + if (server.quality.isNullOrBlank()) "" else " ${server.quality}",
                    url = streamUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = getQualityFromName(server.quality)
                }
            )
        }
        return found
    }

    private suspend fun emitWasabiStreams(mediaId: String, callback: (ExtractorLink) -> Unit): Boolean {
        val url = "$apiBase/videos/wasabi/movie/$mediaId"
        val json = AppUtils.tryParseJson<KurdoraWasabiResponse>(app.get(url).text) ?: return false
        if ((json.totalQualities ?: 0) <= 0) return false
        var found = false
        json.streams.orEmpty().forEach { (qualityLabel, streams) ->
            streams.forEach { stream ->
                val streamUrl = stream.url?.takeIf { it.isNotBlank() && it.startsWith("http") } ?: return@forEach
                found = true
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Kurdora $qualityLabel",
                        url = streamUrl,
                        type = if (stream.isHls == true || streamUrl.contains(".m3u8"))
                            ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName(qualityLabel)
                    }
                )
            }
        }
        return found
    }

    private suspend fun loadSeriesLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val seriesId = Regex("[?&]s_id=([^&]+)").find(data)?.groupValues?.get(1) ?: return false
        val season = Regex("[?&]s=(\\d+)").find(data)?.groupValues?.get(1) ?: return false
        val episode = Regex("[?&]e=(\\d+)").find(data)?.groupValues?.get(1) ?: return false
        val url = "$apiBase/videos/wasabi/series/$seriesId/$season/$episode"
        val json = AppUtils.tryParseJson<KurdoraWasabiResponse>(app.get(url).text) ?: return false
        var found = false
        json.streams.orEmpty().forEach { (qualityLabel, streams) ->
            streams.forEach { stream ->
                val streamUrl = stream.url?.takeIf { it.isNotBlank() && it.startsWith("http") } ?: return@forEach
                found = true
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Kurdora $qualityLabel",
                        url = streamUrl,
                        type = if (stream.isHls == true || streamUrl.contains(".m3u8"))
                            ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName(qualityLabel)
                    }
                )
            }
        }
        return found
    }

    private fun KurdoraListItem.toSearchResponse(): SearchResponse? {
        val s = slug?.takeIf { it.isNotBlank() } ?: return null
        val title = (title ?: titleEnglish)?.takeIf { it.isNotBlank() } ?: return null
        val tvType = if (totalSeasons != null) TvType.TvSeries else TvType.Movie
        val url = if (tvType == TvType.TvSeries) "$mainUrl/series/$s" else "$mainUrl/film/$s"
        return if (tvType == TvType.TvSeries)
            newTvSeriesSearchResponse(title, url, tvType) {
                this.posterUrl = poster?.let { fixUrl(it) }
            }
        else
            newMovieSearchResponse(title, url, tvType) {
                this.posterUrl = poster?.let { fixUrl(it) }
            }
    }

    private fun parseVideoServers(html: String): List<KurdoraVideoServer> {
        val payload = decodeRscPayload(html) ?: return emptyList()
        val jsonText = extractJsonValue(payload, "videoServers") ?: return emptyList()
        return runCatching { AppUtils.parseJson<List<KurdoraVideoServer>>(jsonText) }.getOrNull().orEmpty()
    }

    private fun decodeRscPayload(html: String): String? {
        val sb = StringBuilder()
        rscChunkRegex.findAll(html).forEach { sb.append(it.groupValues[1]) }
        if (sb.isEmpty()) return null
        return unescapeJsString(sb.toString())
    }

    private fun extractJsonValue(text: String, key: String): String? {
        val needle = "\"$key\":"
        val idx = text.indexOf(needle) ?: return null
        return extractBalanced(text, idx + needle.length)
    }

    private fun extractBalanced(text: String, start: Int): String? {
        if (start >= text.length) return null
        val open = text[start]
        if (open != '[' && open != '{' && open != '"') return null
        val close = when (open) {
            '[' -> ']'
            '{' -> '}'
            else -> '"'
        }
        var depth = 0
        var inString = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (esc) esc = false
                else when (c) {
                    '\\' -> esc = true
                    '"' -> {
                        inString = false
                        if (open == '"') return text.substring(start, i + 1)
                    }
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun unescapeJsString(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                val n = raw[i + 1]
                when (n) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val hex = raw.substring(i + 2, i + 6)
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: 'u')
                            i += 4
                        } else {
                            sb.append('u')
                        }
                    }
                    else -> {
                        sb.append('\\')
                        sb.append(n)
                    }
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private companion object {
        val rscChunkRegex = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
    }
}