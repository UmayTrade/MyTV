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
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
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

        // YÖNTEM 1: Embed sayfasını çek ve inline JSON'u ayıkla
        val embedUrl = "https://rumble.com/embed/$videoId/"
        val html = try {
            app.get(embedUrl, referer = "https://rumble.com/", headers = rumbleHeaders).text
        } catch (e: Exception) {
            println("DEBUG Rumble: embed sayfası hatası -> ${e.message}")
            null
        }

        if (html != null) {
            println("DEBUG Rumble: embed HTML uzunluğu=${html.length}")

            // m.f["VIDEOID"]={...} JSON'unu bul
            // Regex: JSON'un kapanış parantezini bulmak için dengeli parantez tarayıcı
            val marker = """m.f["$videoId"]="""
            val startIdx = html.indexOf(marker)
            if (startIdx >= 0) {
                val jsonStart = startIdx + marker.length
                val jsonStr = extractBalancedJson(html, jsonStart)
                if (jsonStr != null) {
                    println("DEBUG Rumble: JSON bulundu, uzunluk=${jsonStr.length}")
                    try {
                        val json = JSONObject(jsonStr)
                        var found = false

                        // u ve ua objelerinden link çıkar
                        parseNestedQualityMap(json.optJSONObject("u"), callback)?.let { found = true }
                        parseNestedQualityMap(json.optJSONObject("ua"), callback)?.let { found = true }

                        // cc (altyazı) objesi
                        val cc = json.optJSONArray("cc")
                        if (cc != null) {
                            for (i in 0 until cc.length()) {
                                val c = cc.optJSONObject(i) ?: continue
                                val ccUrl = c.optString("url")
                                val ccLang = c.optString("language", "tr")
                                if (ccUrl.isNotBlank()) {
                                    subtitleCallback(newSubtitleFile(lang = ccLang, url = ccUrl))
                                }
                            }
                        }

                        if (found) {
                            println("DEBUG Rumble: link(ler) bulundu")
                            return
                        }
                    } catch (e: Exception) {
                        println("DEBUG Rumble: JSON parse hatası -> ${e.message}")
                    }
                }
            }
        }

        // YÖNTEM 2: embedJS API'sini dene (Cloudflare bazen izin verir)
        val apiUrl = "https://rumble.com/embedJS/u3/?request=video&ver=2&v=$videoId"
        try {
            val body = app.get(apiUrl, referer = "https://rumble.com/", headers = rumbleHeaders).text
            if (body.trimStart().startsWith("{")) {
                val json = JSONObject(body)
                var found = false
                parseNestedQualityMap(json.optJSONObject("u"), callback)?.let { found = true }
                parseNestedQualityMap(json.optJSONObject("ua"), callback)?.let { found = true }
                if (found) {
                    println("DEBUG Rumble: API'den link bulundu")
                    return
                }
            } else {
                println("DEBUG Rumble: API JSON dönmedi (Cloudflare)")
            }
        } catch (e: Exception) {
            println("DEBUG Rumble: API hatası -> ${e.message}")
        }

        println("DEBUG Rumble: hiçbir yöntem işe yaramadı")
    }

    /**
     * Verilen metinde `{` ile başlayan JSON'u dengeli parantez mantığıyla çıkarır.
     * (Rumble embed sayfasında JSON tek satırda, string'lerde kaçış karakterleri var.)
     */
    private fun extractBalancedJson(text: String, startIdx: Int): String? {
        if (startIdx >= text.length || text[startIdx] != '{') return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in startIdx until text.length) {
            val c = text[i]
            if (escape) { escape = false; continue }
            if (inString) {
                when (c) {
                    '\\' -> escape = true
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(startIdx, i + 1)
                    }
                }
            }
        }
        return null
    }

    /**
     * Rumble API'nin "u" ve "ua" objelerini özyinelemeli (recursive) tarar.
     */
    private suspend fun parseNestedQualityMap(
        obj: JSONObject?,
        callback: (ExtractorLink) -> Unit
    ): Boolean? {
        if (obj == null) return null
        var found = false

        // 1. Doğrudan "url" alanı var mı?
        obj.optString("url").takeIf { it.isNotBlank() }?.let { directUrl ->
            if (directUrl.contains(".m3u8")) {
                emitLink(directUrl, "auto", true, callback)
                found = true
            } else if (directUrl.contains(".mp4") && !directUrl.contains("timeline")) {
                emitLink(directUrl, "auto", false, callback)
                found = true
            }
        }

        // 2. İç içe objeleri tara
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)

            if (value is JSONObject) {
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
        else -> Qualities.Unknown.value
    }
}
