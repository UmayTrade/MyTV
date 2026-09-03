// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.json.JSONObject
import org.jsoup.Jsoup
import okhttp3.*

class SetFilmIzle : MainAPI() {
    override var mainUrl              = "https://www.setfilmizle.ltd"
    override var name                 = "SetFilmIzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile/"        to "Aile",
        "${mainUrl}/tur/aksiyon/"     to "Aksiyon",
        "${mainUrl}/tur/animasyon/"   to "Animasyon",
        "${mainUrl}/tur/belgesel/"    to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim-Kurgu",
        "${mainUrl}/tur/biyografi/"   to "Biyografi",
        "${mainUrl}/tur/dini/"        to "Dini",
        "${mainUrl}/tur/dram/"        to "Dram",
        "${mainUrl}/tur/fantastik/"   to "Fantastik",
        "${mainUrl}/tur/genclik/"     to "Gençlik",
        "${mainUrl}/tur/gerilim/"     to "Gerilim",
        "${mainUrl}/tur/gizem/"       to "Gizem",
        "${mainUrl}/tur/komedi/"      to "Komedi",
        "${mainUrl}/tur/korku/"       to "Korku",
        "${mainUrl}/tur/macera/"      to "Macera",
        "${mainUrl}/tur/mini-dizi/"   to "Mini Dizi",
        "${mainUrl}/tur/muzik/"       to "Müzik",
        "${mainUrl}/tur/program/"     to "Program",
        "${mainUrl}/tur/romantik/"    to "Romantik",
        "${mainUrl}/tur/savas/"       to "Savaş",
        "${mainUrl}/tur/spor/"        to "Spor",
        "${mainUrl}/tur/suc/"         to "Suç",
        "${mainUrl}/tur/tarih/"       to "Tarih",
        "${mainUrl}/tur/western/"     to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        // Güncel site yapısında "fgrid" sınıfı içindeki "card-link" ler kullanılıyor
        val home = document.select("div.fgrid a.card-link").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // Güncel poster yapısı
        val posterElement = this.selectFirst("div.poster-art img")
        val posterUrl = posterElement?.attr("src")?.let { fixUrlNull(it) }
            ?: posterElement?.attr("data-src")?.let { fixUrlNull(it) }
        
