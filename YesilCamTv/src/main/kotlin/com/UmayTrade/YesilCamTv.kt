package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

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

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            val base = request.data.removeSuffix("/")
            "$base/page/$page/"
        }

        val doc = app.get(targetUrl, headers = browserHeaders).document
        val items = doc.select(
            ".listmovie, article, div.item, div.post, div.video-item, " +
                ".film, .movie-item, .movies, .film-item"
        ).mapNotNull { parseSearchItem(it) }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val targetUrl = if (page <= 1) {
            "${mainUrl}/?s=${enc(query)}"
        } else {
            "${mainUrl}/page/$page/?s=${enc(query)}"
        }

        val doc = app.get(targetUrl, headers = browserHeaders).document
        val items = doc.select(
            ".listmovie, article, div.item, div.post, div.search-result, " +
                ".film, .movie-item, .film-item"
        ).mapNotNull { parseSearchItem(it) }
            .distinctBy { it.url }

        return newSearchResponseList(
            items,
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query, 1).items

    private fun parseSearchItem(element: Element): SearchResponse? {
        val linkEl = element.selectFirst(
            ".poster a[href], .film-poster a[href], .movie-poster a[href], a[href]"
        ) ?: return null

        val href = fixUrlNull(linkEl.attr("href")) ?: return null

        val imgEl = element.selectFirst("img")
        val title = element.selectFirst(
            "h2, h3, .title, .entry-title a, .film-ismi a, " +
                ".movie-title, .film-title, a[title]"
        )?.text()?.trim()
            ?: imgEl?.attr("alt")?.trim()
            ?: linkEl.attr("title").trim()

        if (title.isNullOrBlank()) return null

        val poster = fixUrlNull(
            imgEl?.attr("data-src")?.ifBlank { null }
                ?: imgEl?.attr("data-lazy-src")?.ifBlank { null }
                ?: imgEl?.attr("data-original")?.ifBlank { null }
                ?: imgEl?.attr("src")?.ifBlank { null }
        )

        val year = element.selectFirst(
            ".film-yil, .year, .date, .film-year, .movie-year"
        )?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val score = element.selectFirst(
            ".bolum-ust, .imdb-score, .score, .imdb"
        )?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
            this.score = Score.from10(score)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = browserHeaders).document
        return parseLoadMetadata(doc, url)
    }

    private suspend fun parseLoadMetadata(
        doc: Document,
        url: String
    ): LoadResponse? {
        val title = doc.selectFirst(
            "h1.title-border, h1.entry-title, h1, .video-title, " +
                "meta[property='og:title']"
        )?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.replace(" | YESILCAM TV", "")
            ?.replace(" - Yeşilçam TV", "")
            ?.replace(" İzle", "")
            ?.trim()
            ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(
                    "div.film-afis img, div.poster img, .entry-content img, " +
                        ".film-bilgi img, .movie-poster img"
                )?.let {
                    it.attr("data-src").ifBlank { null }
                        ?: it.attr("data-lazy-src").ifBlank { null }
                        ?: it.attr("src").ifBlank { null }
                }
        )

        val description = doc.selectFirst(
            "#film-aciklama, div.singlecontent p, div.entry-content p, " +
                "meta[property='og:description'], .video-desc"
        )?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim()

        val year = doc.selectFirst(
            "a[href*='/yil/'], .film-yil, .year, .entry-date, span.date"
        )?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val tags = doc.select(
            "div#listelements a[href*='/category/'], " +
                "a[href*='/category/'], .tags a, .categories a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = doc.select(
            "a[href*='/oyuncu/'], .actors a, .cast a"
        ).mapNotNull {
            val actor = it.text().trim()
            actor.takeIf { name -> name.isNotBlank() }?.let { name -> Actor(name) }
        }

        val score = doc.selectFirst(
            ".bolum-ust, .imdb-score, .score, #listelements .elements, .imdb"
        )?.text()?.trim()?.let { text ->
            Regex("""IMDb:\s*([\d.,]+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)
                ?: Regex("""\b([\d][\d.,]?)\s*/\s*10\b""")
                    .find(text)?.groupValues?.getOrNull(1)
        }

        val response = newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
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
        val doc = try {
            app.get(data, headers = browserHeaders).document
        } catch (_: Exception) {
            return false
        }

        var linksFound = false
        val visited = HashSet<String>()

        suspend fun sendToExtractor(rawUrl: String, referer: String = data) {
            val fixed = fixUrlNull(rawUrl) ?: return
            if (fixed.isBlank() || fixed.startsWith("about:") || !visited.add(fixed)) return

            try {
                val success = loadExtractor(
                    fixed,
                    referer = referer,
                    subtitleCallback = subtitleCallback
                ) { link ->
                    callback(link)
                    linksFound = true
                }

                if (success) linksFound = true
            } catch (_: Exception) {
                // Try direct URL parsing below.
            }
        }

        suspend fun addDirect(url: String) {
            val fixed = fixUrlNull(url) ?: return
            if (!visited.add(fixed)) return

            val lower = fixed.lowercase()
            val isM3u8 = lower.contains(".m3u8")
            val isMp4 = lower.contains(".mp4")

            if (!isM3u8 && !isMp4) return

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name HD",
                    url = fixed,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = data
                    this.quality = Qualities.P1080.value
                }
            )
            linksFound = true
        }

        // 1) iframe / embed / lazy iframe attributes
        doc.select(
            "iframe, embed, object, [data-src], [data-url], [data-embed], " +
                "[data-video], [data-player], [data-iframe]"
        ).forEach { element ->
            val candidates = listOf(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("data-embed"),
                element.attr("data-video"),
                element.attr("data-player"),
                element.attr("data-iframe"),
                element.attr("value")
            )

            for (candidate in candidates) {
                if (candidate.isBlank()) continue
                val fixed = fixUrlNull(candidate) ?: continue

                when {
                    isDirectMedia(fixed) -> addDirect(fixed)
                    isSupportedEmbed(fixed) -> sendToExtractor(fixed, data)
                }
            }
        }

        // 2) HTML5 video/source
        doc.select("video, video source, source").forEach { element ->
            listOf(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-url")
            ).filter { it.isNotBlank() }.forEach { addDirect(it) }
        }

        // 3) Known provider URLs and player URLs embedded in attributes/text.
        val rawHtml = doc.html()
        for (providerUrl in extractProviderUrls(rawHtml)) {
            sendToExtractor(providerUrl, data)
        }

        // 4) Direct HLS/MP4 URLs inside page source.
        for (directUrl in extractDirectMediaUrls(rawHtml)) {
            addDirect(directUrl)
        }

        // 5) Some WordPress players expose the source in a script JSON block.
        doc.select("script").forEach { script ->
            val scriptText = script.data().ifBlank { script.html() }

            for (providerUrl in extractProviderUrls(scriptText)) {
                sendToExtractor(providerUrl, data)
            }

            for (directUrl in extractDirectMediaUrls(scriptText)) {
                addDirect(directUrl)
            }
        }

        return linksFound
    }

    private fun isDirectMedia(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4")
    }

    private fun isSupportedEmbed(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("rumble.com/") ||
            lower.contains("youtube.com/") ||
            lower.contains("youtu.be/") ||
            lower.contains("dailymotion.com/") ||
            lower.contains("vimeo.com/")
    }

    private fun extractProviderUrls(text: String): Set<String> {
        val result = LinkedHashSet<String>()

        val patterns = listOf(
            Regex(
                """https?://(?:www\.)?rumble\.com/(?:embed/|v?[a-zA-Z0-9_-]+\.html)[^"'\\\s<>]*""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """https?://(?:www\.)?(?:youtube\.com/(?:embed/|watch\?v=)|youtu\.be/)[^"'\\\s<>]+""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """https?://(?:www\.)?dailymotion\.com/video/[^"'\\\s<>]+""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """https?://(?:www\.)?vimeo\.com/[^"'\\\s<>]+""",
                RegexOption.IGNORE_CASE
            )
        )

        patterns.forEach { regex ->
            regex.findAll(text).forEach {
                result.add(
                    it.value
                        .replace("\\/", "/")
                        .replace("&amp;", "&")
                        .trim('"', '\'')
                )
            }
        }

        return result
    }

    private fun extractDirectMediaUrls(text: String): Set<String> {
        val result = LinkedHashSet<String>()

        val regex = Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        regex.findAll(text).forEach {
            result.add(
                it.value
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("&amp;", "&")
                    .trim('"', '\'')
            )
        }

        return result
    }
}
