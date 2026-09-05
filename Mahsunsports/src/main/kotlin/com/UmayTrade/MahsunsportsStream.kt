package com.UmayTrade

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder

class MahsunSports : MainAPI() {
    override var mainUrl = "https://mahsunsports80.xyz/"
    override var name = "MahsunSports"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    // Ana sayfa kategorileri
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

        // Kanal gruplama fonksiyonu
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

        // Poster URL'leri
        fun getPosterUrl(id: String): String? {
            return when {
                // beIN Sports
                id.startsWith("bs") -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/international/beinsports/old/horizontal/bein-sports-${id.substring(2)}-hz-int.png"
                // S Sport
                id == "ss1" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/s-sport-tr.png"
                id == "ss2" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/s-sport-2-tr.png"
                id == "ssplus1" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/s-sport-plus-tr.png"
                // Spor Smart
                id == "sm1" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/spor-smart-hd-tr.png"
                id == "sm2" -> "https://i.imgur.com/qyUKCUa.png"
                // TRT
                id == "trt1" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-1-tr.png"
                id == "trts" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-spor-tr.png"
                id == "trtsy" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-spor-yildiz-tr.png"
                // Ulusal
                id == "as" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/a-spor-tr.png"
                id == "atv" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/atv-tr.png"
                id == "a2" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/a2-tr.png"
                id == "tjk" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tjk-tv-tr.png"
                id == "ht" -> "https://www.htspor.com/images/manifest/social-share-logo.png"
                id == "tv8" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tv8-tr.png"
                id == "tv85" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tv85-tr.png"
                id == "sptstv" -> "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/sports-tv-tr.png"
                // Yabancı
                id == "es1" -> "https://i.imgur.com/olQJgm7.png"
                id == "es2" -> "https://i.imgur.com/f56dHgR.png"
                id == "idm" -> "https://i.imgur.com/fM9FOrZ.png"
                id == "cbcs" -> "https://i.imgur.com/3mEdjuq.png"
                id == "nba" -> "https://upload.wikimedia.org/wikipedia/en/thumb/d/d2/NBA_TV.svg/960px-NBA_TV.svg.png"
                id == "fb" -> "https://i.imgur.com/qBVqtYd.png"
                id == "gs" -> "https://i.imgur.com/fC3KuwT.jpg"
                // tabii ve Exxen
                id.startsWith("tb") -> "https://cdn.prod.website-files.com/658da28123ee3a39812a40fd/65b199f7f21447f8e9e76a47_tabii-wc.png"
                id.startsWith("exn") -> "https://upload.wikimedia.org/wikipedia/commons/d/db/Exxen.png"
                // Tivibu
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
            
            // Eğer kategori filtresi varsa, tüm kanalları çekip filtrele
            val category = when {
                request.data.contains("?category=") -> {
                    request.data.substringAfter("?category=")
                }
                else -> null
            }

            val document = withContext(Dispatchers.IO) {
                Jsoup.connect(mainUrl).ignoreContentType(true).get()
            }

            // script4.js dosyasını bul
            val scriptUrl = document.select("script[src]").map { it.absUrl("src") }
                .firstOrNull { it.contains("script4.js") || it.contains("main.js") }
                ?: return errorResponse("script4.js bulunamadı", request.data)

            log("Script URL: $scriptUrl")

            val scriptContent = withContext(Dispatchers.IO) { 
                try {
                    app.get(scriptUrl).text
                } catch (e: Exception) {
                    // Alternatif olarak doğrudan HTML içinde ara
                    document.html()
                }
            }

            val channels = parser.channels(scriptContent, mainUrl)
            
            if (channels.isEmpty()) {
                return errorResponse("Kanal bulunamadı", request.data)
            }

            // Kategori filtresi varsa uygula
            val filteredChannels = if (category != null) {
                channels.filter { category(it.title) == category || it.category == category }
            } else {
                channels
            }

            if (filteredChannels.isEmpty()) {
                return errorResponse("Bu kategoride kanal bulunamadı", request.data)
            }

            val grouped = filteredChannels.groupBy { category(it.title) }
            
            // Sıralı kategoriler
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
                        ).apply {
                            posterUrl = getPosterUrl(it.id) ?: ""
                            // Kanal durumu için plot
                            plot = "📺 ${it.title}\n🟢 Yayında"
                        }
                    }
                )
            }

            // Eğer hiç grup yoksa tüm kanalları tek listede göster
            if (homePageList.isEmpty()) {
                return newHomePageResponse(
                    request.data,
                    listOf(
                        HomePageList(
                            "Tüm Kanallar",
                            filteredChannels.map {
                                newLiveSearchResponse(
                                    it.title,
                                    parser.buildChannelUrl(it.id, it.title),
                                    TvType.Live
                                ).apply {
                                    posterUrl = getPosterUrl(it.id) ?: ""
                                }
                            }
                        )
                    )
                )
            }

            return newHomePageResponse(request.data, homePageList)
        } catch (e: Exception) {
            log("getMainPage error: ${e.message}")
            return errorResponse("Hata: ${e.message}", request.data)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val document = withContext(Dispatchers.IO) {
                Jsoup.connect(mainUrl).ignoreContentType(true).get()
            }

            val scriptUrl = document.select("script[src]").map { it.absUrl("src") }
                .firstOrNull { it.contains("script4.js") || it.contains("main.js") }
                ?: return emptyList()

            val scriptContent = withContext(Dispatchers.IO) { 
                try {
                    app.get(scriptUrl).text
                } catch (e: Exception) {
                    document.html()
                }
            }

            val channels = parser.channels(scriptContent, mainUrl)

            return channels.filter { 
                it.title.contains(query, ignoreCase = true) 
            }.map {
                newLiveSearchResponse(
                    it.title,
                    parser.buildChannelUrl(it.id, it.title),
                    TvType.Live
                ).apply {
                    posterUrl = getPosterUrl(it.id) ?: ""
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val channel = parser.parseChannelUrl(url) 
                ?: return null

            return newLiveStreamLoadResponse(
                channel.title,
                url = url,
                url
            ) {
                posterUrl = getPosterUrl(channel.id) ?: ""
                plot = "📺 ${channel.title} canlı yayını\n🔗 Kaynak: MahsunSports\n\n💡 Yayın açılmazsa:\n• WARP (1.1.1.1) kullanın\n• Tarayıcıdan dene"
                addSubtitle("Türkçe", mainUrl)
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

            // Ana sayfayı çek
            val document = withContext(Dispatchers.IO) {
                Jsoup.connect(mainUrl).ignoreContentType(true).get()
            }

            // script4.js'i bul
            val scriptUrl = document.select("script[src]").map { it.absUrl("src") }
                .firstOrNull { it.contains("script4.js") || it.contains("main.js") }
                ?: return false

            val scriptContent = withContext(Dispatchers.IO) { 
                try {
                    app.get(scriptUrl).text
                } catch (e: Exception) {
                    document.html()
                }
            }

            // Stream URL'lerini al
            val streamUrls = parser.getStreamUrls(scriptContent, channel.player)
            log("Stream URLs: $streamUrls")

            if (streamUrls.isEmpty()) {
                // Alternatif URL'leri dene
                return tryAlternativeUrls(channel.id, callback)
            }

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:138.0) Gecko/20100101 Firefox/138.0",
                "Referer" to mainUrl,
                "Connection" to "keep-alive"
            )

            var found = false
            for (url in streamUrls) {
                try {
                    val masterContent = withContext(Dispatchers.IO) {
                        app.get(url, headers = headers).text
                    }

                    val variants = parseHlsVariants(masterContent, url)

                    for (variant in variants) {
                        callback(
                            ExtractorLink(
                                source = name,
                                name = "${variant.height}p",
                                url = variant.url,
                                referer = mainUrl,
                                quality = variant.height,
                                headers = headers,
                                isM3u8 = true
                            )
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

    // --- Alternatif URL Dene ---
    private suspend fun tryAlternativeUrls(channelId: String, callback: (ExtractorLink) -> Unit): Boolean {
        val altUrls = listOf(
            "https://mahsunsports.com/hls/$channelId.m3u8",
            "https://mahsunsports.com/stream/$channelId.m3u8",
            "https://mahsunsports80.xyz/hls/$channelId.m3u8"
        )

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:138.0) Gecko/20100101 Firefox/138.0",
            "Referer" to mainUrl,
            "Connection" to "keep-alive"
        )

        for (url in altUrls) {
            try {
                val response = withContext(Dispatchers.IO) {
                    app.get(url, headers = headers)
                }
                if (response.isSuccessful) {
                    val content = response.text
                    val variants = parseHlsVariants(content, url)
                    for (variant in variants) {
                        callback(
                            ExtractorLink(
                                source = name,
                                name = "${variant.height}p",
                                url = variant.url,
                                referer = mainUrl,
                                quality = variant.height,
                                headers = headers,
                                isM3u8 = true
                            )
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

    // --- HLS Parser ---
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
                Regex("(?i)\\b(4k|uhd|2160p?)\\b").containsMatchIn(text) -> 2160
                Regex("(?i)\\b(1920|1080p?|fhd|full\\s*hd)\\b").containsMatchIn(text) -> 1080
                Regex("(?i)\\b(1280|720p?|hd)\\b").containsMatchIn(text) -> 720
                Regex("(?i)\\b(576p?|sd)\\b").containsMatchIn(text) -> 576
                else -> 480
            }
            return listOf(HlsVariant(baseUrl, hint, 0L))
        }

        return variants.distinctBy { it.url }
            .sortedWith(compareByDescending<HlsVariant> { it.height }
            .thenByDescending { it.bandwidth })
    }

    // --- Hata Yanıtı ---
    private fun errorResponse(message: String, request: String): HomePageResponse {
        return newHomePageResponse(
            request,
            listOf(
                HomePageList(
                    "⚠️ Hata",
                    listOf(
                        newMainPageItem(
                            "❌ $message",
                            "",
                            TvType.Live
                        ).apply { posterUrl = "" }
                    )
                )
            )
        )
    }

    // --- Veri Sınıfları ---
    data class HlsVariant(val url: String, val height: Int, val bandwidth: Long = 0L)

    data class SportsChannel(
        val id: String,
        val title: String,
        val category: String,
        val player: String,
        val time: String = ""
    )

    // --- Parser ---
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

        fun channels(script: String, base: String): List<SportsChannel> {
            // Önce script içinde ara
            var match = Regex("const\\s+channels\\s*=\\s*(\\[.*?\\]);", RegexOption.DOT_MATCHES_ALL)
                .find(script)
            
            // Bulunamazsa HTML içinde ara
            if (match == null) {
                match = Regex("channels\\s*=\\s*(\\[.*?\\]);", RegexOption.DOT_MATCHES_ALL)
                    .find(script)
            }
            
            val array = match?.groupValues?.getOrNull(1) ?: return emptyList()

            return runCatching {
                // Basit JSON parse - Jackson olmadan
                parseJsonArray(array).mapNotNull { row ->
                    val title = row["title"]?.trim() ?: return@mapNotNull null
                    val url = row["url"] ?: return@mapNotNull null
                    
                    val player = runCatching { URI(base).resolve(url) }.getOrNull()?.toString() ?: url
                    if (player.isEmpty()) return@mapNotNull null
                    
                    val id = queryParam(player, "id") ?: return@mapNotNull null
                    if (!Regex("(androstreamlive|facebooklive)[a-zA-Z0-9]{1,30}").matches(id))
                        return@mapNotNull null

                    SportsChannel(id, title, "Spor Kanalları", player)
                }.distinctBy { it.id }
            }.getOrDefault(emptyList())
        }

        fun getStreamUrls(html: String, player: String): List<String> {
            val id = queryParam(player, "id") ?: return emptyList()
            if (!Regex("(androstreamlive|facebooklive)[a-zA-Z0-9]{1,30}").matches(id))
                return emptyList()

            // HTML içinde baseurls ara
            var match = Regex("const\\s+baseurls\\s*=\\s*(\\[.*?\\]);", RegexOption.DOT_MATCHES_ALL)
                .find(html)
            
            if (match == null) {
                match = Regex("baseurls\\s*=\\s*(\\[.*?\\]);", RegexOption.DOT_MATCHES_ALL)
                    .find(html)
            }
            
            val array = match?.groupValues?.getOrNull(1) ?: return emptyList()

            return runCatching {
                parseJsonArray(array).mapNotNull { baseNode ->
                    val baseUrl = httpsUrl(baseNode.toString()) ?: return@mapNotNull null
                    "${baseUrl.trimEnd('/')}/$id.m3u8"
                }.distinct()
            }.getOrDefault(emptyList())
        }

        private fun parseJsonArray(json: String): List<Map<String, String>> {
            // Basit JSON array parse
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
                val uri = URI(value.trim().trim('"'))
                if (uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
                    uri.port in listOf(-1, 443)) {
                    return value.trim().trim('"')
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