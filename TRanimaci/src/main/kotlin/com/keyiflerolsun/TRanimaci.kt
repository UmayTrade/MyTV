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
        "${mainUrl}/category/action"                                   to "Aksiyon",
        "${mainUrl}/category/cars"                                     to "Arabalar",
        "${mainUrl}/category/supernatural"                             to "Doğaüstü",
        "${mainUrl}/category/drama"                                    to "Dram",
        "${mainUrl}/category/ecchi"                                    to "Ecchi",
        "${mainUrl}/category/fantasy"                                  to "Fantastik",
        "${mainUrl}/category/mystery"                                  to "Gizem",
        "${mainUrl}/category/comedy"                                   to "Komedi",
        "${mainUrl}/category/horror"                                   to "Korku",
        "${mainUrl}/category/adventure"                                to "Macera",
        "${mainUrl}/category/mecha"                                    to "Mecha",
        "${mainUrl}/category/music"                                    to "Müzik",
        "${mainUrl}/category/romance"                                  to "Romantik",
        "${mainUrl}/category/sports"                                   to "Spor",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home     = document.select("article.bs div.bsx").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("a")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.limit img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/search?name=${query}").document

        return document.select("article.bs div.bsx").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.thumb img")?.attr("src"))
        val description = document.selectFirst("div.anime-description")?.text()?.trim()
        val tags        = document.select("div#genxed a[href*='/category']").map { it.text() }

        val episodeses = mutableListOf<Episode>()

        for (bolum in document.select("div.eplister ul li a")) {
            val epHref = fixUrlNull(bolum.attr("href")) ?: continue
            val epName = bolum.selectFirst(".epl-title")?.text()?.trim() ?: continue
            val epEpisode = epName.replace("Bölüm", "").trim().toIntOrNull()
    
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
            // Sayfayı yükle
            val document = app.get(data).document
            Log.d("TRanimaci", "Sayfa yüklendi, title: ${document.title()}")

            // TÜM script'leri kontrol et
            val allScripts = document.select("script")
            Log.d("TRanimaci", "Toplam script sayısı: ${allScripts.size}")

            // 1. YÖNTEM: video_source array'ini bul
            var linkBulundu = false
            
            for (script in allScripts) {
                val scriptHtml = script.html()
                
                // video_source kontrolü
                if (scriptHtml.contains("video_source")) {
                    Log.d("TRanimaci", "video_source içeren script bulundu!")
                    linkBulundu = parseVideoSource(scriptHtml, callback)
                    if (linkBulundu) {
                        Log.d("TRanimaci", "video_source'dan link bulundu!")
                        return true
                    }
                }
                
                // player_config kontrolü
                if (scriptHtml.contains("player_config") || scriptHtml.contains("playerConfig")) {
                    Log.d("TRanimaci", "player_config içeren script bulundu!")
                    linkBulundu = parsePlayerConfig(scriptHtml, callback)
                    if (linkBulundu) {
                        Log.d("TRanimaci", "player_config'dan link bulundu!")
                        return true
                    }
                }
            }

            // 2. YÖNTEM: iframe içinde video ara
            val iframe = document.selectFirst("iframe")
            if (iframe != null) {
                val iframeSrc = iframe.attr("src")
                Log.d("TRanimaci", "iframe bulundu: $iframeSrc")
                if (iframeSrc.isNotEmpty()) {
                    linkBulundu = parseIframe(iframeSrc, callback)
                    if (linkBulundu) {
                        Log.d("TRanimaci", "iframe'den link bulundu!")
                        return true
                    }
                }
            }

            // 3. YÖNTEM: video etiketi ara
            val videoElements = document.select("video, source")
            for (video in videoElements) {
                var videoUrl = video.attr("src")
                if (videoUrl.isEmpty()) {
                    videoUrl = video.attr("data-src")
                }
                if (videoUrl.isEmpty()) {
                    videoUrl = video.attr("file")
                }
                if (videoUrl.isNotEmpty()) {
                    Log.d("TRanimaci", "Video elementi bulundu: $videoUrl")
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

            // 4. YÖNTEM: HTML içinde mp4/m3u8 ara
            val htmlContent = document.html()
            val urlPatterns = listOf(
                Regex("""(https?://[^\s"'<>]+\.mp4)"""),
                Regex("""(https?://[^\s"'<>]+\.m3u8)"""),
                Regex("""(https?://[^\s"'<>]+/video/[^\s"'<>]+)""")
            )
            
            for (pattern in urlPatterns) {
                val match = pattern.find(htmlContent)
                if (match != null) {
                    val videoUrl = match.groupValues[1]
                    Log.d("TRanimaci", "HTML içinde video URL bulundu: $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - Direct",
                            url = videoUrl,
                            type = if (videoUrl.endsWith(".m3u8")) ExtractorLinkType.HLS else ExtractorLinkType.VIDEO
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

    // video_source parse et
    private suspend fun parseVideoSource(scriptHtml: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // video_source içindeki JSON'u bul
            val videoSourceMatch = Regex("""video_source\s*=\s*`(\[[\s\S]*?])`""", RegexOption.DOT_MATCHES_ALL)
                .find(scriptHtml)
            
            if (videoSourceMatch == null) {
                Log.e("TRanimaci", "video_source JSON bulunamadı!")
                return false
            }

            val videoSourceJson = videoSourceMatch.groupValues[1]
            Log.d("TRanimaci", "videoSourceJson: ${videoSourceJson.take(200)}")

            val videoSourceArray = JSONArray(videoSourceJson)
            Log.d("TRanimaci", "videoSourceArray uzunluğu: ${videoSourceArray.length()}")

            for (i in 0 until videoSourceArray.length()) {
                try {
                    val source = videoSourceArray.getJSONObject(i)
                    val apiUrl = source.getString("url")
                    Log.d("TRanimaci", "API URL $i: $apiUrl")

                    // API'ye istek at
                    val response = app.get(
                        apiUrl,
                        headers = mapOf(
                            "Referer" to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    )
                    
                    val apiHtml = response.text
                    Log.d("TRanimaci", "API cevap uzunluğu: ${apiHtml.length}")

                    // sources array'ini bul
                    val sourcesMatch = Regex("""(?:const|var|let)\s+sources\s*=\s*(\[[\s\S]*?])\s*;""")
                        .find(apiHtml)
                    
                    if (sourcesMatch == null) {
                        Log.e("TRanimaci", "sources array bulunamadı!")
                        continue
                    }

                    val sourcesArrayRaw = sourcesMatch.groupValues[1]
                    Log.d("TRanimaci", "sourcesArrayRaw: ${sourcesArrayRaw.take(200)}")

                    val mp4Array = JSONArray(sourcesArrayRaw)
                    Log.d("TRanimaci", "mp4Array uzunluğu: ${mp4Array.length()}")

                    for (j in 0 until mp4Array.length()) {
                        try {
                            val mp4 = mp4Array.getJSONObject(j)
                            var videoUrl = mp4.getString("src")
                            
                            // URL'yi düzelt
                            if (!videoUrl.startsWith("http")) {
                                videoUrl = "https://api.animeuzayi.com" + videoUrl
                            }
                            
                            val quality = mp4.optInt("size", 0)
                            Log.d("TRanimaci", "Video URL: $videoUrl, Quality: $quality")

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
                } catch (e: Exception) {
                    Log.e("TRanimaci", "API $i hatası: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("TRanimaci", "parseVideoSource hatası: ${e.message}")
        }
        return false
    }

    // player_config parse et
    private suspend fun parsePlayerConfig(scriptHtml: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // player_config içindeki JSON'u bul
            val configMatch = Regex("""(?:player_config|playerConfig)\s*=\s*({[\s\S]*?});""")
                .find(scriptHtml)
            
            if (configMatch == null) {
                Log.e("TRanimaci", "player_config bulunamadı!")
                return false
            }

            val configJson = configMatch.groupValues[1]
            Log.d("TRanimaci", "configJson: ${configJson.take(200)}")

            val config = JSONObject(configJson)
            
            // sources array'ini bul
            val sources = config.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    var videoUrl = source.getString("src")
                    
                    if (videoUrl.isNotEmpty()) {
                        Log.d("TRanimaci", "player_config video URL: $videoUrl")
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} - Player",
                                url = videoUrl,
                                type = if (videoUrl.endsWith(".m3u8")) ExtractorLinkType.HLS else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                            }
                        )
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TRanimaci", "parsePlayerConfig hatası: ${e.message}")
        }
        return false
    }

    // iframe parse et
    private suspend fun parseIframe(iframeUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val response = app.get(iframeUrl)
            val iframeHtml = response.text
            Log.d("TRanimaci", "iframe içeriği uzunluğu: ${iframeHtml.length}")

            // iframe içinde video ara
            val videoPatterns = listOf(
                Regex("""(https?://[^\s"'<>]+\.mp4)"""),
                Regex("""(https?://[^\s"'<>]+\.m3u8)"""),
                Regex("""file\s*:\s*['"]([^'"]+)['"]"""),
                Regex("""src\s*:\s*['"]([^'"]+)['"]""")
            )

            for (pattern in videoPatterns) {
                val match = pattern.find(iframeHtml)
                if (match != null) {
                    var videoUrl = match.groupValues[1]
                    if (!videoUrl.startsWith("http")) {
                        videoUrl = "https:" + videoUrl
                    }
                    Log.d("TRanimaci", "iframe içinde video URL: $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - iframe",
                            url = videoUrl,
                            type = if (videoUrl.endsWith(".m3u8")) ExtractorLinkType.HLS else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                        }
                    )
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("TRanimaci", "parseIframe hatası: ${e.message}")
        }
        return false
    }
}
