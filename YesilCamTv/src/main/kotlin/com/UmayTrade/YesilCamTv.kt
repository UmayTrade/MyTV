package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
        else "${request.data.removeSuffix("/")}/page/$page/"

        val doc = app.get(targetUrl).document
        val items = doc.select(".listmovie, article, div.item, div.post, div.video-item")
            .mapNotNull { parseSearchItem(it) }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val targetUrl = if (page <= 1) "${mainUrl}/?s=$query"
        else "${mainUrl}/page/$page/?s=$query"

        val doc = app.get(targetUrl).document
        val items = doc.select(".listmovie, article, div.item, div.post, div.search-result")
            .mapNotNull { parseSearchItem(it) }
            .distinctBy { it.url }

        return newSearchResponseList(items, hasNext = items.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query, 1).items

    private fun parseSearchItem(element: Element): SearchResponse? {
        val linkEl = element.selectFirst(".poster a[href], a[href]") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null

        val imgEl = element.selectFirst("img")
        val title = element.selectFirst("h2, h3, .title, .entry-title a, .film-ismi a, a[title]")
            ?.text()?.trim()
            ?: imgEl?.attr("alt")?.trim()
            ?: linkEl.attr("title").trim()
        if (title.isBlank()) return null

        val poster = fixUrlNull(
            imgEl?.attr("data-src")?.ifBlank { null }
                ?: imgEl?.attr("data-lazy-src")?.ifBlank { null }
                ?: imgEl?.attr("src")?.ifBlank { null }
        )

        val year = element.selectFirst(".film-yil, .year, .date")
            ?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
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

    private fun parseLoadMetadata(doc: Document, url: String): LoadResponse? {
        val title = doc.selectFirst("h1.title-border, h1.entry-title, h1, .video-title, meta[property='og:title']")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text().trim() }
            ?.replace(" | YESILCAM TV", "")
            ?.replace(" - Yeşilçam TV", "")
            ?.replace(" İzle", "")
            ?.trim()
            ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("div.film-afis img, div.poster img, .entry-content img")
                    ?.let { it.attr("data-src").ifBlank { null } ?: it.attr("src").ifBlank { null } }
        )

        val description = doc.selectFirst(
            "#film-aciklama, div.singlecontent p, div.entry-content p, meta[property='og:description']"
        )?.text()?.trim()

        val year = doc.selectFirst("a[href*='/yil/'], .film-yil, .year, .entry-date")
            ?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val tags = doc.select("#listelements a[href*='/category/'], a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = doc.select("a[href*='/oyuncu/']")
            .map { Actor(it.text().trim()) }

        val score = doc.selectFirst("#listelements .elements")
            ?.text()?.trim()
            ?.let { Regex("""IMDb:\s*([\d.,]+)""").find(it)?.groupValues?.get(1) }

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

        // Sayfadaki tüm iframe'leri topla (Rumble, YouTube, Ok.ru, vs.)
        val iframes = doc.select("iframe").mapNotNull {
            val src = it.attr("data-src").ifEmpty { it.attr("src") }
            if (src.isBlank() || src.startsWith("about:")) null
            else fixUrl(src)
        }.distinct()

        // Her iframe için CloudStream'in extractor zincirini çalıştır.
        // Rumble iframe'leri artık kayıtlı RumbleExtractor tarafından çözülür.
        for (iframeUrl in iframes) {
            try {
                val ok = loadExtractor(
                    iframeUrl,
                    referer = mainUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                if (ok) linksFound = true
            } catch (_: Exception) {
                // bir sonraki iframe'e geç
            }
        }

        // Sayfada doğrudan <video> etiketi varsa onu da ekle (nadir)
        doc.select("video source[src], video[src]").forEach { v ->
            val src = fixUrlNull(v.attr("src")) ?: return@forEach
            val isM3u8 = src.contains(".m3u8")
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Direct",
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
}
