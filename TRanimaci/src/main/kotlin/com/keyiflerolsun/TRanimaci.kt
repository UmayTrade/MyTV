package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class TRanimaci : MainAPI() {

    companion object {
        private const val TAG = "TRanimaci"

        private const val SITE_REFERER =
            "https://tranimaci.com/"

        private const val VIDEO_API =
            "https://api.animeuzayi.com"

        private val HEADERS = mapOf(
            "User-Agent" to
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/139.0.0.0 Safari/537.36",

            "Accept" to
                    "text/html,application/xhtml+xml,application/xml;q=0.9," +
                    "image/avif,image/webp,*/*;q=0.8",

            "Accept-Language" to
                    "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }

    override var mainUrl = "https://tranimaci.com"

    override var name = "TrAnimeci"

    override val hasMainPage = true

    override var lang = "tr"

    override val hasQuickSearch = false

    override val supportedTypes = setOf(TvType.Anime)

    override var sequentialMainPage = true

    override var sequentialMainPageDelay = 500L

    override var sequentialMainPageScrollDelay = 500L


    // ============================================================
    // MAIN PAGE
    // ============================================================

    override val mainPage = mainPageOf(
        "$mainUrl/category/action" to "Aksiyon",
        "$mainUrl/category/cars" to "Arabalar",
        "$mainUrl/category/supernatural" to "Doğaüstü",
        "$mainUrl/category/drama" to "Dram",
        "$mainUrl/category/ecchi" to "Ecchi",
        "$mainUrl/category/fantasy" to "Fantastik",
        "$mainUrl/category/mystery" to "Gizem",
        "$mainUrl/category/comedy" to "Komedi",
        "$mainUrl/category/horror" to "Korku",
        "$mainUrl/category/adventure" to "Macera",
        "$mainUrl/category/mecha" to "Mecha",
        "$mainUrl/category/music" to "Müzik",
        "$mainUrl/category/romance" to "Romantik",
        "$mainUrl/category/sports" to "Spor"
    )


    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page <= 1) {
            request.data
        } else {
            if (request.data.endsWith("/")) {
                "${request.data}page/$page/"
            } else {
                "${request.data}/page/$page/"
            }
        }

        Log.d(TAG, "MAIN PAGE: $url")

        return try {

            val document = app.get(
                url,
                headers = HEADERS
            ).document

            val results =
                extractAnimeCards(document)

            Log.d(
                TAG,
                "MAIN PAGE RESULT: ${results.size}"
            )

            newHomePageResponse(
                request.name,
                results
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "MAIN PAGE ERROR: ${e.message}"
            )

            newHomePageResponse(
                request.name,
                emptyList()
            )
        }
    }


    // ============================================================
    // SEARCH
    // ============================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery =
            query.trim().replace(" ", "+")

        val searchUrls = listOf(
            "$mainUrl/search?name=$encodedQuery",
            "$mainUrl/?s=$encodedQuery"
        )

        for (searchUrl in searchUrls) {

            try {

                Log.d(
                    TAG,
                    "SEARCH: $searchUrl"
                )

                val document = app.get(
                    searchUrl,
                    headers = HEADERS
                ).document

                val results =
                    extractAnimeCards(document)

                if (results.isNotEmpty()) {

                    Log.d(
                        TAG,
                        "SEARCH RESULT: ${results.size}"
                    )

                    return results
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "SEARCH ERROR: ${e.message}"
                )
            }
        }

        return emptyList()
    }


    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> =
        search(query)


    // ============================================================
    // LOAD
    // ============================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        Log.d(
            TAG,
            "LOAD: $url"
        )

        return try {

            val document = app.get(
                url,
                headers = HEADERS
            ).document

            // ----------------------------------------------------
            // TITLE
            // ----------------------------------------------------

            val title =
                firstText(
                    document,
                    "h1",
                    "h1.entry-title",
                    ".anime-title",
                    ".entry-title",
                    ".post-title",
                    ".title"
                ) ?: return null


            // ----------------------------------------------------
            // POSTER
            // ----------------------------------------------------

            val poster =
                extractPoster(document)


            // ----------------------------------------------------
            // DESCRIPTION
            // ----------------------------------------------------

            val description =
                firstText(
                    document,
                    "div.anime-description",
                    ".anime-description",
                    ".description",
                    ".summary",
                    ".synopsis",
                    ".desc",
                    ".entry-content"
                )


            // ----------------------------------------------------
            // TAGS
            // ----------------------------------------------------

            val tags =
                extractTags(document)


            // ----------------------------------------------------
            // EPISODES
            // ----------------------------------------------------

            val episodes =
                extractEpisodes(document)


            Log.d(
                TAG,
                "TITLE: $title"
            )

            Log.d(
                TAG,
                "POSTER: $poster"
            )

            Log.d(
                TAG,
                "EPISODES: ${episodes.size}"
            )


            // IMPORTANT:
            // Senin orijinal çalışan dosyandaki API
            // kullanım şekli korunmuştur.

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {

                this.posterUrl = poster

                this.plot = description

                this.tags = tags
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "LOAD ERROR: ${e.message}"
            )

            null
        }
    }


    // ============================================================
    // ANIME CARD
    // ============================================================

    private fun extractAnimeCards(
        document: org.jsoup.nodes.Document
    ): List<SearchResponse> {

        val results =
            mutableListOf<SearchResponse>()

        val usedUrls =
            mutableSetOf<String>()

        val selectors = listOf(
            "article.bs div.bsx",
            "article.bs",
            "div.bsx",
            ".bsx",
            ".bs",
            ".anime-card",
            ".anime-item"
        )

        var cards =
            emptyList<Element>()

        for (selector in selectors) {

            val found =
                document.select(selector)

            if (found.isNotEmpty()) {

                cards = found

                Log.d(
                    TAG,
                    "CARD SELECTOR: $selector -> ${found.size}"
                )

                break
            }
        }


        for (card in cards) {

            val link =
                card.selectFirst("a[href]")
                    ?: continue


            val href =
                link.absUrl("href").ifBlank {

                    fixUrlNull(
                        link.attr("href")
                    ) ?: ""
                }


            if (href.isBlank())
                continue


            if (!usedUrls.add(href))
                continue


            val title =
                firstText(
                    card,
                    ".tt",
                    ".title",
                    ".anime-title",
                    ".entry-title",
                    ".name",
                    "h2",
                    "h3",
                    "h4"
                )
                    ?: link.attr("title")
                        .trim()
                        .ifBlank {
                            link.text().trim()
                        }


            if (title.isBlank())
                continue


            val poster =
                extractPoster(card)


            results.add(
                newAnimeSearchResponse(
                    title,
                    href,
                    TvType.Anime
                ) {

                    this.posterUrl =
                        poster
                }
            )
        }


        return results
    }


    // ============================================================
    // POSTER
    // ============================================================

    private fun extractPoster(
        element: Element
    ): String? {

        val selectors = listOf(
            "div.limit img",
            ".limit img",
            ".poster img",
            "div.thumb img",
            ".thumb img",
            ".anime-poster img",
            "img"
        )


        for (selector in selectors) {

            val image =
                element.selectFirst(selector)
                    ?: continue


            val sources = listOf(
                image.attr("src"),
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("data-original"),
                image.attr("data-image"),
                image.attr("data-lazy")
            )


            for (source in sources) {

                if (source.isBlank())
                    continue


                val url =
                    fixUrlNull(source)


                if (!url.isNullOrBlank()) {

                    return url
                }
            }


            // srcset

            val srcset =
                image.attr("srcset")


            if (srcset.isNotBlank()) {

                val first =
                    srcset
                        .split(",")
                        .firstOrNull()
                        ?.trim()
                        ?.split(" ")
                        ?.firstOrNull()


                val url =
                    fixUrlNull(first)


                if (!url.isNullOrBlank()) {

                    return url
                }
            }
        }


        return null
    }


    // ============================================================
    // EPISODES
    // ============================================================

    private fun extractEpisodes(
        document: org.jsoup.nodes.Document
    ): MutableList<Episode> {

        val episodes =
            mutableListOf<Episode>()

        val usedUrls =
            mutableSetOf<String>()

        val selectors = listOf(
            "div.eplister ul li a",
            ".eplister a",
            ".episode-list a",
            ".episodes a",
            ".episodelist a",
            "ul.episodes li a",
            "ul.episode-list li a",
            ".ep-item a"
        )


        val elements =
            mutableListOf<Element>()


        for (selector in selectors) {

            document.select(selector).forEach {

                if (!elements.contains(it)) {
                    elements.add(it)
                }
            }
        }


        for (element in elements) {

            val href =
                element.absUrl("href").ifBlank {

                    fixUrlNull(
                        element.attr("href")
                    ) ?: ""
                }


            if (href.isBlank())
                continue


            if (!usedUrls.add(href))
                continue


            val episodeTitle =
                firstText(
                    element,
                    ".epl-title",
                    ".episode-title",
                    ".ep-title",
                    ".title",
                    "span"
                )
                    ?: element.text().trim()


            if (episodeTitle.isBlank())
                continue


            val episodeNumber =
                extractEpisodeNumber(
                    episodeTitle,
                    href
                )


            val newEp =
                newEpisode(href) {

                    this.name =
                        cleanEpisodeTitle(
                            episodeTitle
                        )

                    if (episodeNumber != null) {

                        this.episode =
                            episodeNumber
                    }
                }


            episodes.add(newEp)
        }


        // --------------------------------------------------------
        // FALLBACK
        // --------------------------------------------------------

        if (episodes.isEmpty()) {

            Log.d(
                TAG,
                "NORMAL EPISODE SELECTOR EMPTY - FALLBACK"
            )


            document.select("a[href]")
                .forEach { element ->

                    val href =
                        element.absUrl("href")


                    if (href.isBlank())
                        return@forEach


                    val text =
                        element.text().trim()


                    if (
                        text.matches(
                            Regex(
                                """.*(bölüm|bolum|episode|ep\.?)\s*[-:]?\s*\d+.*""",
                                RegexOption.IGNORE_CASE
                            )
                        )
                    ) {

                        if (
                            !usedUrls.add(href)
                        ) {
                            return@forEach
                        }


                        val number =
                            extractEpisodeNumber(
                                text,
                                href
                            )


                        val newEp =
                            newEpisode(href) {

                                this.name =
                                    cleanEpisodeTitle(
                                        text
                                    )

                                if (number != null) {

                                    this.episode =
                                        number
                                }
                            }


                        episodes.add(newEp)
                    }
                }
        }


        episodes.sortBy {
            it.episode ?: Int.MAX_VALUE
        }


        return episodes
    }


    // ============================================================
    // LOAD LINKS
    // ============================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d(
            TAG,
            "LOAD LINKS: $data"
        )


        val document =
            try {

                app.get(
                    data,
                    headers = HEADERS + mapOf(
                        "Referer" to SITE_REFERER
                    )
                ).document

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "EPISODE PAGE ERROR: ${e.message}"
                )

                return false
            }


        var found =
            false


        // ========================================================
        // VIDEO_SOURCE
        // ========================================================

        for (script in document.select("script")) {

            val html =
                script.html()


            if (
                !html.contains(
                    "video_source"
                )
            ) {
                continue
            }


            try {

                val match =
                    Regex(
                        """video_source\s*=\s*[`'"](\[[\s\S]*?])[`'"]"""
                    ).find(html)


                val raw =
                    match
                        ?.groups
                        ?.get(1)
                        ?.value
                        ?: continue


                val array =
                    JSONArray(raw)


                for (i in 0 until array.length()) {

                    val source =
                        array.optJSONObject(i)
                            ?: continue


                    val apiUrl =
                        source.optString("url")


                    if (apiUrl.isBlank())
                        continue


                    Log.d(
                        TAG,
                        "VIDEO API: $apiUrl"
                    )


                    if (
                        extractAnimeUzayi(
                            apiUrl,
                            callback
                        )
                    ) {

                        found = true
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "VIDEO_SOURCE ERROR: ${e.message}"
                )
            }
        }


        // ========================================================
        // DIRECT VIDEO
        // ========================================================

        document
            .select("video source, video[src]")
            .forEach { element ->

                val src =
                    if (
                        element.hasAttr("src")
                    ) {
                        element.attr("src")
                    } else {
                        element.absUrl("src")
                    }


                val videoUrl =
                    fixUrlNull(src)


                if (
                    videoUrl.isNullOrBlank()
                ) {
                    return@forEach
                }


                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} Direct",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {

                        this.referer = data

                        this.quality =
                            Qualities.Unknown.value
                    }
                )


                found = true
            }


        // ========================================================
        // IFRAME
        // ========================================================

        document
            .select("iframe[src]")
            .forEach { iframe ->

                val iframeUrl =
                    iframe.absUrl("src")


                if (
                    iframeUrl.isBlank()
                ) {
                    return@forEach
                }


                Log.d(
                    TAG,
                    "IFRAME: $iframeUrl"
                )


                if (
                    iframeUrl.contains(
                        "animeuzayi",
                        ignoreCase = true
                    )
                ) {

                    if (
                        extractAnimeUzayi(
                            iframeUrl,
                            callback
                        )
                    ) {

                        found = true
                    }
                }
            }


        // ========================================================
        // SCRIPT ICINDE MP4
        // ========================================================

        for (script in document.select("script")) {

            val html =
                script.html()


            val matches =
                Regex(
                    """https?://[^\s"'`\\]+?\.(?:mp4|m3u8)(?:\?[^\s"'`\\]*)?""",
                    RegexOption.IGNORE_CASE
                ).findAll(html)


            for (match in matches) {

                val videoUrl =
                    match.value
                        .replace(
                            "\\/",
                            "/"
                        )


                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} Direct",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {

                        this.referer = data

                        this.quality =
                            Qualities.Unknown.value
                    }
                )


                found = true
            }
        }


        Log.d(
            TAG,
            "LOAD LINKS RESULT: $found"
        )


        return found
    }


    // ============================================================
    // ANIMEUZAYI VIDEO PARSER
    // ============================================================

    private suspend fun extractAnimeUzayi(
        apiUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val response =
                app.get(
                    apiUrl,
                    headers = HEADERS + mapOf(
                        "Referer" to SITE_REFERER
                    )
                )


            val html =
                response.text


            val document =
                Jsoup.parse(html)


            var found =
                false


            // ----------------------------------------------------
            // const sources = [...]
            // ----------------------------------------------------

            for (script in document.select("script")) {

                val scriptHtml =
                    script.html()


                if (
                    !scriptHtml.contains(
                        "sources"
                    )
                ) {
                    continue
                }


                val match =
                    Regex(
                        """(?:const|let|var)\s+sources\s*=\s*(\[[\s\S]*?])\s*;?"""
                    ).find(scriptHtml)


                val raw =
                    match
                        ?.groups
                        ?.get(1)
                        ?.value
                        ?: continue


                try {

                    val sources =
                        JSONArray(raw)


                    for (i in 0 until sources.length()) {

                        val source =
                            sources.optJSONObject(i)
                                ?: continue


                        val src =
                            source.optString("src")


                        if (src.isBlank())
                            continue


                        val videoUrl =
                            when {

                                src.startsWith(
                                    "http://"
                                ) ||
                                src.startsWith(
                                    "https://"
                                ) -> src

                                src.startsWith("/") ->
                                    VIDEO_API + src

                                else ->
                                    "$VIDEO_API/$src"
                            }


                        val quality =
                            source.optInt(
                                "size",
                                Qualities.Unknown.value
                            )


                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} - ${quality}p",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {

                                this.referer =
                                    "$VIDEO_API/"

                                this.quality =
                                    quality
                            }
                        )


                        found = true
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "SOURCES JSON ERROR: ${e.message}"
                    )
                }
            }


            // ----------------------------------------------------
            // FALLBACK MP4 / M3U8
            // ----------------------------------------------------

            val directUrls =
                Regex(
                    """(?:https?:)?//[^\s"'`\\]+?\.(?:mp4|m3u8)(?:\?[^\s"'`\\]*)?""",
                    RegexOption.IGNORE_CASE
                ).findAll(html)


            for (match in directUrls) {

                var videoUrl =
                    match.value
                        .replace(
                            "\\/",
                            "/"
                        )


                if (
                    videoUrl.startsWith("//")
                ) {

                    videoUrl =
                        "https:$videoUrl"
                }


                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} Direct",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {

                        this.referer =
                            "$VIDEO_API/"

                        this.quality =
                            Qualities.Unknown.value
                    }
                )


                found = true
            }


            found

        } catch (e: Exception) {

            Log.e(
                TAG,
                "ANIMEUZAYI ERROR: ${e.message}"
            )

            false
        }
    }


    // ============================================================
    // TAGS
    // ============================================================

    private fun extractTags(
        document: org.jsoup.nodes.Document
    ): List<String> {

        val tags =
            mutableListOf<String>()


        val selectors = listOf(
            "div#genxed a[href*='/category']",
            ".genres a",
            ".genre a",
            ".tags a",
            ".anime-genres a"
        )


        for (selector in selectors) {

            document
                .select(selector)
                .forEach {

                    val text =
                        it.text().trim()


                    if (
                        text.isNotBlank() &&
                        !tags.contains(text)
                    ) {

                        tags.add(text)
                    }
                }


            if (tags.isNotEmpty())
                break
        }


        return tags
    }


    // ============================================================
    // TEXT HELPER
    // ============================================================

    private fun firstText(
        element: Element,
        vararg selectors: String
    ): String? {

        for (selector in selectors) {

            val text =
                element
                    .selectFirst(selector)
                    ?.text()
                    ?.trim()


            if (!text.isNullOrBlank()) {

                return text
            }
        }


        return null
    }


    // ============================================================
    // EPISODE NUMBER
    // ============================================================

    private fun extractEpisodeNumber(
        title: String,
        href: String
    ): Int? {

        val patterns =
            listOf(

                Regex(
                    """(?:bölüm|bolum|episode|ep\.?)\s*[-:]?\s*(\d+)""",
                    RegexOption.IGNORE_CASE
                ),

                Regex(
                    """episode[-_/]?(\d+)""",
                    RegexOption.IGNORE_CASE
                ),

                Regex(
                    """/(\d+)(?:/)?$"""
                ),

                Regex(
                    """[-_\s](\d+)(?:/)?$"""
                )
            )


        for (pattern in patterns) {

            val match =
                pattern.find(title)
                    ?: pattern.find(href)
                    ?: continue


            return match
                .groupValues
                .getOrNull(1)
                ?.toIntOrNull()
        }


        return null
    }


    // ============================================================
    // CLEAN EPISODE TITLE
    // ============================================================

    private fun cleanEpisodeTitle(
        title: String
    ): String {

        return title
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()
    }
}
