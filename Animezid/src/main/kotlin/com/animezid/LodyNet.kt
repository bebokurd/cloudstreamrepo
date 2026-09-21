package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import android.util.Log
import java.net.URLEncoder
import java.util.Locale

private const val LODY_TAG = "LodyNet"
private const val LODY_MAX_SERVERS = 4

@Serializable
private data class LodyPlaybackSource(
    @SerialName("provider") val provider: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("id") val id: String? = null
)

@Serializable
private data class LodyPlaybackSession(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("sources") val sources: List<LodyPlaybackSource>? = null
)

@Serializable
private data class LodyPlaybackSuccess(
    @SerialName("success") val success: Boolean? = null,
    @SerialName("html") val html: String? = null
)

@Serializable
private data class LodyResolvedSource(
    @SerialName("resolve_url") val resolveUrl: String? = null
)

class LodyNet : MainAPI() {
    override var mainUrl = "${config.url}" // placeholder, overridden below
    override var name = "LodyNet"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.Movie, TvType.AnimeMovie, TvType.Cartoon)
    override var lang = "ar"
    override val hasMainPage = true

    private val lodyUrl = "https://lodynet.watch"
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private fun lodyBase(headers: Boolean = true): MutableMap<String, String> = baseHeaders(lodyUrl)

    private fun lodyAjax(referer: String? = null): MutableMap<String, String> {
        val headers = lodyBase()
        headers["Accept"] = "application/json, text/javascript, */*; q=0.01"
        headers["X-Requested-With"] = "XMLHttpRequest"
        if (referer != null) headers["Referer"] = referer
        return headers
    }

    private fun encodeSearchQuery(query: String): String = URLEncoder.encode(query, "UTF-8")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(lodyUrl, headers = baseHeaders(lodyUrl)).document
        val homePageList = ArrayList<HomePageList>()

        doc.select("section.az-section").forEach { section ->
            val sectionName = section.selectFirst("h2")?.text() ?: return@forEach
            val items = section.select("article.az-card a.az-card__link").mapNotNull { a ->
                parseLodyCard(a)
            }
            if (items.isNotEmpty()) homePageList.add(HomePageList(sectionName, items))
        }

        return newHomePageResponse(homePageList)
    }

    private fun parseLodyCard(a: org.jsoup.nodes.Element): SearchResponse? {
        val href = a.attr("href")
        if (href.isBlank() || !(href.contains("watch.php") || href.contains("/series/") || href.contains("/movie/")))
            return null
        val label = a.attr("aria-label").ifBlank { a.selectFirst("img")?.attr("alt") } ?: return null
        val poster = a.selectFirst("img")?.attr("src")
        val isMovie = label.contains("فيلم")
        val isAnime = label.contains("أنمي") || label.contains("انمي")
        val type = if (isMovie) TvType.Movie
        else if (isAnime) TvType.Anime
        else TvType.TvSeries

        return if (isMovie) {
            newMovieSearchResponse(cleanTitle(label), href, type) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(cleanTitle(label), href, type) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$lodyUrl/wp-content/themes/Lodynet2020/Api/RequestSearch.php?value=${encodeSearchQuery(query)}"
        return try {
            val jsonText = app.get(searchUrl, headers = lodyAjax(lodyUrl)).text
            val jsonList = parseJson<List<Any>>(jsonText)
            if (jsonList.size < 2) return emptyList()
            val rawResults = jsonList[1]
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val resultsJson = mapper.writeValueAsString(rawResults)
            val results = parseJson<List<LodySearchJson>>(resultsJson)
            results.mapNotNull { item ->
                val url = item.url ?: return@mapNotNull null
                val isMovie = item.category == "فيلم" || item.title?.contains("فيلم") == true
                val title = cleanTitle(item.title ?: "Unknown")
                val type = if (isMovie) TvType.Movie
                else if (item.title?.contains("أنمي") == true || item.title?.contains("انمي") == true) TvType.Anime
                else TvType.TvSeries
                if (isMovie) {
                    newMovieSearchResponse(title, lodyUrl + url, type) { this.posterUrl = item.cover }
                } else {
                    newTvSeriesSearchResponse(title, lodyUrl + url, type) { this.posterUrl = item.cover }
                }
            }
        } catch (e: Exception) {
            Log.w(LODY_TAG, "LodyNet search failed", e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = baseHeaders(url)).document
        return when {
            url.contains("/series/") -> loadLodySeries(doc, url)
            doc.selectFirst("[data-season-count]") != null -> loadLodySeriesFromWatch(doc, url)
            else -> loadLodyMovie(doc, url)
        }
    }

    private fun loadLodySeries(doc: Document, url: String): LoadResponse {
        val titleRaw = doc.selectFirst("h1")?.text() ?: doc.title().ifBlank { "Unknown" }
        val title = cleanTitle(titleRaw)
        val isAnime = titleRaw.contains("أنمي") || titleRaw.contains("انمي")
        val type = if (isAnime) TvType.Anime else TvType.TvSeries
        val poster = getLodyPoster(doc)
        val typeNum = if (isAnime) TvType.Anime.name else TvType.TvSeries.name

        val allEpisodes = ArrayList<Episode>()
        val startVid = doc.selectFirst("a.az-series-start")?.attr("href")
            ?.substringAfterLast("?vid=")?.substringBefore("&")

        if (startVid != null) {
            val seasonCount = doc.selectFirst("[data-season-count]")?.attr("data-season-count")?.toIntOrNull() ?: 0
            for (season in 1..seasonCount) {
                allEpisodes.addAll(episodesForLodySeason(startVid, season))
            }
        }

        return newTvSeriesLoadResponse(title, url, type, sortEpisodes(allEpisodes)) {
            this.posterUrl = poster
            this.year = Regex("(19\\d{2}|20\\d{2})").find(titleRaw)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    private suspend fun loadLodySeriesFromWatch(doc: Document, url: String): LoadResponse {
        val vid = url.substringAfterLast("?vid=").substringBefore("&")
        val titleRaw = doc.selectFirst("h1")?.text() ?: doc.title().ifBlank { "Unknown" }
        val type = if (titleRaw.contains("أنمي") || titleRaw.contains("انمي")) TvType.Anime else TvType.TvSeries
        val seasonCount = doc.selectFirst("[data-season-count]")?.attr("data-season-count")?.toIntOrNull() ?: 0
        val poster = getLodyPoster(doc)

        val allEpisodes = ArrayList<Episode>()
        for (season in 1..seasonCount) {
            allEpisodes.addAll(episodesForLodySeason(vid, season))
        }
        return newTvSeriesLoadResponse(cleanTitle(titleRaw), url, type, sortEpisodes(allEpisodes)) {
            this.posterUrl = poster
        }
    }

    private suspend fun episodesForLodySeason(vid: String, season: Int): List<Episode> {
        return try {
            val endpoint = "$lodyUrl/ajax.php?p=series&do=watch-season&vid=$vid&season=$season"
            val jsonText = app.get(endpoint, headers = lodyAjax(lodyUrl)).text
            val ajax = parseJson<LodyPlaybackSuccess>(jsonText)
            if (ajax.success != true || ajax.html.isNullOrBlank()) return emptyList()
            val fragment = Jsoup.parse(ajax.html)
            fragment.select("a[href*=\"watch.php?vid=\"]").mapNotNull { a ->
                val epUrl = a.attr("href")
                if (epUrl.isBlank()) return@mapNotNull null
                val num = a.selectFirst("strong")?.text()?.toIntOrNull() ?: 0
                newEpisode(epUrl) {
                    this.name = if (num > 0) "الحلقة $num" else "الحلقة"
                    this.episode = num
                    this.season = season
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadLodyMovie(doc: Document, url: String): LoadResponse {
        val titleRaw = doc.selectFirst("h1")?.text() ?: doc.title().ifBlank { "Unknown" }
        val isAnime = titleRaw.contains("أنمي") || titleRaw.contains("انمي")
        val type = if (isAnime) TvType.AnimeMovie else TvType.Movie
        return newMovieLoadResponse(cleanTitle(titleRaw), url, type, url) {
            this.posterUrl = getLodyPoster(doc)
        }
    }

    private fun getLodyPoster(doc: Document): String? {
        return doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".az-series-detail-hero img")?.attr("src")
    }

    private suspend fun episodesForLodySeasonRaw(
        vid: String,
        season: Int,
        doc: Document,
        url: String
    ): List<Episode> = episodesForLodySeason(vid, season)

    private suspend fun loadMovieLody(doc: Document, url: String): LoadResponse = loadLodyMovie(doc, url)

    private fun LodyPlaybackSuccess.episodes(): List<Episode> {
        return Regex("""href=["']([^"']*watch\.php\?vid=[^"']+)["'][^>]*>.*?(\d+).*?</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html.orEmpty()).map { m ->
                newEpisode(m.groupValues[1]) {
                    this.episode = m.groupValues[2].toIntOrNull() ?: 0
                }
            }.toList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vid = data.substringAfterLast("?vid=").substringBefore("&")
        if (vid.isBlank() || vid == data) return false
        val playUrl = "$lodyUrl/play.php?vid=$vid"

        return try {
            val playDoc = app.get(playUrl, headers = baseHeaders(playUrl)).document
            val csrf = playDoc.selectFirst("[data-playback-csrf]")?.attr("data-playback-csrf")
            if (csrf.isNullOrBlank()) return false
            val safePlayUrl = encodeUri(playUrl)

            val sessionJson = app.post(
                "$lodyUrl/web-playback/sessions",
                headers = lodyPlaybackHeaders(playUrl, csrf),
                json = mapOf("content_id" to vid)
            ).text
            val session = parseJson<LodyPlaybackSession>(sessionJson)
            val sessionId = session.sessionId ?: return false

            val sourceOrder = listOf(
                "Uqload", "StreamRuby", "VidTube", "DoodStream", "TurboViPlay",
                "MegaMax", "RPMShare", "UPNShare"
            )
            val sources = (session.sources ?: emptyList())
                .filter { it.type == "embedded_web" && !it.id.isNullOrBlank() }
                .sortedBy { src ->
                    val idx = sourceOrder.indexOf(src.provider)
                    if (idx < 0) Int.MAX_VALUE / 2 else idx
                }
                .take(LODY_MAX_SERVERS)

            var emitted = 0
            var resolvedCount = 0
            for (source in sources) {
                val sourceId = source.id ?: continue
                val provider = source.provider ?: continue
                val resolved = try {
                    parseJson<LodyResolvedSource>(
                        app.post(
                            "$lodyUrl/web-playback/sessions/$sessionId/sources/$sourceId/resolve",
                            headers = lodyPlaybackHeaders(playUrl, csrf),
                            json = mapOf<String, Any>()
                        ).text
                    )
                } catch (e: Exception) {
                    continue
                }
                val launchUrl = resolved.resolveUrl ?: continue
                resolvedCount++

                val launchRes = try {
                    app.get(launchUrl, headers = baseHeaders(playUrl), allowRedirects = false)
                } catch (e: Exception) {
                    continue
                }
                var finalUrl = launchRes.url ?: launchUrl
                var page = launchRes.text
                val location = launchRes.headers["Location"] ?: launchRes.headers["location"]
                if (location != null) {
                    val target = if (location.startsWith("http")) location else "$lodyUrl$location"
                    val redirected = try {
                        app.get(target, headers = baseHeaders(target))
                    } catch (e: Exception) {
                        continue
                    }
                    finalUrl = redirected.url ?: target
                    page = redirected.text
                }

                var result = EmbedResult()
                if (page.trimStart().removePrefix("\uFEFF").startsWith("#EXTM3U")) {
                    result = result.merge(EmbedResult(listOf(finalUrl to Qualities.Unknown.value)))
                }
                result = result.merge(extractDirectFromLodyPage(page))
                if (result.videos.isEmpty()) {
                    if (isDoodHost(finalUrl)) {
                        result = result.merge(extractDood(finalUrl, page))
                    } else if (isEmbedPostHost(finalUrl)) {
                        result = result.merge(postEmbedDl(finalUrl))
                    }
                }

                for ((videoUrl, videoQuality) in result.videos) {
                    val isM3u8 = videoUrl.endsWith(".m3u8", ignoreCase = true)
                    callback(
                        newExtractorLink(
                            source = "LodyNet",
                            name = provider,
                            url = videoUrl,
                        ) {
                            this.referer = finalUrl
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            quality = videoQuality
                        }
                    )
                    emitted++
                }
                if (result.videos.isEmpty()) {
                    val before = emitted
                    try {
                        SmartPlayer.extract(
                            playerUrl = finalUrl,
                            referer = safePlayUrl,
                            qualityInt = Qualities.Unknown.value,
                            displayName = "LodyNet - $provider",
                            callback = { link ->
                                callback(link)
                                emitted++
                            }
                        )
                    } catch (e: Exception) {
                        Log.w(LODY_TAG, "LodyNet SmartPlayer failed for '$provider'", e)
                    }
                    if (emitted == before) {
                        try {
                            app.get(finalUrl, headers = baseHeaders(finalUrl))
                        } catch (e: Exception) {
                        }
                    }
                }
            }
            Log.d(LODY_TAG, "LodyNet finished: $resolvedCount resolved, $emitted emitted")
            emitted > 0
        } catch (e: Exception) {
            Log.e(LODY_TAG, "LodyNet loadLinks failed for $playUrl", e)
            false
        }
    }

    private fun extractDirectFromLodyPage(page: String): EmbedResult {
        val videos = mutableListOf<Pair<String, Int>>()
        Regex("(https?://[^\\s\"'<>]+\\.(?:m3u8|mp4)[^\\s\"'<>]*)", RegexOption.IGNORE_CASE).findAll(page)
            .forEach { videos += it.groupValues[1] to Qualities.Unknown.value }
        val decoded = unpackPacker(page) ?: return EmbedResult(videos = videos)
        return parsePackedMediaLody(decoded).merge(EmbedResult(videos = videos))
    }

    private fun parsePackedMediaLody(decoded: String): EmbedResult {
        val videos = mutableListOf<Pair<String, Int>>()
        Regex("""\{[^{}]*\}""").findAll(decoded).forEach { obj ->
            val file = Regex("""file\s*:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(obj.value)?.groupValues?.get(1) ?: return@forEach
            if (!looksLikeVideoLody(file)) return@forEach
            val label = Regex("""(?:label|quality)\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(obj.value)?.groupValues?.get(1)
            videos += file to qualityFromLodyLabel(label)
        }
        return EmbedResult(videos = videos, subtitles = extractSubtitles(page))
    }

    private fun qualityFromLodyLabel(label: String?): Int {
        return when (label?.trim()?.lowercase(Locale.ROOT)) {
            "2160p", "4k", "2160" -> Qualities.P2160.value
            "1440p", "1440" -> Qualities.P1440.value
            "1080p", "1080", "fhd" -> Qualities.P1080.value
            "720p", "720", "hd" -> Qualities.P720.value
            "480p", "480", "sd" -> Qualities.P480.value
            "360p", "360" -> Qualities.P360.value
            "240p", "240" -> Qualities.P240.value
            "144p", "144" -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }

    private fun looksLikeVideoLody(url: String): Boolean {
        val lower = url.lowercase()
        if (Regex(""".\.(?:jpg|jpeg|png|gif|svg|vtt|srt|css|js|ico|xml|woff2?|json|txt)$""").containsMatchIn(lower)) return false
        return lower.contains(".m3u8") || lower.contains(".mp4") ||
            (lower.contains("token=") && lower.contains("expiry="))
    }

    private fun unpackPacker(page: String): String? {
        return try {
            val regex = Regex("""eval\(function\(p,a,c,k,e,?r?\)\{.*?eval\(e\)\(\)\}""" +
                """\(["']([^"']+)["'],\s*(\d+),\s*(\d+),\s*(["'])(.*?)\4\.split\(""",
                setOf(RegexOption.DOT_MATCHES_ALL))
            val match = regex.find(page) ?: return null
            val encoded = match.groupValues[1]
            var a = match.groupValues[2].toIntOrNull() ?: 62
            val c = match.groupValues[3].toIntOrNull() ?: 0
            val dict = match.groupValues[5].split("|")
            val map = HashMap<String, String>()
            for (i in 0 until c) {
                map[intToBase36(i)] = dict.getOrElse(i) { "" }
            }
            unpackPackerFrom(page, encoded, map)

        } catch (e: Exception) {
            null
        }
    }

    private fun unpackPackerFrom(page: String, p: String, map: Map<String, String>): String? {
        return try {
            Regex("""([a-zA-Z0-9]+)""").replace(p) { m ->
                map[m.value] ?: m.value
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun intToBase36(num: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.append(digits[n % 62])
            n /= 62
        }
        return sb.reverse().toString()
    }

    private fun lodyPlaybackHeaders(referer: String, csrf: String): MutableMap<String, String> {
        val headers = baseHeaders(referer)
        headers["Origin"] = lodyUrl
        headers["Accept"] = "application/json"
        headers["X-Requested-With"] = "XMLHttpRequest"
        headers["Content-Type"] = "application/json"
        headers["X-Playback-CSRF"] = csrf
        return headers
    }
}
