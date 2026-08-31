// ===== DiziPal.kt =====
package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element

class DiziPal : MainAPI() {

    override var mainUrl = "https://dizipal1539.com"
    override var name = "DiziPal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            return if (response.code == 403 || response.code == 503) {
                cloudflareKiller.intercept(chain)
            } else response
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/diziler?kelime=&durum=&tur=1&type=&siralama=" to "Diziler",
        "$mainUrl/filmler?kelime=&durum=&tur=1&type=&siralama=" to "Filmler",
        "$mainUrl/animeler?kelime=&durum=&tur=1&type=&siralama=" to "Animeler",
        "$mainUrl/platform/netflix" to "Netflix",
        "$mainUrl/platform/exxen" to "Exxen",
        "$mainUrl/platform/prime-video" to "Amazon Prime",
        "$mainUrl/platform/tabii" to "Tabii",
        "$mainUrl/platform/disney" to "Disney+",
        "$mainUrl/platform/gain" to "Gain",
        "$mainUrl/platform/tod" to "TOD",
        "$mainUrl/platform/hbomax" to "HBOMAX"
    )

    // ================= UTIL =================

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            !url.startsWith("http") -> "$mainUrl/$url"
            else -> url
        }
    }

    // ================= MAIN PAGE =================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = if (page == 1) {
            app.get(request.data, interceptor = interceptor).document
        } else {
            // Son sayfadaki son öğenin data-date değerini bulup POST ile devam et
            // Ancak CloudStream sayfalama için basitçe ?page=$page deneyelim
            // Çoğu dizi sitesi ?page=2, ?paged=2 vb. destekler
            app.get("${request.data}&page=$page", interceptor = interceptor).document
        }

        // Python'daki gibi: article.type2 ul li
        val items = doc.select("article.type2 ul li, article ul li, .type2 ul li")
            .mapNotNull { element ->

                val title = element.selectFirst("span.title")?.text()?.trim()
                    ?: element.selectFirst("img")?.attr("alt")?.trim()
                    ?: return@mapNotNull null

                val link = element.selectFirst("a")?.attr("href")
                    ?.let { fixUrl(it) }
                    ?: return@mapNotNull null

                val poster = element.selectFirst("img")?.attr("src")
                    ?.let { fixUrl(it) }

                val isMovie = request.name == "Filmler" 
                    || link.contains("/film") 
                    || link.contains("/movie")

                newTvSeriesSearchResponse(
                    title,
                    link,
                    if (isMovie) TvType.Movie else TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            }

        return newHomePageResponse(request.name, items)
    }

    // ================= SEARCH =================

    override suspend fun search(query: String): List<SearchResponse> {

        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url, interceptor = interceptor).document

        // Arama sonuçları da muhtemelen aynı yapıda
        return doc.select("article.type2 ul li, article ul li, .result-item, .search-result")
            .mapNotNull { element ->

                val title = element.selectFirst("span.title")?.text()?.trim()
                    ?: element.selectFirst("h2, h3, .title")?.text()?.trim()
                    ?: element.selectFirst("img")?.attr("alt")?.trim()
                    ?: return@mapNotNull null

                val link = element.selectFirst("a")?.attr("href")
                    ?.let { fixUrl(it) }
                    ?: return@mapNotNull null

                val poster = element.selectFirst("img")?.attr("src")
                    ?.let { fixUrl(it) }

                val isMovie = link.contains("/film") || link.contains("/movie")

                newTvSeriesSearchResponse(
                    title,
                    link,
                    if (isMovie) TvType.Movie else TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            }
    }

    // ================= LOAD =================

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url, interceptor = interceptor).document

        val title = doc.selectFirst("h1, .entry-title, .title, h1.title")?.text()?.trim() 
            ?: "DiziPal"

        val description = doc.selectFirst(".entry-content p, .plot, .description, .summary")?.text()
        
        val poster = fixUrl(
            doc.selectFirst("img.wp-post-image, .poster img, .thumb img, .cover img")?.attr("src")
        )

        // Bölümleri çek - farklı olası selector'lar
        val episodeElements = doc.select(
            "a[href*='bolum'], a[href*='episode'], .episodes-list a, " +
            ".episode-item a, .bolumler a, .season-list a"
        )

        val episodes = episodeElements.mapIndexed { index, ep ->
            val epUrl = fixUrl(ep.attr("href")) ?: ""
            val epName = ep.text().trim().ifBlank { "Bölüm ${index + 1}" }
            
            newEpisode(epUrl) {
                name = epName
                episode = index + 1
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // ================= LOAD LINKS =================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val visited = mutableSetOf<String>()

        suspend fun extract(url: String) {

            if (visited.contains(url)) return
            visited.add(url)

            val res = app.get(url, referer = mainUrl, interceptor = interceptor)
            val html = res.text
            val doc = res.document

            // m3u8
            Regex("""https?://[^"' ]+\.m3u8[^"' ]*""")
                .findAll(html)
                .forEach {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name M3U8",
                            url = it.value,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = url
                            quality = Qualities.Unknown.value
                        }
                    )
                }

            // mp4
            Regex("""https?://[^"' ]+\.mp4[^"' ]*""")
                .findAll(html)
                .forEach {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name MP4",
                            url = it.value,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            referer = url
                            quality = Qualities.Unknown.value
                        }
                    )
                }

            // iframe recursive
            doc.select("iframe").forEach {
                val src = fixUrl(it.attr("src"))
                if (!src.isNullOrBlank() && !visited.contains(src)) {
                    extract(src)
                }
            }
        }

        extract(data)

        return true
    }
}
