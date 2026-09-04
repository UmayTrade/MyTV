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
        "${mainUrl}/kategori/aile" to "Aile",
        "${mainUrl}/kategori/aksiyon-macera" to "Aksiyon-Macera",
        "${mainUrl}/kategori/animasyon" to "Animasyon",
        "${mainUrl}/kategori/belgesel" to "Belgesel",
        "${mainUrl}/kategori/bilim-kurgu-fantazi" to "Bilim Kurgu",
        "${mainUrl}/kategori/dram" to "Dram",
        "${mainUrl}/kategori/gizem" to "Gizem",
        "${mainUrl}/kategori/komedi" to "Komedi",
        "${mainUrl}/kategori/savas-politik" to "Savaş Politik",
        "${mainUrl}/kategori/suc" to "Suç",
        "${mainUrl}/dil/altyazi" to "Altyazi Film",
        "${mainUrl}/dil/dublaj" to "Dublajli Film",
        "${mainUrl}/dil/yerli" to "Yerli Film",
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
                element.toSearchResponse()?.let { home.add(it) }
            }
        } else {
            document.select("li.w-1\\/2").forEach { element ->
                element.toSearchResponse()?.let { home.add(it) }
            }
            
            if (home.isEmpty()) {
                document.select("article.item, div.poster-item, div.movie-item").forEach { element ->
                    element.toSearchResponseAlt()?.let { home.add(it) }
                }
            }
        }

        return newHomePageResponse(request.name, home, hasNext = sonraki)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("h2")?.text()?.trim() 
            ?: this.selectFirst("h3")?.text()?.trim()
            ?: this.selectFirst("h4")?.text()?.trim()
            ?: return null
            
        val linkElement = this.selectFirst("a")
        val href = fixUrlNull(linkElement?.attr("href")) ?: return null
        
        var posterUrl = this.selectFirst("img")?.attr("src") 
            ?: this.selectFirst("img")?.attr("data-src")
        posterUrl = fixUrlNull(posterUrl)
        
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

    private fun Element.toSearchResponseAlt(): SearchResponse? {
        val linkElement = this.selectFirst("a[href*=/dizi/], a[href*=/film/]") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        
        val title = linkElement.selectFirst("h3, h4, span")?.text()?.trim()
            ?: linkElement.attr("title")?.trim()
            ?: linkElement.text().trim()
            ?: return null
            
        var posterUrl = this.selectFirst("img")?.attr("src") 
            ?: this.selectFirst("img")?.attr("data-src")
        posterUrl = fixUrlNull(posterUrl)
        
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

        document.select("ul li").forEach { listItem ->
            val href = listItem.selectFirst("a")?.attr("href")
            if (href != null && (href.contains("/dizi/") || href.contains("/film/"))) {
                val title = listItem.selectFirst("span")?.text()?.trim() 
                    ?: listItem.selectFirst("a")?.text()?.trim()
                    ?: return@forEach
                    
                var posterUrl = listItem.selectFirst("img")?.attr("src") 
                    ?: listItem.selectFirst("img")?.attr("data-src")
                posterUrl = fixUrlNull(posterUrl)
                val fixedPoster = if (posterUrl?.startsWith("//") == true) "https:$posterUrl" else posterUrl

                val result = if (href.contains("/dizi/")) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = fixedPoster
                    }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = fixedPoster
                    }
                }
                results.add(result)
            }
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
        
        val title = document.selectFirst("div.sheader h1")?.text()?.trim()
            ?: document.selectFirst("h1.page-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
            
        var poster = document.selectFirst("div.poster img")?.attr("src")
            ?: document.selectFirst("div.series-profile-image img")?.attr("src")
        poster = fixUrlNull(poster)
        
        val description = document.selectFirst("div.wp-content h4")?.text()?.trim()
            ?: document.selectFirst("div.series-profile-summary p")?.text()?.trim()
            ?: document.selectFirst("div.entry-content p")?.text()?.trim()
        
        val year = document.selectFirst("div.custom_fields:has(b:contains(Yayınlanma Tarihi)) span.valor")?.text()
            ?.substringAfterLast(" ")?.toIntOrNull()
            ?: document.selectFirst("h1 span")?.text()?.substringAfter("(")?.substringBefore(")")?.toIntOrNull()
        
        val ratingText = document.selectFirst("div.custom_fields:has(b:contains(IMDb)) span.valor strong")?.text()?.trim()
            ?: document.selectFirst("span.color-imdb")?.text()?.trim()
        val score = ratingText?.toDoubleOrNull()?.let { Score.from10(it) }
        
        val duration = document.selectFirst("div.custom_fields:has(b:contains(Süre)) span.valor")?.text()
            ?.replace("dakika", "")?.trim()?.toIntOrNull()
        
        val tags = document.select("span.kategori a").mapNotNull { it.text().trim() }
            .ifEmpty { document.select("div.series-profile-type a").mapNotNull { it.text().trim() } }
        
        val actors = mutableListOf<Actor>()
        document.select("div.person").forEach {
            val name = it.selectFirst("div.name a")?.text()?.trim() ?: return@forEach
            val img = fixUrlNull(it.selectFirst("img")?.attr("src"))
            actors.add(Actor(name, img))
        }
        
        // Trailer varsa ekle
        val trailer = document.selectFirst("div.series-profile-trailer")?.attr("data-yt")
        
        if (url.contains("/dizi/") || document.select("div.pag_episodes").isNotEmpty()) {
            val episodeses = mutableListOf<Episode>()
            
            document.select("div.episode-item, li.episode, div.pag_episodes a").forEach { element ->
                val epName = element.selectFirst("h6.truncate a")?.text()?.trim()
                    ?: element.selectFirst("a")?.text()?.trim()
                    ?: return@forEach
                    
                val epHref = fixUrlNull(element.selectFirst("a")?.attr("href"))
                    ?: fixUrlNull(element.attr("href"))
                    ?: return@forEach
                
                var season = 1
                var episode = episodeses.size + 1
                
                val seasonMatch = Regex("(\\d+)\\.\\s*Sezon").find(epName)
                val episodeMatch = Regex("(\\d+)\\.\\s*Bölüm").find(epName)
                
                if (seasonMatch != null) {
                    season = seasonMatch.groupValues[1].toIntOrNull() ?: 1
                }
                if (episodeMatch != null) {
                    episode = episodeMatch.groupValues[1].toIntOrNull() ?: (episodeses.size + 1)
                }
                
                episodeses.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.season = season
                        this.episode = episode
                    }
                )
            }
            
            if (episodeses.isEmpty()) {
                document.select("div#last_episodes_yabanci a, div#last_episodes_yabanci_sticky a").forEach { element ->
                    val epName = element.selectFirst("span h1")?.text()?.trim()
                        ?: element.text().trim()
                        ?: return@forEach
                        
                    val epHref = fixUrlNull(element.attr("href")) ?: return@forEach
                    
                    var season = 1
                    var episode = episodeses.size + 1
                    
                    val seasonMatch = Regex("(\\d+)\\.\\s*Sezon").find(epName)
                    val episodeMatch = Regex("(\\d+)\\.\\s*Bölüm").find(epName)
                    
                    if (seasonMatch != null) {
                        season = seasonMatch.groupValues[1].toIntOrNull() ?: 1
                    }
                    if (episodeMatch != null) {
                        episode = episodeMatch.groupValues[1].toIntOrNull() ?: (episodeses.size + 1)
                    }
                    
                    episodeses.add(
                        newEpisode(epHref) {
                            this.name = epName
                            this.season = season
                            this.episode = episode
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeses) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
                addActors(actors)
                trailer?.let { addTrailer("https://www.youtube.com/embed/${it}") }
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
                this.duration = duration
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

        // Önce ci_session cookie'sini al
        val aa = app.get(mainUrl)
        val ciSession = aa.cookies["ci_session"].toString()
        android.util.Log.d("dzmg", "ci_session cookie obtained: ${ciSession.take(10)}...")

        // Bölüm sayfasını getir
        val document = app.get(
            data, 
            headers = headers, 
            cookies = mapOf("ci_session" to ciSession)
        ).document

        // Sayfanın HTML'ini logla (debug için)
        android.util.Log.d("dzmg", "Document HTML length: ${document.html().length}")

        // Önce dooplay_player içindeki iframe'i ara
        var iframe = fixUrlNull(
            document.selectFirst("div.dooplay_player iframe")?.attr("src")
        )

        // Eğer bulunamazsa diğer seçicileri dene
        if (iframe == null) {
            iframe = fixUrlNull(
                document.selectFirst("div#tv-spoox2 iframe")?.attr("src")
                ?: document.selectFirst("iframe[src*='epikplayer']")?.attr("src")
                ?: document.selectFirst("iframe[src*='player']")?.attr("src")
                ?: document.selectFirst("div.playerembed iframe")?.attr("src")
                ?: document.selectFirst("iframe[src*='dizimag']")?.attr("src")
            )
        }

        android.util.Log.d("dzmg", "iframe found: $iframe")

        // Eğer iframe bulunamadıysa ve data bir URL ise, direkt loadExtractor dene
        if (iframe == null) {
            android.util.Log.e("dzmg", "iframe src not found, trying loadExtractor with data: $data")
            return loadExtractor(data, "$mainUrl/", subtitleCallback, callback)
        }

        // iframe içeriğini getir
        try {
            val docum = app.get(iframe, headers = headers, referer = "$mainUrl/").document
            android.util.Log.d("dzmg", "iframe content fetched, scanning scripts...")

            // bePlayer script'ini ara
            docum.select("script").forEach { sc ->
                val scriptContent = sc.toString()
                if (scriptContent.contains("bePlayer")) {
                    android.util.Log.d("dzmg", "bePlayer script found")
                    val pattern = Pattern.compile("bePlayer\\('(.*?)', '(.*?)'\\)")
                    val matcher = pattern.matcher(scriptContent.trimIndent())
                    if (matcher.find()) {
                        val key = matcher.group(1)
                        val jsonCipher = matcher.group(2)
                        android.util.Log.d("dzmg", "bePlayer matched - key: ${key?.take(5)}..., cipher: ${jsonCipher?.take(10)}...")

                        try {
                            val cipherData = ObjectMapper().readValue(
                                jsonCipher?.replace("\\/", "/"),
                                Cipher::class.java
                            )

                            val decrypt = key?.let { 
                                CryptoJS.decrypt(it, cipherData.ct, cipherData.iv, cipherData.s) 
                            }
                            android.util.Log.d("dzmg", "Decrypted data: ${decrypt?.take(50)}...")

                            val jsonData = ObjectMapper().readValue(decrypt, JsonData::class.java)
                            android.util.Log.d("dzmg", "JSON parsed, videoLocation: ${jsonData.videoLocation}")

                            // Altyazıları ekle
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

                            // Video linkini callback ile gönder
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

                            return true

                        } catch (e: Exception) {
                            android.util.Log.e("dzmg", "decryption error: ${e.message}")
                            android.util.Log.e("dzmg", "Stack trace: ${e.stackTraceToString()}")
                        }
                    } else {
                        android.util.Log.w("dzmg", "bePlayer pattern not matched")
                    }
                }
            }

            // bePlayer bulunamadıysa, iframe içinde başka player var mı kontrol et
            android.util.Log.d("dzmg", "bePlayer not found, trying loadExtractor with iframe: $iframe")
            return loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)

        } catch (e: Exception) {
            android.util.Log.e("dzmg", "Error fetching iframe content: ${e.message}")
            return loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        }
    }
}
