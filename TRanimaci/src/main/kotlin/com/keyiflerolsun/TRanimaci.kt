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

        private const val SITE_REFERER = "https://tranimaci.com/"
        private const val VIDEO_API = "https://api.animeuzayi.com"

        private val DEFAULT_HEADERS = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/139.0.0.0 Safari/537.36",
            "Accept" to
                "text/html,application/xhtml+xml,application/xml;q=0.9," +
                "image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }

    override var mainUrl = "https://tranimaci.com"

    override var name = "TrAnimeci"

    override val hasMainPage = true

    override var lang = "tr"

    override val hasQuickSearch = true

    override val supportedTypes = setOf(TvType.Anime)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 500L
    override var sequentialMainPageScrollDelay = 500L

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

    // ------------------------------------------------------------
    // MAIN PAGE
    // ------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page <= 1) {
            request.data
        } else {
            buildPageUrl(request.data, page)
        }

        Log.d(TAG, "MainPage URL: $url")

        val document = try {
            app.get(
                url,
                headers = DEFAULT_HEADERS
            ).document
        } catch (e: Exception) {
            Log.e(TAG, "MainPage error: ${e.message}")
            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        val results = extractAnimeCards(document)

        Log.d(TAG, "MainPage results: ${results.size}")

        return newHomePageResponse(
            request.name,
            results,
            hasNext = hasNextPage(document, page)
        )
    }

    // ------------------------------------------------------------
    // SEARCH
    // ------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {

        val encodedQuery = query
            .trim()
            .replace(" ", "+")

        val urls = listOf(
            "$mainUrl/search?name=$encodedQuery",
            "$mainUrl/?s=$encodedQuery",
            "$mainUrl/search/$encodedQuery"
        )

        for (url in urls) {

            try {

                Log.d(TAG, "Search URL: $url")

                val document = app.get(
                    url,
                    headers = DEFAULT_HEADERS
                ).document

                val results = extractAnimeCards(document)

                if (results.isNotEmpty()) {
                    Log.d(TAG, "Search found ${results.size} results")
                    return results
                }

            } catch (e: Exception) {
                Log.e(TAG, "Search failed: ${e.message}")
            }
        }

        return emptyList()
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> = search(query)

    // ------------------------------------------------------------
    // LOAD ANIME
    // ------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {

        Log.d(TAG, "Load URL: $url")

        val document = try {

            app.get(
                url,
                headers = DEFAULT_HEADERS
            ).document

        } catch (e: Exception) {

            Log.e(TAG, "Load error: ${e.message}")
            return null
        }

        val title = firstText(
            document,
            "h1",
            "h1.entry-title",
            "h1.title",
            ".anime-title",
            ".entry-title",
            ".post-title"
        ) ?: return null

        val poster = extractPoster(document)

        val description = firstText(
            document,
            "div.anime-description",
            ".anime-description",
            ".description",
            ".entry-content p",
            ".summary",
            ".synopsis",
            ".desc"
        )

        val tags = extractTags(document)

        val episodes = extractEpisodes(document)

        Log.d(TAG, "Title: $title")
        Log.d(TAG, "Poster: $poster")
        Log.d(TAG, "Episodes: ${episodes.size}")

        return newTvSeriesLoadResponse(
            title = title,
            url = url,
            type = TvType.TvSeries,
            episodes = episodes
        ) {

            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    // ------------------------------------------------------------
    // EPISODES
    // ------------------------------------------------------------

    private fun extractEpisodes(
        document: org.jsoup.nodes.Document
    ): MutableList<Episode> {

        val episodes = mutableListOf<Episode>()
        val seenUrls = mutableSetOf<String>()

        val selectors = listOf(
            "div.eplister ul li a",
            ".eplister a",
            ".episode-list a",
            ".episodes a",
            ".episodelist a",
            "ul.episodes li a",
            "ul.episode-list li a",
            ".ep-item a",
            ".episode a"
        )

        val elements = mutableListOf<Element>()

        for (selector in selectors) {
            document.select(selector).forEach {
                if (!elements.contains(it)) {
                    elements.add(it)
                }
            }
        }

        for (element in elements) {

            val href = element.absUrl("href").ifBlank {
                fixUrlNull(element.attr("href")) ?: ""
            }

            if (href.isBlank()) continue

            if (!seenUrls.add(href)) continue

            val episodeTitle =
                firstElementText(
                    element,
                    ".epl-title",
                    ".episode-title",
                    ".ep-title",
                    ".title",
                    "span"
                )
                    ?: element.text().trim()

            if (episodeTitle.isBlank()) continue

            val episodeNumber = extractEpisodeNumber(
                episodeTitle,
                href
            )

            val episode = newEpisode(href) {

                name = cleanEpisodeTitle(episodeTitle)

                if (episodeNumber != null) {
                    episode = episodeNumber
                }
            }

            episodes.add(episode)
        }

        // Eğer yukarıdaki selector'lar hiçbir şey bulamazsa
        // sayfadaki bölüm linklerini URL üzerinden yakalamayı deniyoruz.
        if (episodes.isEmpty()) {

            document.select("a[href]").forEach { element ->

                val href = element.absUrl("href")

                if (href.isBlank()) return@forEach

                val text = element.text().trim()

                if (
                    text.matches(
                        Regex(
                            ".*(bölüm|bolum|episode|ep\\.?)[\\s-]*\\d+.*",
                            RegexOption.IGNORE_CASE
                        )
                    )
                ) {

                    if (!seenUrls.add(href)) return@forEach

                    val number = extractEpisodeNumber(
                        text,
                        href
                    )

                    episodes.add(
                        newEpisode(href) {
                            name = cleanEpisodeTitle(text)

                            if (number != null) {
                                episode = number
                            }
                        }
                    )
                }
            }
        }

        return episodes
            .sortedBy { it.episode ?: Int.MAX_VALUE }
            .toMutableList()
    }

    // ------------------------------------------------------------
    // LINKS / VIDEO
    // ------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d(TAG, "loadLinks data: $data")

        val document = try {

            app.get(
                data,
                headers = DEFAULT_HEADERS + mapOf(
                    "Referer" to SITE_REFERER
                )
            ).document

        } catch (e: Exception) {

            Log.e(TAG, "Episode page error: ${e.message}")
            return false
        }

        var found = false

        // --------------------------------------------------------
        // 1. video_source
        // --------------------------------------------------------

        val videoSourceScripts = document.select("script")

        for (script in videoSourceScripts) {

            val html = script.html()

            if (!html.contains("video_source")) continue

            try {

                val match = Regex(
                    """video_source\s*=\s*[`'"](\[[\s\S]*?])[`'"]"""
                ).find(html)

                val raw = match?.groups?.get(1)?.value
                    ?: continue

                val array = JSONArray(raw)

                for (i in 0 until array.length()) {

                    val obj = array.optJSONObject(i)
                        ?: continue

                    val apiUrl = obj.optString("url")

                    if (apiUrl.isBlank()) continue

                    Log.d(TAG, "video_source API: $apiUrl")

                    val success = extractFromAnimeUzayi(
                        apiUrl,
                        callback
                    )

                    if (success) {
                        found = true
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "video_source parse error: ${e.message}"
                )
            }
        }

        // --------------------------------------------------------
        // 2. Doğrudan iframe / video kaynakları
        // --------------------------------------------------------

        document.select("video source, video[src]").forEach { element ->

            val src = when {
                element.hasAttr("src") ->
                    element.attr("src")

                else ->
                    element.absUrl("src")
            }

            val videoUrl = fixUrlNull(src)

            if (videoUrl.isNullOrBlank()) return@forEach

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name Direct",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    referer = data
                    quality = Qualities.Unknown.value
                }
            )

            found = true
        }

        // --------------------------------------------------------
        // 3. iframe
        // --------------------------------------------------------

        document.select("iframe").forEach { iframe ->

            val iframeUrl = iframe.absUrl("src")

            if (iframeUrl.isBlank()) return@forEach

            Log.d(TAG, "Iframe: $iframeUrl")

            // AnimeUzayi ise doğrudan parse etmeyi dene.
            if (
                iframeUrl.contains(
                    "animeuzayi",
                    ignoreCase = true
                )
            ) {

                if (
                    extractFromAnimeUzayi(
                        iframeUrl,
                        callback
                    )
                ) {
                    found = true
                }
            }
        }

        // --------------------------------------------------------
        // 4. Sayfadaki olası MP4/M3U8 URL'leri
        // --------------------------------------------------------

        document.select("script").forEach { script ->

            val html = script.html()

            val urls = Regex(
                """https?://[^\s"'`\\]+?\.(?:mp4|m3u8)(?:\?[^\s"'`\\]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(html)

            for (match in urls) {

                val videoUrl = match.value
                    .replace("\\/", "/")

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Direct",
                        url = videoUrl,
                        type = if (
                            videoUrl.contains(
                                ".m3u8",
                                ignoreCase = true
                            )
                        ) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                    ) {
                        referer = data
                        quality = Qualities.Unknown.value
                    }
                )

                found = true
            }
        }

        Log.d(TAG, "loadLinks result: $found")

        return found
    }

    // ------------------------------------------------------------
    // ANIMEUZAYI
    // ------------------------------------------------------------

    private suspend fun extractFromAnimeUzayi(
        apiUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val response = app.get(
                apiUrl,
                headers = DEFAULT_HEADERS + mapOf(
                    "Referer" to SITE_REFERER
                )
            )

            val html = response.text

            val document = Jsoup.parse(html)

            var found = false

            val scripts = document.select("script")

            for (script in scripts) {

                val scriptHtml = script.html()

                if (!scriptHtml.contains("sources")) {
                    continue
                }

                val match = Regex(
                    """(?:const|let|var)\s+sources\s*=\s*(\[[\s\S]*?])\s*;?"""
                ).find(scriptHtml)

                val raw = match?.groups?.get(1)?.value
                    ?: continue

                try {

                    val sources = JSONArray(raw)

                    for (i in 0 until sources.length()) {

                        val source = sources.optJSONObject(i)
                            ?: continue

                        val src = source.optString("src")

                        if (src.isBlank()) continue

                        val videoUrl = when {

                            src.startsWith("http://") ||
                            src.startsWith("https://") ->
                                src

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

                        val type =
                            if (
                                videoUrl.contains(
                                    ".m3u8",
                                    ignoreCase = true
                                )
                            ) {
                                ExtractorLinkType.M3U8
                            } else {
                                ExtractorLinkType.VIDEO
                            }

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name - ${quality}p",
                                url = videoUrl,
                                type = type
                            ) {

                                referer = "$VIDEO_API/"

                                this.quality = quality
                            }
                        )

                        found = true
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "sources JSON error: ${e.message}"
                    )
                }
            }

            // JSON bulunamazsa doğrudan video URL'lerini ara.

            val directUrls = Regex(
                """(?:https?:)?//[^\s"'`\\]+?\.(?:mp4|m3u8)(?:\?[^\s"'`\\]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(html)

            for (match in directUrls) {

                var videoUrl = match.value
                    .replace("\\/", "/")

                if (videoUrl.startsWith("//")) {
                    videoUrl = "https:$videoUrl"
                }

                val type =
                    if (
                        videoUrl.contains(
                            ".m3u8",
                            ignoreCase = true
                        )
                    ) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Direct",
                        url = videoUrl,
                        type = type
                    ) {

                        referer = "$VIDEO_API/"
                        quality = Qualities.Unknown.value
                    }
                )

                found = true
            }

            found

        } catch (e: Exception) {

            Log.e(
                TAG,
                "AnimeUzayi error: ${e.message}"
            )

            false
        }
    }

    // ------------------------------------------------------------
    // CARD PARSER
    // ------------------------------------------------------------

    private fun extractAnimeCards(
        document: org.jsoup.nodes.Document
    ): List<SearchResponse> {

        val result = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        val selectors = listOf(
            "article.bs div.bsx",
            "article.bs",
            ".bsx",
            ".bs",
            ".anime-card",
            ".anime-item",
            ".item-summary"
        )

        val cards = mutableListOf<Element>()

        for (selector in selectors) {

            document.select(selector).forEach { card ->

                if (!cards.contains(card)) {
                    cards.add(card)
                }
            }

            if (cards.isNotEmpty()) {
                break
            }
        }

        for (card in cards) {

            val link =
                card.selectFirst(
                    "a[href]"
                ) ?: continue

            val href = link.absUrl("href").ifBlank {
                fixUrlNull(
                    link.attr("href")
                ) ?: ""
            }

            if (href.isBlank()) continue

            if (!seen.add(href)) continue

            val title =
                firstElementText(
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
                    ?: link.text().trim()

            if (title.isBlank()) continue

            val poster = extractPoster(card)

            result.add(
                newAnimeSearchResponse(
                    title.trim(),
                    href,
                    TvType.Anime
                ) {
                    posterUrl = poster
                }
            )
        }

        return result
    }

    // ------------------------------------------------------------
    // POSTER
    // ------------------------------------------------------------

    private fun extractPoster(
        element: Element
    ): String? {

        val imageSelectors = listOf(
            "div.limit img",
            ".limit img",
            ".poster img",
            ".thumb img",
            ".anime-poster img",
            "img"
        )

        for (selector in imageSelectors) {

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

                if (source.isBlank()) continue

                val url = fixUrlNull(source)

                if (!url.isNullOrBlank()) {
                    return url
                }
            }

            // srcset fallback

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

                val url = fixUrlNull(first)

                if (!url.isNullOrBlank()) {
                    return url
                }
            }
        }

        return null
    }

    // ------------------------------------------------------------
    // TAGS
    // ------------------------------------------------------------

    private fun extractTags(
        document: org.jsoup.nodes.Document
    ): List<String> {

        val selectors = listOf(
            "div#genxed a[href*='/category']",
            ".genres a",
            ".genre a",
            ".tags a",
            ".anime-genres a"
        )

        val tags = mutableListOf<String>()

        for (selector in selectors) {

            document.select(selector).forEach {

                val text = it.text().trim()

                if (
                    text.isNotBlank() &&
                    !tags.contains(text)
                ) {
                    tags.add(text)
                }
            }

            if (tags.isNotEmpty()) {
                break
            }
        }

        return tags
    }

    // ------------------------------------------------------------
    // TEXT HELPERS
    // ------------------------------------------------------------

    private fun firstText(
        element: Element,
        vararg selectors: String
    ): String? {

        for (selector in selectors) {

            val found =
                element.selectFirst(selector)
                    ?.text()
                    ?.trim()

            if (!found.isNullOrBlank()) {
                return found
            }
        }

        return null
    }

    private fun firstElementText(
        element: Element,
        vararg selectors: String
    ): String? {

        for (selector in selectors) {

            val found =
                element
                    .selectFirst(selector)
                    ?.text()
                    ?.trim()

            if (!found.isNullOrBlank()) {
                return found
            }
        }

        return null
    }

    // ------------------------------------------------------------
    // EPISODE NUMBER
    // ------------------------------------------------------------

    private fun extractEpisodeNumber(
        title: String,
        href: String
    ): Int? {

        val patterns = listOf(
            Regex(
                """(?:bölüm|bolum|episode|ep\.?)\s*[-:]?\s*(\d+)""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """(?:-|\s)(\d+)(?:/)?$"""
            ),
            Regex(
                """episode[-_/]?(\d+)""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """/(\d+)(?:/)?$"""
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

    private fun cleanEpisodeTitle(
        title: String
    ): String {

        return title
            .replace(
                Regex(
                    """\s+"""
                ),
                " "
            )
            .trim()
    }

    // ------------------------------------------------------------
    // PAGE
    // ------------------------------------------------------------

    private fun buildPageUrl(
        base: String,
        page: Int
    ): String {

        if (page <= 1) return base

        return when {

            base.contains("?") ->
                "$base&page=$page"

            base.endsWith("/") ->
                "${base}page/$page/"

            else ->
                "$base/page/$page/"
        }
    }

    private fun hasNextPage(
        document: org.jsoup.nodes.Document,
        currentPage: Int
    ): Boolean {

        val nextSelectors = listOf(
            "a.next",
            ".pagination a.next",
            ".pagination .next",
            "a[rel=next]",
            ".nav-previous a"
        )

        return nextSelectors.any { selector ->
            document.selectFirst(selector) != null
        }
    }
}
