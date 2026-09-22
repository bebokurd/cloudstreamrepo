package com.okru

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import java.net.URLEncoder

class OkruProvider : MainAPI() {
    override var name = "Ok.ru"
    override var mainUrl = "https://ok.ru"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val showcaseUrl = "$mainUrl/video/showcase"

    override val mainPage = mainPageOf(
        showcaseUrl to "فيديوهات Ok.ru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionUrl = request.data ?: showcaseUrl
        val cards = parseCards(app.get(sectionUrl).text)
        return newHomePageResponse(
            listOf(HomePageList(request.name ?: "فيديوهات Ok.ru", cards)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return search(query, 1)?.list
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return null
        val url = "$mainUrl/video?st.query=${URLEncoder.encode(query, "UTF-8")}"
        val cards = parseCards(app.get(url).text)
        return newSearchResponseList(cards, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = url.trim()
        val html = try {
            app.get(pageUrl).text
        } catch (e: Exception) {
            return null
        }
        val doc = Jsoup.parse(html)
        val movie = parseOkPlayer(html)?.flashvars?.metadata?.movie
        val id = movie?.id?.takeIf { it.isNotBlank() }
            ?: Regex("/video/(\\d+)").find(pageUrl)?.groupValues?.get(1)
            ?: return null
        val title = movie?.title?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "OK Video $id"
        val poster = movie?.poster?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        return newMovieLoadResponse(title, id, TvType.Movie, "$mainUrl/video/$id") {
            this.posterUrl = poster?.let { fixUrl(it) }
            movie?.duration?.let { this.duration = it }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.ifBlank { return false }
        val html = try {
            app.get(pageUrl).text
        } catch (e: Exception) {
            return false
        }
        val player = parseOkPlayer(html) ?: return false
        return emitOkLinks(name, player, mainUrl, subtitleCallback, callback)
    }

    private fun parseCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        val results = mutableListOf<SearchResponse>()
        doc.select(".ugrid_i.js-video-card").forEach { card ->
            runCatching {
                val link = card.selectFirst("a.video-card_lk")
                    ?: return@runCatching
                val href = link.attr("href")
                val id = Regex("/video/(\\d+)").find(href)?.groupValues?.get(1)
                    ?: return@runCatching
                val img = card.selectFirst("img.video-card_img")
                val title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@runCatching
                val poster = img?.attr("src")?.takeIf { it.isNotBlank() }
                results.add(
                    newMovieSearchResponse(title, "$mainUrl/video/$id", TvType.Movie) {
                        this.posterUrl = poster?.let { fixUrl(it) }
                    }
                )
            }
        }
        return results
    }
}