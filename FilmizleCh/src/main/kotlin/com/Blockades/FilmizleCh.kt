package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLDecoder

class FilmizleChProvider : MainAPI() {
    override var mainUrl = "https://filmizlech.com"
    override var name = "FilmizleCh"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/animeler" to "Animeler",
        "$mainUrl/diziler?cats=yerli-dizi" to "Yerli Diziler",
        "$mainUrl/diziler?cats=kore-dizi" to "Kore Dizileri",
        "$mainUrl/diziler?cats=asya-dizi" to "Asya Dizileri",
        "$mainUrl/diziler?cats=hint-dizi" to "Hint Dizileri",
        "$mainUrl/diziler?cats=aile" to "Aile",
        "$mainUrl/diziler?cats=aksiyon" to "Aksiyon",
        "$mainUrl/diziler?cats=animasyon" to "Animasyon",
        "$mainUrl/diziler?cats=belgesel" to "Belgesel",
        "$mainUrl/diziler?cats=bilim-kurgu" to "Bilim-Kurgu",
        "$mainUrl/diziler?cats=cocuk" to "Çocuklar",
        "$mainUrl/diziler?cats=drama" to "Dram",
        "$mainUrl/diziler?cats=gerceklik" to "Gerçeklik",
        "$mainUrl/diziler?cats=gizem" to "Gizem",
        "$mainUrl/diziler?cats=haber" to "Haber",
        "$mainUrl/diziler?cats=komedi" to "Komedi",
        "$mainUrl/diziler?cats=pembe-dizi" to "Pembe Dizi",
        "$mainUrl/diziler?cats=romantik" to "Romantik",
        "$mainUrl/diziler?cats=savas" to "Savaş & Politik",
        "$mainUrl/diziler?cats=suc" to "Suç",
        "$mainUrl/diziler?cats=talk-show" to "Talk",
        "$mainUrl/diziler?cats=tarih" to "Tarih",
        "$mainUrl/diziler?cats=western" to "Vahşi Batı"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data}?page=$page"
            }
        } else {
            request.data
        }

        val res = app.get(url, headers = defaultHeaders, cacheTime = 0)
        val items = mutableListOf<SearchResponse>()

        if (res.isSuccessful) {
            val doc = Jsoup.parse(res.text)
            val cards = doc.select("a.content-card")
            for (card in cards) {
                val searchResult = card.toSearchResult()
                if (searchResult != null) {
                    items.add(searchResult)
                }
            }
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href") ?: return null
        val urlVal = if (href.startsWith("http")) href else "$mainUrl$href"
        
        // Exclude movies
        val isTv = urlVal.contains("/dizi/") || urlVal.contains("/anime/")
        if (!isTv) return null

        val title = selectFirst(".cc-info strong")?.text()?.trim() ?: return null
        
        val styleAttr = selectFirst(".cc-bg")?.attr("style") ?: ""
        val posterUrl = Regex("""url\(['"]?([^'")]+)['"]?\)""").find(styleAttr)?.groupValues?.getOrNull(1)

        val rating = selectFirst(".cc-rating-inline")?.text()?.replace("★", "")?.trim()?.toDoubleOrNull()
        
        return newTvSeriesSearchResponse(title, urlVal, TvType.TvSeries) {
            this.posterUrl = posterUrl
            rating?.takeIf { it > 0.0 }?.let {
                this.score = Score.from10(it)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/pages/arama.php?q=$encodedQuery"
        val res = app.get(searchUrl, headers = defaultHeaders, cacheTime = 0)
        val items = mutableListOf<SearchResponse>()

        if (res.isSuccessful) {
            val doc = Jsoup.parse(res.text)
            val cards = doc.select("a.content-card")
            for (card in cards) {
                val searchResult = card.toSearchResult()
                if (searchResult != null) {
                    items.add(searchResult)
                }
            }
        }

        return items.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = defaultHeaders, cacheTime = 0)
        if (!res.isSuccessful) return null

        val doc = Jsoup.parse(res.text)

        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title().trim()
        val poster = doc.selectFirst(".detail-poster img")?.attr("src")
        val plot = doc.selectFirst("p.description")?.text()?.trim()

        val year = doc.select(".meta-badges .badge").firstOrNull { 
            it.text().contains("20") || it.text().contains("19") 
        }?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        val tags = doc.select("a.badge-cat").map { it.text().trim() }

        val episodes = doc.select(".episode-item").mapNotNull { el ->
            val href = el.attr("href") ?: return@mapNotNull null
            val epUrl = if (href.startsWith("http")) href else "$mainUrl$href"

            val epNumText = el.selectFirst(".ep-num")?.text() // e.g. "1x01"
            val parts = epNumText?.split("x")
            
            val seasonFromUrl = Regex("""sezon-(\d+)""", RegexOption.IGNORE_CASE).find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episodeFromUrl = Regex("""bolum-(\d+)""", RegexOption.IGNORE_CASE).find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val season = parts?.getOrNull(0)?.toIntOrNull() ?: seasonFromUrl
            val episode = parts?.getOrNull(1)?.toIntOrNull() ?: episodeFromUrl

            val epTitle = el.selectFirst("strong")?.text()?.trim() ?: "Bölüm ${episode ?: 1}"
            val epPoster = el.selectFirst(".ep-thumb img")?.attr("src") ?: poster

            newEpisode(epUrl) {
                this.name = epTitle
                this.season = season
                this.episode = episode
                this.posterUrl = epPoster
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val res = app.get(data, headers = defaultHeaders, cacheTime = 0)
            if (!res.isSuccessful) return false

            val doc = Jsoup.parse(res.text)
            val button = doc.selectFirst(".player-cover-btn")
            val pid = button?.attr("data-pid")
            val ts = button?.attr("data-ts")
            val sig = button?.attr("data-sig")

            if (pid.isNullOrBlank() || ts.isNullOrBlank() || sig.isNullOrBlank()) {
                return false
            }

            val tokenUrl = "$mainUrl/api/player-token.php?pid=$pid&_t=$ts&_s=${java.net.URLEncoder.encode(sig, "UTF-8")}"
            val tokenRes = app.get(
                tokenUrl,
                headers = mapOf(
                    "Referer" to data,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                ),
                cacheTime = 0
            )

            if (!tokenRes.isSuccessful) return false

            val json = JSONObject(tokenRes.text)
            val embedUrl = json.optString("url")
            if (embedUrl.isNullOrBlank()) return false

            val embedResponse = app.get(
                embedUrl,
                headers = mapOf(
                    "Referer" to data,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                ),
                cacheTime = 0
            )

            if (!embedResponse.isSuccessful) return false

            val embedDoc = Jsoup.parse(embedResponse.text)
            val subIframeSrc = embedDoc.selectFirst("iframe#embed-frame")?.attr("src")
                ?: embedDoc.selectFirst("iframe")?.attr("src")

            if (subIframeSrc.isNullOrBlank()) return false
            val subIframeUrl = if (subIframeSrc.startsWith("//")) {
                "https:$subIframeSrc"
            } else if (subIframeSrc.startsWith("/")) {
                val embedUri = java.net.URI(embedUrl)
                "${embedUri.scheme ?: "https"}://${embedUri.host}$subIframeSrc"
            } else {
                subIframeSrc
            }

            val subEmbedResponse = app.get(
                subIframeUrl,
                headers = mapOf(
                    "Referer" to embedUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                ),
                cacheTime = 0
            )
            if (!subEmbedResponse.isSuccessful) return false
            val subIframeHtml = subEmbedResponse.text

            val fileId = Regex("""cookie\(['"`]file_id['"`],\s*['"`]([^'"`]+)['"`]""").find(subIframeHtml)?.groupValues?.get(1) ?: "30192"
            val aff = Regex("""cookie\(['"`]aff['"`],\s*['"`]([^'"`]+)['"`]""").find(subIframeHtml)?.groupValues?.get(1) ?: "1"
            val refUrl = Regex("""cookie\(['"`]ref_url['"`],\s*['"`]([^'"`]+)['"`]""").find(subIframeHtml)?.groupValues?.get(1) ?: "play.liderfilm.cc"

            val fetchUrlPath = Regex("""fetch\(['"`]([^'"`]+)['"`]\)""").find(subIframeHtml)?.groupValues?.get(1) ?: return false

            val subIframeUri = java.net.URI(subIframeUrl)
            val subIframeHost = subIframeUri.host ?: "x.ag2m4.cfd"
            val subIframeScheme = subIframeUri.scheme ?: "https"

            val apiUrl = "$subIframeScheme://$subIframeHost$fetchUrlPath"

            val apiRes = app.get(
                apiUrl,
                headers = mapOf(
                    "Host" to subIframeHost,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Accept" to "*/*",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to subIframeUrl,
                    "Cookie" to "file_id=$fileId; aff=$aff; ref_url=$refUrl",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin"
                ),
                cacheTime = 0
            )

            if (!apiRes.isSuccessful) return false
            val apiJson = JSONObject(apiRes.text)
            val streamUrl = apiJson.optString("url")
            if (streamUrl.isNullOrBlank()) return false

            callback(
                newExtractorLink(
                    "LiderFilm",
                    "LiderFilm",
                    streamUrl,
                    type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    headers = getBrowserHeaders(subIframeUrl)
                }
            )

            // Extract subtitles
            try {
                val subtitleMatch = Regex(""""subtitle"\s*:\s*"([^"]+)"""").find(subIframeHtml)
                if (subtitleMatch != null) {
                    val subtitleRaw = subtitleMatch.groupValues[1]
                    val subs = subtitleRaw.split(",")
                    for (sub in subs) {
                        val label = sub.substringAfter("[").substringBefore("]")
                        val url = sub.substringAfter("]")
                        if (url.startsWith("http")) {
                            subtitleCallback(
                                newSubtitleFile(
                                    lang = label,
                                    url = url
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}

            true
        } catch (e: Exception) {
            false
        }
    }
}
