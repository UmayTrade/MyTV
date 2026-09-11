// ! Bu araç @SAKLImavi tarafından | @UmayTrade için yazılmıştır. (Star TV için uyarlanmıştır)

package com.UmayTrade

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.util.*

class StarTv : MainAPI() {
    override var mainUrl              = "https://www.startv.com.tr"
    override var name                 = "Star TV"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Live)

    private var allContentCache: List<SearchResponse> = emptyList()
    private var cacheTime: Long = 0
    private val cacheValidityDuration = 30 * 60 * 1000

    // Star TV'de kullanılan header'lar (403 almamak için)
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "https://www.startv.com.tr/"
    )

    // Sistem sayfaları - dizi/program olarak gösterilmemeli
    private val systemPages = setOf(
        "diziler", "programlar", "yayin-akisi", "canli-yayin",
        "haberler", "haber", "arama", "kunye", "iletisim",
        "gizlilik-bildirimi", "veri-politikasi", "site-haritasi",
        "rss-bilgi", "filmler", "fragmanlar", "ekstralar",
        "foto-galeriler", "oyuncular", "kadro", "bolumler"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler"    to "Diziler",
        "${mainUrl}/programlar" to "Programlar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()

        // ★ 1. Ana sayfadaki menüden linkleri al
        try {
            val mainDoc = app.get(mainUrl, headers = headers).document
            val menuSelector = if (request.name == "Diziler") {
                "div.series-drop .sub-menu-list li a[href], nav a[href*='/dizi/']"
            } else {
                "div.program-drop-menu .sub-menu-list li a[href], nav a[href*='/program/']"
            }
            mainDoc.select(menuSelector).forEach { element ->
                element.toMenuItemResult()?.let { results.add(it) }
            }
            Log.d("StarTV", "Menüden ${results.size} öğe alındı")
        } catch (e: Exception) {
            Log.e("StarTV", "Menü çekme hatası: ${e.message}")
        }

        // ★ 2. Liste sayfasından da kart linklerini al
        try {
            val listDoc = app.get(request.data, headers = headers).document
            listDoc.select("a[href*='/dizi/'], a[href*='/program/']").forEach { element ->
                element.toListPageResult()?.let { results.add(it) }
            }
            Log.d("StarTV", "Liste sayfasından toplam ${results.size} öğe alındı")
        } catch (e: Exception) {
            Log.e("StarTV", "Liste sayfası hatası: ${e.message}")
        }

        val uniqueResults = results.distinctBy { it.url }
        Log.d("StarTV", "getMainPage: ${request.name} -> ${uniqueResults.size} sonuç")

        return newHomePageResponse(
            listOf(HomePageList(request.name, uniqueResults))
        )
    }

    private fun Element.toMenuItemResult(): SearchResponse? {
        val hrefRaw = this.attr("href")
        if (hrefRaw.isBlank()) return null

        val fullUrl = fixUrlNull(hrefRaw) ?: return null
        if (!fullUrl.contains("startv.com.tr")) return null

        // ★ Star TV URL yapısı: /dizi/tuzlu-kahve veya /program/...
        val path = normalizePath(fullUrl) ?: return null
        
        // Path: dizi/tuzlu-kahve formatında olmalı
        if (!path.startsWith("dizi/") && !path.startsWith("program/")) return null
        
        // Alt sayfaları filtrele (fragmanlar, bolumler, ekstralar vb.)
        val segments = path.split("/")
        if (segments.size != 2) return null  // Sadece /dizi/slug veya /program/slug
        
        val slug = segments[1]
        if (systemPages.contains(slug)) return null

        val title = this.text().trim().takeIf { it.isNotEmpty() } ?: return null

        // Menüde poster genelde kardeş <img>'de
        val poster = this.parent()?.selectFirst("img")?.let { img ->
            fixUrlNull(img.attr("data-src").ifEmpty { img.attr("src") })
        }

        return newMovieSearchResponse(title, fullUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun Element.toListPageResult(): SearchResponse? {
        val hrefRaw = this.attr("href")
        if (hrefRaw.isBlank()) return null

        val fullUrl = fixUrlNull(hrefRaw) ?: return null
        if (!fullUrl.contains("startv.com.tr")) return null

        val path = normalizePath(fullUrl) ?: return null

        // ★ SADECE /dizi/slug veya /program/slug formatında olmalı
        val segments = path.split("/")
        if (segments.size != 2) return null
        if (!segments[0].startsWith("dizi") && !segments[0].startsWith("program")) return null
        
        val slug = segments[1]
        if (systemPages.contains(slug)) return null

        // Resim şart (kart olduğunu doğrular)
        val img = this.selectFirst("img") ?: return null

        // Başlık
        val title = this.selectFirst("figcaption p, figcaption .title, h2, h3, .title, .caption")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: img.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        // Poster
        val poster = fixUrlNull(
            img.attr("data-src").ifEmpty {
                img.attr("src").ifEmpty { img.attr("data-lazy-src") }
            }
        )

        Log.d("StarTV", "  ✓ [$path] → $title")

        return newMovieSearchResponse(title, fullUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    /**
     * URL'yi normalize edip path döndürür.
     * Örn: "https://www.startv.com.tr/dizi/tuzlu-kahve" -> "dizi/tuzlu-kahve"
     */
    private fun normalizePath(url: String): String? {
        var path = url
        path = path.replace("https://www.startv.com.tr", "")
        path = path.replace("https://startv.com.tr", "")
        path = path.replace("http://www.startv.com.tr", "")
        path = path.replace("http://startv.com.tr", "")
        path = path.substringBefore("?").substringBefore("#")
        path = path.trim('/')

        if (path.isEmpty()) return null
        if (path.contains(".")) return null  // .jpg, .mp4 vs.
        if (path.length < 2) return null

        return path
    }

    private suspend fun getAllContent(): List<SearchResponse> {
        val currentTime = System.currentTimeMillis()
        if (allContentCache.isNotEmpty() && (currentTime - cacheTime) < cacheValidityDuration) {
            return allContentCache
        }

        val allContent = mutableListOf<SearchResponse>()
        try {
            val pagesToScan = listOf("${mainUrl}/diziler", "${mainUrl}/programlar")
            for (pageUrl in pagesToScan) {
                try {
                    val document = app.get(pageUrl, headers = headers).document
                    document.select("a[href*='/dizi/'], a[href*='/program/']").forEach { element ->
                        element.toListPageResult()?.let { allContent.add(it) }
                    }
                } catch (e: Exception) {
                    Log.e("StarTV", "Sayfa çekme hatası ($pageUrl): ${e.message}")
                }
            }

            // Menüden de ekle
            try {
                val mainDoc = app.get(mainUrl, headers = headers).document
                mainDoc.select("nav a[href*='/dizi/'], nav a[href*='/program/']")
                    .forEach { element ->
                        element.toMenuItemResult()?.let { allContent.add(it) }
                    }
            } catch (e: Exception) {
                Log.e("StarTV", "Menü çekme hatası: ${e.message}")
            }

            val uniqueContent = allContent.distinctBy { it.url }
            allContentCache = uniqueContent
            cacheTime = currentTime
            Log.d("StarTV", "getAllContent: ${uniqueContent.size} öğe önbelleğe alındı")
            return uniqueContent
        } catch (e: Exception) {
            Log.e("StarTV", "İçerik toplanırken hata: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val allContent = getAllContent()
        val searchQuery = query.lowercase(Locale.getDefault())
        return allContent.filter {
            it.name.lowercase(Locale.getDefault()).contains(searchQuery)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        // ★ Star TV'de dizi/program sayfası mı yoksa video sayfası mı?
        val path = normalizePath(url) ?: return null
        
        // Dizi detay sayfası: /dizi/slug
        if (path.startsWith("dizi/") && path.split("/").size == 2) {
            val title = document.selectFirst("h1")?.text()?.trim() ?: return null
            val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

            val episodes = getEpisodes(document, url)

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
        
        // Video/bölüm sayfası - tek bölüm olarak işle
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val episode = newEpisode(url) {
            this.name = title
            this.episode = 1
            this.posterUrl = poster
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOfNotNull(episode)) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private suspend fun getEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()
        try {
            // ★ Star TV bölüm linkleri: /dizi/tuzlu-kahve/1-bolum
            val episodeLinks = document.select("a[href*='/bolum']")
                .filter { element ->
                    val href = element.attr("href")
                    // "bolum" içermeli ve "-fragman" içermemeli
                    href.contains("bolum") && !href.contains("fragman")
                }

            if (episodeLinks.isNotEmpty()) {
                Log.d("StarTV", "Statik ${episodeLinks.size} bölüm linki bulundu")
                episodeLinks.distinctBy { it.attr("href") }.forEachIndexed { index, element ->
                    val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed

                    // Bölüm adını al
                    val epName = element.selectFirst(".style-01, .style-02, h3, .title, .date")
                        ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: element.text().trim().takeIf { it.isNotEmpty() }
                        ?: "Bölüm ${index + 1}"

                    // Bölüm numarasını URL'den çıkarmaya çalış
                    val epNum = Regex("/(\\d+)-bolum").find(href)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (index + 1)

                    newEpisode(href) {
                        this.name = epName
                        this.episode = epNum
                    }?.let { allEpisodes.add(it) }
                }

                // Bölüm numarasına göre sırala
                return allEpisodes.sortedBy { it.episode }
            }

            // ★ Alternatif: AJAX endpoint'lerini dene
            val slug = baseUrl.substringAfter(mainUrl).trim('/').substringBefore("/")
            val ajaxUrls = listOf(
                "$mainUrl/ajax/series/$slug/episodes",
                "$mainUrl/api/series/$slug/episodes"
            )

            for (ajaxUrl in ajaxUrls) {
                try {
                    val response = app.get(
                        ajaxUrl,
                        headers = headers + mapOf(
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    )
                    val doc = response.document
                    val links = doc.select("a[href*='/bolum']")
                        .filter { !it.attr("href").contains("fragman") }
                    if (links.isNotEmpty()) {
                        Log.d("StarTV", "AJAX'den ${links.size} bölüm bulundu: $ajaxUrl")
                        links.distinctBy { it.attr("href") }.forEachIndexed { index, element ->
                            val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed
                            val epName = element.selectFirst(".style-01, .style-02, h3, .title")
                                ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                                ?: "Bölüm ${index + 1}"
                            val epNum = Regex("/(\\d+)-bolum").find(href)?.groupValues?.get(1)?.toIntOrNull()
                                ?: (index + 1)

                            newEpisode(href) {
                                this.name = epName
                                this.episode = epNum
                            }?.let { allEpisodes.add(it) }
                        }
                        if (allEpisodes.isNotEmpty()) return allEpisodes.sortedBy { it.episode }
                    }
                } catch (e: Exception) {
                    Log.d("StarTV", "AJAX denemesi başarısız ($ajaxUrl): ${e.message}")
                }
            }

            return emptyList()
        } catch (e: Exception) {
            Log.e("StarTV", "Bölüm çekme hatası: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StarTV", "Video data: $data")
        try {
            if (data.isBlank()) return false

            val document = app.get(data, headers = headers).document
            var found = false

            // ★ Öncelik 1: JSON-LD VideoObject > contentUrl
            val ldJsonScripts = document.select("script[type=application/ld+json]")
            for (script in ldJsonScripts) {
                val content = script.data()
                if (!content.contains("VideoObject") && !content.contains("contentUrl")) continue
                try {
                    val json = JSONObject(content)
                    if (json.optString("@type").contains("VideoObject")) {
                        val contentUrl = json.optString("contentUrl", "")
                        if (contentUrl.isNotEmpty() && contentUrl.startsWith("http")) {
                            Log.d("StarTV", "JSON-LD contentUrl bulundu: $contentUrl")
                            callback.invoke(
                                newExtractorLink(
                                    name = this.name,
                                    source = this.name,
                                    url = contentUrl,
                                    type = if (contentUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e("StarTV", "JSON-LD parse hatası: ${e.message}")
                }
            }

            // ★ Öncelik 2: Regex ile m3u8 / mp4 ara
            if (!found) {
                val patterns = listOf(
                    Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+\\.m3u8[^\"]*)\""),
                    Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+\\.mp4[^\"]*)\""),
                    Regex("(https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)"),
                    Regex("(https?://[^\"'\\s]+\\.mp4[^\"'\\s]*)")
                )
                for (script in document.select("script")) {
                    val content = script.data()
                    for (pattern in patterns) {
                        pattern.find(content)?.let { match ->
                            val videoUrl = match.groupValues[1].replace("\\/", "/")
                            Log.d("StarTV", "Regex ile bulundu: $videoUrl")
                            callback.invoke(
                                newExtractorLink(
                                    name = this.name,
                                    source = this.name,
                                    url = videoUrl,
                                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                }
                            )
                            found = true
                        }
                    }
                }
            }

            // ★ Öncelik 3: iframe embed
            if (!found) {
                val iframe = document.selectFirst("iframe[src]")
                if (iframe != null) {
                    val embedUrl = fixUrl(iframe.attr("src"))
                    Log.d("StarTV", "iframe bulundu: $embedUrl")
                    if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }

            return found
        } catch (e: Exception) {
            Log.e("StarTV", "LoadLinks hatası: ${e.message}")
            return false
        }
    }
}