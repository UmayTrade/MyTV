package com.UmayTrade

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class Dizican : MainAPI() {
    override var mainUrl              = "https://dizican.cc"
    override var name                 = "Dizican"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}"      to "Yeni Bölümler",
        "${mainUrl}/dizi-arsivi"      to "Tüm Diziler",
        "${mainUrl}/film-arsivi" to "Tüm Filmler",
        "${mainUrl}/dizi-kategori/guney-kore-dizileri-izle"   to "Kore Dizileri",
        "${mainUrl}/dizi-kategori/cin-dizileri-izle"  to "Çin Dizileri",
        "${mainUrl}/dizi-kategori/tayland-dizileri-izle"  to "Tayland Dizileri",
        "${mainUrl}/dizi-kategori/japon-dizileri-izle"  to "Japon Dizisi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/page/$page").document

        val home = if (request.name == "Yeni Bölümler") {
            document.select("div.ep-box").mapNotNull { it.toEpisodeMainPageResult() }
        } else {
            document.select("div.movie-box").mapNotNull { it.toMainPageResult() }
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toEpisodeMainPageResult(): SearchResponse? {
        val episodeLink = this.selectFirst("a")?.attr("href") ?: return null
        val seriesUrl = convertEpisodeUrlToSeriesUrl(episodeLink)
        val seriesTitle = this.selectFirst("span.serietitle")?.text() ?: return null
        val episodeInfo = this.selectFirst("span.episodetitle")?.text() ?: ""

        val title = "$seriesTitle - $episodeInfo"
        val posterUrl = fixUrlNull(this.selectFirst("div.img img")?.attr("data-src"))

        return newMovieSearchResponse(title, seriesUrl, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    private fun convertEpisodeUrlToSeriesUrl(episodeUrl: String): String {
        val regex = """/bolum/(.+?)-(?:\d+-sezon-)?(?:\d+)-bolum/?""".toRegex()
        val match = regex.find(episodeUrl)

        return if (match != null) {
            val seriesSlug = match.groupValues[1]
            "$mainUrl/dizi/$seriesSlug/"
        } else {
            episodeUrl
        }
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("div.name a")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.img img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document
        return document.select("div.movie-box").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.name a")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.img img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isDizi = url.contains("/dizi/")

        if (isDizi) {
            val title = document.selectFirst("h1.film")?.text() ?: return null
            val poster = fixUrlNull(document.selectFirst("div.poster img")?.attr("data-src"))
            val description = document.selectFirst("div.description")?.text()
            val year = document.selectFirst("li.release span a")?.text()?.toIntOrNull()
            val tags = document.select("div.category a").map { it.text() }
            val status = if (document.selectFirst("span.final") != null) {
                ShowStatus.Completed
            } else {
                ShowStatus.Ongoing
            }

            val episodes = mutableListOf<Episode>()

            document.select("div.s-wrap").forEach { seasonDiv ->
                val seasonId = seasonDiv.attr("id")
                val seasonNumber = seasonId.replace("s-", "").toIntOrNull() ?: 1

                seasonDiv.select("div.ep-box").forEach { episodeDiv ->
                    val episodeUrl = episodeDiv.selectFirst("a")?.attr("href")
                    val episodeTitle = episodeDiv.selectFirst("div.name a")?.attr("title")
                    val episodePoster = fixUrlNull(episodeDiv.selectFirst("div.img img")?.attr("data-src"))
                    val episodeDate = episodeDiv.selectFirst("div.date span")?.text()

                    val episodeNumber = episodeTitle?.let { title ->
                        val regex = """(\d+)\.\s*Bölüm""".toRegex()
                        regex.find(title)?.groupValues?.get(1)?.toIntOrNull()
                    } ?: 1

                    if (episodeUrl != null && episodeTitle != null) {
                        episodes.add(
                            newEpisode(
                                url = episodeUrl,
                                {
                                    name = episodeTitle
                                    season = seasonNumber
                                    episode = episodeNumber
                                    posterUrl = episodePoster
                                    this.description = episodeDate
                                }
                            )
                        )
                    }
                }
            }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.AsianDrama,
                episodes
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.showStatus = status
            }
        } else {
            val title = document.selectFirst("h1.film")?.text() ?: return null
            val poster = fixUrlNull(document.selectFirst("div.poster img")?.attr("data-src"))
            val description = document.selectFirst("div.description")?.text()
            val year = document.selectFirst("li.release span a")?.text()?.toIntOrNull()
            val tags = document.select("div.category a").map { it.text() }
            val actors = document.select("div.actors a").map { it.text() }

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags

                if (actors.isNotEmpty()) {
                    addActors(actors)
                }
            }
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title     = this.selectFirst("a img")?.attr("alt") ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("a img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DiziCan", "loadLinks data » $data")
        val document = app.get(data).document

        // 1) TÜM iframe'leri topla (src + data-src + data-litespeed-src)
        val iframeUrls = mutableSetOf<String>()

        document.select("iframe").forEach { iframe ->
            listOf("src", "data-src", "data-litespeed-src").forEach { attr ->
                val v = iframe.attr(attr)
                if (v.isNotBlank() && !v.startsWith("about:")) {
                    iframeUrls.add(v)
                }
            }
        }

        // 2) Alternatif video konteynerları (bazı temalarda player div içinde olur)
        document.select("div.video-content, div.player, div#player, div.video-box").forEach { container ->
            container.select("iframe").forEach { iframe ->
                listOf("src", "data-src", "data-litespeed-src").forEach { attr ->
                    val v = iframe.attr(attr)
                    if (v.isNotBlank() && !v.startsWith("about:")) iframeUrls.add(v)
                }
            }
        }

        // 3) Script içindeki gizli linkleri topla
        val scriptLinks = mutableSetOf<String>()
        val patterns = listOf(
            """https?://ok\.ru/videoembed/\d+""".toRegex(),
            """https?://(?:www\.)?ok\.ru/video/\d+""".toRegex(),
            """https?://vk\.com/video_ext\.php\?[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?dailymotion\.com/embed/video/[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?dai\.ly/[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?vidmoly\.(?:to|me)/embed-[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?youtube\.com/embed/[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?youtu\.be/[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?sibnet\.ru/shell\.php\?videoid=\d+""".toRegex(),
            """https?://(?:www\.)?mail\.ru/video/embed/[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?mp4upload\.com/embed-[^"'\s<>]+""".toRegex(),
        )

        document.select("script").forEach { script ->
            val content = script.html()
            patterns.forEach { regex ->
                regex.findAll(content).forEach { m -> scriptLinks.add(m.value) }
            }
        }

        // 4) Tüm linkleri normalize et ve extractor'a gönder
        val allLinks = (iframeUrls + scriptLinks).distinct()
        Log.d("DiziCan", "Toplam bulunan link sayısı: ${allLinks.size}")

        var found = false
        allLinks.forEach { raw ->
            val full = normalizeUrl(raw) ?: return@forEach
            Log.d("DiziCan", "Extractor deneniyor: $full")

            try {
                val result = loadExtractor(full, "$mainUrl/", subtitleCallback, callback)
                if (result) found = true
            } catch (e: Exception) {
                Log.e("DiziCan", "Extractor hatası ($full): ${e.message}")
            }
        }

        if (!found) {
            Log.w("DiziCan", "Hiçbir extractor link döndürmedi. Sayfa yapısını kontrol edin.")
        }

        return found
    }

    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim().trim('\'', '"')
        return when {
            trimmed.isBlank() -> null
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$mainUrl$trimmed"
            trimmed.startsWith("http") -> trimmed
            else -> null
        }
    }
}
