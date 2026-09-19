package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * OpenAnime Sağlayıcısı
 *
 * Site: https://openani.me / https://openanime.org
 * Yapı: Next.js (Pages Router) tabanlı SPA
 *       - __NEXT_DATA__ script etiketi ile SSR verisi
 *       - /_next/data/{buildId}/... JSON endpoint'leri
 *       - /api/* API Route'ları
 *
 * Oynatıcılar:
 *   - Doğrudan HLS (.m3u8) — clear, DRM yok
 *   - Progressive MP4 (.mp4)
 *   - Harici embed: Vidmoly, Sibnet, Doodstream vb.
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
    // Sabitler ve Yardımcı Alanlar
    // -------------------------------------------------------------------------

    companion object {
        private const val DOMAIN_CONFIG_URL =
            "https://raw.githubusercontent.com/ulgenzade/ulgencs3/master/domains.json"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

        private const val NEXT_DATA_SELECTOR = "script#__NEXT_DATA__"

        // Reklam domainleri — filtrelenir
        private val AD_DOMAINS = setOf(
            "a-ads.com",
            "googlesyndication.com",
            "doubleclick.net",
            "adservice.google.com"
        )
    }

    private val commonHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )

    private val jsonHeaders: Map<String, String>
        get() = commonHeaders + mapOf(
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest"
        )

    // Init kilidi — race condition önler
    private val initMutex = Mutex()
    private var nextBuildId: String? = null
    @Volatile private var isInitialized = false

    // -------------------------------------------------------------------------
    // Başlatma
    // -------------------------------------------------------------------------

    /**
     * Domain config'ini çeker ve Next.js buildId'sini alır.
     * Thread-safe; başarısız olursa tekrar denenebilir.
     */
    private suspend fun ensureInit() {
        if (isInitialized) return
        initMutex.withLock {
            if (isInitialized) return
            try {
                // 1. Dinamik domain güncellemesi
                runCatching {
                    val config = app.get(DOMAIN_CONFIG_URL).text
                    JSONObject(config)
                        .optString("openanime")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { mainUrl = it.trimEnd('/') }
                }

                // 2. Next.js buildId'sini al
                runCatching {
                    val doc = app.get(mainUrl, headers = commonHeaders).document
                    nextBuildId = extractBuildId(doc)
                }

                isInitialized = true
            } catch (_: Exception) {
                // init başarısız — bir sonraki çağrıda tekrar denenir
            }
        }
    }

    /** __NEXT_DATA__ içinden buildId çıkarır. */
    private fun extractBuildId(doc: Document): String? {
        val raw = doc.selectFirst(NEXT_DATA_SELECTOR)?.data() ?: return null
        return runCatching {
            JSONObject(raw).optString("buildId").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Next.js data endpoint URL'i üretir. */
    private fun nextApiUrl(path: String): String {
        val cleanPath = path.trimStart('/')
        val bid = nextBuildId
        return if (bid.isNullOrBlank()) {
            "$mainUrl/api/$cleanPath"
        } else {
            "$mainUrl/_next/data/$bid/$cleanPath.json"
        }
    }

    /** URL encode yardımcı fonksiyonu. */
    private fun String.urlEncode(): String =
        URLEncoder.encode(this, "UTF-8")

    /** Reklam URL'i mi? */
    private fun String.isAdUrl(): Boolean =
        AD_DOMAINS.any { contains(it, ignoreCase = true) }

    // -------------------------------------------------------------------------
    // Ana Sayfa
    // -------------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "/explore"  to "Son Bölümler",
        "/popular"  to "Popüler Animeler",
        "/all"      to "Tüm Animeler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        ensureInit()
        val items = mutableListOf<SearchResponse>()

        // Önce Next.js data endpoint'i dene
        val nextUrl = "${nextApiUrl(request.data)}?page=$page"
        val jsonItems = runCatching {
            val resp = app.get(nextUrl, headers = jsonHeaders).text
            parseAnimeListFromJson(resp)
        }.getOrNull()

        if (!jsonItems.isNullOrEmpty()) {
            items.addAll(jsonItems)
        } else {
            // Fallback: HTML scraping
            val htmlUrl = "$mainUrl${request.data}?page=$page"
            val doc = runCatching {
                app.get(htmlUrl, headers = commonHeaders).document
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

    /** Next.js data endpoint'inden anime listesi çıkarır. */
    private fun parseAnimeListFromJson(raw: String): List<SearchResponse> {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val props = json.optJSONObject("pageProps") ?: return emptyList()

        val arr = props.optJSONArray("animes")
            ?: props.optJSONArray("items")
            ?: props.optJSONArray("data")
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

            val poster = fixUrlNull(
                item.optString("coverImage").takeIf { it.isNotBlank() }
                    ?: item.optString("image")
                    ?: item.optString("poster")
            )

            result.add(
                newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return result
    }

    /** HTML'den anime listesi çıkarır (fallback). */
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

        // 1. API endpoint
        val apiUrl = "$mainUrl/api/search?q=${query.urlEncode()}"
        val apiItems = runCatching {
            val resp = app.get(apiUrl, headers = jsonHeaders).text
            parseSearchFromJson(resp)
        }.getOrNull()

        if (!apiItems.isNullOrEmpty()) return apiItems

        // 2. HTML fallback
        val htmlUrl = "$mainUrl/search?q=${query.urlEncode()}"
        val doc = runCatching {
            app.get(htmlUrl, headers = commonHeaders).document
        }.getOrNull() ?: return emptyList()

        return parseAnimeListFromHtml(doc)
    }

    private fun parseSearchFromJson(raw: String): List<SearchResponse> {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val arr = json.optJSONArray("results")
            ?: json.optJSONArray("data")
            ?: json.optJSONArray("animes")
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

            val poster = fixUrlNull(
                item.optString("coverImage").takeIf { it.isNotBlank() }
                    ?: item.optString("image")
            )

            result.add(
                newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Detay & Bölümler
    // -------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        ensureInit()

        val slug = url.substringAfterLast("/").substringBefore("?")

        // 1. Next.js data endpoint
        val jsonResp = runCatching {
            val apiUrl = nextApiUrl("/anime/$slug")
            app.get(apiUrl, headers = jsonHeaders).text
        }.getOrNull()

        if (!jsonResp.isNullOrBlank()) {
            val parsed = parseAnimeDetailFromJson(jsonResp, url, slug)
            if (parsed != null) return parsed
        }

        // 2. HTML fallback
        return parseAnimeDetailFromHtml(url)
    }

    private fun parseAnimeDetailFromJson(
        raw: String,
        url: String,
        slug: String
    ): LoadResponse? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val props = json.optJSONObject("pageProps") ?: return null

        // pageProps.anime veya pageProps.data.anime veya doğrudan pageProps
        val anime = props.optJSONObject("anime")
            ?: props.optJSONObject("data")?.optJSONObject("anime")
            ?: props

        val title = anime.optString("title").takeIf { it.isNotBlank() }
            ?: anime.optString("name").takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            anime.optString("coverImage").takeIf { it.isNotBlank() }
                ?: anime.optString("image")
                ?: anime.optString("poster")
        )

        val description = anime.optString("description")
            .takeIf { it.isNotBlank() }
            ?: anime.optString("synopsis").takeIf { it.isNotBlank() }

        val tags = mutableListOf<String>()
        anime.optJSONArray("genres")?.let { genres ->
            for (i in 0 until genres.length()) {
                val g = genres.optString(i).takeIf { it.isNotBlank() }
                if (g != null) tags.add(g)
            }
        }

        val episodes = mutableListOf<Episode>()
        val epsArray = anime.optJSONArray("episodes")
        if (epsArray != null) {
            for (i in 0 until epsArray.length()) {
                val ep = epsArray.optJSONObject(i) ?: continue
                val epNum = ep.optInt("number", i + 1)

                val rawTitle = ep.optString("title").trim()
                val cleanTitle = cleanEpisodeTitle(rawTitle, epNum)

                val epSlug = ep.optString("slug").takeIf { it.isNotBlank() }
                    ?: epNum.toString()

                val epUrl = if (epSlug.contains("/")) {
                    "$mainUrl/$epSlug"
                } else {
                    "$mainUrl/anime/$slug/$epSlug"
                }

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = cleanTitle
                        this.episode = epNum
                        this.season = ep.optInt("season", 1)
                        this.posterUrl = fixUrlNull(
                            ep.optString("thumbnail").takeIf { it.isNotBlank() }
                                ?: ep.optString("image")
                        )
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun parseAnimeDetailFromHtml(url: String): LoadResponse {
        val doc = runCatching {
            app.get(url, headers = commonHeaders).document
        }.getOrNull() ?: return newAnimeLoadResponse("Bilinmeyen Anime", url, TvType.Anime) {}

        val title = doc.selectFirst("h1, h2.anime-title, .anime-title h1")
            ?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Bilinmeyen Anime"

        val poster = fixUrlNull(
            doc.selectFirst("img.cover, div.poster img, .anime-poster img")
                ?.attr("src")
        )

        val plot = doc.selectFirst("div.desc, p.description, .synopsis")
            ?.text()?.trim()

        val tags = doc.select("div.genres a, .tags a, span.genre")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val episodeElements = doc.select(
            "ul.episodes li a, " +
            "div.episode-list a, " +
            "a[href*='/anime/$url']"
        )

        val episodes = mutableListOf<Episode>()
        val slug = url.substringAfterLast("/")
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

    /** Bölüm başlığından "1. Bölüm", "Bölüm 1" gibi önekleri temizler. */
    private fun cleanEpisodeTitle(raw: String, epNum: Int): String? {
        val cleaned = raw
            .replace(Regex("""^\s*\d+\s*[\.\-–:]?\s*Bölüm\s*[:\-–]?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\s*Bölüm\s*\d+\s*[:\-–]?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\s*Episode\s*\d+\s*[:\-–]?\s*""", RegexOption.IGNORE_CASE), "")
            .trim()

        return cleaned.takeIf {
            it.isNotBlank() &&
            !it.equals("Bölüm", ignoreCase = true) &&
            !it.equals("Episode", ignoreCase = true) &&
            it != epNum.toString()
        }
    }

    // -------------------------------------------------------------------------
    // Video Bağlantıları
    // -------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureInit()

        val doc = runCatching {
            app.get(data, headers = commonHeaders).document
        }.getOrNull() ?: return false

        val extractedUrls = mutableSetOf<String>()

        // 1. __NEXT_DATA__ içindeki video kaynakları
        val nextDataText = doc.selectFirst(NEXT_DATA_SELECTOR)?.data()
        if (!nextDataText.isNullOrBlank()) {
            processNextDataSources(nextDataText, subtitleCallback, callback, extractedUrls)
        }

        // 2. Iframe / data-* öznitelikleri
        processIframeSources(doc, subtitleCallback, callback, extractedUrls)

        return extractedUrls.isNotEmpty()
    }

    /** __NEXT_DATA__ içindeki sources/videos/players dizilerini işler. */
    private suspend fun processNextDataSources(
        raw: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        extractedUrls: MutableSet<String>
    ) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return

        // Next.js Pages Router: props.pageProps
        val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps")
            ?: json.optJSONObject("pageProps")
            ?: return

        val sources = pageProps.optJSONArray("sources")
            ?: pageProps.optJSONArray("videos")
            ?: pageProps.optJSONArray("players")
            ?: pageProps.optJSONObject("episode")?.optJSONArray("sources")
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

    /** Sayfadaki iframe ve data-video özniteliklerini işler. */
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

    /** Bir kaynağı tipine göre callback'e gönderir. */
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
                        this.headers = commonHeaders
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
                        this.headers = commonHeaders
                    }
                )
            }

            else -> {
                // Harici embed — extractor'a devret
                loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
            }
        }
    }

    /** Etiketten kalite değeri çıkarır. */
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
        // Kart içindeki link
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
        )

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
        }
    }
}
