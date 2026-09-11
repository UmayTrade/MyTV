// ! Bu araç @kerimmkirac tarafından | @kerimmkirac için yazılmıştır. (Kanal D için uyarlanmıştır)

package com.kerimmkirac

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class KanalD : MainAPI() {
    override var mainUrl              = "https://www.kanald.com.tr"
    override var name                 = "Kanal D"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Live)

    private var allContentCache: List<SearchResponse> = emptyList()
    private var cacheTime: Long = 0
    private val cacheValidityDuration = 30 * 60 * 1000 // 30 dakika

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler" to "Diziler",
        "${mainUrl}/programlar" to "Programlar",
        "${mainUrl}/canli-yayin" to "Canlı Yayın"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val sections = mutableListOf<HomePageList>()

        // Kanal D ana sayfa ve liste sayfalarında içerikleri toplama stratejisi
        val items = document.select("div.card, div.item, li.item, article")
        val results = items.mapNotNull { it.toMainPageResult() }

        if (results.isNotEmpty()) {
            sections.add(HomePageList(request.name, results))
        }

        return HomePageResponse(sections)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        
        // Başlık ve resim çekme (Kanal D class yapısına göre tahmini)
        val title = this.selectFirst("h3, h2, .title, .card-title")?.text()?.trim() 
            ?: link.attr("title").trim()
        val poster = this.selectFirst("img")?.let { img ->
            fixUrlNull(img.attr("data-src").ifEmpty { img.attr("src") })
        }

        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private suspend fun getAllContent(): List<SearchResponse> {
        val currentTime = System.currentTimeMillis()

        if (allContentCache.isNotEmpty() && (currentTime - cacheTime) < cacheValidityDuration) {
            return allContentCache
        }

        val allContent = mutableListOf<SearchResponse>()
        try {
            // Kanal D'de diziler ve programlar sayfalarını tarayarak önbellek oluşturuyoruz
            val pagesToScan = listOf("${mainUrl}/diziler", "${mainUrl}/programlar")
            
            for (pageUrl in pagesToScan) {
                val document = app.get(pageUrl).document
                val items = document.select("div.card, div.item, li.item, article")
                items.forEach { element ->
                    element.toMainPageResult()?.let { allContent.add(it) }
                }
            }

            val uniqueContent = allContent.distinctBy { it.url }
            allContentCache = uniqueContent
            cacheTime = currentTime
            return uniqueContent
        } catch (e: Exception) {
            Log.e("KanalD", "İçerik toplanırken hata: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val allContent = getAllContent()
        val searchQuery = query.lowercase(Locale.getDefault())
        
        return allContent.filter { 
            it.name.lowercase(Locale.getDefault()).contains(searchQuery) 
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = document.selectFirst("span.year, .year")?.text()?.trim()?.toIntOrNull()

        // Bölümleri çekme mantığı
        val episodes = getEpisodes(document, url)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
        }
    }

    private suspend fun getEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()
        
        try {
            // Kanal D bölüm listesi genellikle "bolumler" linki altındadır veya sayfada listelenir
            val episodeLinks = document.select("a[href*='/bolumler/'], a[href*='/bolum/']")
            
            // Eğer sayfada direkt bölüm linkleri yoksa, bölümler sayfasına gitmeyi dene
            val episodePageUrl = if (episodeLinks.isEmpty()) {
                if (baseUrl.contains("/bolumler")) baseUrl else "$baseUrl/bolumler"
            } else {
                null
            }

            val docToParse = if (episodePageUrl != null) {
                app.get(episodePageUrl).document
            } else {
                document
            }

            val items = docToParse.select("a[href*='/bolum/'], a[href*='/bolumler/']").distinctBy { it.attr("href") }
            
            items.forEachIndexed { index, element ->
                val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed
                val epName = element.text().trim().ifEmpty { "Bölüm ${index + 1}" }
                
                newEpisode(href) {
                    this.name = epName
                    this.episode = index + 1
                }?.let { allEpisodes.add(it) }
            }

            return allEpisodes.sortedBy { it.episode }
        } catch (e: Exception) {
            Log.e("KanalD", "Bölüm çekme hatası: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("KanalD", "Video data: $data")

        try {
            if (data.isBlank()) return false

            // Kanal D için link çıkarma stratejisi
            // Genellikle sayfa içinde "videoUrl" veya "m3u8" veya YouTube embed bulunur.
            val document = app.get(data).document
            
            // 1. Doğrudan MP4 / M3U8 arama
            val scripts = document.select("script")
            var found = false
            
            for (script in scripts) {
                val content = script.data()
                // Regex ile video linklerini ara
                val patterns = listOf(
                    Regex("\"videoUrl\"\\s*:\\s*\"([^\"]+)\""),
                    Regex("\"file\"\\s*:\\s*\"([^\"]+)\""),
                    Regex("src\\s*=\\s*\"([^\"]+\\.m3u8[^\"]*)\""),
                    Regex("src\\s*=\\s*\"([^\"]+\\.mp4[^\"]*)\"")
                )
                
                for (pattern in patterns) {
                    val match = pattern.find(content)
                    if (match != null) {
                        val videoUrl = match.groupValues[1].replace("\\/", "/")
                        Log.d("KanalD", "Bulunan video URL: $videoUrl")
                        
                        callback.invoke(
                            newExtractorLink(
                                name = this.name,
                                source = this.name,
                                url = fixUrl(videoUrl),
                                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                }
            }
            
            // 2. YouTube veya harici embed kontrolü
            val iframe = document.selectFirst("iframe[src*='youtube'], iframe[src*='dailymotion']")
            if (iframe != null && !found) {
                val embedUrl = iframe.attr("src")
                loadExtractor(embedUrl, data, subtitleCallback, callback)
                found = true
            }

            return found
        } catch (e: Exception) {
            Log.e("KanalD", "LoadLinks hatası: ${e.message}")
            return false
        }
    }
}