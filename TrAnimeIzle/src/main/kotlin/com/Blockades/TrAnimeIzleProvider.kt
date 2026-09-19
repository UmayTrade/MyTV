package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * TrAnimeIzle Sağlayıcısı
 *
 * Site: https://www.tranimeizle.io
 * Oynatıcılar: Vidmoly, Sibnet, OkRu, MailRu, Doodstream, Fembed vb.
 */
class TrAnimeIzle : MainAPI() {

    override var mainUrl = "https://www.tranimeizle.io"
    override var name = "TrAnimeİzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    private var isInitialized = false

    private suspend fun ensureInit() {
        if (isInitialized) return
        isInitialized = true
        try {
            val config = app.get(
                "https://raw.githubusercontent.com/ulgenzade/ulgencs3/master/domains.json"
            ).text
            org.json.JSONObject(config).optString("tranimeizle")
                ?.takeIf { it.isNotBlank() }?.let { mainUrl = it }

            app.get(mainUrl, headers = commonHeaders)
        } catch (_: Exception) { }
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")

    // -------------------------------------------------------------------------
    // Ana Sayfa
    // -------------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "/"    to "Son Eklenenler",
        "/listeler/eklenen/sayfa-1"   to "Yeni Animeler",
        "/listeler/populer/sayfa-1" to "Popüler Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureInit()
        val targetUrl = if (request.data.startsWith("http")) "${request.data}$page" else "$mainUrl${request.data}$page"
        val doc = app.get(targetUrl, headers = commonHeaders).document
        val items = doc.select("div.anime-card, li.anime-item, div.movie-item, div.item")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Arama
    // -------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        ensureInit()
        val doc = app.get(
            "$mainUrl/arama?q=${query.encodeUrl()}",
            headers = commonHeaders
        ).document
        return doc.select("div.anime-card, li.anime-item, div.movie-item").mapNotNull { it.toSearchResult() }
    }

    // -------------------------------------------------------------------------
    // Detay & Bölümler
    // -------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        ensureInit()
        val doc = app.get(url, headers = commonHeaders).document

        val title = doc.selectFirst("h1.anime-title, div.baslik h1, h1.entry-title, h1")
            ?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "Bilinmeyen Anime"

        val poster = fixUrlNull(
            doc.selectFirst("div.anime-poster img, img.cover, div.poster img")?.attr("data-src")
                ?: doc.selectFirst("div.anime-poster img, img.cover, div.poster img")?.attr("src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = doc.selectFirst("div.anime-desc, p.description, div.ozet")?.text()?.trim()
        val tags = doc.select("a[href*='tur'], a[href*='genre'], div.genres a").map { it.text().trim() }

        val rawEpisodes = doc.select("div.episode-list a, ul.bolumler li a, div.bolum-list a, a[href*='-bolum']").mapNotNull { el ->
            val epUrl = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val epText = el.text().trim()
            val epNum = Regex("""(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
            val cleanText = epText.replace(Regex("""^\s*\d+\.\s*Bölüm\s*[-–:]*\s*"""), "").trim()
            val finalName = cleanText.takeIf { it.isNotBlank() && !it.equals("Bölüm", ignoreCase = true) }
            newEpisode(epUrl) {
                name = finalName
                episode = epNum
                season = 1
                posterUrl = poster
            }
        }.distinctBy { it.data }

        val episodes = if (rawEpisodes.any { (it.episode ?: 0) > 0 }) {
            rawEpisodes.sortedBy { it.episode ?: 0 }
        } else {
            rawEpisodes.reversed()
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
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
        val doc = app.get(data, headers = commonHeaders).document
        val extractedUrls = mutableSetOf<String>()

        // 1. Alternatif sunucu / oynatıcı butonları (Vidmoly, Sibnet, OkRu vb.)
        doc.select("div.player-nav a, div.alternatifler button, ul.video-tabs a, button[data-frame], a[data-embed], div[data-video]").forEach { el ->
            val src = fixUrlNull(
                el.attr("data-frame").takeIf { it.isNotBlank() }
                    ?: el.attr("data-embed").takeIf { it.isNotBlank() }
                    ?: el.attr("data-video").takeIf { it.isNotBlank() }
                    ?: el.attr("href")
            ) ?: return@forEach

            if (src.startsWith("http") && !src.contains(mainUrl) && extractedUrls.add(src)) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
            }
        }

        // 2. iframe embed'ler
        doc.select("iframe[src], iframe[data-src]").forEach { el ->
            val src = fixUrlNull(
                el.attr("src").takeIf { it.isNotBlank() }
                    ?: el.attr("data-src")
            ) ?: return@forEach

            if (!src.contains("a-ads.com") && extractedUrls.add(src)) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
            }
        }

        // 3. Script içindeki doğrudan video linkleri
        val scriptContent = doc.select("script").joinToString("\n") { it.data() }
        Regex("""(?:file|url|source)\s*[=:]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            .findAll(scriptContent)
            .forEach { match ->
                val videoUrl = match.groupValues[1].trim()
                if (extractedUrls.add(videoUrl)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = if (videoUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                }
            }

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val title = selectFirst("div.title, h3, span.isim, div.name, h2")?.text()?.trim()
            ?: a.attr("title").takeIf { it.isNotBlank() } ?: return null
        val url = fixUrlNull(a.attr("href")) ?: return null
        val poster = fixUrlNull(
            selectFirst("img")?.attr("data-src")?.takeIf { !it.contains("base64") }
                ?: selectFirst("img")?.attr("src")
        )
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
        }
    }
}