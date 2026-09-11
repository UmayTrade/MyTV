// ! Bu araç @kerimmkirac tarafından | @kerimmkirac için yazılmıştır. (Kanal D için uyarlanmıştır)

package com.kerimmkirac

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
    private val cacheValidityDuration = 30 * 60 * 1000

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler" to "Diziler",
        "${mainUrl}/programlar" to "Programlar",
        "${mainUrl}/canli-yayin" to "Canlı Yayın"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select("div.card, div.item, li.item, article, a[href*='/dizi/'], a[href*='/program/']")
        val results = items.mapNotNull { it.toMainPageResult() }.distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList(request.name, results))
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        // 改进的标题提取策略
        val title = link.attr("title").ifEmpty {
            this.selectFirst("img")?.attr("alt") ?: ""
        }.ifEmpty {
            this.selectFirst("h3, h2, .title, .card-title, .name")?.text() ?: ""
        }.trim()

        if (title.isEmpty()) return null

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
            val pagesToScan = listOf("${mainUrl}/diziler", "${mainUrl}/programlar")
            for (pageUrl in pagesToScan) {
                val document = app.get(pageUrl).document
                val items = document.select("div.card, div.item, li.item, article, a[href*='/dizi/'], a[href*='/program/']")
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

        val episodes = getEpisodes(document, url)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private suspend fun getEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()
        try {
            // 从当前页面和 bölümler 子页面查找
            val episodeLinks = document.select("a[href*='/bolum/'], a[href*='/bolumler/']")
            
            val items = if (episodeLinks.isEmpty()) {
                val episodePageUrl = if (baseUrl.contains("/bolumler")) baseUrl else "$baseUrl/bolumler"
                app.get(episodePageUrl).document.select("a[href*='/bolum/'], a[href*='/bolumler/']")
            } else episodeLinks

            items.distinctBy { it.attr("href") }.forEachIndexed { index, element ->
                val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed
                val epName = element.attr("title").ifEmpty { element.text() }.trim()
                    .ifEmpty { "Bölüm ${index + 1}" }

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

            val document = app.get(data).document
            var found = false

            // 1. 优先尝试 iframe（Kanal D 常用嵌入播放器）
            val iframe = document.selectFirst("iframe[src]")
            if (iframe != null) {
                val embedUrl = fixUrl(iframe.attr("src"))
                Log.d("KanalD", "Found iframe: $embedUrl")
                // 交给 Cloudstream 的 extractor 系统处理
                if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                    found = true
                }
            }

            // 2. 如果 iframe 没找到或失败，尝试从 script 中提取
            if (!found) {
                val scripts = document.select("script")
                val patterns = listOf(
                    Regex("\"videoUrl\"\\s*:\\s*\"([^\"]+)\""),
                    Regex("\"file\"\\s*:\\s*\"([^\"]+)\""),
                    Regex("src\\s*=\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']"),
                    Regex("src\\s*=\\s*[\"']([^\"']+\\.mp4[^\"']*)[\"']")
                )

                for (script in scripts) {
                    val content = script.data()
                    for (pattern in patterns) {
                        pattern.find(content)?.let { match ->
                            val videoUrl = match.groupValues[1].replace("\\/", "/")
                            Log.d("KanalD", "Found video URL: $videoUrl")
                            callback.invoke(
                                newExtractorLink(
                                    name = this.name,
                                    source = this.name,
                                    url = fixUrl(videoUrl),
                                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                }
                            )
                            found = true
                        }
                    }
                }
            }

            return found
        } catch (e: Exception) {
            Log.e("KanalD", "LoadLinks hatası: ${e.message}")
            return false
        }
    }
}
