package com.UmayTrade

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TRasyalog : MainAPI() {

    override var mainUrl = "https://asyalog.co"
    override var name = "AsyaLog"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 500L
    override var sequentialMainPageScrollDelay = 500L

    override val mainPage = mainPageOf(
        "$mainUrl/diziler/ulke/guney-kore/" to "Kore Dizileri",
        "$mainUrl/diziler/ulke/cin/" to "Çin Dizileri",
        "$mainUrl/diziler/ulke/tayland/" to "Tayland Dizileri",
        "$mainUrl/diziler/ulke/japonya/" to "Japon Dizileri",
        "$mainUrl/diziler/ulke/endonezya/" to "Endonezya Dizileri",
        "$mainUrl/devam-eden-diziler/" to "Devam Eden Diziler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val pageUrl = if (page <= 1) {
            request.data
        } else {
            "${request.data}/page/$page/"
        }

        Log.d("TRasyalog", "getMainPage: $pageUrl")

        val document = app.get(pageUrl).document

        val home = document
            .select("div.frag-k")
            .mapNotNull { it.toMainPageResult() }

        Log.d("TRasyalog", "getMainPage found ${home.size} items")

        return newHomePageResponse(
            request.name,
            home
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {

        val title = selectFirst("a.baslik span")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: selectFirst("a.resim")
                ?.attr("title")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: return null

        val href = selectFirst("a.resim")
            ?.attr("href")
            ?.let { fixUrlNull(it) }
            ?: selectFirst("a.baslik")
                ?.attr("href")
                ?.let { fixUrlNull(it) }
            ?: return null

        val poster = selectFirst("a.resim img")
            ?.let { img ->

                val src = img.attr("src")
                    .takeIf { it.isNotBlank() }
                    ?: img.attr("data-src")
                        .takeIf { it.isNotBlank() }

                src?.let { fixUrlNull(it) }
            }

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            posterUrl = poster
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = query
            .trim()
            .replace(" ", "+")

        Log.d("TRasyalog", "search: $encodedQuery")

        val document = app.get(
            "$mainUrl/?s=$encodedQuery"
        ).document

        val results = document
            .select(
                "div.frag-k, div.post-container, .sag-liste li"
            )
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        Log.d("TRasyalog", "search found ${results.size} results")

        return results
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        Log.d("TRasyalog", "========== load() START ==========")
        Log.d("TRasyalog", "load() URL: $url")

        val document = app.get(url).document

        Log.d("TRasyalog", "Document title tag: ${document.title()}")

        val title =
            document.selectFirst(".ssag h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: document.selectFirst("h1")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ?: document.title()
                    .split("|")
                    .firstOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ?: return null

        Log.d("TRasyalog", "load() title: $title")

        val posterElement = document.selectFirst(
            ".afis img"
        )

        val poster = posterElement?.let { img ->

            val src = img.attr("src")
                .takeIf { it.isNotBlank() }
                ?: img.attr("data-src")
                    .takeIf { it.isNotBlank() }

            src?.let { fixUrlNull(it) }
        }

        val description = document
            .selectFirst(".ozet, .aciklama")
            ?.text()
            ?.trim()

        val tags = document
            .select(
                ".kategori a, .post-tags a, span.genre"
            )
            .mapNotNull {
                it.text()
                    .trim()
                    .takeIf { text -> text.isNotEmpty() }
            }
            .distinct()

        val staticLinks = document.select(
            ".dizi-bolumler ul.scroll-liste > li a[href*='/bolum/']"
        )

        Log.d("TRasyalog", "staticLinks size: ${staticLinks.size}")

        val links: List<Element> = if (staticLinks.isNotEmpty()) {
            staticLinks
        } else {
            Log.d("TRasyalog", "No static links, trying AJAX fallback")

            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            val actionValue = "bolumleri_getir"

            val postId = document.selectFirst("body")
                ?.attr("class")
                ?.let { bodyClass ->
                    Regex("""postid-(\d+)""")
                        .find(bodyClass)
                        ?.groupValues
                        ?.getOrNull(1)
                }
                ?: document.selectFirst("input[name='post_id']")
                    ?.attr("value")
                ?: document.selectFirst("[data-post-id]")
                    ?.attr("data-post-id")

            Log.d("TRasyalog", "postId: $postId")

            if (postId != null) {
                try {
                    val ajaxResponse = app.post(
                        ajaxUrl,
                        data = mapOf(
                            "action" to actionValue,
                            "post_id" to postId
                        ),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to url
                        )
                    ).document

                    val ajaxLinks = ajaxResponse.select("a[href*='/bolum/']")
                    Log.d("TRasyalog", "AJAX links size: ${ajaxLinks.size}")
                    ajaxLinks
                } catch (e: Exception) {
                    Log.e("TRasyalog", "AJAX failed", e)
                    emptyList()
                }
            } else {
                val fallbackLinks = document.select("a[href*='/bolum/']")
                Log.d("TRasyalog", "Fallback links size: ${fallbackLinks.size}")
                fallbackLinks
            }
        }

        val episodes = mutableListOf<Episode>()

        links.forEach { element ->

            val href = element
                .attr("href")
                .trim()

            if (href.isEmpty()) return@forEach

            val fixedHref = fixUrlNull(href)
                ?: return@forEach

            if (!fixedHref.contains("/bolum/")) {
                return@forEach
            }

            if (
                fixedHref.contains(
                    "fragman",
                    ignoreCase = true
                )
            ) {
                return@forEach
            }

            val path = fixedHref
                .substringBefore("?")
                .substringBefore("#")
                .trimEnd('/')

            val lastPart = path
                .substringAfterLast('/')

            val rangeMatch = Regex(
                """(\d+)-(\d+)-bolum""",
                RegexOption.IGNORE_CASE
            ).find(lastPart)

            if (rangeMatch != null) {

                val start = rangeMatch
                    .groupValues[1]
                    .toIntOrNull()
                    ?: return@forEach

                val end = rangeMatch
                    .groupValues[2]
                    .toIntOrNull()
                    ?: return@forEach

                if (end < start) {
                    return@forEach
                }

                for (episodeNumber in start..end) {

                    val partNumber =
                        episodeNumber - start + 1

                    val episodeUrl =
                        if (partNumber == 1) {
                            fixedHref.trimEnd('/') + "/"
                        } else {
                            fixedHref.trimEnd('/') +
                                "/$partNumber/"
                        }

                    episodes.add(
                        newEpisode(episodeUrl) {

                            name = "$episodeNumber. Bölüm"
                            episode = episodeNumber
                        }
                    )
                }

            } else {

                val singleMatch = Regex(
                    """(\d+)-bolum""",
                    RegexOption.IGNORE_CASE
                ).find(lastPart)

                val episodeNumber =
                    singleMatch
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: Regex("""\d+""")
                            .find(element.text())
                            ?.value
                            ?.toIntOrNull()

                val episodeName =
                    if (episodeNumber != null) {
                        "$episodeNumber. Bölüm"
                    } else {
                        element.text().trim()
                    }

                episodes.add(
                    newEpisode(fixedHref) {

                        name = episodeName
                        episode = episodeNumber
                    }
                )
            }
        }

        val finalEpisodes = episodes
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> {
                    it.episode ?: Int.MAX_VALUE
                }
            )

        Log.d("TRasyalog", "finalEpisodes size: ${finalEpisodes.size}")
        Log.d("TRasyalog", "========== load() END ==========")

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            finalEpisodes
        ) {

            posterUrl = poster
            plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d("TRasyalog", "########## loadLinks START ##########")
        Log.d("TRasyalog", "loadLinks data: $data")

        val pageUrl = data.substringBefore("#").trim()

        if (pageUrl.isEmpty()) {
            Log.e("TRasyalog", "pageUrl is empty!")
            return false
        }

        Log.d("TRasyalog", "pageUrl: $pageUrl")

        val document = app.get(pageUrl).document

        val iframeElements = document.select(
            "#plyg iframe, iframe[src*='odnoklassniki'], iframe[src*='ok.ru'], iframe"
        )

        Log.d("TRasyalog", "Found ${iframeElements.size} iframe(s)")

        if (iframeElements.isEmpty()) {
            Log.e("TRasyalog", "No iframe found on page!")
            return false
        }

        var found = false

        iframeElements.forEachIndexed { index, iframe ->

            var src = iframe.attr("src").trim()
            if (src.isEmpty()) src = iframe.attr("data-src").trim()
            if (src.isEmpty()) src = iframe.attr("data-litespeed-src").trim()
            if (src.isEmpty()) src = iframe.attr("data-url").trim()

            Log.d("TRasyalog", "iframe[$index] src: $src")

            if (src.isEmpty()) return@forEachIndexed
            if (src.startsWith("javascript:", ignoreCase = true)) return@forEachIndexed
            if (src == "about:blank") return@forEachIndexed

            val fixedUrl = when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("/")  -> fixUrl(src)
                else                 -> src
            }

            Log.d("TRasyalog", "iframe[$index] fixedUrl: $fixedUrl")

            try {
                loadExtractor(
                    fixedUrl,
                    referer = pageUrl,
                    subtitleCallback,
                    callback
                )
                Log.d("TRasyalog", "loadExtractor SUCCESS for $fixedUrl")
                found = true
            } catch (e: Exception) {
                Log.e("TRasyalog", "loadExtractor FAILED for $fixedUrl: ${e.message}", e)
            }

            if (!found && (fixedUrl.contains("odnoklassniki") || fixedUrl.contains("ok.ru"))) {
                try {
                    Log.d("TRasyalog", "Trying fallback OK.ru extraction")
                    val okVideoUrl = extractOkRuVideo(fixedUrl, pageUrl)
                    if (okVideoUrl != null) {
                        Log.d("TRasyalog", "Fallback OK.ru URL: $okVideoUrl")
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} (OK.ru)",
                                url = okVideoUrl,
                                type = if (okVideoUrl.contains(".m3u8"))
                                    ExtractorLinkType.M3U8
                                else
                                    ExtractorLinkType.VIDEO
                            ) {
                                this.referer = pageUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                } catch (e: Exception) {
                    Log.e("TRasyalog", "Fallback OK.ru extraction failed", e)
                }
            }
        }

        Log.d("TRasyalog", "loadLinks returning: $found")
        Log.d("TRasyalog", "########## loadLinks END ##########")
        return found
    }

    private suspend fun extractOkRuVideo(
        embedUrl: String,
        referer: String
    ): String? {
        return try {
            val videoId = Regex("""videoembed/(\d+)""")
                .find(embedUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?: return null

            val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"
            Log.d("TRasyalog", "OK.ru API: $apiUrl")

            val apiResponse = app.get(
                apiUrl,
                referer = embedUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text

            Log.d("TRasyalog", "OK.ru API response first 300: ${apiResponse.take(300)}")

            val videoUrlRegex = Regex(
                """"url"\s*:\s*"([^"]+\.(?:mp4|m3u8)[^"]*)"""",
                RegexOption.IGNORE_CASE
            )

            val matches = videoUrlRegex.findAll(apiResponse)
                .map { it.groupValues[1].replace("\\/", "/") }
                .toList()

            Log.d("TRasyalog", "Found ${matches.size} video URLs")

            matches.lastOrNull()
        } catch (e: Exception) {
            Log.e("TRasyalog", "OK.ru API error", e)
            null
        }
    }
}