```kotlin
package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TRasyalog : MainAPI() {

    override var mainUrl = "https://asyalog.co"
    override var name = "AsyaLog"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 500L
    override var sequentialMainPageScrollDelay = 500L

    override val mainPage = mainPageOf(
        "$mainUrl/diziler/ulke/guney-kore/" to "Kore Dizileri",
        "$mainUrl/diziler/ulke/cin/" to "Çin Dizileri",
        "$mainUrl/diziler/ulke/tayland/" to "Tayland Dizileri",
        "$mainUrl/diziler/ulke/japonya/" to "Japon Dizileri",
        "$mainUrl/diziler/ulke/endonezya/" to "Endonezya Dizileri",
        "$mainUrl/devam-eden-diziler/" to "Devam Eden Diziler"
    )

    // ---------------------------------------------------------
    // ANA SAYFA
    // ---------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}/page/$page/"
        }

        val document = app.get(url).document

        val results = document
            .select(
                "div.frag-k, " +
                "div.post-container, " +
                ".sag-liste li"
            )
            .mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(
            request.name,
            results
        )
    }

    // ---------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {

        val encodedQuery = query
            .trim()
            .replace(" ", "+")

        val document = app.get(
            "$mainUrl/?s=$encodedQuery"
        ).document

        return document
            .select(
                "div.frag-k, " +
                "div.post-container, " +
                ".sag-liste li"
            )
            .mapNotNull { it.toSearchResponse() }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> = search(query)

    // ---------------------------------------------------------
    // SEARCH RESULT
    // ---------------------------------------------------------

    private fun Element.toSearchResponse(): SearchResponse? {

        val title =
            selectFirst("a.baslik span")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: selectFirst("a.resim")
                    ?.attr("title")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ?: selectFirst(".dizi-isim")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ?: return null

        val href =
            selectFirst("a.resim")
                ?.attr("href")
                ?: selectFirst("a.baslik")
                    ?.attr("href")
                ?: selectFirst("a[href*='/dizi/']")
                    ?.attr("href")
                ?: return null

        val fixedUrl = fixUrlNull(href)
            ?: return null

        val posterUrl =
            selectFirst("a.resim img")
                ?.let { image ->

                    fixUrlNull(
                        image.attr("src")
                            .takeIf { it.isNotBlank() }
                            ?: image.attr("data-src")
                            .takeIf { it.isNotBlank() }
                            ?: image.attr("data-lazy-src")
                            .takeIf { it.isNotBlank() }
                    )
                }

        return newTvSeriesSearchResponse(
            title,
            fixedUrl,
            TvType.TvSeries
        ) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------------------------------------------------
    // LOAD DIZI
    // ---------------------------------------------------------

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val title =
            document.selectFirst(".dizi-bilgi .ssag h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: document.selectFirst("h1")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ?: document.title()
                    .substringBefore("|")
                    .trim()
                    .takeIf { it.isNotEmpty() }
                ?: return null

        val poster =
            document.selectFirst(".dizi-bilgi .afis img")
                ?.let { image ->

                    fixUrlNull(
                        image.attr("src")
                            .takeIf { it.isNotBlank() }
                            ?: image.attr("data-src")
                    )
                }

        val description =
            document.selectFirst(
                ".dizi-bilgi .aciklama, " +
                ".ozet, " +
                ".aciklama"
            )
                ?.text()
                ?.trim()

        val tags =
            document.select(
                ".kategori a, " +
                ".post-tags a, " +
                "span.genre"
            )
                .mapNotNull {
                    it.text()
                        .trim()
                        .takeIf { text -> text.isNotEmpty() }
                }
                .distinct()

        val episodes = mutableListOf<Episode>()

        // =====================================================
        // ASYALOG GERÇEK BÖLÜM YAPISI
        //
        // Örnek:
        //
        // 01-04. Bölüm
        // https://asyalog.co/bolum/...-1-4-bolum/
        //
        // 05-08. Bölüm
        // https://asyalog.co/bolum/...-5-8-bolum/
        //
        // 13-16. Bölüm
        // https://asyalog.co/bolum/...-13-16-bolum/
        //
        // Paket içindeki gerçek bölüm yolları:
        //
        // /13-16-bolum/
        // /13-16-bolum/2/
        // /13-16-bolum/3/
        // /13-16-bolum/4/
        // =====================================================

        val episodeRows = document.select(
            ".dizi-bolumler ul.scroll-liste > li"
        )

        for (row in episodeRows) {

            val link =
                row.selectFirst("a[href*='/bolum/']")
                    ?: continue

            val href =
                fixUrlNull(link.attr("href"))
                    ?: continue

            val rangeText =
                row.selectFirst(".blm")
                    ?.text()
                    ?.trim()
                    ?: link.text().trim()

            if (rangeText.isBlank()) {
                continue
            }

            // -------------------------------------------------
            // 13-16. Bölüm
            // -------------------------------------------------

            val rangeMatch = Regex(
                """(\d+)\s*-\s*(\d+)"""
            ).find(rangeText)

            if (rangeMatch != null) {

                val start =
                    rangeMatch.groupValues[1].toIntOrNull()

                val end =
                    rangeMatch.groupValues[2].toIntOrNull()

                if (start != null && end != null && end >= start) {

                    for (episodeNumber in start..end) {

                        val part =
                            episodeNumber - start + 1

                        val episodeUrl =
                            if (part == 1) {
                                href.trimEnd('/')
                            } else {
                                "${href.trimEnd('/')}/$part/"
                            }

                        episodes.add(
                            newEpisode(episodeUrl) {

                                name =
                                    "$episodeNumber. Bölüm"

                                episode =
                                    episodeNumber

                                season = 1
                            }
                        )
                    }
                }

                continue
            }

            // -------------------------------------------------
            // Tek bölüm varsa
            // -------------------------------------------------

            val singleEpisode =
                Regex("""(\d+)""")
                    .find(rangeText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            episodes.add(
                newEpisode(href) {

                    name =
                        if (singleEpisode != null) {
                            "$singleEpisode. Bölüm"
                        } else {
                            rangeText
                        }

                    episode =
                        singleEpisode

                    season = 1
                }
            )
        }

        // =====================================================
        // YEDEK PARSE
        //
        // Bazı sayfalarda .scroll-liste yapısı değişirse
        // /bolum/ linklerini doğrudan tarıyoruz.
        // =====================================================

        if (episodes.isEmpty()) {

            val links = document.select(
                "a[href*='/bolum/']"
            )

            for (link in links) {

                val href =
                    fixUrlNull(link.attr("href"))
                        ?: continue

                val text =
                    link.selectFirst(".blm")
                        ?.text()
                        ?.trim()
                        ?: link.text().trim()

                if (text.isBlank()) {
                    continue
                }

                val match =
                    Regex(
                        """(\d+)\s*-\s*(\d+)"""
                    ).find(text)

                if (match != null) {

                    val start =
                        match.groupValues[1].toIntOrNull()

                    val end =
                        match.groupValues[2].toIntOrNull()

                    if (start != null && end != null) {

                        for (episodeNumber in start..end) {

                            val part =
                                episodeNumber - start + 1

                            val episodeUrl =
                                if (part == 1) {
                                    href.trimEnd('/')
                                } else {
                                    "${href.trimEnd('/')}/$part/"
                                }

                            episodes.add(
                                newEpisode(episodeUrl) {

                                    name =
                                        "$episodeNumber. Bölüm"

                                    episode =
                                        episodeNumber

                                    season = 1
                                }
                            )
                        }
                    }

                } else {

                    val episodeNumber =
                        Regex("""(\d+)""")
                            .find(text)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()

                    episodes.add(
                        newEpisode(href) {

                            name =
                                if (episodeNumber != null) {
                                    "$episodeNumber. Bölüm"
                                } else {
                                    text
                                }

                            episode =
                                episodeNumber

                            season = 1
                        }
                    )
                }
            }
        }

        // -----------------------------------------------------
        // DUPLICATE TEMİZLE
        // -----------------------------------------------------

        val sortedEpisodes =
            episodes
                .distinctBy { it.data }
                .sortedWith(
                    compareBy<Episode> {
                        it.season ?: 1
                    }.thenBy {
                        it.episode ?: Int.MAX_VALUE
                    }
                )

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            sortedEpisodes
        ) {

            posterUrl = poster
            plot = description
            this.tags = tags
        }
    }

    // ---------------------------------------------------------
    // VIDEO LINKLERİ
    // ---------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episodeUrl =
            data
                .substringBefore("#")
                .trim()

        if (episodeUrl.isBlank()) {
            return false
        }

        // -----------------------------------------------------
        // Bölüm sayfasını aç
        // -----------------------------------------------------

        val document =
            app.get(
                episodeUrl,
                referer = mainUrl
            ).document

        var found = false

        // =====================================================
        // 1. ASYALOG'UN GERÇEK VIDEO ALANI
        //
        // <span id="plyg">
        //     <iframe src="//odnoklassniki.ru/videoembed/...">
        // =====================================================

        val plygIframe =
            document.selectFirst(
                "#plyg iframe"
            )

        if (plygIframe != null) {

            val src =
                plygIframe.attr("src")
                    .trim()

            if (src.isNotBlank()) {

                val videoUrl =
                    normalizeUrl(src)

                loadExtractor(
                    videoUrl,
                    episodeUrl,
                    subtitleCallback,
                    callback
                )

                found = true
            }
        }

        // =====================================================
        // 2. YEDEK: TÜM IFRAME'LER
        // =====================================================

        if (!found) {

            document.select(
                "iframe"
            ).forEach { iframe ->

                val src =
                    iframe.attr("src")
                        .ifBlank {
                            iframe.attr("data-src")
                        }
                        .ifBlank {
                            iframe.attr("data-url")
                        }
                        .trim()

                if (
                    src.isBlank() ||
                    src.startsWith("javascript:", true)
                ) {
                    return@forEach
                }

                val videoUrl =
                    normalizeUrl(src)

                loadExtractor(
                    videoUrl,
                    episodeUrl,
                    subtitleCallback,
                    callback
                )

                found = true
            }
        }

        return found
    }

    // ---------------------------------------------------------
    // URL NORMALIZE
    // ---------------------------------------------------------

    private fun normalizeUrl(
        url: String
    ): String {

        val clean =
            url.trim()

        return when {

            clean.startsWith("//") ->
                "https:$clean"

            clean.startsWith("/") ->
                "$mainUrl$clean"

            else ->
                clean
        }
    }
}
```
