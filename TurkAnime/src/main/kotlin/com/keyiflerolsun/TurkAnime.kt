// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Base64
import com.lagradost.cloudstream3.extractors.helper.AesHelper

class TurkAnime : MainAPI() {
    override var mainUrl              = "https://www.turkanime.tv"
    override var name                 = "TurkAnime"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "${mainUrl}/anime-turu/1/Aksiyon"                                   to "Aksiyon",
        "${mainUrl}/anime-turu/3/Arabalar"                                  to "Arabalar",
        "${mainUrl}/anime-turu/38/Askeri"                                   to "Askeri",
        "${mainUrl}/anime-turu/5/Avangard"                                  to "Avangard",
        "${mainUrl}/anime-turu/24/Bilim_Kurgu"                              to "Bilim Kurgu",
        "${mainUrl}/anime-turu/16/B%C3%BCy%C3%BC"                           to "Büyü",
        "${mainUrl}/anime-turu/15/%C3%87ocuklar"                            to "Çocuklar",
        "${mainUrl}/anime-turu/37/Do%C4%9Fa%C3%BCst%C3%BC_G%C3%BC%C3%A7ler" to "Doğaüstü Güçler",
        "${mainUrl}/anime-turu/17/D%C3%B6v%C3%BC%C5%9F_Sanatlar%C4%B1"      to "Dövüş Sanatları",
        "${mainUrl}/anime-turu/8/Dram"                                      to "Dram",
        "${mainUrl}/anime-turu/9/Ecchi"                                     to "Ecchi",
        "${mainUrl}/anime-turu/10/Fantastik"                                to "Fantastik",
        "${mainUrl}/anime-turu/41/Gerilim"                                  to "Gerilim",
        "${mainUrl}/anime-turu/7/Gizem"                                     to "Gizem",
        "${mainUrl}/anime-turu/35/Harem"                                    to "Harem",
        "${mainUrl}/anime-turu/43/Josei"                                    to "Josei",
        "${mainUrl}/anime-turu/4/Komedi"                                    to "Komedi",
        "${mainUrl}/anime-turu/14/Korku"                                    to "Korku",
        "${mainUrl}/anime-turu/2/Macera"                                    to "Macera",
        "${mainUrl}/anime-turu/18/Mecha"                                    to "Mecha",
        "${mainUrl}/anime-turu/19/M%C3%BCzik"                               to "Müzik",
        "${mainUrl}/anime-turu/23/Okul"                                     to "Okul",
        "${mainUrl}/anime-turu/11/Oyun"                                     to "Oyun",
        "${mainUrl}/anime-turu/20/Parodi"                                   to "Parodi",
        "${mainUrl}/anime-turu/39/Polisiye"                                 to "Polisiye",
        "${mainUrl}/anime-turu/40/Psikolojik"                               to "Psikolojik",
        "${mainUrl}/anime-turu/22/Romantizm"                                to "Romantizm",
        "${mainUrl}/anime-turu/21/Samuray"                                  to "Samuray",
        "${mainUrl}/anime-turu/42/Seinen"                                   to "Seinen",
        "${mainUrl}/anime-turu/6/%C5%9Eeytanlar"                            to "Şeytanlar",
        "${mainUrl}/anime-turu/25/Shoujo"                                   to "Shoujo",
        "${mainUrl}/anime-turu/26/Shoujo_Ai"                                to "Shoujo Ai",
        "${mainUrl}/anime-turu/27/Shounen"                                  to "Shounen",
        "${mainUrl}/anime-turu/28/Shounen_Ai"                               to "Shounen Ai",
        "${mainUrl}/anime-turu/30/Spor"                                     to "Spor",
        "${mainUrl}/anime-turu/31/S%C3%BCper_G%C3%BC%C3%A7ler"              to "Süper Güçler",
        "${mainUrl}/anime-turu/13/Tarihi"                                   to "Tarihi",
        "${mainUrl}/anime-turu/29/Uzay"                                     to "Uzay",
        "${mainUrl}/anime-turu/32/Vampir"                                   to "Vampir",
        "${mainUrl}/anime-turu/33/Yaoi"                                     to "Yaoi",
        "${mainUrl}/anime-turu/36/Ya%C5%9Famdan_Kesitler"                   to "Yaşamdan Kesitler",
        "${mainUrl}/anime-turu/34/Yuri"                                     to "Yuri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        // Alternatif seçici - hem panel hem card yapısını dene
        val home = document.select("div#orta-icerik div.panel, div#orta-icerik div.card").mapNotNull { 
            it.toMainPageResult() 
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // Farklı seçici alternatifleri dene
        var titleEl = this.selectFirst("div.panel-title a, div.card-title a, h3 a, h4 a")
        if (titleEl == null) {
            titleEl = this.selectFirst("a[title]")
        }
        
        val title = titleEl?.text()?.trim() ?: return null
        val href = fixUrlNull(titleEl?.attr("href")) ?: return null
        
        // Poster için farklı seçenekler
        var posterUrl = this.selectFirst("img")?.attr("data-src")
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = this.selectFirst("img")?.attr("src")
        }
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = this.selectFirst("div.poster img, div.image img")?.attr("data-src")
        }
        posterUrl = fixUrlNull(posterUrl)

        return newAnimeSearchResponse(title, href, TvType.Anime) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post("${mainUrl}/arama", data=mapOf("arama" to query)).document
        return document.select("div#orta-icerik div.panel, div#orta-icerik div.card").mapNotNull { 
            it.toMainPageResult() 
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Başlık için farklı seçenekler
        var title = document.selectFirst("div#detayPaylas div.panel-title, h1.title, div.title h1")?.text()?.trim()
        if (title.isNullOrEmpty()) {
            title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
        }
        if (title.isNullOrEmpty()) return null

        val poster = fixUrlNull(
            document.selectFirst("div#detayPaylas div.imaj img, div.poster img, meta[property='og:image']")?.attr("data-src") 
                ?: document.selectFirst("div#detayPaylas div.imaj img, div.poster img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )

        val description = document.selectFirst("div#detayPaylas p.ozet, div.description, meta[name='description']")?.text()?.trim()
        
        val year = document.selectFirst("div#detayPaylas a[href*='yil/']")?.attr("href")?.substringAfter("yil/")?.toIntOrNull()
        val tags = document.select("div#detayPaylas a[href*='anime-turu'], div.tags a, div.genre a").map { it.text() }

        // Bölümleri al
        val bolumlerUrl = fixUrlNull(
            document.selectFirst("a[data-url*='ajax/bolumler'], div#bolumler a[data-url]")?.attr("data-url")
                ?: document.selectFirst("a[onclick*='bolumler']")?.attr("onclick")?.substringAfter("'")?.substringBefore("'")
        )

        val episodes = if (bolumlerUrl != null) {
            val token = document.selectFirst("meta[name='_token']")?.attr("content") 
                ?: document.selectFirst("input[name='_token']")?.attr("value")
                ?: ""

            val bolumlerDoc = app.get(
                fixUrlNull(bolumlerUrl) ?: return null,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "token" to token
                ),
                cookies = mapOf("yasOnay" to "1")
            ).document

            bolumlerDoc.select("div#bolum-list li, div.bolum-list li, div.episode-list li").mapNotNull { 
                val epHref = fixUrlNull(it.selectFirst("a[href*='/video/']")?.attr("href")) ?: return@mapNotNull null
                val epName = it.selectFirst("span.bolumAdi, span.episode-name, .bolum-adi")?.text()?.trim() ?: "Bölüm"
                val epTitle = it.selectFirst("a[href*='/video/']")?.attr("title")?.trim() ?: epName
                val epEpisode = Regex("""(\d+)[.\s]*[Bb]?[\s]*[Öö]?[lL]?[\s]*[Uu]?[mM]?""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() 
                    ?: Regex("""(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull() 
                    ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = 1
                    this.episode = epEpisode
                }
            }?.sortedBy { it.episode }
        } else {
            // Alternatif: sayfadaki tüm video linklerini topla
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

    private suspend fun getAesKey(): String {
        // Ana sayfadan güncel AES anahtarını al
        val mainDoc = app.get(mainUrl).document
        val scriptContent = mainDoc.select("script:containsData(aesKey)").firstOrNull()?.data()
        if (scriptContent != null) {
            val keyMatch = Regex("""aesKey\s*=\s*['"]([^'"]+)['"]""").find(scriptContent)
            if (keyMatch != null) {
                return keyMatch.groupValues[1]
            }
        }
        // Fallback - mevcut anahtar
        return "710^8A@3@>T2}#zN5xK?kR7KNKb@-A!LzYL5~M1qU0UfdWsZoBm4UUat%}ueUv6E--*hDPPbH7K2bp9^3o41hw,khL:}Kx8080@M"
    }

    private suspend fun iframe2AesLink(iframe: String): String? {
        try {
            var aesData = iframe.substringAfter("embed/#/url/").substringBefore("?status")
            if (aesData.isEmpty()) {
                aesData = iframe.substringAfter("url=").substringBefore("&")
            }
            if (aesData.isEmpty()) return null
            
            // URL decode işlemi
            aesData = java.net.URLDecoder.decode(aesData, "UTF-8")
            aesData = String(Base64.decode(aesData, Base64.DEFAULT))

            val aesKey = getAesKey()
            val aesLink = AesHelper.cryptoAESHandler(aesData, aesKey.toByteArray(), false)
                ?.replace("\\", "")
                ?.replace("\"", "")
                ?.trim()
            
            return fixUrlNull(aesLink)
        } catch (e: Exception) {
            Log.e("TurkAnime", "AES decrypt error: ${e.message}")
            return null
        }
    }

    private suspend fun iframe2Load(
        document: Document, 
        @Suppress("UNUSED_PARAMETER") iframe: String, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Ana video linkini dene
            val mainVideo = iframe2AesLink(iframe)
            if (mainVideo != null) {
                Log.d("TurkAnime", "Main video: $mainVideo")
                
                // Doğrudan M3U8 ise
                if (mainVideo.endsWith(".m3u8")) {
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "$name - Video",
                            url = mainVideo,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true,
                            headers = mapOf("Referer" to mainUrl)
                        )
                    )
                    return
                }
                
                // M3U8 linkini API'den al
                val mainKey = mainVideo.split("/").lastOrNull()
                if (mainKey != null) {
                    try {
                        val mainAPI = app.get(
                            "${mainUrl}/sources/${mainKey}/true",
                            headers = mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Accept" to "application/json"
                            ),
                            referer = mainVideo,
                            cookies = mapOf("yasOnay" to "1")
                        ).text

                        val m3uLink = Regex("""file\"?\s*:\s*\"([^\"]+)""").find(mainAPI)?.groupValues?.get(1)
                            ?.replace("\\", "")
                            ?.trim()
                        
                        if (m3uLink != null) {
                            callback(
                                ExtractorLink(
                                    source = this.name,
                                    name = "$name - Source",
                                    url = m3uLink,
                                    referer = mainVideo,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = true,
                                    headers = mapOf(
                                        "Referer" to mainVideo,
                                        "Origin" to mainUrl
                                    )
                                )
                            )
                            return
                        }
                    } catch (e: Exception) {
                        Log.e("TurkAnime", "API error: ${e.message}")
                    }
                }
            }

            // Butonlardan video dene
            val buttons = document.select("button[onclick*='ajax/videosec'], button[onclick*='IndexIcerik']")
            for (button in buttons) {
                val onclickAttr = button.attr("onclick")
                val butonLink = onclickAttr.substringAfter("IndexIcerik('").substringBefore("'")
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) } 
                    ?: continue

                Log.d("TurkAnime", "Button link: $butonLink")
                
                val subDoc = app.get(butonLink, headers = mapOf("X-Requested-With" to "XMLHttpRequest")).document
                
                // data-url kontrolü
                val dataUrl = subDoc.selectFirst("div.artplayer-app, div#player, div.video-player")?.attr("data-url")
                if (dataUrl != null && dataUrl.endsWith(".m3u8")) {
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "$name - ${button.text().trim()}",
                            url = dataUrl,
                            referer = butonLink,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true,
                            headers = mapOf(
                                "Referer" to butonLink,
                                "Origin" to mainUrl
                            )
                        )
                    )
                    continue
                }

                // iframe'den dene
                val subFrame = fixUrlNull(subDoc.selectFirst("iframe")?.attr("src")) 
                    ?: subDoc.selectFirst("video source")?.attr("src")
                    ?: continue

                if (subFrame.isNotEmpty()) {
                    val videoLink = iframe2AesLink(subFrame)
                    if (videoLink != null) {
                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = "$name - ${button.text().trim()}",
                                url = videoLink,
                                referer = subFrame,
                                quality = Qualities.Unknown.value,
                                isM3u8 = true,
                                headers = mapOf(
                                    "Referer" to subFrame,
                                    "Origin" to mainUrl
                                )
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TurkAnime", "iframe2Load error: ${e.message}")
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TurkAnime", "Loading: $data")
        
        try {
            val document = app.get(data).document

            // Önce video elementlerini kontrol et
            val videoSource = document.selectFirst("video source")?.attr("src")
            if (videoSource != null && videoSource.endsWith(".m3u8")) {
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "$name - Video",
                        url = videoSource,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true,
                        headers = mapOf("Referer" to data)
                    )
                )
                return true
            }

            // iframe'leri kontrol et
            val iframeElement = document.selectFirst("iframe")
            val iframe = fixUrlNull(iframeElement?.attr("src"))
                ?: document.selectFirst("div.artplayer-app")?.attr("data-url")
                ?: document.selectFirst("div#player")?.attr("data-url")

            if (iframe != null && !iframe.contains("a-ads.com")) {
                // Doğrudan M3U8 ise
                if (iframe.endsWith(".m3u8")) {
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "$name - M3U8",
                            url = iframe,
                            referer = data,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true,
                            headers = mapOf("Referer" to data)
                        )
                    )
                    return true
                }
                
                iframe2Load(document, iframe, subtitleCallback, callback)
                return true
            }

            // Butonlardan dene
            val buttons = document.select("button[onclick*='IndexIcerik'], button[data-url], button.video-source")
            if (buttons.isNotEmpty()) {
                for (button in buttons) {
                    val onclickAttr = button.attr("onclick")
                    val subLink = onclickAttr.substringAfter("IndexIcerik('").substringBefore("'")
                        .takeIf { it.isNotBlank() }
                        ?.let { fixUrlNull(it) }
                        ?: button.attr("data-url")
                        ?.let { fixUrlNull(it) }
                        ?: continue

                    Log.d("TurkAnime", "Button source: $subLink")
                    
                    val subResponse = app.get(subLink, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
                    val subDoc = subResponse.document

                    // Önce data-url kontrolü
                    val dataUrl = subDoc.selectFirst("div.artplayer-app, div#player, div.video-player")?.attr("data-url")
                    if (dataUrl != null && dataUrl.endsWith(".m3u8")) {
                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = "$name - ${button.text().trim()}",
                                url = dataUrl,
                                referer = subLink,
                                quality = Qualities.Unknown.value,
                                isM3u8 = true,
                                headers = mapOf(
                                    "Referer" to subLink,
                                    "Origin" to mainUrl
                                )
                            )
                        )
                        continue
                    }

                    // iframe'den dene
                    val subFrame = fixUrlNull(subDoc.selectFirst("iframe")?.attr("src")) 
                        ?: subDoc.selectFirst("video source")?.attr("src")
                        ?: continue

                    iframe2Load(subDoc, subFrame, subtitleCallback, callback)
                }
                return true
            }

            // Son çare: sayfadaki tüm linkleri tara
            val allLinks = document.select("a[href*='.m3u8'], a[href*='/video/']")
            for (link in allLinks) {
                val href = fixUrlNull(link.attr("href")) ?: continue
                if (href.endsWith(".m3u8")) {
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "$name - Link",
                            url = href,
                            referer = data,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true,
                            headers = mapOf("Referer" to data)
                        )
                    )
                }
            }

        } catch (e: Exception) {
            Log.e("TurkAnime", "loadLinks error: ${e.message}")
            return false
        }

        return true
    }
}
