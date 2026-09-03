// ! Bu araç @Kraptor123 tarafından | @kekikanime için yazılmıştır.

package com.kraptor

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class FilmIzle720 : MainAPI() {
    override var mainUrl = "https://720izle.com"
    override var name = "720izle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Yeni Filmler",
        "${mainUrl}/en-cok-izlenen-filmler-izle/" to "En Çok İzlenenler",
        "${mainUrl}/tur/aile/" to "Aile",
        "${mainUrl}/tur/aksiyon/" to "Aksiyon",
        "${mainUrl}/tur/animasyon/" to "Animasyon",
        "${mainUrl}/tur/belgesel/" to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim Kurgu",
        "${mainUrl}/tur/biyografi/" to "Biyografi",
        "${mainUrl}/tur/dram/" to "Dram",
        "${mainUrl}/tur/fantastik/" to "Fantastik",
        "${mainUrl}/tur/gerilim/" to "Gerilim",
        "${mainUrl}/tur/gizem/" to "Gizem",
        "${mainUrl}/tur/komedi/" to "Komedi",
        "${mainUrl}/tur/korku/" to "Korku",
        "${mainUrl}/tur/macera/" to "Macera",
        "${mainUrl}/tur/muzik/" to "Müzik",
        "${mainUrl}/tur/muzikal/" to "Müzikal",
        "${mainUrl}/tur/romantik/" to "Romantik",
        "${mainUrl}/tur/savas/" to "Savaş",
        "${mainUrl}/tur/spor/" to "Spor",
        "${mainUrl}/tur/suc/" to "Suç",
        "${mainUrl}/tur/tarih/" to "Tarih",
        "${mainUrl}/tur/western/" to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = if (page == 1) {
            app.get(request.data).document
        } else {
            app.get("${request.data}page/$page/").document
        }
        
        // 720izle.com için film kartları
        val home = document.select("div.movie-item, div.movie_box, div.film-item").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("span.title, h2, .film-title, .movie-title")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src") 
                ?: this.selectFirst("img")?.attr("src")
        )
        val puan = this.selectFirst("span.imdb, .rating, .imdb-score")?.text()?.trim()
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.score = Score.from10(puan)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama/${query}").document

        return document.select("div.movie-item, div.movie_box, div.film-item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("span.title, h2, .film-title, .movie-title")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src") 
                ?: this.selectFirst("img")?.attr("src")
        )
        val puan = this.selectFirst("span.imdb, .rating, .imdb-score")?.text()?.trim()
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.score = Score.from10(puan)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // Base64 decode işlemi
    private fun decodeBase64(input: String): String? {
        return try {
            // 720izle.com'da kullanılan base64 decode
            val cleaned = input.replace("\n", "").replace("\r", "")
            val decoded = Base64.decode(cleaned, Base64.DEFAULT)
            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            try {
                // Alternatif decode
                val cleaned = input.replace("\n", "").replace("\r", "")
                val padded = cleaned.padEnd((cleaned.length + 3) and -4, '=')
                val decoded = Base64.decode(padded, Base64.URL_SAFE)
                String(decoded, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w("720izle", "Base64 decode failed: ${e.message}")
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Başlık
        val title = document.selectFirst("h1, .film-title, .movie-title, .title")?.text()?.trim() ?: return null
        
        // Poster
        val poster = fixUrlNull(
            document.selectFirst(".poster img, .movie-poster img, .film-poster img")?.attr("data-src")
                ?: document.selectFirst(".poster img, .movie-poster img, .film-poster img")?.attr("src")
        )
        
        // Açıklama
        val description = document.selectFirst(".description p, .movie-description, .film-description, .desc p")?.text()?.trim()
        
        // Yıl
        val year = document.selectFirst(".year, .movie-year, .film-year, li:contains(Yıl)")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        
        // Türler
        val tags = document.select(".genres a, .movie-genres a, .film-genres a, .tur a").map { it.text() }
        
        // IMDb puanı
        val rating = document.selectFirst(".imdb span, .rating-value, .imdb-score")?.text()?.trim()
        
        // Süre
        val duration = document.selectFirst(".duration, .movie-duration, .film-duration, .sure")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        
        // Oyuncular
        val actors = document.select(".actors a, .cast a, .oyuncular a").map { Actor(it.text()) }
        
        // Dizi mi?
        val isSeries = document.selectFirst(".type, .film-type, .movie-type")?.text()?.contains("Dizi", ignoreCase = true) == true
            || document.selectFirst("li:contains(Bölüm)") != null
        
        // Video linklerini bul
        val pageText = app.get(url).text
        
        // iframe URL'lerini bul
        val iframeUrls = mutableListOf<String>()
        var trailerUrl: String? = null
        
        // 720izle.com için video linklerini bulma - çeşitli yöntemler
        
        // Yöntem 1: pdata içinde base64
        val pdataRegex = Regex("""pdata\['(prt_.*?)'\]\s*=\s*'(.*?)';""")
        val pdataMatches = pdataRegex.findAll(pageText)
        
        pdataMatches.forEach { match ->
            val partName = match.groupValues[1]
            val rawData = match.groupValues[2]
            
            when {
                partName == "prt_fragman0" || partName.contains("trailer") -> {
                    val decoded = decodeBase64(rawData)
                    decoded?.let { html ->
                        Regex("""youtube\.com/embed/([^"\?]+)""").find(html)?.let {
                            trailerUrl = "https://youtu.be/${it.groupValues[1]}"
                            Log.d("720izle", "Trailer found: $trailerUrl")
                        }
                    }
                }
                partName.startsWith("prt_") -> {
                    val decoded = decodeBase64(rawData)
                    decoded?.let { html ->
                        Regex("""src=["']([^"']+)""").find(html)?.let {
                            val videoUrl = it.groupValues[1]
                            if (!iframeUrls.contains(videoUrl)) {
                                iframeUrls.add(videoUrl)
                                Log.d("720izle", "Video URL: $videoUrl")
                            }
                        }
                    }
                }
            }
        }
        
        // Yöntem 2: Doğrudan iframe etiketleri
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !iframeUrls.contains(src)) {
                iframeUrls.add(src)
            }
        }
        
        // Yöntem 3: Video etiketleri
        document.select("video source, video").forEach { video ->
            val src = video.attr("src")
            if (src.isNotBlank() && !iframeUrls.contains(src)) {
                iframeUrls.add(src)
            }
        }
        
        // Yöntem 4: JavaScript içinde video linkleri
        val jsRegex = Regex("""(?:file|video|src|source)\s*[:=]\s*['"]([^'"]+\.(?:m3u8|mp4|mkv|avi))['"]""", RegexOption.IGNORE_CASE)
        jsRegex.findAll(pageText).forEach { match ->
            val url = match.groupValues[1]
            if (url.isNotBlank() && !iframeUrls.contains(url)) {
                iframeUrls.add(url)
            }
        }

        Log.d("720izle", "Found ${iframeUrls.size} video sources")

        return if (isSeries) {
            val episodes = iframeUrls.mapIndexed { index, url ->
                newEpisode(url) {
                    name = "Bölüm ${index + 1}"
                    season = 1
                    episode = index + 1
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.score = Score.from10(rating)
                this.tags = tags
                this.duration = duration
                addActors(actors)
                trailerUrl?.let { addTrailer(it) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrls.firstOrNull() ?: url) {
                posterUrl = poster
                plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
                addActors(actors)
                trailerUrl?.let { addTrailer(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("720izle", "loadLinks: $data")

        try {
            // Doğrudan video linki
            if (data.contains(".m3u8") || data.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Video",
                        url = data,
                        type = if (data.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.DIRECT
                    ) {
                        headers = mapOf(
                            "Referer" to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    }
                )
                return true
            }

            // VidMody player
            if (data.contains("vidmody.com") || data.contains("player.vidmody.com")) {
                val extractor = VidMody720()
                val links = extractor.getUrl(data, mainUrl)
                links.forEach { callback(it) }
                return links.isNotEmpty()
            }

            // Diğer player'lar için generic extractor
            try {
                loadExtractor(data, subtitleCallback, callback)
                return true
            } catch (e: Exception) {
                Log.w("720izle", "Generic extractor failed: ${e.message}")
            }

            // Iframe içinden link çıkarma
            try {
                val doc = app.get(data, referer = mainUrl).document
                val iframe = doc.selectFirst("iframe")
                if (iframe != null) {
                    val iframeSrc = iframe.attr("src")
                    if (iframeSrc.isNotBlank()) {
                        return loadLinks(iframeSrc, isCasting, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                Log.w("720izle", "Iframe extraction failed: ${e.message}")
            }

        } catch (e: Exception) {
            Log.e("720izle", "loadLinks error: ${e.message}")
        }

        return false
    }
}