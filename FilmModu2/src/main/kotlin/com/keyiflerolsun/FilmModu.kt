package com.keyiflerolsun

import com.keyiflerolsun.CommonHeaders
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

typealias FilmModuProvider = FilmModu

class FilmModu : MainAPI() {
    override var mainUrl: String
        get() = "https://www.filmmodu.live"
        set(_) {}
    override var name = "FilmModu2"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/filmler" to "Son Filmler",
        "$mainUrl/tur/aksiyon" to "Aksiyon",
        "$mainUrl/tur/aksiyon-macera" to "Aksiyon & Macera",
        "$mainUrl/tur/animasyon" to "Animasyon",
        "$mainUrl/tur/anime" to "Anime",
        "$mainUrl/tur/belgesel" to "Belgesel",
        "$mainUrl/tur/bilim-kurgu" to "Bilim-Kurgu",
        "$mainUrl/tur/biyografi" to "Biyografi",
        "$mainUrl/tur/dram" to "Dram",
        "$mainUrl/tur/fantastik" to "Fantastik",
        "$mainUrl/tur/gerilim" to "Gerilim",
        "$mainUrl/tur/gizem" to "Gizem",
        "$mainUrl/tur/komedi" to "Komedi",
        "$mainUrl/tur/korku" to "Korku",
        "$mainUrl/tur/macera" to "Macera",
        "$mainUrl/tur/romantik" to "Romantik",
        "$mainUrl/tur/savas" to "Savaş",
        "$mainUrl/tur/suc" to "Suç",
        "$mainUrl/tur/tarih" to "Tarih",
        "$mainUrl/tur/western" to "Vahşi Batı"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(pageUrl, headers = CommonHeaders.defaultHeaders(mainUrl)).document
        val home = document.select("a.group.block, div.movie, a[href*='/film/'], div.film-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(if (this.tagName() == "a") this.attr("href") else this.selectFirst("a")?.attr("href")) ?: return null
        if (!href.contains("/film/") && !href.contains("/dizi/")) return null

        val title = this.selectFirst("img[alt]")?.attr("alt")?.replace("Film izle", "")?.replace("Dizi izle", "")?.replace("izle", "")?.trim()
            ?: this.selectFirst("a")?.attr("title")?.ifBlank { null }
            ?: this.selectFirst("a, h2, h3")?.text()?.ifBlank { null }
            ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")
                ?: this.selectFirst("picture img")?.attr("src")
                ?: this.selectFirst("img")?.attr("data-src")
        )

