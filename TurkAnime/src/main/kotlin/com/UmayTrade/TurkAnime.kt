package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Türk Anime TV (Ayna & Arşiv) Sağlayıcısı (v3)
 *
 * Kaynak: https://turkanimemirror.vercel.app / GitHub JSON Arşivi
 * Arşiv: 6.100+ Anime, tüm bölümler ve çeviri grupları (Fansublar)
 * Oynatıcılar: Sibnet, Voe, Dailymotion, Odnoklassniki (Ok.ru), Google Drive, Mp4Upload vb.
 */
class TurkAnimeProvider : MainAPI() {

    override var mainUrl = "https://turkanimemirror.vercel.app"
    override var name = "Türk Anime TV"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val ARCHIVE_RAW = "https://raw.githubusercontent.com/agnogad/TurkAnimeTV_Arsiv_json/main/animeler"

        // Popüler / Klasik Animeler Seçkisi
        private val POPULAR_SLUGS = listOf(
            "death-note", "shingeki-no-kyojin", "naruto", "naruto-shippuuden", "bleach",
            "one-piece", "jujutsu-kaisen", "fullmetal-alchemist-brotherhood", "hunter-x-hunter-2011",
            "steins-gate", "kimetsu-no-yaiba", "sword-art-online", "boku-no-hero-academia",
            "tokyo-ghoul", "code-geass-hangyaku-no-lelouch", "cowboy-bebop", "vinland-saga",
            "chainsaw-man", "cyberpunk-edgerunners", "haikyuu", "black-clover", "monster",
            "mob-psycho-100", "overlord", "neon-genesis-evangelion", "solo-leveling"
        )
    }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private var cachedSlugs: List<String>? = null

    private suspend fun getAnimeSlugs(): List<String> {
        cachedSlugs?.let { return it }
        return try {
            val text = app.get("$ARCHIVE_RAW/animeler.json", headers = commonHeaders, timeout = 12).text
            val arr = JSONArray(text)
            val list = (0 until arr.length()).map { arr.getString(it) }
            cachedSlugs = list
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun slugToTitle(slug: String): String {
        return slug.split("-").joinToString(" ") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    // -------------------------------------------------------------------------
    // Ana Sayfa
    // -------------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "popular" to "Popüler & Klasik Animeler",
        "all"     to "Tüm Arşiv (A-Z)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val allSlugs = getAnimeSlugs()

        val items = when (request.data) {
            "popular" -> {
                val availablePopular = if (allSlugs.isNotEmpty()) {
                    POPULAR_SLUGS.filter { allSlugs.contains(it) }
                } else {
                    POPULAR_SLUGS
                }
                availablePopular.map { slug ->
                    newAnimeSearchResponse(slugToTitle(slug), "$ARCHIVE_RAW/$slug", TvType.Anime)
                }
            }
            "all" -> {
                val pageSize = 30
                val startIndex = (page - 1) * pageSize
                if (startIndex < allSlugs.size) {
                    val pagedSlugs = allSlugs.drop(startIndex).take(pageSize)
                    pagedSlugs.map { slug ->
                        newAnimeSearchResponse(slugToTitle(slug), "$ARCHIVE_RAW/$slug", TvType.Anime)
                    }
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = (request.data == "all" && (page * 30) < allSlugs.size)
        )
    }

    // -------------------------------------------------------------------------
    // Arama
    // -------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isBlank()) return emptyList()

        val slugQuery = cleanQuery.replace(Regex("""\s+"""), "-")
        val allSlugs = getAnimeSlugs()

        val matchedSlugs = allSlugs.filter { slug ->
            slug.contains(slugQuery) || slug.replace("-", " ").contains(cleanQuery)
        }.take(30)

        return matchedSlugs.map { slug ->
            newAnimeSearchResponse(slugToTitle(slug), "$ARCHIVE_RAW/$slug", TvType.Anime)
        }
    }

    // -------------------------------------------------------------------------
    // Detay & Bölüm Listesi
    // -------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val animeBase = "$ARCHIVE_RAW/$slug"

        // 1. info.json Çek
        var title = slugToTitle(slug)
        var description: String? = null
        val tags = mutableListOf<String>()
        var score: Double? = null

        try {
            val infoText = app.get("$animeBase/info.json", headers = commonHeaders, timeout = 10).text
            val info = JSONObject(infoText)

            val altTitle = info.optString("Japonca").takeIf { it.isNotBlank() }
            if (!altTitle.isNullOrBlank() && !altTitle.equals("?????", ignoreCase = true)) {
                title = "$title ($altTitle)"
            }

            description = info.optString("Özet").takeIf { it.isNotBlank() }
                ?: info.optString("ozet").takeIf { it.isNotBlank() }

            val genresArr = info.optJSONArray("Anime Türü") ?: info.optJSONArray("genres")
            if (genresArr != null) {
                for (i in 0 until genresArr.length()) {
                    val g = genresArr.optString(i).trim()
                    if (g.isNotBlank()) tags.add(g)
                }
            }

            val scoreStr = info.optString("Puanı").takeIf { it.isNotBlank() }
                ?: info.optString("puani").takeIf { it.isNotBlank() }
            score = scoreStr?.toDoubleOrNull()
        } catch (_: Exception) { }

        // 2. bolumler.json Çek
        val episodes = mutableListOf<Episode>()
        try {
            val epsText = app.get("$animeBase/bolumler.json", headers = commonHeaders, timeout = 10).text
            val epsArr = JSONArray(epsText)

            for (i in 0 until epsArr.length()) {
                val epItem = epsArr.optJSONArray(i) ?: continue
                val epSlug = epItem.optString(0)
                val epName = epItem.optString(1).takeIf { it.isNotBlank() } ?: "Bölüm ${i + 1}"
                if (epSlug.isBlank()) continue

                val epNum = Regex("""(\d+)""").find(epSlug)?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1)
                val epDataUrl = "$animeBase/$epSlug.json"

                episodes.add(newEpisode(epDataUrl) {
                    this.name = epName
                    this.episode = epNum
                    this.season = 1
                })
            }
        } catch (_: Exception) { }

        // 3. AniList Karakterler, Seslendirmenler, HD Afiş ve Banner
        val searchCandidate = slugToTitle(slug)
        val (actors, banner, aniListPoster, aniListScore) = fetchAniListMetadata(searchCandidate)

        val finalScore = score ?: aniListScore

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = aniListPoster
            this.backgroundPosterUrl = banner
            this.plot = description
            this.tags = tags
            finalScore?.let { this.score = Score.from10(it) }
            if (actors.isNotEmpty()) {
                addActors(actors)
            }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // -------------------------------------------------------------------------
    // Video Linkleri & Oynatıcılar
    // -------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = "$ARCHIVE_RAW/$slug/$epSlug.json"
        val playersJsonText = try {
            app.get(data, headers = commonHeaders, timeout = 10).text
        } catch (_: Exception) {
            return false
        }

        val playersArr = try {
            JSONArray(playersJsonText)
        } catch (_: Exception) {
            return false
        }

        data class PlayerEntry(
            val player: String,
            val fansub: String,
            val url: String
        )

        val entries = mutableListOf<PlayerEntry>()

        for (i in 0 until playersArr.length()) {
            val obj = playersArr.optJSONObject(i) ?: continue
            val videoUrl = obj.optString("url").trim()
            if (videoUrl.isBlank() || !videoUrl.startsWith("http")) continue

            val player = obj.optString("player").takeIf { it.isNotBlank() } ?: "Sunucu"
            val fansub = obj.optString("fansub").takeIf { it.isNotBlank() } ?: "Varsayılan"
            entries.add(PlayerEntry(player, fansub, videoUrl))
        }

        // Öncelik Sıralaması: Sibnet, Voe, Dailymotion, Ok.ru, Gdrive, Mp4Upload, Diğerleri
        val sortedEntries = entries.sortedByDescending { (player, _, _) ->
            val p = player.lowercase()
            when {
                p.contains("sibnet") -> 100
                p.contains("voe") -> 95
                p.contains("dailymotion") -> 90
                p.contains("odnoklassniki") || p.contains("ok.ru") || p.contains("okru") -> 85
                p.contains("gdrive") || p.contains("drive.google") -> 80
                p.contains("mp4upload") -> 75
                p.contains("dood") -> 70
                p.contains("sendvid") -> 65
                p.contains("cloudvideo") -> 60
                else -> 50
            }
        }

        val extractedUrls = mutableSetOf<String>()

        for (entry in sortedEntries) {
            val rawUrl = entry.url
            if (!extractedUrls.add(rawUrl)) continue

            // Doğrudan M3U8 veya MP4 ise
            if (rawUrl.contains(".m3u8") || rawUrl.contains(".mp4")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name [${entry.player} - ${entry.fansub}]",
                        url = rawUrl,
                        type = if (rawUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                continue
            }

            // Standart Extractor Oynatıcıları (Sibnet, Odnoklassniki, Voe, Dailymotion, Gdrive, Mp4upload, vb.)
            try {
                loadExtractor(rawUrl, mainUrl, subtitleCallback, callback)
            } catch (_: Exception) { }
        }

        return extractedUrls.isNotEmpty()
    }

    // -------------------------------------------------------------------------
    // AniList Karakter & Banner & HD Afiş Zenginleştirme
    // -------------------------------------------------------------------------

    private suspend fun fetchAniListMetadata(searchTitle: String): Quadruple<List<Actor>, String?, String?, Double?> {
        val actors = mutableListOf<Actor>()
        var banner: String? = null
        var cover: String? = null
        var score: Double? = null
        try {
            val queryStr = """
                query (${'$'}search: String) {
                  Media (search: ${'$'}search, type: ANIME) {
                    bannerImage
                    coverImage { extraLarge large }
                    averageScore
                    characters (perPage: 6, sort: ROLE) {
                      edges {
                        node { name { full } image { medium } }
                      }
                    }
                  }
                }
            """.trimIndent()
            val payload = JSONObject().apply {
                put("query", queryStr)
                put("variables", JSONObject().apply { put("search", searchTitle) })
            }
            val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val resp = app.post(
                "https://graphql.anilist.co",
                headers = mapOf("User-Agent" to "Mozilla/5.0"),
                requestBody = requestBody,
                timeout = 4
            ).text
            val m = JSONObject(resp).optJSONObject("data")?.optJSONObject("Media")
            if (m != null) {
                banner = m.optString("bannerImage").takeIf { it.isNotBlank() }
                val coverObj = m.optJSONObject("coverImage")
                cover = coverObj?.optString("extraLarge")?.takeIf { it.isNotBlank() }
                    ?: coverObj?.optString("large")?.takeIf { it.isNotBlank() }

                val avg = m.optDouble("averageScore")
                if (!avg.isNaN() && avg > 0.0) {
                    score = avg / 10.0
                }
                val edges = m.optJSONObject("characters")?.optJSONArray("edges")
                if (edges != null) {
                    for (i in 0 until edges.length()) {
                        val edge = edges.optJSONObject(i) ?: continue
                        val node = edge.optJSONObject("node") ?: continue
                        val name = node.optJSONObject("name")?.optString("full")?.takeIf { it.isNotBlank() } ?: continue
                        val img = node.optJSONObject("image")?.optString("medium")?.takeIf { it.isNotBlank() }
                        actors.add(Actor(name, img))
                    }
                }
            }
        } catch (_: Exception) { }
        return Quadruple(actors, banner, cover, score)
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
