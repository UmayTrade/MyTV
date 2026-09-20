package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.cloudstream.tr.core.concurrency.BoundedParallelResolver
import com.cloudstream.tr.core.model.ProviderModels
import com.cloudstream.tr.core.network.StreamValidator
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DramaDizilerim : MainAPI() {
    override var mainUrl = "https://dramadizilerim.com"
    override var name = "DramaDizilerim"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "${mainUrl}/dizi" to "Tüm Diziler",
        "${mainUrl}" to "Trend Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            "${request.data}?page=${page}"
        }

        val document = app.get(targetUrl).document
        val items = parseSearchResults(document)
        return newHomePageResponse(request.name, items)
    }

    fun toSearchResult(element: Element): SearchResponse? {
        val link = if (element.tagName() == "a") element else element.selectFirst("a[href*='/dizi/']") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (!href.contains("/dizi/")) return null

        val img = element.selectFirst("img")
        val title = img?.attr("alt")?.trim()
            ?.ifBlank { null }
            ?: element.selectFirst(".title, h3, h2, h4, span")?.text()?.trim()
            ?: link.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val posterUrl = fixUrlNull(
            img?.attr("src")?.ifBlank { null }
                ?: img?.attr("data-src")?.ifBlank { null }
        )

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val targetUrl = if (page <= 1) {
            "${mainUrl}/search?q=${query}"
        } else {
            "${mainUrl}/search?q=${query}&page=${page}"
        }

        val document = app.get(targetUrl).document
        val items = parseSearchResults(document)
        return newSearchResponseList(items, hasNext = false)
    }

    fun parseSearchResults(document: Document): List<SearchResponse> {
        val elements = document.select("a[href*='/dizi/']")
        val results = elements.mapNotNull { toSearchResult(it) }
        return ProviderModels.dedupSearchResults(results)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        return parseLoadMetadata(document, url)
    }

    suspend fun parseLoadMetadata(document: Document, url: String): LoadResponse? {
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore("|").trim()
        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img.poster, div.dizi-poster img, img[src*='upload']")?.let {
                    it.attr("src").ifBlank { it.attr("data-src") }
                }
        )
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("div.description, div.ozet, p.summary")?.text()?.trim()

        val episodeElements = document.select("a[href*='/izle/']")
        val episodes = mutableListOf<Episode>()

        val sRegex = Regex("""[?&]s=(\d+)""")
        val eRegex = Regex("""[?&]e=(\d+)""")
        val seenUrls = mutableSetOf<String>()

        for (el in episodeElements) {
            val href = fixUrlNull(el.attr("href")) ?: continue
            if (!seenUrls.add(href)) continue

            val seasonNum = sRegex.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val epNum = eRegex.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

            val epName = el.text().trim().takeIf { it.isNotBlank() && !it.contains("Şimdi İzle") }
                ?: "$seasonNum. Sezon $epNum. Bölüm"

            episodes.add(
                newEpisode(href) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val document = app.get(data).document

        // Look for embed.php data-src in watch page
        val embedUrls = mutableListOf<String>()
        document.select("div[data-src*='embed.php'], [data-src*='embed']").forEach {
            val src = fixUrlNull(it.attr("data-src"))
            if (!src.isNullOrBlank()) {
                embedUrls.add(src)
            }
        }

        document.select("iframe[src]").forEach {
            val src = fixUrlNull(it.attr("src"))
            if (!src.isNullOrBlank()) {
                embedUrls.add(src)
            }
        }

        val candidates = embedUrls.distinct()

        val count = BoundedParallelResolver.resolveProgressive(
            candidates = candidates,
            resolver = { embedUrl, emitLink ->
                try {
                    val embedDoc = app.get(embedUrl, headers = mapOf("Referer" to data)).document
                    val html = embedDoc.html()

                    // Extract direct video source
                    val sourceRegex = Regex("""let\s+source\s*=\s*["']([^"']+)["']""")
                    val sourceMatch = sourceRegex.find(html)
                    val rawVideoUrl = sourceMatch?.groupValues?.getOrNull(1)

                    if (!rawVideoUrl.isNullOrBlank()) {
                        val preflight = StreamValidator.validateStream(rawVideoUrl, mapOf("Referer" to embedUrl), name)
                        if (preflight.isValid) {
                            val typeTag = if (preflight.streamType == ExtractorLinkType.M3U8) "HLS" else "MP4"
                            emitLink(
                                newExtractorLink(
                                    source = name,
                                    name = "$name $typeTag",
                                    url = rawVideoUrl,
                                    type = preflight.streamType
                                ) {
                                    this.referer = embedUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }

                    // Extract subtitles
                    val subRegex = Regex("""<track[^>]+src=["']([^"']+)["']""")
                    subRegex.findAll(html).forEach { subMatch ->
                        val subUrl = fixUrlNull(subMatch.groupValues[1])
                        if (!subUrl.isNullOrBlank()) {
                            subtitleCallback(
                                newSubtitleFile(
                                    lang = "tr",
                                    url = subUrl
                                )
                            )
                        }
                    }
                } catch (_: Exception) {}
            },
            onLinkFound = { link ->
                callback(link)
                found = true
            }
        )

        return found || count > 0
    }
}
