package com.animezid

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal fun encodeUri(url: String): String {
    return try {
        url.toCharArray().joinToString("") { char ->
            if (char.code <= 127) char.toString() else URLEncoder.encode(char.toString(), "UTF-8")
        }
    } catch (e: Exception) {
        ""
    }
}

internal object SmartPlayer {
    private const val KEY_STRING = "kiemtienmua911ca"

    private fun String.decodeHex(): ByteArray {
        val cleanHex = this.trim().replace("\"", "").let { if (it.length % 2 != 0) it.dropLast(1) else it }
        return cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun generateIvCandidates(domain: String, videoId: String): List<ByteArray> {
        val candidates = mutableListOf<ByteArray>()

        val dOpts = mutableListOf(48, 323)
        if (domain.isNotEmpty()) {
            dOpts.add(domain.length * (domain.length + 2))
            val parts = domain.split(".")
            if (parts.size >= 2) {
                val shortDomain = "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
                dOpts.add(shortDomain.length * (shortDomain.length + 2))
            }
        }

        val wOpts = mutableListOf(0, 105, 141, 189, 63)
        if (videoId.isNotEmpty()) {
            wOpts.add(3 * videoId.first().code)
        }

        for (d in dOpts.distinct()) {
            for (w in wOpts.distinct()) {
                val part1 = (1..9).map { (it + d).toChar() }.joinToString("")
                val part2 = intArrayOf(d, 111, w, 128, 132, 97, 95).map { it.toChar() }.joinToString("")
                val ivString = (part1 + part2)
                candidates.add(ivString.toByteArray(Charsets.UTF_8).copyOfRange(0, 16))
            }
        }
        return candidates
    }

    private fun smartDecrypt(encryptedHex: String, domain: String, videoId: String): String? {
        val encryptedBytes = try {
            encryptedHex.decodeHex()
        } catch (e: Exception) {
            return null
        }
        val secretKey = SecretKeySpec(KEY_STRING.toByteArray(Charsets.UTF_8), "AES")

        val ivCandidates = generateIvCandidates(domain, videoId)
        for (iv in ivCandidates) {
            try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
                val decryptedBytes = cipher.doFinal(encryptedBytes)
                val decryptedText = String(decryptedBytes, Charsets.UTF_8)

                if (decryptedText.trim().startsWith("{")) {
                    val data = tryParseJson<StrpResponse>(decryptedText)
                    val source = data?.source
                    if (!source.isNullOrBlank()) {
                        return if (source.contains("://") && !source.startsWith("http")) {
                            "https" + source.substring(source.indexOf("://"))
                        } else {
                            source
                        }
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }

        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(ByteArray(16)))
            val decryptedPadded = cipher.doFinal(encryptedBytes)

            if (decryptedPadded.size > 16) {
                val validText = String(decryptedPadded.copyOfRange(16, decryptedPadded.size), Charsets.UTF_8)
                val match = Regex("""([a-zA-Z0-9.-]+\.[a-zA-Z]{2,10}/[^\s",\\]+\.m3u8)""").find(validText)
                    ?: Regex("""([a-zA-Z0-9.-]+\.[a-zA-Z]{2,10}/[^\s",\\]+)""").find(validText)

                if (match != null) {
                    return "https://" + match.groupValues[1]
                }
            }
        } catch (e: Exception) {
        }

        return null
    }

    suspend fun extract(
        playerUrl: String,
        referer: String,
        qualityInt: Int,
        displayName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val maxRetries = 3
        val safeReferer = encodeUri(referer)

        try {
            val uri = URI(if (playerUrl.startsWith("//")) "https:$playerUrl" else playerUrl)
            val domain = uri.host ?: return

            val videoId = when {
                playerUrl.contains("#") -> playerUrl.substringAfterLast("#").substringBefore("&")
                playerUrl.contains("id=") -> playerUrl.substringAfter("id=").substringBefore("&")
                else -> return
            }

            val apiUrl = "https://$domain/api/v1/video?id=$videoId"

            val headers = mapOf(
                "Referer" to safeReferer,
                "Origin" to "https://$domain",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                "Accept" to "application/json, text/plain, */*"
            )

            for (attempt in 1..maxRetries) {
                val res = app.get(apiUrl, headers = headers)

                if (res.isSuccessful && res.text.isNotBlank()) {
                    val rawM3u8 = smartDecrypt(res.text, domain, videoId)

                    if (!rawM3u8.isNullOrBlank()) {
                        val masterM3u8 = sanitizeUrl(rawM3u8)
                        val finalM3u8 = getFinalM3u8(masterM3u8, safeReferer)

                        callback.invoke(
                            newExtractorLink(
                                source = "Share VIP",
                                name = displayName,
                                url = finalM3u8 ?: masterM3u8,
                            ) {
                                this.referer = safeReferer
                                this.quality = qualityInt
                            }
                        )
                        return
                    }
                }
                delay(1000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun getFinalM3u8(masterUrl: String, referer: String): String? {
        val safeReferer = encodeUri(referer)
        return try {
            val playlistContent = app.get(masterUrl, headers = mapOf("Referer" to safeReferer)).text
            val qualityLine = playlistContent.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }

            when {
                qualityLine != null && !qualityLine.contains("://") -> {
                    val basePath = masterUrl.substringBeforeLast("/")
                    "$basePath/$qualityLine"
                }
                qualityLine != null -> qualityLine
                else -> masterUrl
            }
        } catch (e: Exception) {
            masterUrl
        }
    }

    private fun sanitizeUrl(rawLink: String): String {
        val urlRegex = Regex("""([a-zA-Z0-9.-]+\.[a-zA-Z]{2,10}/.*)""")
        val match = urlRegex.find(rawLink)
        return if (match != null) "https://${match.value}" else rawLink
    }

    data class StrpResponse(
        @JsonProperty("source") val source: String?,
        @JsonProperty("cf") val cf: String?
    )
}
