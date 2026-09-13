// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class FilmMakinesi : MainAPI() {
    override var mainUrl              = "https://filmmakinesi.to"
    override var name                 = "FilmMakinesi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    override val mainPage = mainPageOf(
        "${mainUrl}/"                                          to "Son Filmler",
        "${mainUrl}/kanal/netflix-fm1/"                        to "Netflix",
        "${mainUrl}/kanal/disney-fm2/"                         to "Disney",
        "${mainUrl}/kanal/amazon/"                             to "Amazon",
        "${mainUrl}/film-izle/olmeden-izlenmesi-gerekenler-fm1/" to "Ölmeden İzle",
        "${mainUrl}/tur/aksiyon-fmy54y/film/"                  to "Aksiyon",
        "${mainUrl}/tur/bilim-kurgu-fm3/film/"                 to "Bilim Kurgu",
        "${mainUrl}/tur/macera-fm1/film/"                      to "Macera",
        "${mainUrl}/tur/komedi-fm1/film/"                      to "Komedi",
        "${mainUrl}/tur/romantik-fm1/film/"                    to "Romantik",
        "${mainUrl}/tur/belgesel/film/"                        to "Belgesel",
        "${mainUrl}/tur/fantastik-fm1/film/"                   to "Fantastik",
        "${mainUrl}/tur/polisiye/film/"                        to "Polisiye Suç",
        "${mainUrl}/tur/korku-fm2/film/"                       to "Korku"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base     = request.data.trimEnd('/')
        val url      = if (page == 1) base else "$base/sayfa/$page/"
        val document = app.get(url).document
        val home     = document.select("div.item-relative").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.title")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("div.item-relative a.item")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.thumbnail-outer img.thumbnail")?.attr("src"))
            ?: fixUrlNull(this.selectFirst("img.thumbnail")?.attr("src"))
        val puan      = this.selectFirst("div.rating")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.score     = Score.from10(puan)
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title     = this.select("div.title").last()?.text() ?: return null
        val href      = fixUrlNull(this.select("div.item-relative a.item").last()?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.thumbnail-outer img.thumbnail")?.attr("src"))
        val puan      = this.selectFirst("div.rating")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.score     = Score.from10(puan)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama/?s=${query}").document
        Log.d("kraptor_$name", "arama = $document")
        return document.select("div.item-relative").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document    = app.get(url).document
        val title       = document.selectFirst("div.content h1.title")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.select("div.info-description p").last()?.text()?.trim()
        val tags        = document.select("div.type a").map { it.text() }
        val imdbScore   = document.selectFirst("div.info b")?.text()?.trim()
        val year        = document.selectFirst("span.date a")?.text()?.trim()?.toIntOrNull()

        val durationText = document.selectFirst("div.time")?.text()?.trim() ?: ""
        val duration = if (durationText.startsWith("Süre:")) {
            val durationValue = durationText.removePrefix("Süre:").trim().split(" ")[0]
            durationValue.toIntOrNull() ?: 0
        } else 0

        val recommendations = document.select("div.item-relative").mapNotNull { it.toRecommendResult() }
        val actors = document.select("div.content a.cast").map { Actor(it.text().trim()) }
        val trailer = fixUrlNull(document.selectXpath("//iframe[@title='Fragman']").attr("data-src"))

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.score           = Score.from10(imdbScore)
            this.duration        = duration
            this.recommendations = recommendations
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
        Log.d("kraptor_$name", "data » $data")
        val document = app.get(data).document

        val iframe = document.selectFirst("div.after-player iframe")?.attr("data-src")
            ?: document.selectFirst("div.video-parts a[data-video_url]")?.attr("data-video_url")
            ?: document.selectFirst("iframe[data-src]")?.attr("data-src")
            ?: ""

        Log.d("kraptor_$name", "iframe » $iframe")

        if (iframe.isBlank()) {
            Log.e("kraptor_$name", "Iframe bulunamadı!")
            return false
        }

        loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        return true
    }
}
