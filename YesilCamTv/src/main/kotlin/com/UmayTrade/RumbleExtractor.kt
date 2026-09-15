package com.UmayTrade

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
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
            "Chrome/140.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/json,text/plain,*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalized = try {
            URLDecoder.decode(url.trim(), "UTF-8")
        } catch (_: Exception) {
            url.trim()
        }

        val videoId = extractVideoId(normalized) ?: return

        val seen = HashSet<String>()
        var found = false

        val endpoints = listOf(
            "https://rumble.com/embedJS/u3/?request=video&ver=2&v=$videoId",
            "https://rumble.com/embedJS/VideoPlayback/?request=video&ver=2&v=$videoId",
            "https://rumble.com/-/api/video/$videoId",
            "https://rumble.com/embed/$videoId/"
        )

        for (endpoint in endpoints) {

            val body = try {
                app.get(
                    endpoint,
                    referer = referer ?: "https://rumble.com/",
                    headers = rumbleHeaders
                ).text
            } catch (_: Exception) {
                continue
            }

            if (body.isBlank()) continue

            if (body.trimStart().startsWith("{")) {
                try {
                    val json = JSONObject(body)

                    if (
                        parseJsonForStreams(
                            json = json,
                            seen = seen,
                            callback = callback
                        )
                    ) {
                        found = true
                    }

                    parseJsonSubtitles(
                        obj = json,
                        subtitleCallback = subtitleCallback
                    )

                } catch (_: Exception) {
                    // JSON parse başarısızsa raw HTML/JS taramasına devam edilir.
                }
            }

            if (
                parseRawTextForStreams(
                    text = body,
                    seen = seen,
                    callback = callback
                )
            ) {
                found = true
            }
        }

        if (!found) {

            val embedUrls = listOf(
                "https://rumble.com/embed/$videoId/",
                "https://rumble.com/embed/$videoId"
            )

            for (embedUrl in embedUrls) {

                val html = try {
                    app.get(
                        embedUrl,
                        referer = referer ?: "https://rumble.com/",
                        headers = rumbleHeaders
                    ).text
                } catch (_: Exception) {
                    continue
                }

                if (html.isBlank()) continue

                if (
                    parseRawTextForStreams(
                        text = html,
                        seen = seen,
                        callback = callback
                    )
                ) {
                    found = true
                }

                parseHtmlSubtitles(
                    html = html,
                    subtitleCallback = subtitleCallback
                )

                if (found) break
            }
        }
    }

    /**
     * Desteklenen Rumble URL formatları:
     *
     * https://rumble.com/embed/v5o42le/
     * https://rumble.com/embed/ucfsd.v5moylt/
     * https://rumble.com/v5o42le-title.html
     * https://rumble.com/embed/v5o42le
     */
    private fun extractVideoId(url: String): String? {

        val embed = Regex(
            """rumble\.com/embed/([^/?#]+)""",
            RegexOption.IGNORE_CASE
        )
            .find(url)
            ?.groupValues
            ?.getOrNull(1)

        if (!embed.isNullOrBlank()) {

            val clean = embed
                .substringBefore("?")
                .substringBefore("#")
                .trim('/')

            /*
             * Yeni Rumble formatı:
             *
             * /embed/ucfsd.v5moylt/
             *
             * Gerçek video ID:
             *
             * v5moylt
             */
            if (clean.contains(".")) {

                val last = clean
                    .substringAfterLast(".")
                    .trim()

                if (
                    last.matches(
                        Regex("""[a-zA-Z0-9_-]+""")
                    )
                ) {
                    return last
                }
            }

            return clean.takeIf {
                it.matches(
                    Regex("""[a-zA-Z0-9_-]+""")
                )
            }
        }

        /*
         * Örnek:
         *
         * https://rumble.com/v5o42le-film-adi.html
         */
        val page = Regex(
            """rumble\.com/(v?[a-zA-Z0-9_-]+)(?:-[^/?#]*)?\.html""",
            RegexOption.IGNORE_CASE
        )
            .find(url)
            ?.groupValues
            ?.getOrNull(1)

        return page
            ?.removePrefix("v")
            ?.takeIf {
                it.matches(
                    Regex("""[a-zA-Z0-9_-]+""")
                )
            }
    }

    private suspend fun parseJsonForStreams(
        obj: JSONObject,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var found = false

        val keys = obj.keys()

        while (keys.hasNext()) {

            val key = keys.next()
            val value = obj.opt(key)

            when (value) {

                is JSONObject -> {

                    if (
                        parseJsonForStreams(
                            obj = value,
                            seen = seen,
                            callback = callback
                        )
                    ) {
                        found = true
                    }
                }

                is JSONArray -> {

                    for (i in 0 until value.length()) {

                        val item = value.opt(i)

                        when (item) {

                            is JSONObject -> {
                                if (
                                    parseJsonForStreams(
                                        obj = item,
                                        seen = seen,
                                        callback = callback
                                    )
                                ) {
                                    found = true
                                }
                            }

                            is String -> {
                                if (
                                    emitStream(
                                        rawValue = item,
                                        key = key,
                                        seen = seen,
                                        callback = callback
                                    )
                                ) {
                                    found = true
                                }
                            }
                        }
                    }
                }

                is String -> {

                    if (
                        emitStream(
                            rawValue = value,
                            key = key,
                            seen = seen,
                            callback = callback
                        )
                    ) {
                        found = true
                    }
                }
            }
        }

        return found
    }

    private suspend fun parseRawTextForStreams(
        text: String,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var found = false

        val urlRegex = Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        urlRegex.findAll(text).forEach { match ->

            val raw = match.value
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")

            if (
                emitStream(
                    rawValue = raw,
                    key = "auto",
                    seen = seen,
                    callback = callback
                )
            ) {
                found = true
            }
        }

        return found
    }

    private suspend fun emitStream(
        rawValue: String,
        key: String,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val value = rawValue
            .trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim('"', '\'')

        if (!value.startsWith("http", ignoreCase = true)) {
            return false
        }

        if (
            !value.contains(".m3u8", ignoreCase = true) &&
            !value.contains(".mp4", ignoreCase = true)
        ) {
            return false
        }

        if (!seen.add(value)) {
            return false
        }

        val isM3u8 = value.contains(
            ".m3u8",
            ignoreCase = true
        )

        val quality = qualityFromText(
            "$key $value"
        )

        callback(
            newExtractorLink(
                source = this.name,
                name = "Rumble ${quality.first}",
                url = value,
                type = if (isM3u8) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                referer = "https://rumble.com/"
                this.quality = quality.second
                headers = rumbleHeaders
            }
        )

        return true
    }

    private fun qualityFromText(
        text: String
    ): Pair<String, Int> {

        val normalized = text.lowercase()

        return when {

            normalized.contains("2160") ||
                normalized.contains("4k") -> {
                "2160p" to Qualities.P2160.value
            }

            normalized.contains("1440") -> {
                "1440p" to Qualities.P1440.value
            }

            normalized.contains("1080") -> {
                "1080p" to Qualities.P1080.value
            }

            normalized.contains("720") -> {
                "720p" to Qualities.P720.value
            }

            normalized.contains("480") -> {
                "480p" to Qualities.P480.value
            }

            normalized.contains("360") -> {
                "360p" to Qualities.P360.value
            }

            normalized.contains("240") -> {
                "240p" to Qualities.P240.value
            }

            else -> {
                "Auto" to Qualities.Unknown.value
            }
        }
    }

    private suspend fun parseJsonSubtitles(
        obj: JSONObject,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {

        val seen = HashSet<String>()

        val regex = Regex(
            """https?://[^"'\\\s<>]+?\.(?:vtt|srt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        suspend fun scan(value: Any?) {

            when (value) {

                is JSONObject -> {

                    val keys = value.keys()

                    while (keys.hasNext()) {
                        scan(
                            value.opt(
                                keys.next()
                            )
                        )
                    }
                }

                is JSONArray -> {

                    for (i in 0 until value.length()) {
                        scan(value.opt(i))
                    }
                }

                is String -> {

                    regex.findAll(value).forEach {

                        val subtitleUrl = it.value
                            .replace("\\/", "/")
                            .replace("\\u0026", "&")

                        if (seen.add(subtitleUrl)) {

                            subtitleCallback(
                                newSubtitleFile(
                                    lang = "Türkçe",
                                    url = subtitleUrl
                                )
                            )
                        }
                    }
                }
            }
        }

        scan(obj)
    }

    private suspend fun parseHtmlSubtitles(
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {

        val seen = HashSet<String>()

        val regex = Regex(
            """https?://[^"'\\\s<>]+?\.(?:vtt|srt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        regex.findAll(html).forEach {

            val subtitleUrl = it.value
                .replace("\\/", "/")
                .replace("\\u0026", "&")

            if (seen.add(subtitleUrl)) {

                subtitleCallback(
                    newSubtitleFile(
                        lang = "Türkçe",
                        url = subtitleUrl
                    )
                )
            }
        }
    }
}
