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

    // ==================== LOG TAG ====================
    private val TAG = "SetFilmIzle"
    // =================================================

    override var mainUrl = "https://www.setfilmizle.ltd"
    override var name = "SetFilmIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "========== getMainPage() START ==========")
        Log.d(TAG, "getMainPage() -> page=$page | request.name=${request.name} | request.data=${request.data}")

        val document = app.get(request.data).document
        Log.d(TAG, "getMainPage() -> document.title=${document.title()}")

        val home = document.select("div.items article").mapNotNull { it.toMainPageResult() }
        Log.d(TAG, "getMainPage() -> toplam öğe: ${home.size}")

        Log.d(TAG, "========== getMainPage() END ==========")
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("h2")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        val score = this.selectFirst("span.rating")?.text()?.trim()

        Log.d(TAG, "toMainPageResult() -> title=$title | href=$href | poster=$posterUrl | score=$score")

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

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "========== search() START ==========")
        Log.d(TAG, "search() -> query=$query")

        val mainPage = app.get(mainUrl).document
        val nonce = Regex("""nonce: '(.*)'""").find(mainPage.html())?.groupValues?.get(1) ?: ""
        Log.d(TAG, "search() -> nonce=$nonce")

        val search = app.post(
            url = "${mainUrl}/wp-admin/admin-ajax.php",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            data = mapOf(
                "action" to "ajax_search",
                "nonce" to nonce,
                "search" to query
            )
        )
        Log.d(TAG, "search() -> raw response (ilk 500 char): ${search.text.take(500)}")

        val html = JSONObject(search.text).optString("html", "")
        Log.d(TAG, "search() -> html uzunluğu: ${html.length}")

        val document = Jsoup.parse(html)
        val results = document.select("div.items article").mapNotNull { it.toSearchResult() }

        Log.d(TAG, "search() -> sonuç sayısı: ${results.size}")
        Log.d(TAG, "========== search() END ==========")

        return results
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        Log.d(TAG, "toSearchResult() -> title=$title | href=$href | poster=$posterUrl")

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        Log.d(TAG, "quickSearch() -> query=$query")
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "========== load() START ==========")
        Log.d(TAG, "load() -> url=$url")

        val document = app.get(url).document
        Log.d(TAG, "load() -> document.title=${document.title()}")

        val title =
            document.selectFirst("h1")?.text()?.substringBefore(" izle")?.trim() ?: run {
                Log.e(TAG, "load() -> HATA: title bulunamadı!")
                return null
            }
        Log.d(TAG, "load() -> title=$title")

        val poster = fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
        val description = document.selectFirst("div.wp-content p")?.text()?.trim()
        var year = document.selectFirst("div.extra span.C a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div.sgeneros a").map { it.text() }
        val rating = document.selectFirst("span.dt_rating_vgs")?.text()?.trim()
        var duration =
            document.selectFirst("span.runtime")?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
        val recommendations =
            document.select("div.srelacionados article").mapNotNull { it.toRecommendationResult() }
        val actors = document.select("span.valor a").map { Actor(it.text()) }
        val trailer = Regex("""embed/(.*)\?rel""").find(document.html())?.groupValues?.get(1)
            ?.let { "https://www.youtube.com/embed/$it" }

        Log.d(TAG, "load() -> poster=$poster")
        Log.d(TAG, "load() -> year=$year | rating=$rating | duration=$duration")
        Log.d(TAG, "load() -> tags=$tags")
        Log.d(TAG, "load() -> actors=${actors.map { it.name }}")
        Log.d(TAG, "load() -> trailer=$trailer")
        Log.d(TAG, "load() -> recommendations sayısı: ${recommendations.size}")

        if (url.contains("/dizi/")) {
            Log.d(TAG, "load() -> DİZİ tespit edildi")

            year = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
            duration = document.selectFirst("div#info span:containsOwn(Dakika)")?.text()?.split(" ")
                ?.first()?.trim()?.toIntOrNull()

            Log.d(TAG, "load() -> dizi year=$year | duration=$duration")

            val episodeElements = document.select("div#episodes ul.episodios li")
            Log.d(TAG, "load() -> episode li sayısı: ${episodeElements.size}")

            val episodes = episodeElements.mapNotNull {
                val epHref = fixUrlNull(it.selectFirst("h4.episodiotitle a")?.attr("href"))
                    ?: return@mapNotNull null
                val epName = it.selectFirst("h4.episodiotitle a")?.ownText()?.trim()
                    ?: return@mapNotNull null
                val epDetail = it.selectFirst("h4.episodiotitle a")?.ownText()?.trim()
                    ?: return@mapNotNull null
                val epSeason = epDetail.substringBefore(". Sezon").toIntOrNull()
                val epEpisode =
                    epDetail.split("Sezon ").last().substringBefore(". Bölüm").toIntOrNull()

                Log.d(TAG, "load() -> episode: name=$epName | href=$epHref | S=$epSeason | E=$epEpisode")

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }

            Log.d(TAG, "load() -> toplam episode: ${episodes.size}")
            Log.d(TAG, "========== load() END (dizi) ==========")

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

        Log.d(TAG, "load() -> FİLM tespit edildi")
        Log.d(TAG, "========== load() END (film) ==========")

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
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title = this.selectFirst("a img")?.attr("alt") ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("a img")?.attr("data-src"))

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    private fun sendMultipartRequest(
        nonce: String,
        postId: String,
        playerName: String,
        partKey: String,
        referer: String
    ): Response {
        Log.d(TAG, "sendMultipartRequest() -> post_id=$postId | player_name=$playerName | part_key=$partKey | nonce=$nonce")

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
        Log.d(TAG, "sendMultipartRequest() -> HTTP code: ${response.code}")

        return response
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "########## loadLinks() START ##########")
        Log.d(TAG, "loadLinks() -> data=$data | isCasting=$isCasting")

        val document = app.get(data).document
        Log.d(TAG, "loadLinks() -> document.title=${document.title()}")

        val nonce = document.selectFirst("div#playex")?.attr("data-nonce") ?: ""
        Log.d(TAG, "loadLinks() -> nonce=$nonce")

        val playerElements = document.select("nav.player a")
        Log.d(TAG, "loadLinks() -> nav.player a sayısı: ${playerElements.size}")

        val players = playerElements.map { element ->
            val sourceId = element.attr("data-post-id")
            val name = element.attr("data-player-name")
            val partKey = element.attr("data-part-key")

            Log.d(TAG, "loadLinks() -> player bulundu: name=$name | sourceId=$sourceId | partKey=$partKey")

            Triple(name, sourceId, partKey)
        }

        var index = 0
        players.forEach { (name, sourceId, partKey) ->
            index++
            Log.d(TAG, "loadLinks() -> [$index/${players.size}] işleniyor: name=$name")

            if (sourceId.contains("event")) {
                Log.d(TAG, "loadLinks() -> [$index] ATLANDI: sourceId 'event' içeriyor")
                return@forEach
            }
            if (sourceId.isBlank()) {
                Log.d(TAG, "loadLinks() -> [$index] ATLANDI: sourceId boş")
                return@forEach
            }
            if (name.isBlank()) {
                Log.d(TAG, "loadLinks() -> [$index] ATLANDI: name boş")
                return@forEach
            }

            try {
                val multiPart = sendMultipartRequest(nonce, sourceId, name, partKey, data)
                val sourceBody = multiPart.body.string()
                Log.d(TAG, "loadLinks() -> [$index] sourceBody: $sourceBody")

                val sourceIframe = JSONObject(sourceBody)
                    .optJSONObject("data")
                    ?.optString("url")
                    ?.takeIf { it.isNotBlank() }
                    ?: run {
                        Log.e(TAG, "loadLinks() -> [$index] HATA: iframe URL bulunamadı!")
                        return@forEach
                    }

                Log.d(TAG, "loadLinks() -> [$index] iframe: $sourceIframe")

                when {
                    sourceIframe.contains("vctplay.site") -> {
                        Log.d(TAG, "loadLinks() -> [$index] TÜR: vctplay.site")
                        val vctId = sourceIframe.split("/").last()
                        val masterUrl = "https://vctplay.site/manifests/$vctId/master.txt"
                        Log.d(TAG, "loadLinks() -> [$index] masterUrl: $masterUrl")

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
                        Log.d(TAG, "loadLinks() -> [$index] TÜR: explay/setplay -> loadExtractor")
                        loadExtractor(
                            "${sourceIframe}?partKey=${partKey}",
                            "${mainUrl}/",
                            subtitleCallback,
                            callback
                        )
                    }

                    else -> {
                        Log.d(TAG, "loadLinks() -> [$index] TÜR: genel loadExtractor")
                        loadExtractor(sourceIframe, "${mainUrl}/", subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks() -> [$index] İSTİSNA: ${e.message}", e)
            }
        }

        Log.d(TAG, "loadLinks() -> tamamlandı")
        Log.d(TAG, "########## loadLinks() END ##########")

        return true
    }
}