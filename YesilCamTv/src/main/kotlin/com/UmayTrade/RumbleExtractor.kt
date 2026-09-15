package com.UmayTrade

import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.app
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newSubtitleFile
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

class RumbleExtractor : ExtractorApi() {

    override var mainUrl = "https://rumble.com"
    override var name = "Rumble"
    override val requiresReferer = true

    private val rumbleHeaders = mapOf(
        "Referer" to "https://rumble.com/",
        "Origin" to "https://rumble.com",
        "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = extractVideoId(url) ?: return

        println("DEBUG RumbleExtractor: URL = $url")
        println("DEBUG RumbleExtractor: videoId = $videoId")

        val endpoints = listOf(
            "https://rumble.com/embedJS/u3/?request=video&ver=2&v=$videoId",
            "https://rumble.com/embedJS/VideoPlayback/?request=video&ver=2&v=$videoId",
            "https://rumble.com/-/api/video/$videoId",
            "https://rumble.com/embed/$videoId/"
        )

        var found = false

        for (endpoint in endpoints) {
            println("DEBUG RumbleExtractor: endpoint = $endpoint")

            val responseText = try {
                app.get(
                    endpoint,
                    referer = "https://rumble.com/",
                    headers = rumbleHeaders
                ).text
            } catch (e: Exception) {
                println(
                    "DEBUG RumbleExtractor: request error = ${e.message}"
                )
                continue
            }

            if (responseText.isBlank()) {
                continue
            }

            println(
                "DEBUG RumbleExtractor: response length = ${responseText.length}"
            )

            val trimmed = responseText.trim()

            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    val json = if (trimmed.startsWith("[")) {
                        JSONArray(trimmed)
                    } else {
                        JSONObject(trimmed)
                    }

                    val streamFound = parseJsonForStreams(
                        json = json,
                        callback = callback,
                        subtitleCallback = subtitleCallback
                    )

                    if (streamFound) {
                        found = true
                    }
                } catch (e: Exception) {
                    println(
                        "DEBUG RumbleExtractor: JSON parse error = ${e.message}"
                    )

                    if (parseRawTextForStreams(
                            responseText,
                            callback,
                            subtitleCallback
                        )
                    ) {
                        found = true
                    }
                }
            } else {
                if (parseRawTextForStreams(
                        responseText,
                        callback,
                        subtitleCallback
                    )
                ) {
                    found = true
                }
            }

