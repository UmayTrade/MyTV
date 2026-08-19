// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class TurkAnime : MainAPI() {
    override var mainUrl = "https://www.turkanime.tv"
    override var name = "TurkAnime"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "${mainUrl}/anime-turu/1/Aksiyon" to "Aksiyon",
        "${mainUrl}/anime-turu/3/Arabalar" to "Arabalar",
        "${mainUrl}/anime-turu/38/Askeri" to "Askeri",
        "${mainUrl}/anime-turu/5/Avangard" to "Avangard",
        "${mainUrl}/anime-turu/24/Bilim_Kurgu" to "Bilim Kurgu",
        "${mainUrl}/anime-turu/16/B%C3%BCy%C3%BC" to "Büyü",
        "${mainUrl}/anime-turu/15/%C3%87ocuklar" to "Çocuklar",
        "${mainUrl}/anime-turu/37/Do%C4%9Fa%C3%BCst%C3%BC_G%C3%BC%C3%A7ler" to "Doğaüstü Güçler",
        "${mainUrl}/anime-turu/17/D%C3%B6v%C3%BC%C5%9F_Sanatlar%C4%B1" to "Dövüş Sanatları",
        "${mainUrl}/anime-turu/8/Dram" to "Dram",
        "${mainUrl}/anime-turu/9/Ecchi" to "Ecchi",
        "${mainUrl}/anime-turu/10/Fantastik" to "Fantastik",
        "${mainUrl}/anime-turu/41/Gerilim" to "Gerilim",
        "${mainUrl}/anime-turu/7/Gizem" to "Gizem",
        "${mainUrl}/anime-turu/35/Harem" to "Harem",
        "${mainUrl}/anime-turu/43/Josei" to "Josei",
        "${mainUrl}/anime-turu/4/Komedi" to "Komedi",
        "${mainUrl}/anime-turu/14/Korku" to "Korku",
        "${mainUrl}/anime-turu/2/Macera" to "Macera",
        "${mainUrl}/anime-turu/18/Mecha" to "Mecha",
        "${mainUrl}/anime-turu/19/M%C3%BCzik" to "Müzik",
        "${mainUrl}/anime-turu/23/Okul" to "Okul",
        "${mainUrl}/anime-turu/11/Oyun" to "Oyun",
        "${mainUrl}/anime-turu/20/Parodi" to "Parodi",
        "${mainUrl}/anime-turu/39/Polisiye" to "Polisiye",
        "${mainUrl}/anime-turu/40/Psikolojik" to "Psikolojik",
        "${mainUrl}/anime-turu/22/Romantizm" to "Romantizm",
        "${mainUrl}/anime-turu/21/Samuray" to "Samuray",
        "${mainUrl}/anime-turu/42/Seinen" to "Seinen",
        "${mainUrl}/anime-turu/6/%C5%9Eeytanlar" to "Şeytanlar",
        "${mainUrl}/anime-turu/25/Shoujo" to "Shoujo",
        "${mainUrl}/anime-turu/26/Shoujo_Ai" to "Shoujo Ai",
        "${mainUrl}/anime-turu/27/Shounen" to "Shounen",
        "${mainUrl}/anime-turu/28/Shounen_Ai" to "Shounen Ai",
        "${mainUrl}/anime-turu/30/Spor" to "Spor",
        "${mainUrl}/anime-turu/31/S%C3%BCper_G%C3%BC%C3%A7ler" to "Süper Güçler",
        "${mainUrl}/anime-turu/13/Tarihi" to "Tarihi",
        "${mainUrl}/anime-turu/29/Uzay" to "Uzay",
        "${mainUrl}/anime-turu/32/Vampir" to "Vampir",
        "${mainUrl}/anime-turu/33/Yaoi" to "Yaoi",
        "${mainUrl}/anime-turu/36/Ya%C5%9Famdan_Kesitler" to "Yaşamdan Kesitler",
        "${mainUrl}/anime-turu/34/Yuri" to "Yuri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("div#orta-icerik div.panel, div.anime-card, div.anime-item, div.card").mapNotNull {
            it.toMainPageResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        var titleEl = this.selectFirst("div.panel-title a, h3 a, h4 a, a.anime-link, a.title-link, a[href*='/anime-']")
        
        val title = titleEl?.text()?.trim() ?: return null
        val href = fixUrlNull(titleEl?.attr("href")) ?: return null
        
        var posterUrl = this.selectFirst("img")?.attr("data-src")
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = this.selectFirst("img")?.attr("src")
        }
        posterUrl = fixUrlNull(posterUrl)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val document = app.post("${mainUrl}/arama", data = mapOf("arama" to query)).document
            return document.select("div#orta-icerik div.panel, div.anime-item").mapNotNull {
                it.toMainPageResult()
            }
        } catch (e: Exception) {
            Log.e("TurkAnime", "Search error: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = app.get(url).document

            var title = document.selectFirst("div#detayPaylas div.panel-title, h1, div.title, .anime-title")?.text()?.trim()
            if (title.isNullOrEmpty()) {
                title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            }
            if (title.isNullOrEmpty()) return null

            val poster = fixUrlNull(
                document.selectFirst("div#detayPaylas div.imaj img, div.poster img, meta[property='og:image']")?.attr("data-src")
                    ?: document.selectFirst("div#detayPaylas div.imaj img, div.poster img")?.attr("src")
                    ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            )

            val description = document.selectFirst("div#detayPaylas p.ozet, div.description, meta[name='description']")?.text()?.trim()
            val year = document.selectFirst("a[href*='yil/']")?.attr("href")?.substringAfter("yil/")?.toIntOrNull()
            val tags = document.select("a[href*='anime-turu']").map { it.text() }

            val episodes = getEpisodes(document)

            if (episodes.isEmpty()) return null

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }
        } catch (e: Exception) {
            Log.e("TurkAnime", "Load error: ${e.message}")
            return null
        }
    }

    private suspend fun getEpisodes(document: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // 1. AJAX endpoint'inden bölümleri al
        val bolumlerUrl = document.selectFirst("a[data-url*='bolumler'], div#bolumler a[data-url]")?.attr("data-url")

        if (bolumlerUrl != null) {
            try {
                val token = document.selectFirst("meta[name='_token']")?.attr("content")
                    ?: document.selectFirst("input[name='_token']")?.attr("value")
                    ?: ""

                Log.d("TurkAnime", "Fetching episodes from: $bolumlerUrl")

                val response = app.get(
                    fixUrlNull(bolumlerUrl) ?: return emptyList(),
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json, text/html, */*",
                        "token" to token
                    ),
                    cookies = mapOf("yasOnay" to "1")
                )

                val responseDoc = response.document

                val episodeElements = responseDoc.select("li a[href*='/video/'], div.bolum-item a[href*='/video/'], tr a[href*='/video/']")

                for (element in episodeElements) {
                    val href = fixUrlNull(element.attr("href")) ?: continue
                    val name = element.select("span.bolumAdi, .bolum-adi, .episode-name").text().trim()
                        .ifEmpty { element.text().trim() }
                        .ifEmpty { "Bölüm" }

                    val episodeNum = Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                    episodes.add(
                        newEpisode(href) {
                            this.name = name
                            this.season = 1
                            this.episode = episodeNum
                        }
                    )
                }

                if (episodes.isNotEmpty()) {
                    return episodes.sortedBy { it.episode }
                }
            } catch (e: Exception) {
                Log.e("TurkAnime", "AJAX episode error: ${e.message}")
            }
        }

        // 2. Sayfadaki bölüm linklerini al
        val episodeLinks = document.select("a[href*='/video/']")
        for (link in episodeLinks) {
            val href = fixUrlNull(link.attr("href")) ?: continue
            val name = link.text().trim().ifEmpty { "Bölüm" }
            val episodeNum = Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            episodes.add(
                newEpisode(href) {
                    this.name = name
                    this.season = 1
                    this.episode = episodeNum
                }
            )
        }

        return episodes.sortedBy { it.episode }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TurkAnime", "=== loadLinks: $data ===")

        try {
            // Sayfayı yükle
            val document = app.get(data, cookies = mapOf("yasOnay" to "1")).document

            // 1. ArtPlayer kontrolü (site artplayer kullanıyor)
            val artPlayer = document.selectFirst("div.artplayer-app")
            if (artPlayer != null) {
                Log.d("TurkAnime", "ArtPlayer found")

                // data-url'den link al
                val dataUrl = artPlayer.attr("data-url")
                if (dataUrl.isNotEmpty()) {
                    Log.d("TurkAnime", "ArtPlayer data-url: $dataUrl")
                    if (dataUrl.contains("m3u8") || dataUrl.contains(".mp4")) {
                        createExtractorLink(dataUrl, data, "ArtPlayer", callback)
                        return true
                    }
                }

                // data-video'dan link al
                val dataVideo = artPlayer.attr("data-video")
                if (dataVideo.isNotEmpty()) {
                    Log.d("TurkAnime", "ArtPlayer data-video: $dataVideo")
                    if (dataVideo.contains("m3u8") || dataVideo.contains(".mp4")) {
                        createExtractorLink(dataVideo, data, "ArtPlayer", callback)
                        return true
                    }
                }

                // id'den link bul
                val playerId = artPlayer.id()
                if (playerId.isNotEmpty()) {
                    Log.d("TurkAnime", "ArtPlayer id: $playerId")
                    val scripts = document.select("script")
                    for (script in scripts) {
                        val scriptData = script.data()
                        if (scriptData.contains(playerId) && (scriptData.contains("m3u8") || scriptData.contains(".mp4"))) {
                            val urlMatch = Regex("""(https?://[^\s'\"]+\.m3u8[^\s'\"]*)""").find(scriptData)
                            if (urlMatch != null) {
                                val foundUrl = urlMatch.groupValues[1]
                                Log.d("TurkAnime", "Found URL in script: $foundUrl")
                                createExtractorLink(foundUrl, data, "Script", callback)
                                return true
                            }
                        }
                    }
                }
            }

            // 2. Video elementini kontrol et
            val videoElement = document.selectFirst("video")
            if (videoElement != null) {
                Log.d("TurkAnime", "Video element found")

                val sources = videoElement.select("source")
                for (source in sources) {
                    val src = source.attr("src")
                    if (src.isNotEmpty()) {
                        Log.d("TurkAnime", "Video source: $src")
                        if (src.contains("m3u8") || src.contains(".mp4")) {
                            createExtractorLink(src, data, "Video", callback)
                            return true
                        }
                    }
                }
            }

            // 3. iframe'leri kontrol et
            val iframes = document.select("iframe")
            Log.d("TurkAnime", "Found ${iframes.size} iframes")

            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.isNotEmpty() && !src.contains("a-ads.com") && !src.contains("google.com")) {
                    Log.d("TurkAnime", "Processing iframe: $src")

                    try {
                        val iframeDoc = app.get(src, cookies = mapOf("yasOnay" to "1")).document

                        // iframe içinde ArtPlayer
                        val iframeArtPlayer = iframeDoc.selectFirst("div.artplayer-app")
                        if (iframeArtPlayer != null) {
                            val dataUrl = iframeArtPlayer.attr("data-url")
                            if (dataUrl.isNotEmpty() && (dataUrl.contains("m3u8") || dataUrl.contains(".mp4"))) {
                                Log.d("TurkAnime", "Iframe ArtPlayer: $dataUrl")
                                createExtractorLink(dataUrl, src, "iframe", callback)
                                return true
                            }
                        }

                        // iframe içinde video
                        val iframeVideo = iframeDoc.selectFirst("video source")
                        if (iframeVideo != null) {
                            val videoUrl = iframeVideo.attr("src")
                            if (videoUrl.isNotEmpty() && (videoUrl.contains("m3u8") || videoUrl.contains(".mp4"))) {
                                Log.d("TurkAnime", "Iframe video: $videoUrl")
                                createExtractorLink(videoUrl, src, "iframe", callback)
                                return true
                            }
                        }

                        // iframe içindeki script'ler
                        val iframeScripts = iframeDoc.select("script")
                        for (script in iframeScripts) {
                            val scriptData = script.data()
                            if (scriptData.contains("m3u8") || scriptData.contains(".mp4")) {
                                val urlMatch = Regex("""(https?://[^\s'\"]+\.m3u8[^\s'\"]*)""").find(scriptData)
                                if (urlMatch != null) {
                                    val foundUrl = urlMatch.groupValues[1]
                                    Log.d("TurkAnime", "Found URL in iframe script: $foundUrl")
                                    createExtractorLink(foundUrl, src, "Script", callback)
                                    return true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TurkAnime", "iframe load error: ${e.message}")
                    }
                }
            }

            // 4. Butonlardan dene (AJAX ile video getiren butonlar)
            val buttons = document.select("button[onclick*='IndexIcerik'], button[data-url], button[onclick*='video']")
            Log.d("TurkAnime", "Found ${buttons.size} buttons")

            for (button in buttons) {
                val onclick = button.attr("onclick")
                var link = onclick.substringAfter("IndexIcerik('").substringBefore("'")
                    .takeIf { it.isNotBlank() }

                if (link == null) {
                    link = button.attr("data-url")
                }
                if (link == null) {
                    link = button.attr("data-video")
                }

                if (link != null) {
                    val fullLink = fixUrlNull(link) ?: continue
                    Log.d("TurkAnime", "Button link: $fullLink")

                    try {
                        val response = app.get(
                            fullLink,
                            headers = mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Accept" to "application/json, text/html, */*"
                            )
                        )

                        val responseText = response.text
                        Log.d("TurkAnime", "Response length: ${responseText.length}")

                        // JSON yanıtı kontrol et
                        try {
                            val json = JSONObject(responseText)
                            if (json.has("video")) {
                                val videoUrl = json.getString("video")
                                if (videoUrl.isNotEmpty() && (videoUrl.contains("m3u8") || videoUrl.contains(".mp4"))) {
                                    Log.d("TurkAnime", "JSON video: $videoUrl")
                                    createExtractorLink(videoUrl, fullLink, button.text().trim(), callback)
                                    return true
                                }
                            }
                            if (json.has("url")) {
                                val videoUrl = json.getString("url")
                                if (videoUrl.isNotEmpty() && (videoUrl.contains("m3u8") || videoUrl.contains(".mp4"))) {
                                    Log.d("TurkAnime", "JSON url: $videoUrl")
                                    createExtractorLink(videoUrl, fullLink, button.text().trim(), callback)
                                    return true
                                }
                            }
                            if (json.has("source")) {
                                val videoUrl = json.getString("source")
                                if (videoUrl.isNotEmpty() && (videoUrl.contains("m3u8") || videoUrl.contains(".mp4"))) {
                                    Log.d("TurkAnime", "JSON source: $videoUrl")
                                    createExtractorLink(videoUrl, fullLink, button.text().trim(), callback)
                                    return true
                                }
                            }
                            if (json.has("file")) {
                                val videoUrl = json.getString("file")
                                if (videoUrl.isNotEmpty() && (videoUrl.contains("m3u8") || videoUrl.contains(".mp4"))) {
                                    Log.d("TurkAnime", "JSON file: $videoUrl")
                                    createExtractorLink(videoUrl, fullLink, button.text().trim(), callback)
                                    return true
                                }
                            }
                        } catch (e: Exception) {
                            // JSON değil, HTML
                            val responseDoc = response.document

                            val respVideo = responseDoc.selectFirst("video source")
                            if (respVideo != null) {
                                val videoUrl = respVideo.attr("src")
                                if (videoUrl.isNotEmpty() && (videoUrl.contains("m3u8") || videoUrl.contains(".mp4"))) {
                                    Log.d("TurkAnime", "Button response video: $videoUrl")
                                    createExtractorLink(videoUrl, fullLink, button.text().trim(), callback)
                                    return true
                                }
                            }

                            val respArtPlayer = responseDoc.selectFirst("div.artplayer-app")
                            if (respArtPlayer != null) {
                                val dataUrl = respArtPlayer.attr("data-url")
                                if (dataUrl.isNotEmpty() && (dataUrl.contains("m3u8") || dataUrl.contains(".mp4"))) {
                                    Log.d("TurkAnime", "Button response ArtPlayer: $dataUrl")
                                    createExtractorLink(dataUrl, fullLink, button.text().trim(), callback)
                                    return true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TurkAnime", "Button request error: ${e.message}")
                    }
                }
            }

            // 5. Script'lerden M3U8 linklerini ara
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptData = script.data()
                if (scriptData.contains("m3u8") || scriptData.contains(".mp4")) {
                    val urlMatch = Regex("""(https?://[^\s'\"]+\.m3u8[^\s'\"]*)""").find(scriptData)
                    if (urlMatch != null) {
                        val foundUrl = urlMatch.groupValues[1]
                        Log.d("TurkAnime", "Found URL in script: $foundUrl")
                        createExtractorLink(foundUrl, data, "Script", callback)
                        return true
                    }
                }
            }

            Log.w("TurkAnime", "No video links found!")

        } catch (e: Exception) {
            Log.e("TurkAnime", "loadLinks error: ${e.message}")
            e.printStackTrace()
            return false
        }

        return true
    }

    private suspend fun createExtractorLink(
        url: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ) {
        var cleanUrl = url.trim()
        cleanUrl = cleanUrl.replace(Regex("""[\n\r\t]"""), "")

        Log.d("TurkAnime", "Creating link: $cleanUrl")

        val link = newExtractorLink(
            source = this.name,
            name = "$name - TurkAnime",
            url = cleanUrl,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                "Connection" to "keep-alive",
                "Origin" to mainUrl
            )
        }
        callback(link)
    }
}
