package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * OpenAnime / Tranimeizle Sağlayıcısı
 *
 * Site: https://openani.me / https://www.tranimeizle.io
 * Yapı: ASP.NET MVC (klasik HTML)
 *
 * Tespit edilen yapı:
 *   - Kart: div.flx-block[data-href="/anime/..."]
 *   - Link: a.news-image[href="/anime/..."]
 *   - Poster: a.news-image img[src="https://static.tranimeizle.top/..."]
 *   - Başlık: div.bar h4
 *   - Sayfalama: /listeler/populer/sayfa-{N}
 *   - Arama: /arama/{query}
 *   - Player: video.js (lib/video.js/video.js)
 */
class OpenAnime : MainAPI() {

    override var mainUrl = "https://openani.me"
    override var name = "OpenAnime"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    // -------------------------------------------------------------------------
    // Ana Sayfa
    // -------------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "/"                        to "Yeni Bölümler",
        "/listeler/populer/sayfa-1" to "Popüler Animeler",
        "/listeler/eklenen/sayfa-1" to "Yeni Animeler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Sayfa numarasını URL'ye enjekte et
        val pageUrl = buildPageUrl(request.data, page)

        val doc = app.get(pageUrl, headers = headers()).document
        val items = doc.select("div.flx-block").mapNotNull { it.toAnimeCard() }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    /**
     * Sayfa numarasını URL'ye göre enjekte eder.
     * Örnek: /listeler/populer/sayfa-1 → /listeler/populer/sayfa-2
     *        /                      → /?page=2
     */
    private fun buildPageUrl(base: String, page: Int): String {
        if (page <= 1) return "$mainUrl$base"

        // Zaten "sayfa-N" içeriyorsa → N'i değiştir
        if (base.contains("sayfa-")) {
            val newPath = base.replace(Regex("""sayfa-\d+"""), "sayfa-$page")
            return "$mainUrl$newPath"
        }

        // Ana sayfa → ?page=N
        return "$mainUrl$base?page=$page"
    }

    /** HTML'deki `div.flx-block` kartını SearchResponse'a çevirir. */
    private fun Element.toAnimeCard(): SearchResponse? {
        // Link: a.news-image veya data-href
        val linkEl = selectFirst("a.news-image")
            ?: selectFirst("a[href*='/anime/']")
        val href = linkEl?.attr("href")?.takeIf { it.isNotBlank() }
            ?: attr("data-href").takeIf { it.isNotBlank() }
            ?: return null

        val url = fixUrlNull(href) ?: return null

        // Poster: a.news-image img
        val img = selectFirst("a.news-image img")
            ?: selectFirst("img.img-responsive")
            ?: selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
        )

        // Başlık: div.bar h4
        val title = selectFirst("div.bar h4")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: selectFirst("h4")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: linkEl?.attr("title")?.takeIf { it.isNotBlank() }
            ?: return null

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // -------------------------------------------------------------------------
    // Arama
    // -------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        // Site URL formatı: /arama/{query}
        val searchUrl = "$mainUrl/arama/${query.urlEncode()}"
        val doc = app.get(searchUrl, headers = headers()).document

        // Önce kart selector'ünü dene, sonra genel fallback
        val cards = doc.select("div.flx-block")
        if (cards.isNotEmpty()) {
            return cards.mapNotNull { it.toAnimeCard() }
        }

        // Fallback: herhangi bir anime linki
        return doc.select("a[href*='/anime/']")
            .mapNotNull { el ->
                val href = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                if (!href.contains("/anime/")) return@mapNotNull null

                val title = el.selectFirst("h4, h3, .title")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: el.attr("title").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val img = el.selectFirst("img")
                val poster = fixUrlNull(
                    img?.attr("src")?.takeIf { !it.startsWith("data:") }
                        ?: img?.attr("data-src")
                )

                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.url }
    }

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, "UTF-8")

    // -------------------------------------------------------------------------
    // Detay Sayfası — Bölüm Listesi
    // -------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers()).document

        // Başlık — birden fazla olası selector
        val title = doc.selectFirst("div.anime-title h1, h1.anime-title, h1.title, h1")
            ?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Bilinmeyen Anime"

        // Poster — og:image çoğu sitede en güvenilir kaynak
        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("div.anime-poster img, div.poster img, img.cover, .detail-poster img")?.attr("src")
        )

        // Konu
        val plot = doc.selectFirst("div.anime-desc, div.desc, p.description, .synopsis, .anime-summary")
            ?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        // Türler
        val tags = doc.select("div.anime-genres a, div.genres a, .tags a, a[href*='/animeizle/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // Bölüm listesi
        val episodes = mutableListOf<Episode>()

        // Yaygın bölüm listesi selector'leri
        val episodeSelectors = listOf(
            "div.episodes-list a",
            "ul.episodes li a",
            "div.episode-list a",
            "div#episodes a",
            "table.episodes a",
            "a[href*='-bolum-izle']",
            "a[href*='-bolum']"
        )

        val seen = mutableSetOf<String>()
        for (sel in episodeSelectors) {
            val els = doc.select(sel)
            if (els.isEmpty()) continue

            els.forEach { el ->
                val href = fixUrlNull(el.attr("href")) ?: return@forEach
                if (!seen.add(href)) return@forEach
                if (!href.contains("-bolum")) return@forEach

                val text = el.text().trim()
                val epNum = Regex("""(\d+)""").find(text)
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: (episodes.size + 1)

                episodes.add(
                    newEpisode(href) {
                        this.name = text.takeIf { it.isNotBlank() && it != epNum.toString() }
                        this.episode = epNum
                        this.season = 1
                        this.posterUrl = poster
                    }
                )
            }
            if (episodes.isNotEmpty()) break
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
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
        val doc = app.get(data, headers = headers()).document
        var found = false

        // 1. Sayfada <video> veya <source> etiketi
        doc.select("video source[src], video[src]").forEach { el ->
            val src = fixUrlNull(
                el.attr("src").takeIf { it.isNotBlank() }
                    ?: el.attr("data-src")
            ) ?: return@forEach

            if (src.contains(".m3u8") || src.contains(".mp4")) {
                emitVideo(src, "Direct", callback)
                found = true
            }
        }

        // 2. Script içinde m3u8/mp4 araması
        val scripts = doc.select("script:not([src])")
        val urlRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")

        for (script in scripts) {
            val content = script.data()
            urlRegex.findAll(content).forEach { match ->
                val videoUrl = match.groupValues[1]
                emitVideo(videoUrl, "Script", callback)
                found = true
            }

            // file: "..." veya sources: [{file:"..."}] formatı
            val fileRegex = Regex("""["']file["']\s*:\s*["']([^"']+)["']""")
            fileRegex.findAll(content).forEach { match ->
                val videoUrl = fixUrlNull(match.groupValues[1]) ?: return@forEach
                if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4")) {
                    emitVideo(videoUrl, "Player", callback)
                    found = true
                }
            }
        }

        // 3. iframe / embed
        doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = fixUrlNull(
                iframe.attr("src").takeIf { it.isNotBlank() }
                    ?: iframe.attr("data-src")
            ) ?: return@forEach

            if (src.contains("a-ads") || src.contains("googlesyndication")) return@forEach

            loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
            found = true
        }

        return found
    }

    private fun emitVideo(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8
                   else ExtractorLinkType.VIDEO

        callback(
            newExtractorLink(
                source = name,
                name = "$name [$label]",
                url = url,
                type = type
            ) {
                this.referer = "$mainUrl/"
                this.headers = headers()
            }
        )
    }
}
