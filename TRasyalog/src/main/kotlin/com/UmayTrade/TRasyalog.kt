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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val pageUrl = if (page <= 1) {
            request.data
        } else {
            "${request.data}/page/$page/"
        }

        val document = app.get(pageUrl).document

        val home = document
            .select("div.frag-k")
            .mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(
            request.name,
            home
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {

        val title = selectFirst("a.baslik span")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: selectFirst("a.resim")
                ?.attr("title")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: return null

        val href = selectFirst("a.resim")
            ?.attr("href")
            ?.let { fixUrlNull(it) }
            ?: selectFirst("a.baslik")
                ?.attr("href")
                ?.let { fixUrlNull(it) }
            ?: return null

        val poster = selectFirst("a.resim img")
            ?.let { img ->

                val src = img.attr("src")
                    .takeIf { it.isNotBlank() }
                    ?: img.attr("data-src")
                        .takeIf { it.isNotBlank() }

                src?.let { fixUrlNull(it) }
            }

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            posterUrl = poster
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = query
            .trim()
            .replace(" ", "+")

        val document = app.get(
            "$mainUrl/?s=$encodedQuery"
        ).document

        return document
            .select(
                "div.frag-k, div.post-container, .sag-liste li"
            )
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val title =
            document.selectFirst(".ssag h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: document.selectFirst("h1")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ?: document.title()
                    .split("|")
                    .firstOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ?: return null

        val posterElement = document.selectFirst(
            ".afis img"
        )

        val poster = posterElement?.let { img ->

            val src = img.attr("src")
                .takeIf { it.isNotBlank() }
                ?: img.attr("data-src")
                    .takeIf { it.isNotBlank() }

            src?.let { fixUrlNull(it) }
        }

        val description = document
            .selectFirst(".ozet, .aciklama")
            ?.text()
            ?.trim()

        val tags = document
            .select(
                ".kategori a, .post-tags a, span.genre"
            )
            .mapNotNull {
                it.text()
                    .trim()
                    .takeIf { text -> text.isNotEmpty() }
            }
            .distinct()

        /*
         * ASYALOG BÖLÜM YAPISI
         *
         * Örnek:
         *
         * 01-04. Bölüm
         * 05-08. Bölüm
         * 09-12. Bölüm
         * 13-16. Bölüm
         * 17-20. Bölüm
         *
         * Her paket kendi URL'sine sahip.
         *
         * Örneğin:
         *
         * 13 -> /13-16-bolum/
         * 14 -> /13-16-bolum/2/
         * 15 -> /13-16-bolum/3/
         * 16 -> /13-16-bolum/4/
         */

        val episodes = mutableListOf<Episode>()

        val bundleLinks = document.select(
            ".dizi-bolumler ul.scroll-liste > li a[href*='/bolum/']"
        )

        /*
         * Eğer yukarıdaki selector farklı bir sayfada
         * çalışmazsa alternatif selectorlar.
         */
        val links = if (bundleLinks.isNotEmpty()) {
            bundleLinks
        } else {
            document.select(
                ".dizi-bolumler a[href*='/bolum/']"
            )
        }

        links.forEach { element ->

            val href = element
                .attr("href")
                .trim()

            if (href.isEmpty()) return@forEach

            val fixedHref = fixUrlNull(href)
                ?: return@forEach

            if (!fixedHref.contains("/bolum/")) {
                return@forEach
            }

            if (
                fixedHref.contains(
                    "fragman",
                    ignoreCase = true
                )
            ) {
                return@forEach
            }

            /*
             * Örnek URL:
             *
             * https://asyalog.co/bolum/
             * live-up-to-your-youth-2026-cin-13-16-bolum/
             */

            val path = fixedHref
                .substringBefore("?")
                .substringBefore("#")
                .trimEnd('/')

            val lastPart = path
                .substringAfterLast('/')

            /*
             * 13-16-bolum
             */
            val rangeMatch = Regex(
                """(\d+)-(\d+)-bolum""",
                RegexOption.IGNORE_CASE
            ).find(lastPart)

            if (rangeMatch != null) {

                val start = rangeMatch
                    .groupValues[1]
                    .toIntOrNull()
                    ?: return@forEach

                val end = rangeMatch
                    .groupValues[2]
                    .toIntOrNull()
                    ?: return@forEach

                if (end < start) {
                    return@forEach
                }

                for (episodeNumber in start..end) {

                    /*
                     * İlk bölüm paket URL'sinin kendisi:
                     *
                     * 13:
                     * /13-16-bolum/
                     *
                     * Sonraki bölümler:
                     *
                     * 14:
                     * /13-16-bolum/2/
                     *
                     * 15:
                     * /13-16-bolum/3/
                     *
                     * 16:
                     * /13-16-bolum/4/
                     */

                    val partNumber =
                        episodeNumber - start + 1

                    val episodeUrl =
                        if (partNumber == 1) {
                            fixedHref.trimEnd('/') + "/"
                        } else {
                            fixedHref.trimEnd('/') +
                                "/$partNumber/"
                        }

                    episodes.add(
                        newEpisode(episodeUrl) {

                            name = "$episodeNumber. Bölüm"
                            episode = episodeNumber
                        }
                    )
                }

            } else {

                /*
                 * Tek bölüm URL'si için fallback.
                 */

                val singleMatch = Regex(
                    """(\d+)-bolum""",
                    RegexOption.IGNORE_CASE
                ).find(lastPart)

                val episodeNumber =
                    singleMatch
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: Regex("""\d+""")
                            .find(element.text())
                            ?.value
                            ?.toIntOrNull()

                val episodeName =
                    if (episodeNumber != null) {
                        "$episodeNumber. Bölüm"
                    } else {
                        element.text().trim()
                    }

                episodes.add(
                    newEpisode(fixedHref) {

                        name = episodeName
                        episode = episodeNumber
                    }
                )
            }
        }

        /*
         * Aynı bölümün iki defa eklenmesini engelle.
         */
        val finalEpisodes = episodes
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> {
                    it.episode ?: Int.MAX_VALUE
                }
            )

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            finalEpisodes
        ) {

            posterUrl = poster
            plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageUrl = data
            .substringBefore("#")
            .trim()

        if (pageUrl.isEmpty()) {
            return false
        }

        val document = app.get(pageUrl).document

        /*
         * ASYALOG'daki gerçek player:
         *
         * <span id="plyg">
         *     <iframe
         *       src="//odnoklassniki.ru/videoembed/..."
         *     >
         * </iframe>
         * </span>
         *
         * Öncelikle #plyg içerisindeki iframe
         * alınır.
         */

        val iframeElements = document.select(
            "#plyg iframe"
        ).ifEmpty {
            document.select("iframe")
        }

        if (iframeElements.isEmpty()) {
            return false
        }

        var found = false

        iframeElements.forEach { iframe ->

            var src = iframe
                .attr("src")
                .trim()

            if (src.isEmpty()) {
                src = iframe
                    .attr("data-src")
                    .trim()
            }

            if (src.isEmpty()) {
                src = iframe
                    .attr("data-url")
                    .trim()
            }

            if (src.isEmpty()) {
                return@forEach
            }

            if (
                src.startsWith(
                    "javascript:",
                    ignoreCase = true
                )
            ) {
                return@forEach
            }

            /*
             * Site protocol-relative URL kullanıyor:
             *
             * //odnoklassniki.ru/videoembed/...
             *
             * Bunu:
             *
             * https://odnoklassniki.ru/videoembed/...
             *
             * haline getiriyoruz.
             */

            val fixedUrl =
                when {
                    src.startsWith("//") ->
                        "https:$src"

                    src.startsWith("/") ->
                        fixUrl(src, pageUrl)

                    else ->
                        src
                }

            try {

                loadExtractor(
                    fixedUrl,
                    pageUrl,
                    subtitleCallback,
                    callback
                )

                found = true

            } catch (_: Exception) {
                /*
                 * Bir iframe extractor tarafından
                 * desteklenmiyorsa diğer iframe'lere
                 * devam edilir.
                 */
            }
        }

        return found
    }
}
