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

        Log.e("TRasyalog", "getMainPage: $pageUrl")

        val document = app.get(pageUrl).document

        val home = document
            .select("div.frag-k")
            .mapNotNull { it.toMainPageResult() }

        Log.e("TRasyalog", "getMainPage found ${home.size} items")

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

        Log.e("TRasyalog", "search: $encodedQuery")

        val document = app.get(
            "$mainUrl/?s=$encodedQuery"
        ).document

        val results = document
            .select(
                "div.frag-k, div.post-container, .sag-liste li"
            )
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        Log.e("TRasyalog", "search found ${results.size} results")

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

        Log.e("TRasyalog", "========== load() START ==========")
        Log.e("TRasyalog", "load() URL: $url")

        val document = app.get(url).document

        Log.e("TRasyalog", "Document title tag: ${document.title()}")

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

        Log.e("TRasyalog", "load() title: $title")

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

        Log.e("TRasyalog", "staticLinks size: ${staticLinks.size}")

        val links: List<Element> = if (staticLinks.isNotEmpty()) {
            staticLinks
        } else {
            Log.e("TRasyalog", "No static links, trying AJAX fallback")

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

            Log.e("TRasyalog", "postId: $postId")

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
                    Log.e("TRasyalog", "AJAX links size: ${ajaxLinks.size}")
                    ajaxLinks
                } catch (e: Exception) {
                    Log.e("TRasyalog", "AJAX failed", e)
                    emptyList()
                }
            } else {
                val fallbackLinks = document.select("a[href*='/bolum/']")
                Log.e("TRasyalog", "Fallback links size: ${fallbackLinks.size}")
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

        Log.e("TRasyalog", "finalEpisodes size: ${finalEpisodes.size}")
        Log.e("TRasyalog", "========== load() END ==========")

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

        Log.e("TRasyalog", "########## loadLinks START ##########")
        Log.e("TRasyalog", "loadLinks data: $data")

        val pageUrl = data.substringBefore("#").trim()

        if (pageUrl.isEmpty()) {
            Log.e("TRasyalog", "pageUrl is empty!")
            return false
        }

        Log.e("TRasyalog", "pageUrl: $pageUrl")

        val document = app.get(pageUrl).document

        val iframeElements = document.select(
            "#plyg iframe, iframe[src*='odnoklassniki'], iframe[src*='ok.ru'], iframe"
        )

        Log.e("TRasyalog", "Found ${iframeElements.size} iframe(s)")

        if (iframeElements.isEmpty()) {
            Log.e("TRasyalog", "No iframe found on page!")
            return false
        }

        var found = false

        iframeElements.forEach { iframe ->

            var src = iframe.attr("src").trim()
            if (src.isEmpty()) src = iframe.attr("data-src").trim()
            if (src.isEmpty()) src = iframe.attr("data-litespeed-src").trim()
            if (src.isEmpty()) src = iframe.attr("data-url").trim()

            Log.e("TRasyalog", "iframe src: $src")

            if (src.isEmpty()) return@forEach
            if (src.startsWith("javascript:", ignoreCase = true)) return@forEach
            if (src == "about:blank") return@forEach

            val fixedUrl = when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("/")  -> fixUrl(src)
                else                 -> src
            }

            Log.e("TRasyalog", "fixedUrl: $fixedUrl")

            if (fixedUrl.contains("odnoklassniki") || fixedUrl.contains("ok.ru")) {
                Log.e("TRasyalog", "OK.ru detected, extracting directly")

                val videoUrls = extractOkRuVideo(fixedUrl, pageUrl)

                if (videoUrls.isNotEmpty()) {
                    Log.e("TRasyalog", "OK.ru found ${videoUrls.size} URL(s)")

                    videoUrls.forEachIndexed { index, (url, qualityName) ->
                        Log.e("TRasyalog", "OK.ru URL[$index] ($qualityName): $url")

                        val qualityValue = when (qualityName.lowercase()) {
                            "mobile" -> Qualities.P144.value
                            "lowest" -> Qualities.P240.value
                            "low" -> Qualities.P360.value
                            "sd" -> Qualities.P480.value
                            "hd" -> Qualities.P720.value
                            "full" -> Qualities.P1080.value
                            "quad" -> Qualities.P2160.value
                            else -> Qualities.Unknown.value
                        }

                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} (OK.ru) - $qualityName",
                                url = url,
                                type = if (url.contains(".m3u8"))
                                    ExtractorLinkType.M3U8
                                else
                                    ExtractorLinkType.VIDEO
                            ) {
                                this.referer = pageUrl
                                this.quality = qualityValue
                            }
                        )
                    }
                    found = true
                } else {
                    Log.e("TRasyalog", "OK.ru extraction returned empty, trying loadExtractor")

                    try {
                        loadExtractor(fixedUrl, referer = pageUrl, subtitleCallback, callback)
                        Log.e("TRasyalog", "loadExtractor completed for OK.ru")
                        found = true
                    } catch (e: Exception) {
                        Log.e("TRasyalog", "loadExtractor also failed", e)
                    }
                }
            } else {
                try {
                    loadExtractor(fixedUrl, referer = pageUrl, subtitleCallback, callback)
                    Log.e("TRasyalog", "loadExtractor SUCCESS for $fixedUrl")
                    found = true
                } catch (e: Exception) {
                    Log.e("TRasyalog", "loadExtractor FAILED: ${e.message}", e)
                }
            }
        }

        Log.e("TRasyalog", "loadLinks returning: $found")
        Log.e("TRasyalog", "########## loadLinks END ##########")
        return found
    }

    /**
     * OK.ru embed sayfasından video URL'lerini çıkarır.
     * HTML entity'leri (&quot;) düzgün şekilde keser.
     * Dönen liste: (url, kalite adı) çiftleri
     */
    private suspend fun extractOkRuVideo(
        embedUrl: String,
        referer: String
    ): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()

        try {
            val videoId = Regex("""(?:videoembed|/video)/(\d+)""")
                .find(embedUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?: return emptyList()

            Log.e("TRasyalog", "Video ID: $videoId")

            // Yöntem 1: OK.ru mobil sayfası
            try {
                val mobileApiUrl = "https://m.ok.ru/video/$videoId"
                Log.e("TRasyalog", "Trying mobile: $mobileApiUrl")

                val mobileHtml = app.get(
                    mobileApiUrl,
                    referer = referer,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; SM-G991B) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/120.0.0.0 Mobile Safari/537.36",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                ).text

                Log.e("TRasyalog", "Mobile HTML length: ${mobileHtml.length}")

                // HTML entity'lerini (&quot; &amp; &#34;) normal karakterlere çevir
                val decodedHtml = mobileHtml
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .replace("&#34;", "\"")
                    .replace("&#39;", "'")
                    .replace("\\/", "/")

                // 1. Aşama: Decode edilmiş HTML'de JSON formatını ara
                val jsonPatterns = listOf(
                    Regex(""""url"\s*:\s*"([^"]+\.m3u8[^"]*)""""),
                    Regex(""""url"\s*:\s*"([^"]+\.mp4[^"]*)""""),
                    Regex(""""hls"\s*:\s*"([^"]+\.m3u8[^"]*)""""),
                    Regex(""""ondemand"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
                )

                for ((index, pattern) in jsonPatterns.withIndex()) {
                    val match = pattern.find(decodedHtml)
                    if (match != null) {
                        var url = match.groupValues[1]
                            .replace("\\/", "/")
                            .trim()

                        if (url.startsWith("//")) url = "https:$url"

                        // URL'nin sonundaki olası çöp karakterleri temizle
                        url = url.substringBefore("\"").substringBefore("&").trim()

                        Log.e("TRasyalog", "JSON pattern[$index]: $url")
                        results.add(url to "Mobile")
                        break
                    }
                }

                // 2. Aşama: Decode edilmemiş HTML'de doğrudan URL ara
                // & karakteri regex'ten çıkarıldı, böylece URL &quot; gördüğünde kesiliyor
                if (results.isEmpty()) {
                    val urlPatterns = listOf(
                        Regex("""(https?://[^"'\s\\<>&]+\.m3u8[^"'\s\\<>&]*)"""),
                        Regex("""(https?://[^"'\s\\<>&]+\.mp4[^"'\s\\<>&]*)"""),
                        Regex("""(//[^"'\s\\<>&]+\.m3u8[^"'\s\\<>&]*)"""),
                        Regex("""(//[^"'\s\\<>&]+\.mp4[^"'\s\\<>&]*)""")
                    )

                    for ((index, pattern) in urlPatterns.withIndex()) {
                        val match = pattern.find(mobileHtml)
                        if (match != null) {
                            var url = match.groupValues[1]
                                .replace("\\/", "/")
                                .trim()

                            // Ekstra temizlik: &quot ve &amp'den sonrasını kes
                            if (url.contains("&quot")) url = url.substringBefore("&quot")
                            if (url.contains("&amp")) url = url.substringBefore("&amp")
                            if (url.contains("&")) url = url.substringBefore("&")

                            if (url.startsWith("//")) url = "https:$url"

                            Log.e("TRasyalog", "URL pattern[$index]: $url")
                            results.add(url to "Mobile")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TRasyalog", "Mobile fetch failed: ${e.message}")
            }

            // Yöntem 2: videoPlayerMetadata API
            if (results.isEmpty()) {
                val apiUrls = listOf(
                    "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId",
                    "https://m.ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId",
                    "https://odnoklassniki.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"
                )

                for (apiUrl in apiUrls) {
                    try {
                        Log.e("TRasyalog", "Trying API: $apiUrl")

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

                        Log.e("TRasyalog", "API response length: ${apiResponse.length}")

                        val videoEntries = Regex(
                            """"name"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+)"""",
                            RegexOption.IGNORE_CASE
                        ).findAll(apiResponse).map {
                            val quality = it.groupValues[1]
                            val url = it.groupValues[2]
                                .replace("\\/", "/")
                                .replace("\\u0026", "&")
                                .substringBefore("&")
                                .trim()
                            quality to url
                        }.toList()

                        if (videoEntries.isNotEmpty()) {
                            Log.e("TRasyalog", "Found ${videoEntries.size} video entries via API")
                            videoEntries.forEach { (quality, url) ->
                                Log.e("TRasyalog", "  API entry - $quality: $url")
                                if (!results.any { it.first == url }) {
                                    results.add(url to quality)
                                }
                            }
                            break
                        }
                    } catch (e: Exception) {
                        Log.e("TRasyalog", "API $apiUrl failed: ${e.message}")
                    }
                }
            }

            Log.e("TRasyalog", "Total extracted URLs: ${results.size}")

        } catch (e: Exception) {
            Log.e("TRasyalog", "extractOkRuVideo error", e)
        }

        return results
    }
}