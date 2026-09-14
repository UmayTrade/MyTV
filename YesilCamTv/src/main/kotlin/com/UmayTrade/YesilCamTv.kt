package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject

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
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            val base = request.data.removeSuffix("/")
            "$base/page/$page/"
        }

        val doc = app.get(targetUrl).document
        val items = doc.select(".listmovie, article, div.item, div.post, div.video-item").mapNotNull { el ->
            parseSearchItem(el)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val targetUrl = if (page <= 1) {
            "${mainUrl}/?s=${query}"
        } else {
            "${mainUrl}/page/$page/?s=${query}"
        }

        val doc = app.get(targetUrl).document
        val items = doc.select(".listmovie, article, div.item, div.post, div.search-result").mapNotNull { el ->
            parseSearchItem(el)
        }.distinctBy { it.url }

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
                    it.attr("data-src").ifBlank { null }
                        ?: it.attr("src").ifBlank { null }
                }
        )

        val description = doc.selectFirst("#film-aciklama, div.singlecontent p, div.entry-content p, meta[property='og:description'], .video-desc")?.text()?.trim()

        val year = doc.selectFirst("a[href*='/yil/'], .film-yil, .year, .entry-date, span.date")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val tags = doc.select("div#listelements a[href*='/category/'], a[href*='/category/'], .tags a, .categories a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = doc.select("a[href*='/oyuncu/'], .actors a, .cast a").map { Actor(it.text().trim()) }

        val score = doc.selectFirst(".bolum-ust, .imdb-score, .score, #listelements .elements")?.text()?.trim()
            ?.let { text ->
                Regex("""IMDb:\s*([\d.,]+)""").find(text)?.groupValues?.get(1)
            }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.score = Score.from10(score)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var linksFound = false

        // 1. Tüm iframe'leri topla (Rumble, YouTube, Ok.ru vb.)
        val iframeUrls = mutableListOf<String>()
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isNotBlank() && !src.startsWith("about:")) {
                iframeUrls.add(fixUrl(src))
            }
        }

        // 2. Her iframe için extractor dene
        for (iframeUrl in iframeUrls.distinct()) {
            val isRumble = iframeUrl.contains("rumble.com")
            val refererForExtractor = if (isRumble) "https://rumble.com/" else mainUrl

            try {
                val success = loadExtractor(iframeUrl, referer = refererForExtractor, subtitleCallback) { link ->
                    callback(link)
                    linksFound = true
                }
                if (success) linksFound = true
            } catch (e: Exception) {
                // yoksay
            }
        }

        // 3. Rumble için manuel API çözümü (loadExtractor başarısız olursa)
        for (iframeUrl in iframeUrls) {
            if (iframeUrl.contains("rumble.com")) {
                val rumbleId = extractRumbleId(iframeUrl)
                if (rumbleId != null) {
                    if (extractRumbleLinksManuel(rumbleId, subtitleCallback, callback)) {
                        linksFound = true
                    }
                }
            }
        }

        // 4. Direct HTML5 video / mp4 / m3u8
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

        return linksFound
    }

    private fun extractRumbleId(url: String): String? {
        return Regex("""rumble\.com/embed/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
    }

    /**
     * Rumble için güncel manuel çözüm.
     * Birden fazla endpoint'i dener.
     */
    private suspend fun extractRumbleLinksManuel(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rumbleHeaders = mapOf(
            "Referer" to "https://rumble.com/",
            "Origin" to "https://rumble.com",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*"
        )

        // Denenecek API endpoint'leri (Rumble zaman zaman değiştiriyor)
        val endpoints = listOf(
            "https://rumble.com/embedJS/u3/?request=video&ver=2&v=$videoId",
            "https://rumble.com/embedJS/VideoPlayback/?request=video&ver=2&v=$videoId",
            "https://rumble.com/-/api/video/$videoId"
        )

        for (endpoint in endpoints) {
            try {
                val response = app.get(
                    endpoint,
                    referer = "https://rumble.com/",
                    headers = rumbleHeaders
                ).text

                if (response.isBlank() || response.length < 10) continue

                // JSON parse dene
                val json = try {
                    JSONObject(response)
                } catch (e: Exception) {
                    continue
                }

                var found = false

                // "u" objesi: kalite -> url map
                val u = json.optJSONObject("u")
                if (u != null) {
                    val keys = u.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = u.optString(key)
                        if (value.contains(".mp4") || value.contains(".m3u8")) {
                            callback(
                                newExtractorLink(
                                    source = "Rumble",
                                    name = "Rumble ${parseQuality(key)}",
                                    url = value,
                                    type = if (value.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://rumble.com/"
                                    this.quality = qualityToValue(key)
                                    this.headers = rumbleHeaders
                                }
                            )
                            found = true
                        }
                    }
                }

                // "ua" objesi
                val ua = json.optJSONObject("ua")
                if (ua != null) {
                    val keys = ua.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = ua.optString(key)
                        if (value.contains(".mp4") || value.contains(".m3u8")) {
                            callback(
                                newExtractorLink(
                                    source = "Rumble",
                                    name = "Rumble ${parseQuality(key)}",
                                    url = value,
                                    type = if (value.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://rumble.com/"
                                    this.quality = qualityToValue(key)
                                    this.headers = rumbleHeaders
                                }
                            )
                            found = true
                        }
                    }
                }

                // "s" objesi (bazı videolarda)
                val s = json.optJSONObject("s")
                if (s != null) {
                    val keys = s.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = s.optString(key)
                        if (value.contains(".mp4") || value.contains(".m3u8")) {
                            callback(
                                newExtractorLink(
                                    source = "Rumble",
                                    name = "Rumble ${parseQuality(key)}",
                                    url = value,
                                    type = if (value.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://rumble.com/"
                                    this.quality = qualityToValue(key)
                                    this.headers = rumbleHeaders
                                }
                            )
                            found = true
                        }
                    }
                }

                // Altyazılar
                val cc = json.optJSONObject("cc")
                if (cc != null) {
                    val keys = cc.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = cc.optString(key)
                        if (value.contains(".vtt") || value.contains(".srt")) {
                            subtitleCallback(newSubtitleFile(lang = key, url = value))
                        }
                    }
                }

                if (found) return true
            } catch (e: Exception) {
                // Sonraki endpoint'i dene
            }
        }

        return false
    }

    private fun parseQuality(key: String): String {
        return when {
            key.contains("1080") -> "1080p"
            key.contains("720") -> "720p"
            key.contains("480") -> "480p"
            key.contains("360") -> "360p"
            key.contains("240") -> "240p"
            else -> key
        }
    }

    private fun qualityToValue(key: String): Int {
        return when {
            key.contains("1080") -> Qualities.P1080.value
            key.contains("720") -> Qualities.P720.value
            key.contains("480") -> Qualities.P480.value
            key.contains("360") -> Qualities.P360.value
            key.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }
}
