// ! Bu araç @Kraptor123 tarafından | @kekikanime için yazılmıştır.

package com.kraptor

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class Sinezy : MainAPI() {
    override var mainUrl              = "https://sinezy.to"
    override var name                 = "Sinezy"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/"                    to  "Yeni Eklenenler",
        "${mainUrl}/izle/aksiyon-filmleri/"         to  "Aksiyon",
        "${mainUrl}/izle/animasyon-filmleri/"       to  "Animasyon",
        "${mainUrl}/izle/belgesel-izle/"            to  "Belgesel",
        "${mainUrl}/izle/bilim-kurgu-filmleri/"     to  "Bilim Kurgu",
        "${mainUrl}/izle/biyografi-filmleri/"       to  "Biyografi",
        "${mainUrl}/izle/dram-filmleri/"            to  "Dram",
        "${mainUrl}/izle/fantastik-filmler/"        to  "Fantastik",
        "${mainUrl}/izle/gelecek-filmler/"          to  "Yakında",
        "${mainUrl}/izle/gerilim-filmleri/"         to  "Gerilim",
        "${mainUrl}/izle/gizem-filmleri/"           to  "Gizem",
        "${mainUrl}/izle/komedi-filmleri/"          to  "Komedi",
        "${mainUrl}/izle/korku-filmleri/"           to  "Korku",
        "${mainUrl}/izle/macera-filmleri/"          to  "Macera",
        "${mainUrl}/izle/muzikal-izle/"             to  "Müzikal",
        "${mainUrl}/izle/romantik-film/"            to  "Romantik",
        "${mainUrl}/izle/savas-filmleri/"           to  "Savaş",
        "${mainUrl}/izle/spor-filmleri/"            to  "Spor",
        "${mainUrl}/izle/suc-filmleri/"             to  "Suç",
        "${mainUrl}/izle/tarih-filmleri/"           to  "Tarih",
        "${mainUrl}/izle/en-iyi-filmler/"           to  "En İyi Filmler",
        "${mainUrl}/izle/en-yeni-filmler/"          to  "Yeni Filmler",
        "${mainUrl}/izle/yerli-filmler/"            to  "Yerli Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            val document = app.get("${request.data}page/$page/").document
            val home = document.select("div.container div.content div.movie_box.move_k").mapNotNull { it.toMainPageResult() }
            return newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            Log.e("kraptor_${this.name}", "getMainPage hatası: ${e.message}")
            return newHomePageResponse(request.name, listOf())
        }
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        try {
            val title = this.selectFirst("a")?.attr("title") ?: return null
            val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
            val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
            val puan = this.selectFirst("span.coz")?.text()?.trim()

            return newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score = Score.from10(puan)
            }
        } catch (e: Exception) {
            Log.e("kraptor_Sinezy", "toMainPageResult hatası: ${e.message}")
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val document = app.get("${mainUrl}/arama/?s=${query}").document
            return document.select("div.movie_box.move_k").mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            Log.e("kraptor_Sinezy", "search hatası: ${e.message}")
            return listOf()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        try {
            val title = this.selectFirst("a")?.attr("title") ?: return null
            val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
            val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
            val puan = this.selectFirst("span.coz")?.text()?.trim()

            return newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score = Score.from10(puan)
            }
        } catch (e: Exception) {
            Log.e("kraptor_Sinezy", "toSearchResult hatası: ${e.message}")
            return null
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = app.get(url).document

            val title = document.selectFirst("div.detail")?.attr("title") ?: return null
            val poster = fixUrlNull(document.selectFirst("div.move_k img")?.attr("data-src"))
            val description = document.selectFirst("div.desc.yeniscroll p")?.text()?.trim()
            val year = document.selectFirst("div.move_k span.year span")?.text()?.trim()?.toIntOrNull()
            val tags = document.select("div.detail span a").map { it.text() }
            val rating = document.selectFirst("span.info span.imdb")?.text()?.trim()
            val duration = document.selectFirst("div.detail > span:nth-child(1) > span:nth-child(2) > p:nth-child(1)")
                ?.text()
                ?.replace(" Dakika", "")
                ?.trim()?.toIntOrNull()
            
            val actors = document.select("span.oyn p")
                .flatMap { it.text().split(",") }
                .map { Actor(it.trim()) }
            
            val trailer = Regex("""embed\/(.*)\?rel""").find(document.html())?.groupValues?.get(1)?.let { 
                "https://www.youtube.com/embed/$it" 
            }

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        } catch (e: Exception) {
            Log.e("kraptor_Sinezy", "load hatası: ${e.message}")
            return null
        }
    }

    // Base64 decode fonksiyonu - doğrudan sınıf içinde
    private fun decodeBase64(encoded: String): String {
        return try {
            String(Base64.decode(encoded, Base64.DEFAULT))
        } catch (e: Exception) {
            Log.e("kraptor_Sinezy", "Base64 decode hatası: ${e.message}")
            encoded
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            Log.d("kraptor_Sinezy", "loadLinks başladı - data: $data")
            
            val htmlContent = app.get(data).text
            Log.d("kraptor_Sinezy", "HTML içeriği alındı, uzunluk: ${htmlContent.length}")
            
            // 1. Yöntem: ilkpartkod ile iframe bulma
            val regex = Regex(pattern = """ilkpartkod = '([^']*)';""", options = setOf(RegexOption.IGNORE_CASE))
            val findreg = regex.find(htmlContent)?.groupValues?.get(1).toString()
            
            if (findreg.isNotEmpty()) {
                Log.d("kraptor_Sinezy", "ilkpartkod bulundu: $findreg")
                
                try {
                    val reqCoz = decodeBase64(findreg)
                    Log.d("kraptor_Sinezy", "ilkpartkod decode edildi: $reqCoz")
                    
                    val iframe = reqCoz.substringAfter("src=").substringBefore(" ").replace("\"", "")
                    Log.d("kraptor_Sinezy", "iframe URL: $iframe")
                    
                    if (iframe.isNotEmpty() && iframe.startsWith("http")) {
                        loadExtractor(iframe, mainUrl, subtitleCallback, callback)
                        return true
                    }
                } catch (e: Exception) {
                    Log.e("kraptor_Sinezy", "ilkpartkod işlenirken hata: ${e.message}")
                }
            }
            
            // 2. Yöntem: Doğrudan iframe bulma
            val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val iframeMatches = iframeRegex.findAll(htmlContent).toList()
            
            if (iframeMatches.isNotEmpty()) {
                for (match in iframeMatches) {
                    val iframeUrl = match.groupValues[1]
                    if (iframeUrl.isNotEmpty() && iframeUrl.startsWith("http")) {
                        Log.d("kraptor_Sinezy", "iframe bulundu: $iframeUrl")
                        loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
                        return true
                    }
                }
            }
            
            // 3. Yöntem: Video elementleri
            val videoRegex = Regex("""<video[^>]+src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            val videoMatch = videoRegex.find(htmlContent)
            
            if (videoMatch != null) {
                val videoUrl = videoMatch.groupValues[1]
                Log.d("kraptor_Sinezy", "Doğrudan video bulundu: $videoUrl")
                
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Video",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.Unknown.value
                        headers = mapOf("Referer" to mainUrl, "User-Agent" to "Mozilla/5.0")
                    }
                )
                return true
            }
            
            // 4. Yöntem: M3U8 linkleri
            val m3u8Regex = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
            val m3u8Matches = m3u8Regex.findAll(htmlContent).toList()
            
            if (m3u8Matches.isNotEmpty()) {
                for (match in m3u8Matches) {
                    val m3u8Url = match.value
                    Log.d("kraptor_Sinezy", "M3U8 link bulundu: $m3u8Url")
                    
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "M3U8",
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            quality = Qualities.Unknown.value
                            headers = mapOf("Referer" to mainUrl, "User-Agent" to "Mozilla/5.0")
                        }
                    )
                    return true
                }
            }
            
            Log.e("kraptor_Sinezy", "Hiçbir video linki bulunamadı!")
            return false
            
        } catch (e: Exception) {
            Log.e("kraptor_Sinezy", "loadLinks hatası: ${e.message}", e)
            return false
        }
    }
}
