// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class SetFilmIzle : MainAPI() {

    // ==================================================================
    // ==========   1) ANA DEĞİŞKENLER (ÖNCE BUNLAR)         ============
    // ==================================================================

    override var mainUrl = "https://www.setfilmizle.ltd"
    override var name = "SetFilmIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ==================================================================
    // ==========   2) LOG AYARLARI                         ============
    // ==================================================================
    private val LOG_AKTIF = true
    private val TAG = "SETFILMIZLE"
    private val PREFIX = ">>> SETFILMIZLE >>> "

    private fun log(mesaj: String) {
        if (LOG_AKTIF) Log.d(TAG, "$PREFIX$mesaj")
    }

    private fun logE(mesaj: String, e: Throwable? = null) {
        if (e != null) {
            Log.e(TAG, "$PREFIX[HATA] $mesaj", e)
        } else {
            Log.e(TAG, "$PREFIX[HATA] $mesaj")
        }
    }

    private fun logW(mesaj: String) {
        Log.w(TAG, "$PREFIX[UYARI] $mesaj")
    }

    private fun logBaslik(baslik: String) {
        if (LOG_AKTIF) {
            Log.d(TAG, "$PREFIX========================================")
            Log.d(TAG, "$PREFIX=== $baslik")
            Log.d(TAG, "$PREFIX========================================")
        }
    }

    // ==================================================================
    // ==========   3) EKLENTİ YÜKLENDİĞİNDE ÇALIŞIR         ============
    // ==================================================================
    init {
        Log.e(TAG, "$PREFIX########################################")
        Log.e(TAG, "$PREFIX###  EKLENTİ YÜKLENDİ!                ###")
        Log.e(TAG, "$PREFIX###  Sınıf: SetFilmIzle              ###")
        Log.e(TAG, "$PREFIX###  mainUrl: $mainUrl")
        Log.e(TAG, "$PREFIX###  name: $name")
        Log.e(TAG, "$PREFIX###  lang: $lang")
        Log.e(TAG, "$PREFIX########################################")
    }

    // ==================================================================
    // ==========   4) ANA SAYFA KATEGORİLERİ                ============
    // ==================================================================

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile/" to "Aile",
        "${mainUrl}/tur/aksiyon/" to "Aksiyon",
        "${mainUrl}/tur/animasyon/" to "Animasyon",
        "${mainUrl}/tur/belgesel/" to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim-Kurgu",
        "${mainUrl}/tur/biyografi/" to "Biyografi",
        "${mainUrl}/tur/dini/" to "Dini",
        "${mainUrl}/tur/dram/" to "Dram",
        "${mainUrl}/tur/fantastik/" to "Fantastik",
        "${mainUrl}/tur/genclik/" to "Gençlik",
        "${mainUrl}/tur/gerilim/" to "Gerilim",
        "${mainUrl}/tur/gizem/" to "Gizem",
        "${mainUrl}/tur/komedi/" to "Komedi",
        "${mainUrl}/tur/korku/" to "Korku",
        "${mainUrl}/tur/macera/" to "Macera",
        "${mainUrl}/tur/mini-dizi/" to "Mini Dizi",
        "${mainUrl}/tur/muzik/" to "Müzik",
        "${mainUrl}/tur/program/" to "Program",
        "${mainUrl}/tur/romantik/" to "Romantik",
        "${mainUrl}/tur/savas/" to "Savaş",
        "${mainUrl}/tur/spor/" to "Spor",
        "${mainUrl}/tur/suc/" to "Suç",
        "${mainUrl}/tur/tarih/" to "Tarih",
        "${mainUrl}/tur/western/" to "Western"
    )

    // ==================================================================
    // ==========   5) ANA SAYFA                            ============
    // ==================================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        logBaslik("getMainPage() BAŞLADI")
        log("page = $page")
        log("request.name = ${request.name}")
        log("request.data = ${request.data}")

        return try {
            // Sayfa URL'sini oluştur (pagination desteği)
            val pageUrl = if (page > 1) "${request.data}page/$page/" else request.data
            log("Kullanılacak URL = $pageUrl")

            val document = app.get(pageUrl).document
            log("document.title = ${document.title()}")
            log("HTML uzunluğu = ${document.html().length}")

            // -------- SELECTOR DENEMELERİ --------
            // Sitenin yapısı değişmiş olabilir, çeşitli selector'ları deniyoruz
            val articles = when {
                document.select("div.items article").isNotEmpty() -> {
                    log("Selector kullanıldı: div.items article")
                    document.select("div.items article")
                }
                document.select("div.items > article").isNotEmpty() -> {
                    log("Selector kullanıldı: div.items > article")
                    document.select("div.items > article")
                }
                document.select("article.item").isNotEmpty() -> {
                    log("Selector kullanıldı: article.item")
                    document.select("article.item")
                }
                document.select("div.movies-list article").isNotEmpty() -> {
                    log("Selector kullanıldı: div.movies-list article")
                    document.select("div.movies-list article")
                }
                document.select("div.movie-box").isNotEmpty() -> {
                    log("Selector kullanıldı: div.movie-box")
                    document.select("div.movie-box")
                }
                else -> {
                    logW("Bilinen selector çalışmadı! Fallback: tüm article'lar")
                    document.select("article")
                }
            }

            log("Bulunan 'article' sayısı = ${articles.size}")

            val home = articles.mapNotNull { it.toMainPageResult() }
            log("Başarıyla dönüştürülen öğe sayısı = ${home.size}")

            logBaslik("getMainPage() BİTTİ")
            newHomePageResponse(request.name, home)

        } catch (e: Exception) {
            logE("getMainPage() İSTİSNA: ${e.message}", e)
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // -------- BAŞLIK --------
        val title = when {
            this.selectFirst("h2") != null -> this.selectFirst("h2")!!.text()
            this.selectFirst("h3") != null -> this.selectFirst("h3")!!.text()
            this.selectFirst("div.title a") != null -> this.selectFirst("div.title a")!!.text()
            this.selectFirst("a.title") != null -> this.selectFirst("a.title")!!.text()
            this.selectFirst("h4") != null -> this.selectFirst("h4")!!.text()
            else -> {
                logW("toMainPageResult: başlık bulunamadı, atlanıyor")
                return null
            }
        }

        // -------- HREF --------
        val href = fixUrlNull(
            this.selectFirst("a")?.attr("href")
                ?: this.selectFirst("div.title a")?.attr("href")
        ) ?: run {
            logW("toMainPageResult: href bulunamadı - $title")
            return null
        }

        // -------- POSTER --------
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("data-lazy-src")
                ?: this.selectFirst("img")?.attr("src")
        )

        // -------- SCORE --------
        val score = this.selectFirst("span.rating")?.text()?.trim()
            ?: this.selectFirst("div.rating")?.text()?.trim()

        log("→ $title | href=$href | poster=${posterUrl?.take(60)} | score=$score")

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        }
    }

    // ==================================================================
    // ==========   6) ARAMA                                ============
    // ==================================================================

    override suspend fun search(query: String): List<SearchResponse> {
        logBaslik("search() BAŞLADI")
        log("query = '$query'")

        return try {
            val mainPage = app.get(mainUrl).document
            log("Ana sayfa alındı, title = ${mainPage.title()}")

            val nonce = Regex("""nonce: '(.*)'""").find(mainPage.html())
                ?.groupValues?.get(1) ?: ""
            log("Bulunan nonce = '$nonce'")

            if (nonce.isBlank()) {
                logW("nonce bulunamadı! Arama başarısız olabilir.")
            }

            val search = app.post(
                url = "${mainUrl}/wp-admin/admin-ajax.php",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                data = mapOf(
                    "action" to "ajax_search",
                    "nonce" to nonce,
                    "search" to query
                )
            )

            log("POST yanıt kodu = ${search.code}")
            log("POST yanıt (ilk 500 karakter):")
            log(search.text.take(500))

            val html = try {
                JSONObject(search.text).optString("html", "")
            } catch (e: Exception) {
                logE("JSON parse hatası! Yanıt JSON değil olabilir.", e)
                ""
            }

            log("Çıkarılan html uzunluğu = ${html.length}")

            val document = Jsoup.parse(html)

            // Aynı selector fallback'i
            val articles = when {
                document.select("div.items article").isNotEmpty() ->
                    document.select("div.items article")
                document.select("article.item").isNotEmpty() ->
                    document.select("article.item")
                else -> document.select("article")
            }

            log("Bulunan arama sonucu article sayısı = ${articles.size}")

            val results = articles.mapNotNull { it.toSearchResult() }
            log("Başarıyla dönüştürülen sonuç sayısı = ${results.size}")

            logBaslik("search() BİTTİ")
            results

        } catch (e: Exception) {
            logE("search() İSTİSNA: ${e.message}", e)
            emptyList()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = when {
            this.selectFirst("h2") != null -> this.selectFirst("h2")!!.text()
            this.selectFirst("h3") != null -> this.selectFirst("h3")!!.text()
            this.selectFirst("div.title a") != null -> this.selectFirst("div.title a")!!.text()
            else -> return null
        }
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
        )

        log("→ [arama] $title | $href")

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        log("quickSearch() -> query = '$query'")
        return search(query)
    }

    // ==================================================================
    // ==========   7) DETAY SAYFASI                         ============
    // ==================================================================

    override suspend fun load(url: String): LoadResponse? {
        logBaslik("load() BAŞLADI")
        log("url = $url")

        return try {
            val document = app.get(url).document
            log("document.title = ${document.title()}")

            val title = document.selectFirst("h1")
                ?.text()
                ?.substringBefore(" izle")
                ?.trim()

            if (title.isNullOrBlank()) {
                logE("H1 başlık bulunamadı! Dönüş: null")
                return null
            }
            log("title = '$title'")

            val poster = fixUrlNull(
                document.selectFirst("div.poster img")?.attr("src")
                    ?: document.selectFirst("div.poster img")?.attr("data-src")
            )
            val description = document.selectFirst("div.wp-content p")?.text()?.trim()
            var year = document.selectFirst("div.extra span.C a")?.text()?.trim()?.toIntOrNull()
            val tags = document.select("div.sgeneros a").map { it.text() }
            val rating = document.selectFirst("span.dt_rating_vgs")?.text()?.trim()
            var duration = document.selectFirst("span.runtime")?.text()
                ?.split(" ")?.first()?.trim()?.toIntOrNull()

            val recommendations = document.select("div.srelacionados article")
                .mapNotNull { it.toRecommendationResult() }
            val actors = document.select("span.valor a").map { Actor(it.text()) }
            val trailer = Regex("""embed/(.*)\?rel""").find(document.html())
                ?.groupValues?.get(1)
                ?.let { "https://www.youtube.com/embed/$it" }

            log("poster = ${poster?.take(80)}")
            log("description uzunluğu = ${description?.length ?: 0}")
            log("year = $year")
            log("rating = $rating")
            log("duration = $duration")
            log("tags = $tags")
            log("actor sayısı = ${actors.size}")
            log("trailer = $trailer")
            log("recommendations sayısı = ${recommendations.size}")

            if (url.contains("/dizi/")) {
                log("TÜR: DİZİ")

                year = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
                duration = document.selectFirst("div#info span:containsOwn(Dakika)")
                    ?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()

                log("Dizi güncel year = $year")
                log("Dizi güncel duration = $duration")

                val episodeElements = document.select("div#episodes ul.episodios li")
                log("Episode 'li' sayısı = ${episodeElements.size}")

                val episodes = episodeElements.mapNotNull { el ->
                    val epHref = fixUrlNull(
                        el.selectFirst("h4.episodiotitle a")?.attr("href")
                    ) ?: return@mapNotNull null
                    val epName = el.selectFirst("h4.episodiotitle a")?.ownText()?.trim()
                        ?: return@mapNotNull null
                    val epDetail = el.selectFirst("h4.episodiotitle a")?.ownText()?.trim()
                        ?: return@mapNotNull null
                    val epSeason = epDetail.substringBefore(". Sezon").toIntOrNull()
                    val epEpisode = epDetail.split("Sezon ").last()
                        .substringBefore(". Bölüm").toIntOrNull()

                    log("→ Bölüm: $epName | S=$epSeason E=$epEpisode | $epHref")

                    newEpisode(epHref) {
                        this.name = epName
                        this.season = epSeason
                        this.episode = epEpisode
                    }
                }

                log("Toplam episode = ${episodes.size}")
                logBaslik("load() BİTTİ (DİZİ)")

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = tags
                    this.score = Score.from10(rating)
                    this.duration = duration
                    this.recommendations = recommendations
                    addActors(actors)
                    addTrailer(trailer)
                }
            }

            log("TÜR: FİLM")
            logBaslik("load() BİTTİ (FİLM)")

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }

        } catch (e: Exception) {
            logE("load() İSTİSNA: ${e.message}", e)
            null
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title = this.selectFirst("a img")?.attr("alt") ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("a img")?.attr("data-src")
                ?: this.selectFirst("a img")?.attr("src")
        )

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    // ==================================================================
    // ==========   8) MULTIPART İSTEK                       ============
    // ==================================================================

    private fun sendMultipartRequest(
        nonce: String,
        postId: String,
        playerName: String,
        partKey: String,
        referer: String
    ): Response {
        log("sendMultipartRequest() -> post_id=$postId | player=$playerName | part_key=$partKey | nonce=$nonce")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("action", "get_video_url")
            .addFormDataPart("nonce", nonce)
            .addFormDataPart("post_id", postId)
            .addFormDataPart("player_name", playerName)
            .addFormDataPart("part_key", partKey)
            .build()

        val request = Request.Builder()
            .url("${mainUrl}/wp-admin/admin-ajax.php")
            .post(requestBody)
            .addHeader("Referer", referer)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .build()

        val response = OkHttpClient().newCall(request).execute()
        log("sendMultipartRequest() -> HTTP kodu = ${response.code}")

        return response
    }

    // ==================================================================
    // ==========   9) VİDEO LİNKLERİ                        ============
    // ==================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        logBaslik("loadLinks() BAŞLADI")
        log("data = $data")
        log("isCasting = $isCasting")

        try {
            val document = app.get(data).document
            log("document.title = ${document.title()}")

            val nonce = document.selectFirst("div#playex")?.attr("data-nonce") ?: ""
            log("nonce = '$nonce'")

            if (nonce.isBlank()) {
                logW("nonce boş! İstekler başarısız olabilir.")
            }

            val playerElements = document.select("nav.player a")
            log("nav.player a sayısı = ${playerElements.size}")

            if (playerElements.isEmpty()) {
                logW("Hiç player bulunamadı! HTML'de 'nav.player a' yok.")
                val allLinks = document.select("a")
                log("Toplam 'a' etiketi sayısı = ${allLinks.size}")
            }

            val players = playerElements.mapIndexed { idx, element ->
                val sourceId = element.attr("data-post-id")
                val pName = element.attr("data-player-name")
                val partKey = element.attr("data-part-key")

                log("player[$idx] -> name='$pName' | sourceId='$sourceId' | partKey='$partKey'")

                Triple(pName, sourceId, partKey)
            }

            var index = 0
            players.forEach { (pName, sourceId, partKey) ->
                index++
                log("--- [$index/${players.size}] işleniyor: $pName ---")

                if (sourceId.contains("event")) {
                    log("[$index] ATLANDI: sourceId 'event' içeriyor")
                    return@forEach
                }
                if (sourceId.isBlank()) {
                    log("[$index] ATLANDI: sourceId boş")
                    return@forEach
                }
                if (pName.isBlank()) {
                    log("[$index] ATLANDI: player name boş")
                    return@forEach
                }

                try {
                    val multiPart = sendMultipartRequest(nonce, sourceId, pName, partKey, data)
                    val sourceBody = multiPart.body.string()
                    log("[$index] sourceBody: $sourceBody")

                    val json = try {
                        JSONObject(sourceBody)
                    } catch (e: Exception) {
                        logE("[$index] JSON parse hatası! sourceBody JSON değil.", e)
                        return@forEach
                    }

                    val sourceIframe = json
                        .optJSONObject("data")
                        ?.optString("url")
                        ?.takeIf { it.isNotBlank() }

                    if (sourceIframe == null) {
                        logE("[$index] iframe URL bulunamadı! data.url boş.")
                        log("[$index] JSON içeriği: ${json.toString().take(300)}")
                        return@forEach
                    }

                    log("[$index] iframe = $sourceIframe")

                    when {
                        sourceIframe.contains("vctplay.site") -> {
                            log("[$index] TÜR: vctplay.site")
                            val vctId = sourceIframe.split("/").last()
                            val masterUrl = "https://vctplay.site/manifests/$vctId/master.txt"
                            log("[$index] masterUrl = $masterUrl")

                            callback.invoke(
                                newExtractorLink(
                                    source = "FastPlay",
                                    name = "FastPlay",
                                    url = masterUrl,
                                    ExtractorLinkType.M3U8
                                ) {
                                    referer = "https://vctplay.site/"
                                    quality = Qualities.Unknown.value
                                }
                            )
                        }

                        sourceIframe.contains("explay.store") || sourceIframe.contains("setplay.site") -> {
                            log("[$index] TÜR: explay/setplay -> loadExtractor")
                            val fullUrl = "${sourceIframe}?partKey=${partKey}"
                            log("[$index] loadExtractor URL = $fullUrl")
                            loadExtractor(fullUrl, "${mainUrl}/", subtitleCallback, callback)
                        }

                        else -> {
                            log("[$index] TÜR: genel loadExtractor")
                            log("[$index] URL = $sourceIframe")
                            loadExtractor(sourceIframe, "${mainUrl}/", subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    logE("[$index] İSTİSNA: ${e.message}", e)
                }
            }

            logBaslik("loadLinks() BİTTİ")
            return true

        } catch (e: Exception) {
            logE("loadLinks() GENEL İSTİSNA: ${e.message}", e)
            return false
        }
    }
}