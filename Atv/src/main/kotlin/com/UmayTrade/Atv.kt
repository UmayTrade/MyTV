// ! Bu araç @SAKLImavi tarafından | @UmayTrade için yazılmıştır. (ATV için uyarlanmıştır)

package com.UmayTrade

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.util.*

class Atv : MainAPI() {
    override var mainUrl              = "https://www.atv.com.tr"
    override var name                 = "ATV"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Live)

    private var allContentCache: List<SearchResponse> = emptyList()
    private var cacheTime: Long = 0
    private val cacheValidityDuration = 30 * 60 * 1000

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler"    to "Diziler",
        "${mainUrl}/programlar" to "Programlar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        // ATV liste sayfaları: .series-alternative-content a veya .series-slider a
        val items = document.select(
            "div.series-alternative-content > a, " +
            "div.series-slider .swiper-slide > a, " +
            "section.listing-holder .item, " +
            "div.listing-holder > div"
        )
        val results = items.mapNotNull { it.toMainPageResult() }.distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList(request.name, results))
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // Bazen <a> kendisi, bazen içinde
        val link = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        // Alt sayfaları filtrele
        if (href.contains("/izle") || href.contains("/bolum") ||
            href.contains("/fragman") || href.contains("/ozet") ||
            href.contains("/foto") || href.contains("/haber") ||
            href.contains("/kadro") || href.contains("/galeri") ||
            href.contains("/ozelvideo")) return null

        // Ana sayfa linki değil (sadece /slug formatı olsun)
        val slug = href.removePrefix(mainUrl).trim('/')
        if (slug.isEmpty() || slug.contains("/")) return null

        // Başlık: figcaption > p, h2, img alt
        val title = this.selectFirst("figcaption p, figcaption .title, h2, h3, .title, .caption")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: link.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        // Poster
        val poster = this.selectFirst("img")?.let { img ->
            fixUrlNull(
                img.attr("data-src").ifEmpty {
                    img.attr("src").ifEmpty { img.attr("data-lazy-src") }
                }
            )
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
                val items = document.select(
                    "div.series-alternative-content > a, " +
                    "div.series-slider .swiper-slide > a, " +
                    "section.listing-holder .item, " +
                    "div.listing-holder > div"
                )
                items.forEach { element ->
                    element.toMainPageResult()?.let { allContent.add(it) }
                }
            }

            val uniqueContent = allContent.distinctBy { it.url }
            allContentCache = uniqueContent
            cacheTime = currentTime
            return uniqueContent
        } catch (e: Exception) {
            Log.e("ATV", "İçerik toplanırken hata: ${e.message}")
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

        // ★ Bölüm sayfası mı? (/izle ile bitiyorsa tek bölüm olarak işle)
        if (url.contains("/izle")) {
            val title = document.selectFirst("h1.video-title")?.text()?.trim()
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: return null
            val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

            val episode = newEpisode(url) {
                this.name = title
                this.episode = 1
                this.posterUrl = poster
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOfNotNull(episode)) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // Dizi detay sayfası
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
            // ★ Öncelik 1: AJAX endpoint'lerini dene (ATV bölümleri JS ile yükler)
            val slug = baseUrl.substringAfter(mainUrl).trim('/').substringBefore("/")
            val ajaxUrls = listOf(
                "$mainUrl/ajax/series/$slug/episodes",
                "$mainUrl/ajax/$slug/episodes",
                "$mainUrl/ajax/series/$slug/bolumler",
                "$mainUrl/$slug/bolumler"
            )

            for (ajaxUrl in ajaxUrls) {
                try {
                    val response = app.get(
                        ajaxUrl,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to baseUrl,
                            "Accept" to "application/json, text/html, */*"
                        )
                    )
                    val doc = response.document
                    // Bölüm linklerini ara
                    val links = doc.select("a[href*='/izle'], a[href*='-bolum']")
                    if (links.isNotEmpty()) {
                        Log.d("ATV", "AJAX'den ${links.size} bölüm bulundu: $ajaxUrl")
                        links.distinctBy { it.attr("href") }.forEachIndexed { index, element ->
                            val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed
                            if (href == baseUrl) return@forEachIndexed

                            val epName = element.selectFirst(".style-01, .style-02, h3, .title")
                                ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                                ?: element.text().trim().takeIf { it.isNotEmpty() }
                                ?: "Bölüm ${index + 1}"

                            newEpisode(href) {
                                this.name = epName
                                this.episode = index + 1
                            }?.let { allEpisodes.add(it) }
                        }
                        if (allEpisodes.isNotEmpty()) {
                            return allEpisodes.reversed()
                        }
                    }
                } catch (e: Exception) {
                    Log.d("ATV", "AJAX denemesi başarısız ($ajaxUrl): ${e.message}")
                }
            }

            // ★ Öncelik 2: Sayfadaki statik bölüm linkleri (fragmanlar vs.)
            val episodeLinks = document.select("a[href*='/izle']")
                .filter { it.attr("href").contains("-bolum") }
            if (episodeLinks.isNotEmpty()) {
                Log.d("ATV", "Statik ${episodeLinks.size} bölüm linki bulundu")
                episodeLinks.distinctBy { it.attr("href") }.forEachIndexed { index, element ->
                    val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed
                    val epName = element.selectFirst(".style-02, .style-01, .title")?.text()?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "Bölüm ${index + 1}"

                    newEpisode(href) {
                        this.name = epName
                        this.episode = index + 1
                    }?.let { allEpisodes.add(it) }
                }
                return allEpisodes.reversed()
            }

            return emptyList()
        } catch (e: Exception) {
            Log.e("ATV", "Bölüm çekme hatası: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("ATV", "Video data: $data")
        try {
            if (data.isBlank()) return false

            val document = app.get(data).document
            var found = false

            // ★ Öncelik 1: JSON-LD VideoObject > contentUrl
            val ldJsonScripts = document.select("script[type=application/ld+json]")
            for (script in ldJsonScripts) {
                val content = script.data()
                if (!content.contains("VideoObject") && !content.contains("contentUrl")) continue
                try {
                    val json = JSONObject(content)
                    if (json.optString("@type").contains("VideoObject")) {
                        val contentUrl = json.optString("contentUrl", "")
                        if (contentUrl.isNotEmpty() && (contentUrl.startsWith("http"))) {
                            Log.d("ATV", "JSON-LD contentUrl bulundu: $contentUrl")
                            callback.invoke(
                                newExtractorLink(
                                    name = this.name,
                                    source = this.name,
                                    url = contentUrl,
                                    type = if (contentUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ATV", "JSON-LD parse hatası: ${e.message}")
                }
            }

            // ★ Öncelik 2: Regex ile contentUrl / m3u8 / mp4 ara
            if (!found) {
                val patterns = listOf(
                    Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+\\.m3u8[^\"]*)\""),
                    Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+\\.mp4[^\"]*)\""),
                    Regex("(https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)"),
                    Regex("(https?://[^\"'\\s]+\\.mp4[^\"'\\s]*)")
                )
                for (script in document.select("script")) {
                    val content = script.data()
                    for (pattern in patterns) {
                        pattern.find(content)?.let { match ->
                            val videoUrl = match.groupValues[1].replace("\\/", "/")
                            Log.d("ATV", "Regex ile bulundu: $videoUrl")
                            callback.invoke(
                                newExtractorLink(
                                    name = this.name,
                                    source = this.name,
                                    url = videoUrl,
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

            // ★ Öncelik 3: iframe embed
            if (!found) {
                val iframe = document.selectFirst("iframe[src]")
                if (iframe != null) {
                    val embedUrl = fixUrl(iframe.attr("src"))
                    Log.d("ATV", "iframe bulundu: $embedUrl")
                    if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }

            return found
        } catch (e: Exception) {
            Log.e("ATV", "LoadLinks hatası: ${e.message}")
            return false
        }
    }
}
