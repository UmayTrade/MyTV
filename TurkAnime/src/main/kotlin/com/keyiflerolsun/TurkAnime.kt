// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Base64
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import org.jsoup.Jsoup

class TurkAnime : MainAPI() {
    override var mainUrl              = "https://www.turkanime.tv"
    override var name                 = "TurkAnime"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "${mainUrl}/anime-turu/1/Aksiyon" to "Aksiyon",
        "${mainUrl}/anime-turu/2/Macera" to "Macera",
        "${mainUrl}/anime-turu/4/Komedi" to "Komedi",
        "${mainUrl}/anime-turu/8/Dram" to "Dram",
        "${mainUrl}/anime-turu/10/Fantastik" to "Fantastik",
        "${mainUrl}/anime-turu/9/Ecchi" to "Ecchi",
        "${mainUrl}/anime-turu/27/Shounen" to "Shounen",
        "${mainUrl}/anime-turu/25/Shoujo" to "Shoujo",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("div.panel, div.card, div.anime-item, div.movie-item").mapNotNull { 
            it.toSearchResult() 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Farklı seçici alternatifleri
        val titleEl = this.selectFirst("a[title], div.panel-title a, div.card-title a, h3 a, h4 a, a.anime-link")
            ?: this.selectFirst("a[href*='/anime/']")
            ?: return null
            
        val title = titleEl.text().trim().ifEmpty { return null }
        val href = fixUrlNull(titleEl.attr("href")) ?: return null
        
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src") 
                ?: this.selectFirst("img")?.attr("src")
                ?: this.selectFirst("div.poster img")?.attr("data-src")
                ?: this.selectFirst("div.image img")?.attr("src")
        )

        return newAnimeSearchResponse(title, href, TvType.Anime) { 
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post("${mainUrl}/arama", data = mapOf("arama" to query)).document
        return document.select("div.panel, div.card, div.anime-item").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Başlık
        val title = document.selectFirst("h1, div.panel-title, div.title, meta[property='og:title']")?.text()?.trim() 
            ?: return null
            
        // Poster
        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.poster img, div.imaj img")?.attr("data-src")
                ?: document.selectFirst("div.poster img, div.imaj img")?.attr("src")
        )
        
        // Açıklama
        val description = document.selectFirst("div.ozet, div.description, div.summary, meta[name='description']")?.text()?.trim()
        
        // Yıl
        val year = document.selectFirst("a[href*='yil/']")?.text()?.toIntOrNull()
        
        // Türler
        val tags = document.select("a[href*='anime-turu/']").map { it.text().trim() }.filter { it.isNotEmpty() }

        // BÖLÜM LİSTESİNİ AL
        val episodes = mutableListOf<Episode>()
        
        // 1. Yöntem: Ajax ile bölüm listesi
        val ajaxUrl = document.selectFirst("a[data-url*='ajax/bolumler']")?.attr("data-url")
            ?: document.selectFirst("div#bolumler a[data-url]")?.attr("data-url")
            ?: document.selectFirst("a[onclick*='bolumler']")?.attr("onclick")?.substringAfter("'")?.substringBefore("'")
        
