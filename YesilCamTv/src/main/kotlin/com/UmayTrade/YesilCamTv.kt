package com.UmayTrade

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.ExtractorLinkType
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.app
import com.lagradost.cloudstream3.utils.fixUrlNull
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newHomePageResponse
import com.lagradost.cloudstream3.utils.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.newSearchResponseList
import com.lagradost.cloudstream3.utils.Score
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class YesilCamTv : MainAPI() {

    override var mainUrl = "https://yesilcamtv.com.tr"

    override var name = "YesilCamTv"

    override val hasMainPage = true

    override var lang = "tr"

    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie
    )

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

        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/140.0.0.0 Safari/537.36",

        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9," +
            "image/avif,image/webp,image/apng,*/*;q=0.8",

        "Accept-Language" to
            "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",

        "Cache-Control" to "no-cache",

        "Pragma" to "no-cache"
    )

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

        val doc = app.get(
            targetUrl,
            headers = browserHeaders
        ).document

        val items = doc.select(
            ".listmovie, article, div.item, div.post, div.video-item, " +
                ".film, .movie-item, .movies, .film-item, " +
                ".movie, .film"
        )
            .mapNotNull {
                parseSearchItem(it)
            }
            .distinctBy {
                it.url
            }

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

        /*
         * CloudStream sürümünde urlEncode extension bulunmadığı
         * için standart Java URL encoder kullanıyoruz.
         */
        val encodedQuery = URLEncoder
            .encode(query, "UTF-8")
            .replace("+", "%20")

        val targetUrl = if (page <= 1) {

            "${mainUrl}/?s=$encodedQuery"

        } else {

            "${mainUrl}/page/$page/?s=$encodedQuery"
        }

        val doc = app.get(
            targetUrl,
            headers = browserHeaders
        ).document

        val items = doc.select(
            ".listmovie, article, div.item, div.post, div.search-result, " +
                ".film, .movie-item, .film-item, .movie, .movies"
        )
            .mapNotNull {
                parseSearchItem(it)
            }
            .distinctBy {
                it.url
            }

        return newSearchResponseList(
            items,
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse>? {

        return search(
            query = query,
            page = 1
        ).items
    }

    private fun parseSearchItem(
        element: Element
    ): SearchResponse? {

        val linkEl = element.selectFirst(
            ".poster a[href], " +
                ".film-poster a[href], " +
                ".movie-poster a[href], " +
                ".thumb a[href], " +
                "a[href]"
        ) ?: return null

        val href = fixUrlNull(
            linkEl.attr("href")
        ) ?: return null

        if (
            href.startsWith("#") ||
            href.startsWith("javascript:")
        ) {
            return null
        }

        val imgEl = element.selectFirst("img")

        val title = element.selectFirst(
            "h2, h3, h4, " +
                ".title, " +
                ".entry-title a, " +
                ".film-ismi a, " +
                ".movie-title, " +
                ".film-title, " +
                ".movie-name, " +
                ".film-name, " +
                "a[title]"
        )
            ?.text()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: imgEl
                ?.attr("alt")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
            ?: linkEl
                .attr("title")
                .trim()
                .takeIf {
                    it.isNotBlank()
                }
            ?: return null

        val poster = fixUrlNull(

            imgEl
                ?.attr("data-src")
                ?.ifBlank {
                    null
                }

                ?: imgEl
                    ?.attr("data-lazy-src")
                    ?.ifBlank {
                        null
                    }

                ?: imgEl
                    ?.attr("data-original")
                    ?.ifBlank {
                        null
                    }

                ?: imgEl
                    ?.attr("data-image")
                    ?.ifBlank {
                        null
                    }

                ?: imgEl
                    ?.attr("src")
                    ?.ifBlank {
                        null
                    }
        )

        val year = element.selectFirst(
            ".film-yil, " +
                ".year, " +
                ".date, " +
                ".film-year, " +
                ".movie-year, " +
                ".release-year"
        )
            ?.text()
            ?.filter {
                it.isDigit()
            }
            ?.take(4)
            ?.toIntOrNull()

        val score = element.selectFirst(
            ".bolum-ust, " +
                ".imdb-score, " +
                ".score, " +
                ".imdb, " +
                ".rating"
        )
            ?.text()
            ?.trim()

        return newMovieSearchResponse(
            title,
            href,
            TvType.Movie
        ) {

            posterUrl = poster

            this.year = year

            this.score = Score.from10(score)
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val doc = try {

            app.get(
                url,
                headers = browserHeaders
            ).document

        } catch (_: Exception) {

            return null
        }

        return parseLoadMetadata(
            doc = doc,
            url = url
        )
    }

    /*
     * ÖNEMLİ:
     *
     * newMovieLoadResponse() suspend olduğu için
     * bu fonksiyon da suspend olmalı.
     */
    private suspend fun parseLoadMetadata(
        doc: Document,
        url: String
    ): LoadResponse? {

        val title = doc.selectFirst(
            "h1.title-border, " +
                "h1.entry-title, " +
                "h1, " +
                ".video-title, " +
                ".film-title, " +
                "meta[property='og:title']"
        )
            ?.let {

                if (it.tagName() == "meta") {

                    it.attr("content")

                } else {

                    it.text().trim()
                }
            }
            ?.replace(
                " | YESILCAM TV",
                "",
                ignoreCase = true
            )
            ?.replace(
                " - Yeşilçam TV",
                "",
                ignoreCase = true
            )
            ?.replace(
                " İzle",
                "",
                ignoreCase = true
            )
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: return null

        val poster = fixUrlNull(

            doc.selectFirst(
                "meta[property='og:image']"
            )
                ?.attr("content")

                ?: doc.selectFirst(
                    "div.film-afis img, " +
                        "div.poster img, " +
                        ".entry-content img, " +
                        ".film-bilgi img, " +
                        ".movie-poster img, " +
                        ".film-poster img"
                )
                    ?.let {

                        it.attr("data-src")
                            .ifBlank {
                                null
                            }

                            ?: it.attr("data-lazy-src")
                                .ifBlank {
                                    null
                                }

                            ?: it.attr("data-original")
                                .ifBlank {
                                    null
                                }

                            ?: it.attr("src")
                                .ifBlank {
                                    null
                                }
                    }
        )

        val description = doc.selectFirst(
            "#film-aciklama, " +
                "div.singlecontent p, " +
                "div.entry-content p, " +
                "meta[property='og:description'], " +
                ".video-desc, " +
                ".film-description"
        )
            ?.let {

                if (it.tagName() == "meta") {

                    it.attr("content")

                } else {

                    it.text()
                }
            }
            ?.trim()

        val year = doc.selectFirst(
            "a[href*='/yil/'], " +
                ".film-yil, " +
                ".year, " +
                ".entry-date, " +
                "span.date, " +
                ".film-year"
        )
            ?.text()
            ?.filter {
                it.isDigit()
            }
            ?.take(4)
            ?.toIntOrNull()

        val tags = doc.select(
            "div#listelements a[href*='/category/'], " +
                "a[href*='/category/'], " +
                ".tags a, " +
                ".categories a, " +
                ".genres a"
        )
            .map {
                it.text().trim()
            }
            .filter {
                it.isNotBlank()
            }
            .distinct()

        val actors = doc.select(
            "a[href*='/oyuncu/'], " +
                ".actors a, " +
                ".cast a, " +
                ".oyuncular a"
        )
            .mapNotNull {

                val actorName = it
                    .text()
                    .trim()

                actorName.takeIf {
                    it.isNotBlank()
                }?.let {
                    Actor(it)
                }
            }
            .distinctBy {
                it.name
            }

        val scoreText = doc.selectFirst(
            ".bolum-ust, " +
                ".imdb-score, " +
                ".score, " +
                "#listelements .elements, " +
                ".imdb, " +
                ".rating"
        )
            ?.text()
            ?.trim()

        val score = scoreText?.let {

            Regex(
                """IMDb:\s*([\d.,]+)""",
                RegexOption.IGNORE_CASE
            )
                .find(it)
                ?.groupValues
                ?.getOrNull(1)

                ?: Regex(
                    """\b([\d][\d.,]?)\s*/\s*10\b"""
                )
                    .find(it)
                    ?.groupValues
                    ?.getOrNull(1)
        }

        val response = newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {

            posterUrl = poster

            plot = description

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

            app.get(
                data,
                headers = browserHeaders
            ).document

        } catch (_: Exception) {

            return false
        }

        var linksFound = false

        val visited = HashSet<String>()

        /*
         * loadExtractor suspend olduğu için
         * yardımcı fonksiyon da suspend.
         */
        suspend fun sendToExtractor(
            rawUrl: String,
            referer: String = data
        ) {

            val fixed = fixUrlNull(
                rawUrl
            ) ?: return

            if (
                fixed.isBlank() ||
                fixed.startsWith("about:") ||
                fixed.startsWith("javascript:")
            ) {
                return
            }

            if (!visited.add(fixed)) {
                return
            }

            try {

                val success = loadExtractor(
                    url = fixed,
                    referer = referer,
                    subtitleCallback = subtitleCallback
                ) { link ->

                    callback(link)

                    linksFound = true
                }

                if (success) {
                    linksFound = true
                }

            } catch (_: Exception) {
                // Aşağıdaki direct-media kontrolleri devam eder.
            }
        }

        fun addDirect(
            url: String
        ) {

            val fixed = fixUrlNull(
                url
            ) ?: return

            if (!visited.add(fixed)) {
                return
            }

            val lower = fixed.lowercase()

            val isM3u8 = lower.contains(
                ".m3u8"
            )

            val isMp4 = lower.contains(
                ".mp4"
            )

            if (!isM3u8 && !isMp4) {
                return
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name HD",
                    url = fixed,
                    type = if (isM3u8) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                ) {

                    referer = data

                    quality = Qualities.P1080.value
                }
            )

            linksFound = true
        }

        /*
         * 1) iframe / embed / lazy iframe
         */
        val embedElements = doc.select(
            "iframe, " +
                "embed, " +
                "object, " +
                "[data-src], " +
                "[data-url], " +
                "[data-embed], " +
                "[data-video], " +
                "[data-player], " +
                "[data-iframe], " +
                "[data-source]"
        )

        for (element in embedElements) {

            val candidates = listOf(

                element.attr("src"),

                element.attr("data-src"),

                element.attr("data-url"),

                element.attr("data-embed"),

                element.attr("data-video"),

                element.attr("data-player"),

                element.attr("data-iframe"),

                element.attr("data-source"),

                element.attr("value")
            )

            for (candidate in candidates) {

                if (candidate.isBlank()) {
                    continue
                }

                val fixed = fixUrlNull(
                    candidate
                ) ?: continue

                when {

                    isDirectMedia(fixed) -> {
                        addDirect(fixed)
                    }

                    isSupportedEmbed(fixed) -> {
                        sendToExtractor(
                            rawUrl = fixed,
                            referer = data
                        )
                    }
                }
            }
        }

        /*
         * 2) HTML5 video/source
         */
        val videoElements = doc.select(
            "video, video source, source"
        )

        for (element in videoElements) {

            val candidates = listOf(

                element.attr("src"),

                element.attr("data-src"),

                element.attr("data-url"),

                element.attr("data-source")
            )

            candidates
                .filter {
                    it.isNotBlank()
                }
                .forEach {
                    addDirect(it)
                }
        }

        /*
         * 3) Sayfa HTML'i içinde bulunan player/provider URL'leri
         */
        val rawHtml = doc.html()

        val providerUrls = extractProviderUrls(
            rawHtml
        )

        for (providerUrl in providerUrls) {

            sendToExtractor(
                rawUrl = providerUrl,
                referer = data
            )
        }

        /*
         * 4) Sayfa kaynağındaki direkt MP4/M3U8
         */
        extractDirectMediaUrls(
            rawHtml
        ).forEach {
            addDirect(it)
        }

        /*
         * 5) Script JSON / JavaScript player kaynakları
         */
        val scripts = doc.select(
            "script"
        )

        for (script in scripts) {

            val scriptText = script
                .data()
                .ifBlank {
                    script.html()
                }

            extractProviderUrls(
                scriptText
            ).forEach {

                sendToExtractor(
                    rawUrl = it,
                    referer = data
                )
            }

            extractDirectMediaUrls(
                scriptText
            ).forEach {
                addDirect(it)
            }
        }

        return linksFound
    }

    private fun isDirectMedia(
        url: String
    ): Boolean {

        val lower = url.lowercase()

        return lower.contains(".m3u8") ||
            lower.contains(".mp4")
    }

    private fun isSupportedEmbed(
        url: String
    ): Boolean {

        val lower = url.lowercase()

        return lower.contains(
            "rumble.com/"
        ) ||
            lower.contains(
                "youtube.com/"
            ) ||
            lower.contains(
                "youtu.be/"
            ) ||
            lower.contains(
                "dailymotion.com/"
            ) ||
            lower.contains(
                "vimeo.com/"
            )
    }

    private fun extractProviderUrls(
        text: String
    ): Set<String> {

        val result = LinkedHashSet<String>()

        val patterns = listOf(

            /*
             * Rumble
             */
            Regex(
                """https?://(?:www\.)?rumble\.com/(?:embed/[^"'\\\s<>]+|v?[a-zA-Z0-9_-]+\.html)""",
                RegexOption.IGNORE_CASE
            ),

            /*
             * YouTube
             */
            Regex(
                """https?://(?:www\.)?(?:youtube\.com/(?:embed/|watch\?v=)|youtu\.be/)[^"'\\\s<>]+""",
                RegexOption.IGNORE_CASE
            ),

            /*
             * Dailymotion
             */
            Regex(
                """https?://(?:www\.)?dailymotion\.com/video/[^"'\\\s<>]+""",
                RegexOption.IGNORE_CASE
            ),

            /*
             * Vimeo
             */
            Regex(
                """https?://(?:www\.)?vimeo\.com/[^"'\\\s<>]+""",
                RegexOption.IGNORE_CASE
            )
        )

        patterns.forEach { regex ->

            regex.findAll(
                text
            ).forEach {

                result.add(
                    it.value
                        .replace("\\/", "/")
                        .replace("\\u0026", "&")
                        .replace("&amp;", "&")
                        .trim('"', '\'')
                )
            }
        }

        return result
    }

    private fun extractDirectMediaUrls(
        text: String
    ): Set<String> {

        val result = LinkedHashSet<String>()

        val regex = Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        regex.findAll(
            text
        ).forEach {

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
