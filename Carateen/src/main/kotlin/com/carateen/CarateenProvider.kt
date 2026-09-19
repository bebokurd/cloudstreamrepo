package com.carateen

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CarateenProvider : MainAPI() {
    override var mainUrl = "https://carateen.tv"
    override var name = "Carateen"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Movie
    )

    private val encryptionKey = "7annaba3l_loves_crypto_safe_key!"
    private val posterBaseUrl = "https://carateen.tv/assets/img/posters/"

    private val standardHeaders = mapOf(
        "X-Cartoony-Client" to "web-frontend-v1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "Accept" to "application/json, text/plain, */*"
    )

    private class SimpleCache<T>(val data: T, val time: Long)

    @Volatile
    private var spShowsCache: SimpleCache<JSONArray>? = null

    @Volatile
    private var tgShowsCache: SimpleCache<JSONArray>? = null

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    private fun decryptJson(raw: String): String {
        return try {
            val obj = JSONObject(raw)
            val encryptedData = obj.optString("encryptedData")
            val iv = obj.optString("iv")
            if (encryptedData.isEmpty() || iv.isEmpty()) return raw
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(encryptionKey.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(hexToBytes(iv))
            )
            String(cipher.doFinal(hexToBytes(encryptedData)), Charsets.UTF_8)
        } catch (e: Exception) {
            raw
        }
    }

    private fun decryptArray(raw: String): JSONArray {
        val text = decryptJson(raw).trim()
        return if (text.startsWith("[")) JSONArray(text) else JSONArray("[$text]")
    }

    private fun decryptObject(raw: String): JSONObject {
        val text = decryptJson(raw).trim()
        return if (text.startsWith("[")) JSONObject() else JSONObject(text)
    }

    private suspend fun getSpShows(): JSONArray {
        spShowsCache?.let {
            if (System.currentTimeMillis() - it.time < 15 * 60_000) return it.data
        }
        val array = decryptArray(app.get("$mainUrl/api/sp/tvshows", headers = standardHeaders).text)
        spShowsCache = SimpleCache(array, System.currentTimeMillis())
        return array
    }

    private suspend fun getTgShows(): JSONArray {
        tgShowsCache?.let {
            if (System.currentTimeMillis() - it.time < 15 * 60_000) return it.data
        }
        val array = decryptArray(app.get("$mainUrl/api/tvshows", headers = standardHeaders).text)
        tgShowsCache = SimpleCache(array, System.currentTimeMillis())
        return array
    }

    private fun spPoster(obj: JSONObject): String {
        return obj.optString("cover_full_path")
            .ifBlank { obj.optString("trailer_cover_full_path") }
    }

    private fun tgPoster(cover: String?): String {
        if (cover.isNullOrBlank()) return ""
        val file = cover.trim().trimStart('/')
        return if (file.startsWith("http")) file else "$posterBaseUrl$file"
    }

    private fun spSearchResponse(obj: JSONObject): SearchResponse {
        val id = obj.optLong("id")
        val url = "$mainUrl/watch/sp/$id"
        val title = obj.optString("name")
        val poster = spPoster(obj)
        val isMovie = obj.optInt("is_movie", 0) == 1
        val type = if (isMovie) TvType.Movie else TvType.Cartoon
        return if (isMovie) {
            newMovieSearchResponse(title, url, type) {
                this.addPoster(poster)
            }
        } else {
            newTvSeriesSearchResponse(title, url, type) {
                this.addPoster(poster)
            }
        }
    }

    private fun tgSearchResponse(obj: JSONObject): SearchResponse {
        val id = obj.optLong("id")
        val url = "$mainUrl/watch/$id"
        val title = obj.optString("title")
        val poster = tgPoster(obj.optString("poster_cover"))
        val category = obj.optString("category")
        val isMovie = category.contains("فيلم")
        val type = if (isMovie) TvType.Movie else TvType.Cartoon
        return if (isMovie) {
            newMovieSearchResponse(title, url, type) {
                this.addPoster(poster)
            }
        } else {
            newTvSeriesSearchResponse(title, url, type) {
                this.addPoster(poster)
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        val homePageList = arrayListOf<HomePageList>()

        runCatching {
            val recent = decryptArray(
                app.get("$mainUrl/api/sp/recentEpisodes", headers = standardHeaders).text
            )
            val items = (0 until recent.length()).mapNotNull { i ->
                val obj = recent.optJSONObject(i) ?: return@mapNotNull null
                val showId = obj.optLong("tv_series_id")
                val title = obj.optString("name").ifBlank { return@mapNotNull null }
                val poster = obj.optString("cover_full_path")
                val epNum = obj.optInt("number", 0)
                val displayName = if (epNum > 0) "$title - الحلقة $epNum" else title
                newTvSeriesSearchResponse(displayName, "$mainUrl/watch/sp/$showId", TvType.Cartoon) {
                    this.addPoster(poster)
                }
            }.distinctBy { it.url }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList("آخر الحلقات", items.take(40)))
            }
        }.onFailure { logError(it) }

        runCatching {
            val sp = getSpShows()
            val series = (0 until sp.length()).map { sp.getJSONObject(it) }
                .filter { it.optInt("is_movie", 0) != 1 }
                .sortedByDescending { it.optString("updated_at") }
            val items = series.mapNotNull { if (it.optString("name").isBlank()) null else spSearchResponse(it) }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList("مسلسلات سبيستون", items.take(30)))
            }
        }.onFailure { logError(it) }

        runCatching {
            val sp = getSpShows()
            val movies = (0 until sp.length()).map { sp.getJSONObject(it) }
                .filter { it.optInt("is_movie", 0) == 1 }
                .sortedByDescending { it.optString("updated_at") }
            val items = movies.mapNotNull { if (it.optString("name").isBlank()) null else spSearchResponse(it) }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList("أفلام سبيستون", items.take(30)))
            }
        }.onFailure { logError(it) }

        runCatching {
            val tg = getTgShows()
            val series = (0 until tg.length()).map { tg.getJSONObject(it) }
                .filter { !it.optString("category").contains("فيلم") }
            val items = series.mapNotNull { if (it.optString("title").isBlank()) null else tgSearchResponse(it) }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList("مسلسلات كرتون", items.take(30)))
            }
        }.onFailure { logError(it) }

        runCatching {
            val tg = getTgShows()
            val movies = (0 until tg.length()).map { tg.getJSONObject(it) }
                .filter { it.optString("category").contains("فيلم") }
            val items = movies.mapNotNull { if (it.optString("title").isBlank()) null else tgSearchResponse(it) }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList("أفلام كرتون", items.take(30)))
            }
        }.onFailure { logError(it) }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val results = arrayListOf<SearchResponse>()

        runCatching {
            val sp = getSpShows()
            for (i in 0 until sp.length()) {
                val obj = sp.getJSONObject(i)
                val title = obj.optString("name")
                val pref = obj.optString("pref")
                if (title.contains(q, true) || pref.contains(q, true)) {
                    results.add(spSearchResponse(obj))
                }
            }
        }.onFailure { logError(it) }

        runCatching {
            val tg = getTgShows()
            for (i in 0 until tg.length()) {
                val obj = tg.getJSONObject(i)
                val title = obj.optString("title")
                if (title.contains(q, true)) {
                    results.add(tgSearchResponse(obj))
                }
            }
        }.onFailure { logError(it) }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("/watch/sp/")) {
            return loadSp(url)
        }
        return loadTg(url)
    }

    private suspend fun fetchSpEpisodes(showId: Long): JSONArray {
        return decryptArray(app.get("$mainUrl/api/sp/episodes?id=$showId", headers = standardHeaders).text)
    }

    private suspend fun fetchTgEpisodes(showId: Long): JSONArray {
        return decryptArray(app.get("$mainUrl/api/episodes?id=$showId", headers = standardHeaders).text)
    }

    private suspend fun loadSp(url: String): LoadResponse {
        val showId = url.substringAfterLast("/").toLongOrNull()
            ?: throw ErrorLoadingException("invalid url")
        val episodesArray = fetchSpEpisodes(showId)

        var title = ""
        var poster: String? = null
        var plot: String? = null
        var isMovie = false
        var epCount = 0

        runCatching {
            val sp = getSpShows()
            for (i in 0 until sp.length()) {
                val obj = sp.getJSONObject(i)
                if (obj.optLong("id") == showId) {
                    title = obj.optString("name")
                    poster = spPoster(obj).ifBlank { null }
                    plot = obj.optString("pref").ifBlank { null }
                    isMovie = obj.optInt("is_movie", 0) == 1
                    epCount = obj.optInt("ep_count", 0)
                    break
                }
            }
        }.onFailure { logError(it) }

        if (title.isBlank()) title = "sp$showId"

        val episodes = (0 until episodesArray.length()).map { i ->
            val obj = episodesArray.getJSONObject(i)
            val epId = obj.optLong("id")
            val number = obj.optInt("number", 0)
            val name = obj.optString("pref").ifBlank { null }
            val seasonVal = obj.opt("season")
            val season = if (seasonVal is Int && seasonVal > 0) seasonVal else 1
            val cover = obj.optString("cover_full_path").ifBlank { poster }
            newEpisode("spEp:$epId") {
                this.name = name
                this.season = season
                this.episode = number
                this.posterUrl = cover
            }
        }

        if (episodes.isEmpty()) throw ErrorLoadingException("لا توجد حلقات")

        val rating = runCatching {
            val sp = getSpShows()
            for (i in 0 until sp.length()) {
                val obj = sp.getJSONObject(i)
                if (obj.optLong("id") == showId) {
                    return@runCatching obj.optDouble("rating", 0.0)
                }
            }
            0.0
        }.getOrDefault(0.0)

        return if (isMovie || epCount <= 1) {
            val data = episodes.joinToString("||") { it.data }
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.plot = plot
                if (rating > 0) this.score = Score.from10(rating * 2)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
                this.posterUrl = poster
                this.plot = plot
                if (rating > 0) this.score = Score.from10(rating * 2)
            }
        }
    }

    private suspend fun loadTg(url: String): LoadResponse {
        val showId = url.substringAfterLast("/").toLongOrNull()
            ?: throw ErrorLoadingException("invalid url")
        val episodesArray = fetchTgEpisodes(showId)

        var title = ""
        var poster: String? = null
        var plot: String? = null
        var year: Int? = null
        var category = ""

        runCatching {
            val tg = getTgShows()
            for (i in 0 until tg.length()) {
                val obj = tg.getJSONObject(i)
                if (obj.optLong("id") == showId) {
                    title = obj.optString("title")
                    poster = tgPoster(obj.optString("poster_cover")).ifBlank { null }
                    plot = obj.optString("description").ifBlank { null }
                    year = obj.optString("release_year").toIntOrNull()
                    category = obj.optString("category")
                    break
                }
            }
        }.onFailure { logError(it) }

        if (title.isBlank()) title = "tg$showId"

        val episodes = (0 until episodesArray.length()).mapIndexed { index, i ->
            val obj = episodesArray.getJSONObject(i)
            val epId = obj.optLong("id")
            val order = obj.optInt("order_id", 0)
            val number = if (order > 0) order else index + 1
            val seasonVal = obj.opt("season")
            val season = if (seasonVal is Int && seasonVal > 0) seasonVal else 1
            val thumb = tgPoster(obj.optString("thumbnail")).ifBlank { null }
            newEpisode("tgEp:$epId") {
                this.season = season
                this.episode = number
                this.posterUrl = thumb
            }
        }

        if (episodes.isEmpty()) throw ErrorLoadingException("لا توجد حلقات")

        val isMovie = category.contains("فيلم") || episodes.size <= 1

        return if (isMovie) {
            val data = episodes.joinToString("||") { it.data }
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        data.split("||").filter { it.isNotBlank() }.forEach { token ->
            try {
                if (token.startsWith("spEp:")) {
                    val epId = token.removePrefix("spEp:")
                    val response = app.post(
                        "$mainUrl/api/sp/episode/link",
                        headers = standardHeaders + mapOf("Content-Type" to "application/json"),
                        json = mapOf("episodeId" to epId)
                    )
                    val obj = decryptObject(response.text)
                    val link = obj.optString("link")
                    if (link.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Carateen - HLS",
                                url = link
                            ) {
                                referer = "$mainUrl/"
                                quality = Qualities.Unknown.value
                                type = ExtractorLinkType.M3U8
                            }
                        )
                    }
                } else if (token.startsWith("tgEp:")) {
                    val epId = token.removePrefix("tgEp:")
                    val response = app.get("$mainUrl/api/episode?id=$epId", headers = standardHeaders)
                    val streamUrl = decryptObject(response.text).optString("streamUrl").trim()
                    if (streamUrl.isBlank()) return@forEach
                    val resolved = if (streamUrl.startsWith("//")) "https:$streamUrl" else streamUrl
                    if (!resolved.startsWith("https://") && !resolved.startsWith("http://")) return@forEach
                    val isDash = resolved.contains(".mpd", ignoreCase = true)
                    if (resolved.contains(".m3u8", ignoreCase = true) || isDash) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Carateen - ${if (isDash) "DASH" else "HLS"}",
                                url = resolved
                            ) {
                                referer = "$mainUrl/"
                                quality = Qualities.Unknown.value
                                type = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                            }
                        )
                    } else {
                        loadExtractor(resolved, "$mainUrl/", subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                logError(e)
            }
        }
        return true
    }
}