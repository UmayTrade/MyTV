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
        "${mainUrl}/tum-diziler"      to "Tüm Diziler",
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
        // https://dizican.cc/bolum/deep-revenge-11-bolum/ -> https://dizican.cc/dizi/deep-revenge/
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
            ?: fixUrlNull(this.selectFirst("div.img img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isDizi = url.contains("/dizi/")

        if (isDizi) {
            val title = document.selectFirst("div.film h1")?.text()
                ?: document.selectFirst("h1.film")?.text()
                ?: return null
            val poster = fixUrlNull(document.selectFirst("div.poster img")?.attr("data-src"))
                ?: fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
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

                    val episodeNumber = episodeTitle?.let { t ->
                        val regex = """(\d+)\.\s*Bölüm""".toRegex()
                        regex.find(t)?.groupValues?.get(1)?.toIntOrNull()
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
            val title = document.selectFirst("div.film h1")?.text()
                ?: document.selectFirst("h1.film")?.text()
                ?: return null
            val poster = fixUrlNull(document.selectFirst("div.poster img")?.attr("data-src"))
                ?: fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
            val description = document.selectFirst("div.description")?.text()
            val year = document.selectFirst("li.release span a")?.text()?.toIntOrNull()
            val tags = document.select("div.category a").map { it.text() }
            val actors = document.select("div.actors.list a").map { it.text() }

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

        // İframe'leri topla
        val iframeUrls = mutableSetOf<String>()

        document.select("div.video-content iframe, div.autosize-container iframe, iframe").forEach { iframe ->
            listOf("src", "data-src").forEach { attr ->
                val v = iframe.attr(attr).trim()
                if (v.isNotBlank() && !v.startsWith("about:") && !v.startsWith("data:")) {
                    iframeUrls.add(v)
                }
            }
        }

        // Script içindeki OK.ru ve diğer linkleri ara
        val patterns = listOf(
            """https?://ok\.ru/videoembed/\d+""".toRegex(),
            """//ok\.ru/videoembed/\d+""".toRegex(),
            """https?://vk\.com/video_ext\.php\?[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?dailymotion\.com/embed/video/[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?dai\.ly/[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?vidmoly\.(?:to|me)/embed-[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?youtube\.com/embed/[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?youtu\.be/[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?sibnet\.ru/shell\.php\?videoid=\d+""".toRegex(),
            """https?://(?:www\.)?mail\.ru/video/embed/[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?mp4upload\.com/embed-[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?streamtape\.com/[ve]/[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?voe\.sx/[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?dood\.[^/]+/[ve]/[^"'\s<>]+""".toRegex(),
            """https?://(?:www\.)?filemoon\.[^/]+/[ve]/[^"'\s<>]+""".toRegex(),
        )

        document.select("script").forEach { script ->
            val content = script.html()
            patterns.forEach { regex ->
                regex.findAll(content).forEach { m -> iframeUrls.add(m.value) }
            }
        }

        var found = false

        iframeUrls.forEach { raw ->
            val fullUrl = normalizeUrl(raw) ?: return@forEach
            Log.d("DiziCan", "İşleniyor: $fullUrl")

            // OK.ru linki mi kontrol et
            if (fullUrl.contains("ok.ru/videoembed/")) {
                val okFound = extractOkRu(fullUrl, callback)
                if (okFound) found = true
            } else if (fullUrl.contains("ok.ru/video/")) {
                val okFound = extractOkRu(fullUrl, callback)
                if (okFound) found = true
            } else {
                // Diğer linkler için varsayılan extractor
                try {
                    val result = loadExtractor(fullUrl, "$mainUrl/", subtitleCallback, callback)
                    if (result) {
                        Log.d("DiziCan", "BAŞARILI: $fullUrl")
                        found = true
                    } else {
                        Log.d("DiziCan", "BAŞARISIZ: $fullUrl")
                    }
                } catch (e: Exception) {
                    Log.e("DiziCan", "Extractor hatası ($fullUrl): ${e.message}")
                }
            }
        }

        if (!found) {
            Log.w("DiziCan", "Hiçbir extractor link döndürmedi!")
        }

        return found
    }

    /**
     * OK.ru video embed linklerinden doğrudan stream URL'sini çıkarır.
     * Desteklenen formatlar:
     *   - https://ok.ru/videoembed/9685846329869
     *   - https://ok.ru/video/9685846329869
     *   - //ok.ru/videoembed/9685846329869?nochat=1
     */
    private suspend fun extractOkRu(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Video ID'sini çıkar
            val videoId = Regex("""(?:videoembed|video)/(\d+)""")
                .find(embedUrl)?.groupValues?.get(1)

            if (videoId == null) {
                Log.e("DiziCan", "OK.ru video ID bulunamadı: $embedUrl")
                return false
            }

            Log.d("DiziCan", "OK.ru Video ID: $videoId")

            // OK.ru video sayfasını çek
            val okUrl = "https://ok.ru/video/$videoId"
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9,tr;q=0.8",
                "Referer" to "https://ok.ru/"
            )

            val okResponse = app.get(okUrl, headers = headers).text
            Log.d("DiziCan", "OK.ru yanıt uzunluğu: ${okResponse.length}")

            // 1) HLS master playlist URL'sini ara
            var streamUrl: String? = null
            var isHls = false

            val hlsRegex = """"hlsMasterPlaylistUrl":"([^"]+)"""".toRegex()
            hlsRegex.find(okResponse)?.groupValues?.get(1)?.let { hls ->
                streamUrl = hls.replace("\\/", "/")
                isHls = true
                Log.d("DiziCan", "HLS Master bulundu: $streamUrl")
            }

            // 2) HLS yoksa videoUrl (mp4) ara
            if (streamUrl == null) {
                val videoUrlRegex = """"videoUrl":"([^"]+)"""".toRegex()
                videoUrlRegex.find(okResponse)?.groupValues?.get(1)?.let { url ->
                    streamUrl = url.replace("\\/", "/")
                    Log.d("DiziCan", "videoUrl bulundu: $streamUrl")
                }
            }

            // 3) Son olarak videoSrc ara
            if (streamUrl == null) {
                val videoSrcRegex = """"videoSrc":"([^"]+)"""".toRegex()
                videoSrcRegex.find(okResponse)?.groupValues?.get(1)?.let { url ->
                    streamUrl = url.replace("\\/", "/")
                    Log.d("DiziCan", "videoSrc bulundu: $streamUrl")
                }
            }

            // 4) Alternatif: metadata JSON içindeki "url" alanları
            if (streamUrl == null) {
                val altRegex = """"url":"(https?://[^"]+\.mp4[^"]*)"""".toRegex()
                altRegex.find(okResponse)?.groupValues?.get(1)?.let { url ->
                    streamUrl = url.replace("\\/", "/")
                    Log.d("DiziCan", "Alternatif mp4 bulundu: $streamUrl")
                }
            }

            if (streamUrl == null) {
                Log.e("DiziCan", "OK.ru stream URL'si bulunamadı")
                return false
            }

            // Kalite bilgisini çıkarmaya çalış
            val quality = when {
                streamUrl.contains("1080") -> 1080
                streamUrl.contains("720") -> 720
                streamUrl.contains("480") -> 480
                streamUrl.contains("360") -> 360
                else -> 0
            }

            callback.invoke(
                newExtractorLink(
                    source = "OK.ru",
                    name = "OK.ru",
                    url = streamUrl,
                    type = if (isHls || streamUrl.contains(".m3u8")) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                ) {
                    this.referer = "https://ok.ru/"
                    this.quality = quality
                }
            )

            Log.d("DiziCan", "OK.ru stream başarıyla eklendi: $streamUrl")
            return true

        } catch (e: Exception) {
            Log.e("DiziCan", "OK.ru extract hatası: ${e.message}")
            return false
        }
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