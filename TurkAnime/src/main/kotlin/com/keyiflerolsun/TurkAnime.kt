// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Base64
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import java.net.URLDecoder

class TurkAnime : MainAPI() {
    override var mainUrl              = "https://www.turkanime.tv"
    override var name                 = "TurkAnime"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
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
        val home = document.select("div#orta-icerik div.panel, div#orta-icerik div.card, div.anime-card, div.anime-item").mapNotNull { 
            it.toMainPageResult() 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // Başlık için çoklu seçici
        var titleEl = this.selectFirst("div.panel-title a, div.card-title a, h3 a, h4 a, a.anime-title, div.title a")
        if (titleEl == null) {
            titleEl = this.selectFirst("a[title]")
        }
        if (titleEl == null) {
            titleEl = this.selectFirst("a[href*='/anime-']")
        }
        
        val title = titleEl?.text()?.trim() ?: return null
        val href = fixUrlNull(titleEl?.attr("href")) ?: return null
        
        // Poster için çoklu seçici
        var posterUrl = this.selectFirst("img")?.attr("data-src")
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = this.selectFirst("img")?.attr("src")
        }
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = this.selectFirst("div.poster img, div.image img, div.anime-poster img")?.attr("data-src")
        }
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = this.selectFirst("div.poster img, div.image img, div.anime-poster img")?.attr("src")
        }
        posterUrl = fixUrlNull(posterUrl)

        return newAnimeSearchResponse(title, href, TvType.Anime) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post("${mainUrl}/arama", data=mapOf("arama" to query)).document
        return document.select("div#orta-icerik div.panel, div#orta-icerik div.card, div.anime-card, div.anime-item").mapNotNull { 
            it.toMainPageResult() 
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Başlık için çoklu seçici
        var title = document.selectFirst("div#detayPaylas div.panel-title, h1.title, div.title h1, div.anime-title h1, div.detay-baslik h1")?.text()?.trim()
        if (title.isNullOrEmpty()) {
            title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
        }
        if (title.isNullOrEmpty()) return null

        // Poster için çoklu seçici
        val poster = fixUrlNull(
            document.selectFirst("div#detayPaylas div.imaj img, div.poster img, div.anime-poster img, meta[property='og:image']")?.attr("data-src") 
                ?: document.selectFirst("div#detayPaylas div.imaj img, div.poster img, div.anime-poster img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )

        val description = document.selectFirst("div#detayPaylas p.ozet, div.description, div.anime-description, meta[name='description']")?.text()?.trim()
        
        val year = document.selectFirst("div#detayPaylas a[href*='yil/'], div.detay-yil a[href*='yil/']")?.attr("href")?.substringAfter("yil/")?.toIntOrNull()
        val tags = document.select("div#detayPaylas a[href*='anime-turu'], div.tags a, div.genre a, div.kategori a").map { it.text() }

        // Bölümleri al - önce AJAX ile
        val bolumlerUrl = fixUrlNull(
            document.selectFirst("a[data-url*='ajax/bolumler'], div#bolumler a[data-url]")?.attr("data-url")
                ?: document.selectFirst("a[onclick*='bolumler']")?.attr("onclick")?.substringAfter("'")?.substringBefore("'")
        )

        val episodes = if (bolumlerUrl != null) {
            val token = document.selectFirst("meta[name='_token']")?.attr("content") 
                ?: document.selectFirst("input[name='_token']")?.attr("value")
                ?: ""

            try {
                val bolumlerDoc = app.get(
                    fixUrlNull(bolumlerUrl) ?: return null,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "token" to token
                    ),
                    cookies = mapOf("yasOnay" to "1")
                ).document

                bolumlerDoc.select("div#bolum-list li, div.bolum-list li, div.episode-list li, div.bolum-item").mapNotNull { 
                    val epHref = fixUrlNull(it.selectFirst("a[href*='/video/']")?.attr("href")) ?: return@mapNotNull null
                    val epName = it.selectFirst("span.bolumAdi, span.episode-name, .bolum-adi, .episode-title")?.text()?.trim() ?: "Bölüm"
                    val epTitle = it.selectFirst("a[href*='/video/']")?.attr("title")?.trim() ?: epName
                    val epEpisode = Regex("""(\d+)[.\s]*[Bb]?[\s]*[Öö]?[lL]?[\s]*[Uu]?[mM]?""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() 
                        ?: Regex("""(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull() 
                        ?: 1

                    newEpisode(epHref) {
                        this.name = epName
                        this.season = 1
                        this.episode = epEpisode
                    }
                }?.sortedBy { it.episode } ?: emptyList()
            } catch (e: Exception) {
                Log.e("TurkAnime", "Episode fetch error: ${e.message}")
                emptyList()
            }
        } else {
            // Doğrudan sayfadaki bölümleri al
            document.select("a[href*='/video/']").mapNotNull { 
                val href = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epName = it.text().trim().ifEmpty { "Bölüm" }
                val epEpisode = Regex("""(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                
                newEpisode(href) {
                    this.name = epName
                    this.season = 1
                    this.episode = epEpisode
                }
            }.sortedBy { it.episode }
        }

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
        }
    }

    /**
     * Video linkini çıkarmak için ana fonksiyon
     */
    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TurkAnime", "Loading links for: $data")
        
        try {
            val document = app.get(data).document
            
            // 1. Video elementini kontrol et
            val videoElement = document.selectFirst("video")
            if (videoElement != null) {
                // video source'ları kontrol et
                val sources = videoElement.select("source")
                for (source in sources) {
                    val src = source.attr("src")
                    if (src.isNotEmpty() && (src.endsWith(".m3u8") || src.contains(".m3u8"))) {
                        callback(
                            newExtractorLink(
                                name = "$name - Video",
                                source = this.name,
                                url = src,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "Referer" to data,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                            }
                        )
                        return true
                    }
                }
            }

            // 2. iframe'leri kontrol et
            val iframes = document.select("iframe")
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.isNotEmpty() && !src.contains("a-ads.com") && !src.contains("google")) {
                    // iframe içeriğini yükle
                    try {
                        val iframeDoc = app.get(src).document
                        
                        // iframe içindeki video source
                        val iframeVideo = iframeDoc.selectFirst("video source")
                        if (iframeVideo != null) {
                            val videoUrl = iframeVideo.attr("src")
                            if (videoUrl.isNotEmpty()) {
                                callback(
                                    newExtractorLink(
                                        name = "$name - iframe",
                                        source = this.name,
                                        url = videoUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = src
                                        this.quality = Qualities.Unknown.value
                                        this.headers = mapOf(
                                            "Referer" to src,
                                            "User-Agent" to "Mozilla/5.0"
                                        )
                                    }
                                )
                                return true
                            }
                        }
                        
                        // iframe içindeki data-url
                        val dataUrl = iframeDoc.selectFirst("div.artplayer-app, div#player")?.attr("data-url")
                        if (dataUrl != null && dataUrl.isNotEmpty()) {
                            callback(
                                newExtractorLink(
                                    name = "$name - Player",
                                    source = this.name,
                                    url = dataUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = src
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf(
                                        "Referer" to src,
                                        "User-Agent" to "Mozilla/5.0"
                                    )
                                }
                            )
                            return true
                        }
                    } catch (e: Exception) {
                        Log.e("TurkAnime", "iframe load error: ${e.message}")
                    }
                }
            }

            // 3. Butonlardan video dene
            val buttons = document.select("button[onclick*='IndexIcerik'], button[onclick*='ajax/videosec'], button[data-url]")
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
                        
                        // response içindeki data-url
                        val dataUrl = responseDoc.selectFirst("div.artplayer-app, div#player, div.video-player")?.attr("data-url")
                        if (dataUrl != null && dataUrl.isNotEmpty()) {
                            callback(
                                newExtractorLink(
                                    name = "$name - ${button.text().trim()}",
                                    source = this.name,
                                    url = dataUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = fullLink
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf(
                                        "Referer" to fullLink,
                                        "User-Agent" to "Mozilla/5.0"
                                    )
                                }
                            )
                            continue
                        }
                        
                        // response içindeki iframe
                        val respIframe = responseDoc.selectFirst("iframe")?.attr("src")
                        if (respIframe != null && respIframe.isNotEmpty()) {
                            try {
                                val iframeDoc = app.get(respIframe).document
                                val videoUrl = iframeDoc.selectFirst("video source")?.attr("src")
                                    ?: iframeDoc.selectFirst("div.artplayer-app")?.attr("data-url")
                                
                                if (videoUrl != null && videoUrl.isNotEmpty()) {
                                    callback(
                                        newExtractorLink(
                                            name = "$name - ${button.text().trim()}",
                                            source = this.name,
                                            url = videoUrl,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = respIframe
                                            this.quality = Qualities.Unknown.value
                                            this.headers = mapOf(
                                                "Referer" to respIframe,
                                                "User-Agent" to "Mozilla/5.0"
                                            )
                                        }
                                    )
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
}
