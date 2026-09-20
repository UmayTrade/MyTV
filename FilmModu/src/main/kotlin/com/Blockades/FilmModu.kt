package com.Blockades

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * FilmModu Sağlayıcısı
 * Site: https://www.filmmodu.one
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
                "https://raw.githubusercontent.com/UmayTrade/MyTV/refs/heads/master/domains.json"
            ).text
            org.json.JSONObject(config).optString("filmmodu")
                ?.takeIf { it.isNotBlank() }?.let { mainUrl = it }
        } catch (_: Exception) { }
    }

    override val mainPage = mainPageOf(
        "/"                                      to "Son Eklenen Filmler",
        "/hd-populer-filmler"                    to "Popüler Filmler",
        "/boxset-seri-filmler"                   to "Seri Filmler",
        "/arsiv-filmler"                         to "Film Arşivi",
        "/hd-film-kategori/bilim-kurgu-filmleri" to "Bilim-Kurgu",
        "/hd-film-kategori/aksiyon"              to "Aksiyon",
        "/hd-film-kategori/komedi-filmleri"      to "Komedi",
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
            val actorName = el.selectFirst("span")?.text()?.trim() ?: el.text().trim()
            if (actorName.isNotBlank()) Actor(actorName) else null
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

        vidReq.subtitle?.let { sub ->
            subtitleCallback(
                SubtitleFile(
                    lang = "Türkçe",
                    url = fixUrl(sub)
                )
            )
        }

        vidReq.sources?.forEach { source ->
            val srcUrl = source.src ?: return@forEach
            callback(
                newExtractorLink(
                    source = "$name - $altName",
                    name = "$name - $altName",
                    url = fixUrl(srcUrl),
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