            if (found) {
                break
            }
        }

        /*
         * Son güvenlik: Rumble embed sayfasının HTML'ini doğrudan
         * tarayalım.
         */
        if (!found) {
            try {
                val html = app.get(
                    url,
                    referer = referer ?: mainUrl,
                    headers = rumbleHeaders
                ).text

                if (parseRawTextForStreams(
                        html,
                        callback,
                        subtitleCallback
                    )
                ) {
                    found = true
                }
            } catch (e: Exception) {
                println(
                    "DEBUG RumbleExtractor: HTML fallback error = ${e.message}"
                )
            }
        }

        println("DEBUG RumbleExtractor: found = $found")
    }

    private fun extractVideoId(url: String): String? {
        val cleanUrl = url.substringBefore("?").substringBefore("#")

        /*
         * Örnek:
         * https://rumble.com/embed/v5o42le/
         */
        Regex(
            """rumble\.com/embed/([a-zA-Z0-9._-]+)"""
        ).find(cleanUrl)?.groupValues?.getOrNull(1)?.let { raw ->
            val id = raw
                .trim('/')
                .substringAfterLast(".")
                .takeIf { it.isNotBlank() }

            if (!id.isNullOrBlank()) {
                return id
            }
        }

        /*
         * Örnek:
         * https://rumble.com/embed/ucfsd.v5moylt/
         *
         * Burada gerçek video ID:
         * v5moylt
         */
        Regex(
            """rumble\.com/embed/[^/]*\.([a-zA-Z0-9_-]+)/?"""
        ).find(cleanUrl)?.groupValues?.getOrNull(1)?.let {
            return it
        }

        /*
         * Örnek:
         * https://rumble.com/v5o42le-film-adi.html
         */
        Regex(
            """rumble\.com/([a-zA-Z0-9_-]+)-[^/]+\.html"""
        ).find(cleanUrl)?.groupValues?.getOrNull(1)?.let {
            return it
        }

        /*
         * Daha genel .html desteği
         */
        Regex(
            """rumble\.com/([a-zA-Z0-9_-]+)\.html"""
        ).find(cleanUrl)?.groupValues?.getOrNull(1)?.let {
            return it
        }

        return null
    }

    private suspend fun parseJsonForStreams(
        json: Any,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {

        var found = false

        suspend fun scan(value: Any?) {
            when (value) {

                is JSONObject -> {
                    val keys = value.keys()

                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)

                        if (child is String) {
                            if (emitStream(
                                    key = key,
                                    value = child,
                                    callback = callback
                                )
                            ) {
                                found = true
                            }

                            if (emitSubtitle(
                                    key = key,
                                    value = child,
                                    subtitleCallback = subtitleCallback
                                )
                            ) {
                                found = true
                            }
                        } else {
                            scan(child)
                        }
                    }
                }

                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        scan(value.opt(i))
                    }
                }
            }
        }

        scan(json)

        return found
    }

    private suspend fun parseRawTextForStreams(
        text: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {

        var found = false

        /*
         * MP4 URL'leri
         */
        val mp4Regex = Regex(
            """https?://[^"'\\\s<>]+\.mp4(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        for (match in mp4Regex.findAll(text)) {
            val url = decodeUrl(match.value)

            callback(
                newExtractorLink(
                    source = this.name,
                    name = "Rumble MP4",
                    url = url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    referer = "https://rumble.com/"
                    quality = guessQuality(url)
                    headers = rumbleHeaders
                }
            )

            found = true
        }

        /*
         * M3U8 URL'leri
         */
        val m3u8Regex = Regex(
            """https?://[^"'\\\s<>]+\.m3u8(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        for (match in m3u8Regex.findAll(text)) {
            val url = decodeUrl(match.value)

            callback(
                newExtractorLink(
                    source = this.name,
                    name = "Rumble HLS",
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = "https://rumble.com/"
                    quality = guessQuality(url)
                    headers = rumbleHeaders
                }
            )

            found = true
        }

        /*
         * Altyazı
         */
        val subtitleRegex = Regex(
            """https?://[^"'\\\s<>]+\.(?:vtt|srt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        for (match in subtitleRegex.findAll(text)) {
            val subtitleUrl = decodeUrl(match.value)

            subtitleCallback(
                newSubtitleFile(
                    lang = "tr",
                    url = subtitleUrl
                )
            )

            found = true
        }

        return found
    }

    private suspend fun emitStream(
        key: String,
        value: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val cleaned = decodeUrl(
            value
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .trim()
        )

        if (cleaned.isBlank()) {
            return false
        }

        val isM3u8 = cleaned.contains(
            ".m3u8",
            ignoreCase = true
        )

        val isMp4 = cleaned.contains(
            ".mp4",
            ignoreCase = true
        )

        if (!isM3u8 && !isMp4) {
            return false
        }

        val quality = guessQuality(
            key = key,
            url = cleaned
        )

        callback(
            newExtractorLink(
                source = this.name,
                name = "Rumble ${qualityName(quality)}",
                url = cleaned,
                type = if (isM3u8) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                referer = "https://rumble.com/"
                this.quality = quality
                headers = rumbleHeaders
            }
        )

        println(
            "DEBUG RumbleExtractor: stream -> $key -> $cleaned"
        )

        return true
    }

    private suspend fun emitSubtitle(
        key: String,
        value: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {

        val cleaned = decodeUrl(
            value
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .trim()
        )

        val isSubtitle = cleaned.contains(
            ".vtt",
            ignoreCase = true
        ) || cleaned.contains(
            ".srt",
            ignoreCase = true
        )

        if (!isSubtitle) {
            return false
        }

        val language = when {
            key.contains("tr", ignoreCase = true) -> "tr"
            key.contains("turkish", ignoreCase = true) -> "tr"
            key.contains("en", ignoreCase = true) -> "en"
            key.contains("english", ignoreCase = true) -> "en"
            else -> key.ifBlank { "und" }
        }

        subtitleCallback(
            newSubtitleFile(
                lang = language,
                url = cleaned
            )
        )

        return true
    }

    private fun decodeUrl(value: String): String {
        return try {
            URLDecoder.decode(
                value
                    .replace("\\/", "/")
                    .replace("\\u0026", "&"),
                "UTF-8"
            )
        } catch (_: Exception) {
            value
        }
    }

    private fun guessQuality(
        key: String = "",
        url: String = ""
    ): Int {

        val text = "$key $url"

        return when {
            Regex("""2160|4k""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text) ->
                Qualities.P2160.value

            Regex("""1440""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text) ->
                Qualities.P1440.value

            Regex("""1080""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text) ->
                Qualities.P1080.value

            Regex("""720""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text) ->
                Qualities.P720.value

            Regex("""480""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text) ->
                Qualities.P480.value

            Regex("""360""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text) ->
                Qualities.P360.value

            Regex("""240""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text) ->
                Qualities.P240.value

            else ->
                Qualities.Unknown.value
        }
    }

    private fun qualityName(quality: Int): String {
        return when (quality) {
            Qualities.P2160.value -> "2160p"
            Qualities.P1440.value -> "1440p"
            Qualities.P1080.value -> "1080p"
            Qualities.P720.value -> "720p"
            Qualities.P480.value -> "480p"
            Qualities.P360.value -> "360p"
            Qualities.P240.value -> "240p"
            else -> "Auto"
        }
    }
}
