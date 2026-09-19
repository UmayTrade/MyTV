package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll

private inline fun <reified T> parseJson(json: String): T {
    return mapper.readValue<T>(json)
}

class OpenAnime : MainAPI() {
    override var mainUrl              = "https://openani.me"
    override var name                 = "OpenAnime"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Anime)

    private val apiHeaders = mapOf(
        "Client-Protocol-Model" to "RCSA-14402/05",
        "User-Agent"            to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Origin"                to "https://openani.me",
        "Referer"               to "https://openani.me/"
    )

    private var cachedCdnHost: String? = null

    private suspend fun getCdnHost(): String {
        cachedCdnHost?.let { return it }
        try {
            val html = app.get(mainUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).text
            Regex("""random_cdn_host:"(https://[^"]+)"""").find(html)?.groupValues?.get(1)?.let {
                cachedCdnHost = it
                return it
            }
        } catch (e: Exception) { e.printStackTrace() }
        val defaultHost = "https://de2---vn-t9g4tsan-5qcl.yeshi.eu.org"
        cachedCdnHost = defaultHost
        return defaultHost
    }

    override val mainPage = mainPageOf(
        "latest-episodes" to "En Son Eklenen Bölümler",
        "popular-episodes" to "Popüler Bölümler",
        "popular-series" to "Popüler Seriler",
        "4k-releases" to "4K Çözünürlüklü Animeler",
        "all-anime" to "Tüm Animeler",
        "genre_Aksiyon & Macera" to "Aksiyon & Macera Animeleri",
        "genre_Bilim Kurgu & Fantazi" to "Bilim Kurgu & Fantastik Animeler",
        "genre_Komedi" to "Komedi Animeleri",
        "genre_Dram" to "Dram Animeleri",
        "genre_Gizem" to "Gizem Animeleri",
        "genre_Romantik" to "Romantik Animeler",
        "genre_Doğaüstü & Büyü" to "Doğaüstü & Büyü Animeleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val searchItems = mutableListOf<OpenAnimeSearchItem>()

        when (request.data) {
            "latest-episodes" -> {
                val url = "https://api.openani.me/anime/episodes/latest?limit=30&page=$page"
                val response = app.get(url, headers = apiHeaders).parsedSafe<OpenAnimeEpisodesResponse>()
                response?.episodes?.let { searchItems.addAll(it) }
            }
            "popular-episodes" -> {
                val url = "https://api.openani.me/anime/episodes/latest/populars?limit=30&page=$page"
                val response = app.get(url, headers = apiHeaders).parsedSafe<OpenAnimeEpisodesResponse>()
                response?.episodes?.let { searchItems.addAll(it) }
            }
            "popular-series" -> {
                val url = "https://api.openani.me/anime?sort=popular&limit=30&page=$page"
                val response = app.get(url, headers = apiHeaders).parsedSafe<OpenAnimeListResponse>()
                response?.animes?.let { searchItems.addAll(it) }
            }
            "4k-releases" -> {
                if (page > 1) return null
                val url = "https://api.openani.me/anime/4k-releases"
                val responseText = app.get(url, headers = apiHeaders).text
                parseJson<OpenAnime4kResponse>(responseText).animes?.let { searchItems.addAll(it) }
            }
            "all-anime" -> {
                val url = "https://api.openani.me/anime?limit=30&page=$page"
                val response = app.get(url, headers = apiHeaders).parsedSafe<OpenAnimeListResponse>()
                response?.animes?.let { searchItems.addAll(it) }
            }
            else -> {
                if (request.data.startsWith("genre_")) {
                    val targetGenre = request.data.substringAfter("genre_")
                    val startPage = (page - 1) * 2 + 1
                    val endPage = page * 2
                    val allAnimes = mutableListOf<OpenAnimeSearchItem>()
                    coroutineScope {
                        val deferred = (startPage..endPage).map { p ->
                            async {
                                try {
                                    app.get("https://api.openani.me/anime?limit=30&page=$p", headers = apiHeaders).parsedSafe<OpenAnimeListResponse>()
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        }
                        deferred.awaitAll().forEach { res ->
                            res?.animes?.let { allAnimes.addAll(it) }
                        }
                    }
                    val filtered = allAnimes.filter { item ->
                        item.genres?.any { g -> g.contains(targetGenre, ignoreCase = true) || (targetGenre == "Doğaüstü & Büyü" && g.contains("Doğaüstü", ignoreCase = true)) } == true
                    }
                    searchItems.addAll(filtered.distinctBy { it.slug })
                }
            }
        }

        val homeItems = searchItems.mapNotNull { item ->
            val slug = item.slug ?: return@mapNotNull null
            val titleVal = item.turkish ?: item.english ?: item.originalName ?: "Anime"
            val poster = item.pictures?.avatar ?: item.pictures?.banner ?: "https://openani.me/favicon512.png"

            newAnimeSearchResponse(titleVal, "https://api.openani.me/anime/$slug", TvType.Anime) {
                this.posterUrl = poster
                addSub(null)
                addDub(null)
                item.score?.takeIf { it > 0 }?.let { this.score = Score.from10(it) } ?:
                item.tmdbScore?.takeIf { it > 0 }?.let { this.score = Score.from10(it) }
            }
        }

        if (homeItems.isEmpty()) return null
        val hasNext = homeItems.isNotEmpty()
        return newHomePageResponse(request.name, homeItems, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.lowercase().trim()
            .replace(Regex("""\s*(?:animesi|animeleri|izle)$"""), "")
            .trim()

        val targetGenre = when (cleanQuery) {
            "aksiyon", "macera", "aksiyon macera", "aksiyon & macera" -> "Aksiyon & Macera"
            "bilim kurgu", "bilimkurgu", "fantastik", "fantazi", "bilim kurgu & fantazi" -> "Bilim Kurgu & Fantazi"
            "komedi" -> "Komedi"
            "dram" -> "Dram"
            "gizem" -> "Gizem"
            "romantik", "aşk", "ask", "romantizm" -> "Romantik"
            "doğaüstü", "dogaustu", "büyü", "buyu", "doğaüstü & büyü" -> "Doğaüstü & Büyü"
            else -> null
        }

        val searchItems = mutableListOf<OpenAnimeSearchItem>()

        if (targetGenre != null) {
            val allAnimes = mutableListOf<OpenAnimeSearchItem>()
            coroutineScope {
                val deferred = (1..5).map { p ->
                    async {
                        try {
                            app.get("https://api.openani.me/anime?limit=30&page=$p", headers = apiHeaders).parsedSafe<OpenAnimeListResponse>()
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                deferred.awaitAll().forEach { res ->
                    res?.animes?.let { allAnimes.addAll(it) }
                }
            }
            val filtered = allAnimes.filter { item ->
                item.genres?.any { g -> g.contains(targetGenre, ignoreCase = true) || (targetGenre == "Doğaüstü & Büyü" && g.contains("Doğaüstü", ignoreCase = true)) } == true
            }
            searchItems.addAll(filtered.distinctBy { it.slug })
        } else {
            val searchQuery = query.trim()
            if (searchQuery.length >= 2) {
                val url = "https://api.openani.me/anime/search?q=${URLEncoder.encode(searchQuery, "UTF-8")}"
                try {
                    val res = app.get(url, headers = apiHeaders)
                    if (res.code == 200) {
                        parseJson<List<OpenAnimeSearchItem>>(res.text).let { searchItems.addAll(it) }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return searchItems.mapNotNull { item ->
            val slug = item.slug ?: return@mapNotNull null
            val titleVal = item.turkish ?: item.english ?: item.originalName ?: "Anime"
            val poster = item.pictures?.avatar ?: item.pictures?.banner ?: "https://openani.me/favicon512.png"

            newAnimeSearchResponse(titleVal, "https://api.openani.me/anime/$slug", TvType.Anime) {
                this.posterUrl = poster
                addSub(null)
                addDub(null)
                item.score?.takeIf { it > 0 }?.let { this.score = Score.from10(it) } ?:
                item.tmdbScore?.takeIf { it > 0 }?.let { this.score = Score.from10(it) }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = apiHeaders).parsedSafe<OpenAnimeDetail>() ?: return null

        val title = res.turkish ?: res.english ?: res.originalName ?: "Anime"
        val poster = res.pictures?.avatar ?: res.pictures?.banner ?: "https://openani.me/favicon512.png"
        val banner = res.pictures?.banner
        val summary = res.summary
        val scoreVal = res.tmdbScore?.let { if (it > 0) Score.from(it, 10) else null }
        val tracker = res.malID?.let { mapOf("MyAnimeList" to it.toString()) }

        val episodeList = mutableListOf<Episode>()
        val animeType = res.type ?: "tv"

        if (animeType == "movie" || (res.numberOfEpisodes == 1 && res.seasons.isNullOrEmpty())) {
            episodeList.add(
                newEpisode("$url/season/1/episode/1") {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                    this.posterUrl = poster
                    this.description = summary
                }
            )
        } else {
            res.seasons?.forEach { season ->
                val sNum = season.seasonNumber ?: return@forEach
                val seasonUrl = "$url/season/$sNum"
                try {
                    val sRes = app.get(seasonUrl, headers = apiHeaders).parsedSafe<OpenAnimeSeasonResponse>()
                    sRes?.season?.episodes?.forEach { ep ->
                        val eNum = ep.episodeNumber ?: return@forEach
                        val epTitle = ep.name?.takeIf { it.isNotBlank() } ?: "Bölüm $eNum"
                        episodeList.add(
                            newEpisode("$url/season/$sNum/episode/$eNum") {
                                this.name = epTitle
                                this.season = sNum
                                this.episode = eNum
                                this.description = ep.summary
                                this.posterUrl = ep.avatar ?: poster
                            }
                        )
                    }
                } catch (e: Exception) {
                    // Skip failed seasons
                }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.plot = summary
            this.score = scoreVal
            tracker?.let { this.syncData = it.toMutableMap() }
            this.tags = res.genres
            this.year = res.firstAirDate?.substringAfterLast(".")?.toIntOrNull()
            addEpisodes(DubStatus.Subbed, episodeList)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val cdnHost = getCdnHost()
        val slug = data.substringBefore("/season/").substringAfterLast("/")
        val sNum = data.substringAfter("/season/").substringBefore("/episode/")

        val res = app.get(data, headers = apiHeaders).parsedSafe<OpenAnimeStreamResponse>() ?: return false

        val fansubs = res.fansubs
        if (!fansubs.isNullOrEmpty()) {
            coroutineScope {
                fansubs.map { f ->
                    async {
                        val fId = f.id ?: return@async
                        val fName = f.name ?: "Fansub"
                        val subUrl = if (data.contains("?")) "$data&fansub=$fId" else "$data?fansub=$fId"
                        try {
                            val subRes = app.get(subUrl, headers = apiHeaders).parsedSafe<OpenAnimeStreamResponse>() ?: return@async
                            subRes.episodeData?.files?.forEach { file ->
                                val fileName = file.file ?: return@forEach
                                val resInt = file.resolution ?: 1080
                                val streamUrl = "$cdnHost/animes/$slug/$sNum/$fileName"
                                val qualityName = "$fName (${resInt}p)"
                                callback.invoke(
                                    newExtractorLink(
                                        source = this@OpenAnime.name,
                                        name = qualityName,
                                        url = streamUrl,
                                        type = if (streamUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = getQuality(resInt)
                                        this.headers = mapOf("Referer" to "https://openani.me/")
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            // Skip failed fansub
                        }
                    }
                }.awaitAll()
            }
        } else {
            val files = res.episodeData?.files ?: return false
            files.forEach { file ->
                val fileName = file.file ?: return@forEach
                val resInt = file.resolution ?: 1080
                val streamUrl = "$cdnHost/animes/$slug/$sNum/$fileName"
                callback.invoke(
                    newExtractorLink(
                        source = this@OpenAnime.name,
                        name = "${this@OpenAnime.name} (${resInt}p)",
                        url = streamUrl,
                        type = if (streamUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getQuality(resInt)
                        this.headers = mapOf("Referer" to "https://openani.me/")
                    }
                )
            }
        }
        return true
    }

    suspend fun loadTags(): List<HomePageResponse>? {
        return listOfNotNull(
            loadTypeCategory("tv", "TV Serileri"),
            loadTypeCategory("movie", "Filmler"),
            loadTypeCategory("ova", "OVA'lar"),
            loadTypeCategory("special", "Özel Bölümler")
        )
    }

    private suspend fun loadTypeCategory(type: String, label: String): HomePageResponse? {
        try {
            val allItems = mutableListOf<OpenAnimeSearchItem>()
            for (page in 1..5) {
                val url = "https://api.openani.me/anime?limit=30&page=$page"
                val response = app.get(url, headers = apiHeaders).parsedSafe<OpenAnimeListResponse>() ?: break
                val animes = response.animes ?: break
                allItems.addAll(animes)
                if (animes.size < 30) break
            }

            val filteredItems = allItems.filter { it.type == type }

            val homeItems = filteredItems.mapNotNull { item ->
                val slug = item.slug ?: return@mapNotNull null
                val titleVal = item.turkish ?: item.english ?: item.originalName ?: "Anime"
                val poster = item.pictures?.avatar ?: item.pictures?.banner ?: "https://openani.me/favicon512.png"

                newAnimeSearchResponse(titleVal, "https://api.openani.me/anime/$slug", TvType.Anime) {
                    this.posterUrl = poster
                    item.episode?.let { addSub(it) } ?: addSub(1)
                    addDub(1)
                    item.score?.takeIf { it > 0 }?.let { this.score = Score.from10(it) } ?:
                    item.tmdbScore?.takeIf { it > 0 }?.let { this.score = Score.from10(it) }
                }
            }

            if (homeItems.isEmpty()) return null
            return newHomePageResponse(label, homeItems)
        } catch (e: Exception) {
            return null
        }
    }

    private fun getQuality(res: Int): Int {
        return when (res) {
            2160 -> Qualities.P2160.value
            1080 -> Qualities.P1080.value
            720  -> Qualities.P720.value
            480  -> Qualities.P480.value
            360  -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
