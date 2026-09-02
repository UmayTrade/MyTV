package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element

class DiziPal : MainAPI() {

    override var mainUrl = "https://dizipalorijinal15.com/"
    override var name = "DiziPalim"
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
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/animeler" to "Animeler",
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
            else -> url
        }
    }

    private fun Element.getPoster(): String? {
        var poster =
            selectFirst("img")?.attr("data-src")
                ?: selectFirst("img")?.attr("data-lazy-src")
                ?: selectFirst("img")?.attr("src")

        if (poster.isNullOrBlank()) {
            val srcset = selectFirst("img")?.attr("srcset")
            poster = srcset?.split(",")?.lastOrNull()?.trim()?.split(" ")?.firstOrNull()
        }

        return fixUrl(poster)
    }

    // ================= MAIN PAGE =================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val doc = app.get(url, interceptor = interceptor).document

        val items = doc.select("article, div.post-item, div.poster-item, div.video-item")
            .mapNotNull { element ->

                val title = element.selectFirst("h2, h3, .entry-title, .title, img")
                    ?.let { if (it.tagName() == "img") it.attr("alt") else it.text() }
                    ?.trim()
                    ?: return@mapNotNull null

                val link = element.selectFirst("a")?.attr("href")
                    ?.let { fixUrl(it) }
                    ?: return@mapNotNull null

                newTvSeriesSearchResponse(
                    title,
                    link,
                    if (request.name == "Filmler") TvType.Movie else TvType.TvSeries
                ) {
                    posterUrl = element.getPoster()
                }
            }

        return newHomePageResponse(request.name, items)
    }

    // ================= SEARCH =================

    override suspend fun search(query: String): List<SearchResponse> {

        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url, interceptor = interceptor).document

        return doc.select("article, div.post-item, div.poster-item")
            .mapNotNull { element ->

                val title = element.selectFirst("h2, h3, img")
                    ?.let { if (it.tagName() == "img") it.attr("alt") else it.text() }
                    ?.trim()
                    ?: return@mapNotNull null

                val link = element.selectFirst("a")?.attr("href")
                    ?.let { fixUrl(it) }
                    ?: return@mapNotNull null

                val isMovie = link.contains("/film") || link.contains("/movie")

                newTvSeriesSearchResponse(
                    title,
                    link,
                    if (isMovie) TvType.Movie else TvType.TvSeries
                ) {
                    posterUrl = element.getPoster()
                }
            }
    }

    // ================= LOAD =================

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url, interceptor = interceptor).document

        val title = doc.selectFirst("h1, .entry-title")?.text()?.trim() ?: "DiziPal"
        val description = doc.selectFirst(".entry-content p, .plot")?.text()
        val poster = fixUrl(doc.selectFirst("img.wp-post-image, .poster img")?.attr("src"))

        val episodeElements =
            doc.select("a[href*='bolum'], a[href*='episode'], .episodes-list a")

        val episodes = episodeElements.mapIndexed { index, ep ->
            newEpisode(
                fixUrl(ep.attr("href")) ?: ""
            ) {
                name = ep.text().trim()
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
                if (!src.isNullOrBlank()) {
                    extract(src)
                }
            }
        }

        extract(data)

        return true
    }
}
