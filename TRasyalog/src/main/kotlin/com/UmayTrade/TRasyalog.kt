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

        val document = app.get(pageUrl).document

        val home = document
            .select("div.frag-k")
            .mapNotNull { it.toMainPageResult() }

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

        val document = app.get(
            "$mainUrl/?s=$encodedQuery"
        ).document

        return document
            .select(
                "div.frag-k, div.post-container, .sag-liste li"
            )
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

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

        val links: List<Element> = if (staticLinks.isNotEmpty()) {
            staticLinks
        } else {
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

                    ajaxResponse.select("a[href*='/bolum/']")
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                document.select("a[href*='/bolum/']")
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

        val pageUrl = data.substringBefore("#").trim()
        if (pageUrl.isEmpty()) return false

        val document = app.get(pageUrl).document

        val iframeElements = document.select(
            "#plyg iframe, iframe[src*='odnoklassniki'], iframe[src*='ok.ru'], iframe"
        )

        if (iframeElements.isEmpty()) return false

        var found = false

        iframeElements.forEach { iframe ->

            var src = iframe.attr("src").trim()
            if (src.isEmpty()) src = iframe.attr("data-src").trim()
            if (src.isEmpty()) src = iframe.attr("data-litespeed-src").trim()
            if (src.isEmpty()) src = iframe.attr("data-url").trim()

            if (src.isEmpty()) return@forEach
            if (src.startsWith("javascript:", ignoreCase = true)) return@forEach
            if (src == "about:blank") return@forEach

            val fixedUrl = when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("/")  -> fixUrl(src)
                else                 -> src
            }

            // 1) CloudStream'in yerleşik extractor'ını dene
            try {
                loadExtractor(
                    fixedUrl,
                    referer = pageUrl,
                    subtitleCallback,
                    callback
                )
                found = true
            } catch (e: Exception) {
                Log.e("TRasyalog", "Built-in extractor failed for $fixedUrl", e)
            }

            // 2) Yedek: OK.ru embed sayfasından doğrudan video URL'si çıkar
            if (!found && fixedUrl.contains("odnoklassniki") || fixedUrl.contains("ok.ru")) {
                try {
                    val okVideoUrl = extractOkRuVideo(fixedUrl, pageUrl)
                    if (okVideoUrl != null) {
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

        return found
    }

    /**
     * OK.ru embed sayfasından doğrudan video URL'sini çıkarmayı dener.
     * OK.ru'nun kendi API'sini kullanır.
     */
    private suspend fun extractOkRuVideo(
        embedUrl: String,
        referer: String
    ): String? {
        return try {
            // Embed sayfasını çek
            val embedDoc = app.get(
                embedUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Safari/537.36"
                )
            ).text

            // Video ID'sini çıkar
            val videoId = Regex("""videoembed/(\d+)""")
                .find(embedUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?: return null

            // OK.ru API'sinden video bilgilerini al
            val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"
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

            // JSON yanıtından en yüksek kaliteli video URL'sini çıkar
            val videoUrlRegex = Regex(
                """"url"\s*:\s*"([^"]+\.(?:mp4|m3u8)[^"]*)"""",
                RegexOption.IGNORE_CASE
            )

            val matches = videoUrlRegex.findAll(apiResponse)
                .map { it.groupValues[1].replace("\\/", "/") }
                .toList()

            // En yüksek kaliteyi seç (genelde sonuncu veya en büyük boyutlu)
            matches.lastOrNull()
        } catch (e: Exception) {
            Log.e("TRasyalog", "OK.ru API extraction error", e)
            null
        }
    }
}
