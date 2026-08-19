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
        Log.d("TRanimaci", "loadLinks başladı - data: $data")
        
        try {
            val document = app.get(data).document

            // 1. video_source içeren <script> etiketi
            val scriptContent = document.select("script").firstOrNull {
                it.html().contains("video_source")
            }?.html() ?: run {
                Log.e("TRanimaci", "video_source script bulunamadı!")
                return false
            }

            Log.d("TRanimaci", "scriptContent bulundu, uzunluk: ${scriptContent.length}")

            // 2. video_source içindeki JSON array'i çek
            val videoSourceMatch = Regex("""video_source\s*=\s*`(\[[\s\S]*?])`""", RegexOption.DOT_MATCHES_ALL)
                .find(scriptContent)
            
            if (videoSourceMatch == null) {
                Log.e("TRanimaci", "video_source regex eşleşmesi bulunamadı!")
                return false
            }

            val videoSourceJson = videoSourceMatch.groupValues[1]
            Log.d("TRanimaci", "videoSourceJson: $videoSourceJson")

            val videoSourceArray = JSONArray(videoSourceJson)
            Log.d("TRanimaci", "videoSourceArray uzunluğu: ${videoSourceArray.length()}")

            // 3. Her bir API URL'sine istek at
            var linkBulundu = false
            
            for (i in 0 until videoSourceArray.length()) {
                try {
                    val source = videoSourceArray.getJSONObject(i)
                    val apiUrl = source.getString("url")
                    Log.d("TRanimaci", "API URL $i: $apiUrl")

                    // 4. API sayfasını çek
                    val apiResponse = app.get(
                        apiUrl, 
                        headers = mapOf(
                            "Referer" to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                            "Connection" to "keep-alive"
                        )
                    )
                    
                    val apiHtml = apiResponse.text
                    Log.d("TRanimaci", "API HTML uzunluğu: ${apiHtml.length}")

                    // 5. const sources = [...] içeren <script> bul
                    val apiDoc = Jsoup.parse(apiHtml)
                    val sourcesScript = apiDoc.select("script").firstOrNull {
                        it.html().contains("const sources") || it.html().contains("var sources")
                    }
                    
                    if (sourcesScript == null) {
                        Log.e("TRanimaci", "sources script bulunamadı API $i")
                        continue
                    }

                    Log.d("TRanimaci", "sourcesScript bulundu, içerik: ${sourcesScript.html().take(200)}")

                    // 6. sources array'ini çek
                    val sourcesMatch = Regex("""(?:const|var|let)\s+sources\s*=\s*(\[[\s\S]*?])\s*;""")
                        .find(sourcesScript.html())
                    
                    if (sourcesMatch == null) {
                        Log.e("TRanimaci", "sources array regex eşleşmesi bulunamadı API $i")
                        continue
                    }

                    val sourcesArrayRaw = sourcesMatch.groupValues[1]
                    Log.d("TRanimaci", "sourcesArrayRaw: ${sourcesArrayRaw.take(200)}")

                    // 7. MP4 linklerini JSON olarak parse et
                    try {
                        val mp4Array = JSONArray(sourcesArrayRaw)
                        Log.d("TRanimaci", "mp4Array uzunluğu: ${mp4Array.length()}")

                        for (j in 0 until mp4Array.length()) {
                            try {
                                val mp4 = mp4Array.getJSONObject(j)
                                var videoUrl = mp4.getString("src")
                                
                                // URL'yi düzelt
                                if (!videoUrl.startsWith("http")) {
                                    if (videoUrl.startsWith("//")) {
                                        videoUrl = "https:" + videoUrl
                                    } else if (videoUrl.startsWith("/")) {
                                        videoUrl = "https://api.animeuzayi.com" + videoUrl
                                    } else {
                                        videoUrl = "https://api.animeuzayi.com/" + videoUrl
                                    }
                                }
                                
                                val quality = try {
                                    val size = mp4.optInt("size", 0)
                                    when {
                                        size >= 1080 -> Qualities.Unknown.value // 1080p
                                        size >= 720 -> Qualities.Unknown.value  // 720p
                                        size >= 480 -> Qualities.Unknown.value  // 480p
                                        else -> Qualities.Unknown.value
                                    }
                                } catch (e: Exception) {
                                    Qualities.Unknown.value
                                }

                                Log.d("TRanimaci", "Video URL bulundu: $videoUrl, quality: $quality")

                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "${this.name} - ${if (quality > 0) "${quality}p" else "SD"}",
                                        url = videoUrl,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "https://api.animeuzayi.com/"
                                        this.quality = quality
                                        this.headers = mapOf(
                                            "Referer" to mainUrl,
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                        )
                                    }
                                )
                                linkBulundu = true
                            } catch (e: Exception) {
                                Log.e("TRanimaci", "MP4 parse hatası j=$j: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TRanimaci", "JSONArray parse hatası: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.e("TRanimaci", "API $i işlenirken hata: ${e.message}")
                }
            }

            // 8. Eğer hiç link bulunamadıysa, alternatif yöntem dene
            if (!linkBulundu) {
                Log.d("TRanimaci", "Hiç link bulunamadı, alternatif yöntem deneniyor...")
                linkBulundu = tryAlternatifLinkler(document, callback)
            }

            return linkBulundu
        } catch (e: Exception) {
            Log.e("TRanimaci", "loadLinks hatası: ${e.message}", e)
            return false
        }
    }

    private suspend fun tryAlternatifLinkler(
        document: Document,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Alternatif 1: iframe içinde video olabilir
            val iframe = document.selectFirst("iframe")
            if (iframe != null) {
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.isNotEmpty()) {
                    Log.d("TRanimaci", "iframe bulundu: $iframeSrc")
                    // iframe içeriğini kontrol et
                    val iframeDoc = app.get(iframeSrc).document
                    val videoElement = iframeDoc.selectFirst("video source, video")
                    if (videoElement != null) {
                        val videoUrl = videoElement.attr("src")
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
                }
            }

            // Alternatif 2: Direkt video etiketi
            val video = document.selectFirst("video")
            if (video != null) {
                val videoUrl = video.attr("src")
                if (videoUrl.isNotEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - video",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                        }
                    )
                    return true
                }
            }

            // Alternatif 3: MP4 dosyasına direkt link
            val mp4Links = document.select("a[href$=.mp4], source[src$=.mp4]")
            for (element in mp4Links) {
                val videoUrl = element.attr("href").ifEmpty { element.attr("src") }
                if (videoUrl.isNotEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - mp4",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                        }
                    )
                    return true
                }
            }

            // Alternatif 4: player içinde gizli link
            val playerScript = document.select("script").firstOrNull {
                it.html().contains("player") || it.html().contains("video")
            }
            if (playerScript != null) {
                val urlMatch = Regex("""(https?://[^\s"'<>]+\.(?:mp4|m3u8))""").find(playerScript.html())
                if (urlMatch != null) {
                    val videoUrl = urlMatch.groupValues[1]
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - player",
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
            Log.e("TRanimaci", "Alternatif link hatası: ${e.message}")
        }
        return false
    }
}
