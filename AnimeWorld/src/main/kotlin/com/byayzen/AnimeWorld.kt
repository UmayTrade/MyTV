package com.byayzen

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class AnimeWorld : MainAPI() {
    override var mainUrl = "https://animeworld.ac"
    override var name = "AnimeWorld"
    override var lang = "it" // Veya "tr", sitenin diline göre değiştirebilirsiniz
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/az-list?page=" to "Tüm Animeler",
        "$mainUrl/trending?page=" to "Trendler"
    )

    override async fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("${request.data}$page").document
        val home = document.select("div.film-list div.item, div.archive-page div.item").mapNotNull {
            it.toMainPageResult()
        }
        
        // Sayfalama kontrolü: Eğer "Next" veya sonraki sayfa butonu yoksa hasNext = false olur
        val hasNext = document.select("a.page-link[rel=next], ul.pagination li.active + li").isNotEmpty()
        
        return newHomePageResponse(
            list = HomePageList(request.name, home),
            hasNext = hasNext
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("a.name, a.title, .film-detail .film-name a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        
        // AVIF/WEBP uzantılı veya protokolü eksik görselleri düzeltme
        var posterUrl = this.selectFirst("img")?.getImageUrl()
        if (posterUrl != null && posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        }

        // Türü dinamik belirleme (Dizi / Film)
        val isMovie = this.select(".badge, .type").text().contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override async fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?keyword=$query").document
        return document.select("div.film-list div.item, div.archive-page div.item").mapNotNull {
            it.toMainPageResult()
        }
    }

    override async fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title, h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.poster img, div.thumb img")?.getImageUrl()
        val description = document.selectFirst("div.desc, div.synopsis, div.storyline")?.text()?.trim()
        val genre = document.select("div.genres a, div.genre a").map { it.text() }
        val year = document.selectFirst("div.year, span.release-date")?.text()?.toIntOrNull()

        // Bölüm/Server Listesi
        val episodes = mutableListOf<Episode>()
        
        val epElements = document.select("ul.episodes-list li a, div.episodes a")
        if (epElements.isNotEmpty()) {
            epElements.forEachIndexed { index, element ->
                val epHref = fixUrl(element.attr("href"))
                val epName = element.text().trim()
                episodes.add(
                    newEpisode(epHref) {
                        this.name = if (epName.isNotEmpty()) epName else "Bölüm ${index + 1}"
                        this.episode = index + 1
                    }
                )
            }
        } else {
            // Eğer doğrudan tek bir film veya video sayfasıysa
            episodes.add(
                newEpisode(url) {
                    this.name = title
                    this.episode = 1
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genre
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override async fun loadLinks(
        data: String,
        isCdn: Boolean,
        handler: PlaylistUtils,
        videoTemplate: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, allowRedirects = false)
        
        // Redirect (Yönlendirme) kontrolü ve Null-Safety koruması
        val locationHeader = response.headers["Location"] ?: response.headers["location"]
        val targetUrl = if (!locationHeader.isNullOrEmpty()) {
            fixUrl(locationHeader)
        } else {
            data
        }

        val document = if (targetUrl != data) app.get(targetUrl).document else response.document

        // Sayfa içindeki oynatıcı (iframe / embed / video) bağlantılarını çekme
        val iframeSrc = document.select("iframe[src], div.player iframe").attr("data-src")
            .ifEmpty { document.select("iframe[src]").attr("src") }

        if (iframeSrc.isNotEmpty()) {
            val fixedIframe = fixUrl(iframeSrc)
            loadExtractor(fixedIframe, data, videoTemplate)
            return true
        }

        return false
    }

    // Görsel URL'sini alma yardımcı fonksiyonu
    private fun Element.getImageUrl(): String? {
        return this.attr("data-src").ifEmpty {
            this.attr("data-lazy-src").ifEmpty {
                this.attr("src")
            }
        }.takeIf { it.isNotEmpty() }
    }
}
