package com.UmayTrade

import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
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

        println("RumbleExtractor URL: $url")

        val videoId = extractVideoId(url)

        if (videoId.isNullOrBlank()) {
            println("RumbleExtractor: Video ID bulunamadı")
            return
        }

        println("RumbleExtractor Video ID: $videoId")

        val endpoints = listOf(
            "https://rumble.com/embedJS/u3/?request=video&ver=2&v=$videoId",
            "https://rumble.com/embedJS/VideoPlayback/?request=video&ver=2&v=$videoId",
            "https://rumble.com/-/api/video/$videoId"
        )

        var found = false

        for (endpoint in endpoints) {

            println("RumbleExtractor endpoint: $endpoint")

            val responseText = try {

                app.get(
                    endpoint,
                    referer = "https://rumble.com/",
                    headers = rumbleHeaders
                ).text

            } catch (e: Exception) {

                println(
                    "RumbleExtractor request error: ${e.message}"
                )

                continue
            }

            if (responseText.isBlank()) {
                continue
            }

            try {

                val trimmed = responseText.trim()

                if (
                    trimmed.startsWith("{") ||
                    trimmed.startsWith("[")
                ) {

                    val json: Any =
                        if (trimmed.startsWith("[")) {
                            JSONArray(trimmed)
                        } else {
                            JSONObject(trimmed)
                        }

                    if (
                        parseJson(
                            json,
                            subtitleCallback,
                            callback
                        )
                    ) {
                        found = true
                    }

                } else {

                    if (
                        parseRawText(
                            responseText,
                            subtitleCallback,
                            callback
                        )
                    ) {
                        found = true
                    }
                }

            } catch (e: Exception) {

                println(
                    "RumbleExtractor JSON error: ${e.message}"
                )

                if (
                    parseRawText(
                        responseText,
                        subtitleCallback,
                        callback
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
         * Son fallback:
         * Embed sayfasının kendisini tara.
         */
        if (!found) {

            try {

                val html = app.get(
                    url,
                    referer = referer ?: "https://rumble.com/",
                    headers = rumbleHeaders
                ).text

                parseRawText(
                    html,
                    subtitleCallback,
                    callback
                )

            } catch (e: Exception) {

                println(
                    "RumbleExtractor HTML fallback error: ${e.message}"
                )
            }
        }
    }

    private fun extractVideoId(
        url: String
    ): String? {

        val cleanUrl = url
            .substringBefore("?")
            .substringBefore("#")

        /*
         * Normal:
         *
         * /embed/v5o42le/
         */
        val normalEmbed = Regex(
            """rumble\.com/embed/([a-zA-Z0-9_-]+)/?"""
        ).find(cleanUrl)

        if (normalEmbed != null) {

            val value = normalEmbed
                .groupValues
                .getOrNull(1)

            if (!value.isNullOrBlank()) {

                /*
                 * ucfsd.v5moylt gibi durumda
                 * son parçayı al.
                 */
                return value
                    .substringAfterLast(".")
                    .trim()
            }
        }

        /*
         * Örnek:
         *
         * /embed/ucfsd.v5moylt/
         */
        val dottedEmbed = Regex(
            """rumble\.com/embed/[^/]*\.([a-zA-Z0-9_-]+)/?"""
        ).find(cleanUrl)

        if (dottedEmbed != null) {

            return dottedEmbed
                .groupValues
                .getOrNull(1)
                ?.trim()
        }

        /*
         * Örnek:
         *
         * /v5o42le-video-title.html
         */
        val htmlUrl = Regex(
            """rumble\.com/([a-zA-Z0-9_-]+)-[^/]+\.html"""
        ).find(cleanUrl)

        if (htmlUrl != null) {

            return htmlUrl
                .groupValues
                .getOrNull(1)
                ?.trim()
        }

        /*
         * Basit .html
         */
        val simpleHtml = Regex(
            """rumble\.com/([a-zA-Z0-9_-]+)\.html"""
        ).find(cleanUrl)

        if (simpleHtml != null) {

            return simpleHtml
                .groupValues
                .getOrNull(1)
                ?.trim()
        }

        return null
    }

    private suspend fun parseJson(
        json: Any,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var found = false

        suspend fun scan(
            value: Any?
        ) {

            when (value) {

                is JSONObject -> {

                    val keys = value.keys()

                    while (keys.hasNext()) {

                        val key = keys.next()

                        val child = value.opt(key)

                        if (child is String) {

                            if (
                                addStream(
                                    key,
                                    child,
                                    callback
                                )
                            ) {
                                found = true
                            }

                            if (
                                addSubtitle(
                                    key,
                                    child,
                                    subtitleCallback
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

                    for (index in 0 until value.length()) {

                        scan(
                            value.opt(index)
                        )
                    }
                }
            }
        }

        scan(json)

        return found
    }

    private suspend fun parseRawText(
        text: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var found = false

        /*
         * MP4
         */
        val mp4Regex = Regex(
            """https?://[^"'\\\s<>]+\.mp4(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        for (match in mp4Regex.findAll(text)) {

            val url = cleanUrl(match.value)

            callback(
                newExtractorLink(
                    source = name,
                    name = "Rumble MP4",
                    url = url,
                    type = ExtractorLinkType.VIDEO
                ) {

                    referer = "https://rumble.com/"
                    quality = detectQuality(url)
                    headers = rumbleHeaders
                }
            )

            found = true
        }

        /*
         * M3U8
         */
        val m3u8Regex = Regex(
            """https?://[^"'\\\s<>]+\.m3u8(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        for (match in m3u8Regex.findAll(text)) {

            val url = cleanUrl(match.value)

            callback(
                newExtractorLink(
                    source = name,
                    name = "Rumble HLS",
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {

                    referer = "https://rumble.com/"
                    quality = detectQuality(url)
                    headers = rumbleHeaders
                }
            )

            found = true
        }

        /*
         * VTT / SRT
         */
        val subtitleRegex = Regex(
            """https?://[^"'\\\s<>]+\.(?:vtt|srt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        for (match in subtitleRegex.findAll(text)) {

            val subtitleUrl = cleanUrl(
                match.value
            )

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

    private suspend fun addStream(
        key: String,
        value: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val url = cleanUrl(value)

        if (url.isBlank()) {
            return false
        }

        val isM3u8 = url.contains(
            ".m3u8",
            ignoreCase = true
        )

        val isMp4 = url.contains(
            ".mp4",
            ignoreCase = true
        )

        if (!isM3u8 && !isMp4) {
            return false
        }

        val quality = detectQuality(
            "$key $url"
        )

        callback(
            newExtractorLink(
                source = name,
                name = "Rumble ${qualityName(quality)}",
                url = url,
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
            "Rumble stream found: $key -> $url"
        )

        return true
    }

    private suspend fun addSubtitle(
        key: String,
        value: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {

        val url = cleanUrl(value)

        val isSubtitle =
            url.contains(
                ".vtt",
                ignoreCase = true
            ) ||
                    url.contains(
                        ".srt",
                        ignoreCase = true
                    )

        if (!isSubtitle) {
            return false
        }

        val language = when {

            key.contains(
                "tr",
                ignoreCase = true
            ) -> "tr"

            key.contains(
                "turkish",
                ignoreCase = true
            ) -> "tr"

            key.contains(
                "en",
                ignoreCase = true
            ) -> "en"

            key.contains(
                "english",
                ignoreCase = true
            ) -> "en"

            else -> "und"
        }

        subtitleCallback(
            newSubtitleFile(
                lang = language,
                url = url
            )
        )

        return true
    }

    private fun cleanUrl(
        value: String
    ): String {

        var result = value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .trim()

        result = try {

            URLDecoder.decode(
                result,
                "UTF-8"
            )

        } catch (_: Exception) {

            result
        }

        return result
    }

    private fun detectQuality(
        value: String
    ): Int {

        return when {

            Regex(
                """2160|4k""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(value) ->
                Qualities.P2160.value

            Regex(
                """1440""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(value) ->
                Qualities.P1440.value

            Regex(
                """1080""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(value) ->
                Qualities.P1080.value

            Regex(
                """720""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(value) ->
                Qualities.P720.value

            Regex(
                """480""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(value) ->
                Qualities.P480.value

            Regex(
                """360""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(value) ->
                Qualities.P360.value

            Regex(
                """240""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(value) ->
                Qualities.P240.value

            else ->
                Qualities.Unknown.value
        }
    }

    private fun qualityName(
        quality: Int
    ): String {

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
