package com.byayzen

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnimeJara : MainAPI() {

    override var mainUrl = "https://animejara.com"
    override var name = "AnimeJara"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Anime)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36",
        "Referer" to mainUrl
    )

    // ========================
    // HOME (NO TOCADO)
    // ========================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl, headers = headers).document

        val home = doc.select("a.anime-card").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse("Últimos Animes", home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".card-title")?.text() ?: return null
        val href = fixUrl(attr("href"))

        val poster = fixUrlNull(
            selectFirst("img")?.attr("data-src")
                ?: selectFirst("img")?.attr("src")
        )

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // ========================
    // SEARCH (NO TOCADO)
    // ========================
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = headers).document

        return doc.select("a.search-result-item, a.anime-card").mapNotNull { el ->
            val title = el.selectFirst(".search-title, .card-title")?.text()
                ?: return@mapNotNull null

            val href = fixUrl(el.attr("href"))

            val poster = fixUrlNull(
                el.selectFirst("img")?.attr("data-src")
                    ?: el.selectFirst("img")?.attr("src")
            )

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    // ========================
    // LOAD (EPISODIOS FIX)
    // ========================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1")?.text() ?: "Anime"

        val poster = fixUrlNull(
            doc.selectFirst("#mainPosterImg")?.attr("src")
                ?: doc.selectFirst("img")?.attr("src")
        )

        val description = doc.selectFirst(".anime-sinopsis-contenedor")?.text()

        val episodes = mutableListOf<Episode>()

// 🔥 MÉTODO UNIVERSAL (EL QUE SÍ FUNCIONA)
        doc.select("a[href*=/episode/]").forEach { ep ->

            val epUrl = ep.attr("href")

            if (!epUrl.contains("/episode/")) return@forEach

            val number = Regex("""(\d+)x(\d+)""")
                .find(epUrl)
                ?.groupValues
                ?.get(2)
                ?.toIntOrNull()

            episodes.add(
                newEpisode(epUrl) {
                    this.name = ep.text().ifBlank { "Episodio $number" }
                    this.episode = number
                }
            )
        }

// ❗ eliminar duplicados
        val cleanEpisodes = episodes
            .distinctBy { it.data }
            .toMutableList()

// fallback
        if (cleanEpisodes.isEmpty()) {
            val slug = url.substringAfterLast("/").removeSuffix("/")
            val episodeUrl = "$mainUrl/episode/$slug-1x1/"

            cleanEpisodes.add(
                newEpisode(episodeUrl) {
                    this.name = "Episodio 1"
                    this.episode = 1
                }
            )
        }

        // 🔥 2. JS (TEMPORADAS_DATA)
        if (episodes.isEmpty()) {
            val script = doc.selectFirst("script:contains(TEMPORADAS_DATA)")?.data()

            val json = Regex("""TEMPORADAS_DATA\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
                .find(script ?: "")
                ?.groupValues?.get(1)

            if (!json.isNullOrEmpty()) {
                val slug = url.substringAfterLast("/").removeSuffix("/")

                Regex(""""numero_temporada":(\d+).*?"numero_episodio":"(\d+)"""")
                    .findAll(json)
                    .forEach {
                        val season = it.groupValues[1]
                        val epNum = it.groupValues[2]

                        val epUrl = "$mainUrl/episode/$slug-${season}x$epNum/"

                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = "T$season Ep $epNum"
                                this.episode = epNum.toIntOrNull()
                            }
                        )
                    }
            }
        }

        // 🔥 fallback final
        if (episodes.isEmpty()) {
            val slug = url.substringAfterLast("/").removeSuffix("/")
            val episodeUrl = "$mainUrl/episode/$slug-1x1/"

            episodes.add(
                newEpisode(episodeUrl) {
                    this.name = "Episodio 1"
                    this.episode = 1
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, cleanEpisodes.sortedBy { it.episode })
        }
    }

    // ========================
    // LOAD LINKS (REPRODUCTOR FIX)
    // ========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data, headers = headers).document

// 🔥 buscar cualquier iframe
        val iframe = doc.select("iframe").firstOrNull()?.attr("src") ?: return false

        val iframeDoc = app.get(
            iframe,
            headers = headers + mapOf("Referer" to data)
        ).document

        var found = false

        iframeDoc.select("[onclick*=playVideo]").forEach { el ->

            val onclick = el.attr("onclick")

            val videoUrl = Regex("""playVideo\(['"]([^'"]+)['"]\)""")
                .find(onclick)
                ?.groupValues
                ?.get(1)

            if (!videoUrl.isNullOrBlank()) {
                loadExtractor(videoUrl, iframe, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }
}