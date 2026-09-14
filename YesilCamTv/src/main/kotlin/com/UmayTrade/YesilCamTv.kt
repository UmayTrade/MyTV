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
                val regex = Regex("""IMDb:\s*([\d.,]+)""")
                regex.find(text)?.groupValues?.get(1)
            }

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
        val doc = app.get(data).document
        var linksFound = false

        // 1. Tüm iframe'ler (Rumble, YouTube, Ok.ru vb.)
        //    Rumble URL'leri artık kayıtlı RumbleExtractor tarafından otomatik çözülür.
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isNotBlank() && !src.startsWith("about:")) {
                val fixed = fixUrl(src)
                try {
                    val success = loadExtractor(fixed, referer = mainUrl, subtitleCallback) { link ->
                        callback(link)
                        linksFound = true
                    }
                    if (success) linksFound = true
                } catch (e: Exception) {
                    // yoksay, sonraki iframe'e geç
                }
            }
        }

        // 2. Direct HTML5 video / mp4 / m3u8
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
}
