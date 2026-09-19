package com.shahid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

class ShahidProvider : MainAPI() {
    override var mainUrl = "https://shahid.mbc.net"
    override var name = "MBC Shahid"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = false
    override val hasChromecastSupport = false
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "Main/LEVANT/home/LEV-Home-Editorial-Hero-All-All~Guest" to "الرئيسية",
        "Main/LEVANT/home/LEV-Home-Trends-RecentContent-All-All~Guest" to "جديد MBC Shahid",
        "TOP-MOVIES" to "أفضل الأفلام",
        "TOP-SERIES" to "أفضل المسلسلات",
        "Main/LEVANT/home/LEV-Home-Trends-RecentContent-Series-Dialect-All-All-PAN~Guest" to "مسلسلات عربية",
        "Main/LEVANT/home/LEV-Home-Trends-RecentContent-Series-Dialect-All-All-Egyptian~Guest" to "مسلسلات مصرية",
        "Main/LEVANT/home/LEV-Home-Trends-RecentContent-Series-Dialect-All-All-Gulf~Guest" to "مسلسلات خليجية",
        "Main/LEVANT/home/LEV-Home-Trends-RecentContent-Series-Dialect-All-All-Saudi~Guest" to "مسلسلات سعودية",
        "Main/LEVANT/home/LEV-Home-Trends-RecentContent-Series-Dialect-All-All-Turkish~Guest" to "مسلسلات تركية",
        "Main/LEVANT/home/LEV-Home-Editorial-TurkishMovies-All-All~Guest" to "أفلام تركية",
        "Main/LEVANT/home/LEV-Home-Trends-RecentContent-Series-Dialect-All-All-Korean~Guest" to "صنع في كوريا",
        "Main/LEVANT/home/LEV-Home-Trends-RecentContent-Series-Dialect-All-All-Bollywood~Guest" to "بوليوود",
        "Main/LEVANT/home/LEV-Home-Trends-TopContent-Movies-All-All" to "أفضل 10 أفلام",
        "Main/LEVANT/home/LEV-Home-Trends-TopContent-Series-All-All" to "أفضل 10 مسلسلات",
        "Main/LEVANT/home/LEV-Home-Editorial-AwardWinning-Movies-All~Guest" to "أفلام حائزة على جوائز",
        "Main/LEVANT/home/LEV-Home-Trends-Related-Movies-Genre-All-All-Action~Guest" to "أفلام أكشن",
        "Main/LEVANT/home/LEV-Home-Trends-Related-Movies-Genre-All-All-Horror~Guest" to "أفلام رعب",
        "Main/LEVANT/home/LEV-Home-Trends-Related-Movies-Genre-All-All-Family~Guest" to "أفلام عائلية",
        "Main/LEVANT/home/LEV-Home-Hybrid-EditorialPersonalized-RecentContent-All-All-ComingSoon~Guest" to "قريباً"
    )

    private val apiBase = "https://api3.shahid.net/proxy/v2.1/"
    private val country = "IQ"

    private val appHeaders = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/en",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "language" to "EN",
        "UUID" to "WEB",
        "X-Anonymous-Id" to "web_" + UUID.randomUUID().toString(),
        "country" to country
    )

    private fun searchHeaders(): Map<String, String> = appHeaders + mapOf(
        "mParticleID" to "mp_" + UUID.randomUUID().toString()
    )

    private fun detailUrl(id: Long) = "$mainUrl/en/detail/$id"

    private fun idFromUrl(url: String): Long {
        val trimmed = url.trim().trimEnd('/')
        val m = Regex("""[-/](\d+)$""").find(trimmed) ?: return 0L
        return m.groupValues[1].toLongOrNull() ?: 0L
    }

    private fun fixImage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.replace("{height}", "450")
            .replace("{width}", "300")
            .replace("{croppingPoint}", "1")
    }

    private suspend fun apiGet(
        path: String,
        requestJson: String,
        headers: Map<String, String> = appHeaders
    ): JSONObject? {
        val q = URLEncoder.encode(requestJson, "UTF-8").replace("+", "%20")
        return runCatching {
            JSONObject(app.get("$apiBase$path?request=$q&country=$country", headers = headers).text)
        }.getOrNull()
    }

    private fun imageOf(obj: JSONObject): String? {
        val img = obj.optJSONObject("image")
        return fixImage(
            img?.optString("thumbnailImage").takeIf { !it.isNullOrBlank() }
                ?: img?.optString("posterImage")
                ?: obj.optString("thumbnailImage").ifBlank { null }
        )
    }

    private fun yearOf(obj: JSONObject): Int? {
        val pd = obj.optString("productionDate")
        return if (pd.length >= 4) pd.substring(0, 4).toIntOrNull() else null
    }

    private fun genresOf(obj: JSONObject): List<String> {
        return runCatching {
            val out = mutableListOf<String>()
            val arr = obj.optJSONArray("genres")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val g = arr.optString(i)
                    if (g.isNotBlank()) out.add(g)
                }
            }
            val dialect = obj.optString("dialect")
            if (dialect.isNotBlank() && !dialect.equals("NULL", true)) out.add(dialect)
            out.distinct()
        }.getOrDefault(emptyList())
    }

    private fun searchResponse(obj: JSONObject): SearchResponse? {
        val tv = when (obj.optString("productType")) {
            "MOVIE" -> TvType.Movie
            "SHOW" -> TvType.TvSeries
            else -> return null
        }
        val id = obj.optLong("id", 0L)
        if (id <= 0L) return null
        val title = obj.optString("title").trim()
        if (title.isEmpty()) return null
        val url = obj.optJSONObject("productUrl")?.optString("url")
            ?.takeIf { it.isNotBlank() } ?: detailUrl(id)
        val poster = imageOf(obj)

        return if (tv == TvType.Movie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                addPoster(poster)
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                addPoster(poster)
            }
        }
    }

    private fun parseList(root: JSONObject?): Pair<List<SearchResponse>, Boolean> {
        val list = arrayListOf<SearchResponse>()
        if (root == null) return list to false

        val items = root.optJSONArray("editorialItems")
        if (items != null) {
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i)?.optJSONObject("item") ?: continue
                searchResponse(it)?.let { list.add(it) }
            }
            return list to root.optBoolean("hasMore", false)
        }

        val arr = root.optJSONArray("products")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                searchResponse(arr.optJSONObject(i))?.let { list.add(it) }
            }
            return list to root.optBoolean("hasMore", false)
        }
        return list to false
    }

    private suspend fun rankingPage(marker: String, page: Int): HomePageResponse {
        val title = if (marker == "TOP-MOVIES") "أفضل الأفلام" else "أفضل المسلسلات"
        if (page > 1) {
            return newHomePageResponse(listOf(HomePageList(title, emptyList())), hasNext = false)
        }
        val root = apiGet(
            "product/top-ranking-by-type",
            """{"pageNumber":0,"pageSize":50,"profileType":"ADULT"}"""
        ) ?: return newHomePageResponse(listOf(HomePageList(title, emptyList())), hasNext = false)
        val top = root.optJSONObject("top") ?: return newHomePageResponse(listOf(HomePageList(title, emptyList())), hasNext = false)
        val group = if (marker == "TOP-MOVIES") top.optJSONObject("movie") else top.optJSONObject("series")
        val list = arrayListOf<SearchResponse>()
        val arr = group?.optJSONArray("products")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                searchResponse(arr.optJSONObject(i))?.let { list.add(it) }
            }
        }
        return newHomePageResponse(listOf(HomePageList(title, list)), hasNext = false)
    }

    private suspend fun carouselPage(usecaseId: String, page: Int, title: String): HomePageResponse {
        val idx = if (page > 1) page - 1 else 0
        val root = apiGet(
            "editorial/carousel",
            """{"id":"$usecaseId","displayedItems":0,"itemsRequestedStatic":true,"pageNumber":$idx,"pageSize":20}"""
        ) ?: return newHomePageResponse(listOf(HomePageList(title, emptyList())), hasNext = false)
        val (list, hasNext) = parseList(root)
        return newHomePageResponse(listOf(HomePageList(title, list)), hasNext = hasNext)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.trim()
        return when (data) {
            "TOP-MOVIES", "TOP-SERIES" -> rankingPage(data, page)
            else -> carouselPage(data, page, request.name)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val text = runCatching {
            app.get(
                "$apiBase" + "search/" + URLEncoder.encode(q, "UTF-8").replace("+", "%20") + "?country=$country",
                headers = searchHeaders()
            ).text
        }.getOrNull() ?: return emptyList()
        return parseList(runCatching { JSONObject(text) }.getOrNull()).first
    }

    override suspend fun load(url: String): LoadResponse {
        val id = idFromUrl(url)
        if (id <= 0L) throw ErrorLoadingException("معرف غير صالح")
        val pm = apiGet("product/id", """{"id":$id}""")?.optJSONObject("productModel")
            ?: throw ErrorLoadingException("تعذر تحميل البيانات")
        return if (pm.optString("productType") == "MOVIE") {
            movieLoad(pm, url)
        } else {
            seriesLoad(pm, url)
        }
    }

    private suspend fun movieLoad(pm: JSONObject, url: String): LoadResponse {
        val title = pm.optString("title").ifBlank { "MBC Shahid" }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = imageOf(pm)
            this.plot = pm.optString("description").ifBlank { null }
            this.year = yearOf(pm)
            this.tags = genresOf(pm)
        }
    }

    private suspend fun seriesLoad(pm: JSONObject, url: String): LoadResponse {
        val title = pm.optString("title").ifBlank { "MBC Shahid" }
        val episodes = runCatching { fetchEpisodes(pm) }.getOrDefault(emptyList())
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = imageOf(pm)
            this.plot = pm.optString("description").ifBlank { null }
            this.year = yearOf(pm)
            this.tags = genresOf(pm)
        }
    }

    private suspend fun fetchEpisodes(pm: JSONObject): List<Episode> {
        val episodes = arrayListOf<Episode>()
        val seasonObj = pm.optJSONObject("season")
        val playlists = seasonObj?.optJSONArray("playlists") ?: return episodes
        val seasonNumber = seasonObj.optString("seasonNumber").toIntOrNull() ?: 1

        var playlistId: String? = null
        for (i in 0 until playlists.length()) {
            val pl = playlists.optJSONObject(i) ?: continue
            if (pl.optString("type") == "EPISODE") {
                playlistId = pl.optString("id")
                break
            }
        }
        if (playlistId.isNullOrBlank()) return episodes

        val root = apiGet(
            "product/playlist",
            """{"playListId":"$playlistId","pageNumber":0,"pageSize":100,"sorts":[{"order":"DESC","type":"SORTDATE"}]}"""
        ) ?: return episodes

        val arr = root.optJSONObject("productList")?.optJSONArray("products")
            ?: root.optJSONArray("products")
            ?: return episodes

        for (i in 0 until arr.length()) {
            val ep = arr.optJSONObject(i) ?: continue
            val id = ep.optLong("id", 0L)
            if (id <= 0L) continue
            val epNumber = ep.optInt("episodeNumber", 0).takeIf { it > 0 } ?: (i + 1)
            val name = ep.optString("title").ifBlank { "الحلقة $epNumber" }
            episodes.add(
                newEpisode(detailUrl(id)) {
                    this.name = name
                    this.season = seasonNumber
                    this.episode = epNumber
                    this.posterUrl = imageOf(ep)
                    this.description = ep.optString("description").ifBlank { null }
                }
            )
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return true
    }
}