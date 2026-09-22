package com.okru

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

class OkPlayerData(
    @JsonProperty("flashvars") val flashvars: OkFlashVars?
)

class OkFlashVars(
    @JsonProperty("metadata") val metadata: OkMetadata?
)

class OkMetadata(
    @JsonProperty("movie") val movie: OkMovie?,
    @JsonProperty("hlsManifestUrl") val hlsManifestUrl: String?,
    @JsonProperty("videos") val videos: List<OkVideo>?,
    @JsonProperty("failoverHosts") val failoverHosts: List<String>?
)

class OkMovie(
    @JsonProperty("id") val id: String?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("duration") val duration: Int?,
    @JsonProperty("isLive") val isLive: Boolean?,
    @JsonProperty("subtitleTracks") val subtitleTracks: List<OkSubtitleTrack>?
)

class OkVideo(
    @JsonProperty("name") val name: String?,
    @JsonProperty("url") val url: String?
)

class OkSubtitleTrack(
    @JsonProperty("url") val url: String?,
    @JsonProperty("language") val language: String?,
    @JsonProperty("title") val title: String?
)

fun parseOkPlayer(html: String): OkPlayerData? {
    val doc = Jsoup.parse(html)
    return doc.select("[data-options]").firstNotNullOfOrNull { el ->
        AppUtils.tryParseJson<OkPlayerData>(el.attr("data-options"))
            ?.takeIf { it.flashvars?.metadata != null }
    }
}

fun okQualityLabel(name: String?): String = when (name?.trim()?.lowercase()) {
    "full" -> "1080p"
    "hd" -> "720p"
    "sd" -> "480p"
    "low" -> "360p"
    "lowest" -> "240p"
    "mobile" -> "Mobile"
    else -> name?.trim()?.takeIf { it.isNotBlank() } ?: "Video"
}

fun emitOkLinks(
    source: String,
    player: OkPlayerData,
    referer: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val metadata = player.flashvars?.metadata ?: return false
    var found = false

    metadata.hlsManifestUrl?.takeIf { it.isNotBlank() }?.let {
        found = true
        callback.invoke(
            newExtractorLink(
                source = source,
                name = "OK HLS",
                url = it,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
            }
        )
    }

    metadata.videos?.forEach { video ->
        val videoUrl = video.url?.takeIf { it.isNotBlank() } ?: return@forEach
        found = true
        val label = okQualityLabel(video.name)
        callback.invoke(
            newExtractorLink(
                source = source,
                name = "OK $label",
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(label)
            }
        )
    }

    metadata.movie?.subtitleTracks?.forEach { sub ->
        val subUrl = sub.url?.takeIf { it.isNotBlank() } ?: return@forEach
        val fixed = if (subUrl.startsWith("//")) "https:$subUrl" else subUrl
        subtitleCallback(SubtitleFile(sub.language ?: sub.title ?: "", fixed))
    }

    return found
}