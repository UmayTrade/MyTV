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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("a.group.block").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("h3")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        
        // Poster URL'ini al ve düzelt
        var posterUrl = this.selectFirst("div.relative img")?.attr("src")
        posterUrl = fixPosterUrl(posterUrl)
        
        return newAnimeSearchResponse(title, href, TvType.Anime) { 
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama?q=${query}").document
        return document.select("a.group.block").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        
        // Poster URL'ini al ve düzelt
        var poster = document.selectFirst("div.relative img")?.attr("src")
        poster = fixPosterUrl(poster)
        
        val description = document.selectFirst("p.text-sm.text-foreground/80.leading-relaxed")?.text()?.trim()
        
        val tags = document.select("div.flex.flex-wrap.gap-1.5 span").map { it.text() }

        val episodeses = mutableListOf<Episode>()

        val episodeLinks = document.select("a[href*='/video/']")
        for (link in episodeLinks) {
            val epHref = fixUrlNull(link.attr("href")) ?: continue
            val epName = link.selectFirst("span")?.text()?.trim() 
                ?: link.text()?.trim() 
                ?: "Bölüm ${episodeses.size + 1}"
            
            val epEpisode = Regex("""(\d+)\. Bölüm""").find(epName)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: epName.replace("Bölüm", "").trim().toIntOrNull()

            val newEpisode = newEpisode(epHref) {
                this.name = epName
                this.episode = epEpisode
            }
            episodeses.add(newEpisode)
        }

        if (episodeses.isEmpty()) {
            val allVideoLinks = document.select("a[href*='/video/']")
            for (link in allVideoLinks) {
                val epHref = fixUrlNull(link.attr("href")) ?: continue
                val epName = link.text()?.trim() ?: "Bölüm ${episodeses.size + 1}"
                
                val newEpisode = newEpisode(epHref) {
                    this.name = epName
                    this.episode = episodeses.size + 1
                }
                episodeses.add(newEpisode)
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeses) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    // Poster URL'ini düzelt
    private fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        
        return when {
            // _next/image formatındaki URL'leri düzelt
            url.contains("/_next/image?url=") -> {
                try {
                    // URL'den gerçek resim yolunu çıkar
                    val decodedUrl = java.net.URLDecoder.decode(url, "UTF-8")
                    val match = Regex("""url=([^&]+)""").find(decodedUrl)
                    match?.groupValues?.get(1)?.let { 
                        // Eğer relative path ise mainUrl ekle
                        if (it.startsWith("/")) {
                            fixUrlNull(mainUrl + it)
                        } else {
                            fixUrlNull(it)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TRanimaci", "Poster URL decode hatası: ${e.message}")
                    url
                }
            }
            // Direkt URL ise
            url.startsWith("http") -> fixUrlNull(url)
            // Relative path ise
            else -> fixUrlNull(mainUrl + url)
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
            val document = app.get(data).document
            Log.d("TRanimaci", "Sayfa yüklendi, title: ${document.title()}")

            // 1. YÖNTEM: videostraeam1.can.re linklerini bul
            val videoLinks = document.select("source[src*='videostraeam1.can.re'], video[src*='videostraeam1.can.re']")
            for (video in videoLinks) {
                var videoUrl = video.attr("src")
                if (videoUrl.isEmpty()) {
                    videoUrl = video.attr("data-src")
                }
                if (videoUrl.isNotEmpty()) {
                    Log.d("TRanimaci", "Video source bulundu: $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - Video",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                        }
                    )
                    return true
                }
            }

            // 2. YÖNTEM: iframe içindeki videoları bul
            val iframes = document.select("iframe[src*='videostraeam1'], iframe[src*='embed']")
            for (iframe in iframes) {
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.isNotEmpty()) {
                    Log.d("TRanimaci", "iframe bulundu: $iframeSrc")
                    try {
                        val iframeDoc = app.get(iframeSrc).document
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
                                        this.referer = mainUrl
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

            // 3. YÖNTEM: script içinde video linkleri
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptHtml = script.html()
                
                val videoUrlMatch = Regex("""(https?://[^\s"'<>]+\.(?:mp4|m3u8))""").find(scriptHtml)
                if (videoUrlMatch != null) {
                    val videoUrl = videoUrlMatch.groupValues[1]
                    Log.d("TRanimaci", "Script içinde video URL bulundu: $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - Script",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                        }
                    )
                    return true
                }

                if (scriptHtml.contains("video_source") || scriptHtml.contains("player_config") || scriptHtml.contains("sources")) {
                    Log.d("TRanimaci", "Video konfigürasyonu içeren script bulundu")
                    val success = parseVideoConfig(scriptHtml, callback)
                    if (success) {
                        return true
                    }
                }
            }

            // 4. YÖNTEM: HTML içinde MP4 linkleri
            val htmlContent = document.html()
            val mp4Patterns = listOf(
                Regex("""(https?://cdn\d*\.videostraeam\d*\.can\.re/[^\s"'<>]+\.mp4)"""),
                Regex("""(https?://[^\s"'<>]+\.mp4)"""),
                Regex("""(https?://[^\s"'<>]+\.m3u8)""")
            )
            
            for (pattern in mp4Patterns) {
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
            // video_source array'i bul
            val videoSourceMatch = Regex("""video_source\s*=\s*`(\[[\s\S]*?])`""", RegexOption.DOT_MATCHES_ALL)
                .find(scriptHtml)
            
            if (videoSourceMatch != null) {
                val videoSourceJson = videoSourceMatch.groupValues[1]
                Log.d("TRanimaci", "videoSourceJson bulundu")
                
                val videoSourceArray = JSONArray(videoSourceJson)
                for (i in 0 until videoSourceArray.length()) {
                    try {
                        val source = videoSourceArray.getJSONObject(i)
                        val apiUrl = source.getString("url")
                        Log.d("TRanimaci", "API URL: $apiUrl")
                        
                        val response = app.get(apiUrl)
                        val apiHtml = response.text
                        
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
                                    Log.d("TRanimaci", "Video URL: $videoUrl")
                                    
                                    callback.invoke(
                                        newExtractorLink(
                                            source = this.name,
                                            name = "${this.name} - ${if (quality > 0) "${quality}p" else "SD"}",
                                            url = videoUrl,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = "https://api.animeuzayi.com/"
                                            this.quality = quality
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

            // player_config dene
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
                                }
                            )
                            return true
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("TRanimaci", "parseVideoConfig hatası: ${e.message}")
        }
        return false
    }
}