        if (ajaxUrl != null) {
            try {
                val token = document.selectFirst("meta[name='_token']")?.attr("content") 
                    ?: document.selectFirst("input[name='_token']")?.attr("value")
                    ?: ""
                
                val bolumDoc = app.get(
                    fixUrlNull(ajaxUrl) ?: return null,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "token" to token
                    ),
                    cookies = mapOf("yasOnay" to "1")
                ).document
                
                episodes.addAll(
                    bolumDoc.select("li, div.bolum-item, div.episode-item").mapNotNull { item ->
                        val link = item.selectFirst("a[href*='/video/']")?.attr("href")?.let { fixUrlNull(it) }
                            ?: return@mapNotNull null
                        
                        val name = item.selectFirst("span.bolumAdi, span.episode-name, span.name")?.text()?.trim() 
                            ?: "Bölüm"
                        
                        val episodeNum = Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        
                        newEpisode(link) {
                            this.name = name
                            this.season = 1
                            this.episode = episodeNum
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("TurkAnime", "Ajax bolum error: ${e.message}")
            }
        }
        
        // 2. Yöntem: Sayfadaki direkt video linkleri
        if (episodes.isEmpty()) {
            episodes.addAll(
                document.select("a[href*='/video/']").mapNotNull { link ->
                    val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                    val name = link.text().trim().ifEmpty { "Bölüm" }
                    val episodeNum = Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    
                    newEpisode(href) {
                        this.name = name
                        this.season = 1
                        this.episode = episodeNum
                    }
                }
            )
        }
        
        // 3. Yöntem: Tüm linkleri tara
        if (episodes.isEmpty()) {
            val allLinks = document.select("a[href]").map { it.attr("href") }.filter { 
                it.contains("/video/") || it.contains("/bolum/") 
            }.distinct()
            
            allLinks.forEachIndexed { index, link ->
                val href = fixUrlNull(link) ?: return@forEachIndexed
                newEpisode(href) {
                    this.name = "Bölüm ${index + 1}"
                    this.season = 1
                    this.episode = index + 1
                }.let { episodes.add(it) }
            }
        }

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.sortedBy { it.episode }) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
        }
    }

    // ==================== VİDEO LİNK BULMA ====================
    
    private suspend fun getVideoLinksFromPage(url: String): List<String> {
        val links = mutableListOf<String>()
        try {
            val doc = app.get(url).document
            
            // 1. Video source
            doc.select("video source").forEach { 
                it.attr("src").takeIf { src -> src.isNotEmpty() }?.let { links.add(it) }
            }
            
            // 2. data-url
            doc.select("[data-url]").forEach {
                it.attr("data-url").takeIf { url -> url.isNotEmpty() && url.contains(".m3u8") }?.let { links.add(it) }
            }
            
            // 3. iframe
            doc.select("iframe").forEach {
                it.attr("src").takeIf { src -> src.isNotEmpty() && !src.contains("a-ads") }?.let { links.add(it) }
            }
            
            // 4. M3U8 linkleri
            val text = doc.html()
            Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").findAll(text).forEach {
                links.add(it.value)
            }
            
            // 5. Özel linkler (turkanime CDN)
            Regex("""https?://[^\s"']+/get/[^\s"']+""").findAll(text).forEach {
                links.add(it.value)
            }
            
        } catch (e: Exception) {
            Log.e("TurkAnime", "getVideoLinks error: ${e.message}")
        }
        return links.distinct()
    }

    private suspend fun extractM3u8FromIframe(iframeUrl: String): String? {
        try {
            // AES şifreli link
            if (iframeUrl.contains("embed/#/url/")) {
                return iframe2AesLink(iframeUrl)
            }
            
            // Doğrudan M3U8
            if (iframeUrl.contains(".m3u8")) {
                return iframeUrl
            }
            
            // Iframe içeriğini al
            val doc = app.get(iframeUrl).document
            val m3u8Links = getVideoLinksFromPage(iframeUrl)
            return m3u8Links.firstOrNull { it.contains(".m3u8") }
            
        } catch (e: Exception) {
            Log.e("TurkAnime", "extractM3u8 error: ${e.message}")
            return null
        }
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
            val result = AesHelper.cryptoAESHandler(aesData, aesKey.toByteArray(), false)
                ?.replace("\\", "")
                ?.replace("\"", "")
                ?.trim()
            
            return fixUrlNull(result)
        } catch (e: Exception) {
            Log.e("TurkAnime", "AES error: ${e.message}")
            return null
        }
    }

    private suspend fun getAesKey(): String {
        try {
            val doc = app.get(mainUrl).document
            val scripts = doc.select("script")
            for (script in scripts) {
                val data = script.data()
                val match = Regex("""aesKey\s*[:=]\s*['"]([^'"]+)['"]""").find(data)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
        } catch (e: Exception) {
            Log.e("TurkAnime", "AES key error: ${e.message}")
        }
        return "710^8A@3@>T2}#zN5xK?kR7KNKb@-A!LzYL5~M1qU0UfdWsZoBm4UUat%}ueUv6E--*hDPPbH7K2bp9^3o41hw,khL:}Kx8080@M"
    }

    // ==================== ANA LİNK YÜKLEYİCİ ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TurkAnime", "loadLinks: $data")
        
        try {
            // Önce sayfadaki tüm video linklerini topla
            val allVideoLinks = getVideoLinksFromPage(data)
            Log.d("TurkAnime", "Bulunan linkler: ${allVideoLinks.size}")
            
            for (link in allVideoLinks) {
                try {
                    val videoUrl = if (link.contains("embed/#/url/")) {
                        iframe2AesLink(link)
                    } else if (link.contains(".m3u8")) {
                        link
                    } else if (link.startsWith("http") && !link.contains("a-ads")) {
                        // İçeriği kontrol et
                        val extracted = extractM3u8FromIframe(link)
                        extracted
                    } else {
                        null
                    }
                    
                    if (videoUrl != null && videoUrl.contains(".m3u8")) {
                        Log.d("TurkAnime", "Video bulundu: $videoUrl")
                        callback(
                            newExtractorLink(
                                name = name,
                                source = name,
                                url = videoUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "Referer" to data,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                    "Origin" to mainUrl
                                )
                            }
                        )
                        return true
                    }
                } catch (e: Exception) {
                    Log.e("TurkAnime", "Link işleme hatası: ${e.message}")
                }
            }
            
            // Butonlardan dene (alternatif kaynaklar)
            val doc = app.get(data).document
            val buttons = doc.select("button[onclick*='IndexIcerik'], button[data-url], a[data-url]")
            
            for (button in buttons) {
                try {
                    val onclick = button.attr("onclick")
                    var subUrl = onclick.substringAfter("IndexIcerik('").substringBefore("'")
                    if (subUrl.isEmpty()) {
                        subUrl = button.attr("data-url")
                    }
                    if (subUrl.isEmpty()) continue
                    
                    val fullUrl = fixUrlNull(subUrl) ?: continue
                    Log.d("TurkAnime", "Buton link: $fullUrl")
                    
                    val subDoc = app.get(fullUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest")).document
                    val subLinks = getVideoLinksFromPage(fullUrl)
                    
                    for (subLink in subLinks) {
                        val videoUrl = if (subLink.contains("embed/#/url/")) {
                            iframe2AesLink(subLink)
                        } else if (subLink.contains(".m3u8")) {
                            subLink
                        } else {
                            null
                        }
                        
                        if (videoUrl != null) {
                            callback(
                                newExtractorLink(
                                    name = "$name - ${button.text().trim()}",
                                    source = name,
                                    url = videoUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = fullUrl
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf(
                                        "Referer" to fullUrl,
                                        "User-Agent" to "Mozilla/5.0",
                                        "Origin" to mainUrl
                                    )
                                }
                            )
                            return true
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TurkAnime", "Buton hatası: ${e.message}")
                }
            }
            
            Log.e("TurkAnime", "Hiç video linki bulunamadı!")
            return false
            
        } catch (e: Exception) {
            Log.e("TurkAnime", "loadLinks hatası: ${e.message}")
            return false
        }
    }
}
