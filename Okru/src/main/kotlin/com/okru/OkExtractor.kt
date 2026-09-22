package com.okru

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

class OkExtractor : ExtractorApi() {
    override val name = "Ok.ru"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = url.trim().takeIf { it.isNotBlank() } ?: return
        val html = try {
            app.get(pageUrl, referer = referer ?: mainUrl).text
        } catch (e: Exception) {
            null
        } ?: return
        val player = parseOkPlayer(html) ?: return
        emitOkLinks(name, player, referer ?: mainUrl, subtitleCallback, callback)
    }
}