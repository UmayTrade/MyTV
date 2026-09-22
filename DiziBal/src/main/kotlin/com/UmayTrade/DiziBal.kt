package com.UmayTrade

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder

class DiziBal : MainAPI() {
    override var mainUrl              = "https://dizibal.org"
    override var name                 = "DiziBal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val apiUrl = "$mainUrl/api"
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // Tarayıcı benzeri başlıklar — Cloudflare ve bot korumalarını aşmak için
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
        "X-Requested-With" to "XMLHttpRequest"
    )

    // Ortak güvenli GET isteği
    private suspend fun safeGet(url: String, extraHeaders: Map<String, String> = emptyMap()): String? {
        return try {
            val headers = browserHeaders + extraHeaders
            val res = app.get(url, headers = headers, referer = "$mainUrl/")
            if (res.code in 200..299) {
                res.text
            } else {
                Log.e(name, "safeGet HTTP ${res.code} -> $url")
                null
            }
        } catch (e: Exception) {
            Log.e(name, "safeGet hatası: ${e.message} -> $url")
            null
        }
    }

    override val mainPage = mainPageOf(
        "$apiUrl/movies?sort=-release_date&limit=20&page=" to "Yeni Filmler",
        "$apiUrl/series?network=Netflix&limit=20&page=" to "Netflix Dizileri",
        "$apiUrl/series?network=Prime%20Video&limit=20&page=" to "Prime Video Dizileri",
        "$apiUrl/series?network=Disney%2B&limit=20&page=" to "Disney+ Dizileri",
        "$apiUrl/series?network=Apple%20TV&limit=20&page=" to "Apple TV Dizileri",
        "$apiUrl/series?network=Hulu&limit=20&page=" to "Hulu Dizileri",
        "$apiUrl/series?network=HBO&limit=20&page=" to "HBO Dizileri",
        "$apiUrl/series?network=GA%C4%B0N&limit=20&page=" to "GAİN Dizileri",
        "$apiUrl/series?network=Exxen&limit=20&page=" to "Exxen Dizileri",
        "$apiUrl/series?network=BluTV&limit=20&page=" to "BluTV Dizileri",
        "$apiUrl/series?network=TOD&limit=20&page=" to "TOD Dizileri",
        "$apiUrl/series?network=puhutv&limit=20&page=" to "puhutv Dizileri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = request.data + page
        return try {
            val text = safeGet(url)
            if (text.isNullOrBlank()) {
                Log.e(name, "getMainPage: boş yanıt -> $url")
                return newHomePageResponse(emptyList())
            }

            val res = try {
                mapper.readValue(text, WListResponse::class.java)
            } catch (e: Exception) {
                Log.e(name, "getMainPage parse hatası: ${e.message}")
                Log.e(name, "Ham yanıt (ilk 300 karakter): ${text.take(300)}")
                null
            }

            if (res?.data == null) {
                Log.e(name, "getMainPage: data null -> $url")
                newHomePageResponse(emptyList())
            } else {
                val items = res.data!!.mapNotNull { it.toSearchResponse() }
                val hasNext = (res.pagination?.page ?: 1) < (res.pagination?.totalPages ?: 1)
                newHomePageResponse(request.name, items, hasNext)
            }
        } catch (e: Exception) {
            Log.e(name, "getMainPage beklenmeyen hata: ${e.message}")
            newHomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val endpoints = listOf("movies", "series", "anime")
        val results = mutableListOf<SearchResponse>()

        for (ep in endpoints) {
            val searchUrl = "$apiUrl/$ep?search=$encodedQuery&page=1&limit=20"
            val text = safeGet(searchUrl) ?: continue
            try {
                val res = mapper.readValue(text, WListResponse::class.java)
                res.data?.mapNotNull { it.toSearchResponse() }?.let { results.addAll(it) }
            } catch (e: Exception) {
                Log.e(name, "search parse hatası ($ep): ${e.message}")
            }
        }
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val parts = url.split("||")
            if (parts.size < 2) throw Exception("Bozuk URL")

            val typeStr = parts[0]
            val slug = parts[1]
            val dbId = if (parts.size > 2) parts[2] else ""

            val isSeries = typeStr.contains(TvType.TvSeries.name) ||
                (typeStr.contains(TvType.Anime.name) && dbId.isNotEmpty())

            val detailUrl = if (isSeries) "$apiUrl/series/$slug" else "$apiUrl/movies/$slug"

            val text = safeGet(detailUrl) ?: throw Exception("Boş yanıt: $detailUrl")
            val res = try {
                mapper.readValue(text, WDetailResponse::class.java).data
            } catch (e: Exception) {
                Log.e(name, "load parse hatası: ${e.message}")
                null
            } ?: throw Exception("Boş veri: $detailUrl")

            val titleStr = res.title_tr ?: res.title_en ?: res.title
                ?: res.name_tr ?: res.name_en ?: res.name ?: throw Exception("Başlık yok")
            val posterStr = res.poster_url
                ?: res.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                ?: res.backdrop_url
                ?: res.backdrop_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            val descStr = res.overview_tr ?: res.overview_en ?: res.overview
            val yearInt = res.release_date?.substringBefore("-")?.toIntOrNull()
                ?: res.first_air_date?.substringBefore("-")?.toIntOrNull()
            val ratingDbl = res.vote_average
            val tagsList = res.genres?.mapNotNull { it.name }

            if (isSeries) {
                val episodes = mutableListOf<Episode>()
                val fetchDbId = res._id ?: dbId

                res.seasons?.forEach { season ->
                    val sNum = season.season_number ?: return@forEach
                    val seasonUrl = "$apiUrl/series/$slug/seasons/$sNum"
                    val seasonText = safeGet(seasonUrl) ?: return@forEach
                    val seasonData = try {
                        mapper.readValue(seasonText, WSeasonResponse::class.java).data
                    } catch (e: Exception) {
                        Log.e(name, "season parse hatası S$sNum: ${e.message}")
                        null
                    } ?: return@forEach

                    seasonData.episodes?.forEach { ep ->
                        val eNum = ep.episode_number ?: return@forEach
                        val epName = ep.name_tr ?: ep.name_en ?: ep.name
                        val epUrl = "$apiUrl/series/$fetchDbId/seasons/$sNum/episodes/$eNum/stream"
                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epName
                                this.season = sNum
                                this.episode = eNum
                            }
                        )
                    }
                }

                newTvSeriesLoadResponse(titleStr, url, TvType.TvSeries, episodes) {
                    this.posterUrl = posterStr
                    this.plot = descStr
                    this.year = yearInt
                    this.tags = tagsList
                    if (ratingDbl != null && ratingDbl > 0) {
                        try { this.score = Score.from10(ratingDbl.toString()) } catch (_: Exception) {}
                    }
                }
            } else {
                val streamUrl = res.streamUrl
                val sourceUrl = if (!streamUrl.isNullOrEmpty()) streamUrl else url

                newMovieLoadResponse(titleStr, url, TvType.Movie, sourceUrl) {
                    this.posterUrl = posterStr
                    this.plot = descStr
                    this.year = yearInt
                    this.tags = tagsList
                    if (ratingDbl != null && ratingDbl > 0) {
                        try { this.score = Score.from10(ratingDbl.toString()) } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "load hatası: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var streamUrl = data
            Log.d(name, "loadLinks başladı, data: $data")

            if (data.startsWith("$apiUrl/series/")) {
                Log.d(name, "Dizi bölümü stream URL'si alınıyor: $data")
                val text = safeGet(data) ?: throw Exception("Stream endpoint boş yanıt")
                val res = try {
                    mapper.readValue(text, WStreamResponse::class.java).data
                } catch (e: Exception) {
                    Log.e(name, "stream parse hatası: ${e.message}")
                    null
                }
                streamUrl = res?.streamUrl ?: throw Exception("Gerekli veri bulunamadı")
                Log.d(name, "Alınan streamUrl: $streamUrl")
            }

            if (!streamUrl.contains("/embed-")) {
                Log.e(name, "streamUrl embed içermiyor: $streamUrl")
                throw Exception("Gerekli veri bulunamadı")
            }

            Log.d(name, "Embed URL tespit edildi: $streamUrl")
            val embedRes = app.get(streamUrl, headers = browserHeaders, referer = apiUrl)
            val html = embedRes.text

            val fetchPath = Regex("""fetch\(['"](/dl\?op=get_stream.*?)['"]\)""")
                .find(html)?.groupValues?.get(1)
            Log.d(name, "fetchPath: $fetchPath")

            if (fetchPath != null) {
                val host = streamUrl.substringBefore("/embed-")
                val jsonUrl = "$host$fetchPath"
                val fileId = Regex("""cookie\('file_id',\s*'(\d+)'""")
                    .find(html)?.groupValues?.get(1)
                Log.d(name, "fileId: $fileId, jsonUrl: $jsonUrl")

                val customCookies = mutableMapOf<String, String>()
                if (fileId != null) customCookies["file_id"] = fileId
                customCookies["aff"] = "1"
                customCookies["ref_url"] = "dizibal.com"

                val streamRes = app.get(
                    jsonUrl,
                    referer = streamUrl,
                    headers = browserHeaders,
                    cookies = customCookies
                )
                Log.d(name, "JSON yanıtı alındı, uzunluk: ${streamRes.text.length}")

                val urlToStream = Regex(""""url":"([^"]+)"""")
                    .find(streamRes.text)?.groupValues?.get(1)?.replace("\\/", "/")
                Log.d(name, "urlToStream: $urlToStream")

                if (!urlToStream.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            source = "DiziBal",
                            name = "DiziBal HD",
                            url = urlToStream,
                            type = if (urlToStream.contains(".m3u8")) ExtractorLinkType.M3U8
                                   else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.P1080.value
                            this.referer = streamUrl
                        }
                    )
                    Log.d(name, "Extractor link eklendi")

                    val subs = Regex(""""subtitle":"([^"]+)"""")
                        .find(html)?.groupValues?.get(1)
                    if (subs != null) {
                        subs.split(",").forEach { subEntry ->
                            val subParts = subEntry.split("]")
                            if (subParts.size > 1) {
                                val lang = subParts[0].replace("[", "").trim()
                                val subUrlPart = subParts[1].trim()
                                val subUrl = if (subUrlPart.startsWith("http")) subUrlPart
                                             else host + subUrlPart
                                subtitleCallback(SubtitleFile(lang, subUrl))
                                Log.d(name, "Altyazı eklendi: $lang -> $subUrl")
                            }
                        }
                    }
                } else {
                    Log.e(name, "urlToStream bulunamadı veya boş")
                }
            } else {
                Log.e(name, "fetchPath bulunamadı")
            }

            loadExtractor(streamUrl, subtitleCallback, callback)
            Log.d(name, "loadExtractor tamamlandı")
            true
        } catch (e: Exception) {
            Log.e(name, "loadLinks hatası: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun WItem.toSearchResponse(): SearchResponse? {
        val titleStr = this.title_tr ?: this.title_en ?: this.title
            ?: this.name_tr ?: this.name_en ?: this.name ?: return null
        val posterStr = this.poster_url
            ?: this.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
        val slugStr = this.slug ?: return null
        val dbId = this._id ?: ""

        val isSeries = !this.name.isNullOrEmpty() || !this.name_tr.isNullOrEmpty()
        val isAnime = this.isAnime == true

        val type = when {
            isAnime && isSeries -> TvType.Anime
            isSeries -> TvType.TvSeries
            isAnime && !isSeries -> TvType.Anime
            else -> TvType.Movie
        }

        val urlData = "$type||$slugStr||$dbId"

        return newMovieSearchResponse(titleStr, urlData, type) {
            this.posterUrl = posterStr
        }
    }

    data class WListResponse(
        @field:JsonProperty("success") val success: Boolean? = null,
        @field:JsonProperty("data") val data: List<WItem>? = null,
        @field:JsonProperty("pagination") val pagination: WPagination? = null
    )

    data class WDetailResponse(
        @field:JsonProperty("success") val success: Boolean? = null,
        @field:JsonProperty("data") val data: WItemDetail? = null
    )

    data class WStreamResponse(
        @field:JsonProperty("success") val success: Boolean? = null,
        @field:JsonProperty("data") val data: WStreamData? = null
    )

    data class WSeasonResponse(
        @field:JsonProperty("success") val success: Boolean? = null,
        @field:JsonProperty("data") val data: WSeason? = null
    )

    data class WPagination(
        @field:JsonProperty("page") val page: Int? = null,
        @field:JsonProperty("limit") val limit: Int? = null,
        @field:JsonProperty("total") val total: Int? = null,
        @field:JsonProperty("totalPages") val totalPages: Int? = null
    )

    data class WItem(
        @field:JsonProperty("_id") val _id: String? = null,
        @field:JsonProperty("slug") val slug: String? = null,
        @field:JsonProperty("title") val title: String? = null,
        @field:JsonProperty("title_tr") val title_tr: String? = null,
        @field:JsonProperty("title_en") val title_en: String? = null,
        @field:JsonProperty("name") val name: String? = null,
        @field:JsonProperty("name_tr") val name_tr: String? = null,
        @field:JsonProperty("name_en") val name_en: String? = null,
        @field:JsonProperty("poster_url") val poster_url: String? = null,
        @field:JsonProperty("poster_path") val poster_path: String? = null,
        @field:JsonProperty("backdrop_url") val backdrop_url: String? = null,
        @field:JsonProperty("backdrop_path") val backdrop_path: String? = null,
        @field:JsonProperty("isAnime") val isAnime: Boolean? = null
    )

    data class WItemDetail(
        @field:JsonProperty("_id") val _id: String? = null,
        @field:JsonProperty("slug") val slug: String? = null,
        @field:JsonProperty("title") val title: String? = null,
        @field:JsonProperty("title_tr") val title_tr: String? = null,
        @field:JsonProperty("title_en") val title_en: String? = null,
        @field:JsonProperty("name") val name: String? = null,
        @field:JsonProperty("name_tr") val name_tr: String? = null,
        @field:JsonProperty("name_en") val name_en: String? = null,
        @field:JsonProperty("poster_url") val poster_url: String? = null,
        @field:JsonProperty("poster_path") val poster_path: String? = null,
        @field:JsonProperty("backdrop_url") val backdrop_url: String? = null,
        @field:JsonProperty("backdrop_path") val backdrop_path: String? = null,
        @field:JsonProperty("overview") val overview: String? = null,
        @field:JsonProperty("overview_tr") val overview_tr: String? = null,
        @field:JsonProperty("overview_en") val overview_en: String? = null,
        @field:JsonProperty("release_date") val release_date: String? = null,
        @field:JsonProperty("first_air_date") val first_air_date: String? = null,
        @field:JsonProperty("vote_average") val vote_average: Double? = null,
        @field:JsonProperty("genres") val genres: List<WGenre>? = null,
        @field:JsonProperty("streamUrl") val streamUrl: String? = null,
        @field:JsonProperty("seasons") val seasons: List<WSeason>? = null
    )

    data class WGenre(@field:JsonProperty("name") val name: String? = null)

    data class WSeason(
        @field:JsonProperty("season_number") val season_number: Int? = null,
        @field:JsonProperty("episodes") val episodes: List<WEpisode>? = null
    )

    data class WEpisode(
        @field:JsonProperty("episode_number") val episode_number: Int? = null,
        @field:JsonProperty("name") val name: String? = null,
        @field:JsonProperty("name_tr") val name_tr: String? = null,
        @field:JsonProperty("name_en") val name_en: String? = null
    )

    data class WStreamData(
        @field:JsonProperty("streamUrl") val streamUrl: String? = null
    )
}