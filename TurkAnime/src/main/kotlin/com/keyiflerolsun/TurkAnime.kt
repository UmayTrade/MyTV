// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class TurkAnime : MainAPI() {
    override var mainUrl              = "https://www.turkanime.tv"
    override var name                 = "TurkAnime"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "${mainUrl}/anime-turu/1/Aksiyon" to "Aksiyon",
        "${mainUrl}/anime-turu/3/Arabalar" to "Arabalar",
        "${mainUrl}/anime-turu/38/Askeri" to "Askeri",
        "${mainUrl}/anime-turu/5/Avangard" to "Avangard",
        "${mainUrl}/anime-turu/24/Bilim_Kurgu" to "Bilim Kurgu",
        "${mainUrl}/anime-turu/16/B%C3%BCy%C3%BC" to "Büyü",
        "${mainUrl}/anime-turu/15/%C3%87ocuklar" to "Çocuklar",
        "${mainUrl}/anime-turu/37/Do%C4%9Fa%C3%BCst%C3%BC_G%C3%BC%C3%A7ler" to "Doğaüstü Güçler",
        "${mainUrl}/anime-turu/17/D%C3%B6v%C3%BC%C5%9F_Sanatlar%C4%B1" to "Dövüş Sanatları",
        "${mainUrl}/anime-turu/8/Dram" to "Dram",
        "${mainUrl}/anime-turu/9/Ecchi" to "Ecchi",
        "${mainUrl}/anime-turu/10/Fantastik" to "Fantastik",
        "${mainUrl}/anime-turu/41/Gerilim" to "Gerilim",
        "${mainUrl}/anime-turu/7/Gizem" to "Gizem",
        "${mainUrl}/anime-turu/35/Harem" to "Harem",
        "${mainUrl}/anime-turu/43/Josei" to "Josei",
        "${mainUrl}/anime-turu/4/Komedi" to "Komedi",
        "${mainUrl}/anime-turu/14/Korku" to "Korku",
        "${mainUrl}/anime-turu/2/Macera" to "Macera",
        "${mainUrl}/anime-turu/18/Mecha" to "Mecha",
        "${mainUrl}/anime-turu/19/M%C3%BCzik" to "Müzik",
        "${mainUrl}/anime-turu/23/Okul" to "Okul",
        "${mainUrl}/anime-turu/11/Oyun" to "Oyun",
        "${mainUrl}/anime-turu/20/Parodi" to "Parodi",
        "${mainUrl}/anime-turu/39/Polisiye" to "Polisiye",
        "${mainUrl}/anime-turu/40/Psikolojik" to "Psikolojik",
        "${mainUrl}/anime-turu/22/Romantizm" to "Romantizm",
        "${mainUrl}/anime-turu/21/Samuray" to "Samuray",
        "${mainUrl}/anime-turu/42/Seinen" to "Seinen",
        "${mainUrl}/anime-turu/6/%C5%9Eeytanlar" to "Şeytanlar",
        "${mainUrl}/anime-turu/25/Shoujo" to "Shoujo",
        "${mainUrl}/anime-turu/26/Shoujo_Ai" to "Shoujo Ai",
        "${mainUrl}/anime-turu/27/Shounen" to "Shounen",
        "${mainUrl}/anime-turu/28/Shounen_Ai" to "Shounen Ai",
        "${mainUrl}/anime-turu/30/Spor" to "Spor",
        "${mainUrl}/anime-turu/31/S%C3%BCper_G%C3%BC%C3%A7ler" to "Süper Güçler",
        "${mainUrl}/anime-turu/13/Tarihi" to "Tarihi",
        "${mainUrl}/anime-turu/29/Uzay" to "Uzay",
        "${mainUrl}/anime-turu/32/Vampir" to "Vampir",
        "${mainUrl}/anime-turu/33/Yaoi" to "Yaoi",
        "${mainUrl}/anime-turu/36/Ya%C5%9Famdan_Kesitler" to "Yaşamdan Kesitler",
        "${mainUrl}/anime-turu/34/Yuri" to "Yuri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("div#orta-icerik div.panel, div.anime-list div.item, div.anime-item, div.card").mapNotNull { 
            it.toMainPageResult() 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // Başlık bul
        var titleEl = this.selectFirst("div.panel-title a, h3 a, h4 a, a.anime-link, a.title-link")
        if (titleEl == null) {
            titleEl = this.selectFirst("a[href*='/anime-']")
        }
        
        val title = titleEl?.text()?.trim() ?: return null
        val href = fixUrlNull(titleEl?.attr("href")) ?: return null
        
        // Poster bul
        var posterUrl = this.selectFirst("img")?.attr("data-src")
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = this.selectFirst("img")?.attr("src")
        }
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = this.selectFirst("div.poster img")?.attr("data-src")
        }
        posterUrl = fixUrlNull(posterUrl)

        return newAnimeSearchResponse(title, href, TvType.Anime) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val document = app.post("${mainUrl}/arama", data=mapOf("arama" to query)).document
            return document.select("div#orta-icerik div.panel, div.anime-list div.item, div.anime-item").mapNotNull { 
                it.toMainPageResult() 
            }
        } catch (e: Exception) {
            Log.e("TurkAnime", "Search error: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = app.get(url).document

            // Başlık
            var title = document.selectFirst("div#detayPaylas div.panel-title, h1, div.title, .anime-title")?.text()?.trim()
            if (title.isNullOrEmpty()) {
                title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            }
            if (title.isNullOrEmpty()) return null

            // Poster
            val poster = fixUrlNull(
                document.selectFirst("div#detayPaylas div.imaj img, div.poster img, meta[property='og:image']")?.attr("data-src") 
                    ?: document.selectFirst("div#detayPaylas div.imaj img, div.poster img")?.attr("src")
                    ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            )

            // Açıklama
            val description = document.selectFirst("div#detayPaylas p.ozet, div.description, meta[name='description']")?.text()?.trim()
            
            // Yıl
            val year = document.selectFirst("a[href*='yil/']")?.attr("href")?.substringAfter("yil/")?.toIntOrNull()
            
            // Türler
            val tags = document.select("a[href*='anime-turu']").map { it.text() }

            // Bölümleri al
            val episodes = getEpisodes(document, url)

            if (episodes.isEmpty()) return null

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }
        } catch (e: Exception) {
            Log.e("TurkAnime", "Load error: ${e.message}")
            return null
        }
    }

    private suspend fun getEpisodes(document: Document, url: String): List<Episode> {
        // 1. Yöntem: AJAX ile bölümleri getir
        val bolumlerUrl = document.selectFirst("a[data-url*='bolumler']")?.attr("data-url")
        
        if (bolumlerUrl != null) {
            try {
                val token = document.selectFirst("meta[name='_token']")?.attr("content") ?: ""
                val bolumlerDoc = app.get(
                    fixUrlNull(bolumlerUrl) ?: return emptyList(),
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "token" to token
                    ),
                    cookies = mapOf("yasOnay" to "1")
                ).document

                val episodeElements = bolumlerDoc.select("li a[href*='/video/']")
                if (episodeElements.isNotEmpty()) {
                    return episodeElements.mapNotNull { 
                        val href = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                        val name = it.select("span.bolumAdi").text().trim()
                            .ifEmpty { it.text().trim() }
                            .ifEmpty { "Bölüm" }
                        
                        val episodeNum = Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        
                        newEpisode(href) {
                            this.name = name
                            this.season = 1
                            this.episode = episodeNum
                        }
                    }.sortedBy { it.episode }
                }
            } catch (e: Exception) {
                Log.e("TurkAnime", "AJAX episode error: ${e.message}")
            }
        }

        // 2. Yöntem: Sayfadaki bölüm linklerini al
        val episodeLinks = document.select("a[href*='/video/']")
        if (episodeLinks.isNotEmpty()) {
            return episodeLinks.mapNotNull { 
                val href = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val name = it.text().trim().ifEmpty { "Bölüm" }
                val episodeNum = Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                
                newEpisode(href) {
                    this.name = name
                    this.season = 1
                    this.episode = episodeNum
                }
            }.sortedBy { it.episode }
        }

        return emptyList()
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TurkAnime", "Loading links: $data")
        
        try {
            val document = app.get(data).document
            
            // 1. Video source'ları kontrol et
            val videoSources = document.select("video source")
            for (source in videoSources) {
                val src = source.attr("src")
                if (src.isNotEmpty() && src.contains(".m3u8")) {
                    callback(createExtractorLink(src, data, "Video"))
                    return true
                }
            }

            // 2. Video elementinin kendisini kontrol et
            val videoElement = document.selectFirst("video")
            if (videoElement != null) {
                val src = videoElement.attr("src")
                if (src.isNotEmpty() && src.contains(".m3u8")) {
                    callback(createExtractorLink(src, data, "Video"))
                    return true
                }
            }

            // 3. data-url kontrolü
            val dataUrl = document.selectFirst("div.artplayer-app, div#player, div.video-player, div.player")?.attr("data-url")
            if (dataUrl != null && dataUrl.isNotEmpty() && dataUrl.contains(".m3u8")) {
                callback(createExtractorLink(dataUrl, data, "Player"))
                return true
            }

            // 4. iframe'leri kontrol et
            val iframes = document.select("iframe")
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.isNotEmpty() && !src.contains("a-ads.com") && !src.contains("google.com")) {
                    // iframe içeriğini yükle
                    try {
                        val iframeDoc = app.get(src).document
                        
                        // iframe içindeki video source
                        val iframeVideo = iframeDoc.selectFirst("video source")
                        if (iframeVideo != null) {
                            val videoUrl = iframeVideo.attr("src")
                            if (videoUrl.isNotEmpty() && videoUrl.contains(".m3u8")) {
                                callback(createExtractorLink(videoUrl, src, "iframe"))
                                return true
                            }
                        }
                        
                        // iframe içindeki data-url
                        val iframeDataUrl = iframeDoc.selectFirst("div.artplayer-app, div#player")?.attr("data-url")
                        if (iframeDataUrl != null && iframeDataUrl.isNotEmpty() && iframeDataUrl.contains(".m3u8")) {
                            callback(createExtractorLink(iframeDataUrl, src, "Player"))
                            return true
                        }
                    } catch (e: Exception) {
                        Log.e("TurkAnime", "iframe load error: ${e.message}")
                    }
                }
            }

            // 5. Butonlardan video dene
            val buttons = document.select("button[onclick*='IndexIcerik'], button[data-url], button[onclick*='video']")
            for (button in buttons) {
                val onclick = button.attr("onclick")
                var link = onclick.substringAfter("IndexIcerik('").substringBefore("'")
                    .takeIf { it.isNotBlank() }
                
                if (link == null) {
                    link = button.attr("data-url")
                }
                
                if (link != null) {
                    val fullLink = fixUrlNull(link) ?: continue
                    Log.d("TurkAnime", "Button link: $fullLink")
                    
                    try {
                        val response = app.get(fullLink, headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest"
                        ))
                        val responseDoc = response.document
                        
                        // Response içindeki video source
                        val respVideo = responseDoc.selectFirst("video source")
                        if (respVideo != null) {
                            val videoUrl = respVideo.attr("src")
                            if (videoUrl.isNotEmpty() && videoUrl.contains(".m3u8")) {
                                callback(createExtractorLink(videoUrl, fullLink, button.text().trim()))
                                continue
                            }
                        }
                        
                        // Response içindeki data-url
                        val respDataUrl = responseDoc.selectFirst("div.artplayer-app, div#player")?.attr("data-url")
                        if (respDataUrl != null && respDataUrl.isNotEmpty() && respDataUrl.contains(".m3u8")) {
                            callback(createExtractorLink(respDataUrl, fullLink, button.text().trim()))
                            continue
                        }
                        
                        // Response içindeki iframe
                        val respIframe = responseDoc.selectFirst("iframe")?.attr("src")
                        if (respIframe != null && respIframe.isNotEmpty()) {
                            try {
                                val iframeDoc = app.get(respIframe).document
                                val videoUrl = iframeDoc.selectFirst("video source")?.attr("src")
                                    ?: iframeDoc.selectFirst("div.artplayer-app")?.attr("data-url")
                                
                                if (videoUrl != null && videoUrl.isNotEmpty() && videoUrl.contains(".m3u8")) {
                                    callback(createExtractorLink(videoUrl, respIframe, button.text().trim()))
                                }
                            } catch (e: Exception) {
                                Log.e("TurkAnime", "iframe from button error: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TurkAnime", "Button request error: ${e.message}")
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("TurkAnime", "loadLinks error: ${e.message}")
            e.printStackTrace()
            return false
        }

        return true
    }

    private fun createExtractorLink(url: String, referer: String, name: String): ExtractorLink {
        return newExtractorLink(
            name = "$name - TurkAnime",
            source = this.name,
            url = url,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                "Connection" to "keep-alive"
            )
        }
    }
}
