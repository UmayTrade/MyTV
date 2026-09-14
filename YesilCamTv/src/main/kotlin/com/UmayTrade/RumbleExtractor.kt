package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class RumbleExtractor : ExtractorApi() {
    override var mainUrl = "https://rumble.com"
    override var name = "Rumble"
    override val requiresReferer = true

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
        println("DEBUG Rumble: getUrl çağrıldı -> $url")

        val videoId = Regex("""rumble\.com/embed/([a-zA-Z0-9]+)""")
            .find(url)?.groupValues?.get(1)
            ?: Regex("""rumble\.com/([a-zA-Z0-9\-]+)\.html""")
                .find(url)?.groupValues?.get(1)
            ?: run {
                println("DEBUG Rumble: videoId çıkarılamadı!")
                return
            }

        println("DEBUG Rumble: videoId=$videoId")

        val endpoints = listOf(
            "https://rumble.com/embedJS/u3/?request=video&ver=2&v=$videoId",
            "https://rumble.com/embedJS/VideoPlayback/?request=video&ver=2&v=$videoId"
        )

        var anyLinkFound = false

        for (endpoint in endpoints) {
            val body = try {
                app.get(endpoint, referer = "https://rumble.com/", headers = rumbleHeaders).text
            } catch (e: Exception) {
                println("DEBUG Rumble: istek hatası -> ${e.message}")
                null
            } ?: continue

            println("DEBUG Rumble: yanıt uzunluğu=${body.length}")
            if (body.isBlank() || body.length < 20) continue

            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                println("DEBUG Rumble: JSON parse hatası -> ${e.message}")
                continue
            }

            // Ana "u" objesi (yeni format)
            parseNestedQualityMap(json.optJSONObject("u"), callback)?.let { anyLinkFound = true }
            // Alternatif "ua" objesi
            parseNestedQualityMap(json.optJSONObject("ua"), callback)?.let { anyLinkFound = true }

            if (anyLinkFound) {
                println("DEBUG Rumble: link bulundu, döngüden çıkılıyor")
                break
            }
        }

        println("DEBUG Rumble: sonuç anyLinkFound=$anyLinkFound")
    }

    /**
     * Rumble API'nin "u" ve "ua" objelerini özyinelemeli (recursive) tarar.
     * İç içe geçmiş yapıdan hls.url / tar.url / mp4 / m3u8 linklerini çıkarır.
     */
    private suspend fun parseNestedQualityMap(
        obj: JSONObject?,
        callback: (ExtractorLink) -> Unit
    ): Boolean? {
        if (obj == null) return null
        var found = false

        // 1. Doğrudan "url" alanı var mı? (tar, hls, audio objeleri)
        obj.optString("url").takeIf { it.isNotBlank() }?.let { directUrl ->
            // HLS ise direkt ekle
            if (directUrl.contains(".m3u8") || directUrl.contains("playlist.m3u8")) {
                emitLink(directUrl, "auto", true, callback)
                found = true
            }
            // .mp4 ise (timeline gibi küçük dosyaları atla)
            else if (directUrl.contains(".mp4") && !directUrl.contains("timeline")) {
                emitLink(directUrl, "auto", false, callback)
                found = true
            }
        }

        // 2. İç içe objeleri tara (tar, ua.tar.360, ua.hls.auto gibi)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)

            if (value is JSONObject) {
                // İç objenin içinde "url" var mı kontrol et
                val innerUrl = value.optString("url")
                if (innerUrl.isNotBlank()) {
                    if (innerUrl.contains(".m3u8")) {
                        emitLink(innerUrl, key, true, callback)
                        found = true
                    } else if (innerUrl.contains(".mp4") && !innerUrl.contains("timeline")) {
                        emitLink(innerUrl, key, false, callback)
                        found = true
                    }
                }
                // Daha derine in
                if (parseNestedQualityMap(value, callback) == true) found = true
            }
        }

        return if (found) true else null
    }

    private suspend fun emitLink(
        url: String,
        qualityKey: String,
        isHls: Boolean,
        callback: (ExtractorLink) -> Unit
    ) {
        println("DEBUG Rumble: kalite=$qualityKey url=$url")
        callback(
            newExtractorLink(
                source = this.name,
                name = "Rumble ${prettyQuality(qualityKey)}",
                url = url,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://rumble.com/"
                this.quality = qualityToValue(qualityKey)
                this.headers = rumbleHeaders
            }
        )
    }

    private fun prettyQuality(key: String): String = when {
        key.contains("1080") -> "1080p"
        key.contains("720") -> "720p"
        key.contains("480") -> "480p"
        key.contains("360") -> "360p"
        key.contains("240") -> "240p"
        key.equals("auto", ignoreCase = true) -> "Otomatik"
        else -> key
    }

    private fun qualityToValue(key: String): Int = when {
        key.contains("1080") -> Qualities.P1080.value
        key.contains("720") -> Qualities.P720.value
        key.contains("480") -> Qualities.P480.value
        key.contains("360") -> Qualities.P360.value
        key.contains("240") -> Qualities.P240.value
        key.equals("auto", ignoreCase = true) -> Qualities.Unknown.value
        else -> Qualities.Unknown.value
    }
}
