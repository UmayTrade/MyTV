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
        /*
         * OpenAnime'nin SvelteKit verisi saf JSON değildir:
         * data = [{type:"data",data:{...}}];
         *
         * Bu nedenle önce [] bloğunu dengeli şekilde çıkarıyor,
         * sonra JavaScript object-literal key'lerini JSON key'lerine
         * dönüştürüyoruz.
         */
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
     * Poster URL'sini anime veya season objesinden çıkarır.
     *
     * Desteklenen alanlar:
     *   poster
     *   poster_path
     *   pictures.avatar
     *   pictures.banner
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

        // Animes ana liste
        val animes = dataObj.optJSONArray("animes")
        if (animes != null) {
            for (i in 0 until animes.length()) {
                val anime = animes.optJSONObject(i) ?: continue
                val res = animeToSearchResponse(anime) ?: continue
                if (seen.add(res.url)) items.add(res)
            }
        }

        // Popular animes (bazı sayfalarda ayrı)
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

        // API üzerinden arama
        val searchUrl = "$apiLink/anime/search?q=${query.encodeUrl()}"
        val apiResult = runCatching {
            val resp = app.get(searchUrl, headers = apiHeaders()).text
            parseSearchApiResponse(resp)
        }.getOrNull()

        if (!apiResult.isNullOrEmpty()) return apiResult

        // Fallback: keşfet sayfasını çek ve filtrele
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

        // Data objesinde anime ara
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

        // Türler
        val tags = mutableListOf<String>()
        anime.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { tags.add(it) }
            }
        }

        // Bölümler — "seasons" dizisinden
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

                // Sezonun kendi posteri varsa onu kullan.
                val seasonPoster = buildPosterUrl(season) ?: poster

                // Her bölüm için episode oluştur
                for (ep in 1..episodeCount) {
                    // OpenAni URL formatı: /anime/{slug}/{season}/{episode} olabilir
                    // veya /watch/{slug}/{ep} — kesin format API'den gelmeli
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

        // Doğrudan obje olabilir
        val direct = dataObj.optJSONObject("anime")
        if (direct != null && direct.optString("slug") == slug) return direct

        return null
    }

    private suspend fun fallbackLoad(url: String, html: String): LoadResponse {
        // HTML'den title/postercıkar
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

        // 1. SvelteKit data içinde video kaynakları
        val jsonArray = extractSvelteData(html)
        val dataObj = if (jsonArray != null) extractDataObject(jsonArray) else null

        if (dataObj != null) {
            // sources / videos / players
            val sources = dataObj.optJSONArray("sources")
                ?: dataObj.optJSONArray("videos")
                ?: dataObj.optJSONArray("players")
                ?: dataObj.optJSONObject("episode")?.optJSONArray("sources")

            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val src = sources.optJSONObject(i) ?: continue
                    val url = src.optString("url").takeIf { it.isNotBlank() }
                        ?: src.optString("file").takeIf { it.isNotBlank() }
                        ?: continue
                    if (url.isAdUrl()) continue

                    val label = src.optString("label").takeIf { it.isNotBlank() }
                        ?: src.optString("name").takeIf { it.isNotBlank() }
                        ?: "Player"

                    emitSource(url, label, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // 2. HTML'de <video> ve <source>
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

        // 3. Script içinde m3u8/mp4
        val urlRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
        doc.select("script:not([src])").forEach { script ->
            val content = script.data()
            urlRegex.findAll(content).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.isAdUrl()) return@forEach
                emitSource(videoUrl, "Script", subtitleCallback, callback)
                found = true
            }
        }

        // 4. iframe embed
        doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = fixUrlNull(
                iframe.attr("src").takeIf { it.isNotBlank() }
                    ?: iframe.attr("data-src")
            ) ?: return@forEach
            if (src.isAdUrl()) return@forEach

            loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
            found = true
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
