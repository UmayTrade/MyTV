package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

class DiziBal : MainAPI() {

    override var mainUrl = "https://dizibal.org/"
    override var name = "DiziBal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val apiUrl = "$mainUrl/api"
    private val mapper = jacksonObjectMapper()
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            return if (response.code == 403 || response.code == 503) {
                cloudflareKiller.intercept(chain)
            } else response
        }
    }

    // ================= DATA MODELS =================

    data class WListResponse(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("data") val data: List<WItem>?
    )

    data class WItem(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("_id") val _id: String?,
        @JsonProperty("imdb_id") val imdbId: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("title_tr") val titleTr: String?,
        @JsonProperty("title_en") val titleEn: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("name_tr") val nameTr: String?,
        @JsonProperty("name_en") val nameEn: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("year") val year: String?,
        @JsonProperty("genres") val genres: String?,
        @JsonProperty("imdb_rating") val imdbRating: String?,
        @JsonProperty("description") val description: String?
    )

    data class WDetailResponse(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("data") val data: WItemDetail?
    )

    data class WItemDetail(
        @JsonProperty("streamUrl") val streamUrl: String?,
        @JsonProperty("seasons") val seasons: List<WSeason>?,
        @JsonProperty("episodes") val episodes: List<WEpisode>?
    )

    data class WSeason(
        @JsonProperty("id") val id: String?,
        @JsonProperty("season_number") val seasonNumber: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("episodes") val episodes: List<WEpisode>?
    )

    data class WEpisode(
        @JsonProperty("id") val id: String?,
        @JsonProperty("episode_number") val episodeNumber: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("streamUrl") val streamUrl: String?,
        @JsonProperty("season_number") val seasonNumber: Int?
    )

    data class WStreamResponse(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("data") val data: WStreamData?
    )

    data class WStreamData(
        @JsonProperty("streamUrl") val streamUrl: String?,
        @JsonProperty("subtitles") val subtitles: List<WSubtitle>?
    )

    data class WSubtitle(
        @JsonProperty("lang") val lang: String?,
        @JsonProperty("url") val url: String?
    )

    // ================= UTIL =================

    private fun getMainUrl(): String {
        return mainUrl
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            else -> url
        }
    }

    private fun getPosterFromElement(element: Element): String? {
        var poster = element.selectFirst("img")?.attr("data-src")
            ?: element.selectFirst("img")?.attr("data-lazy-src")
            ?: element.selectFirst("img")?.attr("src")

        if (poster.isNullOrBlank()) {
            val srcset = element.selectFirst("img")?.attr("srcset")
            poster = srcset?.split(",")?.lastOrNull()?.trim()?.split(" ")?.firstOrNull()
        }

        return fixUrl(poster)
    }

    private fun getTitleFromItem(item: WItem): String {
        return item.titleTr ?: item.titleEn ?: item.title ?: item.nameTr ?: item.nameEn ?: item.name ?: "Bilinmiyor"
    }

    private fun getSlugFromItem(item: WItem): String {
        return item.slug ?: item._id ?: ""
    }

    private fun getPosterFromItem(item: WItem): String? {
        return fixUrl(item.poster)
    }

    private fun getTypeFromUrl(url: String): String {
        return when {
            url.contains("/movie/") || url.contains("/film/") -> "movie"
            url.contains("/series/") || url.contains("/dizi/") -> "series"
            url.contains("/anime/") -> "anime"
            else -> "series"
        }
    }

    private fun getSlugFromUrl(url: String): String {
        return url.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: ""
    }

    // Extension function for Element
    private fun Element.getPoster(): String? {
        val img = selectFirst("img")
        return img?.let { 
            fixUrl(it.attr("src").ifEmpty { it.attr("data-src") })
        }
    }

    // ================= MAIN PAGE =================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        // Determine type from request
        val type = when {
            request.name.contains("Film", ignoreCase = true) -> "movie"
            request.name.contains("Dizi", ignoreCase = true) -> "series"
            request.name.contains("Anime", ignoreCase = true) -> "anime"
            else -> "all"
        }

        // Platform filtering
        val platform = when {
            request.name.contains("Netflix", ignoreCase = true) -> "netflix"
            request.name.contains("Exxen", ignoreCase = true) -> "exxen"
            request.name.contains("Prime", ignoreCase = true) -> "prime-video"
            request.name.contains("Disney", ignoreCase = true) -> "disney"
            request.name.contains("BluTV", ignoreCase = true) -> "blutv"
            request.name.contains("Gain", ignoreCase = true) -> "gain"
            request.name.contains("TOD", ignoreCase = true) -> "tod"
            request.name.contains("HBOMAX", ignoreCase = true) -> "hbomax"
            request.name.contains("Tabii", ignoreCase = true) -> "tabii"
            else -> null
        }

        // Build search URL
        val searchUrl = buildString {
            append("$apiUrl/search?")
            if (type != "all") append("type=$type&")
            if (platform != null) append("platform=$platform&")
            if (page > 1) append("page=$page&")
            append("limit=20")
        }

        try {
            val response = app.get(searchUrl, interceptor = interceptor)
            val listResponse = mapper.readValue<WListResponse>(response.text)

            val items = listResponse.data?.mapNotNull { item ->
                val title = getTitleFromItem(item)
                val slug = getSlugFromItem(item)
                val link = "$mainUrl$type/$slug"

                val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries

                newTvSeriesSearchResponse(title, link, tvType) {
                    this.posterUrl = getPosterFromItem(item)
                    this.year = item.year?.toIntOrNull()
                }
            } ?: emptyList()

            return newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            // Fallback to HTML parsing if API fails
            val url = if (page == 1) request.data else "${request.data}/page/$page"
            val doc = app.get(url, interceptor = interceptor).document

            val items = doc.select("article, div.post-item, div.poster-item, div.video-item")
                .mapNotNull { element ->
                    val title = element.selectFirst("h2, h3, .entry-title, .title, img")
                        ?.let { if (it.tagName() == "img") it.attr("alt") else it.text() }
                        ?.trim()
                        ?: return@mapNotNull null

                    val link = element.selectFirst("a")?.attr("href")
                        ?.let { fixUrl(it) }
                        ?: return@mapNotNull null

                    val isMovie = link.contains("/film") || link.contains("/movie")

                    newTvSeriesSearchResponse(
                        title,
                        link,
                        if (isMovie) TvType.Movie else TvType.TvSeries
                    ) {
                        posterUrl = element.getPoster()
                    }
                }

            return newHomePageResponse(request.name, items)
        }
    }

    // ================= SEARCH =================

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, UTF_8.name())
        val url = "$apiUrl/search?q=$encodedQuery"

        try {
            val response = app.get(url, interceptor = interceptor)
            val listResponse = mapper.readValue<WListResponse>(response.text)

            return listResponse.data?.mapNotNull { item ->
                val title = getTitleFromItem(item)
                val slug = getSlugFromItem(item)
                
                val isMovie = item.title?.contains("film", ignoreCase = true) == true ||
                        item.titleEn?.contains("movie", ignoreCase = true) == true

                val link = "$mainUrl${if (isMovie) "movie" else "series"}/$slug"

                newTvSeriesSearchResponse(title, link, if (isMovie) TvType.Movie else TvType.TvSeries) {
                    posterUrl = getPosterFromItem(item)
                    year = item.year?.toIntOrNull()
                }
            } ?: emptyList()
        } catch (e: Exception) {
            // Fallback to HTML search
            val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
            val doc = app.get(searchUrl, interceptor = interceptor).document

            return doc.select("article, div.post-item, div.poster-item")
                .mapNotNull { element ->
                    val title = element.selectFirst("h2, h3, img")
                        ?.let { if (it.tagName() == "img") it.attr("alt") else it.text() }
                        ?.trim()
                        ?: return@mapNotNull null

                    val link = element.selectFirst("a")?.attr("href")
                        ?.let { fixUrl(it) }
                        ?: return@mapNotNull null

                    val isMovie = link.contains("/film") || link.contains("/movie")

                    newTvSeriesSearchResponse(
                        title,
                        link,
                        if (isMovie) TvType.Movie else TvType.TvSeries
                    ) {
                        posterUrl = element.getPoster()
                    }
                }
        }
    }

    // ================= LOAD =================

    override suspend fun load(url: String): LoadResponse {
        val type = getTypeFromUrl(url)
        val slug = getSlugFromUrl(url)

        // Try to get from API first
        try {
            val detailUrl = "$apiUrl/detail?slug=$slug&type=$type"
            val response = app.get(detailUrl, interceptor = interceptor)
            val detailResponse = mapper.readValue<WDetailResponse>(response.text)

            val detail = detailResponse.data ?: return fallbackLoad(url)

            val title = detail.seasons?.firstOrNull()?.title ?: 
                       detail.episodes?.firstOrNull()?.title ?:
                       slug.replace("-", " ").split(" ").joinToString(" ") { 
                           it.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                       }

            val description = detail.episodes?.firstOrNull()?.title ?: ""

            // Get poster from API or use default
            val poster = fixUrl("https://via.placeholder.com/300x450?text=$title")

            // Handle episodes
            if (type == "series" || type == "anime") {
                val episodes = mutableListOf<Episode>()

                // Get episodes from seasons
                detail.seasons?.forEach { season ->
                    season.episodes?.forEach { episode ->
                        val epUrl = episode.streamUrl ?: "$url/bolum-${episode.episodeNumber}"
                        episodes.add(
                            newEpisode(epUrl) {
                                name = episode.title ?: "Bölüm ${episode.episodeNumber}"
                                episode = episode.episodeNumber ?: 1
                                season = season.seasonNumber ?: 1
                            }
                        )
                    }
                }

                // If no episodes from seasons, try from episodes list
                if (episodes.isEmpty()) {
                    detail.episodes?.forEachIndexed { index, episode ->
                        val epUrl = episode.streamUrl ?: "$url/bolum-${episode.episodeNumber ?: index + 1}"
                        episodes.add(
                            newEpisode(epUrl) {
                                name = episode.title ?: "Bölüm ${episode.episodeNumber ?: index + 1}"
                                episode = episode.episodeNumber ?: index + 1
                                season = episode.seasonNumber ?: 1
                            }
                        )
                    }
                }

                if (episodes.isNotEmpty()) {
                    return newTvSeriesLoadResponse(title, url, 
                        if (type == "anime") TvType.Anime else TvType.TvSeries, 
                        episodes
                    ) {
                        this.posterUrl = poster
                        this.plot = description
                        this.year = null
                    }
                }

                // Fallback: try to get episodes from HTML
                return getEpisodesFromHtml(url, title, poster, description)
            }

            // For movies
            val streamUrl = detail.streamUrl ?: detail.episodes?.firstOrNull()?.streamUrl ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, streamUrl) {
                this.posterUrl = poster
                this.plot = description
                this.year = null
            }

        } catch (e: Exception) {
            // Fallback to HTML parsing
            return fallbackLoad(url)
        }
    }

    private suspend fun getEpisodesFromHtml(
        url: String,
        title: String,
        poster: String?,
        description: String
    ): LoadResponse {
        val doc = app.get(url, interceptor = interceptor).document

        val episodeElements = doc.select("a[href*='bolum'], a[href*='episode'], .episodes-list a")

        val episodes = episodeElements.mapIndexed { index, ep ->
            newEpisode(
                fixUrl(ep.attr("href")) ?: ""
            ) {
                name = ep.text().trim().ifEmpty { "Bölüm ${index + 1}" }
                episode = index + 1
                season = 1
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // Check if it's a movie
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    private suspend fun fallbackLoad(url: String): LoadResponse {
        val doc = app.get(url, interceptor = interceptor).document

        val title = doc.selectFirst("h1, .entry-title")?.text()?.trim() ?: "DiziBal"
        val description = doc.selectFirst(".entry-content p, .plot")?.text()
        val poster = fixUrl(doc.selectFirst("img.wp-post-image, .poster img")?.attr("src"))

        val episodeElements = doc.select("a[href*='bolum'], a[href*='episode'], .episodes-list a")

        val episodes = episodeElements.mapIndexed { index, ep ->
            newEpisode(
                fixUrl(ep.attr("href")) ?: ""
            ) {
                name = ep.text().trim().ifEmpty { "Bölüm ${index + 1}" }
                episode = index + 1
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // ================= LOAD LINKS =================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val visited = mutableSetOf<String>()

        suspend fun extract(url: String) {
            if (visited.contains(url)) return
            visited.add(url)

            val res = app.get(url, referer = mainUrl, interceptor = interceptor)
            val html = res.text
            val doc = res.document

            // Check if it's a JSON response
            if (html.trim().startsWith("{")) {
                try {
                    val streamResponse = mapper.readValue<WStreamResponse>(html)
                    streamResponse.data?.streamUrl?.let { streamUrl ->
                        // Extract subtitles
                        streamResponse.data?.subtitles?.forEach { sub ->
                            subtitleCallback(
                                SubtitleFile(
                                    sub.lang ?: "Unknown",
                                    sub.url ?: ""
                                )
                            )
                        }

                        // Recursively extract from the stream URL
                        extract(streamUrl)
                        return
                    }
                } catch (e: Exception) {
                    // Not a JSON response, continue
                }
            }

            // m3u8 streams
            Regex("""https?://[^"' ]+\.m3u8[^"' ]*""")
                .findAll(html)
                .forEach {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name M3U8",
                            url = it.value,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = url
                            quality = Qualities.Unknown.value
                        }
                    )
                }

            // mp4 streams
            Regex("""https?://[^"' ]+\.mp4[^"' ]*""")
                .findAll(html)
                .forEach {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name MP4",
                            url = it.value,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            referer = url
                            quality = Qualities.Unknown.value
                        }
                    )
                }

            // iframe recursive
            doc.select("iframe").forEach {
                val src = fixUrl(it.attr("src"))
                if (!src.isNullOrBlank()) {
                    extract(src)
                }
            }

            // video sources
            doc.select("video source, video").forEach {
                val src = fixUrl(it.attr("src"))
                if (!src.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name Video",
                            url = src,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            referer = url
                            quality = Qualities.Unknown.value
                        }
                    )
                }
            }

            // JavaScript embedded URLs
            Regex("""(?:src|file|url|source)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4|mkv|avi)[^"']*)["']""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .forEach {
                    val videoUrl = it.groupValues[1]
                    if (videoUrl.isNotBlank()) {
                        val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name JS",
                                url = videoUrl,
                                type = type
                            ) {
                                referer = url
                                quality = Qualities.Unknown.value
                            }
                        )
                    }
                }

            // HLS manifest in JavaScript
            Regex("""https?://[^"' ]+\.m3u8[^"' ]*""")
                .findAll(html)
                .forEach {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name HLS",
                            url = it.value,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = url
                            quality = Qualities.Unknown.value
                        }
                    )
                }

            // Try to find embedded player
            doc.select("div[data-player], .player, #player, .video-player, .embed-player").forEach { player ->
                val dataSrc = player.attr("data-src")
                val dataUrl = player.attr("data-url")
                val dataFile = player.attr("data-file")
                
                val playerUrl = when {
                    dataSrc.isNotBlank() -> dataSrc
                    dataUrl.isNotBlank() -> dataUrl
                    dataFile.isNotBlank() -> dataFile
                    else -> null
                }
                
                if (!playerUrl.isNullOrBlank()) {
                    extract(playerUrl)
                }
            }
        }

        extract(data)
        return true
    }

    // ================= QUICK SEARCH =================

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }
}
