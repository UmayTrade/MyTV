package com.UmayTrade

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder

class MahsunSports : MainAPI() {
    override var mainUrl = "https://mahsunsports80.xyz/"
    override var name = "MahsunSports"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "${mainUrl}" to "📺 Tüm Kanallar",
        "${mainUrl}?category=bein" to "beIN Sports",
        "${mainUrl}?category=ssport" to "S Sport",
        "${mainUrl}?category=tivibu" to "Tivibu",
        "${mainUrl}?category=tabii" to "tabii",
        "${mainUrl}?category=exxen" to "Exxen",
        "${mainUrl}?category=ulusal" to "Ulusal",
        "${mainUrl}?category=yabancı" to "Yabancı Spor"
    )

    companion object {
        private const val TAG = "MahsunSports"
        private val LOG = false

        private fun log(message: String) {
            if (LOG) Log.d(TAG, message)
        }

        private fun key(s: String) = Regex("[^a-z0-9]").replace(
            java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD),
            ""
        )

        fun category(title: String): String {
            val n = key(title)
            return when {
                title.contains('[') -> "Yabancı Spor"
                n.startsWith("bein") -> "beIN Sports"
                n.startsWith("splus") || n.startsWith("ssportplus") || title.startsWith("S+") -> "S Plus"
                n.startsWith("ssport") -> "S Sport"
                n.startsWith("tivibu") -> "Tivibu"
                n.startsWith("tabii") -> "tabii"
                n.startsWith("exxen") -> "Exxen"
                n.startsWith("sporsmart") || n.startsWith("smartspor") -> "Spor Smart"
                Regex("^(trt|aspor|htspor|tv8|atv|a2|tv100|ekol|sports?tv|fb|gs)").containsMatchIn(n) -> "Ulusal"
                else -> "Diğer Spor"
            }
        }

        fun getPosterUrl(id: String): String? {
            return when {
                id.startsWith("bs") -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/international/beinsports/old/horizontal/bein-sports-${id.substring(2)}-hz-int.png"
                id == "ss1" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/s-sport-tr.png"
                id == "ss2" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/s-sport-2-tr.png"
                id == "ssplus1" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/s-sport-plus-tr.png"
                id == "sm1" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/spor-smart-hd-tr.png"
                id == "sm2" -> "https://i.imgur.com/qyUKCUa.png"
                id == "trt1" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-1-tr.png"
                id == "trts" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-spor-tr.png"
                id == "trtsy" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-spor-yildiz-tr.png"
                id == "as" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/a-spor-tr.png"
                id == "atv" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/atv-tr.png"
                id == "a2" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/a2-tr.png"
                id == "tjk" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tjk-tv-tr.png"
                id == "ht" -> "https://www.htspor.com/images/manifest/social-share-logo.png"
                id == "tv8" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tv8-tr.png"
                id == "tv85" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tv85-tr.png"
                id == "sptstv" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/sports-tv-tr.png"
                id == "es1" -> "https://i.imgur.com/olQJgm7.png"
                id == "es2" -> "https://i.imgur.com/f56dHgR.png"
                id == "idm" -> "https://i.imgur.com/fM9FOrZ.png"
                id == "cbcs" -> "https://i.imgur.com/3mEdjuq.png"
                id == "nba" -> "https://upload.wikimedia.org/wikipedia/en/thumb/d/d2/NBA_TV.svg/960px-NBA_TV.svg.png"
                id == "fb" -> "https://i.imgur.com/qBVqtYd.png"
                id == "gs" -> "https://i.imgur.com/fC3KuwT.jpg"
                id.startsWith("tb") -> "https://cdn.prod.website-files.com/658da28123ee3a39812a40fd/65b199f7f21447f8e9e76a47_tabii-wc.png"
                id.startsWith("exn") -> "https://upload.wikimedia.org/wikipedia/commons/d/db/Exxen.png"
                id.startsWith("ts") -> {
                    val index = if (id.length > 2) id.substring(2).toIntOrNull() ?: 0 else 0
                    val images = listOf("qvrKQY3", "qvrKQY3", "fZMSjNE", "xLrgt2O", "LgGxe7z")
                    if (index < images.size) "https://i.imgur.com/${images[index]}.png" else null
                }
                else -> null
            }
        }
    }

    private val parser = SportsParser()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            log("getMainPage: ${request.data}")

            val category = when {
                request.data.contains("?category=") -> request.data.substringAfter("?category=")
                else -> null
            }

            val document = withContext(Dispatchers.IO) {
                Jsoup.connect(mainUrl)
                    .userAgent(USER_AGENT)
                    .ignoreContentType(true)
                    .timeout(30000)
                    .get()
            }

            val channels = parser.channels(document, mainUrl)
            log("Found ${channels.size} channels total")

            if (channels.isEmpty()) {
                return errorResponse("Kanal bulunamadı", request.data)
            }

            val filteredChannels = if (category != null) {
                channels.filter { category(it.title) == category || it.category == category }
            } else {
                channels
            }

            if (filteredChannels.isEmpty()) {
                return errorResponse("Bu kategoride kanal bulunamadı", request.data)
            }

            val grouped = filteredChannels.groupBy { category(it.title) }

            val order = listOf(
                "beIN Sports", "S Sport", "S Plus", "Tivibu", "tabii",
                "Exxen", "Spor Smart", "Ulusal", "Yabancı Spor", "Diğer Spor"
            )

            val homePageList = order.mapNotNull { groupName ->
                val items = grouped[groupName]
                if (items.isNullOrEmpty()) null
                else HomePageList(
                    groupName,
                    items.map {
                        newLiveSearchResponse(
                            it.title,
                            parser.buildChannelUrl(it.id, it.title),
                            TvType.Live
                        ) {
                            posterUrl = getPosterUrl(it.id) ?: ""
                        }
                    }
                )
            }

            if (homePageList.isEmpty()) {
                return newHomePageResponse(
                    listOf(
                        HomePageList(
                            "Tüm Kanallar",
                            filteredChannels.map {
                                newLiveSearchResponse(
                                    it.title,
                                    parser.buildChannelUrl(it.id, it.title),
                                    TvType.Live
                                ) {
                                    posterUrl = getPosterUrl(it.id) ?: ""
                                }
                            }
                        )
                    )
                )
            }

            return newHomePageResponse(homePageList)
        } catch (e: Exception) {
            log("getMainPage error: ${e.message}")
            e.printStackTrace()
            return errorResponse("Hata: ${e.message}", request.data)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val document = withContext(Dispatchers.IO) {
                Jsoup.connect(mainUrl)
                    .userAgent(USER_AGENT)
                    .ignoreContentType(true)
                    .timeout(30000)
                    .get()
            }

            val channels = parser.channels(document, mainUrl)

            return channels.filter {
                it.title.contains(query, ignoreCase = true)
            }.map {
                newLiveSearchResponse(
                    it.title,
                    parser.buildChannelUrl(it.id, it.title),
                    TvType.Live
                ) {
                    posterUrl = getPosterUrl(it.id) ?: ""
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val channel = parser.parseChannelUrl(url) ?: return null

            return newLiveStreamLoadResponse(
                channel.title,
                url = url,
                url
            ) {
                posterUrl = getPosterUrl(channel.id) ?: ""
                plot = "📺 ${channel.title} canlı yayını\n🔗 Kaynak: MahsunSports\n\n💡 Yayın açılmazsa:\n• WARP (1.1.1.1) kullanın\n• Tarayıcıdan dene"
            }
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val channel = parser.parseChannelUrl(data) ?: return false

            val document = withContext(Dispatchers.IO) {
                Jsoup.connect(mainUrl)
                    .userAgent(USER_AGENT)
                    .ignoreContentType(true)
                    .timeout(30000)
                    .get()
            }

            val streamUrls = parser.getStreamUrls(document, channel.player)
            log("Stream URLs found: ${streamUrls.size}")

            if (streamUrls.isEmpty()) {
                return tryAlternativeUrls(channel.id, callback)
            }

            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl,
                "Connection" to "keep-alive"
            )

            var found = false
            for (url in streamUrls) {
                try {
                    val masterContent = withContext(Dispatchers.IO) {
                        app.get(url, headers = headers, timeout = 15).text
                    }

                    val variants = parseHlsVariants(masterContent, url)

                    for (variant in variants) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "${variant.height}p",
                                url = variant.url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = mainUrl
                                this.quality = variant.height
                                this.headers = headers
                            }
                        )
                        found = true
                    }
                } catch (e: Exception) {
                    log("Stream error for $url: ${e.message}")
                    continue
                }
            }

            return found
        } catch (e: Exception) {
            log("loadLinks error: ${e.message}")
            return false
        }
    }

    private suspend fun tryAlternativeUrls(channelId: String, callback: (ExtractorLink) -> Unit): Boolean {
        val altUrls = listOf(
            "https://mahsunsports.com/hls/$channelId.m3u8",
            "https://mahsunsports.com/stream/$channelId.m3u8",
            "https://mahsunsports80.xyz/hls/$channelId.m3u8",
            "https://mahsunsports80.xyz/stream/$channelId.m3u8"
        )

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to mainUrl,
            "Connection" to "keep-alive"
        )

        for (url in altUrls) {
            try {
                val response = withContext(Dispatchers.IO) {
                    app.get(url, headers = headers, timeout = 10)
                }
                if (response.isSuccessful) {
                    val content = response.text
                    val variants = parseHlsVariants(content, url)
                    for (variant in variants) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "${variant.height}p",
                                url = variant.url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = mainUrl
                                this.quality = variant.height
                                this.headers = headers
                            }
                        )
                    }
                    return true
                }
            } catch (e: Exception) {
                continue
            }
        }
        return false
    }

    private fun parseHlsVariants(text: String, baseUrl: String): List<HlsVariant> {
        val variants = mutableListOf<HlsVariant>()
        var pending = false
        var bandwidth = 0L
        var height = 0

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-STREAM-INF:") -> {
                    val resolution = Regex("RESOLUTION=([0-9]+)x([0-9]+)", RegexOption.IGNORE_CASE)
                        .find(trimmed)
                    height = resolution?.groupValues?.get(2)?.toIntOrNull() ?: 0

                    val bandwidthMatch = Regex("(?:^|,)BANDWIDTH=([0-9]+)")
                        .find(trimmed.substringAfter(':'))
                    bandwidth = bandwidthMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    pending = true
                }
                pending && trimmed.isNotBlank() && !trimmed.startsWith('#') -> {
                    val resolved = runCatching { URI(baseUrl).resolve(trimmed) }.getOrNull()
                    if (resolved != null && resolved.scheme in listOf("http", "https")) {
                        variants.add(HlsVariant(resolved.toString(), height, bandwidth))
                    }
                    pending = false
                }
            }
        }

        if (variants.isEmpty()) {
            val hint = when {
                Regex("(?i)\b(4k|uhd|2160p?)\b").containsMatchIn(text) -> 2160
                Regex("(?i)\b(1920|1080p?|fhd|full\s*hd)\b").containsMatchIn(text) -> 1080
                Regex("(?i)\b(1280|720p?|hd)\b").containsMatchIn(text) -> 720
                Regex("(?i)\b(576p?|sd)\b").containsMatchIn(text) -> 576
                else -> 480
            }
            return listOf(HlsVariant(baseUrl, hint, 0L))
        }

        return variants.distinctBy { it.url }
            .sortedWith(compareByDescending<HlsVariant> { it.height }
            .thenByDescending { it.bandwidth })
    }

    private fun errorResponse(message: String, request: String): HomePageResponse {
        return newHomePageResponse(
            listOf(
                HomePageList(
                    "⚠️ Hata",
                    listOf(
                        newLiveSearchResponse(
                            "❌ $message",
                            request,
                            TvType.Live
                        ) {
                            posterUrl = ""
                        }
                    )
                )
            )
        )
    }

    data class HlsVariant(val url: String, val height: Int, val bandwidth: Long = 0L)

    data class SportsChannel(
        val id: String,
        val title: String,
        val category: String,
        val player: String,
        val time: String = ""
    )

    inner class SportsParser {
        fun buildChannelUrl(id: String, title: String): String {
            return "https://mahsunsports.com/turkspor?id=${java.net.URLEncoder.encode(id, "UTF-8")}&title=${java.net.URLEncoder.encode(title, "UTF-8")}"
        }

        fun parseChannelUrl(url: String): SportsChannel? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val params = uri.query?.split('&')?.associate {
                val parts = it.split('=')
                parts.firstOrNull() to parts.getOrNull(1)
            } ?: return null

            val id = params["id"] ?: return null
            val title = params["title"] ?: return null
            return SportsChannel(id, title, "", "")
        }

        suspend fun channels(document: Document, base: String): List<SportsChannel> {
            // Strategy 1: Parse all inline scripts
            for (script in document.select("script")) {
                val data = script.data().ifBlank { null } ?: script.html()
                val parsed = parseChannelsFromText(data, base)
                if (parsed.isNotEmpty()) {
                    log("Found ${parsed.size} channels from inline script")
                    return parsed
                }
            }

            // Strategy 2: Fetch and parse external scripts
            val scriptUrls = document.select("script[src]").map { it.absUrl("src") }
            for (scriptUrl in scriptUrls) {
                try {
                    val text = app.get(scriptUrl, timeout = 15).text
                    val parsed = parseChannelsFromText(text, base)
                    if (parsed.isNotEmpty()) {
                        log("Found ${parsed.size} channels from script: $scriptUrl")
                        return parsed
                    }
                } catch (_: Exception) { }
            }

            // Strategy 3: Parse HTML directly for channel links
            val htmlParsed = parseChannelsFromHtml(document, base)
            if (htmlParsed.isNotEmpty()) {
                log("Found ${htmlParsed.size} channels from HTML")
                return htmlParsed
            }

            // Strategy 4: Search entire HTML as text for JSON arrays
            val htmlText = document.html()
            val parsed = parseChannelsFromText(htmlText, base)
            if (parsed.isNotEmpty()) {
                log("Found ${parsed.size} channels from full HTML")
                return parsed
            }

            return emptyList()
        }

        private fun parseChannelsFromText(script: String, base: String): List<SportsChannel> {
            val patterns = listOf(
                Regex("const\s+channels\s*=\s*(\[.*?\]);", RegexOption.DOT_MATCHES_ALL),
                Regex("var\s+channels\s*=\s*(\[.*?\]);", RegexOption.DOT_MATCHES_ALL),
                Regex("let\s+channels\s*=\s*(\[.*?\]);", RegexOption.DOT_MATCHES_ALL),
                Regex("channels\s*=\s*(\[.*?\]);", RegexOption.DOT_MATCHES_ALL),
                Regex("window\.channels\s*=\s*(\[.*?\]);", RegexOption.DOT_MATCHES_ALL),
                Regex("'channels':\s*(\[.*?\])", RegexOption.DOT_MATCHES_ALL),
                Regex("\"channels\":\s*(\[.*?\])", RegexOption.DOT_MATCHES_ALL),
            )

            for (pattern in patterns) {
                val match = pattern.find(script) ?: continue
                val array = match.groupValues.getOrNull(1) ?: continue
                val parsed = parseJsonArray(array).mapNotNull { row ->
                    val title = row["title"]?.trim() ?: row["name"]?.trim() ?: return@mapNotNull null
                    val url = row["url"] ?: row["link"] ?: row["src"] ?: return@mapNotNull null
                    val player = runCatching { URI(base).resolve(url) }.getOrNull()?.toString() ?: url
                    val id = queryParam(player, "id") ?: row["id"] ?: return@mapNotNull null
                    if (id.isBlank()) return@mapNotNull null
                    SportsChannel(id, title, "Spor Kanalları", player)
                }.distinctBy { it.id }
                if (parsed.isNotEmpty()) return parsed
            }
            return emptyList()
        }

        private fun parseChannelsFromHtml(document: Document, base: String): List<SportsChannel> {
            val channels = mutableListOf<SportsChannel>()

            // Look for links with id parameter
            for (link in document.select("a[href*=\"id=\"]")) {
                val href = link.absUrl("href").ifEmpty { link.attr("href") }
                val id = queryParam(href, "id") ?: continue
                val title = link.text().trim().ifEmpty { link.attr("title") } ?: continue
                if (title.isBlank()) continue
                channels.add(SportsChannel(id, title, "Spor Kanalları", href))
            }

            // Look for elements with data-id
            for (elem in document.select("[data-id]")) {
                val id = elem.attr("data-id")
                val title = elem.text().trim().ifEmpty { elem.attr("title") } ?: continue
                val href = elem.select("a").firstOrNull()?.absUrl("href") ?: continue
                if (title.isBlank() || id.isBlank()) continue
                channels.add(SportsChannel(id, title, "Spor Kanalları", href))
            }

            // Look for common channel list patterns
            for (item in document.select(".channel, .kanal, [class*=channel], [class*=kanal]")) {
                val link = item.select("a").firstOrNull()
                val href = link?.absUrl("href") ?: continue
                val id = queryParam(href, "id") ?: continue
                val title = link.text().trim().ifEmpty { item.text().trim() } ?: continue
                if (title.isBlank()) continue
                channels.add(SportsChannel(id, title, "Spor Kanalları", href))
            }

            return channels.distinctBy { it.id }
        }

        suspend fun getStreamUrls(document: Document, player: String): List<String> {
            val id = queryParam(player, "id") ?: return emptyList()

            // Strategy 1: Look for baseurls in all inline scripts
            for (script in document.select("script")) {
                val data = script.data().ifBlank { null } ?: script.html()
                val urls = parseBaseUrlsFromText(data, id)
                if (urls.isNotEmpty()) return urls
            }

            // Strategy 2: Look in external scripts
            for (scriptUrl in document.select("script[src]").map { it.absUrl("src") }) {
                try {
                    val text = app.get(scriptUrl, timeout = 15).text
                    val urls = parseBaseUrlsFromText(text, id)
                    if (urls.isNotEmpty()) return urls
                } catch (_: Exception) { }
            }

            // Strategy 3: Search entire HTML
            val urls = parseBaseUrlsFromText(document.html(), id)
            if (urls.isNotEmpty()) return urls

            return emptyList()
        }

        private fun parseBaseUrlsFromText(html: String, id: String): List<String> {
            val patterns = listOf(
                Regex("const\s+baseurls\s*=\s*(\[.*?\]);", RegexOption.DOT_MATCHES_ALL),
                Regex("var\s+baseurls\s*=\s*(\[.*?\]);", RegexOption.DOT_MATCHES_ALL),
                Regex("let\s+baseurls\s*=\s*(\[.*?\]);", RegexOption.DOT_MATCHES_ALL),
                Regex("baseurls\s*=\s*(\[.*?\]);", RegexOption.DOT_MATCHES_ALL),
                Regex("window\.baseurls\s*=\s*(\[.*?\]);", RegexOption.DOT_MATCHES_ALL),
            )

            for (pattern in patterns) {
                val match = pattern.find(html) ?: continue
                val array = match.groupValues.getOrNull(1) ?: continue
                return runCatching {
                    parseJsonArray(array).mapNotNull { baseNode ->
                        val baseUrl = httpsUrl(baseNode.toString()) ?: return@mapNotNull null
                        "${baseUrl.trimEnd('/')}/$id.m3u8"
                    }.distinct()
                }.getOrDefault(emptyList())
            }
            return emptyList()
        }

        private fun parseJsonArray(json: String): List<Map<String, String>> {
            val result = mutableListOf<Map<String, String>>()
            var current = mutableMapOf<String, String>()
            var key = ""
            var value = ""
            var inString = false
            var inObject = false
            var inKey = true
            var i = 0

            while (i < json.length) {
                val c = json[i]
                when {
                    c == '{' && !inString -> {
                        inObject = true
                        current = mutableMapOf()
                        inKey = true
                        key = ""
                        value = ""
                    }
                    c == '}' && !inString -> {
                        if (inObject) {
                            if (key.isNotEmpty() && value.isNotEmpty()) {
                                current[key] = value
                            }
                            result.add(current)
                            inObject = false
                        }
                    }
                    c == '"' && (i == 0 || json[i-1] != '\\') -> {
                        inString = !inString
                    }
                    c == ':' && !inString && inObject -> {
                        inKey = false
                        value = ""
                    }
                    c == ',' && !inString && inObject -> {
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            current[key] = value
                        }
                        inKey = true
                        key = ""
                        value = ""
                    }
                    !inString && c in " \t\n\r" -> {
                        // Skip whitespace
                    }
                    else -> {
                        if (inString || !inObject) {
                            // Skip
                        } else if (inKey) {
                            if (c != '"') key += c
                        } else {
                            if (c != '"') value += c
                        }
                    }
                }
                i++
            }

            return result
        }

        private fun httpsUrl(value: String): String? {
            return runCatching {
                val clean = value.trim().trim('"').trim()
                val uri = URI(clean)
                if (uri.scheme == "https" && uri.host != null) {
                    return clean
                }
                null
            }.getOrNull()
        }

        private fun queryParam(url: String, key: String): String? {
            return runCatching {
                val uri = URI(url)
                val query = uri.rawQuery ?: return null
                query.split('&').firstOrNull { it.startsWith("$key=") }
                    ?.substringAfter('=')
                    ?.let { URLDecoder.decode(it, "UTF-8") }
            }.getOrNull()
        }
    }
}
