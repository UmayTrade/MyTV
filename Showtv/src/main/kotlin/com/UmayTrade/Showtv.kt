// ! Bu araç @UmayTrade tarafından Show TV için yazılmıştır.

package com.UmayTrade

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.util.Locale

class ShowTv : MainAPI() {
    override var mainUrl = "https://www.showtv.com.tr"
    override var name = "Show TV"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/programlar" to "Programlar"
    )

    // ★ Ana sayfa
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()

        try {
            val doc = app.get(request.data, headers = headers).document

            // Ana sayfa menüsünden
            doc.select("nav a[href*='/dizi/tanitim/'], nav a[href*='/programlar/tanitim/']")
                .forEach { element ->
                    element.toSearchResponse()?.let { results.add(it) }
                }

            // Liste sayfasındaki kartlardan
            doc.select("a[href*='/dizi/tanitim/'], a[href*='/programlar/tanitim/']")
                .forEach { element ->
                    element.toSearchResponse()?.let { results.add(it) }
                }

            Log.d(name, "getMainPage [${request.name}]: ${results.size} öğe bulundu")
        } catch (e: Exception) {
            Log.e(name, "getMainPage hatası: ${e.message}")
        }

        val uniqueResults = results.distinctBy { it.url }
        return newHomePageResponse(
            listOf(HomePageList(request.name, uniqueResults))
        )
    }

    // ★ Link elementini SearchResponse'a çevirir
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fullUrl = fixUrlNull(href) ?: return null

        if (!fullUrl.contains("/tanitim/")) return null

        val segments = fullUrl.trimEnd('/').split("/")
        if (segments.size < 2) return null

        // ID son segmentte
        segments.lastOrNull()?.toIntOrNull() ?: return null

        // Başlık
        val title = this.selectFirst("figcaption span.font-bold, figcaption span.text-xl, figcaption .title, h2, h3")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        // Poster
        val img = this.selectFirst("img")
        val poster: String? = img?.let {
            val dataSrc = it.attr("data-src")
            val src = it.attr("src")
            val raw = if (dataSrc.isNotEmpty()) dataSrc else src
            fixUrlNull(raw)
        }

        return newMovieSearchResponse(title, fullUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ★ Arama
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResponse>()

        try {
            val searchUrl = "$mainUrl/arama?ara=${query.replace(" ", "+")}"
            val doc = app.get(searchUrl, headers = headers).document
            doc.select("a[href*='/dizi/tanitim/'], a[href*='/programlar/tanitim/']")
                .forEach { element ->
                    element.toSearchResponse()?.let { results.add(it) }
                }
            Log.d(name, "Arama '$query': ${results.size} sonuç")
        } catch (e: Exception) {
            Log.e(name, "Arama hatası: ${e.message}")
        }

        // Fallback: tüm listeden filtrele
        if (results.isEmpty()) {
            try {
                val allContent = mutableListOf<SearchResponse>()
                for (pageUrl in listOf("$mainUrl/diziler", "$mainUrl/programlar")) {
                    try {
                        val doc = app.get(pageUrl, headers = headers).document
                        doc.select("a[href*='/dizi/tanitim/'], a[href*='/programlar/tanitim/']")
                            .forEach { element ->
                                element.toSearchResponse()?.let { allContent.add(it) }
                            }
                    } catch (e: Exception) {
                        Log.e(name, "Liste hatası ($pageUrl): ${e.message}")
                    }
                }
                val q = query.lowercase(Locale.getDefault())
                allContent.distinctBy { it.url }
                    .filter { it.name.lowercase(Locale.getDefault()).contains(q) }
                    .let { results.addAll(it) }
            } catch (e: Exception) {
                Log.e(name, "Fallback hatası: ${e.message}")
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ★ Detay sayfası
    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load: $url")

        // Bölüm sayfası mı?
        if (url.contains("/tum_bolumler/") || url.contains("/videolar/")) {
            return loadEpisodePage(url)
        }

        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.substringBefore("|")
            ?: return null

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val episodes = getEpisodes(document, url)
        Log.d(name, "Toplam ${episodes.size} bölüm: $title")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ★ Bölüm sayfası (tek bölümlük dizi olarak)
    private suspend fun loadEpisodePage(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        var title: String? = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        var poster: String? = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        var description: String? = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        // JSON-LD'den ek bilgi
        document.select("script[type=application/ld+json]").forEach { script ->
            try {
                val json = JSONObject(script.data())
                if (json.optString("@type") == "VideoObject") {
                    if (title.isNullOrEmpty()) {
                        title = json.optString("name").substringBefore("|").trim()
                    }
                    if (poster.isNullOrEmpty()) {
                        poster = fixUrlNull(json.optString("thumbnailUrl"))
                    }
                    if (description.isNullOrEmpty()) {
                        description = json.optString("description").trim()
                    }
                }
            } catch (_: Exception) {}
        }

        if (title.isNullOrEmpty()) return null

        val epNum = Regex("(\\d+)\\.\\s*Bölüm").find(title!!)?.groupValues?.get(1)?.toIntOrNull()

        val episode = newEpisode(url) {
            this.name = title!!
            this.episode = epNum
            this.posterUrl = poster
        } ?: return null

        return newTvSeriesLoadResponse(title!!, url, TvType.TvSeries, listOf(episode)) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ★ Bölümleri çek
    private suspend fun getEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()

        // Yöntem 1: "BÖLÜMLER" linkine git
        try {
            val bolumLink = document.selectFirst("nav a[title=BÖLÜMLER]")
                ?.attr("href")?.takeIf { it.isNotBlank() }

            if (bolumLink != null) {
                val bolumUrl = fixUrl(bolumLink)
                Log.d(name, "Bölümler sayfası: $bolumUrl")
                val bolumDoc = app.get(bolumUrl, headers = headers).document

                // Program sayfaları: select'ten
                val options = bolumDoc.select("select#seasonWithJs option[data-href], select option[data-href]")
                if (options.isNotEmpty()) {
                    Log.d(name, "Select'te ${options.size} bölüm")
                    options.forEachIndexed { index, option ->
                        val epUrl = fixUrlNull(option.attr("data-href")) ?: return@forEachIndexed
                        val epName = option.text().trim().takeIf { it.isNotEmpty() } ?: "Bölüm ${index + 1}"
                        val epNum = Regex("(\\d+)\\.").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                            ?: (options.size - index)

                        newEpisode(epUrl) {
                            this.name = epName
                            this.episode = epNum
                        }?.let { allEpisodes.add(it) }
                    }
                    return allEpisodes.sortedByDescending { it.episode ?: 0 }
                }

                // Dizi sayfaları: kartlardan
                val episodeCards = bolumDoc.select("section#default-season ul > li.iterate, ul#iterableSection > li.iterate")
                if (episodeCards.isNotEmpty()) {
                    Log.d(name, "Kart listesinde ${episodeCards.size} bölüm")
                    episodeCards.forEach { card ->
                        val a = card.selectFirst("a[data-ajax-link], a[href*='/tum_bolumler/']") ?: return@forEach
                        val epUrl = fixUrlNull(a.attr("href")) ?: return@forEach
                        val epName = a.attr("title").trim().ifEmpty {
                            a.selectFirst("span[data-ajax-title]")?.text()?.trim() ?: "Bölüm"
                        }
                        val epPoster: String? = a.selectFirst("img")?.let {
                            val dataSrc = it.attr("data-src")
                            val src = it.attr("src")
                            fixUrlNull(if (dataSrc.isNotEmpty()) dataSrc else src)
                        }
                        val epNum = Regex("(\\d+)\\.\\s*Bölüm").find(epName)?.groupValues?.get(1)?.toIntOrNull()

                        newEpisode(epUrl) {
                            this.name = epName
                            this.episode = epNum
                            this.posterUrl = epPoster
                        }?.let { allEpisodes.add(it) }
                    }
                    return allEpisodes.sortedByDescending { it.episode ?: 0 }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Bölüm sayfası hatası: ${e.message}")
        }

        // Yöntem 2: Detay sayfasındaki select
        try {
            val options = document.select("select#seasonWithJs option[data-href], select option[data-href]")
            if (options.isNotEmpty()) {
                Log.d(name, "Detay select'inde ${options.size} bölüm")
                options.forEachIndexed { index, option ->
                    val epUrl = fixUrlNull(option.attr("data-href")) ?: return@forEachIndexed
                    val epName = option.text().trim().takeIf { it.isNotEmpty() } ?: "Bölüm ${index + 1}"
                    val epNum = Regex("(\\d+)\\.").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (options.size - index)

                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                    }?.let { allEpisodes.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Detay select hatası: ${e.message}")
        }

        return allEpisodes.sortedByDescending { it.episode ?: 0 }
    }

    // ★ Video linklerini çek
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks: $data")
        var found = false

        try {
            val document = app.get(data, headers = headers).document

            // Öncelik 1: JSON-LD VideoObject > contentUrl
            document.select("script[type=application/ld+json]").forEach { script ->
                try {
                    val json = JSONObject(script.data())
                    if (json.optString("@type") == "VideoObject") {
                        val contentUrl = json.optString("contentUrl", "")
                        if (contentUrl.isNotEmpty() && contentUrl.startsWith("http")) {
                            Log.d(name, "JSON-LD contentUrl: $contentUrl")

                            val m3u8Url = contentUrl
                                .replace(Regex("_\\d+x\\d+\\.mp4$"), ".m3u8")
                                .replace(".mp4", ".m3u8")

                            val isHls = m3u8Url.contains(".m3u8")
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = m3u8Url,
                                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        }
                    }
                } catch (_: Exception) {}
            }

            // Öncelik 2: Regex ile m3u8/mp4 ara
            if (!found) {
                val patterns = listOf(
                    Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+)\""),
                    Regex("(https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)"),
                    Regex("(https?://[^\"'\\s]+\\.mp4[^\"'\\s]*)")
                )
                for (script in document.select("script")) {
                    val content = script.data()
                    for (pattern in patterns) {
                        val match = pattern.find(content)
                        if (match != null) {
                            val rawUrl = match.groupValues[1].replace("\\/", "/")
                            val videoUrl = rawUrl
                                .replace(Regex("_\\d+x\\d+\\.mp4$"), ".m3u8")
                                .replace(".mp4", ".m3u8")

                            Log.d(name, "Regex: $videoUrl")
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = videoUrl,
                                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                }
                            )
                            found = true
                            break
                        }
                    }
                    if (found) break
                }
            }

            // Öncelik 3: iframe embed
            if (!found) {
                val iframe = document.selectFirst("iframe[src*='embed'], iframe[src*='player']")
                if (iframe != null) {
                    val embedUrl = fixUrl(iframe.attr("src"))
                    Log.d(name, "iframe: $embedUrl")
                    if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(name, "loadLinks hatası: ${e.message}")
        }

        return found
    }
}
