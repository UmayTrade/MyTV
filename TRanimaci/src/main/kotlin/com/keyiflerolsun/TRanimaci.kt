// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.*
import org.jsoup.Jsoup

class TRanimaci : MainAPI() {
    override var mainUrl              = "https://tranimaci.com"
    override var name                 = "TrAnimeci"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 500L
    override var sequentialMainPageScrollDelay = 500L

    // Cloudflare için özel headers
    private val cloudflareHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Accept-Encoding" to "gzip, deflate, br",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Cache-Control" to "max-age=0",
        "Pragma" to "no-cache"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/kategoriler/action"                                    to "Aksiyon",
        "${mainUrl}/kategoriler/cars"                                      to "Arabalar",
        "${mainUrl}/kategoriler/supernatural"                              to "Doğaüstü",
        "${mainUrl}/kategoriler/drama"                                     to "Dram",
        "${mainUrl}/kategoriler/ecchi"                                     to "Ecchi",
        "${mainUrl}/kategoriler/fantasy"                                   to "Fantastik",
        "${mainUrl}/kategoriler/mystery"                                   to "Gizem",
        "${mainUrl}/kategoriler/comedy"                                    to "Komedi",
        "${mainUrl}/kategoriler/horror"                                    to "Korku",
        "${mainUrl}/kategoriler/adventure"                                 to "Macera",
        "${mainUrl}/kategoriler/mecha"                                     to "Mecha",
        "${mainUrl}/kategoriler/music"                                     to "Müzik",
        "${mainUrl}/kategoriler/romance"                                   to "Romantik",
        "${mainUrl}/kategoriler/sports"                                    to "Spor",
    )

    // Cloudflare korumasını aşmak için özel get fonksiyonu
    private suspend fun getWithCloudflare(url: String): Document {
        var retryCount = 0
        val maxRetries = 3
        
        while (retryCount < maxRetries) {
            try {
                Log.d("TRanimaci", "Sayfa yükleniyor (deneme ${retryCount + 1}): $url")
                
                // Normal istek dene
                val response = app.get(url, headers = cloudflareHeaders)
                val html = response.text
                
                // Cloudflare challenge kontrolü
                if (html.contains("cf-browser-verification") || 
                    html.contains("challenge-platform") ||
                    html.contains("Please wait") ||
                    html.contains("Checking your browser")) {
                    
                    Log.d("TRanimaci", "Cloudflare challenge tespit edildi! Bekleniyor...")
                    
                    // Bekle ve tekrar dene
                    val waitTime = (retryCount + 1) * 2000L
                    Thread.sleep(waitTime)
                    
                    val retryResponse = app.get(url, headers = cloudflareHeaders)
                    val retryHtml = retryResponse.text
                    
                    if (!retryHtml.contains("cf-browser-verification") && 
                        !retryHtml.contains("challenge-platform") &&
                        !retryHtml.contains("Please wait")) {
                        return retryResponse.document
                    }
                } else {
                    return response.document
                }
                
            } catch (e: Exception) {
                Log.e("TRanimaci", "Sayfa yüklenirken hata (deneme ${retryCount + 1}): ${e.message}")
                retryCount++
                if (retryCount < maxRetries) {
                    val waitTime = retryCount * 2000L
                    Thread.sleep(waitTime)
                }
            }
        }
        
        // Son bir deneme daha
        try {
            Log.d("TRanimaci", "Son deneme: $url")
            val finalResponse = app.get(url, headers = cloudflareHeaders)
            return finalResponse.document
        } catch (e: Exception) {
            Log.e("TRanimaci", "Son deneme de başarısız: ${e.message}")
            throw Exception("Cloudflare koruması aşılamadı: $url")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            val document = getWithCloudflare(request.data)
            val home = document.select("a.group.block").mapNotNull { it.toMainPageResult() }
            return newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            Log.e("TRanimaci", "getMainPage hatası: ${e.message}")
            return newHomePageResponse(request.name, listOf())
        }
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        try {
            val title = this.selectFirst("h3")?.text()?.trim() ?: return null
            val href = fixUrlNull(this.attr("href")) ?: return null
            
            var posterUrl = this.selectFirst("div.relative img")?.attr("src")
            posterUrl = fixPosterUrl(posterUrl)
            
            return newAnimeSearchResponse(title, href, TvType.Anime) { 
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("TRanimaci", "toMainPageResult hatası: ${e.message}")
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val document = getWithCloudflare("${mainUrl}/arama?q=${query}")
            return document.select("a.group.block").mapNotNull { it.toMainPageResult() }
        } catch (e: Exception) {
            Log.e("TRanimaci", "search hatası: ${e.message}")
            return listOf()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = getWithCloudflare(url)

            val title = document.selectFirst("h1")?.text()?.trim() ?: return null
            
            var poster = document.selectFirst("div.relative img")?.attr("src")
            poster = fixPosterUrl(poster)
            
            val description = document.selectFirst("p.text-sm.text-foreground/80.leading-relaxed")?.text()?.trim()
            
            val tags = document.select("div.flex.flex-wrap.gap-1.5 span").map { it.text() }

            val episodeses = mutableListOf<Episode>()

            // Bölümleri bul - farklı selector'ları dene
            var episodeElements = document.select("a[href*='/video/']")
            
            // Eğer hiç bölüm yoksa, alternatif selector dene
            if (episodeElements.isEmpty()) {
                episodeElements = document.select("a[href^='/video/']")
            }
            
            // Hala yoksa tüm linkleri kontrol et
            if (episodeElements.isEmpty()) {
                val allLinks = document.select("a")
                val filteredLinks = allLinks.filter { 
                    it.attr("href").contains("/video/") || it.attr("href").contains("bolum")
                }
                // filter sonucu List<Element> döner, bunu Elements'e çevirelim
                episodeElements = org.jsoup.select.Elements()
                episodeElements.addAll(filteredLinks)
            }
            
            for (link in episodeElements) {
                val epHref = fixUrlNull(link.attr("href")) ?: continue
                val epName = link.selectFirst("span")?.text()?.trim() 
                    ?: link.text()?.trim() 
                    ?: "Bölüm ${episodeses.size + 1}"
                
                val epEpisode = Regex("""(\d+)\. Bölüm""").find(epName)
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: epName.replace("Bölüm", "").trim().toIntOrNull()
                    ?: episodeses.size + 1

                val newEpisode = newEpisode(epHref) {
                    this.name = epName
                    this.episode = epEpisode
                }
                episodeses.add(newEpisode)
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeses) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } catch (e: Exception) {
            Log.e("TRanimaci", "load hatası: ${e.message}")
            return null
        }
    }

    private fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        
        return try {
            when {
                url.contains("/_next/image?url=") -> {
                    val decodedUrl = java.net.URLDecoder.decode(url, "UTF-8")
                    val match = Regex("""url=([^&]+)""").find(decodedUrl)
                    match?.groupValues?.get(1)?.let { 
                        if (it.startsWith("/")) {
                            fixUrlNull(mainUrl + it)
                        } else {
                            fixUrlNull(it)
                        }
                    }
                }
                url.startsWith("http") -> fixUrlNull(url)
                else -> fixUrlNull(mainUrl + url)
            }
        } catch (e: Exception) {
            Log.e("TRanimaci", "fixPosterUrl hatası: ${e.message}")
            url
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TRanimaci", "=== loadLinks BAŞLADI ===")
        Log.d("TRanimaci", "URL: $data")
        
        try {
            val document = getWithCloudflare(data)
            Log.d("TRanimaci", "Sayfa yüklendi, title: ${document.title()}")

            // 1. Videoları bul - videostraeam1.can.re
            val videoElements = document.select("source[src], video[src]")
            for (video in videoElements) {
                var videoUrl = video.attr("src")
                if (videoUrl.isEmpty()) {
                    videoUrl = video.attr("data-src")
                }
                if (videoUrl.isNotEmpty() && videoUrl.contains("videostraeam1.can.re")) {
                    Log.d("TRanimaci", "Video source bulundu: $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - Video",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.headers = cloudflareHeaders
                        }
                    )
                    return true
                }
            }

            // 2. iframe kontrolü
            val iframes = document.select("iframe[src]")
            for (iframe in iframes) {
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.isNotEmpty() && (iframeSrc.contains("videostraeam1") || iframeSrc.contains("embed") || iframeSrc.contains("player"))) {
                    Log.d("TRanimaci", "iframe bulundu: $iframeSrc")
                    try {
                        val iframeDoc = getWithCloudflare(iframeSrc)
                        val innerVideo = iframeDoc.select("source[src], video[src]").first()
                        if (innerVideo != null) {
                            var videoUrl = innerVideo.attr("src")
                            if (videoUrl.isEmpty()) {
                                videoUrl = innerVideo.attr("data-src")
                            }
                            if (videoUrl.isNotEmpty()) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "${this.name} - iframe",
                                        url = videoUrl,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = iframeSrc
                                        this.headers = cloudflareHeaders
                                    }
                                )
                                return true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TRanimaci", "iframe içeriği yüklenemedi: ${e.message}")
                    }
                }
            }

            // 3. Script içinde video linkleri
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptHtml = script.html()
                
                // MP4/M3U8 linklerini bul
                val videoPatterns = listOf(
                    Regex("""(https?://[^\s"'<>]+\.mp4)"""),
                    Regex("""(https?://[^\s"'<>]+\.m3u8)"""),
                    Regex("""(https?://cdn\d*\.videostraeam\d*\.can\.re/[^\s"'<>]+)""")
                )
                
                for (pattern in videoPatterns) {
                    val match = pattern.find(scriptHtml)
                    if (match != null) {
                        val videoUrl = match.groupValues[1]
                        Log.d("TRanimaci", "Script içinde video URL bulundu: $videoUrl")
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} - Script",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.headers = cloudflareHeaders
                            }
                        )
                        return true
                    }
                }

                // video_source veya player_config kontrolü
                if (scriptHtml.contains("video_source") || scriptHtml.contains("player_config") || 
                    scriptHtml.contains("sources") || scriptHtml.contains("file:")) {
                    Log.d("TRanimaci", "Video konfigürasyonu içeren script bulundu")
                    val success = parseVideoConfig(scriptHtml, callback)
                    if (success) {
                        return true
                    }
                }
            }

            // 4. HTML içinde MP4 linkleri
            val htmlContent = document.html()
            val htmlPatterns = listOf(
                Regex("""(https?://cdn\d*\.videostraeam\d*\.can\.re/[^\s"'<>]+\.mp4)"""),
                Regex("""(https?://[^\s"'<>]+\.mp4)"""),
                Regex("""(https?://[^\s"'<>]+\.m3u8)""")
            )
            
            for (pattern in htmlPatterns) {
                val match = pattern.find(htmlContent)
                if (match != null) {
                    val videoUrl = match.groupValues[1]
                    Log.d("TRanimaci", "HTML içinde video URL bulundu: $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - Direct",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.headers = cloudflareHeaders
                        }
                    )
                    return true
                }
            }

            Log.e("TRanimaci", "Hiçbir link bulunamadı!")
            return false

        } catch (e: Exception) {
            Log.e("TRanimaci", "loadLinks HATASI: ${e.message}", e)
            return false
        }
    }

    private suspend fun parseVideoConfig(scriptHtml: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // video_source
            val videoSourceMatch = Regex("""video_source\s*=\s*`(\[[\s\S]*?])`""", RegexOption.DOT_MATCHES_ALL)
                .find(scriptHtml)
            
            if (videoSourceMatch != null) {
                val videoSourceJson = videoSourceMatch.groupValues[1]
                val videoSourceArray = JSONArray(videoSourceJson)
                
                for (i in 0 until videoSourceArray.length()) {
                    try {
                        val source = videoSourceArray.getJSONObject(i)
                        val apiUrl = source.getString("url")
                        
                        val response = getWithCloudflare(apiUrl)
                        val apiHtml = response.html()
                        
                        val sourcesMatch = Regex("""(?:const|var|let)\s+sources\s*=\s*(\[[\s\S]*?])\s*;""")
                            .find(apiHtml)
                        
                        if (sourcesMatch != null) {
                            val sourcesArrayRaw = sourcesMatch.groupValues[1]
                            val mp4Array = JSONArray(sourcesArrayRaw)
                            
                            for (j in 0 until mp4Array.length()) {
                                try {
                                    val mp4 = mp4Array.getJSONObject(j)
                                    var videoUrl = mp4.getString("src")
                                    
                                    if (!videoUrl.startsWith("http")) {
                                        if (videoUrl.startsWith("//")) {
                                            videoUrl = "https:" + videoUrl
                                        } else {
                                            videoUrl = "https://api.animeuzayi.com" + videoUrl
                                        }
                                    }
                                    
                                    val quality = mp4.optInt("size", 0)
                                    
                                    callback.invoke(
                                        newExtractorLink(
                                            source = this.name,
                                            name = "${this.name} - ${if (quality > 0) "${quality}p" else "SD"}",
                                            url = videoUrl,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = "https://api.animeuzayi.com/"
                                            this.quality = quality
                                            this.headers = cloudflareHeaders
                                        }
                                    )
                                    return true
                                } catch (e: Exception) {
                                    Log.e("TRanimaci", "MP4 parse hatası: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TRanimaci", "API ${i} hatası: ${e.message}")
                    }
                }
            }

            // player_config
            val configMatch = Regex("""(?:player_config|playerConfig)\s*=\s*({[\s\S]*?});""")
                .find(scriptHtml)
            
            if (configMatch != null) {
                val configJson = configMatch.groupValues[1]
                val config = JSONObject(configJson)
                
                val sources = config.optJSONArray("sources")
                if (sources != null) {
                    for (i in 0 until sources.length()) {
                        val source = sources.getJSONObject(i)
                        var videoUrl = source.getString("src")
                        
                        if (videoUrl.isNotEmpty()) {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${this.name} - Player",
                                    url = videoUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                    this.headers = cloudflareHeaders
                                }
                            )
                            return true
                        }
                    }
                }
            }

            // file: veya src: içeren JSON
            val fileMatch = Regex("""(?:file|src)\s*:\s*['"]([^'"]+)['"]""").find(scriptHtml)
            if (fileMatch != null) {
                var videoUrl = fileMatch.groupValues[1]
                if (!videoUrl.startsWith("http")) {
                    if (videoUrl.startsWith("//")) {
                        videoUrl = "https:" + videoUrl
                    }
                }
                if (videoUrl.isNotEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - File",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.headers = cloudflareHeaders
                        }
                    )
                    return true
                }
            }

        } catch (e: Exception) {
            Log.e("TRanimaci", "parseVideoConfig hatası: ${e.message}")
        }
        return false
    }
}