        val pHeaders = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to CommonHeaders.DEFAULT_USER_AGENT
        )

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title.trim(), href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.posterHeaders = pHeaders
            }
        } else {
            newMovieSearchResponse(title.trim(), href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.posterHeaders = pHeaders
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/ara?q=$encoded", headers = CommonHeaders.defaultHeaders(mainUrl)).document
        return document.select("a.group.block, div.movie, a[href*='/film/'], div.film-item, .movie, .film, article, .movie-item, div.col-movie")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = CommonHeaders.defaultHeaders(mainUrl)).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.replace("izle", "")?.trim()
            ?: return null
        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img")?.attr("src")
        )
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("p")?.text()?.trim()
        val year = Regex("""\((\d{4})\)""").find(document.title())?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(\d{4})""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        val tags = document.select("a[href*='/tur/']").map { it.text().trim() }.distinct()

        val pv = document.selectFirst("[data-pv]")?.attr("data-pv")
        val linkData = if (!pv.isNullOrBlank()) "$pv|$url" else url

        return newMovieLoadResponse(title, url, TvType.Movie, linkData) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
        }
    }

    private data class FilmModuSource(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("label") val label: String? = null
    )

    private data class FilmModuSourceResponse(
        @JsonProperty("sources") val sources: List<FilmModuSource> = emptyList(),
        @JsonProperty("subtitle") val subtitle: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var processed = false

        val pvFromData = if (data.contains("|")) data.substringBefore("|") else null
        val pageUrl = if (data.contains("|")) data.substringAfter("|") else data

        // Strategy 1: data-pv Pilavyer extraction
        var targetPv = pvFromData
        if (targetPv.isNullOrBlank() && pageUrl.startsWith("http")) {
            val doc = try {
                app.get(pageUrl, headers = CommonHeaders.defaultHeaders(mainUrl)).document
            } catch (_: Exception) {
                null
            }
            targetPv = doc?.selectFirst("[data-pv]")?.attr("data-pv")
                ?: doc?.let { Regex("""data-pv="([^"]+)"""").find(it.html())?.groupValues?.get(1) }
        }

        if (!targetPv.isNullOrBlank()) {
            val hosts = listOf("https://pilavyerplay.top", "https://play2.pilavyerplay.top")
            for (host in hosts) {
                val sUrl = "$host/assets/js/s.php?s=$targetPv"
                val sHtml = try {
                    app.get(
                        sUrl,
                        referer = "$mainUrl/",
                        headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to CommonHeaders.systemUserAgent
                        )
                    ).text
                } catch (_: Exception) {
                    ""
                }

                if (sHtml.isNotBlank()) {
                    val streamMatch = Regex(""""stream"\s*:\s*"([^"]+)"""").find(sHtml)?.groupValues?.get(1)
                    if (!streamMatch.isNullOrBlank()) {
                        val cleanedStream = streamMatch.replace("\\u0026", "&").replace("&amp;", "&").replace("\\/", "/")
                        if (cleanedStream.startsWith("http")) {
                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = "$name HD",
                                    url = cleanedStream,
                                    referer = sUrl,
                                    quality = Qualities.P1080.value,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                            processed = true
                            break
                        }
                    // Subtitle extraction
                    val subRegex = Regex("""\{"sid":"[^"]+","lang":"([^"]+)","label":"([^"]+)".*?"src":"([^"]+)"""")
                    subRegex.findAll(sHtml).forEach { mr ->
                        val lang = mr.groupValues[1]
                        val label = mr.groupValues[2]
                        val subSrc = mr.groupValues[3].replace("\\u0026", "&").replace("&amp;", "&").replace("\\/", "/")
                        if (subSrc.startsWith("http")) {
                            subtitleCallback(
                                SubtitleFile(
                                    lang = if (label.isNotBlank()) label else lang,
                                    url = subSrc
                                )
                            )
                        }
                    }

                    if (processed) break
                }
            }
        }
    }

        // Strategy 2: Legacy get-source fallback
        if (!processed && pageUrl.startsWith("http")) {
            val doc = try {
                app.get(pageUrl, headers = CommonHeaders.defaultHeaders(mainUrl)).document
            } catch (_: Exception) {
                null
            }

            val videoId = doc?.let { Regex("""var\s+videoId\s*=\s*['"]?(\d+)['"]?""").find(it.html())?.groupValues?.get(1) }
            val videoType = doc?.let { Regex("""var\s+videoType\s*=\s*['"]?([^'";]*)['"]?""").find(it.html())?.groupValues?.get(1) ?: "0" } ?: "0"

            if (!videoId.isNullOrBlank()) {
                val sourceJsonUrl = "$mainUrl/get-source?movie_id=$videoId&type=$videoType"
                val response = try {
                    app.get(
                        sourceJsonUrl,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to "$mainUrl/",
                            "User-Agent" to CommonHeaders.systemUserAgent
                        )
                    ).parsedSafe<FilmModuSourceResponse>()
                } catch (_: Exception) {
                    null
                }

                if (response != null) {
                    response.sources.forEach { src ->
                        val streamUrl = src.src
                        if (!streamUrl.isNullOrBlank() && streamUrl.startsWith("http")) {
                            val quality = getQualityFromName(src.label)
                            val isHls = streamUrl.contains(".m3u8")
                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = "FilmModu ${src.label ?: "HD"}",
                                    url = streamUrl,
                                    referer = "$mainUrl/",
                                    quality = quality,
                                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                )
                            )
                            processed = true
                        }
                    }

                    if (!response.subtitle.isNullOrBlank()) {
                        subtitleCallback(
                            SubtitleFile(
                                lang = "Turkish",
                                url = fixUrl(response.subtitle)
                            )
                        )
                    }
                }
            }

            // Strategy 3: Direct iframe fallback
            doc?.select("iframe")?.forEach { iframe ->
                val src = fixUrlNull(iframe.attr("src"))
                if (src != null) {
                    loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                    processed = true
                }
            }
        }

        return processed
    }
}
