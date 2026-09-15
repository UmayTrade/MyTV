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

    override val supportedTypes = setOf(
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/film-arsivi/" to "Film Arşivi",
        "$mainUrl/category/komedi/" to "Komedi",
        "$mainUrl/category/dram/" to "Dram",
        "$mainUrl/category/aksiyon/" to "Aksiyon",
        "$mainUrl/category/macera/" to "Macera",
        "$mainUrl/category/romantik/" to "Romantik"
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

        println(
            "DEBUG YesilCamTv: getMainPage -> $targetUrl"
        )

        val doc = try {
            app.get(
                targetUrl,
                referer = mainUrl
            ).document
        } catch (e: Exception) {
            println(
                "DEBUG YesilCamTv: main page error -> ${e.message}"
            )
            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        val items = doc.select(
            ".listmovie, article, div.item, div.post, div.video-item"
        )
            .mapNotNull { element ->
                parseSearchItem(element)
            }
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

        val encodedQuery = URLEncoder
            .encode(query, "UTF-8")
            .replace("+", "%20")

        val targetUrl = if (page <= 1) {
            "$mainUrl/?s=$encodedQuery"
        } else {
            "$mainUrl/page/$page/?s=$encodedQuery"
        }

        println(
            "DEBUG YesilCamTv: search -> $targetUrl"
        )

        val doc = try {
            app.get(
                targetUrl,
                referer = mainUrl
            ).document
        } catch (e: Exception) {
            println(
                "DEBUG YesilCamTv: search error -> ${e.message}"
            )

            return newSearchResponseList(
                emptyList(),
                hasNext = false
            )
        }

        val items = doc.select(
            ".listmovie, article, div.item, div.post, div.search-result"
        )
            .mapNotNull { element ->
                parseSearchItem(element)
            }
            .distinctBy { it.url }

        return newSearchResponseList(
            items,
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse>? {
        return search(query, 1).items
    }

    private fun parseSearchItem(
        element: Element
    ): SearchResponse? {

        val linkEl = element.selectFirst(
            ".poster a[href], a[href]"
        ) ?: return null

        val href = fixUrlNull(
            linkEl.attr("href")
        ) ?: return null

        val imgEl = element.selectFirst("img")

        val title =
            element.selectFirst(
                "h2, h3, .title, .entry-title a, .film-ismi a, a[title]"
            )?.text()?.trim()
                ?: imgEl?.attr("alt")?.trim()
                ?: linkEl.attr("title").trim()

        if (title.isBlank()) {
            return null
        }

        val poster = fixUrlNull(
            imgEl?.attr("data-src")?.ifBlank { null }
                ?: imgEl?.attr("data-lazy-src")?.ifBlank { null }
                ?: imgEl?.attr("src")?.ifBlank { null }
        )

        val year = element.selectFirst(
            ".film-yil, .year, .date, .release-year"
        )
            ?.text()
            ?.filter { it.isDigit() }
            ?.take(4)
            ?.toIntOrNull()

        val scoreText = element.selectFirst(
            ".bolum-ust, .imdb-score, .score"
        )?.text()?.trim()

        val numericScore = parseScore(scoreText)

        return newMovieSearchResponse(
            title,
            href,
            TvType.Movie
        ) {
            this.posterUrl = poster
            this.year = year

            if (numericScore != null) {
                this.score = Score.from10(numericScore)
            }
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        println(
            "DEBUG YesilCamTv: load -> $url"
        )

        val doc = try {
            app.get(
                url,
                referer = mainUrl
            ).document
        } catch (e: Exception) {
            println(
                "DEBUG YesilCamTv: load error -> ${e.message}"
            )
            return null
        }

        return parseLoadMetadata(
            doc = doc,
            url = url
        )
    }

    private suspend fun parseLoadMetadata(
        doc: Document,
        url: String
    ): LoadResponse? {

        val titleElement = doc.selectFirst(
            "h1.title-border, " +
                    "h1.entry-title, " +
                    "h1, " +
                    ".video-title, " +
                    "meta[property='og:title']"
        )

        val title = titleElement?.let {
            if (it.tagName() == "meta") {
                it.attr("content")
            } else {
                it.text().trim()
            }
        }
            ?.replace(" | YESILCAM TV", "")
            ?.replace(" | YEŞİLÇAM TV", "")
            ?.replace(" - Yeşilçam TV", "")
            ?.replace(" - YesilCamTv", "")
            ?.replace(" İzle", "")
            ?.trim()
            ?: return null

        val poster = fixUrlNull(
            doc.selectFirst(
                "meta[property='og:image']"
            )?.attr("content")
                ?: doc.selectFirst(
                    "div.film-afis img, " +
                            "div.poster img, " +
                            ".entry-content img, " +
                            ".film-bilgi img"
                )?.let {
                    it.attr("data-src").ifBlank {
                        null
                    } ?: it.attr("data-lazy-src").ifBlank {
                        null
                    } ?: it.attr("src").ifBlank {
                        null
                    }
                }
        )

        val description = doc.selectFirst(
            "#film-aciklama, " +
                    "div.singlecontent p, " +
                    "div.entry-content p, " +
                    "meta[property='og:description'], " +
                    ".video-desc"
        )?.let {
            if (it.tagName() == "meta") {
                it.attr("content")
            } else {
                it.text()
            }
        }?.trim()

        val year = doc.selectFirst(
            "a[href*='/yil/'], " +
                    ".film-yil, " +
                    ".year, " +
                    ".entry-date, " +
                    "span.date"
        )
            ?.text()
            ?.filter { it.isDigit() }
            ?.take(4)
            ?.toIntOrNull()

        val tags = doc.select(
            "div#listelements a[href*='/category/'], " +
                    "a[href*='/category/'], " +
                    ".tags a, " +
                    ".categories a"
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
                    ".cast a"
        )
            .mapNotNull {
                val actorName = it.text().trim()

                if (actorName.isBlank()) {
                    null
                } else {
                    Actor(actorName)
                }
            }
            .distinctBy {
                it.name
            }

        val scoreText = doc.selectFirst(
            ".bolum-ust, " +
                    ".imdb-score, " +
                    ".score, " +
                    "#listelements .elements"
        )?.text()?.trim()

        val score = parseScore(scoreText)

        val response = newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {

            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags

            if (score != null) {
                this.score = Score.from10(score)
            }
        }

        if (actors.isNotEmpty()) {
            response.addActors(actors)
        }

        return response
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        println(
            "DEBUG YesilCamTv: loadLinks -> $data"
        )

        val doc = try {
            app.get(
                data,
                referer = mainUrl
            ).document
        } catch (e: Exception) {
            println(
                "DEBUG YesilCamTv: loadLinks document error -> ${e.message}"
            )
            return false
        }

        var linksFound = false

        println(
            "DEBUG YesilCamTv: iframe sayısı = " +
                    doc.select("iframe").size
        )

        println(
            "DEBUG YesilCamTv: video sayısı = " +
                    doc.select("video").size
        )

        /*
         * =========================================================
         * 1. IFRAME
         * =========================================================
         */

        val iframeElements = doc.select(
            "iframe[src], iframe[data-src]"
        )

        for (iframe in iframeElements) {

            val src = iframe.attr("data-src")
                .ifBlank {
                    iframe.attr("src")
                }
                .trim()

            if (src.isBlank()) {
                continue
            }

            if (
                src.startsWith("about:", ignoreCase = true) ||
                src.startsWith("javascript:", ignoreCase = true)
            ) {
                continue
            }

            val fixed = try {
                fixUrl(src)
            } catch (_: Exception) {
                src
            }

            println(
                "DEBUG YesilCamTv: iframe -> $fixed"
            )

            try {

                val success = loadExtractor(
                    fixed,
                    referer = data,
                    subtitleCallback = subtitleCallback
                ) { link ->

                    println(
                        "DEBUG YesilCamTv: extractor link -> ${link.url}"
                    )

                    callback(link)
                    linksFound = true
                }

                if (success) {
                    linksFound = true
                }

            } catch (e: Exception) {

                println(
                    "DEBUG YesilCamTv: iframe extractor error -> " +
                            e.message
                )
            }
        }

        /*
         * =========================================================
         * 2. VIDEO SOURCE
         * =========================================================
         */

        val videoElements = doc.select(
            "video source[src], video[src]"
        )

        for (video in videoElements) {

            val src = fixUrlNull(
                video.attr("src")
            ) ?: continue

            if (src.isBlank()) {
                continue
            }

            println(
                "DEBUG YesilCamTv: direct video -> $src"
            )

            val isM3u8 = src.contains(
                ".m3u8",
                ignoreCase = true
            )

            val isMp4 = src.contains(
                ".mp4",
                ignoreCase = true
            )

            if (!isM3u8 && !isMp4) {
                continue
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name HD",
                    url = src,
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
         * =========================================================
         * 3. HTML İÇERİSİNDEKİ MP4 / M3U8
         * =========================================================
         */

        val html = try {
            app.get(
                data,
                referer = mainUrl
            ).text
        } catch (_: Exception) {
            ""
        }

        if (html.isNotBlank()) {

            val mediaRegex = Regex(
                """https?://[^"'\\\s<>]+(?:\.m3u8|\.mp4)(?:\?[^"'\\\s<>]*)?""",
                RegexOption.IGNORE_CASE
            )

            for (match in mediaRegex.findAll(html)) {

                val mediaUrl = match.value
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")

                val isM3u8 = mediaUrl.contains(
                    ".m3u8",
                    ignoreCase = true
                )

                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Direct",
                        url = mediaUrl,
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
             * HTML içindeki altyazılar
             */
            val subtitleRegex = Regex(
                """https?://[^"'\\\s<>]+\.(?:vtt|srt)(?:\?[^"'\\\s<>]*)?""",
                RegexOption.IGNORE_CASE
            )

            for (match in subtitleRegex.findAll(html)) {

                val subtitleUrl = match.value
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")

                subtitleCallback(
                    newSubtitleFile(
                        lang = "tr",
                        url = subtitleUrl
                    )
                )
            }
        }

        println(
            "DEBUG YesilCamTv: linksFound = $linksFound"
        )

        return linksFound
    }

    private fun parseScore(
        value: String?
    ): Double? {

        if (value.isNullOrBlank()) {
            return null
        }

        val imdbMatch = Regex(
            """(?:IMDb|IMDB)?\s*:?\s*([0-9]+(?:[.,][0-9]+)?)"""
        ).find(value)

        val number = imdbMatch
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex(
                """([0-9]+(?:[.,][0-9]+)?)"""
            )
                .find(value)
                ?.groupValues
                ?.getOrNull(1)

        return number
            ?.replace(",", ".")
            ?.toDoubleOrNull()
            ?.takeIf {
                it in 0.0..10.0
            }
    }
}