        val title = this.selectFirst("div.card-info h2.card-ad")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        
        // Tür bilgisini al
        val meta = this.selectFirst("div.card-info p.meta")?.text()?.trim() ?: ""
        val isSeries = href.contains("/dizi/") || meta.contains("Sezon")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { 
                this.posterUrl = posterUrl
                this.year = meta.substringBefore("·").trim().toIntOrNull()
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { 
                this.posterUrl = posterUrl
                this.year = meta.substringBefore("·").trim().toIntOrNull()
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val mainPage = app.get(mainUrl).document
        val nonce = Regex("""nonce: ['"]([^'"]+)['"]""").find(mainPage.html())?.groupValues?.get(1) 
            ?: Regex(""""nonce":"([^"]+)"""").find(mainPage.html())?.groupValues?.get(1)
            ?: ""

        val search = app.post(
            url = "${mainUrl}/wp-admin/admin-ajax.php",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded"
            ),
            data = mapOf(
                "action" to "ajax_search",
                "nonce" to nonce,
                "search" to query
            )
        )
        
        val jsonResponse = JSONObject(search.text)
        val html = jsonResponse.optString("html", "")
        val document = Jsoup.parse(html)

        return document.select("div.items article, div.fgrid a.card-link").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val posterElement = this.selectFirst("div.poster-art img")
        val posterUrl = posterElement?.attr("src")?.let { fixUrlNull(it) }
            ?: posterElement?.attr("data-src")?.let { fixUrlNull(it) }
        
        val title = this.selectFirst("h2.card-ad, h2")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        
        val isSeries = href.contains("/dizi/") || this.selectFirst("div.card-info p.meta")?.text()?.contains("Sezon") == true

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Başlık - " izle" kısmını temizle
        val title = document.selectFirst("h1")?.text()?.replace(" izle", "")?.trim() ?: return null
        
        // Poster
        val poster = document.selectFirst("div.poster-art img")?.attr("src")?.let { fixUrlNull(it) }
            ?: document.selectFirst("div.poster img")?.attr("src")?.let { fixUrlNull(it) }
        
        // Açıklama - wp-content içindeki p etiketleri
        val description = document.select("div.wp-content p, div#info p.description, div.detay p").firstOrNull()?.text()?.trim()
        
        // Yıl
        var year = document.select("div.extra span.C a, a[href*='/yil/']").firstOrNull()?.text()?.trim()?.toIntOrNull()
        if (year == null) {
            year = document.select("div.card-info p.meta").firstOrNull()?.text()?.substringBefore("·")?.trim()?.toIntOrNull()
        }
        
        // Türler
        val tags = document.select("div.sgeneros a, div.tags a, div.genre a").map { it.text().trim() }.filter { it.isNotEmpty() }
        
        // Süre
        var duration = document.select("span.runtime, span.duration").firstOrNull()?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
        if (duration == null) {
            duration = document.select("div#info span:containsOwn(Dakika)").firstOrNull()?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
        }
        
        // Öneriler
        val recommendations = document.select("div.srelacionados article, div.fgrid a.card-link").mapNotNull { it.toRecommendationResult() }
        
        // Oyuncular
        val actors = document.select("span.valor a, div.cast a, div.actors a").map { Actor(it.text().trim()) }
        
        // Trailer
        val trailer = Regex("""embed/([^"?]+)""").find(document.html())?.groupValues?.get(1)?.let { 
            "https://www.youtube.com/embed/$it" 
        }

        if (url.contains("/dizi/") || document.select("div#seasons, div#episodes").isNotEmpty()) {
            // Bölümleri topla
            val episodes = document.select("div#episodes ul.episodios li, div#episodes div.episode-item, div.episode-list li").mapNotNull { episodeElement ->
                val epHref = fixUrlNull(episodeElement.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val epName = episodeElement.selectFirst("h4.episodiotitle a, .episode-title a, .bolum-ad")?.text()?.trim() ?: "Bölüm"
                val epDetail = episodeElement.selectFirst("h4.episodiotitle a, .episode-title, .bolum-detay")?.text()?.trim() ?: ""
                
                val seasonMatch = Regex("(\\d+)\\.? Sezon").find(epDetail)
                val episodeMatch = Regex("Sezon (?:\\d+)?\\.? ?(\\d+)\\.? Bölüm").find(epDetail)
                
                newEpisode(epHref) {
                    this.name = epName
                    this.season = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
                    this.episode = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.duration = duration
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val posterElement = this.selectFirst("a img, div.poster-art img")
        val posterUrl = posterElement?.attr("src")?.let { fixUrlNull(it) }
            ?: posterElement?.attr("data-src")?.let { fixUrlNull(it) }
        
        val title = this.selectFirst("a img")?.attr("alt")?.trim()
            ?: this.selectFirst("div.card-info h2.card-ad")?.text()?.trim()
            ?: this.selectFirst("h2")?.text()?.trim()
            ?: return null
            
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        
        val isSeries = href.contains("/dizi/") || this.selectFirst("div.card-info p.meta")?.text()?.contains("Sezon") == true

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    private fun sendMultipartRequest(nonce: String, postId: String, playerName: String, partKey: String, referer: String): Response {
        val formData = mapOf(
            "action" to "get_video_url",
            "nonce" to nonce,
            "post_id" to postId,
            "player_name" to playerName,
            "part_key" to partKey
        )

        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            formData.forEach { (key, value) -> addFormDataPart(key, value) }
        }.build()

        val headers = mapOf(
            "Referer" to referer,
            "X-Requested-With" to "XMLHttpRequest"
        )

        val request = Request.Builder().url("${mainUrl}/wp-admin/admin-ajax.php").post(requestBody).apply {
            headers.forEach { (key, value) -> addHeader(key, value) }
        }.build()

        val client = OkHttpClient()

        return client.newCall(request).execute()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("STF", "loadLinks data » $data")
        val document = app.get(data).document

        // Video oynatıcı seçeneklerini bul
        val playerElements = document.select("nav.player a, div.player-options a, div.video-sources a")
        
        if (playerElements.isEmpty()) {
            // Alternatif: doğrudan video URL'leri
            val directVideos = document.select("iframe[src*='setplay'], iframe[src*='fastplay'], iframe[src*='embed']")
            directVideos.forEach { iframe ->
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.isNotEmpty()) {
                    loadExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback)
                }
            }
            return true
        }

        playerElements.forEach { element ->
            val sourceId = element.attr("data-post-id")
            val name = element.attr("data-player-name")
            val partKey = element.attr("data-part-key").takeIf { it.isNotEmpty() }

            if (sourceId.isEmpty() || sourceId.contains("event")) return@forEach

            val nonce = document.selectFirst("div#playex")?.attr("data-nonce") 
                ?: document.selectFirst("script:containsData(get_video_url)")?.html()?.let { html ->
                    Regex("""nonce:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                } ?: ""

            try {
                val multiPart = sendMultipartRequest(nonce, sourceId, name, partKey ?: "", data)
                val sourceBody = multiPart.body.string()
                val jsonResponse = JSONObject(sourceBody)
                val sourceIframe = jsonResponse.optJSONObject("data")?.optString("url") ?: return@forEach

                Log.d("STF", "iframe » $sourceIframe")

                val finalUrl = if (sourceIframe.contains("setplay") || sourceIframe.contains("fastplay")) {
                    if (partKey != null && !sourceIframe.contains("?partKey=")) {
                        "$sourceIframe?partKey=$partKey"
                    } else {
                        sourceIframe
                    }
                } else {
                    sourceIframe
                }

                loadExtractor(finalUrl, "$mainUrl/", subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("STF", "Video URL alınırken hata: ${e.message}")
            }
        }

        return true
    }
}
