package com.kurdbin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class KurdbinProvider : MainAPI() {
    override var name = "Kurdbin"
    override var mainUrl = "https://kurdbin.kurdsat.tv"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val apiBase = "https://portal.kurdbin.net/api"
    private val imageBase = "https://portal.kurdbin.net"
    private val pageSize = 20

    override val mainPage = mainPageOf(
        *categories.map { (catId, title) ->
            "$apiBase/videos?filters[type][id]=$catId&sort=date:desc" to title
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data ?: return newHomePageResponse(request.name ?: name, emptyList())
        var url = base
        if (page > 1) url += "&pagination[page]=$page"
        url += "&pagination[pageSize]=$pageSize&populate[thumbnail][fields][0]=url"
        val json = AppUtils.tryParseJson<KurdbinList>(app.get(url).text)
            ?: return newHomePageResponse(request.name ?: name, emptyList())
        val items = json.data.orEmpty().mapNotNull { it.toSearchResponse() }
        val total = json.meta?.pagination?.total ?: 0
        val hasMore = total > page * pageSize
        return newHomePageResponse(
            listOf(HomePageList(request.name ?: name, items)),
            hasNext = hasMore
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$apiBase/videos?filters[title][${'$'}containsi]=$q&sort=date:desc" +
                "&pagination[pageSize]=$pageSize&populate[thumbnail][fields][0]=url"
        val json = AppUtils.tryParseJson<KurdbinList>(app.get(url).text) ?: return emptyList()
        return json.data.orEmpty().mapNotNull { it.toSearchResponse() }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return null
        return newSearchResponseList(search(query) ?: emptyList(), hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val videoId = parseVideoId(url) ?: return null
        val json = AppUtils.tryParseJson<KurdbinVideo>(
            app.get("$apiBase/videos/$videoId?fields[0]=title&fields[1]=length&fields[2]=date&fields[3]=body&populate[thumbnail][fields][0]=url").text
        ) ?: return null
        val data = json.data ?: return null
        val attrs = data.attributes ?: return null
        val title = attrs.title?.takeIf { it.isNotBlank() }?.trim() ?: return null
        val loadUrl = "$mainUrl/videos/$videoId"
        return newMovieLoadResponse(title, loadUrl, TvType.Movie, loadUrl) {
            this.posterUrl = attrs.thumbnail?.data?.attributes?.url?.let { imageBase + it }
            this.year = attrs.date?.take(4)?.toIntOrNull()
            this.plot = attrs.body
            this.duration = attrs.length?.toSeconds()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = parseVideoId(data) ?: return false
        val json = AppUtils.tryParseJson<KurdbinVideo>(
            app.get("$apiBase/videos/$videoId?fields[0]=hls&fields[1]=video_link").text
        ) ?: return false
        val attrs = json.data?.attributes ?: return false
        var found = false

        val hls = attrs.hls?.takeIf { it.isNotBlank() }
        if (hls != null) {
            found = true
            callback.invoke(newExtractorLink(name, name, hls, ExtractorLinkType.M3U8) {
                this.referer = imageBase
            })
        }

        val link = attrs.videoLink?.takeIf { it.isNotBlank() }?.substringBefore('"').orEmpty()
        when {
            link.contains("youtube.com/embed/") -> {
                val ytId = Regex("""youtube\.com/embed/([\w-]+)""").find(link)?.groupValues?.get(1)
                if (ytId != null &&
                    loadExtractor("https://www.youtube.com/watch?v=$ytId", mainUrl, subtitleCallback, callback) == true
                ) {
                    found = true
                }
            }
            link.contains("iframe.mediadelivery.net") -> {
                val embed = runCatching { app.get(link, referer = mainUrl).text }.getOrNull().orEmpty()
                val m3u8 = Regex("""https://vz-[\w-]+\.b-cdn\.net/[^"'\\]+/playlist\.m3u8""").find(embed)?.value
                if (m3u8 != null) {
                    found = true
                    callback.invoke(newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                        this.referer = imageBase
                    })
                }
            }
        }
        return found
    }

    private fun KurdbinItem.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val attrs = attributes ?: return null
        val title = attrs.title?.takeIf { it.isNotBlank() }?.trim() ?: return null
        val url = "$mainUrl/videos/$id"
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = attrs.thumbnail?.data?.attributes?.url?.let { imageBase + it }
        }
    }

    private fun parseVideoId(url: String): String? =
        Regex("""(\d+)(?:/watch)?$""").find(url.trimEnd('/'))?.groupValues?.get(1)

    private fun String.toSeconds(): Int? {
        val parts = split(":").mapNotNull { it.trim().toIntOrNull() }
        if (parts.isEmpty()) return null
        return when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    private companion object {
        val categories = listOf(
            1L to "بەرنامە",
            2L to "دراما",
            3L to "زنجیرەی مناڵان",
            5L to "فیلمی کوردی",
            6L to "دۆکیومێنتاری",
            7L to "کۆنسێرت",
            8L to "مناڵان",
            9L to "فیلمی بیانی",
            10L to "کوردسات دۆکیومێنتاری",
            11L to "مناڵان - کارتۆن",
            12L to "زیتەڵە",
            13L to "ئەلف و بێ",
            16L to "کلیپ"
        )
    }
}