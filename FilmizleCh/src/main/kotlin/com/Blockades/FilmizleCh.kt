package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject

class FilmizleCh : MainAPI() {
    override var mainUrl = "https://filmizlech.org"
    override var name = "FilmizleCh"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/animeler" to "Animeler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/platform/netflix" to "Netflix",
        "$mainUrl/platform/apple-tv-store" to "Apple Tv Store",
        "$mainUrl/platform/amazon-prime-video" to "Amazon Prime Video",
        "$mainUrl/platform/amazon-video" to "Amazon Video",
        "$mainUrl/platform/disney-plus" to "Disney Plus",
        "$mainUrl/platform/tv" to "Turkcell Tv+",
        "$mainUrl/platform/hbo-max" to "HBO - MAX",
        "$mainUrl/platform/tod-tv" to "TOD Tv",
        "$mainUrl/platform/mubi" to "Mubi",
        "$mainUrl/platform/apple-tv" to "Apple Tv",
        "$mainUrl/platform/hulu" to "Hulu",
        "$mainUrl/platform/discovery" to "Discovery +",
        "$mainUrl/platform/tabii" to "Tabii",
        "$mainUrl/platform/youtube" to "Youtube",
        "$mainUrl/yil/2026?yil=2026&type=film" to "2026 Yapimlari",
        "$mainUrl/yil/2026?yil=2025&type=film" to "2025 Yapimlari",
        "$mainUrl/yil/2026?yil=2024&type=film" to "2024 Yapimlari",
        "$mainUrl/yil/2026?yil=2023&type=film" to "2023 Yapimlari",
        "$mainUrl/yil/2026?yil=2022&type=film" to "2022 Yapimlari",
        "$mainUrl/yil/2026?yil=2021&type=film" to "2021 Yapimlari",
        "$mainUrl/yil/2026?yil=2020&type=film" to "2020 Yapimlari",
        "$mainUrl/yil/2026?yil=2019&type=film" to "2019 Yapimlari"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data}?page=$page"
            }
        } else {
            request.data
        }

        val res = app.get(url, headers = defaultHeaders, cacheTime = 0)
        val items = mutableListOf<SearchResponse>()

        if (res.isSuccessful) {
            val doc = Jsoup.parse(res.text)
            val cards = doc.select("a.content-card")
            for (card in cards) {
                val searchResult = card.toSearchResult()
                if (searchResult != null) {
                    items.add(searchResult)
                }
            }
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href") ?: return null
        val urlVal = if (href.startsWith("http")) href else "$mainUrl$href"

        val isTv = urlVal.contains("/dizi/") || urlVal.contains("/anime/")
        if (!isTv) return null

        val title = selectFirst(".cc-info strong")?.text()?.trim()
            ?: selectFirst(".cc-title")?.text()?.trim()
            ?: selectFirst("h3")?.text()?.trim()
            ?: return null

        // --- POSTER: Çoklu fallback ---
        var posterUrl: String? = null

        // 1. .cc-bg style içindeki background-image
        val styleAttr = selectFirst(".cc-bg")?.attr("style") ?: ""
        posterUrl = Regex("""url\(['"]?([^'")]+)['"]?\)""")
            .find(styleAttr)?.groupValues?.getOrNull(1)

        // 2. img src / data-src
        if (posterUrl.isNullOrBlank()) {
            val img = selectFirst(".cc-bg img")
                ?: selectFirst("img.cc-img")
                ?: selectFirst(".cc-poster img")
                ?: selectFirst("img")
            posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        }

        // 3. data-bg / data-background
        if (posterUrl.isNullOrBlank()) {
            posterUrl = selectFirst(".cc-bg")?.attr("data-bg")?.takeIf { it.isNotBlank() }
                ?: selectFirst(".cc-bg")?.attr("data-background")?.takeIf { it.isNotBlank() }
        }

        // 4. Tüm elementte style ara
        if (posterUrl.isNullOrBlank()) {
            val allStyle = attr("style")
            posterUrl = Regex("""url\(['"]?([^'")]+)['"]?\)""")
                .find(allStyle)?.groupValues?.getOrNull(1)
        }

        // URL düzeltme
        posterUrl = posterUrl?.let {
            when {
                it.startsWith("http") -> it
                it.startsWith("//") -> "https:$it"
                it.startsWith("/") -> "$mainUrl$it"
                else -> "$mainUrl/$it"
            }
        }

        val rating = selectFirst(".cc-rating-inline")?.text()?.replace("★", "")?.trim()?.toDoubleOrNull()

        return newTvSeriesSearchResponse(title, urlVal, TvType.TvSeries) {
            this.posterUrl = posterUrl
            rating?.takeIf { it > 0.0 }?.let {
                this.score = Score.from10(it)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/pages/arama.php?q=$encodedQuery"
        val res = app.get(searchUrl, headers = defaultHeaders, cacheTime = 0)
        val items = mutableListOf<SearchResponse>()

        if (res.isSuccessful) {
            val doc = Jsoup.parse(res.text)
            val cards = doc.select("a.content-card")
            for (card in cards) {
                val searchResult = card.toSearchResult()
                if (searchResult != null) {
                    items.add(searchResult)
                }
            }
        }

        return items.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = defaultHeaders, cacheTime = 0)
        if (!res.isSuccessful) return null

        val doc = Jsoup.parse(res.text)

        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title().trim()

        // --- POSTER: Çoklu fallback ---
        var poster: String? = null

        val posterImg = doc.selectFirst(".detail-poster img")
            ?: doc.selectFirst("picture.poster-auto img")
            ?: doc.selectFirst(".poster img")
            ?: doc.selectFirst("img.poster")

        poster = posterImg?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: posterImg?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: posterImg?.attr("src")?.takeIf { it.isNotBlank() }

        if (poster.isNullOrBlank()) {
            val styleAttr = doc.selectFirst(".detail-poster")?.attr("style") ?: ""
            poster = Regex("""url\(['"]?([^'")]+)['"]?\)""")
                .find(styleAttr)?.groupValues?.getOrNull(1)
        }

        // URL düzeltme
        poster = poster?.let {
            when {
                it.startsWith("http") -> it
                it.startsWith("//") -> "https:$it"
                it.startsWith("/") -> "$mainUrl$it"
                else -> "$mainUrl/$it"
            }
        }

        val plot = doc.selectFirst("p.description")?.text()?.trim()

        val year = doc.select(".meta-badges .badge").firstOrNull {
            it.text().contains("20") || it.text().contains("19")
        }?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        val tags = doc.select("a.badge-cat").map { it.text().trim() }

        val episodes = doc.select(".episode-item").mapNotNull { el ->
            val href = el.attr("href") ?: return@mapNotNull null
            val epUrl = if (href.startsWith("http")) href else "$mainUrl$href"

            val epNumText = el.selectFirst(".ep-num")?.text()
            val parts = epNumText?.split("x")

            val seasonFromUrl = Regex("""sezon-(\d+)""", RegexOption.IGNORE_CASE).find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episodeFromUrl = Regex("""bolum-(\d+)""", RegexOption.IGNORE_CASE).find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val season = parts?.getOrNull(0)?.toIntOrNull() ?: seasonFromUrl
            val episode = parts?.getOrNull(1)?.toIntOrNull() ?: episodeFromUrl

            val epTitle = el.selectFirst("strong")?.text()?.trim() ?: "Bölüm ${episode ?: 1}"

            // Bölüm posteri için de fallback
            val epImg = el.selectFirst(".ep-thumb img")
            var epPoster = epImg?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: epImg?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: epImg?.attr("src")?.takeIf { it.isNotBlank() }
                ?: poster

            epPoster = epPoster?.let {
                when {
                    it.startsWith("http") -> it
                    it.startsWith("//") -> "https:$it"
                    it.startsWith("/") -> "$mainUrl$it"
                    else -> "$mainUrl/$it"
                }
            }

            newEpisode(epUrl) {
                this.name = epTitle
                this.season = season
                this.episode = episode
                this.posterUrl = epPoster
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val res = app.get(data, headers = defaultHeaders, cacheTime = 0)
            if (!res.isSuccessful) return false

            val doc = Jsoup.parse(res.text)
            val button = doc.selectFirst(".player-cover-btn")
            val pid = button?.attr("data-pid")
            val ts = button?.attr("data-ts")
            val sig = button?.attr("data-sig")

            if (pid.isNullOrBlank() || ts.isNullOrBlank() || sig.isNullOrBlank()) {
                return false
            }

            val tokenUrl = "$mainUrl/api/player-token.php?pid=$pid&_t=$ts&_s=${java.net.URLEncoder.encode(sig, "UTF-8")}"
            val tokenRes = app.get(
                tokenUrl,
                headers = mapOf(
                    "Referer" to data,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                ),
                cacheTime = 0
            )

            if (!tokenRes.isSuccessful) return false

            val json = JSONObject(tokenRes.text)
            val embedUrl = json.optString("url")
            if (embedUrl.isNullOrBlank()) return false

            val embedResponse = app.get(
                embedUrl,
                headers = mapOf(
                    "Referer" to data,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                ),
                cacheTime = 0
            )

            if (!embedResponse.isSuccessful) return false

            val embedDoc = Jsoup.parse(embedResponse.text)
            val subIframeSrc = embedDoc.selectFirst("iframe#embed-frame")?.attr("src")
                ?: embedDoc.selectFirst("iframe")?.attr("src")

            if (subIframeSrc.isNullOrBlank()) return false
            val subIframeUrl = if (subIframeSrc.startsWith("//")) {
                "https:$subIframeSrc"
            } else if (subIframeSrc.startsWith("/")) {
                val embedUri = java.net.URI(embedUrl)
                "${embedUri.scheme ?: "https"}://${embedUri.host}$subIframeSrc"
            } else {
                subIframeSrc
            }

            val subEmbedResponse = app.get(
                subIframeUrl,
                headers = mapOf(
                    "Referer" to embedUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                ),
                cacheTime = 0
            )
            if (!subEmbedResponse.isSuccessful) return false
            val subIframeHtml = subEmbedResponse.text

            val fileId = Regex("""cookie\(['"`]file_id['"`],\s*['"`]([^'"`]+)['"`]""").find(subIframeHtml)?.groupValues?.get(1) ?: "30192"
            val aff = Regex("""cookie\(['"`]aff['"`],\s*['"`]([^'"`]+)['"`]""").find(subIframeHtml)?.groupValues?.get(1) ?: "1"
            val refUrl = Regex("""cookie\(['"`]ref_url['"`],\s*['"`]([^'"`]+)['"`]""").find(subIframeHtml)?.groupValues?.get(1) ?: "play.liderfilm.cc"

            val fetchUrlPath = Regex("""fetch\(['"`]([^'"`]+)['"`]\)""").find(subIframeHtml)?.groupValues?.get(1) ?: return false

            val subIframeUri = java.net.URI(subIframeUrl)
            val subIframeHost = subIframeUri.host ?: "x.ag2m4.cfd"
            val subIframeScheme = subIframeUri.scheme ?: "https"

            val apiUrl = "$subIframeScheme://$subIframeHost$fetchUrlPath"

            val apiRes = app.get(
                apiUrl,
                headers = mapOf(
                    "Host" to subIframeHost,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Accept" to "*/*",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to subIframeUrl,
                    "Cookie" to "file_id=$fileId; aff=$aff; ref_url=$refUrl",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin"
                ),
                cacheTime = 0
            )

            if (!apiRes.isSuccessful) return false
            val apiJson = JSONObject(apiRes.text)
            val streamUrl = apiJson.optString("url")
            if (streamUrl.isNullOrBlank()) return false

            callback(
                newExtractorLink(
                    "LiderFilm",
                    "LiderFilm",
                    streamUrl,
                    type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    headers = buildBrowserHeaders(subIframeUrl)
                }
            )

            try {
                val subtitleMatch = Regex(""""subtitle"\s*:\s*"([^"]+)"""").find(subIframeHtml)
                if (subtitleMatch != null) {
                    val subtitleRaw = subtitleMatch.groupValues[1]
                    val subs = subtitleRaw.split(",")
                    for (sub in subs) {
                        val label = sub.substringAfter("[").substringBefore("]")
                        val url = sub.substringAfter("]")
                        if (url.startsWith("http")) {
                            subtitleCallback(
                                newSubtitleFile(
                                    lang = label,
                                    url = url
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}

            true
        } catch (e: Exception) {
            false
        }
    }
}

// getBrowserHeaders projede bulunmadığı için burada tanımlıyoruz
private fun buildBrowserHeaders(referer: String): Map<String, String> {
    return mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to referer
    )
}
