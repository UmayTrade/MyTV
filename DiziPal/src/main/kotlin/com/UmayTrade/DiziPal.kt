// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.UmayTrade

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziPalOriginal : MainAPI() {
    override var mainUrl              = "https://dizipal2134.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/bolumler"                     to "Son Bölümler",
        "${mainUrl}/diziler"                      to "Yeni Diziler",
        "${mainUrl}/filmler"                      to "Yeni Filmler",
        "${mainUrl}/platform/netflix"             to "Netflix",
        "${mainUrl}/platform/exxen"               to "Exxen",
        "${mainUrl}/platform/blutv"               to "BluTV",
        "${mainUrl}/platform/disney-plus"         to "Disney+",
        "${mainUrl}/platform/prime-video"         to "Amazon Prime",
        "${mainUrl}/platform/tabii"               to "Tabii",
        "${mainUrl}/platform/gain"                to "Gain",
        "${mainUrl}/platform/max"                 to "Max",
        "${mainUrl}/kategori/bilim-kurgu"         to "Bilimkurgu Filmleri",
        "${mainUrl}/kategori/komedi"              to "Komedi Filmleri",
        "${mainUrl}/kategori/belgesel"            to "Belgesel Filmleri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home     = if (request.data.contains("/bolumler")) {
            document.select("div.episodes-list-grid > a.episode-list-item").mapNotNull { it.sonBolumler() }
        } else {
            document.select("ul.content-grid > li").mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name      = this.selectFirst(".ep-title")?.text() ?: return null
        val episode   = this.selectFirst(".ep-info")?.text()?.trim()?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: return null
        val title     = "$name $episode"

        val href      = fixUrlNull(this.attr("href")) ?: return null
        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrlNull(imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") })

        val seriesUrl = href
            .replace(Regex("-\\d+-sezon-\\d+-bolum.*$"), "")
            .replace("/bolum/", "/dizi/")

        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title     = this.selectFirst("div.card-info h3")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/ajax-search?q=$query"

        val responseRaw = app.get(
            searchUrl,
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/"
        )

        val jsonResponse = AppUtils.parseJson<DizipalSearchData>(responseRaw.text)
        val searchResponses = mutableListOf<SearchResponse>()

        jsonResponse.results?.forEach { item ->
            val title = item.title ?: return@forEach
            val url = item.url ?: return@forEach
            val poster = item.poster

            if (item.type == "Dizi") {
                searchResponses.add(
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = item.year
                    }
                )
            } else {
                searchResponses.add(
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = item.year
                    }
                )
            }
        }

        return searchResponses
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/bolum/")) {
            val seriesUrl = url.replace("/bolum/", "/dizi/")
                .replace(Regex("-\\d+-sezon.*"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val year = document.selectFirst("div.info-row:contains(Yıl) span.info-value")?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("p.series-description")?.text()?.trim()
        val tags = document.select("div.info-row:contains(Kategoriler) span.info-value.categories a").map { it.text().trim() }
        val duration: Int? = null

        if (url.contains("/dizi/")) {
            val title = document.selectFirst("h1.series-title")?.text()?.trim() ?: return null

            val episodes = document.select("div.detail-episode-item-wrap").mapNotNull { wrap ->
                val anchor = wrap.selectFirst("a.detail-episode-item") ?: return@mapNotNull null
                val epHref = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val epName = anchor.selectFirst("div.detail-episode-title")?.text()?.trim() ?: return@mapNotNull null

                val subtitle = anchor.selectFirst("div.detail-episode-subtitle")?.text()?.trim() ?: ""
                val match = Regex("""(\d+)\.\s*[Ss]ezon\s*(\d+)\.\s*[Bb]ölüm""").find(subtitle)

                val epSeason = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                val epEpisode = match?.groupValues?.getOrNull(2)?.toIntOrNull()

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
                this.duration  = duration
            }
        } else {
            val title = document.selectFirst("h1.series-title, h1.movie-title")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
                ?: ""

            if (title.isEmpty()) return null

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.duration  = duration
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "Oynatılacak Bölüm Linki » $data")

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        val pageResponse = app.get(
            url = data,
            headers = mapOf(
                "User-Agent"    to userAgent,
                "Cache-Control" to "no-cache",
                "Pragma"        to "no-cache"
            )
        )

        val pageDocument = pageResponse.document
        val videoContainer = pageDocument.selectFirst("#videoContainer")

        if (videoContainer == null) {
            Log.e("DZP", "#videoContainer elementi bulunamadı!")
            return false
        }

        val configToken = videoContainer.attr("data-cfg").trim()

        if (configToken.isEmpty()) {
            Log.e("DZP", "data-cfg token'ı boş!")
            return false
        }

        Log.d("DZP", "Bulunan data-cfg Token » $configToken")

        val ajaxResponse = app.post(
            url = "$mainUrl/ajax",
            data = mapOf("cfg" to configToken),
            headers = mapOf(
                "User-Agent"        to userAgent,
                "Content-Type"      to "application/x-www-form-urlencoded",
                "X-Requested-With"  to "XMLHttpRequest",
                "Accept"            to "application/json, text/javascript, */*; q=0.01"
            ),
            referer = data
        )

        val ajaxText = ajaxResponse.text
        Log.d("DZP", "Ajax yanıtı » $ajaxText")

        val ajaxJson = try {
            AppUtils.parseJson<Map<String, Any>>(ajaxText)
        } catch (e: Exception) {
            Log.e("DZP", "Ajax yanıtı JSON olarak parse edilemedi: ${e.message}")
            return false
        }

        val success = ajaxJson["success"] as? Boolean ?: false
        if (!success) {
            Log.e("DZP", "Ajax yanıtı başarısız!")
            return false
        }

        @Suppress("UNCHECKED_CAST")
        val config = ajaxJson["config"] as? Map<String, Any> ?: run {
            Log.e("DZP", "Ajax yanıtında 'config' alanı yok!")
            return false
        }

        val videoUrl = config["v"] as? String
        val videoType = config["t"] as? String ?: "iframe"

        if (videoUrl.isNullOrEmpty()) {
            Log.e("DZP", "Config içinde video URL (v) bulunamadı!")
            return false
        }

        Log.d("DZP", "Çözülen Video URL » $videoUrl")
        Log.d("DZP", "Video Tipi » $videoType")

        return when {
            videoType == "m3u8" || videoType == "mp4" ||
            videoUrl.contains(".m3u8") || videoUrl.contains(".mp4") -> {

                val linkType = if (videoUrl.contains(".m3u8")) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Dizipal (Ana Sunucu)",
                        url = videoUrl,
                        type = linkType
                    ) {
                        referer = data
                        quality = Qualities.Unknown.value
                    }
                )
                true
            }

            videoUrl.contains("<iframe") -> {
                val iframeSrc = Regex("""src=["']([^"']+)["']""").find(videoUrl)?.groupValues?.getOrNull(1)
                if (iframeSrc != null) {
                    extractFromEmbed(fixUrl(iframeSrc), data, userAgent, subtitleCallback, callback)
                } else {
                    false
                }
            }

            videoType == "iframe" -> {
                extractFromEmbed(fixUrl(videoUrl), data, userAgent, subtitleCallback, callback)
            }

            else -> {
                extractFromEmbed(fixUrl(videoUrl), data, userAgent, subtitleCallback, callback)
            }
        }
    }

    private fun findM3u8Url(embedSource: String): String? {
        val m3u8Patterns = listOf(
            Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""["'](https?://[^"']+\.urlset/master\.m3u8[^"']*)["']"""),
            Regex("""v\s*:\s*["']([^"']+\.html[^"']*)["']""")
        )
        for (pattern in m3u8Patterns) {
            val match = pattern.find(embedSource)?.groupValues?.getOrNull(1)
            if (match != null) {
                Log.d("DZP", "Pattern eşleşti: $match")
                return match
            }
        }
        Log.e("DZP", "Embed içeriğinde m3u8 bulunamadı!")
        return null
    }

    private fun normalizeM3u8Url(rawUrl: String): String? {
        return when {
            rawUrl.contains(".m3u8") -> rawUrl

            rawUrl.contains(".html") -> {
                val idRegex = Regex("""embed-([^.]+)\.html""")
                val idMatch = idRegex.find(rawUrl)?.groupValues?.getOrNull(1)
                if (idMatch != null) {
                    "https://s8.superadjacentsoddenly.xyz/hls2/01/00009/${idMatch}_,n,h,.urlset/master.m3u8"
                } else {
                    Log.e("DZP", "HTML linkinden ID ayıklanamadı: $rawUrl")
                    null
                }
            }

            rawUrl.contains("hls2") -> rawUrl
            else -> rawUrl
        }
    }

    private suspend fun extractFromEmbed(
        embedUrl: String,
        referer: String,
        userAgent: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "Embed URL işleniyor » $embedUrl")

        if (embedUrl.contains("imagestoo")) {
            return handleImagestoo(embedUrl, userAgent, subtitleCallback, callback)
        }

        val embedSource: String = try {
            app.get(
                url = embedUrl,
                referer = referer,
                headers = mapOf("User-Agent" to userAgent)
            ).text
        } catch (e: Exception) {
            Log.e("DZP", "Embed sayfası alınamadı: ${e.message}")
            return false
        }

        Log.d("DZP", "Embed içerik uzunluğu: ${embedSource.length}")

        val rawUrl: String = findM3u8Url(embedSource) ?: return false
        val finalM3u8Url: String = normalizeM3u8Url(rawUrl) ?: return false

        Log.d("DZP", "Bulunan M3U8 » $finalM3u8Url")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Dizipal (Ana Sunucu)",
                url = finalM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                referer = embedUrl
                quality = Qualities.Unknown.value
            }
        )

        extractSubtitles(embedSource, subtitleCallback)
        return true
    }

    private fun extractSubtitles(
        embedSource: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        var found = false

        val tracksBlockMatch = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(embedSource)

        tracksBlockMatch?.groupValues?.getOrNull(1)?.let { tracksBlock ->
            val trackItemRegex = Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)

            trackItemRegex.findAll(tracksBlock).forEach { itemMatch ->
                val itemStr = itemMatch.groupValues[1]
                val fileMatch = Regex("""file\s*:\s*["']([^"']+)["']""").find(itemStr)
                val labelMatch = Regex("""label\s*:\s*["']([^"']+)["']""").find(itemStr)

                val fileUrl = fileMatch?.groupValues?.getOrNull(1)
                val label = labelMatch?.groupValues?.getOrNull(1) ?: "Unknown"

                if (fileUrl != null && (fileUrl.endsWith(".vtt") || fileUrl.endsWith(".srt"))) {
                    subtitleCallback.invoke(
                        SubtitleFile(lang = label, url = fixUrl(fileUrl))
                    )
                    found = true
                }
            }
        }

        if (!found) {
            val vttRegex = Regex("""["'](https?://[^"']+\.vtt[^"']*)["']""")
            vttRegex.findAll(embedSource).forEach { match ->
                val vttUrl = match.groupValues[1]
                val langMatch = Regex("""_([a-z]{2,3})\.vtt""").find(vttUrl)
                val langCode = langMatch?.groupValues?.getOrNull(1)

                val langText: String = if (langCode == "tur") {
                    "Türkçe"
                } else if (langCode == "eng") {
                    "English"
                } else {
                    langCode ?: "Unknown"
                }

                subtitleCallback.invoke(
                    SubtitleFile(lang = langText, url = fixUrl(vttUrl))
                )
            }
        }
    }

    private suspend fun handleImagestoo(
        embedUrl: String,
        userAgent: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = embedUrl.trimEnd('/').substringAfterLast("/")
        val imagestooApiUrl = "https://imagestoo.com/player/index.php?data=$videoId&do=getVideo"
        Log.d("DZP", "Imagestoo API URL » $imagestooApiUrl")

        val apiResponse = app.post(
            url = imagestooApiUrl,
            referer = embedUrl,
            headers = mapOf(
                "User-Agent" to userAgent,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "*/*"
            )
        )

        var sessionCookie = ""
        val playerToken = apiResponse.cookies["fireplayer_player"]

        if (!playerToken.isNullOrEmpty()) {
            sessionCookie = "fireplayer_player=$playerToken"
        } else {
            val rawSetCookie = apiResponse.headers["Set-Cookie"] ?: apiResponse.headers["set-cookie"]
            if (rawSetCookie != null && rawSetCookie.contains("fireplayer_player")) {
                val cleanCookie = rawSetCookie.split(";").firstOrNull()
                if (cleanCookie != null) {
                    sessionCookie = "$cleanCookie;"
                }
            }
        }

        Log.d("DZP", "Yakalanan Cookie » $sessionCookie")

        val responseText = apiResponse.text
        val videoSourceRaw = Regex(""""securedLink"\s*:\s*"([^"]+)"""").find(responseText)?.groupValues?.getOrNull(1)

        if (videoSourceRaw != null) {
            val cleanUrl = videoSourceRaw.replace("\\/", "/")
            val finalM3u8Url = fixUrl(cleanUrl)

            Log.d("DZP", "Imagestoo Çözülen Video Kaynağı » $finalM3u8Url")

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "Dizipal (Imagestoo)",
                    url = finalM3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = embedUrl
                    headers = mapOf("Cookie" to sessionCookie)
                    quality = Qualities.Unknown.value
                }
            )

            return true
        } else {
            Log.e("DZP", "Imagestoo API yanıtından videoSource çıkarılamadı! Yanıt: $apiResponse")
            return false
        }
    }
}
