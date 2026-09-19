package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * OpenAnime Sağlayıcısı
 *
 * Site: https://openani.me
 * Framework: SvelteKit
 * API: https://api.openani.me
 * Veri: Inline JSON (script içinde data = [{type:"data", data:{...}}])
 * Poster: TMDB (image.tmdb.org)
 * Oynatıcı: HLS (m3u8) + harici embed
 */
class OpenAnime : MainAPI() {

    override var mainUrl = "https://openani.me"
    override var name = "OpenAnime"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // -------------------------------------------------------------------------
    // Sabitler — HTML'den alındı
    // -------------------------------------------------------------------------

    private val apiLink = "https://api.openani.me"
    private val kmsLink = "https://kms.openani.me"
    private val cdnLinkTemplate = "https://de2---vn-t9g4tsan-5qcl.yeshi.eu.org"
    private val tmdbImageBase = "https://image.tmdb.org/t/p/original"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

    private val adDomains = setOf(
        "a-ads.com",
        "googlesyndication.com",
        "doubleclick.net",
        "adservice.google.com"
    )

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    private fun apiHeaders(): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "application/json, text/plain, */*"
    )

    // -------------------------------------------------------------------------
    // JSON Çıkarma Yardımcıları
    // -------------------------------------------------------------------------

    /**
     * SvelteKit HTML içindeki `data = [...]` JSON dizisini çıkarır.
     * Format: `const data = [{"type":"data","data":{...}},...];`
     */
    private fun extractSvelteData(html: String): JSONArray? {
        val dataMatch = Regex("""(?:const|let|var)?\s*data\s*=\s*\[""")
            .find(html)
            ?: Regex("""\bdata\s*=\s*\[""").find(html)
            ?: return null

        val arrayStart = html.indexOf('[', dataMatch.range.first)
        if (arrayStart < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        var end = -1

        for (i in arrayStart until html.length) {
            val c = html[i]

            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }

        if (end < 0) return null

        var source = html.substring(arrayStart, end + 1)

        source = source.replace(
            Regex("""([\{,])\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*:"""),
            "$1\"$2\":"
        )

        source = source
            .replace(Regex("""\bvoid\s+0\b"""), "null")
            .replace(Regex("""\bundefined\b"""), "null")
            .replace(Regex("""\bNaN\b"""), "null")
            .replace(Regex("""\bInfinity\b"""), "null")

        return runCatching {
            JSONArray(source)
        }.getOrNull()
    }

    /**
     * SvelteKit data dizisinden asıl anime objesini bulur.
     * Yapı: [{type:"data", data:{animes:[...], popularAnimes:[...]}}]
     */
    private fun extractDataObject(jsonArray: JSONArray): JSONObject? {
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(i) ?: continue
            if (item.optString("type") == "data") {
                return item.optJSONObject("data")
            }
        }
        return null
    }

    /**
     * Belirli bir data index'indeki data objesini döndürür.
     */
    private fun extractDataObjectAt(jsonArray: JSONArray, index: Int): JSONObject? {
        if (index < 0 || index >= jsonArray.length()) return null
        val item = jsonArray.optJSONObject(index) ?: return null
        if (item.optString("type") != "data") return null
        return item.optJSONObject("data")
    }

    /**
     * Poster URL'sini anime veya season objesinden çıkarır.
     */
    private fun buildPosterUrl(obj: JSONObject?): String? {
        if (obj == null) return null

        obj.optString("poster")
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { return it }

        obj.optString("poster_path")
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let {
                return if (it.startsWith("http")) {
                    it
                } else {
                    "$tmdbImageBase${if (it.startsWith("/")) it else "/$it"}"
                }
            }

        val pictures = obj.optJSONObject("pictures")

        pictures?.optString("avatar")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { return it }

        pictures?.optString("banner")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { return it }

        return null
    }

    /** Anime objesinden SearchResponse oluşturur. */
    private fun animeToSearchResponse(anime: JSONObject): SearchResponse? {
        val slug = anime.optString("slug").takeIf { it.isNotBlank() } ?: return null
        val title = anime.optString("turkish").takeIf { it.isNotBlank() }
            ?: anime.optString("english").takeIf { it.isNotBlank() }
            ?: anime.optString("romaji").takeIf { it.isNotBlank() }
            ?: anime.optString("originalName").takeIf { it.isNotBlank() }
            ?: return null

        val poster = buildPosterUrl(anime)

        return newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // -------------------------------------------------------------------------
    // Ana Sayfa
    // -------------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "/explore" to "Keşfet",
        "/popular" to "Popüler",
        "/all"     to "Tüm Animeler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl${request.data}"
        val html = app.get(url, headers = headers()).text

        val jsonArray = extractSvelteData(html) ?: return newHomePageResponse(
            HomePageList(request.name, emptyList()),
            hasNext = false
        )
        val dataObj = extractDataObject(jsonArray) ?: return newHomePageResponse(
            HomePageList(request.name, emptyList()),
            hasNext = false
        )

        val items = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        val animes = dataObj.optJSONArray("animes")
        if (animes != null) {
            for (i in 0 until animes.length()) {
                val anime = animes.optJSONObject(i) ?: continue
                val res = animeToSearchResponse(anime) ?: continue
                if (seen.add(res.url)) items.add(res)
            }
        }

        val popular = dataObj.optJSONArray("popularAnimes")
        if (popular != null) {
            for (i in 0 until popular.length()) {
                val anime = popular.optJSONObject(i) ?: continue
                val res = animeToSearchResponse(anime) ?: continue
                if (seen.add(res.url)) items.add(res)
            }
        }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    // -------------------------------------------------------------------------
    // Arama
    // -------------------------------------------------------------------------

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val searchUrl = "$apiLink/anime/search?q=${query.encodeUrl()}"
        val apiResult = runCatching {
            val resp = app.get(searchUrl, headers = apiHeaders()).text
            parseSearchApiResponse(resp)
        }.getOrNull()

        if (!apiResult.isNullOrEmpty()) return apiResult

        val html = runCatching {
            app.get("$mainUrl/explore", headers = headers()).text
        }.getOrNull() ?: return emptyList()

        val jsonArray = extractSvelteData(html) ?: return emptyList()
        val dataObj = extractDataObject(jsonArray) ?: return emptyList()
        val animes = dataObj.optJSONArray("animes") ?: return emptyList()

        val queryLower = query.lowercase()
        val results = mutableListOf<SearchResponse>()
        for (i in 0 until animes.length()) {
            val anime = animes.optJSONObject(i) ?: continue
            val title = anime.optString("turkish").takeIf { it.isNotBlank() }
                ?: anime.optString("english").takeIf { it.isNotBlank() }
                ?: anime.optString("romaji").takeIf { it.isNotBlank() }
                ?: continue
            if (title.lowercase().contains(queryLower)) {
                animeToSearchResponse(anime)?.let { results.add(it) }
            }
        }
        return results
    }

    private fun parseSearchApiResponse(raw: String): List<SearchResponse> {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val arr = json.optJSONArray("results")
            ?: json.optJSONArray("data")
            ?: json.optJSONArray("animes")
            ?: return emptyList()

        val result = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val anime = arr.optJSONObject(i) ?: continue
            animeToSearchResponse(anime)?.let { result.add(it) }
        }
        return result
    }

    private fun String.encodeUrl(): String =
        URLEncoder.encode(this, "UTF-8")

    // -------------------------------------------------------------------------
    // Detay Sayfası
    // -------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = headers()).text

        val jsonArray = extractSvelteData(html)
        val dataObj = if (jsonArray != null) extractDataObject(jsonArray) else null

        val anime = findAnimeInData(dataObj, url)
            ?: return fallbackLoad(url, html)

        val slug = anime.optString("slug").takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/")

        val title = anime.optString("turkish").takeIf { it.isNotBlank() }
            ?: anime.optString("english").takeIf { it.isNotBlank() }
            ?: anime.optString("romaji").takeIf { it.isNotBlank() }
            ?: anime.optString("originalName").takeIf { it.isNotBlank() }
            ?: "Bilinmeyen Anime"

        val poster = buildPosterUrl(anime)

        val plot = anime.optString("summary").takeIf { it.isNotBlank() }

        val tags = mutableListOf<String>()
        anime.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { tags.add(it) }
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasons = anime.optJSONArray("seasons")

        if (seasons != null && seasons.length() > 0) {
            for (s in 0 until seasons.length()) {
                val season = seasons.optJSONObject(s) ?: continue
                val seasonNum = season.optInt(
                    "season_number",
                    season.optInt("tmdb_season_number", s + 1)
                )
                val seasonName = season.optString("name").takeIf { it.isNotBlank() }
                val episodeCount = season.optInt("episode_count", 0)

                val seasonPoster = buildPosterUrl(season) ?: poster

                for (ep in 1..episodeCount) {
                    val epUrl = "$mainUrl/anime/$slug/$seasonNum/$ep"
                    val epTitle = if (episodeCount > 1) {
                        seasonName?.let { "$it - $ep. Bölüm" } ?: "$ep. Bölüm"
                    } else {
                        seasonName
                    }

                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epTitle
                            this.episode = ep
                            this.season = seasonNum
                            this.posterUrl = seasonPoster
                        }
                    )
                }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    /** Data objesi içinde slug'a göre anime bulur. */
    private fun findAnimeInData(dataObj: JSONObject?, url: String): JSONObject? {
        if (dataObj == null) return null
        val slug = url.substringAfterLast("/")

        val arrays = listOf("animes", "popularAnimes", "data", "results")
        for (key in arrays) {
            val arr = dataObj.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val anime = arr.optJSONObject(i) ?: continue
                if (anime.optString("slug") == slug) return anime
            }
        }

        val direct = dataObj.optJSONObject("anime")
        if (direct != null && direct.optString("slug") == slug) return direct

        return null
    }

    private suspend fun fallbackLoad(url: String, html: String): LoadResponse {
        val doc = org.jsoup.Jsoup.parse(html)

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Bilinmeyen Anime"

        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
        )

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = doc.selectFirst("meta[name='description']")?.attr("content")
        }
    }

    // -------------------------------------------------------------------------
    // Video Linkleri
    // -------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, headers = headers()).text

        var found = false

        // SvelteKit data dizisini çıkar
        val jsonArray = extractSvelteData(html)

        if (jsonArray != null) {
            // Tüm data objelerini dolaş, requestResponse içereni bul
            for (i in 0 until jsonArray.length()) {
                val dataObj = extractDataObjectAt(jsonArray, i) ?: continue

                val requestResponse = dataObj.optJSONObject("requestResponse") ?: continue
                val episodeData = requestResponse.optJSONObject("episodeData") ?: continue

                // CDN_LINK hem data kökünde hem requestResponse içinde olabilir
                val cdnLink = dataObj.optString("CDN_LINK").takeIf { it.isNotBlank() }
                    ?: requestResponse.optString("CDN_LINK").takeIf { it.isNotBlank() }
                    ?: "$cdnLinkTemplate/animes/"

                val files = episodeData.optJSONArray("files") ?: continue

                for (j in 0 until files.length()) {
                    val fileObj = files.optJSONObject(j) ?: continue
                    val fileName = fileObj.optString("file").takeIf { it.isNotBlank() } ?: continue
                    val resolution = fileObj.optInt("resolution", 0)

                    // CDN linkini oluştur
                    val videoUrl = if (fileName.startsWith("http")) {
                        fileName
                    } else {
                        cdnLink.trimEnd('/') + "/" + fileName.trimStart('/')
                    }

                    if (videoUrl.isAdUrl()) continue

                    val label = if (resolution > 0) "${resolution}p" else "Video"

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name [$label]",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = when (resolution) {
                                2160 -> Qualities.P2160.value
                                1440 -> Qualities.P1440.value
                                1080 -> Qualities.P1080.value
                                720  -> Qualities.P720.value
                                480  -> Qualities.P480.value
                                360  -> Qualities.P360.value
                                240  -> Qualities.P240.value
                                else -> Qualities.Unknown.value
                            }
                            this.referer = "$mainUrl/"
                            this.headers = headers()
                        }
                    )
                    found = true
                }
            }
        }

        // Fallback: HTML'de doğrudan video/iframe araması
        if (!found) {
            val doc = org.jsoup.Jsoup.parse(html)

            doc.select("video source[src], video[src]").forEach { el ->
                val src = fixUrlNull(
                    el.attr("src").takeIf { it.isNotBlank() }
                        ?: el.attr("data-src")
                ) ?: return@forEach
                if (src.isAdUrl()) return@forEach
                emitSource(src, "Direct", subtitleCallback, callback)
                found = true
            }

            val urlRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            doc.select("script:not([src])").forEach { script ->
                urlRegex.findAll(script.data()).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isAdUrl()) return@forEach
                    emitSource(videoUrl, "Script", subtitleCallback, callback)
                    found = true
                }
            }

            doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
                val src = fixUrlNull(
                    iframe.attr("src").takeIf { it.isNotBlank() }
                        ?: iframe.attr("data-src")
                ) ?: return@forEach
                if (src.isAdUrl()) return@forEach

                loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                found = true
            }
        }

        return found
    }

    private suspend fun emitSource(
        url: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val lower = url.lowercase()
        when {
            lower.contains(".m3u8") -> {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name [$label]",
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = parseQuality(label)
                        this.referer = "$mainUrl/"
                        this.headers = headers()
                    }
                )
            }
            lower.contains(".mp4") || lower.contains(".webm") -> {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name [$label]",
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = parseQuality(label)
                        this.referer = "$mainUrl/"
                        this.headers = headers()
                    }
                )
            }
            else -> {
                loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
            }
        }
    }

    private fun parseQuality(label: String): Int {
        val l = label.lowercase()
        return when {
            l.contains("2160") || l.contains("4k") -> Qualities.P2160.value
            l.contains("1440") -> Qualities.P1440.value
            l.contains("1080") -> Qualities.P1080.value
            l.contains("720")  -> Qualities.P720.value
            l.contains("480")  -> Qualities.P480.value
            l.contains("360")  -> Qualities.P360.value
            l.contains("240")  -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.isAdUrl(): Boolean =
        adDomains.any { contains(it, ignoreCase = true) }
}
