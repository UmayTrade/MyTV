// ! Bu araç @SAKLImavi tarafından | @UmayTrade için yazılmıştır. (Star TV için uyarlanmıştır)

package com.UmayTrade

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "https://www.startv.com.tr/"
    )

    // Sistem sayfaları
    private val systemPages = setOf(
        "diziler", "programlar", "yayin-akisi", "canli-yayin",
        "haberler", "haber", "arama", "kunye", "iletisim",
        "gizlilik-bildirimi", "veri-politikasi", "site-haritasi",
        "rss-bilgi", "filmler", "fragmanlar", "ekstralar",
        "foto-galeriler", "oyuncular", "kadro", "bolumler",
        "fragman", "ozel-videolar", "benzer-diziler"
    )

    // ★ DÜZELTME: /dizi ve /program (diziler/programlar değil)
    override val mainPage = mainPageOf(
        "${mainUrl}/dizi"    to "Diziler",
        "${mainUrl}/program" to "Programlar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()

        // ★ Liste sayfasından kart linklerini al
        try {
            val listDoc = app.get(request.data, headers = headers).document

            // HTML'deki poster-card yapısı: <div class="poster-card"> <a href="/dizi/..."> <figure> <img alt="..."/> </figure> </a> </div>
            listDoc.select("div.poster-card a[href*='/dizi/'], div.poster-card a[href*='/program/']").forEach { element ->
                element.toListPageResult()?.let { results.add(it) }
            }

            // Alternatif selector
            if (results.isEmpty()) {
                listDoc.select("a[href*='/dizi/'], a[href*='/program/']").forEach { element ->
                    element.toListPageResult()?.let { results.add(it) }
                }
            }

            Log.d("StarTV", "Liste sayfasından ${results.size} öğe alındı")
        } catch (e: Exception) {
            Log.e("StarTV", "Liste sayfası hatası: ${e.message}")
        }

        // ★ Menüden de link al (yedek)
        if (results.isEmpty()) {
            try {
                val mainDoc = app.get(mainUrl, headers = headers).document
                val menuSelector = if (request.name == "Diziler") {
                    "nav a[href*='/dizi/']"
                } else {
                    "nav a[href*='/program/']"
                }
                mainDoc.select(menuSelector).forEach { element ->
                    element.toMenuItemResult()?.let { results.add(it) }
                }
            } catch (e: Exception) {
                Log.e("StarTV", "Menü çekme hatası: ${e.message}")
            }
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

        val path = normalizePath(fullUrl) ?: return null

        if (!path.startsWith("dizi/") && !path.startsWith("program/")) return null

        val segments = path.split("/")
        if (segments.size != 2) return null

        val slug = segments[1]
        if (systemPages.contains(slug)) return null

        val title = this.text().trim().takeIf { it.isNotEmpty() } ?: return null

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

        // ★ SADECE /dizi/slug veya /program/slug
        val segments = path.split("/")
        if (segments.size != 2) return null
        if (segments[0] != "dizi" && segments[0] != "program") return null

        val slug = segments[1]
        if (systemPages.contains(slug)) return null

        // ★ HTML yapısı: <div class="poster-card"> <a href="..."> <figure> <img alt="Başkalarının Hayatı" .../> </figure> </a> </div>
        // img bu elementin içinde veya parent'ında
        val img = this.selectFirst("img")
            ?: this.parent()?.selectFirst("img")
            ?: return null

        // Başlık: img alt'ından veya figcaption'dan
        val title = img.attr("alt").trim().takeIf { it.isNotEmpty() && it != "null" }
            ?: this.selectFirst("figcaption, .title, .caption, h2, h3")
                ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        // ★ HTML'de src direkt kullanılıyor (lazy load yok gibi görünüyor)
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

    private fun normalizePath(url: String): String? {
        var path = url
        path = path.replace("https://www.startv.com.tr", "")
        path = path.replace("https://startv.com.tr", "")
        path = path.replace("http://www.startv.com.tr", "")
        path = path.replace("http://startv.com.tr", "")
        path = path.substringBefore("?").substringBefore("#")
        path = path.trim('/')

        if (path.isEmpty()) return null
        if (path.contains(".")) return null
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
            // ★ DÜZELTME: /dizi ve /program
            val pagesToScan = listOf("${mainUrl}/dizi", "${mainUrl}/program")
            for (pageUrl in pagesToScan) {
                try {
                    val document = app.get(pageUrl, headers = headers).document
                    document.select("div.poster-card a[href*='/dizi/'], div.poster-card a[href*='/program/']").forEach { element ->
                        element.toListPageResult()?.let { allContent.add(it) }
                    }
                    // Fallback
                    if (allContent.isEmpty()) {
                        document.select("a[href*='/dizi/'], a[href*='/program/']").forEach { element ->
                            element.toListPageResult()?.let { allContent.add(it) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("StarTV", "Sayfa çekme hatası ($pageUrl): ${e.message}")
                }
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

        val path = normalizePath(url) ?: return null

        // ★ Dizi/Program detay sayfası: /dizi/slug veya /program/slug
        if ((path.startsWith("dizi/") || path.startsWith("program/")) && path.split("/").size == 2) {
            // ★ HTML'de başlık: <h1 class="...">Tuzlu Kahve</h1>
            val title = document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.substringBefore("|")
                ?: return null

            // ★ HTML'de poster: <meta property="og:image" content="https://media.startv.com.tr/star-tv/images/banner(2).jpg"/>
            val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

            val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

            val episodes = getEpisodes(document, url)

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // ★ Bölüm sayfası: /dizi/slug/bolumler/N-bolum
        // Bu durumda parent diziyi yükleyip bölümü bul
        if (path.contains("/bolumler/")) {
            val parentPath = path.substringBefore("/bolumler/")
            val parentUrl = "$mainUrl/$parentPath"
            Log.d("StarTV", "Bölüm sayfası, parent'a yönlendiriliyor: $parentUrl")
            return load(parentUrl)
        }

        // Diğer - tek bölüm olarak işle
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
            // ★ DÜZELTME: Star TV bölüm linkleri: /dizi/tuzlu-kahve/bolumler/1-bolum
            // HTML'de: <a href="/dizi/tuzlu-kahve/bolumler/1-bolum">
            val episodeLinks = document.select("a[href*='/bolumler/']")
                .filter { element ->
                    val href = element.attr("href")
                    // "bolumler" içermeli ve "-fragman" içermemeli
                    href.contains("/bolumler/") && !href.contains("fragman")
                }

            if (episodeLinks.isNotEmpty()) {
                Log.d("StarTV", "Statik ${episodeLinks.size} bölüm linki bulundu")
                episodeLinks.distinctBy { it.attr("href") }.forEachIndexed { index, element ->
                    val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed

                    // Bölüm adını al - HTML'de: <h4 class="video-card-title ...">1. Bölüm</h4>
                    val epName = element.selectFirst(".video-card-title, h4, h3, .title")
                        ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: element.text().trim().takeIf { it.isNotEmpty() }
                        ?: "Bölüm ${index + 1}"

                    // ★ Bölüm numarasını URL'den çıkar: /1-bolum → 1
                    val epNum = Regex("/(\\d+)-bolum").find(href)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (index + 1)

                    // Bölüm posteri - HTML'de: <img alt="1. Bölüm" src="..."/>
                    val epPoster = element.selectFirst("img")?.let { img ->
                        fixUrlNull(img.attr("data-src").ifEmpty { img.attr("src") })
                    }

                    newEpisode(href) {
                        this.name = epName
                        this.episode = epNum
                        this.posterUrl = epPoster
                    }?.let { allEpisodes.add(it) }
                }

                return allEpisodes.sortedBy { it.episode }
            }

            // ★ Fragmanları da ekle (isteğe bağlı - "Fragman" kategorisi olarak)
            val trailerLinks = document.select("a[href*='/fragmanlar/']")
                .filter { !it.attr("href").contains("tanitim") }

            // Not: Fragmanları ayrı bir sezon gibi ekleyebiliriz ama şimdilik atlıyoruz

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

            // ★ Öncelik 1: Sayfadaki JSON-LD'de VideoObject varsa (genelde yok)
            val ldJsonScripts = document.select("script[type=application/ld+json]")
            for (script in ldJsonScripts) {
                val content = script.data()
                if (!content.contains("VideoObject")) continue
                try {
                    // @graph array içinde olabilir
                    val json = JSONObject(content)
                    val graph = json.optJSONArray("@graph")
                    if (graph != null) {
                        for (i in 0 until graph.length()) {
                            val item = graph.getJSONObject(i)
                            if (item.optString("@type") == "VideoObject") {
                                val contentUrl = item.optString("contentUrl", "")
                                val embedUrl = item.optString("embedUrl", "")
                                // contentUrl genelde sayfa URL'i, embedUrl de öyle
                                // thumbnailUrl ve duration var ama video URL yok
                                Log.d("StarTV", "VideoObject bulundu - contentUrl: $contentUrl, embedUrl: $embedUrl")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("StarTV", "JSON-LD parse hatası: ${e.message}")
                }
            }

            // ★ Öncelik 2: Player script'lerinde video ID ara
            // HTML'de: "videoId":"1033394"
            val videoIdRegex = Regex("\"videoId\"\\s*:\\s*\"?(\\d+)\"?")
            var videoId: String? = null
            for (script in document.select("script")) {
                val content = script.data()
                videoIdRegex.find(content)?.let { match ->
                    videoId = match.groupValues[1]
                    Log.d("StarTV", "Video ID bulundu: $videoId")
                }
                // Ayrıca embedded player URL'i ara
                val playerUrlRegex = Regex("(https?://[^\"'\\s]*(?:player|embed)[^\"'\\s]*)")
                playerUrlRegex.find(content)?.let { match ->
                    val playerUrl = match.groupValues[1].replace("\\/", "/")
                    Log.d("StarTV", "Player URL bulundu: $playerUrl")
                    // Player'ı extract et
                    if (loadExtractor(playerUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }

            // ★ Öncelik 3: DygDigital player varsa
            // HTML'de player: <div id="dyg-player"> ve player-assets.dygdigital.com/prod/js/bundle.js
            if (!found) {
                // Player bundle URL'i
                val playerBundle = "https://player-assets.dygdigital.com/prod/js/bundle.js"
                // Genelde video kaynağı: https://dygvideo.dygdigital.com/...
                // Bu kısım API'ye bağlı, doğrudan iframe yoksa zor
                Log.d("StarTV", "DygDigital player tespit edildi, doğrudan video kaynağı yok")
            }

            // ★ Öncelik 4: iframe embed
            val iframe = document.selectFirst("iframe[src]")
            if (iframe != null && !found) {
                val embedUrl = fixUrl(iframe.attr("src"))
                Log.d("StarTV", "iframe bulundu: $embedUrl")
                if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                    found = true
                }
            }

            // ★ Öncelik 5: m3u8/mp4 doğrudan arama (fallback)
            if (!found) {
                val patterns = listOf(
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

            if (!found) {
                Log.w("StarTV", "Hiçbir video kaynağı bulunamadı: $data")
            }

            return found
        } catch (e: Exception) {
            Log.e("StarTV", "LoadLinks hatası: ${e.message}")
            return false
        }
    }
}
