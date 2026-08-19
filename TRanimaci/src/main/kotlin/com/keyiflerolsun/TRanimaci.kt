// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.*
import org.jsoup.Jsoup
import java.net.URLEncoder

class TRanimaci : MainAPI() {
    override var mainUrl              = "https://tranimaci.com"
    override var name                 = "TrAnimeci"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 1000L  // Cloudflare için daha yavaş
    override var sequentialMainPageScrollDelay = 1000L

    // Cloudflare için özel headerlar
    private val cloudflareHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Accept-Encoding" to "gzip, deflate, br",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Cache-Control" to "max-age=0"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/category/action"                                   to "Aksiyon",
        "${mainUrl}/category/cars"                                     to "Arabalar",
        "${mainUrl}/category/supernatural"                             to "Doğaüstü",
        "${mainUrl}/category/drama"                                    to "Dram",
        "${mainUrl}/category/ecchi"                                    to "Ecchi",
        "${mainUrl}/category/fantasy"                                  to "Fantastik",
        "${mainUrl}/category/mystery"                                  to "Gizem",
        "${mainUrl}/category/comedy"                                   to "Komedi",
        "${mainUrl}/category/horror"                                   to "Korku",
        "${mainUrl}/category/adventure"                                to "Macera",
        "${mainUrl}/category/mecha"                                    to "Mecha",
        "${mainUrl}/category/music"                                    to "Müzik",
        "${mainUrl}/category/romance"                                  to "Romantik",
        "${mainUrl}/category/sports"                                   to "Spor",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = cloudflareHeaders).document
        val home     = document.select("article.bs div.bsx").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("a")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.limit img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("${mainUrl}/search?name=${encodedQuery}", headers = cloudflareHeaders).document

        return document.select("article.bs div.bsx").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = cloudflareHeaders).document

        val title       = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.thumb img")?.attr("src"))
        val description = document.selectFirst("div.anime-description")?.text()?.trim()
        val tags        = document.select("div#genxed a[href*='/category']").map { it.text() }

        val episodeses = mutableListOf<Episode>()

        for (bolum in document.select("div.eplister ul li a")) {
            val epHref = fixUrlNull(bolum.attr("href")) ?: continue
            val epName = bolum.selectFirst(".epl-title")?.text()?.trim() ?: continue
            val epEpisode = epName.replace("Bölüm", "").trim().toIntOrNull()
    
            val newEpisode = newEpisode(epHref) {
                this.name = epName
                this.episode = epEpisode
            }
            episodeses.add(newEpisode)
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeses) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("ANI", "data » $data")
        
        // Cloudflare korumasını aşmak için özel headerlar
        val videoHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to "https://tranimaci.com/",
            "Origin" to "https://tranimaci.com"
        )
        
        val document = app.get(data, headers = cloudflareHeaders + videoHeaders).document

        // 1. video_source içeren <script> etiketi
        val scriptContent = document.select("script").firstOrNull {
            it.html().contains("video_source")
        }?.html() ?: return false

        // 2. video_source içindeki JSON array'i çek
        val videoSourceJson = Regex("""video_source\s*=\s*`(\[.*?])`""", RegexOption.DOT_MATCHES_ALL)
            .find(scriptContent)
            ?.groups?.get(1)
            ?.value
            ?: return false

        val videoSourceArray = JSONArray(videoSourceJson)

        // 3. Her bir API URL'sine istek at
        for (i in 0 until videoSourceArray.length()) {
            val source = videoSourceArray.getJSONObject(i)
            val apiUrl = source.getString("url")
            Log.d("ANI", "apiUrl » $apiUrl")

            // API için özel headerlar
            val apiHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                "Referer" to "https://tranimaci.com/",
                "Origin" to "https://tranimaci.com",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site"
            )

            // 4. API sayfasını çek - Cloudflare için gecikme ekle
            delay(500)
            val apiHtml = app.get(apiUrl, headers = cloudflareHeaders + apiHeaders).text
            val apiDoc = Jsoup.parse(apiHtml)
            Log.d("ANI", "apiDoc » ${apiDoc.html().take(500)}")

            // 5. const sources = [...] içeren <script> bul
            val sourcesScript = apiDoc.select("script").firstOrNull {
                it.html().contains("const sources")
            } ?: continue

            val sourcesArrayRaw = Regex("""const\s+sources\s*=\s*(\[[\s\S]*?])\s*;""")
                .find(sourcesScript.html())
                ?.groups?.get(1)
                ?.value
                ?: continue

            // 6. MP4 linklerini JSON olarak parse et
            val mp4Array = JSONArray(sourcesArrayRaw)
            Log.d("ANI", "mp4Array » $mp4Array")

            for (j in 0 until mp4Array.length()) {
                val mp4 = mp4Array.getJSONObject(j)
                val videoUrl = "https://api.animeuzayi.com" + mp4.getString("src")
                val quality = mp4.optInt("size", Qualities.Unknown.value)

                // Video linki için özel headerlar
                val videoLinkHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to "https://api.animeuzayi.com/",
                    "Origin" to "https://api.animeuzayi.com"
                )

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} - ${quality}p",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://api.animeuzayi.com/"
                        this.quality = quality
                        this.headers = videoLinkHeaders
                    }
                )
            }
        }

        return true
    }
}
