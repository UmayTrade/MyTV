package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

/**
 * OpenAnime Sağlayıcısı
 *
 * Site: https://openani.me / https://openanime.org
 * Yapı: Next.js tabanlı SPA
 * Oynatıcılar: Doğrudan HLS (m3u8), harici embed oynatıcılar (Vidmoly, Sibnet, Doodstream vb.)
 */
class OpenAnime : MainAPI() {

    override var mainUrl = "https://openani.me"
    override var name = "OpenAnime"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private var nextBuildId: String? = null
    private var isInitialized = false

    private suspend fun ensureInit() {
        if (isInitialized) return
        isInitialized = true
        try {
            val config = app.get(
                "https://raw.githubusercontent.com/ulgenzade/ulgencs3/master/domains.json"
            ).text
            org.json.JSONObject(config).optString("openanime")
                ?.takeIf { it.isNotBlank() }?.let { mainUrl = it }

            val doc = app.get(mainUrl, headers = commonHeaders).document
            val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
            if (nextData != null) {
                nextBuildId = JSONObject(nextData).optString("buildId").takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { }
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private fun nextApiUrl(path: String): String {
        val bid = nextBuildId ?: return "$mainUrl/api$path"
        return "$mainUrl/_next/data/$bid$path.json"
    }

    // -------------------------------------------------------------------------
    // Ana Sayfa
    // -------------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "/explore"  to "Son Bölümler",
        "/popular" to "Popüler Animeler",
        "/all"     to "Tüm Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureInit()
        val pageUrl = if (request.data.startsWith("http")) request.data else "$mainUrl/${request.data}"
        val items = mutableListOf<SearchResponse>()

        try {
            val url = "${nextApiUrl(request.data)}?page=$page"
            val resp = app.get(url, headers = commonHeaders).text
            val json = JSONObject(resp)
            val pageProps = json.optJSONObject("pageProps")
            val animeArray = pageProps?.optJSONArray("animes")
                ?: pageProps?.optJSONArray("items")

            if (animeArray != null) {
                for (i in 0 until animeArray.length()) {
                    val item = animeArray.getJSONObject(i)
                    val title = item.optString("title").takeIf { it.isNotBlank() }
                        ?: item.optString("name") ?: continue
                    val slug = item.optString("slug").takeIf { it.isNotBlank() }
                        ?: item.optInt("id").toString()
                    val poster = fixUrlNull(item.optString("coverImage").takeIf { it.isNotBlank() }
                        ?: item.optString("image"))
                    items.add(newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                        this.posterUrl = poster
                    })
                }
            }
        } catch (_: Exception) {
            val doc = app.get("$mainUrl${request.data}?page=$page", headers = commonHeaders).document
            items.addAll(doc.select("div.anime-card, article, div.card").mapNotNull { it.toSearchResult() })
        }

        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Arama
    // -------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        ensureInit()
        return try {
            val resp = app.get(
                "$mainUrl/api/search?q=${query.encodeUrl()}",
                headers = commonHeaders
            ).text
            val json = JSONObject(resp)
            val results = json.optJSONArray("results") ?: json.optJSONArray("data")
            val items = mutableListOf<SearchResponse>()
            if (results != null) {
                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val title = item.optString("title").takeIf { it.isNotBlank() }
                        ?: item.optString("name") ?: continue
                    val slug = item.optString("slug").takeIf { it.isNotBlank() }
                        ?: item.optInt("id").toString()
                    val poster = fixUrlNull(item.optString("image").takeIf { it.isNotBlank() })
                    items.add(newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                        this.posterUrl = poster
                    })
                }
            }
            items
        } catch (_: Exception) {
            val doc = app.get("$mainUrl/search?q=${query.encodeUrl()}", headers = commonHeaders).document
            doc.select("div.anime-card, article, div.card").mapNotNull { it.toSearchResult() }
        }
    }

    // -------------------------------------------------------------------------
    // Detay & Bölümler
    // -------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        ensureInit()
        val slug = url.substringAfterLast("/")
        val title: String
        val poster: String?
        val description: String?
        val tags: List<String>
        val episodes = mutableListOf<Episode>()

        try {
            val apiUrl = nextApiUrl("/anime/$slug")
            val resp = app.get(apiUrl, headers = commonHeaders).text
            val json = JSONObject(resp)
            val props = json.optJSONObject("pageProps")
            val anime = props?.optJSONObject("anime") ?: props

            title = anime?.optString("title")?.takeIf { it.isNotBlank() }
                ?: anime?.optString("name") ?: "Bilinmeyen Anime"
            poster = fixUrlNull(
                anime?.optString("coverImage").takeIf { !it.isNullOrBlank() }
                    ?: anime?.optString("image")
            )
            description = anime?.optString("description")?.takeIf { it.isNotBlank() }
            tags = buildList {
                val genres = anime?.optJSONArray("genres")
                if (genres != null) {
                    for (i in 0 until genres.length()) add(genres.getString(i))
                }
            }

            val epsArray = anime?.optJSONArray("episodes")
            if (epsArray != null) {
                for (i in 0 until epsArray.length()) {
                    val ep = epsArray.getJSONObject(i)
                    val epNum = ep.optInt("number", i + 1)
                    val rawTitle = ep.optString("title").trim()
                    val cleanTitle = rawTitle.replace(Regex("""^\s*\d+\.\s*Bölüm\s*[-–:]*\s*"""), "").trim()
                    val finalName = cleanTitle.takeIf { it.isNotBlank() && !it.equals("Bölüm", ignoreCase = true) && it != "$epNum" }
                    val epSlug = ep.optString("slug").takeIf { it.isNotBlank() } ?: epNum.toString()
                    episodes.add(newEpisode("$mainUrl/anime/$slug/$epSlug") {
                        name = finalName
                        episode = epNum
                        season = 1
                        posterUrl = fixUrlNull(ep.optString("thumbnail"))
                    })
                }
            }
        } catch (_: Exception) {
            val doc = app.get(url, headers = commonHeaders).document
            val t = doc.selectFirst("h1, h2.anime-title")?.text()?.trim() ?: "Bilinmeyen Anime"
            val fallbackPoster = fixUrlNull(doc.selectFirst("img.cover, div.poster img")?.attr("src"))
            val fallbackEpisodes = doc.select("ul.episodes li a, div.episode-list a, a[href*='/anime/$slug/']").mapNotNull { el ->
                val epHref = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                val epText = el.text().trim()
                val epNum = Regex("""(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                val cleanText = epText.replace(Regex("""^\s*\d+\.\s*Bölüm\s*[-–:]*\s*"""), "").trim()
                val fallbackName = cleanText.takeIf { it.isNotBlank() && !it.equals("Bölüm", ignoreCase = true) }
                newEpisode(epHref) {
                    name = fallbackName
                    episode = epNum
                    season = 1
                    posterUrl = fallbackPoster
                }
            }.distinctBy { it.data }
            return newAnimeLoadResponse(t, url, TvType.Anime) {
                this.posterUrl = fallbackPoster
                this.plot = doc.selectFirst("div.desc, p.description")?.text()?.trim()
                addEpisodes(DubStatus.Subbed, fallbackEpisodes)
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
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
        val doc = app.get(data, headers = commonHeaders).document
        val extractedUrls = mutableSetOf<String>()

        // 1. __NEXT_DATA__ içindeki video kaynakları
        val nextDataText = doc.selectFirst("script#__NEXT_DATA__")?.data()
        if (nextDataText != null) {
            try {
                val json = JSONObject(nextDataText)
                val props = json.optJSONObject("props")?.optJSONObject("pageProps")
                val videoSources = props?.optJSONArray("sources")
                    ?: props?.optJSONArray("videos")
                    ?: props?.optJSONArray("players")

                if (videoSources != null) {
                    for (i in 0 until videoSources.length()) {
                        val src = videoSources.getJSONObject(i)
                        val srcUrl = src.optString("url").takeIf { it.isNotBlank() } ?: continue
                        if (!extractedUrls.add(srcUrl)) continue

                        val label = src.optString("label", src.optString("name", "Player"))
                        if (srcUrl.contains(".m3u8") || srcUrl.contains(".mp4")) {
                            val quality = when {
                                label.contains("1080") -> Qualities.P1080.value
                                label.contains("720")  -> Qualities.P720.value
                                label.contains("480")  -> Qualities.P480.value
                                else                   -> Qualities.Unknown.value
                            }
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "$name [$label]",
                                    url = srcUrl,
                                    type = if (srcUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) { this.quality = quality }
                            )
                        } else {
                            loadExtractor(srcUrl, mainUrl, subtitleCallback, callback)
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        // 2. iframe embed'leri (Vidmoly, Sibnet vb.)
        doc.select("iframe[src], iframe[data-src], div[data-video], div[data-player]").forEach { iframe ->
            val src = fixUrlNull(
                iframe.attr("src").takeIf { it.isNotBlank() }
                    ?: iframe.attr("data-src").takeIf { it.isNotBlank() }
                    ?: iframe.attr("data-video").takeIf { it.isNotBlank() }
                    ?: iframe.attr("data-player")
            ) ?: return@forEach

            if (!src.contains("a-ads.com") && extractedUrls.add(src)) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val title = selectFirst("h3, div.title, span.name, h2")?.text()?.trim()
            ?: a.attr("title").takeIf { it.isNotBlank() } ?: return null
        val url = fixUrlNull(a.attr("href")) ?: return null
        val poster = fixUrlNull(
            selectFirst("img")?.attr("src")?.takeIf { !it.contains("base64") }
                ?: selectFirst("img")?.attr("data-src")
        )
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
        }
    }
}