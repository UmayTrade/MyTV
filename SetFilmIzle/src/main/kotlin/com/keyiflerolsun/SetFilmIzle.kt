package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

typealias SetFilmIzleProvider = SetFilmIzle

class SetFilmIzle : MainAPI() {
    override var mainUrl: String
        get() = "https://www.setfilmizle.ltd"
        set(_) {}
    override var name = "SetFilmİzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/film/" to "Filmler",
        "$mainUrl/dizi/" to "Diziler",
        "$mainUrl/trend/" to "Trendler",
        "$mainUrl/ag/netflix/" to "Netflix",
        "$mainUrl/ag/apple-tv/" to "Apple TV+",
        "$mainUrl/ag/prime-video/" to "Prime Video",
        "$mainUrl/turkce-dublaj-filmler/" to "Türkçe Dublaj",
        "$mainUrl/tur/aksiyon/" to "Aksiyon",
        "$mainUrl/tur/bilim-kurgu/" to "Bilim-Kurgu",
        "$mainUrl/tur/komedi/" to "Komedi",
        "$mainUrl/tur/korku/" to "Korku"
    )

    // ---------------------------------------------------------------
    // Header yardımcıları
    // ---------------------------------------------------------------
    private fun baseHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "Referer" to referer,
        "User-Agent" to CommonHeaders.DEFAULT_USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    private fun ajaxHeaders(referer: String): Map<String, String> = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to referer,
        "User-Agent" to CommonHeaders.systemUserAgent,
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )

    // ---------------------------------------------------------------
    // Ana sayfa
    // ---------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data.trimEnd('/')}/page/$page/" else request.data
        val document = app.get(url, headers = baseHeaders()).document

        val home = document.select(
            "a.card-link, a:has(article.card), article.card, article.item.movies, article.item, div.items article"
        )
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(
            if (this.tagName() == "a") this.attr("href") else this.selectFirst("a")?.attr("href")
        ) ?: return null

        val title = this.selectFirst("h2.card-ad, h2, h3, a")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val img = this.selectFirst("img")
        val rawPoster = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img?.attr("data-srcset")?.split(" ")?.firstOrNull { it.startsWith("http") }
            ?: img?.attr("srcset")?.split(" ")?.firstOrNull { it.startsWith("http") }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

        val posterUrl = fixUrlNull(rawPoster)
        val pHeaders = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to CommonHeaders.DEFAULT_USER_AGENT
        )

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.posterHeaders = pHeaders
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.posterHeaders = pHeaders
            }
        }
    }

    // ---------------------------------------------------------------
    // Arama
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val mainPage = runCatching {
            app.get(mainUrl, headers = baseHeaders()).document
        }.getOrNull()

        val nonce = mainPage?.let {
            Regex("""nonce:\s*'([^']+)'""").find(it.html())?.groupValues?.get(1)
        } ?: ""

        val response = runCatching {
            app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = ajaxHeaders("$mainUrl/"),
                data = mapOf(
                    "action" to "ajax_search",
                    "nonce" to nonce,
                    "search" to query
                )
            )
        }.getOrNull()

        val results = mutableListOf<SearchResponse>()

        if (response != null && response.text.isNotBlank()) {
            val htmlContent = runCatching {
                JSONObject(response.text).optString("html")
            }.getOrDefault(response.text)

            if (htmlContent.isNotBlank()) {
                val doc = Jsoup.parse(htmlContent)
                doc.select("article").forEach { art ->
                    art.toSearchResult()?.let { results.add(it) }
                }
            }
        }

        if (results.isEmpty()) {
            val fallbackDoc = runCatching {
                app.get("$mainUrl/?s=$query", headers = baseHeaders()).document
            }.getOrNull()

            fallbackDoc?.select(
                "a.card-link, a:has(article.card), article.card, article.item.movies, article.item, div.items article"
            )
                ?.mapNotNull { it.toSearchResult() }
                ?.distinctBy { it.url }
                ?.let { results.addAll(it) }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---------------------------------------------------------------
    // Load
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = baseHeaders()).document

        val title = document.selectFirst("h1")?.text()?.replace(" izle", "")?.trim() ?: return null

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.poster img")?.attr("src")
                ?: document.selectFirst("img")?.attr("data-src")
        )

        val plot = document.selectFirst(
            "div.wp-content p, div#info p, meta[property='og:description']"
        )?.text()?.trim()

        val year = document.selectFirst("div.extra span.C a, span.C a, div.extra span a")
            ?.text()?.trim()?.toIntOrNull()
            ?: Regex("""(\d{4})""").find(document.html())?.groupValues?.get(1)?.toIntOrNull()

        val rating = document.selectFirst("span.dt_rating_vgs, span.rating")?.text()?.trim()

        val isSeries = url.contains("/dizi/") || document.select("div#episodes").isNotEmpty()

        val postId = document.selectFirst("#stfPlayer[data-post-id]")?.attr("data-post-id")
            ?: document.selectFirst("[data-post-id]")?.attr("data-post-id")

        val nonce = Regex("""video:\s*"([^"]+)"""").find(document.html())?.groupValues?.get(1) ?: ""

        val firstTab = document.selectFirst(".src-tab.selected") ?: document.selectFirst(".src-tab")
        val playerName = firstTab?.attr("data-player-name") ?: "SetPlay"
        val partKey = firstTab?.attr("data-part-key") ?: ""

        val linkData = if (!postId.isNullOrBlank()) {
            "$postId|$url|$nonce|$playerName|$partKey"
        } else {
            url
        }

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()

            document.select("div#episodes ul.episodios li, ul.episodios li").forEach { epLi ->
                val epHref = fixUrlNull(epLi.selectFirst("a")?.attr("href")) ?: return@forEach
                val epTitle = epLi.selectFirst("h4.episodiotitle a, a")?.text()?.trim()

                val seMatch = Regex("""(\d+)x(\d+)""").find(epLi.text())
                    ?: Regex("""(\d+)\.\s*Sezon\s*(\d+)\.\s*Bölüm""").find(epLi.text())

                val sNum = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                val eNum = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.season = sNum
                        this.episode = eNum
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = Score.from10(rating)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, linkData) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = Score.from10(rating)
            }
        }
    }

    // ---------------------------------------------------------------
    // LoadLinks
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var processed = false

        val tokens = data.split("|")
        var postId = tokens.getOrNull(0)
        val pageUrl = tokens.getOrNull(1) ?: data
        var nonce = tokens.getOrNull(2) ?: ""
        var playerName = tokens.getOrNull(3) ?: "SetPlay"
        var partKey = tokens.getOrNull(4) ?: ""

        // Eksik veri varsa sayfayı yeniden çek
        if ((nonce.isBlank() || postId.isNullOrBlank()) && pageUrl.startsWith("http")) {
            val pageDoc = runCatching {
                app.get(pageUrl, headers = baseHeaders()).document
            }.getOrNull()

            if (pageDoc != null) {
                if (postId.isNullOrBlank()) {
                    postId = pageDoc.selectFirst("#stfPlayer[data-post-id]")?.attr("data-post-id")
                        ?: pageDoc.selectFirst("[data-post-id]")?.attr("data-post-id")
                }
                if (nonce.isBlank()) {
                    nonce = Regex("""video:\s*"([^"]+)"""").find(pageDoc.html())?.groupValues?.get(1) ?: ""
                }
                val tab = pageDoc.selectFirst(".src-tab.selected") ?: pageDoc.selectFirst(".src-tab")
                if (playerName.isBlank()) playerName = tab?.attr("data-player-name") ?: "SetPlay"
                if (partKey.isBlank()) partKey = tab?.attr("data-part-key") ?: ""
            }
        }

        if (!postId.isNullOrBlank() && nonce.isNotBlank()) {
            val response = runCatching {
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    headers = ajaxHeaders("$pageUrl/"),
                    data = mapOf(
                        "action" to "get_video_url",
                        "nonce" to nonce,
                        "post_id" to postId,
                        "player_name" to playerName,
                        "part_key" to partKey
                    )
                )
            }.getOrNull()

            if (response != null && response.text.isNotBlank()) {
                val jsonObj = runCatching { JSONObject(response.text) }.getOrNull()
                val streamObj = jsonObj?.optJSONObject("data")?.optJSONObject("stream")
                val bridgeUrl = streamObj?.optString("url")
                    ?: Regex("""src=["']([^"']+)["']""").find(response.text)?.groupValues?.get(1)

                if (!bridgeUrl.isNullOrBlank() && bridgeUrl.startsWith("http")) {
                    val bridgeHtml = runCatching {
                        app.get(bridgeUrl, headers = baseHeaders("$mainUrl/")).text
                    }.getOrDefault("")

                    val cerceveMatch = Regex(
                        """SPG\.cerceve\s*\(\s*["'][^"']+["']\s*,\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)"""
                    ).find(bridgeHtml)

                    if (cerceveMatch != null) {
                        val cipherB64 = cerceveMatch.groupValues[1]
                        val keyB64 = cerceveMatch.groupValues[2]

                        val fastplayUrl = runCatching {
                            val cipherBytes = java.util.Base64.getDecoder().decode(cipherB64)
                            val keyBytes = java.util.Base64.getDecoder().decode(keyB64)
                            val decryptedBytes = ByteArray(cipherBytes.size) { idx ->
                                (cipherBytes[idx].toInt() xor keyBytes[idx % keyBytes.size].toInt()).toByte()
                            }
                            String(decryptedBytes, Charsets.UTF_8).split("|").firstOrNull()?.trim()
                        }.getOrNull()

                        if (!fastplayUrl.isNullOrBlank() && fastplayUrl.startsWith("http")) {
                            val fpDoc = runCatching {
                                app.get(fastplayUrl, headers = baseHeaders(bridgeUrl)).text
                            }.getOrDefault("")

                            val streamPath = Regex("""stream:\s*["']([^"']+)["']""")
                                .find(fpDoc)?.groupValues?.get(1)

                            if (!streamPath.isNullOrBlank()) {
                                val fpBase = fastplayUrl.substringBefore("/video/")
                                val manifestUrl = if (streamPath.startsWith("http")) {
                                    streamPath
                                } else {
                                    "$fpBase$streamPath"
                                }

                                val sp = Regex(""""sp"\s*:\s*"([^"]*)"""").find(fpDoc)?.groupValues?.get(1) ?: ""
                                val spT = Regex(""""spT"\s*:\s*(\d+)"""").find(fpDoc)?.groupValues?.get(1)?.toLongOrNull()
                                    ?: (System.currentTimeMillis() / 1000)

                                // FNV-1a 32-bit
                                val r = java.lang.Long.toString((Math.random() * 2176782335L).toLong(), 36)
                                val hashInput = "$sp|$spT|$r"
                                var h = 2166136261L
                                for (ch in hashInput) {
                                    h = (h xor ch.code.toLong()) and 0xFFFFFFFFL
                                    h = (h * 16777619L) and 0xFFFFFFFFL
                                }
                                val sig = java.lang.Long.toHexString(h)
                                val xSp = "$spT.$r.$sig"

                                callback(
                                    ExtractorLink(
                                        source = name,
                                        name = "$name FastPlay HD",
                                        url = manifestUrl,
                                        referer = "$fpBase/",
                                        quality = Qualities.P1080.value,
                                        type = ExtractorLinkType.M3U8,
                                        headers = mapOf(
                                            "Referer" to "$fpBase/",
                                            "User-Agent" to CommonHeaders.DEFAULT_USER_AGENT,
                                            "X-Sp" to xSp
                                        )
                                    )
                                )
                                processed = true

                                // Altyazılar
                                val tracksMatch = Regex("""tracks:\s*(\[[^\]]+\])""")
                                    .find(fpDoc)?.groupValues?.get(1)

                                if (!tracksMatch.isNullOrBlank()) {
                                    Regex("""\{"file":"([^"]+)","label":"([^"]+)"""")
                                        .findAll(tracksMatch)
                                        .forEach { tm ->
                                            val subUrl = tm.groupValues[1].replace("\\/", "/")
                                            val subLabel = tm.groupValues[2]
                                            subtitleCallback(
                                                SubtitleFile(lang = subLabel, url = subUrl)
                                            )
                                        }
                                }
                            }
                        }
                    } else {
                        if (loadExtractor(bridgeUrl, "$mainUrl/", subtitleCallback, callback)) {
                            processed = true
                        }
                    }
                }
            }
        }

        // Fallback: iframe
        if (!processed && pageUrl.startsWith("http")) {
            val doc = runCatching {
                app.get(pageUrl, headers = baseHeaders()).document
            }.getOrNull()

            doc?.select("iframe")?.forEach { iframe ->
                val src = fixUrlNull(iframe.attr("src"))
                if (src != null) {
                    if (loadExtractor(src, "$mainUrl/", subtitleCallback, callback)) {
                        processed = true
                    }
                }
            }
        }

        return processed
    }
}