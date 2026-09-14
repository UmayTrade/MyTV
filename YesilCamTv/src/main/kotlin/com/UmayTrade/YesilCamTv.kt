package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class YesilCamTv : MainAPI() {
    override var mainUrl = "https://yesilcamtv.com.tr"
    override var name = "YesilCamTv"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Eklenenler",
        "${mainUrl}/film-arsivi/" to "Film Arşivi",
        "${mainUrl}/category/komedi/" to "Komedi",
        "${mainUrl}/category/dram/" to "Dram",
        "${mainUrl}/category/aksiyon/" to "Aksiyon",
        "${mainUrl}/category/macera/" to "Macera",
        "${mainUrl}/category/romantik/" to "Romantik"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) request.data
        else request.data.removeSuffix("/") + "/page/$page/"

        val doc = app.get(targetUrl).document
        val items = doc.select(".listmovie, article, div.item, div.post, div.video-item")
            .mapNotNull { parseSearchItem(it) }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val targetUrl = if (page <= 1) "${mainUrl}/?s=${query}"
        else "${mainUrl}/page/$page/?s=${query}"

        val doc = app.get(targetUrl).document
        val items = doc.select(".listmovie, article, div.item, div.post, div.search-result")
            .mapNotNull { parseSearchItem(it) }
            .distinctBy { it.url }

        return newSearchResponseList(items, hasNext = items.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    fun parseSearchItem(element: Element): SearchResponse? {
        val linkEl = element.selectFirst(".poster a[href], a[href]") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null

        val imgEl = element.selectFirst("img")
        val title = element.selectFirst("h2, h3, .title, .entry-title a, .film-ismi a, a[title]")?.text()?.trim()
            ?: imgEl?.attr("alt")?.trim()
            ?: linkEl.attr("title").trim()
        if (title.isBlank()) return null

        val poster = fixUrlNull(
            imgEl?.attr("data-src")?.ifBlank { null }
                ?: imgEl?.attr("data-lazy-src")?.ifBlank { null }
                ?: imgEl?.attr("src")?.ifBlank { null }
        )

        val year = element.selectFirst(".film-yil, .year, .date")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val score = element.selectFirst(".bolum-ust, .imdb-score, .score")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
            this.score = Score.from10(score)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        return parseLoadMetadata(doc, url)
    }

    suspend fun parseLoadMetadata(doc: Document, url: String): LoadResponse? {
        val title = doc.selectFirst("h1.title-border, h1.entry-title, h1, .video-title, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.replace(" | YESILCAM TV", "")?.replace(" - Yeşilçam TV", "")?.replace(" İzle", "")?.trim() ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("div.film-afis img, div.poster img, .entry-content img, .film-bilgi img")?.let {
                    it.attr("data-src").ifBlank { null } ?: it.attr("src").ifBlank { null }
                }
        )

        val description = doc.selectFirst("#film-aciklama, div.singlecontent p, div.entry-content p, meta[property='og:description'], .video-desc")?.text()?.trim()
        val year = doc.selectFirst("a[href*='/yil/'], .film-yil, .year, .entry-date, span.date")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val tags = doc.select("div#listelements a[href*='/category/'], a[href*='/category/'], .tags a, .categories a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val actors = doc.select("a[href*='/oyuncu/'], .actors a, .cast a").map { Actor(it.text().trim()) }
        val score = doc.selectFirst(".bolum-ust, .imdb-score, .score, #listelements .elements")?.text()?.trim()
            ?.let { Regex("""IMDb:\s*([\d.,]+)""").find(it)?.groupValues?.get(1) }

        val response = newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.score = Score.from10(score)
        }
        response.addActors(actors)
        return response
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("DEBUG YesilCamTv: loadLinks data=$data")
        val doc = app.get(data).document
        var linksFound = false

        // 1. iframe'ler
        doc.select("iframe").forEach { iframe ->
            val rawSrc = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (rawSrc.isBlank() || rawSrc.startsWith("about:")) return@forEach

            val cleanUrl = rawSrc.substringBefore("#")
            println("DEBUG YesilCamTv: iframe -> $cleanUrl")

            if (cleanUrl.contains("rumble.com")) {
                if (extractRumbleLinks(cleanUrl, subtitleCallback, callback)) {
                    linksFound = true
                }
            } else {
                try {
                    val ok = loadExtractor(cleanUrl, referer = mainUrl, subtitleCallback) { link ->
                        callback(link)
                        linksFound = true
                    }
                    if (ok) linksFound = true
                } catch (e: Exception) {
                    println("DEBUG YesilCamTv: loadExtractor hatası -> ${e.message}")
                }
            }
        }

        // 2. Doğrudan video/mp4/m3u8
        doc.select("video source[src], video[src]").forEach { v ->
            val src = fixUrlNull(v.attr("src")) ?: return@forEach
            val isM3u8 = src.contains(".m3u8")
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name HD",
                    url = src,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.P1080.value
                }
            )
            linksFound = true
        }

        println("DEBUG YesilCamTv: linksFound=$linksFound")
        return linksFound
    }

    /**
     * Rumble embed sayfasından video linklerini çıkarır.
     * Öncelik: mp4 (ua.tar.*) > mp4 (u.tar) > HLS > timeline önizleme
     */
    private suspend fun extractRumbleLinks(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = Regex("""rumble\.com/embed/([a-zA-Z0-9]+)""")
            .find(embedUrl)?.groupValues?.get(1) ?: return false

        println("DEBUG Rumble: videoId=$videoId")

        val rumbleHeaders = mapOf(
            "Referer" to "https://rumble.com/",
            "Origin" to "https://rumble.com",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        val html = try {
            app.get("https://rumble.com/embed/$videoId/", referer = "https://rumble.com/", headers = rumbleHeaders).text
        } catch (e: Exception) {
            println("DEBUG Rumble: HTML hatası -> ${e.message}")
            return false
        }

        println("DEBUG Rumble: HTML uzunluk=${html.length}")

        val marker = """m.f["$videoId"]="""
        val startIdx = html.indexOf(marker)
        if (startIdx < 0) {
            println("DEBUG Rumble: marker bulunamadı")
            return false
        }

        val jsonStr = extractBalancedJson(html, startIdx + marker.length)
        if (jsonStr == null) {
            println("DEBUG Rumble: JSON çıkarılamadı")
            return false
        }

        println("DEBUG Rumble: JSON uzunluk=${jsonStr.length}")

        val json = try { JSONObject(jsonStr) } catch (e: Exception) {
            println("DEBUG Rumble: JSON parse hatası -> ${e.message}")
            return false
        }

        // Toplanan linkler: (url, type, quality)
        data class RumLink(val url: String, val type: ExtractorLinkType, val quality: Int, val label: String)
        val collected = mutableListOf<RumLink>()

        // Öncelik 1: ua.tar.* → yüksek kaliteli mp4'ler (en çok tercih edilen)
        json.optJSONObject("ua")?.optJSONObject("tar")?.let { tar ->
            val keys = tar.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val obj = tar.optJSONObject(k) ?: continue
                val url = obj.optString("url").takeIf { it.isNotBlank() } ?: continue
                val q = when {
                    k.contains("1080") -> Qualities.P1080.value
                    k.contains("720") -> Qualities.P720.value
                    k.contains("480") -> Qualities.P480.value
                    k.contains("360") -> Qualities.P360.value
                    k.contains("240") -> Qualities.P240.value
                    else -> Qualities.Unknown.value
                }
                collected.add(RumLink(url, ExtractorLinkType.VIDEO, q, "${k}p"))
            }
        }

        // Öncelik 2: u.tar → varsayılan yüksek kalite mp4
        json.optJSONObject("u")?.optJSONObject("tar")?.optString("url")?.takeIf { it.isNotBlank() }?.let {
            collected.add(RumLink(it, ExtractorLinkType.VIDEO, Qualities.P1080.value, "En Yüksek"))
        }

        // Öncelik 3: u.hls.url ve ua.hls.*
        json.optJSONObject("u")?.optJSONObject("hls")?.optString("url")?.takeIf { it.contains(".m3u8") }?.let {
            collected.add(RumLink(it, ExtractorLinkType.M3U8, Qualities.P1080.value, "HLS"))
        }
        json.optJSONObject("ua")?.optJSONObject("hls")?.let { hlsObj ->
            val keys = hlsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val obj = hlsObj.optJSONObject(k) ?: continue
                obj.optString("url").takeIf { it.contains(".m3u8") }?.let {
                    collected.add(RumLink(it, ExtractorLinkType.M3U8, Qualities.P720.value, "HLS"))
                }
            }
        }

        // Öncelik 4: timeline mp4 (düşük kalite önizleme - son çare)
        json.optJSONObject("u")?.optJSONObject("timeline")?.optString("url")?.takeIf { it.isNotBlank() }?.let {
            collected.add(RumLink(it, ExtractorLinkType.VIDEO, Qualities.P240.value, "240p"))
        }

        if (collected.isEmpty()) {
            println("DEBUG Rumble: video linki bulunamadı")
            return false
        }

        // Kaliteye göre yüksekten düşüğe sırala
        val sorted = collected.distinctBy { it.url }.sortedByDescending { it.quality }

        sorted.forEach { rumLink ->
            println("DEBUG Rumble: link kalite=${rumLink.label} url=${rumLink.url}")
            callback(
                newExtractorLink(
                    source = "Rumble",
                    name = "Rumble ${rumLink.label}",
                    url = rumLink.url,
                    type = rumLink.type
                ) {
                    this.referer = "https://rumble.com/"
                    this.quality = rumLink.quality
                    this.headers = rumbleHeaders
                }
            )
        }

        // Altyazılar
        val cc = json.optJSONArray("cc")
        if (cc != null) {
            for (i in 0 until cc.length()) {
                val c = cc.optJSONObject(i) ?: continue
                val ccUrl = c.optString("url")
                val ccLang = c.optString("language", "tr")
                if (ccUrl.isNotBlank()) {
                    subtitleCallback(newSubtitleFile(lang = ccLang, url = ccUrl))
                }
            }
        }

        return true
    }

    /**
     * Dengeli parantezle JSON bloğunu çıkarır.
     */
    private fun extractBalancedJson(text: String, startIdx: Int): String? {
        if (startIdx >= text.length || text[startIdx] != '{') return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in startIdx until text.length) {
            val c = text[i]
            if (escape) { escape = false; continue }
            if (inString) {
                when (c) {
                    '\\' -> escape = true
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(startIdx, i + 1)
                    }
                }
            }
        }
        return null
    }
}
