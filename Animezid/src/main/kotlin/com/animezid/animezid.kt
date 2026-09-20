package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder

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
    @SerialName("launch_url") val launchUrl: String? = null
)

class Animezid : MainAPI() {
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.php?keywords=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url, headers = baseHeaders(url)).document
        return doc.select("article.az-card a.az-card__link").mapNotNull { a -> parseCard(a) }
    }

    private fun parseCard(a: org.jsoup.nodes.Element): SearchResponse? {
        val href = a.attr("href")
        if (href.isBlank() || !(href.contains("watch.php") || href.contains("/series/") || href.contains("/movie/")))
            return null
        val label = a.attr("aria-label").ifBlank {
            a.selectFirst("img")?.attr("alt")
        } ?: return null
        val poster = a.selectFirst("img")?.attr("src")
        val isMovie = label.contains("فيلم")
        val type = if (isMovie) TvType.Movie
        else if (label.contains("أنمي") || label.contains("انمي") || label.contains("كرتون")) TvType.Anime
        else TvType.TvSeries

        return if (isMovie) {
            newMovieSearchResponse(cleanTitle(label), href, type) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(cleanTitle(label), href, type) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = baseHeaders(url)).document

        return when {
            url.contains("/series/") -> loadSeries(doc, url)
            doc.selectFirst("[data-season-count]") != null -> loadSeriesFromWatch(doc, url)
            else -> loadMovie(doc, url)
        }
    }

    private suspend fun loadSeries(doc: Document, url: String): LoadResponse {
        val titleRaw = doc.title().ifBlank { doc.selectFirst("h1")?.text() ?: "Unknown" }
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

        return newTvSeriesLoadResponse(title, url, type, sortEpisodes(allEpisodes)) {
            this.posterUrl = poster
            this.plot = plot
            this.year = Regex("(19\\d{2}|20\\d{2})").find(titleRaw)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    private suspend fun loadSeriesFromWatch(doc: Document, url: String): LoadResponse {
        val vid = url.substringAfterLast("?vid=").substringBefore("&")
        val titleRaw = doc.selectFirst("h1")?.text() ?: doc.title().ifBlank { "Unknown" }
        val isAnime = titleRaw.contains("أنمي") || titleRaw.contains("انمي")
        val type = if (isAnime) TvType.Anime else TvType.TvSeries
        val seasonCount = doc.selectFirst("[data-season-count]")?.attr("data-season-count")?.toIntOrNull() ?: 0
        val poster = getPoster(doc)
        val plot = getDescription(doc)

        val allEpisodes = ArrayList<Episode>()
        if (vid.isNotBlank() && vid != url) {
            for (season in 1..seasonCount) {
                allEpisodes.addAll(episodesForSeason(vid, season))
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

    private fun sortEpisodes(episodes: List<Episode>): List<Episode> {
        val sorted = episodes.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        if (sorted.all { (it.season ?: 0) <= 1 }) {
            sorted.forEach { it.season = 1 }
        }
        return sorted
    }

    private suspend fun loadMovie(doc: Document, url: String): LoadResponse {
        val titleRaw = doc.selectFirst("h1")?.text() ?: doc.title().ifBlank { "Unknown" }
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

            val sessionJson = app.post(
                "$mainUrl/web-playback/sessions",
                headers = playbackHeaders(playUrl, csrf),
                json = mapOf("content_id" to vid)
            ).text
            val session = parseJson<PlaybackSession>(sessionJson)
            val sessionId = session.sessionId ?: return false

            val sources = (session.sources ?: emptyList())
                .filter { it.type == "embedded_web" && !it.id.isNullOrBlank() }
                .sortedBy { if (it.provider?.contains("TurboViPlay") == true) 0 else 1 }

            for (source in sources) {
                val sourceId = source.id ?: continue
                val resolved = try {
                    parseJson<PlaybackResolve>(
                        app.post(
                            "$mainUrl/web-playback/sessions/$sessionId/sources/$sourceId/resolve",
                            headers = playbackHeaders(playUrl, csrf),
                            json = mapOf<String, Any>()
                        ).text
                    )
                } catch (e: Exception) {
                    continue
                }
                val launchUrl = resolved.launchUrl ?: continue

                val launchHtml = try {
                    app.get(launchUrl, headers = baseHeaders(playUrl)).text
                } catch (e: Exception) {
                    continue
                }

                val m3u8 = Regex("data-hash=\"([^\"]+\\.m3u8[^\"]*)\"", RegexOption.IGNORE_CASE).find(launchHtml)
                    ?.groupValues?.get(1)
                    ?: Regex("(https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)", RegexOption.IGNORE_CASE).find(launchHtml)
                        ?.groupValues?.get(1)

                if (m3u8 != null) {
                    callback(
                        newExtractorLink(
                            source = "AnimeZid",
                            name = source.provider ?: "Server",
                            url = m3u8,
                        ) {
                            this.referer = playUrl
                            type = ExtractorLinkType.M3U8
                            quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun playbackHeaders(referer: String, csrf: String): MutableMap<String, String> {
        val headers = baseHeaders(referer)
        headers["Origin"] = mainUrl
        headers["Accept"] = "application/json"
        headers["X-Requested-With"] = "XMLHttpRequest"
        headers["Content-Type"] = "application/json"
        headers["X-Playback-CSRF"] = csrf
        return headers
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
            "(?:مترجم|مدبلج|بالعربية|بالعربي|العربية|العربي|بجودة عالية|جودة عالية|والاخيرة|واخيرة|الاخيرة|الحلقة|حلقة|HD|SD|Web-?DL|WEB-?DL|1080p|720p|2160p|4K|\\d{4})\\s*$"
        )
        repeat(8) {
            val match = trailing.find(title) ?: return@repeat
            title = title.substring(0, match.range.first).trimEnd()
        }
        return title.replace(Regex("\\s{2,}"), " ").trim()
    }
}