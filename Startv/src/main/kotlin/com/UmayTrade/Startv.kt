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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "https://www.startv.com.tr/"
    )

    private val systemPages = setOf(
        "diziler", "programlar", "yayin-akisi", "canli-yayin",
        "haberler", "haber", "arama", "kunye", "iletisim",
        "gizlilik-bildirimi", "veri-politikasi", "site-haritasi",
        "rss-bilgi", "filmler", "fragmanlar", "ekstralar",
        "foto-galeriler", "oyuncular", "kadro", "bolumler",
        "fragman", "ozel-videolar", "benzer-diziler"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/dizi"    to "Diziler",
        "${mainUrl}/program" to "Programlar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()

        try {
            val listDoc = app.get(request.data, headers = headers).document

            listDoc.select("div.poster-card a[href*='/dizi/'], div.poster-card a[href*='/program/']").forEach { element ->
                element.toListPageResult()?.let { results.add(it) }
            }

            if (results.isEmpty()) {
                listDoc.select("a[href*='/dizi/'], a[href*='/program/']").forEach { element ->
                    element.toListPageResult()?.let { results.add(it) }
                }
            }

            Log.d("StarTV", "Liste sayfasından ${results.size} öğe alındı")
        } catch (e: Exception) {
            Log.e("StarTV", "Liste sayfası hatası: ${e.message}")
        }

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
        val segments = path.split("/")
        if (segments.size != 2) return null
        if (segments[0] != "dizi" && segments[0] != "program") return null

        val slug = segments[1]
        if (systemPages.contains(slug)) return null

        val img = this.selectFirst("img")
            ?: this.parent()?.selectFirst("img")
            ?: return null

        val title = img.attr("alt").trim().takeIf { it.isNotEmpty() && it != "null" }
            ?: this.selectFirst("figcaption, .title, .caption, h2, h3")
                ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

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
            val pagesToScan = listOf("${mainUrl}/dizi", "${mainUrl}/program")
            for (pageUrl in pagesToScan) {
                try {
                    val document = app.get(pageUrl, headers = headers).document
                    document.select("div.poster-card a[href*='/dizi/'], div.poster-card a[href*='/program/']").forEach { element ->
                        element.toListPageResult()?.let { allContent.add(it) }
                    }
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
            val title = document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.substringBefore("|")
                ?: return null

            val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

            val episodes = getEpisodes(document, url)

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // ★ Bölüm sayfası → parent diziye yönlendir
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
            val episodeLinks = document.select("a[href*='/bolumler/']")
                .filter { element ->
                    val href = element.attr("href")
                    href.contains("/bolumler/") && !href.contains("fragman")
                }

            if (episodeLinks.isNotEmpty()) {
                Log.d("StarTV", "Statik ${episodeLinks.size} bölüm linki bulundu")
                episodeLinks.distinctBy { it.attr("href") }.forEachIndexed { index, element ->
                    val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed

                    val epName = element.selectFirst(".video-card-title, h4, h3, .title")
                        ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: element.text().trim().takeIf { it.isNotEmpty() }
                        ?: "Bölüm ${index + 1}"

                    val epNum = Regex("/(\\d+)-bolum").find(href)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (index + 1)

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
            return emptyList()
        } catch (e: Exception) {
            Log.e("StarTV", "Bölüm çekme hatası: ${e.message}")
            return emptyList()
        }
    }

    /**
     * ★ HTML metninden m3u8/mp4/medya URL'i çıkarır
     * mncdn.com, akamaized.net, smil: formatlarını tanır
     */
    private fun extractMediaUrl(text: String): String? {
        // Kaçış karakterlerini decode et
        val decoded = text
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u002f", "/")
            .replace("\\u003a", ":")
            .replace("\\u003F", "?")
            .replace("\\u002E", ".")

        val patterns = listOf(
            // ★ mncdn.com (Star TV güncel CDN)
            Regex("(https?://[^\"'\\s<>]*mncdn\\.com[^\"'\\s<>]*\\.m3u8[^\"'\\s<>]*)"),
            // ★ akamaized.net (Star TV eski CDN)
            Regex("(https?://[^\"'\\s<>]*akamaized\\.net[^\"'\\s<>]*\\.m3u8[^\"'\\s<>]*)"),
            // ★ smil: formatı
            Regex("(https?://[^\"'\\s<>]+/smil:[^\"'\\s<>]+)"),
            // Genel m3u8
            Regex("(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)"),
            // Genel mp4
            Regex("(https?://[^\"'\\s<>]+\\.mp4[^\"'\\s<>]*)"),
            // JSON içindeki file/src/hls alanları
            Regex("\"(?:file|src|url|hls|hlsUrl|streamUrl|videoUrl|source|contentUrl|playlist)\"\\s*:\\s*\"([^\"]+)\"")
        )

        for (pattern in patterns) {
            pattern.find(decoded)?.let { match ->
                val url = match.groupValues[1]
                if (url.startsWith("http") &&
                    (url.contains(".m3u8") || url.contains(".mp4") ||
                     url.contains("mncdn") || url.contains("akamaized") ||
                     url.contains("smil:"))) {
                    return url
                }
            }
        }
        return null
    }

    /**
     * ★ iframe içeriğini çek ve içindeki medya URL'ini ara (özyinelemeli)
     */
    private suspend fun findMediaInIframe(
        embedUrl: String,
        referer: String,
        depth: Int = 0
    ): String? {
        if (depth > 3) {
            Log.d("StarTV", "iframe derinlik limiti aşıldı (3)")
            return null
        }

        try {
            Log.d("StarTV", "  → iframe çekiliyor (depth=$depth): $embedUrl")
            val iframeDoc = app.get(
                embedUrl,
                headers = headers + mapOf("Referer" to referer)
            ).document

            // 1. iframe HTML'inde medya URL'i ara
            extractMediaUrl(iframeDoc.html())?.let { url ->
                Log.d("StarTV", "  ✓ iframe HTML'inde bulundu: $url")
                return url
            }

            // 2. <video> ve <source> elementleri
            iframeDoc.select("video[src], video source[src], source[src]").forEach { src ->
                val url = src.attr("src")
                if (url.isNotEmpty() && (url.contains(".m3u8") || url.contains(".mp4") || url.contains("smil:"))) {
                    val fullUrl = if (url.startsWith("http")) url else fixUrl(url)
                    Log.d("StarTV", "  ✓ iframe video element: $fullUrl")
                    return fullUrl
                }
            }

            // 3. Script'lerde ara
            for (script in iframeDoc.select("script")) {
                extractMediaUrl(script.data())?.let { url ->
                    Log.d("StarTV", "  ✓ iframe script'te bulundu: $url")
                    return url
                }
            }

            // 4. Data attribute'larda ara
            iframeDoc.select("[data-src], [data-url], [data-file], [data-video]").forEach { el ->
                val url = el.attr("data-src").ifEmpty {
                    el.attr("data-url").ifEmpty {
                        el.attr("data-file").ifEmpty { el.attr("data-video") }
                    }
                }
                if (url.isNotEmpty() && (url.contains(".m3u8") || url.contains(".mp4") || url.contains("smil:"))) {
                    val fullUrl = if (url.startsWith("http")) url else fixUrl(url)
                    Log.d("StarTV", "  ✓ iframe data attribute: $fullUrl")
                    return fullUrl
                }
            }

            // 5. İç içe iframe'lerde ara (özyinelemeli)
            for (nestedIframe in iframeDoc.select("iframe[src]")) {
                val nestedSrc = nestedIframe.attr("src")
                if (nestedSrc.isBlank()) continue
                val nestedUrl = if (nestedSrc.startsWith("http")) nestedSrc else fixUrl(nestedSrc)
                Log.d("StarTV", "  → iç iframe bulundu: $nestedUrl")
                findMediaInIframe(nestedUrl, embedUrl, depth + 1)?.let { return it }
            }
        } catch (e: Exception) {
            Log.e("StarTV", "  ✗ iframe çekme hatası ($embedUrl): ${e.message}")
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StarTV", "═══════════ loadLinks BAŞLADI ═══════════")
        Log.d("StarTV", "URL: $data")

        try {
            if (data.isBlank()) {
                Log.e("StarTV", "data boş!")
                return false
            }

            val document = app.get(data, headers = headers).document
            var found = false

            // ═══ ADIM 1: Sayfa HTML'inde doğrudan medya URL'i ara ═══
            Log.d("StarTV", "→ ADIM 1: Sayfa HTML'inde medya URL'i aranıyor...")
            extractMediaUrl(document.html())?.let { url ->
                Log.d("StarTV", "✓ ADIM 1 BAŞARILI: $url")
                callback.invoke(
                    newExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = url,
                        type = if (url.contains(".m3u8") || url.contains("smil:"))
                            ExtractorLinkType.M3U8
                        else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }

            // ═══ ADIM 2: <video> ve <source> elementlerini kontrol et ═══
            if (!found) {
                Log.d("StarTV", "→ ADIM 2: video elementleri kontrol ediliyor...")
                document.select("video[src], video source[src], source[src]").forEach { el ->
                    if (found) return@forEach
                    val src = el.attr("src")
                    if (src.isNotEmpty()) {
                        val fullUrl = if (src.startsWith("http")) src else fixUrl(src)
                        if (fullUrl.contains(".m3u8") || fullUrl.contains(".mp4") || fullUrl.contains("smil:")) {
                            Log.d("StarTV", "✓ ADIM 2 BAŞARILI: $fullUrl")
                            callback.invoke(
                                newExtractorLink(
                                    name = this.name,
                                    source = this.name,
                                    url = fullUrl,
                                    type = if (fullUrl.contains(".m3u8") || fullUrl.contains("smil:"))
                                        ExtractorLinkType.M3U8
                                    else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                }
                            )
                            found = true
                        }
                    }
                }
            }

            // ═══ ADIM 3: iframe'leri tara (loadExtractor + manuel) ═══
            if (!found) {
                Log.d("StarTV", "→ ADIM 3: iframe'ler kontrol ediliyor...")
                val iframes = document.select("iframe[src]")
                Log.d("StarTV", "Toplam ${iframes.size} iframe bulundu")

                for (iframe in iframes) {
                    if (found) break
                    val iframeSrc = iframe.attr("src")
                    if (iframeSrc.isBlank()) continue

                    val embedUrl = if (iframeSrc.startsWith("http")) iframeSrc else fixUrl(iframeSrc)
                    Log.d("StarTV", "→ iframe: $embedUrl")

                    // 3a. Cloudstream'in kendi extractor'ları
                    try {
                        if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                            Log.d("StarTV", "✓ ADIM 3a BAŞARILI (loadExtractor): $embedUrl")
                            found = true
                            break
                        }
                    } catch (e: Exception) {
                        Log.d("StarTV", "loadExtractor hatası: ${e.message}")
                    }

                    // 3b. Manuel iframe kazıma
                    findMediaInIframe(embedUrl, data)?.let { url ->
                        Log.d("StarTV", "✓ ADIM 3b BAŞARILI (manuel): $url")
                        callback.invoke(
                            newExtractorLink(
                                name = this.name,
                                source = this.name,
                                url = url,
                                type = if (url.contains(".m3u8") || url.contains("smil:"))
                                    ExtractorLinkType.M3U8
                                else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                }
            }

            // ═══ ADIM 4: JSON-LD VideoObject ═══
            if (!found) {
                Log.d("StarTV", "→ ADIM 4: JSON-LD kontrol ediliyor...")
                for (script in document.select("script[type=application/ld+json]")) {
                    if (found) break
                    val content = script.data()
                    if (!content.contains("VideoObject")) continue
                    try {
                        val json = JSONObject(content)
                        val graph = json.optJSONArray("@graph")
                        if (graph != null) {
                            for (i in 0 until graph.length()) {
                                val item = graph.getJSONObject(i)
                                if (item.optString("@type") == "VideoObject") {
                                    val contentUrl = item.optString("contentUrl", "")
                                    if (contentUrl.isNotEmpty() &&
                                        (contentUrl.contains(".m3u8") || contentUrl.contains("smil:"))) {
                                        Log.d("StarTV", "✓ ADIM 4 BAŞARILI: $contentUrl")
                                        callback.invoke(
                                            newExtractorLink(
                                                name = this.name,
                                                source = this.name,
                                                url = contentUrl,
                                                type = ExtractorLinkType.M3U8
                                            ) {
                                                this.referer = mainUrl
                                            }
                                        )
                                        found = true
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("StarTV", "JSON-LD parse hatası: ${e.message}")
                    }
                }
            }

            // ═══ ADIM 5: Tüm script'lerde agresif arama ═══
            if (!found) {
                Log.d("StarTV", "→ ADIM 5: Tüm script'lerde agresif arama...")
                for (script in document.select("script")) {
                    if (found) break
                    val content = script.data()
                    if (content.length < 50) continue
                    extractMediaUrl(content)?.let { url ->
                        Log.d("StarTV", "✓ ADIM 5 BAŞARILI: $url")
                        callback.invoke(
                            newExtractorLink(
                                name = this.name,
                                source = this.name,
                                url = url,
                                type = if (url.contains(".m3u8") || url.contains("smil:"))
                                    ExtractorLinkType.M3U8
                                else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                            }
                        )
                        found = true
                    }
                }
            }

            Log.d("StarTV", "═══════════ SONUÇ: $found ═══════════")
            return found

        } catch (e: Exception) {
            Log.e("StarTV", "LoadLinks hatası: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}
