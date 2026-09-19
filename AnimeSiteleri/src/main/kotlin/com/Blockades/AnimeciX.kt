package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll

class AnimeciX : MainAPI() {
    override var mainUrl              = "https://animecix.tv"
    override var name                 = "AnimeciX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)
    override val supportedSyncNames   = setOf(SyncIdName.MyAnimeList)

    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 200L  // ? 0.20 saniye
    override var sequentialMainPageScrollDelay = 200L  // ? 0.20 saniye

    private var cachedToken: String? = null
    private suspend fun getAuthHeaders(): Map<String, String> {
        cachedToken?.let { return mapOf("x-e-h" to it) }
        try {
            val html = kotlinx.coroutines.withTimeoutOrNull(2000) {
                app.get(mainUrl).text
            }
            if (html != null) {
                val match = Regex("""['"]x-e-h['"]\s*:\s*['"]([^'"]+)['"]""").find(html)
                if (match != null) {
                    cachedToken = match.groupValues[1]
                    return mapOf("x-e-h" to cachedToken!!)
                }
            }
        } catch (e: Exception) {}
        
        cachedToken = "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk"
        return mapOf("x-e-h" to cachedToken!!)
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/secure/last-episodes"                          to "Son Eklenen Bölümler",
        "${mainUrl}/secure/titles?type=series&onlyStreamable=true" to "Seriler",
        "${mainUrl}/secure/titles?type=movie&onlyStreamable=true"  to "Filmler",
        "genre_Aksiyon" to "Aksiyon Animeleri",
        "genre_Komedi" to "Komedi Animeleri",
        "genre_Dram" to "Dram Animeleri",
        "genre_Romantizm" to "Romantik Animeler",
        "genre_Gizem" to "Gizem Animeleri",
        "genre_Bilim Kurgu & Fantastik" to "Bilim Kurgu & Fantastik Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val authHeader = getAuthHeaders()
        return if (request.data.contains("/last-episodes")) {
            val response = app.get(
                "${mainUrl}/secure/last-episodes?page=$page&perPage=10",
                headers = authHeader
            ).parsedSafe<LastEpisodesResponse>()?.data ?: emptyList()
    
            val home = response.map {
                val formattedTitle = "S${it.seasonNumber}B${it.episodeNumber} - ${it.titleName}"
                newAnimeSearchResponse(
                    formattedTitle,
                    "${mainUrl}/secure/titles/${it.titleId}?titleId=${it.titleId}",
                    TvType.Anime
                ) {
                    this.posterUrl = fixUrlNull(it.titlePoster)
                    this.posterHeaders = authHeader
                    addDub(null)
                    addSub(null)
                }
            }
    
            newHomePageResponse(request.name, home)
        } else if (request.data.startsWith("genre_")) {
            val targetGenre = request.data.substringAfter("genre_")
            val genreMap = mapOf(
                "Aksiyon" to "action-adventure",
                "Komedi" to "comedy",
                "Dram" to "drama",
                "Romantizm" to "romance",
                "Gizem" to "mystery",
                "Bilim Kurgu & Fantastik" to "sci-fi-fantasy"
            )
            val engKeyword = genreMap[targetGenre] ?: targetGenre
            val encodedGenre = URLEncoder.encode(engKeyword, "UTF-8")
            
            val startPage = (page - 1) * 2 + 1
            val endPage = page * 2
            val allItems = mutableListOf<SearchResponse>()
            coroutineScope {
                val deferred = (startPage..endPage).map { p ->
                    async {
                        try {
                            val res = app.get(
                                "${mainUrl}/secure/titles?genre=$encodedGenre&page=$p&perPage=16",
                                headers = authHeader
                            ).parsedSafe<Category>()?.pagination?.data ?: emptyList()
                            res.map { anime ->
                                newAnimeSearchResponse(anime.title, "${mainUrl}/secure/titles/${anime.id}?titleId=${anime.id}", TvType.Anime) {
                                    this.posterUrl = fixUrlNull(anime.poster)
                                    this.posterHeaders = authHeader
                                    addDub(null)
                                    addSub(null)
                                    anime.rating?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { this.score = Score.from10(it) } ?:
                                    anime.tmdbVoteAverage?.takeIf { it > 0 }?.let { this.score = Score.from10(it) }
                                }
                            }
                        } catch (e: Exception) { emptyList() }
                    }
                }
                deferred.awaitAll().forEach { allItems.addAll(it) }
            }
            newHomePageResponse(request.name, allItems.distinctBy { it.url }, hasNext = allItems.isNotEmpty())
        } else {
            val response = app.get(
                "${request.data}&page=${page}&perPage=16",
                headers = authHeader
            ).parsedSafe<Category>()
    
            val home = response?.pagination?.data?.map { anime ->
                newAnimeSearchResponse(
                    anime.title,
                    "${mainUrl}/secure/titles/${anime.id}?titleId=${anime.id}",
                    TvType.Anime
                ) {
                    this.posterUrl = fixUrlNull(anime.poster)
                    this.posterHeaders = authHeader
                    addDub(null)
                    addSub(null)
                    anime.rating?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { this.score = Score.from10(it) } ?:
                    anime.tmdbVoteAverage?.takeIf { it > 0 }?.let { this.score = Score.from10(it) }
                }
            } ?: listOf()
    
            newHomePageResponse(request.name, home)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val authHeader = getAuthHeaders()
        val cleanQuery = query.lowercase().trim()
            .replace(Regex("""\s*(?:animesi|animeleri|izle)$"""), "")
            .trim()

        val categoryId = when (cleanQuery) {
            "aksiyon", "macera" -> "action-adventure"
            "komedi" -> "comedy"
            "dram" -> "drama"
            "romantizm", "romantik", "aşk", "ask" -> "romance"
            "gizem" -> "mystery"
            "bilim kurgu", "bilimkurgu", "fantastik", "fantazi", "bilim kurgu & fantastik" -> "sci-fi-fantasy"
            else -> null
        }

        val items = if (categoryId != null) {
            val encodedGenre = URLEncoder.encode(categoryId, "UTF-8")
            val res = app.get(
                "${mainUrl}/secure/titles?genre=$encodedGenre&page=1&perPage=30",
                headers = authHeader
            ).parsedSafe<Category>()?.pagination?.data ?: emptyList()
            res
        } else {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val response = app.get("${mainUrl}/secure/search/${encodedQuery}?limit=30", headers = authHeader).parsedSafe<Search>()
            response?.results ?: emptyList()
        }

        return items.map { anime ->
            newAnimeSearchResponse(
                anime.title,
                "${mainUrl}/secure/titles/${anime.id}?titleId=${anime.id}",
                TvType.Anime
            ) {
                this.posterUrl = fixUrlNull(anime.poster)
                addDub(null)
                addSub(null)
                anime.rating?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { this.score = Score.from10(it) } ?:
                anime.tmdbVoteAverage?.takeIf { it > 0 }?.let { this.score = Score.from10(it) }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val authHeader = getAuthHeaders()
        val response = app.get(
            url,
            headers = authHeader
        ).parsedSafe<Title>() ?: return null
        val episodes = mutableListOf<Episode>()
        val titleId  = url.substringAfter("?titleId=")

        if (response.title.titleType == "anime") {
            for (sezon in response.title.seasons) {
                val sezonResponse = app.get("${mainUrl}/secure/related-videos?episode=1&season=${sezon.number}&videoId=0&titleId=${titleId}").parsedSafe<TitleVideos>() ?: return null
                for (video in sezonResponse.videos) {
                    episodes.add(newEpisode(video.url) {
                        this.name = "${video.seasonNum}. Sezon ${video.episodeNum}. Bölüm"
                        this.season = video.seasonNum
                        this.episode = video.episodeNum
                    })
                }
            }
        } else {
            if (response.title.videos.isNotEmpty()) {
                episodes.add(newEpisode(response.title.videos.first().url) {
                    this.name    = "Filmi İzle"
                    this.season  = 1
                    this.episode = 1
                })
            }
        }


        return newTvSeriesLoadResponse(
            response.title.title,
            "${mainUrl}/secure/titles/${response.title.id}?titleId=${response.title.id}",
            TvType.Anime,
            episodes
        ) {
            this.posterUrl = fixUrlNull(response.title.poster)
            this.year      = response.title.year
            this.plot      = response.title.description
            this.tags      = response.title.tags.map { it.name }
            response.title.rating?.toDoubleOrNull()?.let { this.score = Score.from(it, 10) }
            addActors(response.title.actors.map { Actor(it.name, fixUrlNull(it.poster)) })
            addTrailer(response.title.trailer)
            val syncMap = mutableMapOf<String, String>()
            response.title.malId?.let { syncMap[SyncIdName.MyAnimeList.name] = it.toString() }
            response.title.tmdbId?.let { syncMap["TMDb"] = it.toString() }
            response.title.imdbId?.let { syncMap["IMDb"] = it }
            this.syncData = syncMap
        }
    }
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    Log.d("ACX", "data » $data")
    val pageUrl = "$mainUrl/$data"

    // Sayfayı çek
    val response = app.get(pageUrl, referer = "$mainUrl/")
    var iframeLink = response.url
    Log.d("ACX", "iframeLink » $iframeLink")

    // Eğer iframeLink içinde çift URL varsa düzelt
    val doubleUrlRegex = Regex("https://animecix.tv/(https://animecix.tv/secure/\\S+)")
    val match = doubleUrlRegex.find(iframeLink)
    if (match != null) {
        iframeLink = match.groupValues[1]
        Log.d("ACX", "Corrected iframeLink » $iframeLink")
    }

    // Eğer dizi (best-video) ise yönlendirmeyi takip et
    if (iframeLink.contains("/secure/best-video")) {
        val redirectResponse = app.get(iframeLink, referer = "$mainUrl/")
        val redirectedUrl = redirectResponse.url
        Log.d("ACX", "Redirected final URL » $redirectedUrl")

        if (redirectedUrl.contains("tau-video")) {
            loadExtractor(redirectedUrl, "$mainUrl/", subtitleCallback, callback)
        } else {
            Log.d("ACX", "Redirect failed or unexpected URL: $redirectedUrl")
        }
    } else {
        loadExtractor(iframeLink, "$mainUrl/", subtitleCallback, callback)
    }

    return true
}
}
