
package com.UmayTrade.filmzal

import java.net.URLEncoder
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

@JsonIgnoreProperties(ignoreUnknown = true)
data class FzSearchItem(
    @JsonProperty("s_type")  val sType: String?   = null,
    @JsonProperty("s_link")  val sLink: String?   = null,
    @JsonProperty("s_name")  val sName: String?   = null,
    @JsonProperty("s_image") val sImage: String?  = null,
    @JsonProperty("s_year")  val sYear: String?   = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FzSearchData(
    val result: List<FzSearchItem>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FzSearchResponse(
    val success: String?    = null,
    val data: FzSearchData?  = null,
)

class Filmzal : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("filmzal", "https://filmzal.me")
    override var name                 = "Filmzal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/film" to "Filmlər",
        mainUrl          to "Diziler",
    )

    private val seasonRegex     = Regex("""^(\d+)-sezon""")
    private val yearSuffixRegex = Regex("""\s*\(\d{4}\)\s*$""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // "Diziler" bölümü ana sayfadaki dar bir widget'tır, sayfalaması yoktur.
        if (request.data == mainUrl) {
            if (page > 1) return newHomePageResponse(request.name, emptyList())

            val document = app.get(mainUrl).document
            val home = document.select("li.segment-poster")
                .filter { it.selectFirst("a[href*='/diziler/']") != null }
                .mapNotNull { it.toSeriesWidgetResult() }

            return newHomePageResponse(request.name, home)
        }

        val url = if (page <= 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document

        val home = document.select("li.segment-poster").mapNotNull { it.toFilmListResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toFilmListResult(): SearchResponse? {
        val a = this.selectFirst("div.poster-subject a") ?: return null
        val href  = fixUrlNull(a.attr("href")) ?: return null
        val title = a.text().trim().ifBlank { null } ?: return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifBlank { img.attr("src") })

        return when {
            href.contains("/film/") -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
            else -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun Element.toSeriesWidgetResult(): SearchResponse? {
        val a = this.selectFirst("a[href*='/diziler/']") ?: return null
        val href  = fixUrlNull(a.attr("href")) ?: return null
        val title = this.selectFirst("h4")?.text()?.trim()?.ifBlank { null } ?: return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifBlank { img.attr("src") })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val response = try {
            app.get("$mainUrl/ajaxservice/index.php?qr=$encodedQuery").text
        } catch (e: Exception) {
            return emptyList()
        }

        val wrapper = try {
            jacksonObjectMapper().readValue<FzSearchResponse>(response)
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val results = wrapper.data?.result ?: return emptyList()

        return results.mapNotNull { item ->
            val url   = item.sLink ?: return@mapNotNull null
            val title = item.sName ?: return@mapNotNull null
            val isMovie = item.sType == "1"
            val poster  = item.sImage?.ifBlank { null }

            if (isMovie) {
                newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isMovie  = url.contains("/film/")

        val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: return null
        val title    = rawTitle.replace(yearSuffixRegex, "").trim().ifBlank { rawTitle }

        val posterEl = document.selectFirst(".ui.image img")
        val poster = if (posterEl != null) {
            fixUrlNull(posterEl.attr("data-src").ifBlank { posterEl.attr("src") })
        } else null

        val infoMap = mutableMapOf<String, String>()
        document.selectFirst("table.ui.unstackable.single.line.celled.table")?.select("td")?.forEach { td ->
            val divs = td.select("div")
            if (divs.size >= 2) infoMap[divs[0].text().trim()] = divs[1].text().trim()
        }
        val year  = infoMap["Yapım Yılı"]?.toIntOrNull()
        val score = infoMap["IMDb Puanı"]?.substringBefore(" ")?.replace(",", ".")
            ?.toDoubleOrNull()?.let { Score.from10(it) }

        val tags = document.select("article.series-summary a[href*='/tur/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }

        val description = document.selectFirst(".series-summary-wrapper p")?.text()?.trim()

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
                this.score     = score
            }
        }

        val episodes = document.select(".ajax_post.el-item[data-epnumber]").mapNotNull { epEl ->
            val epLink = epEl.selectFirst("a") ?: return@mapNotNull null
            val epHref = fixUrlNull(epLink.attr("href")) ?: return@mapNotNull null
            val epNum  = epEl.attr("data-epnumber").toIntOrNull()
            val season = seasonRegex.find(epEl.attr("data-category"))?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epName = epLink.text().trim().ifBlank { null }

            newEpisode(epHref) {
                this.season  = season
                this.episode = epNum
                this.name    = epName
            }
        }.distinctBy { it.data }

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.score     = score
        }
    }

    /** Site içi "{mainUrl}/player/player.html?url=<gerçek adres>" sarmalayıcısını çözer; değilse adresi olduğu gibi döner. */
    private fun resolveSourceUrl(raw: String): String? {
        val trimmed = raw.trim().ifBlank { null } ?: return null
        val fixed   = if (trimmed.startsWith("//")) "https:$trimmed" else fixUrl(trimmed)

        return if (fixed.contains("/player/player.html")) {
            fixed.substringAfter("url=", "").ifBlank { null }
        } else {
            fixed
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false

        val firstDocument = try {
            app.get(data).document
        } catch (e: Exception) {
            return false
        }

        // Dil sekmeleri (Türkçe Dublaj / Türkçe Altyazı / Rusça vb.) — her biri ayrı bir sayfadır.
        val variantUrls = (
            firstDocument.select("div.ui.pointing.secondary.menu a[href]").mapNotNull { fixUrlNull(it.attr("href")) } +
            data
        ).distinct()

        for (variantUrl in variantUrls) {
            val document = try {
                if (variantUrl == data) firstDocument else app.get(variantUrl, referer = data).document
            } catch (e: Exception) {
                continue
            }

            val frames = document.select("a.post-page-numbers[data-frame]").map { it.attr("data-frame") }
            val candidates = frames.ifEmpty {
                listOfNotNull(document.selectFirst("#srcframe")?.attr("src"))
            }

            for (candidate in candidates) {
                val videoUrl = resolveSourceUrl(candidate) ?: continue
                try {
                    loadExtractor(videoUrl, data, subtitleCallback, callback)
                    found = true
                } catch (e: Exception) {
                    // Bu kaynak başarısız oldu, diğerleriyle devam edilir.
                }
            }
        }

        return found
    }
}
