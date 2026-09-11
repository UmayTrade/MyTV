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

    // ★ DygDigital API sabitleri
    private val dygPublisherId = "1"
    private val dygSecretKey   = "NtvApiSecret2014*"
    private val dygApiBase     = "https://dygvideo.dygdigital.com/api/redirect"

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
        } catch (e: Exception) {
            Log.e("StarTV", "Liste sayfası hatası: ${e.message}")
        }

        val uniqueResults = results.distinctBy { it.url }
        Log.d("StarTV", "getMainPage: ${request.name} -> ${uniqueResults.size} sonuç")

        return newHomePageResponse(
            listOf(HomePageList(request.name, uniqueResults))
        )
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

        if (path.contains("/bolumler/")) {
            val parentPath = path.substringBefore("/bolumler/")
            val parentUrl = "$mainUrl/$parentPath"
            return load(parentUrl)
        }

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
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
     * ★ DygDigital API URL'i oluşturur
     * Doğrulanmış format: PublisherId + ReferenceId + SecretKey + .m3u8
     */
    private fun buildDygUrl(referenceId: String): String {
        return "$dygApiBase?PublisherId=$dygPublisherId&ReferenceId=$referenceId&SecretKey=$dygSecretKey&.m3u8"
    }

    /**
     * ★ Video ID çıkarma - 6 farklı yöntem
     * Star TV, HTML'de video ID'sini saklar: "videoId":"1033394"
     */
    private fun extractVideoId(document: org.jsoup.nodes.Document): String? {
        // 1. "videoId":"XXXXX"
        val videoIdRegex = Regex("\"videoId\"\\s*:\\s*\"?(\\d+)\"?")
        for (script in document.select("script")) {
            videoIdRegex.find(script.data())?.let {
                Log.d("StarTV", "✓ videoId bulundu: ${it.groupValues[1]}")
                return it.groupValues[1]
            }
        }

        // 2. "referenceId":"XXXXX"
        val refRegex = Regex("\"referenceId\"\\s*:\\s*\"?([A-Za-z0-9_]+)\"?")
        for (script in document.select("script")) {
            refRegex.find(script.data())?.let {
                Log.d("StarTV", "✓ referenceId bulundu: ${it.groupValues[1]}")
                return it.groupValues[1]
            }
        }

        // 3. data-video-id attribute
        document.select("[data-video-id]").firstOrNull()?.let { el ->
            val id = el.attr("data-video-id").trim()
            if (id.matches(Regex("\\d+"))) {
                Log.d("StarTV", "✓ data-video-id bulundu: $id")
                return id
            }
        }

        // 4. Player div attribute
        document.select("#dyg-player, [id*=player], [class*=player]").firstOrNull()?.let { el ->
            el.attributes().forEach { attr ->
                val value = attr.value.trim()
                if (value.matches(Regex("\\d{5,}"))) {
                    Log.d("StarTV", "✓ player attribute: ${attr.key}=$value")
                    return value
                }
            }
        }

        // 5. Canonical URL'den bölüm numarası
        document.select("link[rel=canonical]").firstOrNull()?.attr("href")?.let { canonical ->
            Regex("/(\\d+)-bolum").find(canonical)?.let {
                Log.d("StarTV", "✓ canonical'dan: ${it.groupValues[1]}")
                return it.groupValues[1]
            }
        }

        // 6. LD+JSON'dan referenceId
        for (script in document.select("script[type=application/ld+json]")) {
            val content = script.data()
            Regex("\"referenceId\"\\s*:\\s*\"?(\\d+)\"?").find(content)?.let {
                Log.d("StarTV", "✓ LD+JSON referenceId: ${it.groupValues[1]}")
                return it.groupValues[1]
            }
        }

        Log.w("StarTV", "✗ Video ID bulunamadı!")
        return null
    }

    /**
     * ★ iframe içeriğinden m3u8 bul - özyinelemeli
     */
    private suspend fun findM3u8InIframe(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            Log.d("StarTV", "iframe içeriği çekiliyor: $embedUrl")
            val iframeDoc = app.get(embedUrl, headers = headers).document
            val iframeHtml = iframeDoc.html()

            // iframe HTML'inde m3u8 ara
            val m3u8Regex = Regex("(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)")
            m3u8Regex.find(iframeHtml)?.let { match ->
                val m3u8Url = match.groupValues[1].replace("\\/", "/")
                Log.d("StarTV", "✓ iframe'de m3u8 bulundu: $m3u8Url")
                callback.invoke(
                    newExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            // video source / file attribute
            iframeDoc.select("video source[src], video[src], source[src]").forEach { src ->
                val url = src.attr("src")
                if (url.contains(".m3u8") || url.contains(".mp4")) {
                    val fullUrl = fixUrl(url)
                    Log.d("StarTV", "✓ iframe video source: $fullUrl")
                    callback.invoke(
                        newExtractorLink(
                            name = this.name,
                            source = this.name,
                            url = fullUrl,
                            type = if (fullUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                        }
                    )
                    return true
                }
            }

            // iframe içindeki script'lerde m3u8 ara
            for (script in iframeDoc.select("script")) {
                val content = script.data()
                m3u8Regex.find(content)?.let { match ->
                    val m3u8Url = match.groupValues[1].replace("\\/", "/")
                    Log.d("StarTV", "✓ iframe script'te m3u8: $m3u8Url")
                    callback.invoke(
                        newExtractorLink(
                            name = this.name,
                            source = this.name,
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                        }
                    )
                    return true
                }
            }

            return false
        } catch (e: Exception) {
            Log.e("StarTV", "iframe çekme hatası: ${e.message}")
            return false
        }
    }

    /**
     * ★ DygDigital API'den m3u8 al - doğrulama ile
     */
    private suspend fun tryDygDigital(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Denenecek formatlar
        val formats = listOf(
            "StarTV_$videoId",
            videoId,
            "StarTV_${videoId}_1",
            "startv_$videoId"
        )

        for (refId in formats) {
            val url = buildDygUrl(refId)
            Log.d("StarTV", "DygDigital deneme: $url")

            try {
                val response = app.get(
                    url,
                    headers = headers + mapOf("Referer" to mainUrl),
                    allowRedirects = true
                )

                val finalUrl = response.url
                val body = response.text

                // Başarılı mı kontrol et
                if (body.contains("#EXTM3U") || finalUrl.contains(".m3u8")) {
                    val m3u8Url = if (finalUrl.contains(".m3u8")) finalUrl else url
                    Log.d("StarTV", "✓ DygDigital başarılı: $m3u8Url")

                    callback.invoke(
                        newExtractorLink(
                            name = this.name,
                            source = this.name,
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }

                // Body'de m3u8 URL'i var mı?
                val bodyM3u8 = Regex("(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)").find(body)
                if (bodyM3u8 != null) {
                    val m3u8Url = bodyM3u8.groupValues[1].replace("\\/", "/")
                    Log.d("StarTV", "✓ DygDigital yanıtında m3u8: $m3u8Url")
                    callback.invoke(
                        newExtractorLink(
                            name = this.name,
                            source = this.name,
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                        }
                    )
                    return true
                }

                Log.d("StarTV", "✗ Format başarısız ($refId), yanıt: ${body.take(150)}")
            } catch (e: Exception) {
                Log.d("StarTV", "✗ DygDigital hata ($refId): ${e.message}")
            }
        }
        return false
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
            if (data.isBlank()) return false

            val document = app.get(data, headers = headers).document
            var found = false

            // ★ ADIM 1: Video ID çıkar
            val videoId = extractVideoId(document)

            // ★ ADIM 2: DygDigital API dene
            if (videoId != null) {
                Log.d("StarTV", "→ ADIM 2: DygDigital API deneniyor (ID: $videoId)")
                if (tryDygDigital(videoId, callback)) {
                    found = true
                }
            }

            // ★ ADIM 3: HTML'de doğrudan m3u8/mp4 ara
            if (!found) {
                Log.d("StarTV", "→ ADIM 3: HTML'de m3u8/mp4 aranıyor")
                val html = document.html()
                val patterns = listOf(
                    Regex("(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)"),
                    Regex("(https?://[^\"'\\s<>]+\\.mp4[^\"'\\s<>]*)"),
                    Regex("(https?://startv\\.akamaized\\.net/[^\"'\\s<>]+)"),
                    Regex("\"(?:hls|hlsUrl|streamUrl|videoUrl|source)\"\\s*:\\s*\"([^\"]+)\"")
                )

                for (pattern in patterns) {
                    pattern.findAll(html).forEach { match ->
                        if (found) return@forEach
                        val videoUrl = match.groupValues[1]
                            .replace("\\/", "/")
                            .replace("\\u0026", "&")

                        if (videoUrl.startsWith("http") &&
                            (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4") ||
                             videoUrl.contains("akamaized"))) {
                            Log.d("StarTV", "✓ Regex m3u8: $videoUrl")
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

            // ★ ADIM 4: JSON-LD'den contentUrl
            if (!found) {
                Log.d("StarTV", "→ ADIM 4: JSON-LD kontrol ediliyor")
                for (script in document.select("script[type=application/ld+json]")) {
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
                                    if (contentUrl.isNotEmpty() && contentUrl.contains(".m3u8")) {
                                        Log.d("StarTV", "✓ JSON-LD contentUrl: $contentUrl")
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
                        Log.e("StarTV", "JSON-LD hatası: ${e.message}")
                    }
                }
            }

            // ★ ADIM 5: iframe içeriğini özyinelemeli ara
            if (!found) {
                Log.d("StarTV", "→ ADIM 5: iframe'ler kontrol ediliyor")
                for (iframe in document.select("iframe[src]")) {
                    if (found) break
                    val iframeSrc = iframe.attr("src")
                    if (iframeSrc.isBlank()) continue

                    val embedUrl = fixUrl(iframeSrc)
                    Log.d("StarTV", "iframe bulundu: $embedUrl")

                    // loadExtractor dene
                    if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                        found = true
                        break
                    }

                    // Manuel iframe HTML çek ve m3u8 ara
                    if (findM3u8InIframe(embedUrl, callback)) {
                        found = true
                        break
                    }
                }
            }

            // ★ ADIM 6: Video element
            if (!found) {
                Log.d("StarTV", "→ ADIM 6: video element kontrol ediliyor")
                document.select("video[src], video source[src]").forEach { el ->
                    val src = el.attr("src")
                    if (src.isNotEmpty()) {
                        val fullUrl = fixUrl(src)
                        Log.d("StarTV", "✓ video element: $fullUrl")
                        callback.invoke(
                            newExtractorLink(
                                name = this.name,
                                source = this.name,
                                url = fullUrl,
                                type = if (fullUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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
