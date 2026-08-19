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
        // ... (önceki ana sayfa listesi aynı)
        "${mainUrl}/anime-turu/1/Aksiyon" to "Aksiyon",
        // ... diğerleri
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("div#orta-icerik div.panel, div#orta-icerik div.card").mapNotNull { 
            it.toMainPageResult() 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // ... (önceki kod aynı)
        var titleEl = this.selectFirst("div.panel-title a, div.card-title a, h3 a, h4 a")
        if (titleEl == null) {
            titleEl = this.selectFirst("a[title]")
        }
        val title = titleEl?.text()?.trim() ?: return null
        val href = fixUrlNull(titleEl?.attr("href")) ?: return null
        
        var posterUrl = this.selectFirst("img")?.attr("data-src")
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = this.selectFirst("img")?.attr("src")
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
        // ... (önceki load fonksiyonu aynı)
        val document = app.get(url).document

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
            }?.sortedBy { it.episode } ?: emptyList()
        } else {
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
        try {
            val mainDoc = app.get(mainUrl).document
            val scriptContent = mainDoc.select("script:containsData(aesKey)").firstOrNull()?.data()
            if (scriptContent != null) {
                val keyMatch = Regex("""aesKey\s*=\s*['"]([^'"]+)['"]""").find(scriptContent)
                if (keyMatch != null) {
                    return keyMatch.groupValues[1]
                }
            }
        } catch (e: Exception) {
            Log.e("TurkAnime", "AES key fetch error: ${e.message}")
        }
        return "710^8A@3@>T2}#zN5xK?kR7KNKb@-A!LzYL5~M1qU0UfdWsZoBm4UUat%}ueUv6E--*hDPPbH7K2bp9^3o41hw,khL:}Kx8080@M"
    }

    private suspend fun iframe2AesLink(iframe: String): String? {
        try {
            var aesData = iframe.substringAfter("embed/#/url/").substringBefore("?status")
            if (aesData.isEmpty()) {
                aesData = iframe.substringAfter("url=").substringBefore("&")
            }
            if (aesData.isEmpty()) return null
            
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

    /**
     * M3U8 linkini doğrula ve düzelt
     */
    private fun validateAndFixM3u8(url: String): String? {
        var fixedUrl = url.trim()
        
        // Geçersiz karakterleri temizle
        fixedUrl = fixedUrl.replace(Regex("""[\x00-\x1F\x7F]"""), "")
        
        // Eğer link .m3u8 ile bitmiyorsa dene
        if (!fixedUrl.endsWith(".m3u8") && !fixedUrl.contains(".m3u8?")) {
            // Bazı linkler .m3u8?token=... şeklinde olabilir
            if (fixedUrl.contains(".m3u8")) {
                // Geçerli
            } else {
                return null
            }
        }
        
        return fixUrlNull(fixedUrl)
    }

    private suspend fun iframe2Load(
        document: Document, 
        iframe: String, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // 1. Önce ana video linkini dene
            val mainVideo = iframe2AesLink(iframe)
            if (mainVideo != null) {
                Log.d("TurkAnime", "Main video: $mainVideo")
                
                val validatedUrl = validateAndFixM3u8(mainVideo)
                if (validatedUrl != null) {
                    callback(
                        newExtractorLink(
                            name = "$name - Video",
                            source = this.name,
                            url = validatedUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "Referer" to mainUrl,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                "Origin" to mainUrl
                            )
                        }
                    )
                    return
                }
            }

            // 2. Butonlardan dene
            val buttons = document.select("button[onclick*='ajax/videosec'], button[onclick*='IndexIcerik'], button.video-source")
            for (button in buttons) {
                val onclickAttr = button.attr("onclick")
                val butonLink = onclickAttr.substringAfter("IndexIcerik('").substringBefore("'")
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) } 
                    ?: button.attr("data-url")?.let { fixUrlNull(it) }
                    ?: continue

                Log.d("TurkAnime", "Button link: $butonLink")
                
                try {
                    val subDoc = app.get(butonLink, headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to "Mozilla/5.0"
                    )).document
                    
                    // data-url kontrolü
                    val dataUrl = subDoc.selectFirst("div.artplayer-app, div#player, div.video-player")?.attr("data-url")
                    if (dataUrl != null) {
                        val validatedDataUrl = validateAndFixM3u8(dataUrl)
                        if (validatedDataUrl != null) {
                            callback(
                                newExtractorLink(
                                    name = "$name - ${button.text().trim()}",
                                    source = this.name,
                                    url = validatedDataUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = butonLink
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf(
                                        "Referer" to butonLink,
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                        "Origin" to mainUrl
                                    )
                                }
                            )
                            continue
                        }
                    }

                    // iframe'den dene
                    val subFrame = fixUrlNull(subDoc.selectFirst("iframe")?.attr("src")) 
                        ?: subDoc.selectFirst("video source")?.attr("src")
                        ?: continue

                    if (subFrame.isNotEmpty()) {
                        val videoLink = iframe2AesLink(subFrame)
                        if (videoLink != null) {
                            val validatedVideoLink = validateAndFixM3u8(videoLink)
                            if (validatedVideoLink != null) {
                                callback(
                                    newExtractorLink(
                                        name = "$name - ${button.text().trim()}",
                                        source = this.name,
                                        url = validatedVideoLink,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = subFrame
                                        this.quality = Qualities.Unknown.value
                                        this.headers = mapOf(
                                            "Referer" to subFrame,
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                            "Origin" to mainUrl
                                        )
                                    }
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TurkAnime", "Button processing error: ${e.message}")
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

            // 1. Video source kontrolü
            val videoSource = document.selectFirst("video source")?.attr("src")
            if (videoSource != null) {
                val validated = validateAndFixM3u8(videoSource)
                if (validated != null) {
                    callback(
                        newExtractorLink(
                            name = "$name - Video",
                            source = this.name,
                            url = validated,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "Referer" to data,
                                "User-Agent" to "Mozilla/5.0"
                            )
                        }
                    )
                    return true
                }
            }

            // 2. iframe kontrolü
            val iframeElement = document.selectFirst("iframe")
            val iframe = fixUrlNull(iframeElement?.attr("src"))
                ?: document.selectFirst("div.artplayer-app")?.attr("data-url")
                ?: document.selectFirst("div#player")?.attr("data-url")

            if (iframe != null && !iframe.contains("a-ads.com")) {
                val validated = validateAndFixM3u8(iframe)
                if (validated != null) {
                    callback(
                        newExtractorLink(
                            name = "$name - M3U8",
                            source = this.name,
                            url = validated,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "Referer" to data,
                                "User-Agent" to "Mozilla/5.0"
                            )
                        }
                    )
                    return true
                }
                
                iframe2Load(document, iframe, subtitleCallback, callback)
                return true
            }

            // 3. Butonlardan dene
            val buttons = document.select("button[onclick*='IndexIcerik'], button[data-url], button.video-source")
            if (buttons.isNotEmpty()) {
                for (button in buttons) {
                    val onclickAttr = button.attr("onclick")
                    val subLink = onclickAttr.substringAfter("IndexIcerik('").substringBefore("'")
                        .takeIf { it.isNotBlank() }
                        ?.let { fixUrlNull(it) }
                        ?: button.attr("data-url")?.let { fixUrlNull(it) }
                        ?: continue

                    Log.d("TurkAnime", "Button source: $subLink")
                    
                    try {
                        val subResponse = app.get(subLink, headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "User-Agent" to "Mozilla/5.0"
                        ))
                        val subDoc = subResponse.document

                        val dataUrl = subDoc.selectFirst("div.artplayer-app, div#player, div.video-player")?.attr("data-url")
                        if (dataUrl != null) {
                            val validated = validateAndFixM3u8(dataUrl)
                            if (validated != null) {
                                callback(
                                    newExtractorLink(
                                        name = "$name - ${button.text().trim()}",
                                        source = this.name,
                                        url = validated,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = subLink
                                        this.quality = Qualities.Unknown.value
                                        this.headers = mapOf(
                                            "Referer" to subLink,
                                            "User-Agent" to "Mozilla/5.0",
                                            "Origin" to mainUrl
                                        )
                                    }
                                )
                                continue
                            }
                        }

                        val subFrame = fixUrlNull(subDoc.selectFirst("iframe")?.attr("src")) 
                            ?: subDoc.selectFirst("video source")?.attr("src")
                            ?: continue

                        iframe2Load(subDoc, subFrame, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.e("TurkAnime", "Button processing error: ${e.message}")
                    }
                }
                return true
            }

            // 4. Son çare: sayfadaki tüm linkleri tara
            val allLinks = document.select("a[href*='.m3u8']")
            for (link in allLinks) {
                val href = fixUrlNull(link.attr("href")) ?: continue
                val validated = validateAndFixM3u8(href)
                if (validated != null) {
                    callback(
                        newExtractorLink(
                            name = "$name - Link",
                            source = this.name,
                            url = validated,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "Referer" to data,
                                "User-Agent" to "Mozilla/5.0"
                            )
                        }
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
