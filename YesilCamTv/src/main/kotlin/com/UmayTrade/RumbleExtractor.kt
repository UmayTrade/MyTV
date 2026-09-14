package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

/**
 * Rumble için özel Extractor.
 * Embed URL'ini alır, Rumble'ın embedJS API'sinden doğrudan mp4/m3u8 linklerini çeker.
 *
 * Örnek giriş: https://rumble.com/embed/v6ylbw8/#?secret=aapeyq6DLH
 * videoId     : v6ylbw8
 */
class RumbleExtractor : ExtractorApi() {
    override var mainUrl = "https://rumble.com"
    override var name = "Rumble"
    override val requiresReferer = false

    private val rumbleHeaders = mapOf(
        "Referer" to "https://rumble.com/",
        "Origin" to "https://rumble.com",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = Regex("""rumble\.com/embed/([a-zA-Z0-9]+)""")
            .find(url)?.groupValues?.get(1)
            ?: Regex("""rumble\.com/([a-zA-Z0-9\-]+)\.html""")
                .find(url)?.groupValues?.get(1)
            ?: return

        // Rumble API birden fazla endpoint barındırır, sırayla dene
        val endpoints = listOf(
            "https://rumble.com/embedJS/u3/?request=video&ver=2&v=$videoId",
            "https://rumble.com/embedJS/VideoPlayback/?request=video&ver=2&v=$videoId",
            "https://rumble.com/-/api/video/$videoId"
        )

        var anyLinkFound = false

        for (endpoint in endpoints) {
            val json = try {
                val body = app.get(
                    endpoint,
                    referer = "https://rumble.com/",
                    headers = rumbleHeaders
                ).text
                if (body.isBlank() || body.length < 20) null
                else JSONObject(body)
            } catch (e: Exception) {
                null
            } ?: continue

            // "u" objesi: en yaygın kalite haritası
            parseQualityMap(json.optJSONObject("u"), subtitleCallback, callback)?.let { anyLinkFound = true }

            // "ua" objesi: alternatif isimlendirme
            parseQualityMap(json.optJSONObject("ua"), subtitleCallback, callback)?.let { anyLinkFound = true }

            // "s" objesi: bazı eski videolarda
            parseQualityMap(json.optJSONObject("s"), subtitleCallback, callback)?.let { anyLinkFound = true }

            // Altyazılar (cc)
            json.optJSONObject("cc")?.let { cc ->
                cc.keys().forEach { lang ->
                    val v = cc.optString(lang)
                    if (v.contains(".vtt") || v.contains(".srt")) {
                        subtitleCallback(
                            newSubtitleFile(
                                lang = lang,
                                url = v
                            )
                        )
                    }
                }
            }

            if (anyLinkFound) break
        }
    }

    private fun parseQualityMap(
        obj: JSONObject?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean? {
        if (obj == null) return null
        var found = false
        obj.keys().forEach { key ->
            val value = obj.optString(key)
            if (value.isBlank()) return@forEach

            val isM3u8 = value.contains(".m3u8")
            val isMp4 = value.contains(".mp4")

            if (isM3u8 || isMp4) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "Rumble ${prettyQuality(key)}",
                        url = value,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://rumble.com/"
                        this.quality = qualityToValue(key)
                        this.headers = rumbleHeaders
                    }
                )
                found = true
            }
        }
        return if (found) true else null
    }

    private fun prettyQuality(key: String): String = when {
        key.contains("1080") -> "1080p"
        key.contains("720") -> "720p"
        key.contains("480") -> "480p"
        key.contains("360") -> "360p"
        key.contains("240") -> "240p"
        else -> key
    }

    private fun qualityToValue(key: String): Int = when {
        key.contains("1080") -> Qualities.P1080.value
        key.contains("720") -> Qualities.P720.value
        key.contains("480") -> Qualities.P480.value
        key.contains("360") -> Qualities.P360.value
        key.contains("240") -> Qualities.P240.value
        else -> Qualities.Unknown.value
    }
}
