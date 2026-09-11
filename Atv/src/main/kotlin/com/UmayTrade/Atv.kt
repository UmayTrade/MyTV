// ! Bu araç @SAKLImavi tarafından | @UmayTrade için yazılmıştır. (ATV için uyarlanmıştır)

package com.UmayTrade

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.util.*

class Atv : MainAPI() {
    override var mainUrl              = "https://www.atv.com.tr"
    override var name                 = "ATV"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Live)

    private var allContentCache: List<SearchResponse> = emptyList()
    private var cacheTime: Long = 0
    private val cacheValidityDuration = 30 * 60 * 1000

    // Sistem sayfaları - bunlar dizi/program olarak gösterilmemeli
    private val systemPages = setOf(
        "diziler", "programlar", "yayin-akisi", "canli-yayin",
        "haberler", "haber", "eski-diziler", "a2tv", "arama",
        "kunye", "iletisim", "bize-ulasin", "gizlilik-bildirimi",
        "veri-politikasi", "uydu-frekanslari", "site-haritasi",
        "rss-bilgi", "adblock", "retro-d", "filmler",
        "milyoner", "webtv", "diger", "kadro", "fragmanlar",
        "ozetler", "ozel-klipler", "foto-galeri", "oyuncular",
        "hikaye-ve-kunye", "d-shorts", "bolumler"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler"    to "Diziler",
        "${mainUrl}/programlar" to "Programlar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()

        // ★ 1. Ana sayfadaki menüden linkleri al
        try {
            val mainDoc = app.get(mainUrl).document
            val menuSelector = if (request.name == "Diziler") {
                "div.series-drop .sub-menu-list li a[href]"
            } else {
                "div.program-drop-menu .sub-menu-list li a[href]"
            }
            mainDoc.select(menuSelector).forEach { element ->
                element.toMenuItemResult()?.let { results.add(it) }
            }
            Log.d("ATV", "Menüden ${results.size} öğe alındı")
        } catch (e: Exception) {
            Log.e("ATV", "Menü çekme hatası: ${e.message}")
        }

        // ★ 2. Liste sayfasından da kart linklerini al
        try {
            val listDoc = app.get(request.data).document
            listDoc.select("a[href]").forEach { element ->
                element.toListPageResult()?.let { results.add(it) }
            }
            Log.d("ATV", "Liste sayfasından toplam ${results.size} öğe alındı")
        } catch (e: Exception) {
            Log.e("ATV", "Liste sayfası hatası: ${e.message}")
        }

        val uniqueResults = results.distinctBy { it.url }
        Log.d("ATV", "getMainPage: ${request.name} -> ${uniqueResults.size} sonuç")

        return newHomePageResponse(
            listOf(HomePageList(request.name, uniqueResults))
        )
    }

    private fun Element.toMenuItemResult(): SearchResponse? {
        val hrefRaw = this.attr("href")
        if (hrefRaw.isBlank()) return null

        val fullUrl = fixUrlNull(hrefRaw) ?: return null
        if (!fullUrl.contains("atv.com.tr")) return null

        // Path'i normalize et
        val path = normalizePath(fullUrl) ?: return null

        // Alt sayfaları filtrele
        if (path.contains("/")) return null
        if (systemPages.contains(path)) return null

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
        if (!fullUrl.contains("atv.com.tr")) return null

        // Path'i normalize et
        val path = normalizePath(fullUrl) ?: return null

        // ★ Kesin kural: SADECE tek segment slug
        if (path.contains("/")) return null
        if (systemPages.contains(path)) return null

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

        Log.d("ATV", "  ✓ [$path] → $title")

        return newMovieSearchResponse(title, fullUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    /**
     * URL'yi normalize edip path döndürür.
     * Örn: "https://www.atv.com.tr/mercan-kosk" -> "mercan-kosk"
     */
    private fun normalizePath(url: String): String? {
        var path = url
        path = path.replace("https://www.atv.com.tr", "")
        path = path.replace("https://atv.com.tr", "")
        path = path.replace("http://www.atv.com.tr", "")
        path = path.replace("http://atv.com.tr", "")
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
                    val document = app.get(pageUrl).document
                    document.select("a[href]").forEach { element ->
                        element.toListPageResult()?.let { allContent.add(it) }
                    }
                } catch (e: Exception) {
                    Log.e("ATV", "Sayfa çekme hatası ($pageUrl): ${e.message}")
                }
            }

            // Menüden de ekle
            try {
                val mainDoc = app.get(mainUrl).document
                mainDoc.select("div.series-drop .sub-menu-list li a[href], div.program-drop-menu .sub-menu-list li a[href]")
                    .forEach { element ->
                        element.toMenuItemResult()?.let { allContent.add(it) }
                    }
            } catch (e: Exception) {
                Log.e("ATV", "Menü çekme hatası: ${e.message}")
            }

            val uniqueContent = allContent.distinctBy { it.url }
            allContentCache = uniqueContent
            cacheTime = currentTime
            Log.d("ATV", "getAllContent: ${uniqueContent.size} öğe önbelleğe alındı")
            return uniqueContent
        } catch (e: Exception) {
            Log.e("ATV", "İçerik toplanırken hata: ${e.message}")
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
        val document = app.get(url).document

        // ★ Bölüm sayfası mı? (/izle ile bitiyorsa tek bölüm olarak işle)
        if (url.contains("/izle")) {
            val title = document.selectFirst("h1.video-title")?.text()?.trim()
                ?: document.selectFirst("h1")?.text()?.trim()
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

        // Dizi detay sayfası
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val episodes = getEpisodes(document, url)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private suspend fun getEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()
        try {
            // ★ Bölüm linklerini topla
            // Format: /mercan-kosk/1-bolum/izle veya /mercan-kosk/1-bolum-1-fragman/izle
            val episodeLinks = document.select("a[href*='/izle']")
                .filter { element ->
                    val href = element.attr("href")
                    // Fragman değil, bölüm olmalı: "-bolum/" veya "-bolum-"
                    href.contains("-bolum") && href.endsWith("/izle")
                }

            if (episodeLinks.isNotEmpty()) {
                Log.d("ATV", "Statik ${episodeLinks.size} bölüm linki bulundu")
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
                "$mainUrl/ajax/$slug/episodes"
            )

            for (ajaxUrl in ajaxUrls) {
                try {
                    val response = app.get(
                        ajaxUrl,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to baseUrl
                        )
                    )
                    val doc = response.document
                    val links = doc.select("a[href*='/izle']")
                        .filter { it.attr("href").contains("-bolum") }
                    if (links.isNotEmpty()) {
                        Log.d("ATV", "AJAX'den ${links.size} bölüm bulundu: $ajaxUrl")
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
                    Log.d("ATV", "AJAX denemesi başarısız ($ajaxUrl): ${e.message}")
                }
            }

            return emptyList()
        } catch (e: Exception) {
            Log.e("ATV", "Bölüm çekme hatası: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("ATV", "Video data: $data")
        try {
            if (data.isBlank()) return false

            val document = app.get(data).document
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
                            Log.d("ATV", "JSON-LD contentUrl bulundu: $contentUrl")
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
                    Log.e("ATV", "JSON-LD parse hatası: ${e.message}")
                }
            }

            // ★ Öncelik 2: Regex ile contentUrl / m3u8 / mp4 ara
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
                            Log.d("ATV", "Regex ile bulundu: $videoUrl")
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
                    Log.d("ATV", "iframe bulundu: $embedUrl")
                    if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }

            return found
        } catch (e: Exception) {
            Log.e("ATV", "LoadLinks hatası: ${e.message}")
            return false
        }
    }
}
