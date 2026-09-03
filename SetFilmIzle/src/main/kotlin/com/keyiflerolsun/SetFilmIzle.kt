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
        val home = document.select("div.fgrid a.card-link").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val posterElement = this.selectFirst("div.poster-art img")
        val posterUrl = posterElement?.attr("src")?.let { fixUrlNull(it) }
            ?: posterElement?.attr("data-src")?.let { fixUrlNull(it) }
        
        val title = this.selectFirst("div.card-info h2.card-ad")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        
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
        val nonce = Regex("""nonce:\s*['"]([^'"]+)['"]""").find(mainPage.html())?.groupValues?.get(1) 
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

        val title = document.selectFirst("h1.fbox-title-tx")?.text()?.trim() 
            ?: document.selectFirst("h1")?.text()?.replace(" izle", "")?.trim() 
            ?: return null
        
        val poster = document.selectFirst("div.fbox-cover-img, div.poster-art img")?.attr("src")?.let { fixUrlNull(it) }
        
        val description = document.select("div.fbox-desc p, div.wp-content p").firstOrNull()?.text()?.trim()
        
        val year = document.select("div.fbox-info span:has(b:contains(Yıl)) a, div.fbox-date").firstOrNull()?.text()?.trim()?.toIntOrNull()
        
        val tags = document.select("div.fbox-info span:has(b:contains(Tür)) a").map { it.text().trim() }.filter { it.isNotEmpty() }
        
        val duration = document.select("div.fbox-info span:has(b:contains(Süre))").firstOrNull()?.text()?.replace("Süre:", "")?.trim()?.split(" ")?.first()?.toIntOrNull()
        
        val recommendations = document.select("div.fgrid a.card-link").mapNotNull { it.toRecommendationResult() }
        
        val actors = document.select("a.fk-k[href*='/oyuncu/']").mapNotNull { 
            val name = it.selectFirst("span.fk-t b")?.text()?.trim() ?: return@mapNotNull null
            Actor(name)
        }
        
        val trailer = document.select("button.fbtn-trailer").firstOrNull()?.attr("data-trailer")?.let { 
            "https://www.youtube.com/embed/$it" 
        }

        if (url.contains("/dizi/") || document.select("div#seasons, div#episodes").isNotEmpty()) {
            val episodes = document.select("div#episodes ul.episodios li, div#episodes div.episode-item").mapNotNull { episodeElement ->
                val epHref = fixUrlNull(episodeElement.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val epName = episodeElement.selectFirst("h4.episodiotitle a, .episode-title")?.text()?.trim() ?: "Bölüm"
                val epDetail = episodeElement.selectFirst("h4.episodiotitle a, .episode-title")?.text()?.trim() ?: ""
                
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
        val posterElement = this.selectFirst("div.poster-art img")
        val posterUrl = posterElement?.attr("src")?.let { fixUrlNull(it) }
            ?: posterElement?.attr("data-src")?.let { fixUrlNull(it) }
        
        val title = this.selectFirst("div.card-info h2.card-ad, h3.card-ad")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        
        val isSeries = href.contains("/dizi/") || this.selectFirst("div.card-info p.meta")?.text()?.contains("Sezon") == true

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("STF", "loadLinks data » $data")
        val document = app.get(data).document
        
        // YENİ: fplayer-parts içindeki oynatıcı butonlarını bul
        val playerButtons = document.select("div.fplayer-parts button.fsrc.src-tab")
        
        Log.d("STF", "Found ${playerButtons.size} player buttons")
        
        if (playerButtons.isEmpty()) {
            Log.d("STF", "No player buttons found, checking for iframes")
            val iframes = document.select("iframe[src*='setplay'], iframe[src*='fastplay'], iframe[src*='embed']")
            iframes.forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotEmpty()) {
                    Log.d("STF", "Direct iframe: $src")
                    loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                }
            }
            return true
        }

        // Nonce'yi window.STF_AJAX'dan al
        val nonce = Regex("""video:\s*['"]([^'"]+)['"]""").find(document.html())?.groupValues?.get(1)
            ?: Regex("""nonce['"]?\s*[:=]\s*['"]([^'"]+)['"]""").find(document.html())?.groupValues?.get(1)
            ?: ""
        
        Log.d("STF", "Using nonce: $nonce")
        
        // Post ID'yi bul
        val postId = document.select("span.stf-hit").firstOrNull()?.attr("data-id") 
            ?: document.select("div#stfPlayer").firstOrNull()?.attr("data-post-id")
            ?: Regex("""post-id['"]?\s*[:=]\s*['"]?(\d+)['"]?""").find(document.html())?.groupValues?.get(1)
            ?: ""
        
        Log.d("STF", "Post ID: $postId")

        playerButtons.forEach { button ->
            val playerName = button.attr("data-player-name") ?: button.text().trim()
            val partKey = button.attr("data-part-key") ?: ""
            
            if (playerName.isEmpty()) return@forEach
            
            Log.d("STF", "Processing player: $playerName, partKey: $partKey")
            
            try {
                val formData = mutableMapOf(
                    "action" to "get_video_url",
                    "post_id" to postId,
                    "player_name" to playerName
                )
                
                if (nonce.isNotEmpty()) {
                    formData["nonce"] = nonce
                }
                
                if (partKey.isNotEmpty()) {
                    formData["part_key"] = partKey
                }

                val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                    formData.forEach { (key, value) -> addFormDataPart(key, value) }
                }.build()

                val request = Request.Builder()
                    .url("${mainUrl}/wp-admin/admin-ajax.php")
                    .post(requestBody)
                    .addHeader("Referer", data)
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val client = OkHttpClient()
                val response = client.newCall(request).execute()
                val responseBody = response.body.string()
                
                Log.d("STF", "Response: $responseBody")
                
                val jsonResponse = JSONObject(responseBody)
                val success = jsonResponse.optBoolean("success", false)
                
                if (success) {
                    val dataObj = jsonResponse.optJSONObject("data")
                    val sourceIframe = dataObj?.optString("url") ?: ""
                    
                    if (sourceIframe.isNotEmpty()) {
                        Log.d("STF", "iframe url: $sourceIframe")
                        
                        val finalUrl = if (sourceIframe.contains("setplay") || sourceIframe.contains("fastplay")) {
                            if (partKey.isNotEmpty() && !sourceIframe.contains("?partKey=")) {
                                "$sourceIframe?partKey=$partKey"
                            } else {
                                sourceIframe
                            }
                        } else {
                            sourceIframe
                        }
                        
                        loadExtractor(finalUrl, "$mainUrl/", subtitleCallback, callback)
                    }
                } else {
                    val error = jsonResponse.optString("message", "Bilinmeyen hata")
                    Log.e("STF", "API error: $error")
                }
            } catch (e: Exception) {
                Log.e("STF", "Error processing player $playerName: ${e.message}")
            }
        }

        return true
    }
}
