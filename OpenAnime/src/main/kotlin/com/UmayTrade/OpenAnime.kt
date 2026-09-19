package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * OpenAnime Sağlayıcısı
 *
 * Site: https://openani.me / https://openanime.org
 * Yapı: Next.js (Pages Router / App Router) tabanlı SPA
 *
 * Veri kaynakları (öncelik sırası):
 *   1. /_next/data/{buildId}/... JSON endpoint
 *   2. __NEXT_DATA__ script etiketi
 *   3. React Server Components Flight Data (self.__next_f.push)
 *   4. HTML DOM scraping (fallback)
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
    // Sabitler
    // -------------------------------------------------------------------------

    private val domainConfigUrl =
        "https://raw.githubusercontent.com/ulgenzade/ulgencs3/master/domains.json"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

    private val nextDataSelector = "script#__NEXT_DATA__"

    private val adDomains = setOf(
        "a-ads.com",
        "googlesyndication.com",
        "doubleclick.net",
        "adservice.google.com"
    )

    // Poster için aranacak alan adları (öncelik sırasına göre)
    private val posterKeys = listOf(
        "coverImage", "cover", "poster", "image",
        "thumbnail", "posterUrl", "coverUrl", "img",
        "imageUrl", "posterImage", "cover_image"
    )

    // Bölüm listesi için aranacak alan adları
    private val episodeKeys = listOf(
        "episodes", "episodeList", "eps", "bolumler",
        "episode_list", "episodeItems"
    )

    // -------------------------------------------------------------------------
    // Header yardımcıları
    // -------------------------------------------------------------------------

    private fun commonHeaders(): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    private fun jsonHeaders(): Map<String, String> = commonHeaders() + mapOf(
        "Accept" to "application/json, text/plain, */*",
        "X-Requested-With" to "XMLHttpRequest"
    )

    // -------------------------------------------------------------------------
    // Init
    // -------------------------------------------------------------------------

    private val initMutex = Mutex()

    @Volatile
    private var isInitialized = false

    private var nextBuildId: String? = null

    private suspend fun ensureInit() {
        if (isInitialized) return
        initMutex.withLock {
            if (isInitialized) return
            try {
                // 1. Dinamik domain
                runCatching {
                    val config = app.get(domainConfigUrl).text
                    JSONObject(config)
                        .optString("openanime")
                        .takeIf { it.isNotBlank() }
                        ?.let { mainUrl = it.trimEnd('/') }
                }

                // 2. buildId
                runCatching {
                    val doc = app.get(mainUrl, headers = commonHeaders()).document
                    nextBuildId = extractBuildId(doc)
                }

                isInitialized = true
            } catch (_: Exception) {
                // sonraki çağrıda tekrar denenir
            }
        }
    }

    private fun extractBuildId(doc: Document): String? {
        val raw = doc.selectFirst(nextDataSelector)?.data() ?: return null
        return runCatching {
            JSONObject(raw).optString("buildId").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun nextApiUrl(path: String): String {
        val cleanPath = path.trimStart('/')
        val bid = nextBuildId
        return if (bid.isNullOrBlank()) {
            "$mainUrl/api/$cleanPath"
        } else {
            "$mainUrl/_next/data/$bid/$cleanPath.json"
        }
    }

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, "UTF-8")

    private fun String.isAdUrl(): Boolean =
        adDomains.any { contains(it, ignoreCase = true) }

    // -------------------------------------------------------------------------
    // JSON yardımcıları — poster ve episode için esnek tarama
    // -------------------------------------------------------------------------

    /** Bir JSONObject içinden poster URL'sini esnek şekilde çıkarır. */
    private fun extractPoster(obj: JSONObject?): String? {
        if (obj == null) return null

        // 1. Doğrudan alan adları
        for (key in posterKeys) {
            val v = obj.optString(key).takeIf { it.isNotBlank() && it.startsWith("http") }
            if (v != null) return fixUrlNull(v)
        }

        // 2. İç içe objeler (anime, data, detail, series, info)
        val nestedKeys = listOf("anime", "data", "detail", "series", "info", "attributes")
        for (nk in nestedKeys) {
            val nested = obj.optJSONObject(nk) ?: continue
            val v = extractPoster(nested)
            if (v != null) return v
        }

        // 3. images / posters array
        val imgArr = obj.optJSONArray("images") ?: obj.optJSONArray("posters")
        if (imgArr != null && imgArr.length() > 0) {
            val first = imgArr.opt(0)
            when (first) {
                is String -> if (first.startsWith("http")) return fixUrlNull(first)
                is JSONObject -> {
                    val v = first.optString("url").takeIf { it.isNotBlank() }
                        ?: first.optString("src").takeIf { it.isNotBlank() }
                    if (v != null) return fixUrlNull(v)
                }
            }
        }

        return null
    }

    /** Bir JSONObject içinden bölüm dizisini esnek şekilde bulur. */
    private fun findEpisodesArray(obj: JSONObject?): JSONArray? {
        if (obj == null) return null

        // 1. Doğrudan alan adları
        for (key in episodeKeys) {
            val arr = obj.optJSONArray(key)
            if (arr != null && arr.length() > 0) return arr
        }

        // 2. İç içe objeler
        val nestedKeys = listOf("anime", "data", "detail", "series", "info")
        for (nk in nestedKeys) {
            val nested = obj.optJSONObject(nk) ?: continue
            val arr = findEpisodesArray(nested)
            if (arr != null) return arr
        }

        // 3. Herhangi bir JSONArray içinde "number" veya "episode" alanı olan
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k)
            if (v is JSONArray && v.length() > 0) {
                val first = v.optJSONObject(0)
                if (first != null &&
                    (first.has("number") || first.has("episode") || first.has("episodeNumber"))
                ) {
                    return v
                }
            }
        }

        return null
    }

    /** Bir bölüm objesinden URL çıkarır. */
    private fun extractEpisodeUrl(
        ep: JSONObject,
        slug: String,
        epNum: Int
    ): String? {
        // 1. Doğrudan URL alanları
        val urlKeys = listOf("url", "link", "watchUrl", "href", "path")
        for (key in urlKeys) {
            val v = ep.optString(key).takeIf { it.isNotBlank() }
            if (v != null) {
                return when {
                    v.startsWith("http") -> v
                    v.startsWith("/")    -> "$mainUrl$v"
                    else                 -> "$mainUrl/$v"
                }
            }
        }

        // 2. slug alanı
        val epSlug = ep.optString("slug").takeIf { it.isNotBlank() }
            ?: ep.optString("episodeSlug").takeIf { it.isNotBlank() }

        return when {
            epSlug == null -> null
            epSlug.startsWith("http") -> epSlug
            epSlug.startsWith("/")    -> "$mainUrl$epSlug"
            epSlug.contains("/")      -> "$mainUrl/$epSlug"
            else                      -> "$mainUrl/anime/$slug/$epSlug"
        }
    }

    // -------------------------------------------------------------------------
    // Ana Sayfa
    // -------------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "/explore" to "Son Bölümler",
        "/popular" to "Popüler Animeler",
        "/all"     to "Tüm Animeler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        ensureInit()
        val items = mutableListOf<SearchResponse>()

        // 1. Next.js data endpoint
        val nextUrl = "${nextApiUrl(request.data)}?page=$page"
        val jsonItems = runCatching {
            val resp = app.get(nextUrl, headers = jsonHeaders()).text
            parseAnimeListFromJson(resp)
        }.getOrNull()

        if (!jsonItems.isNullOrEmpty()) {
            items.addAll(jsonItems)
        } else {
            // 2. HTML fallback
            val htmlUrl = "$mainUrl${request.data}?page=$page"
            val doc = runCatching {
                app.get(htmlUrl, headers = commonHeaders()).document
            }.getOrNull()

            if (doc != null) {
                items.addAll(parseAnimeListFromHtml(doc))
            }
        }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    private fun parseAnimeListFromJson(raw: String): List<SearchResponse> {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val props = json.optJSONObject("props")?.optJSONObject("pageProps")
            ?: json.optJSONObject("pageProps")
            ?: return emptyList()

        val arr = props.optJSONArray("animes")
            ?: props.optJSONArray("items")
            ?: props.optJSONArray("data")
            ?: props.optJSONArray("results")
            ?: findFirstAnimeArray(props)
            ?: return emptyList()

        val result = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("title").takeIf { it.isNotBlank() }
                ?: item.optString("name").takeIf { it.isNotBlank() }
                ?: continue

            val slug = item.optString("slug").takeIf { it.isNotBlank() }
                ?: item.optInt("id").toString().takeIf { it != "0" }
                ?: continue

            val poster = extractPoster(item)

            result.add(
                newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return result
    }

    private fun findFirstAnimeArray(obj: JSONObject): JSONArray? {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val v = obj.opt(keys.next())
            if (v is JSONArray && v.length() > 0) {
                val first = v.optJSONObject(0) ?: continue
                if (first.has("title") || first.has("name") || first.has("slug")) {
                    return v
                }
            }
        }
        return null
    }

    private fun parseAnimeListFromHtml(doc: Document): List<SearchResponse> {
        val selectors = listOf(
            "div.anime-card",
            "article.anime",
            "div.card",
            "a[href*='/anime/']"
        )
        val items = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (sel in selectors) {
            doc.select(sel).forEach { el ->
                val res = el.toSearchResult() ?: return@forEach
                if (seen.add(res.url)) items.add(res)
            }
            if (items.isNotEmpty()) break
        }
        return items
    }

    // -------------------------------------------------------------------------
    // Arama
    // -------------------------------------------------------------------------

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        ensureInit()
        if (query.isBlank()) return emptyList()

        val apiUrl = "$mainUrl/api/search?q=${query.urlEncode()}"
        val apiItems = runCatching {
            val resp = app.get(apiUrl, headers = jsonHeaders()).text
            parseSearchFromJson(resp)
        }.getOrNull()

        if (!apiItems.isNullOrEmpty()) return apiItems

        val htmlUrl = "$mainUrl/search?q=${query.urlEncode()}"
        val doc = runCatching {
            app.get(htmlUrl, headers = commonHeaders()).document
        }.getOrNull() ?: return emptyList()

        return parseAnimeListFromHtml(doc)
    }

    private fun parseSearchFromJson(raw: String): List<SearchResponse> {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val arr = json.optJSONArray("results")
            ?: json.optJSONArray("data")
            ?: json.optJSONArray("animes")
            ?: findFirstAnimeArray(json)
            ?: return emptyList()

        val result = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("title").takeIf { it.isNotBlank() }
                ?: item.optString("name").takeIf { it.isNotBlank() }
                ?: continue

            val slug = item.optString("slug").takeIf { it.isNotBlank() }
                ?: item.optInt("id").toString().takeIf { it != "0" }
                ?: continue

            val poster = extractPoster(item)

            result.add(
                newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Detay
    // -------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        ensureInit()

        val slug = url.substringAfterLast("/").substringBefore("?")

        // 1. Next.js data endpoint
        val jsonResp = runCatching {
            val apiUrl = nextApiUrl("/anime/$slug")
            app.get(apiUrl, headers = jsonHeaders()).text
        }.getOrNull()

        if (!jsonResp.isNullOrBlank()) {
            val parsed = parseAnimeDetailFromJson(jsonResp, url, slug)
            if (parsed != null) return parsed
        }

        // 2. HTML scraping (__NEXT_DATA__ dahil)
        val doc = runCatching {
            app.get(url, headers = commonHeaders()).document
        }.getOrNull()

        if (doc != null) {
            // 2a. __NEXT_DATA__ içinden
            val nextData = doc.selectFirst(nextDataSelector)?.data()
            if (!nextData.isNullOrBlank()) {
                val parsed = parseAnimeDetailFromJson(nextData, url, slug)
                if (parsed != null) return parsed
            }

            // 2b. Flight Data (self.__next_f.push)
            val flightData = extractFlightData(doc)
            if (flightData != null) {
                val parsed = parseAnimeDetailFromJson(flightData, url, slug)
                if (parsed != null) return parsed
            }
        }

        // 3. Saf HTML DOM fallback
        return parseAnimeDetailFromHtml(url, doc)
    }

    /**
     * React Server Components Flight Data'yı çıkarır.
     * Next.js App Router, veriyi `self.__next_f.push([1, "..."])` script'lerine yazar.
     */
    private fun extractFlightData(doc: Document): String? {
        val scripts = doc.select("script")
        val sb = StringBuilder()

        for (script in scripts) {
            val content = script.data()
            if (content.contains("__next_f.push")) {
                // JSON string'lerini birleştir
                val regex = Regex("""__next_f\.push\(\[\d+,\s*"((?:[^"\\]|\\.)*)"\]\)""")
                regex.findAll(content).forEach { match ->
                    val raw = match.groupValues[1]
                    // Unescape
                    sb.append(
                        raw.replace("\\\"", "\"")
                            .replace("\\n", "\n")
                            .replace("\\\\", "\\")
                    )
                }
            }
        }

        if (sb.isEmpty()) return null

        // Flight Data'da JSON objeleri aranır — en kapsamlı olanı döndür
        val text = sb.toString()
        val startIdx = text.indexOf("{\"pageProps\"")
        if (startIdx < 0) return null

        // Dengeli parantez sayarak JSON objesini çıkar
        var depth = 0
        var inStr = false
        var esc = false
        val start = startIdx

        for (i in start until text.length) {
            val c = text[i]
            when {
                esc -> esc = false
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private suspend fun parseAnimeDetailFromJson(
        raw: String,
        url: String,
        slug: String
    ): LoadResponse? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        // Farklı Next.js yapılarını dene
        val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps")
            ?: json.optJSONObject("pageProps")
            ?: json.optJSONObject("props")
            ?: json

        // Anime objesini bul
        val anime = pageProps.optJSONObject("anime")
            ?: pageProps.optJSONObject("data")?.optJSONObject("anime")
            ?: pageProps.optJSONObject("data")
            ?: pageProps.optJSONObject("detail")
            ?: pageProps.optJSONObject("series")
            ?: pageProps

        // Title
        val title = anime.optString("title").takeIf { it.isNotBlank() }
            ?: anime.optString("name").takeIf { it.isNotBlank() }
            ?: anime.optString("animeTitle").takeIf { it.isNotBlank() }
            ?: return null

        // Poster — esnek tarama
        val poster = extractPoster(anime)
            ?: extractPoster(pageProps)
            ?: extractPoster(json)

        // Plot
        val description = anime.optString("description").takeIf { it.isNotBlank() }
            ?: anime.optString("synopsis").takeIf { it.isNotBlank() }
            ?: anime.optString("overview").takeIf { it.isNotBlank() }
            ?: anime.optString("summary").takeIf { it.isNotBlank() }

        // Tags
        val tags = mutableListOf<String>()
        val genreArr = anime.optJSONArray("genres")
            ?: anime.optJSONArray("tags")
            ?: anime.optJSONArray("categories")
        genreArr?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.opt(i)
                when (g) {
                    is String -> if (g.isNotBlank()) tags.add(g)
                    is JSONObject -> {
                        val name = g.optString("name").takeIf { it.isNotBlank() }
                            ?: g.optString("title").takeIf { it.isNotBlank() }
                        if (name != null) tags.add(name)
                    }
                }
            }
        }

        // Episodes — esnek tarama (pageProps ve anime içinde ara)
        val episodes = mutableListOf<Episode>()
        val epsArray = findEpisodesArray(pageProps)
            ?: findEpisodesArray(anime)
            ?: findEpisodesArray(json)

        if (epsArray != null) {
            for (i in 0 until epsArray.length()) {
                val ep = epsArray.optJSONObject(i) ?: continue

                val epNum = ep.optInt("number",
                    ep.optInt("episode",
                        ep.optInt("episodeNumber",
                            ep.optInt("ep", i + 1))))

                val rawTitle = ep.optString("title").trim()
                    .ifBlank { ep.optString("name").trim() }
                val cleanTitle = cleanEpisodeTitle(rawTitle, epNum)

                val epUrl = extractEpisodeUrl(ep, slug, epNum) ?: continue

                val epPoster = extractPoster(ep) ?: poster

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = cleanTitle
                        this.episode = epNum
                        this.season = ep.optInt("season", 1)
                        this.posterUrl = epPoster
                    }
                )
            }
        }

        // Hiç bölüm bulunamadıysa, yine de LoadResponse döndür (poster + plot ile)
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private suspend fun parseAnimeDetailFromHtml(
        url: String,
        docInput: Document?
    ): LoadResponse {
        val doc = docInput ?: runCatching {
            app.get(url, headers = commonHeaders()).document
        }.getOrNull() ?: return newAnimeLoadResponse("Bilinmeyen Anime", url, TvType.Anime) {}

        val title = doc.selectFirst("h1, h2.anime-title, .anime-title h1, h1.title")
            ?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Bilinmeyen Anime"

        // Poster: birden fazla selector ve öznitelik dene
        val poster = fixUrlNull(
            doc.selectFirst("img.cover")?.attr("src")
                ?: doc.selectFirst("div.poster img")?.attr("src")
                ?: doc.selectFirst(".anime-poster img")?.attr("src")
                ?: doc.selectFirst("img[alt*='cover' i]")?.attr("src")
                ?: doc.selectFirst("img[alt*='poster' i]")?.attr("src")
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("img")?.attr("src")?.takeIf { !it.startsWith("data:") }
        )

        val plot = doc.selectFirst("div.desc, p.description, .synopsis, .summary")
            ?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val tags = doc.select("div.genres a, .tags a, span.genre, a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val slug = url.substringAfterLast("/").substringBefore("?")
        val episodeElements = doc.select(
            "ul.episodes li a, " +
            "div.episode-list a, " +
            "div.episodes a, " +
            "a[href*='/anime/$slug/'], " +
            "a[href*='/watch/']"
        )

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()

        episodeElements.forEach { el ->
            val href = fixUrlNull(el.attr("href")) ?: return@forEach
            if (!seen.add(href)) return@forEach

            val text = el.text().trim()
            val epNum = Regex("""(\d+)""").find(text)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: (episodes.size + 1)

            val cleanName = cleanEpisodeTitle(text, epNum)

            episodes.add(
                newEpisode(href) {
                    this.name = cleanName
                    this.episode = epNum
                    this.season = 1
                    this.posterUrl = poster
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun cleanEpisodeTitle(raw: String, epNum: Int): String? {
        val cleaned = raw
            .replace(
                Regex(
                    """^\s*\d+\s*[\.\-–:]?\s*Bölüm\s*[:\-–]?\s*""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(
                Regex(
                    """^\s*Bölüm\s*\d+\s*[:\-–]?\s*""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(
                Regex(
                    """^\s*Episode\s*\d+\s*[:\-–]?\s*""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()

        return cleaned.takeIf {
            it.isNotBlank() &&
            !it.equals("Bölüm", ignoreCase = true) &&
            !it.equals("Episode", ignoreCase = true) &&
            it != epNum.toString()
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
        ensureInit()

        val doc = runCatching {
            app.get(data, headers = commonHeaders()).document
        }.getOrNull() ?: return false

        val extractedUrls = mutableSetOf<String>()

        // 1. __NEXT_DATA__
        val nextDataText = doc.selectFirst(nextDataSelector)?.data()
        if (!nextDataText.isNullOrBlank()) {
            processNextDataSources(nextDataText, subtitleCallback, callback, extractedUrls)
        }

        // 2. Flight Data
        val flightData = extractFlightData(doc)
        if (!flightData.isNullOrBlank()) {
            processNextDataSources(flightData, subtitleCallback, callback, extractedUrls)
        }

        // 3. Iframe / data-* öznitelikleri
        processIframeSources(doc, subtitleCallback, callback, extractedUrls)

        return extractedUrls.isNotEmpty()
    }

    private suspend fun processNextDataSources(
        raw: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        extractedUrls: MutableSet<String>
    ) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return

        val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps")
            ?: json.optJSONObject("pageProps")
            ?: json

        val sources = pageProps.optJSONArray("sources")
            ?: pageProps.optJSONArray("videos")
            ?: pageProps.optJSONArray("players")
            ?: pageProps.optJSONObject("episode")?.optJSONArray("sources")
            ?: pageProps.optJSONObject("data")?.optJSONArray("sources")
            ?: return

        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            val url = src.optString("url").takeIf { it.isNotBlank() } ?: continue
            if (url.isAdUrl()) continue
            if (!extractedUrls.add(url)) continue

            val label = src.optString("label").takeIf { it.isNotBlank() }
                ?: src.optString("name").takeIf { it.isNotBlank() }
                ?: src.optString("quality").takeIf { it.isNotBlank() }
                ?: "Player"

            emitSource(url, label, subtitleCallback, callback)
        }
    }

    private suspend fun processIframeSources(
        doc: Document,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        extractedUrls: MutableSet<String>
    ) {
        val elements = doc.select(
            "iframe[src], iframe[data-src], " +
            "div[data-video], div[data-player], " +
            "div[data-src], source[src]"
        )

        for (el in elements) {
            val rawUrl = el.attr("src").takeIf { it.isNotBlank() }
                ?: el.attr("data-src").takeIf { it.isNotBlank() }
                ?: el.attr("data-video").takeIf { it.isNotBlank() }
                ?: el.attr("data-player").takeIf { it.isNotBlank() }
                ?: el.attr("data-url").takeIf { it.isNotBlank() }
                ?: continue

            val url = fixUrlNull(rawUrl) ?: continue
            if (url.isAdUrl()) continue
            if (!extractedUrls.add(url)) continue

            emitSource(url, "Embed", subtitleCallback, callback)
        }
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
                        this.headers = commonHeaders()
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
                        this.headers = commonHeaders()
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

    // -------------------------------------------------------------------------
    // HTML Yardımcıları
    // -------------------------------------------------------------------------

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href*='/anime/']")
            ?: selectFirst("a")
            ?: return null

        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        if (!href.contains("/anime/")) return null

        val url = fixUrlNull(href) ?: return null

        val title = selectFirst("h3, h2, div.title, span.name, .anime-title")
            ?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: return null

        val img = selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
        )

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
        }
    }
}
