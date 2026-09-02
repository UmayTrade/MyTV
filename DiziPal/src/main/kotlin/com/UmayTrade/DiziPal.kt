// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.
// ! Güncelleme: 03.09.2026 - bandai-azuma.com yeni tema uyumlu
// ! data-cfg Base64 decode ile video linkleri çalışıyor

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
    override var mainUrl              = "https://bandai-azuma.com"
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

    // ! Yeni site yapısına göre ana sayfa kategorileri
    override val mainPage = mainPageOf(
        "${mainUrl}/diziler"           to "Yeni Diziler",
        "${mainUrl}/filmler"           to "Yeni Filmler",
        "${mainUrl}/bolumler"          to "Son Bölümler",
        "${mainUrl}/platform/netflix"  to "Netflix",
        "${mainUrl}/platform/exxen"    to "Exxen",
        "${mainUrl}/platform/blutv"    to "BluTV",
        "${mainUrl}/platform/disney-plus" to "Disney+",
        "${mainUrl}/platform/prime-video" to "Amazon Prime",
        "${mainUrl}/platform/tabii"    to "tabii",
        "${mainUrl}/platform/gain"     to "Gain",
        "${mainUrl}/kategori/anime"    to "Anime",
        "${mainUrl}/kategori/bilim-kurgu" to "Bilimkurgu",
        "${mainUrl}/kategori/komedi"   to "Komedi",
        "${mainUrl}/kategori/belgesel" to "Belgesel",
        "${mainUrl}/kategori/aksiyon"  to "Aksiyon",
        "${mainUrl}/kategori/gerilim"  to "Gerilim",
        "${mainUrl}/kategori/korku"    to "Korku",
        "${mainUrl}/kategori/dram"     to "Dram"
    )

    private val dateCache = mutableMapOf<String, String>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("DZP", "getMainPage: page=$page, url=${request.data}")

        val home = mutableListOf<SearchResponse>()

        if (page == 1) {
            val document = app.get(request.data, interceptor = interceptor).document
            val html     = document.html()

            Log.d("DZP", "HTML length: ${html.length}")

            // ! Yeni site yapısına göre seçiciler
            when {
                request.data.contains("/bolumler") -> {
                    // Son Bölümler
                    home.addAll(document.select("a.episode-list-item").mapNotNull { it.sonBolumlerYeni() })
                }
                request.data.contains("/diziler") -> {
                    // Diziler - content-grid large içindeki content-card
                    home.addAll(document.select("ul.content-grid.large li.content-card").mapNotNull { it.diziKarti() })
                }
                request.data.contains("/filmler") -> {
                    // Filmler - content-grid içindeki content-card
                    home.addAll(document.select("ul.content-grid li.content-card").mapNotNull { it.diziKarti() })
                }
                else -> {
                    // Platform ve kategori sayfaları
                    home.addAll(document.select("ul.content-grid li.content-card, a.episode-list-item").mapNotNull { 
                        it.diziKarti() ?: it.sonBolumlerYeni()
                    })
                }
            }

            // ! Yedek seçici
            if (home.isEmpty()) {
                Log.d("DZP", "Trying fallback selectors...")
                home.addAll(document.select("a[href*=/dizi/], a[href*=/film/]").mapNotNull { element ->
                    val href = element.attr("href")
                    if (href.contains("/dizi/") || href.contains("/film/")) {
                        val title = element.attr("title").ifEmpty { element.text() }
                        val img = element.selectFirst("img")
                        val poster = img?.attr("src") ?: img?.attr("data-src")
                        if (title.isNotBlank() && href.isNotBlank()) {
                            newTvSeriesSearchResponse(title, fixUrlNull(href) ?: href, TvType.TvSeries) {
                                this.posterUrl = fixUrlNull(poster)
                            }
                        } else null
                    } else null
                })
            }

            Log.d("DZP", "Primary load: ${home.size} items")
        } else {
            // ! Lazy loading - yeni site için API kontrolü
            Log.d("DZP", "Lazy loading page $page not implemented for new site")
        }

        if (home.isEmpty()) {
            Log.w("DZP", "No content found for ${request.name}")
        }

        return newHomePageResponse(request.name, home)
    }

    // ! Yeni site için Son Bölümler seçici
    private fun Element.sonBolumlerYeni(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/bolum/")) return null

        val title = this.selectFirst(".ep-title")?.text()?.trim() ?: return null
        val info = this.selectFirst(".ep-info")?.text()?.trim() ?: ""
        val poster = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))

        // Dizi URL'sini çıkar (örn: /bolum/school-spirits-3-sezon-8-bolum -> /dizi/school-spirits)
        val seriesSlug = href.substringAfter("/bolum/").substringBefore("-").replace("-", " ")
        val seriesUrl = "${mainUrl}/dizi/${seriesSlug.lowercase().replace(" ", "-")}"

        val fullTitle = if (info.isNotEmpty()) "$title $info" else title

        return newTvSeriesSearchResponse(fullTitle, seriesUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ! Yeni site için Dizi/Film kartları seçici
    private fun Element.diziKarti(): SearchResponse? {
        val link = this.selectFirst("a.card-link") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        val title = this.selectFirst(".card-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))
        
        val typeBadge = this.selectFirst(".card-badge.type")?.text()?.trim()?.lowercase()
        val isSeries = typeBadge == "dizi" || href.contains("/dizi/")
        val isMovie = typeBadge == "film" || href.contains("/film/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            null
        }
    }

    private fun SearchItem.toPostSearchResult(): SearchResponse {
        val title = this.title
        val href = "${mainUrl}${this.url}"
        val posterUrl = this.poster
        val isSeries = this.type.lowercase().contains("series") || this.type.lowercase() == "dizi"

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // ! Yeni site için arama API'si
        try {
            val response = app.get(
                "${mainUrl}?s=${query}",
                interceptor = interceptor
            )
            
            val document = response.document
            val results = mutableListOf<SearchResponse>()

            // Arama sonuçlarını çek
            document.select("ul.content-grid li.content-card").forEach { element ->
                element.diziKarti()?.let { results.add(it) }
            }

            // Eğer sonuç yoksa ana sayfadaki gibi dene
            if (results.isEmpty()) {
                document.select("a[href*=/dizi/], a[href*=/film/]").forEach { element ->
                    val href = element.attr("href")
                    if (href.contains("/dizi/") || href.contains("/film/")) {
                        val title = element.attr("title").ifEmpty { element.text() }
                        val img = element.selectFirst("img")
                        val poster = img?.attr("src") ?: img?.attr("data-src")
                        if (title.isNotBlank() && href.isNotBlank()) {
                            results.add(
                                newTvSeriesSearchResponse(title, fixUrlNull(href) ?: href, TvType.TvSeries) {
                                    this.posterUrl = fixUrlNull(poster)
                                }
                            )
                        }
                    }
                }
            }

            return results
        } catch (e: Exception) {
            Log.e("DZP", "Search error: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        // ! Poster
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        
        // ! Başlık - JSON-LD'dan veya sayfadan al
        val title = document.selectFirst("h1.series-title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null
        
        // ! Açıklama - JSON-LD'dan veya sayfadan al
        val description = document.selectFirst("p.series-description")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()
        
        // ! Yıl ve Puan - JSON-LD'dan al (daha güvenilir)
        var year: Int? = null
        var score: Score? = null
        
        // JSON-LD'dan verileri çek
        document.select("script[type='application/ld+json']").forEach { script ->
            try {
                val json = script.data()
                if (json.contains("\"datePublished\"")) {
                    year = Regex("\"datePublished\":\"?(\\d{4})\"?").find(json)?.groupValues?.get(1)?.toIntOrNull()
                }
                if (json.contains("\"ratingValue\"")) {
                    val rating = Regex("\"ratingValue\":([\\d.]+)").find(json)?.groupValues?.get(1)?.toDoubleOrNull()
                    score = rating?.let { Score.from10(it) }
                }
            } catch (_: Exception) { }
        }
        
        // ! Etiketler (Kategoriler)
        val tags = document.select(".sidebar-info .info-value.categories a").map { it.text().trim() }.filter { it.isNotEmpty() }
        
        // ! Tür kontrolü
        val isSeries = url.contains("/dizi/") || url.contains("/bolum/")
        val isMovie = url.contains("/film/")

        return if (isSeries) {
            // ! ★★★ BÖLÜMLER - Yeni site yapısına göre ★★★
            val episodes = mutableListOf<Episode>()
            
            // ! 1. Yöntem: detail-episode-list içindeki bölümler
            document.select(".detail-episode-list .detail-episode-item-wrap").forEach { item ->
                val link = item.selectFirst("a.detail-episode-item")
                val epHref = fixUrlNull(link?.attr("href")) ?: return@forEach
                
                val titleText = link?.selectFirst(".detail-episode-title")?.text()?.trim() ?: ""
                val subtitleText = link?.selectFirst(".detail-episode-subtitle")?.text()?.trim() ?: ""
                
                // Sezon ve bölüm numaralarını çıkar (örn: "1. Sezon 1. Bölüm")
                val seasonMatch = Regex("(\\d+)\\.\\s*Sezon").find(subtitleText)
                val episodeMatch = Regex("(\\d+)\\.\\s*Bölüm").find(subtitleText)
                
                val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
                val episode = episodeMatch?.groupValues?.get(1)?.toIntOrNull()
                
                val displayName = if (season != null && episode != null) {
                    "${season}x${episode.toString().padStart(2, '0')}"
                } else {
                    titleText.ifEmpty { subtitleText }
                }
                
                episodes.add(
                    newEpisode(epHref) {
                        this.name = displayName
                        this.season = season
                        this.episode = episode
                    }
                )
            }
            
            // ! 2. Yöntem: Eğer hiç bölüm bulunamadıysa, tüm /bolum/ linklerini dene
            if (episodes.isEmpty()) {
                document.select("a[href*=/bolum/]").forEach { link ->
                    val epHref = fixUrlNull(link.attr("href")) ?: return@forEach
                    val text = link.text().trim()
                    
                    // Sezon ve bölüm bilgisini çıkar
                    val seasonMatch = Regex("(\\d+)\\s*[.-]?\\s*Sezon").find(text)
                    val episodeMatch = Regex("(\\d+)\\s*[.-]?\\s*Bölüm").find(text)
                    
                    val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
                    val episode = episodeMatch?.groupValues?.get(1)?.toIntOrNull()
                    
                    val displayName = if (season != null && episode != null) {
                        "${season}x${episode.toString().padStart(2, '0')}"
                    } else {
                        text.ifEmpty { "Bölüm" }
                    }
                    
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = displayName
                            this.season = season
                            this.episode = episode
                        }
                    )
                }
            }
            
            // ! 3. Yöntem: JSON-LD'dan sezon sayısını al (hata ayıklama için)
            val totalSeasons = document.select("script[type='application/ld+json']").firstOrNull()?.let { script ->
                Regex("\"numberOfSeasons\":(\\d+)").find(script.data())?.groupValues?.get(1)?.toIntOrNull()
            } ?: 0
            
            Log.d("DZP", "Total seasons from JSON-LD: $totalSeasons, Episodes found: ${episodes.size}")
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
            }
        } else if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
            }
        } else {
            null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZP", "loadLinks: $data")
        
        try {
            val document = app.get(data, interceptor = interceptor).document
            val html = document.html()
            Log.d("DZP", "HTML length: ${html.length}")
            
            // ! ★★★ 1. YÖNTEM: data-cfg attribute'ünden video URL'sini al ★★★
            val videoContainer = document.selectFirst("#videoContainer, .video-player-container, [data-cfg]")
            var embedUrl: String? = null
            
            if (videoContainer != null) {
                val dataCfg = videoContainer.attr("data-cfg")
                if (dataCfg.isNotEmpty()) {
                    try {
                        // Base64 decode
                        val decoded = String(android.util.Base64.decode(dataCfg, android.util.Base64.DEFAULT))
                        Log.d("DZP", "Decoded data-cfg: $decoded")
                        
                        // JSON'dan video URL'sini çıkar
                        val vMatch = Regex("\"v\"\\s*:\\s*\"([^\"]+)\"").find(decoded)
                        if (vMatch != null) {
                            embedUrl = vMatch.groupValues[1]
                            Log.d("DZP", "Found embed URL from data-cfg: $embedUrl")
                        }
                    } catch (e: Exception) {
                        Log.d("DZP", "Failed to decode data-cfg: ${e.message}")
                    }
                }
            }
            
            // ! 2. YÖNTEM: Eğer data-cfg yoksa, iframe ara
            if (embedUrl == null) {
                // Iframe'leri kontrol et
                val iframes = document.select("iframe")
                for (frame in iframes) {
                    val src = frame.attr("src")
                    if (src.isNotEmpty() && (src.contains("embed") || src.contains("player") || src.contains("video"))) {
                        embedUrl = src
                        Log.d("DZP", "Found iframe: $embedUrl")
                        break
                    }
                }
            }
            
            // ! 3. YÖNTEM: Script içinde video URL'si ara
            if (embedUrl == null) {
                val scripts = document.select("script")
                for (script in scripts) {
                    val scriptData = script.data()
                    
                    // window.playerConfig veya benzeri
                    val configMatch = Regex("""playerConfig\s*=\s*\{[^}]*url\s*:\s*['"]([^'"]+)['"]""").find(scriptData)
                    if (configMatch != null) {
                        embedUrl = configMatch.groupValues[1]
                        Log.d("DZP", "Found from playerConfig: $embedUrl")
                        break
                    }
                }
            }
            
            if (embedUrl == null) {
                Log.d("DZP", "No embed URL found")
                return false
            }
            
            // ! Embed URL'sini düzelt
            val fullEmbedUrl = when {
                embedUrl.startsWith("http") -> embedUrl
                embedUrl.startsWith("//") -> "https:$embedUrl"
                embedUrl.startsWith("/") -> "${mainUrl}${embedUrl}"
                else -> embedUrl
            }
            
            Log.d("DZP", "Full embed URL: $fullEmbedUrl")
            
            // ! Embed sayfasını al
            val embedDoc = app.get(fullEmbedUrl, referer = mainUrl, interceptor = interceptor)
            val embedHtml = embedDoc.text
            Log.d("DZP", "Embed HTML length: ${embedHtml.length}")
            
            // ! M3U8 linkini bul - tüm olası pattern'ler
            val m3uPatterns = listOf(
                Regex("""file:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""src:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""url:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""video:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""source:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""href:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""(https?://[^\s'"]+\.m3u8[^\s'"]*)""")
            )
            
            var m3uLink: String? = null
            for (pattern in m3uPatterns) {
                val match = pattern.find(embedHtml)
                if (match != null) {
                    m3uLink = match.groupValues[1]
                    Log.d("DZP", "Found m3u8 with pattern: ${pattern.pattern}")
                    break
                }
            }
            
            if (m3uLink == null) {
                Log.d("DZP", "No m3u8 link found, trying extractor")
                Log.d("DZP", "Embed HTML preview: ${embedHtml.take(500)}")
                return loadExtractor(fullEmbedUrl, mainUrl, subtitleCallback, callback)
            }
            
            Log.d("DZP", "m3uLink found: $m3uLink")
            
            // ! Altyazıları bul
            val subtitlePatterns = listOf(
                Regex(""""subtitle":"([^"]+)""""),
                Regex("""subtitle:\s*['"]([^'"]+)['"]"""),
                Regex("""subtitles:\s*['"]([^'"]+)['"]""")
            )
            
            for (pattern in subtitlePatterns) {
                val match = pattern.find(embedHtml)
                if (match != null) {
                    val subtitles = match.groupValues[1]
                    if (subtitles.contains(",")) {
                        subtitles.split(",").forEach { sub ->
                            val subLang = sub.substringAfter("[").substringBefore("]")
                            val subUrl = sub.replace("[${subLang}]", "")
                            if (subUrl.isNotBlank()) {
                                subtitleCallback.invoke(SubtitleFile(lang = subLang, url = fixUrlNull(subUrl) ?: subUrl))
                            }
                        }
                    } else {
                        val subLang = subtitles.substringAfter("[").substringBefore("]")
                        val subUrl = subtitles.replace("[${subLang}]", "")
                        if (subUrl.isNotBlank()) {
                            subtitleCallback.invoke(SubtitleFile(lang = subLang, url = fixUrlNull(subUrl) ?: subUrl))
                        }
                    }
                    break
                }
            }
            
            // ! M3U8 linkini düzelt
            val finalM3uLink = when {
                m3uLink.startsWith("http") -> m3uLink
                m3uLink.startsWith("//") -> "https:$m3uLink"
                m3uLink.startsWith("/") -> "${mainUrl}${m3uLink}"
                else -> {
                    val baseUrl = fullEmbedUrl.substringBeforeLast("/")
                    "$baseUrl/$m3uLink"
                }
            }
            
            Log.d("DZP", "Final m3u8 URL: $finalM3uLink")
            
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalM3uLink,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                        "Referer" to mainUrl
                    )
                }
            )
            
            return true
        } catch (e: Exception) {
            Log.e("DZP", "Error in loadLinks: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}
