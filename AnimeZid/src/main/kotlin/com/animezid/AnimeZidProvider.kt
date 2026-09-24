package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.util.Locale

private const val MAX_SERVERS = 16

@Serializable
data class SeasonAjax(
    @SerialName("success") val success: Boolean? = null,
    @SerialName("html") val html: String? = null
)

@Serializable
data class PlaybackSource(
    @SerialName("provider") val provider: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("id") val id: String? = null
)

@Serializable
data class PlaybackSession(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("sources") val sources: List<PlaybackSource>? = null
)

@Serializable
data class PlaybackResolve(
    @SerialName("launch_url") val launchUrl: String? = null,
    @SerialName("resolve_url") val resolveUrl: String? = null
)

private data class EmbedResult(
    val videos: List<Pair<String, Int>> = emptyList(),
    val subtitles: List<Pair<String, String>> = emptyList()
) {
    fun merge(other: EmbedResult): EmbedResult {
        val videoSeen = HashSet<String>()
        val subSeen = HashSet<Pair<String, String>>()
        val allVideos = (videos + other.videos).filter { videoSeen.add(it.first) }
        val allSubs = (subtitles + other.subtitles).filter { subSeen.add(it) }
        return EmbedResult(allVideos, allSubs)
    }
}

class AnimeZidProvider : MainAPI() {
    override var mainUrl = "https://animezid.cam"
    override var name = "AnimeZid"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Movie, TvType.TvSeries, TvType.Cartoon)
    override var lang = "ar"
    override val hasMainPage = true
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private fun baseHeaders(referer: String? = null): MutableMap<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "Accept-Language" to "ar,en-US;q=0.9",
            "Upgrade-Insecure-Requests" to "1"
        )
        if (referer != null) headers["Referer"] = referer
        return headers
    }

    private fun ajaxHeaders(referer: String? = null): MutableMap<String, String> {
        val headers = baseHeaders(referer)
        headers["Accept"] = "application/json, text/javascript, */*; q=0.01"
        headers["X-Requested-With"] = "XMLHttpRequest"
        return headers
    }

    private fun playbackHeaders(csrf: String): MutableMap<String, String> {
        return mutableMapOf(
            "User-Agent" to userAgent,
            "Accept-Language" to "ar,en-US;q=0.9",
            "Origin" to mainUrl,
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
            "X-Playback-CSRF" to csrf
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)
        val doc = app.get(mainUrl, headers = baseHeaders()).document
        val homePageList = ArrayList<HomePageList>()

        doc.select("section.az-section").forEach { section ->
            val sectionName = section.selectFirst("h2")?.text() ?: return@forEach
            val items = section.select("article.az-card a.az-card__link").mapNotNull { a ->
                parseCard(a)
            }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(sectionName, items))
            }
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()
        val url = "$mainUrl/search.php?keywords=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url, headers = baseHeaders(url)).document
        return doc.select("article.az-card a.az-card__link").mapNotNull { a -> parseCard(a) }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return null
        val results = search(query) ?: return null
        return newSearchResponseList(results, hasNext = false)
    }

    private fun parseCard(a: org.jsoup.nodes.Element): SearchResponse? {
        val href = a.attr("href")
        if (href.isBlank() || !(href.contains("watch.php") || href.contains("/series/"))) return null
        val label = a.attr("aria-label").ifBlank {
            a.selectFirst("img")?.attr("alt")
        } ?: return null
        val poster = a.selectFirst("img")?.attr("src")
        val hasEpisodeBadge = a.selectFirst(".az-badge--episode") != null
        val hasQualityBadge = a.selectFirst(".az-badge--quality") != null
        val isMovie = !hasEpisodeBadge && (label.contains("فيلم") || hasQualityBadge)
        val type = when {
            isMovie -> TvType.Movie
            label.contains("أنمي") || label.contains("انمي") || label.contains("كرتون") -> TvType.Anime
            else -> TvType.TvSeries
        }

        return if (isMovie) {
            newMovieSearchResponse(cleanTitle(label), href, type) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(cleanTitle(label), href, type) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = baseHeaders(url)).document

        return when {
            url.contains("/series/") || doc.selectFirst("a.az-series-start") != null -> loadSeries(doc, url)
            doc.selectFirst("[data-season-count]") != null || doc.selectFirst(".az-cinema-season-tabs") != null ->
                loadSeriesFromWatch(doc, url)
            else -> loadMovie(doc, url)
        }
    }

    private suspend fun loadSeries(doc: Document, url: String): LoadResponse {
        val titleRaw = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()
        val title = cleanTitle(titleRaw)
        val isAnime = titleRaw.contains("أنمي") || titleRaw.contains("انمي")
        val type = if (isAnime) TvType.Anime else TvType.TvSeries
        val poster = getPoster(doc)
        val plot = getDescription(doc)

        val allEpisodes = ArrayList<Episode>()
        val startVid = doc.selectFirst("a.az-series-start")?.attr("href")
            ?.substringAfterLast("?vid=")?.substringBefore("&")

        if (startVid != null) {
            doc.select(".az-series-seasons-grid article.az-season-poster-card a.az-card__link").forEach { a ->
                val seasonNum = Regex("season/(\\d+)/?").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                if (seasonNum != null) allEpisodes.addAll(episodesForSeason(startVid, seasonNum))
            }
        }

        if (allEpisodes.isEmpty()) {
            val gridSeason = doc.selectFirst("a[data-season-link][data-season]")?.attr("data-season")?.toIntOrNull() ?: 1
            allEpisodes.addAll(parseInlineGrid(doc, gridSeason))
        }

        return newTvSeriesLoadResponse(title, url, type, sortEpisodes(allEpisodes)) {
            this.posterUrl = poster
            this.plot = plot
            this.year = Regex("(19\\d{2}|20\\d{2})").find(titleRaw)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    private suspend fun loadSeriesFromWatch(doc: Document, url: String): LoadResponse {
        val vid = url.substringAfterLast("?vid=").substringBefore("&")
        val titleRaw = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()
        val isAnime = titleRaw.contains("أنمي") || titleRaw.contains("انمي")
        val type = if (isAnime) TvType.Anime else TvType.TvSeries
        val poster = getPoster(doc)
        val plot = getDescription(doc)

        val allEpisodes = ArrayList<Episode>()
        if (vid.isNotBlank() && vid != url) {
            val seasons = doc.select("a[data-season-link][data-season]")
                .mapNotNull { it.attr("data-season").toIntOrNull() }
                .distinct()
                .ifEmpty { listOf(1) }
            for (season in seasons) {
                allEpisodes.addAll(episodesForSeason(vid, season))
            }
            if (allEpisodes.isEmpty()) {
                val gridSeason = doc.selectFirst("a[data-season-link][data-season]")?.attr("data-season")?.toIntOrNull() ?: 1
                allEpisodes.addAll(parseInlineGrid(doc, gridSeason))
            }
        }

        return newTvSeriesLoadResponse(cleanTitle(titleRaw), url, type, sortEpisodes(allEpisodes)) {
            this.posterUrl = poster
            this.plot = plot
            this.year = Regex("(19\\d{2}|20\\d{2})").find(titleRaw)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    private suspend fun episodesForSeason(vid: String, season: Int): List<Episode> {
        return try {
            val endpoint = "$mainUrl/ajax.php?p=series&do=watch-season&vid=$vid&season=$season"
            val response = app.get(endpoint, headers = ajaxHeaders(mainUrl)).text
            val ajax = parseJson<SeasonAjax>(response)
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

    private fun parseInlineGrid(doc: Document, season: Int): List<Episode> {
        return doc.select(".az-cinema-episode-grid a[href*=\"watch.php?vid=\"]").mapNotNull { a ->
            val epUrl = a.attr("href")
            if (epUrl.isBlank()) return@mapNotNull null
            val num = a.selectFirst("strong")?.text()?.toIntOrNull() ?: 0
            newEpisode(epUrl) {
                this.name = if (num > 0) "الحلقة $num" else "الحلقة"
                this.episode = num
                this.season = season
            }
        }
    }

    private fun sortEpisodes(episodes: List<Episode>): List<Episode> {
        val sorted = episodes.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        if (sorted.all { (it.season ?: 0) <= 1 }) {
            sorted.forEach { it.season = 1 }
        }
        return sorted
    }

    private suspend fun loadMovie(doc: Document, url: String): LoadResponse {
        val titleRaw = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()
        val isAnime = titleRaw.contains("أنمي") || titleRaw.contains("انمي")
        val type = if (isAnime) TvType.AnimeMovie else TvType.Movie
        return newMovieLoadResponse(cleanTitle(titleRaw), url, type, url) {
            this.posterUrl = getPoster(doc)
            this.plot = getDescription(doc)
            this.year = Regex("(19\\d{2}|20\\d{2})").find(titleRaw)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    private fun getPoster(doc: Document): String? {
        val og = doc.selectFirst("meta[property=og:image]")?.attr("content")
        if (!og.isNullOrBlank()) return og
        return doc.selectFirst(".az-series-detail-hero img")?.attr("src")
            ?: doc.selectFirst("img[data-fallback]")?.attr("src")
    }

    private fun getDescription(doc: Document): String? {
        return doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?.ifBlank { null }
            ?: doc.selectFirst("script[type=\"application/ld+json\"]")?.data()?.let { json ->
                Regex("\"description\"\\s*:\\s*\"([^\"]{0,600})\"").find(json)?.groupValues?.get(1)
                    ?.replace("\\\"", "\"")
            }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vid = data.substringAfterLast("?vid=").substringBefore("&")
        if (vid.isBlank() || vid == data) return false
        val playUrl = "$mainUrl/play.php?vid=$vid"

        return try {
            val playDoc = app.get(playUrl, headers = baseHeaders(playUrl)).document
            val csrf = playDoc.selectFirst("[data-playback-csrf]")?.attr("data-playback-csrf")
            if (csrf.isNullOrBlank()) return false
            val tokenVidlo = Regex(""""TokenVidlo"\s*:\s*"([^"]+)"""").find(playDoc.html())
                ?.groupValues?.get(1).orEmpty()

            val sessionJson = app.post(
                "$mainUrl/web-playback/sessions",
                headers = playbackHeaders(csrf),
                json = mapOf("content_id" to vid)
            ).text
            val session = parseJson<PlaybackSession>(sessionJson)
            val sessionId = session.sessionId ?: return false

            val sourceOrder = listOf(
                "Uqload", "StreamRuby", "VidTube", "DoodStream", "TurboViPlay",
                "MegaMax", "PlayMate", "StreamP2P", "RPMShare", "UPNShare"
            )
            val sources = (session.sources ?: emptyList())
                .filter { it.type == "embedded_web" && !it.id.isNullOrBlank() }
                .sortedBy { src ->
                    val idx = sourceOrder.indexOf(src.provider)
                    if (idx < 0) Int.MAX_VALUE / 2 else idx
                }
                .take(MAX_SERVERS)

            var emitted = 0
            for (source in sources) {
                val sourceId = source.id ?: continue
                val provider = source.provider ?: continue
                val resolved = try {
                    parseJson<PlaybackResolve>(
                        app.post(
                            "$mainUrl/web-playback/sessions/$sessionId/sources/$sourceId/resolve",
                            headers = playbackHeaders(csrf),
                            json = mapOf<String, Any>()
                        ).text
                    )
                } catch (e: Exception) {
                    continue
                }
                val launchUrl = resolved.launchUrl ?: resolved.resolveUrl ?: continue

                val launchRes = try {
                    app.get(launchUrl, headers = baseHeaders(playUrl), allowRedirects = false)
                } catch (e: Exception) {
                    continue
                }
                var finalUrl = launchRes.url
                var page = launchRes.text
                val location = launchRes.headers["Location"] ?: launchRes.headers["location"]
                if (location != null) {
                    val target = if (location.startsWith("http")) location else "$mainUrl$location"
                    val redirected = try {
                        app.get(target, headers = baseHeaders(target))
                    } catch (e: Exception) {
                        continue
                    }
                    finalUrl = redirected.url
                    page = redirected.text
                }

                var result = EmbedResult()
                if (page.removePrefix("\uFEFF").startsWith("#EXTM3U")) {
                    result = result.merge(EmbedResult(listOf(finalUrl to Qualities.Unknown.value)))
                }
                result = result.merge(extractDirectFromPage(page))
                if (result.videos.isEmpty()) {
                    if (isDoodHost(finalUrl)) {
                        result = result.merge(extractDood(finalUrl, page))
                    } else if (isEmbedPostHost(finalUrl)) {
                        result = result.merge(postEmbedDl(finalUrl))
                    }
                }

                for ((videoUrl, videoQuality) in result.videos) {
                    val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
                    callback(
                        newExtractorLink(
                            source = "AnimeZid",
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
                        loadExtractor(finalUrl, playUrl, subtitleCallback) { link ->
                            callback(link)
                            emitted++
                        }
                    } catch (e: Exception) {
                    }
                    if ((provider.contains("vidlo", ignoreCase = true) ||
                            finalUrl.contains("vidlo", ignoreCase = true))
                    ) {
                        emitted += extractVidlo(finalUrl, tokenVidlo, callback)
                    }
                }
                val subSeen = HashSet<String>()
                for ((lang, subUrl) in result.subtitles) {
                    if (subSeen.add(subUrl)) subtitleCallback(newSubtitleFile(lang, subUrl))
                }
            }
            emitted > 0
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun extractVidlo(
        embedUrl: String,
        tokenVidlo: String,
        callback: (ExtractorLink) -> Unit
    ): Int {
        return try {
            val vidloUrlWithToken = if (tokenVidlo.isNotEmpty()) {
                if (embedUrl.contains("?")) "$embedUrl&${tokenVidlo.removePrefix("?")}"
                else "$embedUrl$tokenVidlo"
            } else {
                embedUrl
            }
            val vidloRes = app.get(
                vidloUrlWithToken,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$mainUrl/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                )
            ).text
            val sourcesMatch = Regex("""sources\s*:\s*\[(.*?)\]""", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(vidloRes) ?: return 0
            val sources = sourcesMatch.groupValues[1]
            val files = Regex("""file\s*:\s*"([^"]+)"""").findAll(sources).map { it.groupValues[1] }.toList()
            val labels = Regex("""label\s*:\s*"([^"]+)"""").findAll(sources).map { it.groupValues[1] }.toList()
            var count = 0
            var qualityIndex = 0
            for (file in files) {
                if (file.endsWith(".m3u8")) {
                    callback(
                        newExtractorLink(
                            source = "AnimeZid",
                            name = "Vidlo HLS",
                            url = file,
                        ) {
                            referer = "$mainUrl/"
                            quality = Qualities.Unknown.value
                        }
                    )
                    count++
                } else {
                    val label = if (qualityIndex < labels.size) labels[qualityIndex] else "Unknown"
                    callback(
                        newExtractorLink(
                            source = "AnimeZid",
                            name = "Vidlo $label",
                            url = file,
                        ) {
                            referer = "$mainUrl/"
                            quality = getQualityFromName(label)
                        }
                    )
                    qualityIndex++
                    count++
                }
            }
            count
        } catch (e: Exception) {
            0
        }
    }

    private fun extractDirectFromPage(page: String): EmbedResult {
        val videos = LinkedHashSet<Pair<String, Int>>()
        fun add(url: String, quality: Int = Qualities.Unknown.value) {
            if (looksLikeVideo(url)) videos += url to quality
        }
        Regex("data-hash=\"([^\"]+\\.m3u8[^\"]*)\"", RegexOption.IGNORE_CASE).find(page)
            ?.let { add(it.groupValues[1]) }
        Regex("var\\s+urlPlay\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE).find(page)
            ?.let { add(it.groupValues[1]) }
        Regex("(https?://[^\\s\"'<>]+\\.(?:m3u8|mp4)[^\\s\"'<>]*)", RegexOption.IGNORE_CASE).findAll(page)
            .forEach { add(it.groupValues[1]) }
        if (videos.isNotEmpty()) return EmbedResult(videos = videos.toList(), subtitles = extractSubtitles(page))
        val decoded = unpackPacker(page) ?: return EmbedResult(subtitles = extractSubtitles(page))
        return parsePackedMedia(decoded).merge(EmbedResult(subtitles = extractSubtitles(page)))
    }

    private fun parsePackedMedia(decoded: String): EmbedResult {
        val videos = mutableListOf<Pair<String, Int>>()
        Regex("""\{[^{}]*\}""").findAll(decoded).forEach { obj ->
            val file = Regex("""file\s*:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(obj.value)?.groupValues?.get(1) ?: return@forEach
            if (!looksLikeVideo(file)) return@forEach
            val label = Regex("""(?:label|quality)\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(obj.value)?.groupValues?.get(1)
            videos += file to qualityFromLabel(label)
        }
        val seen = videos.map { it.first }.toHashSet()
        Regex("""(?:file|src|url)\s*[:=]\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(decoded).forEach { m ->
                val candidate = m.groupValues[1]
                if (candidate !in seen && looksLikeVideo(candidate)) videos += candidate to Qualities.Unknown.value
            }
        return EmbedResult(videos = videos, subtitles = extractSubtitles(decoded))
    }

    private fun extractSubtitles(source: String): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        Regex("""tracks\s*:\s*\[(.*?)\]""", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(source)
            .forEach { block ->
                Regex("""\{[^{}]*file\s*:\s*["']([^"']+)["'][^{}]*\}""", RegexOption.IGNORE_CASE)
                    .findAll(block.groupValues[1]).forEach { obj ->
                        val url = obj.groupValues[1]
                        if (!url.startsWith("http")) return@forEach
                        if (!url.contains(".vtt") && !url.contains(".srt") &&
                            !url.contains("/vtt/") && !url.contains("/srt/")
                        ) return@forEach
                        if (url.contains("thumbnails") || url.contains("_sli") || url.contains("empty")) return@forEach
                        val label = Regex("""(?:label|language|kind)\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                            .find(obj.value)?.groupValues?.get(1)
                        if (label?.lowercase(Locale.ROOT) == "thumbnails") return@forEach
                        out += (label ?: "Arabic") to url
                    }
            }
        return out.toList()
    }

    private fun qualityFromLabel(label: String?): Int {
        return when (label?.trim()?.lowercase(Locale.ROOT)) {
            "2160p", "4k", "4k60", "2160" -> Qualities.P2160.value
            "1440p", "2k", "1440" -> Qualities.P1440.value
            "1080p", "1080", "fullhd", "full hd", "fhd" -> Qualities.P1080.value
            "720p", "720", "hd" -> Qualities.P720.value
            "480p", "480", "sd" -> Qualities.P480.value
            "360p", "360" -> Qualities.P360.value
            "240p", "240" -> Qualities.P240.value
            "144p", "144" -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }

    private fun looksLikeVideo(url: String): Boolean {
        val lower = url.lowercase()
        if (Regex(""".\.(?:jpg|jpeg|png|gif|svg|vtt|srt|css|js|ico|xml|woff2?|json|txt)$""").containsMatchIn(lower)) return false
        return lower.contains(".m3u8") || lower.contains(".mp4") ||
            (lower.contains("token=") && lower.contains("expiry="))
    }

    private fun isEmbedPostHost(url: String): Boolean {
        val host = getHost(url).lowercase()
        if (host.contains("vidtube") || host.contains("rubyvidhub") ||
            host.contains("streamruby") || host.contains("playmogo")
        ) return true
        return try {
            val path = java.net.URI(url).path.orEmpty()
            path.startsWith("/e/") || path.startsWith("/embed/")
        } catch (e: Exception) {
            false
        }
    }

    private fun isDoodHost(url: String): Boolean {
        return getHost(url).lowercase().contains("dood")
    }

    private suspend fun extractDood(finalUrl: String, page: String): EmbedResult {
        return try {
            val host = getHost(finalUrl)
            val match = Regex("""/pass_md5/([a-zA-Z0-9]+)/(\d+)""").find(page) ?: return EmbedResult()
            val md5 = match.groupValues[1]
            val ts = match.groupValues[2]
            val json = app.get(
                "https://$host/pass_md5/$md5/$ts",
                headers = mutableMapOf(
                    "User-Agent" to userAgent,
                    "Referer" to finalUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json"
                )
            ).text
            val token = Regex(""""token"\s*:\s*"([^"]+)""").find(json)?.groupValues?.get(1)
            if (token.isNullOrBlank()) return EmbedResult()
            EmbedResult(videos = listOf("https://$host/$token" to Qualities.Unknown.value))
        } catch (e: Exception) {
            EmbedResult()
        }
    }

    private suspend fun postEmbedDl(finalUrl: String): EmbedResult {
        return try {
            val host = getHost(finalUrl)
            val path = finalUrl.substringAfterLast('/').substringBeforeLast('.')
            val code = path.substringAfterLast('-')
            if (code.isBlank()) return EmbedResult()
            val res = app.post(
                "https://$host/dl",
                headers = mutableMapOf(
                    "User-Agent" to userAgent,
                    "Referer" to finalUrl,
                    "Origin" to "https://$host",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                ),
                data = mapOf(
                    "op" to "embed",
                    "file_code" to code,
                    "auto" to "1",
                    "referer" to finalUrl
                )
            )
            val decoded = unpackPacker(res.text)
            if (decoded != null) parsePackedMedia(decoded)
            else extractDirectFromPage(res.text)
        } catch (e: Exception) {
            EmbedResult()
        }
    }

    private fun unpackPacker(page: String): String? {
        val match = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?\}\(\s*(['"])(.*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])(.*?)\5\.split""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(page) ?: return null
        val raw = match.groupValues[2]
        val aRaw = match.groupValues[3].toIntOrNull() ?: return null
        val cRaw = match.groupValues[4].toIntOrNull() ?: return null
        val k = match.groupValues[6].split("|")
        if (aRaw < 2 || cRaw < 0) return null
        val a = if (aRaw > 62) 62 else aRaw
        val p = raw.replace("\\'", "'")
        val mapping = HashMap<String, String>()
        for (i in 0 until cRaw) {
            val key = intToBase(i, a)
            val value = k.getOrNull(i)
            if (!value.isNullOrBlank()) mapping[key] = value
        }
        return Regex("([0-9A-Za-z]+)").replace(p) { m -> mapping[m.value] ?: m.value }
    }

    private fun intToBase(num: Int, base: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.append(digits[n % base])
            n /= base
        }
        return sb.reverse().toString()
    }

    private fun getHost(url: String): String {
        return try {
            val uri = java.net.URI(url)
            uri.host ?: uri.authority ?: url
        } catch (e: Exception) {
            url
        }
    }

    private fun cleanTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return "Unknown"
        var title = raw.trim()
        val leading = Regex("^(وصلة\\s*)?(?:مشاهدة|فتح|حلقات|الحلقة|فيلم|مسلسل|انمي|أنمي|كرتون|سهرة)\\s+")
        while (true) {
            val match = leading.find(title) ?: break
            title = title.substring(match.range.last + 1).trimStart()
        }
        val trailing = Regex(
            "(?:مترجم|مدبلج|مصري|بالعربية|بالعربي|العربية|العربي|بجودة عالية|جودة عالية|والاخيرة|واخيرة|الاخيرة|الحلقة|حلقة|HD|SD|Web-?DL|WEB-?DL|1080p|720p|2160p|4K|\\d{4})\\s*$"
        )
        repeat(8) {
            val match = trailing.find(title) ?: return@repeat
            title = title.substring(0, match.range.first).trimEnd()
        }
        return title.replace(Regex("\\s{2,}"), " ").trim()
    }
}