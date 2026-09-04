package com.UmayTrade

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.regex.Pattern

class DiziMag : MainAPI() {
    override var mainUrl = "https://dizimag.eu"
    override var name = "DiziMag"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L

    override val mainPage = mainPageOf(
        "${mainUrl}/kesfet/eyJ0eXBlIjoic2VyaWVzIn0=" to "Yeni Eklenenler",
        "${mainUrl}/dizi/tur/aile" to "Aile",
        "${mainUrl}/dizi/tur/aksiyon-macera" to "Aksiyon-Macera",
        "${mainUrl}/dizi/tur/animasyon" to "Animasyon",
        "${mainUrl}/dizi/tur/belgesel" to "Belgesel",
        "${mainUrl}/dizi/tur/bilim-kurgu-fantazi" to "Bilim Kurgu",
        "${mainUrl}/dizi/tur/dram" to "Dram",
        "${mainUrl}/dizi/tur/gizem" to "Gizem",
        "${mainUrl}/dizi/tur/komedi" to "Komedi",
        "${mainUrl}/dizi/tur/savas-politik" to "Savaş Politik",
        "${mainUrl}/dizi/tur/suc" to "Suç",
        "${mainUrl}/film/tur/aile" to "Aile Film",
        "${mainUrl}/film/tur/animasyon" to "Animasyon Film",
        "${mainUrl}/film/tur/bilim-kurgu" to "Bilim-Kurgu Film",
        "${mainUrl}/film/tur/dram" to "Dram Film",
        "${mainUrl}/film/tur/fantastik" to "Fantastik Film",
        "${mainUrl}/film/tur/gerilim" to "Gerilim Film",
        "${mainUrl}/film/tur/gizem" to "Gizem Film",
        "${mainUrl}/film/tur/komedi" to "Komedi Film",
        "${mainUrl}/film/tur/korku" to "Korku Film",
        "${mainUrl}/film/tur/macera" to "Macera Film",
        "${mainUrl}/film/tur/romantik" to "Romantik Film",
        "${mainUrl}/film/tur/savas" to "Savaş Film",
        "${mainUrl}/film/tur/suc" to "Suç Film",
        "${mainUrl}/film/tur/tarih" to "Tarih Film",
        "${mainUrl}/film/tur/vahsi-bati" to "Vahşi Batı Film",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var sonraki = false
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:139.0) Gecko/20100101 Firefox/139.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )
        
        val mainReq = if (request.name.contains("Yeni Eklenenler")) {
            sonraki = true
            app.get("${request.data}/${page}", headers = headers)
        } else {
            app.get(request.data, headers = headers)
        }
        
        val document = Jsoup.parse(mainReq.body.string())
        
        val home = mutableListOf<SearchResponse>()
        
        if (request.name.contains("Yeni Eklenenler")) {
            document.select("div.filter-result-box").forEach { element ->
                element.dizilerYeni()?.let { home.add(it) }
            }
        } else {
            document.select("li.w-1\\/2, div.poster-item, div.movie-item").forEach { element ->
                element.dizilerKategori()?.let { home.add(it) }
            }
            
            if (home.isEmpty()) {
                document.select("div.portfolio-item, div.grid-item, div.col-6, div.col-md-3").forEach { element ->
                    element.dizilerAlternatif()?.let { home.add(it) }
                }
            }
        }

        return newHomePageResponse(request.name, home, hasNext = sonraki)
    }

    private fun Element.dizilerYeni(): SearchResponse? {
        val title = this.selectFirst("h2")?.text()?.trim() ?: return null
        val linkElement = this.selectFirst("a")
        val href = fixUrlNull(linkElement?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))
        
        val fixedPoster = if (posterUrl?.startsWith("//") == true) "https:$posterUrl" else posterUrl
        
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixedPoster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixedPoster
            }
        }
    }

    private fun Element.dizilerKategori(): SearchResponse? {
        val title = this.selectFirst("h3, h4, h5, span.title, div.title")?.text()?.trim() 
            ?: this.selectFirst("a")?.attr("title")?.trim()
            ?: return null
            
        val linkElement = this.selectFirst("a")
        val href = fixUrlNull(linkElement?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src") 
            ?: this.selectFirst("img")?.attr("data-src")
            ?: this.selectFirst("div.poster img")?.attr("src")
        )
        
        val fixedPoster = if (posterUrl?.startsWith("//") == true) "https:$posterUrl" else posterUrl
        
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixedPoster
            }
        } else if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixedPoster
            }
        } else {
            null
        }
    }

    private fun Element.dizilerAlternatif(): SearchResponse? {
        val linkElement = this.selectFirst("a[href*=/dizi/], a[href*=/film/]") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        val title = linkElement.attr("title")?.trim() 
            ?: linkElement.selectFirst("img")?.attr("alt")?.trim()
            ?: linkElement.text().trim()
            ?: return null
            
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src") 
            ?: this.selectFirst("img")?.attr("data-src")
        )
        
        val fixedPoster = if (posterUrl?.startsWith("//") == true) "https:$posterUrl" else posterUrl
        
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixedPoster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixedPoster
            }
        }
    }

    private fun Element.toPostSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a[href*=/dizi/], a[href*=/film/]") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        val title = linkElement.selectFirst("span, h3, h4")?.text()?.trim() 
            ?: linkElement.attr("title")?.trim()
            ?: linkElement.text().trim()
            ?: return null
            
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src") 
            ?: this.selectFirst("img")?.attr("data-src")
        )
        
        val fixedPoster = if (posterUrl?.startsWith("//") == true) "https:$posterUrl" else posterUrl

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixedPoster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixedPoster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )

        val searchReq = app.post(
            "${mainUrl}/search",
            data = mapOf("query" to query),
            headers = headers,
            referer = "${mainUrl}/"
        ).parsedSafe<SearchResult>()

        if (searchReq?.success != true) {
            throw ErrorLoadingException("Invalid Json response")
        }

        val document = Jsoup.parse(searchReq.theme.toString())
        val results = mutableListOf<SearchResponse>()

        document.select("ul li, div.search-item, div.result-item").forEach { listItem ->
            val result = listItem.toPostSearchResult()
            result?.let { results.add(it) }
        }
        
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:139.0) Gecko/20100101 Firefox/139.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )
        
        val mainReq = app.get(url, headers = headers, referer = mainUrl)
        val document = mainReq.document
        
        val title = document.selectFirst("div.page-title h1")?.selectFirst("a")?.text() 
            ?: document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: return null
            
        val orgtitle = document.selectFirst("div.page-title p")?.text() ?: ""
        val tit = if (orgtitle.isNotEmpty()) "$title - $orgtitle" else title
        
        val poster = fixUrlNull(
            document.selectFirst("div.series-profile-image img")?.attr("src")
            ?: document.selectFirst("div.poster img")?.attr("src")
            ?: document.selectFirst("img.wp-post-image")?.attr("src")
        )
        
        val year = document.selectFirst("h1 span")?.text()?.substringAfter("(")?.substringBefore(")")?.toIntOrNull()
        val rating = document.selectFirst("span.color-imdb")?.text()?.trim()
        val description = document.selectFirst("div.series-profile-summary p")?.text()?.trim()
            ?: document.selectFirst("div.entry-content p")?.text()?.trim()
        
        val tags = document.selectFirst("div.series-profile-type")?.select("a")?.mapNotNull { it.text().trim() }
        val trailer = document.selectFirst("div.series-profile-trailer")?.attr("data-yt")
        
        val actors = mutableListOf<Actor>()
        document.select("div.series-profile-cast li, div.cast-list li").forEach {
            val img = fixUrlNull(it.selectFirst("img")?.attr("src") ?: it.selectFirst("img")?.attr("data-src"))
            val name = it.selectFirst("h5.truncate, h5, span.name")?.text()?.trim() ?: return@forEach
            actors.add(Actor(name, img))
        }

        if (url.contains("/dizi/")) {
            val episodeses = mutableListOf<Episode>()
            var szn = 1
            
            val seasonElements = document.select("div.series-profile-episode-list, div.season-list, div.episode-list")
            if (seasonElements.isEmpty()) {
                document.select("div.episode-item, li.episode").forEach { bolum ->
                    val epName = bolum.selectFirst("h6.truncate a, a.episode-title")?.text() ?: return@forEach
                    val epHref = fixUrlNull(bolum.select("a").attr("href")) ?: return@forEach
                    episodeses.add(
                        newEpisode(epHref) {
                            this.name = epName
                            this.season = 1
                            this.episode = episodeses.size + 1
                        }
                    )
                }
            } else {
                for (sezon in seasonElements) {
                    var blm = 1
                    for (bolum in sezon.select("li, div.episode-item")) {
                        val epName = bolum.selectFirst("h6.truncate a, a.episode-title")?.text() ?: continue
                        val epHref = fixUrlNull(bolum.select("a").attr("href")) ?: continue
                        episodeses.add(
                            newEpisode(epHref) {
                                this.name = epName
                                this.season = szn
                                this.episode = blm++
                            }
                        )
                    }
                    szn++
                }
            }

            return newTvSeriesLoadResponse(tit, url, TvType.TvSeries, episodeses) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                trailer?.let { addTrailer("https://www.youtube.com/embed/${it}") }
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                trailer?.let { addTrailer("https://www.youtube.com/embed/${it}") }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "$mainUrl/"
        )

        android.util.Log.d("dzmg", "loadLinks: Starting with data URL - $data")

        val aa = app.get(mainUrl)
        val ciSession = aa.cookies["ci_session"].toString()
        android.util.Log.d("dzmg", "ci_session cookie obtained")

        val document = app.get(
            data, 
            headers = headers, 
            cookies = mapOf("ci_session" to ciSession)
        ).document

        val iframe = fixUrlNull(
            document.selectFirst("div#tv-spoox2 iframe")?.attr("src")
            ?: document.selectFirst("iframe[src*='epikplayer']")?.attr("src")
            ?: document.selectFirst("iframe[src*='player']")?.attr("src")
        ) ?: run {
            android.util.Log.e("dzmg", "iframe src not found")
            return loadExtractor(data, "$mainUrl/", subtitleCallback, callback)
        }
        
        android.util.Log.d("dzmg", "iframe URL found: $iframe")

        val docum = app.get(iframe, headers = headers, referer = "$mainUrl/").document

        docum.select("script").forEach { sc ->
            if (sc.toString().contains("bePlayer")) {
                android.util.Log.d("dzmg", "bePlayer script found")
                val pattern = Pattern.compile("bePlayer\\('(.*?)', '(.*?)'\\)")
                val matcher = pattern.matcher(sc.toString().trimIndent())
                if (matcher.find()) {
                    val key = matcher.group(1)
                    val jsonCipher = matcher.group(2)

                    try {
                        val cipherData = ObjectMapper().readValue(
                            jsonCipher?.replace("\\/", "/"),
                            Cipher::class.java
                        )

                        val decrypt = key?.let { 
                            CryptoJS.decrypt(it, cipherData.ct, cipherData.iv, cipherData.s) 
                        }

                        val jsonData = ObjectMapper().readValue(decrypt, JsonData::class.java)

                        jsonData.strSubtitles?.forEach { sub ->
                            subtitleCallback.invoke(
                                SubtitleFile(
                                    lang = sub.label ?: "Unknown",
                                    url = "https://epikplayer.xyz${sub.file}"
                                )
                            )
                        }

                        val myHeaders = mapOf(
                            "Accept" to "*/*", 
                            "Referer" to iframe,
                            "User-Agent" to headers["User-Agent"].toString()
                        )

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = jsonData.videoLocation,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = myHeaders
                                quality = Qualities.Unknown.value
                            }
                        )

                    } catch (e: Exception) {
                        android.util.Log.e("dzmg", "decryption error: ${e.message}")
                    }
                }
            }
        }

        loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)

        return true
    }
}
