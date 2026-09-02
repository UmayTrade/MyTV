// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.
// ! Güncelleme: 03.09.2026 - Site yapısına uygun seçiciler eklendi.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.Interceptor
import okhttp3.Response

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal2123.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage           = true
    override var sequentialMainPageDelay      = 300L
    override var sequentialMainPageScrollDelay = 300L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }
    private val mapper           by lazy { jacksonObjectMapper() }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()

            val response = chain.proceed(request)
            val body     = response.peekBody(1024 * 1024).string()

            val isCloudflare = body.contains("cf-browser-verification") ||
                               body.contains("challenge-platform") ||
                               body.contains("Just a moment") ||
                               body.contains("Bir dakika") ||
                               body.contains("Checking your browser") ||
                               body.contains("Turnstile") ||
                               response.request.url.toString().contains("challenges.cloudflare")

            if (isCloudflare) {
                Log.d("DZP", "Cloudflare detected! Solving...")
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler/son-bolumler"                          to "Son Bölümler",
        "${mainUrl}/diziler"                                       to "Yeni Diziler",
        "${mainUrl}/filmler"                                       to "Yeni Filmler",
        "${mainUrl}/koleksiyon/netflix"                            to "Netflix",
        "${mainUrl}/koleksiyon/exxen"                              to "Exxen",
        "${mainUrl}/koleksiyon/blutv"                              to "BluTV",
        "${mainUrl}/koleksiyon/disney"                             to "Disney+",
        "${mainUrl}/koleksiyon/amazon-prime"                       to "Amazon Prime",
        "${mainUrl}/koleksiyon/tod-bein"                           to "TOD (beIN)",
        "${mainUrl}/koleksiyon/gain"                               to "Gain",
        "${mainUrl}/tur/mubi"                                      to "Mubi",
        "${mainUrl}/diziler?kelime=&durum=&tur=26&type=&siralama=" to "Anime",
        "${mainUrl}/diziler?kelime=&durum=&tur=5&type=&siralama="  to "Bilimkurgu Dizileri",
        "${mainUrl}/tur/bilimkurgu"                                to "Bilimkurgu Filmleri",
        "${mainUrl}/diziler?kelime=&durum=&tur=11&type=&siralama=" to "Komedi Dizileri",
        "${mainUrl}/tur/komedi"                                    to "Komedi Filmleri",
        "${mainUrl}/diziler?kelime=&durum=&tur=4&type=&siralama="  to "Belgesel Dizileri",
        "${mainUrl}/tur/belgesel"                                  to "Belgesel Filmleri",
        "${mainUrl}/diziler?kelime=&durum=&tur=25&type=&siralama=" to "Erotik Diziler",
        "${mainUrl}/tur/erotik"                                    to "Erotik Filmler",
    )

    private val dateCache = mutableMapOf<String, String>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("DZP", "getMainPage: page=$page, url=${request.data}")

        val home = mutableListOf<SearchResponse>()

        if (page == 1) {
            val document = app.get(request.data, interceptor = interceptor).document
            val html     = document.html()

            Log.d("DZP", "HTML length: ${html.length}")
            Log.d("DZP", "Title: ${document.selectFirst("title")?.text()}")

            // ! GÜNCELLENEN SEÇİCİLER - Site yapısına göre düzenlendi
            when {
                request.data.contains("/son-bolumler") -> {
                    // Son Bölümler için özel seçici
                    home.addAll(document.select("div.episode-item, .episode-item, .son-bolum-item, .last-episode-item").mapNotNull { it.sonBolumler() })
                }
                request.data.contains("/diziler") && !request.data.contains("/son-bolumler") -> {
                    // Dizi listesi için geniş seçici seti
                    home.addAll(document.select("article.type2 ul li, .series-item, .dizi-item, .content-item, .grid-item, .movie-grid-item, article ul li").mapNotNull { it.diziler() })
                }
                request.data.contains("/filmler") -> {
                    // Film listesi için geniş seçici seti
                    home.addAll(document.select("article.type2 ul li, .movie-item, .film-item, .content-item, .grid-item, .movie-grid-item, article ul li").mapNotNull { it.diziler() })
                }
                request.data.contains("/koleksiyon") || request.data.contains("/tur") -> {
                    // Koleksiyon ve tür sayfaları için
                    home.addAll(document.select("article.type2 ul li, .collection-item, .content-item, .grid-item, article ul li").mapNotNull { it.diziler() })
                }
                else -> {
                    // Genel yedek seçici
                    home.addAll(document.select("article.type2 ul li, .episode-item, .series-item, .movie-item, .content-item, .grid-item, article ul li").mapNotNull { it.diziler() })
                }
            }

            // ! Eğer hala boşsa, en genel seçicileri dene
            if (home.isEmpty()) {
                Log.d("DZP", "Trying fallback selectors...")
                home.addAll(document.select("a[href*=/dizi/], a[href*=/film/]").mapNotNull { element ->
                    val href = element.attr("href")
                    if (href.contains("/dizi/") || href.contains("/film/")) {
                        val title = element.attr("title").ifEmpty { element.text() }
                        val poster = element.selectFirst("img")?.attr("src") ?: element.selectFirst("img")?.attr("data-src")
                        if (title.isNotBlank() && href.isNotBlank()) {
                            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                                this.posterUrl = fixUrl(poster)
                            }
                        } else null
                    } else null
                })
            }

            // ! Tarih önbelleğe alma
            val lastDate = document.select("a[data-date]").last()?.attr("data-date")
                ?: document.select("[data-date]").last()?.attr("data-date")
            
            if (lastDate != null) {
                dateCache[request.data] = lastDate
                Log.d("DZP", "Cached date: $lastDate")
            }

            Log.d("DZP", "Primary load: ${home.size} items")
        } else {
            // ! 2. ve sonraki sayfalar için lazy loading
            val lastDate = dateCache[request.data]
            Log.d("DZP", "Lazy loading with date: $lastDate")

            if (lastDate != null) {
                val apiUrl = when {
                    request.data.contains("/filmler") -> "${mainUrl}/api/load-movies"
                    else -> "${mainUrl}/api/load-series"
                }

                val tur = Regex("""tur=([\d]+)""").find(request.data)?.groupValues?.get(1) ?: ""

                try {
                    val response = app.post(
                        apiUrl,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json, text/javascript, */*; q=0.01"
                        ),
                        referer = "${mainUrl}/",
                        data = mapOf(
                            "date"     to lastDate,
                            "tur"      to tur,
                            "durum"    to "",
                            "kelime"   to "",
                            "type"     to "",
                            "siralama" to ""
                        ),
                        interceptor = interceptor
                    )

                    val jsonText = response.text
                    Log.d("DZP", "API response length: ${jsonText.length}")

                    if (jsonText.isNotEmpty() && jsonText != "{}") {
                        val json = mapper.readValue<Map<String, String>>(jsonText)
                        val html = json["html"] ?: ""

                        if (html.isNotEmpty()) {
                            val doc = Jsoup.parse("<article class='type2'><ul>$html</ul></article>")
                            val items = doc.select("li").mapNotNull { it.diziler() }
                            home.addAll(items)

                            val newDate = doc.select("li a").last()?.attr("data-date")
                                ?: doc.select("a[data-date]").last()?.attr("data-date")
                            
                            if (newDate != null) {
                                dateCache[request.data] = newDate
                                Log.d("DZP", "New cached date: $newDate")
                            }
                            
                            Log.d("DZP", "Lazy loaded: ${items.size} items")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DZP", "Error in lazy loading: ${e.message}")
                }
            }
        }

        // ! Eğer hiç içerik yoksa boş liste döndürme, en azından bir mesaj göster
        if (home.isEmpty()) {
            Log.w("DZP", "No content found for ${request.name}")
        }

        Log.d("DZP", "Returning ${home.size} items for ${request.name}")
        return newHomePageResponse(request.name, home)
    }

    // ! GÜNCELLENEN: Son Bölümler için Element fonksiyonu
    private fun Element.sonBolumler(): SearchResponse? {
        // Birden fazla olası yapıyı dene
        val nameEl = this.selectFirst("div.name, .episode-name, .title, h4, h5")
        val name = nameEl?.text()?.trim() ?: return null
        
        val episodeEl = this.selectFirst("div.episode, .episode-number, .bolum, .season-episode")
        val episodeText = episodeEl?.text()?.trim() ?: ""
        
        // Sezon ve bölüm bilgisini çıkar
        val season = Regex("""(\d+)\.\s*Sezon""").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
        val episode = Regex("""(\d+)\.\s*Bölüm""").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
        
        val title = if (season != null && episode != null) {
            "$name ${season}x${episode.toString().padStart(2, '0')}"
        } else {
            name
        }

        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src")) 
            ?: fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        // Dizi ID'sini URL'den çıkar (örn: /dizi/example-dizi-123)
        val seriesUrl = href.substringBefore("/sezon").substringBefore("/bolum")
        
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.episode = episode
            this.season = season
        }
    }

    // ! GÜNCELLENEN: Dizi/Film öğeleri için Element fonksiyonu
    private fun Element.diziler(): SearchResponse? {
        // Birden fazla olası yapıyı dene
        val title = this.selectFirst("span.title, .title, .name, h3, h4, a[title]")?.text()?.trim()
            ?: this.selectFirst("a")?.attr("title")?.trim()
            ?: return null

        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        
        // Sadece dizi/film linklerini al
        if (!href.contains("/dizi/") && !href.contains("/film/") && !href.contains("/bolum/")) {
            return null
        }

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("data-original"))

        // Türü belirle
        val isSeries = href.contains("/dizi/") || href.contains("/bolum/")
        
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { 
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { 
                this.posterUrl = posterUrl
            }
        }
    }

    // ! SEARCH ve diğer fonksiyonlar aynen kalıyor...
    private fun SearchItem.toPostSearchResult(): SearchResponse {
        val title     = this.title
        val href      = "${mainUrl}${this.url}"
        val posterUrl = this.poster
        val isSeries  = this.type.lowercase().contains("series") || this.type.lowercase() == "dizi"

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val responseRaw = app.post(
            "${mainUrl}/api/search-autocomplete",
            headers     = mapOf(
                "Accept"           to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer     = "${mainUrl}/",
            data        = mapOf("query" to query),
            interceptor = interceptor
        )

        val searchItemsMap = mapper.readValue<Map<String, SearchItem>>(responseRaw.text)

        val searchResponses = mutableListOf<SearchResponse>()

        for ((_, searchItem) in searchItemsMap) {
            searchResponses.add(searchItem.toPostSearchResult())
        }

        return searchResponses
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val year        = document.selectXpath("//div[text()='Yapım Yılı']//following-sibling::div").text().trim().toIntOrNull()
        val description = document.selectFirst("div.summary p, .description, .plot, .summary")?.text()?.trim()
        val tags        = document.selectXpath("//div[text()='Türler']//following-sibling::div").text().trim().split(" ").map { it.trim() }.filter { it.isNotEmpty() }
        val rating      = document.selectXpath("//div[text()='IMDB Puanı']//following-sibling::div").text().trim()
        val duration    = Regex("(\\d+)").find(document.selectXpath("//div[text()='Ortalama Süre']//following-sibling::div").text())?.value?.toIntOrNull()

        val scoreValue = rating.replace(",", ".").toDoubleOrNull()?.let { Score.from10(it) }

        if (url.contains("/dizi/")) {
            val title       = document.selectFirst("div.cover h5, h1.title, .dizi-title, .series-title")?.text() ?: return null

            val episodes    = document.select("div.episode-item, .episode-list-item, .bolum-item").mapNotNull {
                val epName    = it.selectFirst("div.name, .episode-name, .title")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                
                val episodeText = it.selectFirst("div.episode, .episode-number, .season-episode")?.text()?.trim() ?: ""
                val epEpisode = Regex("""(\d+)\.\s*Bölüm""").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = Regex("""(\d+)\.\s*Sezon""").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = epName
                    this.episode = epEpisode
                    this.season  = epSeason
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.score     = scoreValue
                this.duration  = duration
            }
        } else {
            val title = document.selectXpath("//div[@class='g-title'][2]/div").text().trim()
                .ifEmpty { document.selectFirst("h1.title, .film-title, .movie-title")?.text()?.trim() ?: return null }

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.score     = scoreValue
                this.duration  = duration
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZP", "loadLinks: $data")
        
        try {
            val document = app.get(data, interceptor = interceptor).document
            val iframe = document.selectFirst(".series-player-container iframe, #vast_new iframe, .video-player iframe, iframe[src*='dizipal']")?.attr("src")
            
            if (iframe.isNullOrEmpty()) {
                Log.d("DZP", "No iframe found")
                return false
            }
            
            Log.d("DZP", "iframe: $iframe")

            val iSource = app.get(iframe, referer = mainUrl).text
            val m3uLink = Regex("""file:"([^"]+)""").find(iSource)?.groupValues?.get(1)
            
            if (m3uLink == null) {
                Log.d("DZP", "No m3u8 link found, trying extractor")
                return loadExtractor(iframe, mainUrl, subtitleCallback, callback)
            }

            // Altyazıları kontrol et
            val subtitles = Regex(""""subtitle":"([^"]+)"""").find(iSource)?.groupValues?.get(1)
            subtitles?.let {
                if (it.contains(",")) {
                    it.split(",").forEach { sub ->
                        val subLang = sub.substringAfter("[").substringBefore("]")
                        val subUrl = sub.replace("[${subLang}]", "")
                        subtitleCallback.invoke(SubtitleFile(lang = subLang, url = fixUrl(subUrl)))
                    }
                } else {
                    val subLang = it.substringAfter("[").substringBefore("]")
                    val subUrl = it.replace("[${subLang}]", "")
                    subtitleCallback.invoke(SubtitleFile(lang = subLang, url = fixUrl(subUrl)))
                }
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3uLink,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )

            return true
        } catch (e: Exception) {
            Log.e("DZP", "Error in loadLinks: ${e.message}")
            return false
        }
    }
}
