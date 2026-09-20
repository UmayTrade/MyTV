package com.Blockades

import com.aethelon.network.CommonHeaders
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
    override var name = "FilmModu"
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
}package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * FilmModu Sağlayıcısı
 *
 * Site: https://www.filmmodu.one
 * İçerik: Türkçe Dublaj ve Altyazılı Filmler, 4K filmler, Yerli filmler
 * Oynatıcı: Doğrudan M3U8 akışları ve Türkçe altyazı desteği
 */
class FilmModu : MainAPI() {

    override var mainUrl = "https://www.filmmodu.one"
    override var name = "FilmModu"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private var isInitialized = false

    private suspend fun ensureInit() {
        if (isInitialized) return
        isInitialized = true
        try {
            val config = app.get(
                "https://raw.githubusercontent.com/ulgenzade/ulgencs3/master/domains.json"
            ).text
            org.json.JSONObject(config).optString("filmmodu")
                ?.takeIf { it.isNotBlank() }?.let { mainUrl = it }
        } catch (_: Exception) { }
    }

    override val mainPage = mainPageOf(
        "/"                                     to "Son Eklenen Filmler",
        "/hd-populer-filmler"                   to "Popüler Filmler",
        "/boxset-seri-filmler"                  to "Seri Filmler",
        "/arsiv-filmler"                        to "Film Arşivi",
        "/hd-film-kategori/bilim-kurgu-filmleri" to "Bilim-Kurgu",
        "/hd-film-kategori/aksiyon"              to "Aksiyon",
        "/hd-film-kategori/komedi-filmleri"     to "Komedi",
        "/hd-film-kategori/korku-filmleri"       to "Korku",
        "/hd-film-kategori/animasyon"            to "Animasyon"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureInit()
        val targetUrl = if (request.data.startsWith("http")) request.data else "$mainUrl${request.data}"
        val sep = if (targetUrl.contains("?")) "&" else "?"
        val doc = app.get("$targetUrl${sep}page=$page", headers = commonHeaders).document
        val home = doc.select("div.movie, div.movie-large, div.poster, div.col-md-2, div.hover-box, a[href*='/film/']")
            .mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(HomePageList(request.name, home), hasNext = home.isNotEmpty())
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val a = if (tagName() == "a") this else selectFirst("a") ?: return null
        val title = a.attr("title").takeIf { it.isNotBlank() }
            ?: selectFirst(".movie-title, .title, h3, h2")?.text()?.trim()
            ?: a.text().trim().takeIf { it.isNotBlank() }
            ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            selectFirst("picture img")?.attr("data-src")
                ?: selectFirst("img")?.attr("data-src")
                ?: selectFirst("img")?.attr("src")
        )
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureInit()
        val doc = app.get("$mainUrl/film-ara?term=$query", headers = commonHeaders).document
        return doc.select("div.movie").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        ensureInit()
        val doc = app.get(url, headers = commonHeaders).document

        val orgTitle = doc.selectFirst("div.titles h1")?.text()?.trim() ?: return null
        val altTitle = doc.selectFirst("div.titles h2")?.text()?.trim().orEmpty()
        val title = if (altTitle.isNotEmpty()) "$orgTitle - $altTitle" else orgTitle
        val poster = fixUrlNull(doc.selectFirst("img.img-responsive")?.attr("src"))
        val description = doc.selectFirst("p[itemprop='description']")?.text()?.trim()
        val year = doc.selectFirst("span[itemprop='dateCreated']")?.text()?.trim()?.toIntOrNull()
        val tags = doc.select("div.description a[href*='-kategori/']").map { it.text().trim() }
        val actors = doc.select("div.description a[href*='-oyuncu-']").mapNotNull { el ->
            val name = el.selectFirst("span")?.text()?.trim() ?: el.text().trim()
            if (name.isNotBlank()) Actor(name) else null
        }
        val trailer = doc.selectFirst("div.container iframe")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureInit()
        val doc = app.get(data, headers = commonHeaders).document

        doc.select("div.alternates a").forEach { alternate ->
            val altLink = fixUrlNull(alternate.attr("href")) ?: return@forEach
            val altName = alternate.text().trim()
            if (altName.equals("Fragman", ignoreCase = true)) return@forEach

            val altReq = app.get(altLink, headers = commonHeaders)
            val vidId = Regex("""var videoId = '(.*)'""").find(altReq.text)?.groupValues?.get(1) ?: return@forEach
            val vidType = Regex("""var videoType = '(.*)'""").find(altReq.text)?.groupValues?.get(1) ?: return@forEach

            val vidReq = app.get(
                "$mainUrl/get-source?movie_id=$vidId&type=$vidType",
                headers = commonHeaders
            ).parsedSafe<GetSource>() ?: return@forEach

            if (vidReq.subtitle != null) {
                subtitleCallback(
                    SubtitleFile(
                        lang = "Türkçe",
                        url = fixUrl(vidReq.subtitle)
                    )
                )
            }

            vidReq.sources?.forEach { source ->
                callback(
                    newExtractorLink(
                        source = "$name - $altName",
                        name = "$name - $altName",
                        url = fixUrl(source.src),
                        type = ExtractorLinkType.M3U8
                    ) {
                        headers = mapOf("Referer" to "$mainUrl/")
                        quality = getQualityFromName(source.label)
                    }
                )
            }
        }

        return true
    }
}
