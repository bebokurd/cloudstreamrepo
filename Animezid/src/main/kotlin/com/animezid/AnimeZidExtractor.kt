package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class AnimeZidExtractor : ExtractorApi() {
    override val name = "AnimeZid"
    override val mainUrl = "https://animezid.cam"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val playUrl = when {
            url.contains("play.php?vid=", ignoreCase = true) ||
                url.contains("watch.php?vid=", ignoreCase = true) -> url
            url.contains("vid=") -> {
                val vid = url.substringAfter("vid=").substringBefore("&")
                if (vid.isBlank()) url else "$mainUrl/play.php?vid=$vid"
            }
            else -> url
        }

        if (playUrl.contains("vid=")) {
            Animezid().loadLinks(playUrl, false, subtitleCallback, callback)
            return
        }

        if (playUrl.contains("/web-playback/launch/")) {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                "Referer" to "$mainUrl/play.php",
                "Origin" to mainUrl
            )
            val launchRes = app.get(playUrl, headers = headers, allowRedirects = false)
            val location = launchRes.headers["Location"] ?: launchRes.headers["location"]
            val target = when {
                location.isNullOrBlank() -> playUrl
                location.startsWith("http") -> location
                else -> "$mainUrl$location"
            }
            loadExtractor(target, referer ?: mainUrl, subtitleCallback, callback)
        }
    }
}
