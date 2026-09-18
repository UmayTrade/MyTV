package com.Blockades

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Anizium Sağlayıcısı
 *
 * Site: https://anizium.co
 * Özellik: 4K (2160p), Resmi REST API, Çoklu Altyazı Dilleri ve TR Dublaj desteği
 */
class Anizium : MainAPI() {

    override var mainUrl = "https://anizium.co"
    override var name = "Anizium"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiHost = "https://api.anizium.co"
    private val tokenKey = "hlxjl1c2w281ax473rt1ofgrvhyjvi"

    private var isInitialized = false

    private suspend fun ensureInit() {
        if (isInitialized) return
        isInitialized = true
        try {
            val config = app.get(
                "https://raw.githubusercontent.com/ulgenzade/ulgencs3/master/domains.json",
                timeout = 5
            ).text
            org.json.JSONObject(config).optString("anizium")
                ?.takeIf { it.isNotBlank() }?.let { mainUrl = it }
        } catch (_: Exception) { }
    }

    private fun getCfControl(): String {
        return try {
            val sdf = SimpleDateFormat("EEEE", Locale.ENGLISH)
            sdf.timeZone = TimeZone.getTimeZone("Europe/Istanbul")
            val weekday = sdf.format(Date()).lowercase()
            val key = "${tokenKey}_$weekday".toByteArray(Charsets.UTF_8)

            val rnd = (1..6).map { ('a'..'z').random() }.joinToString("")
            val payload = "{\"$rnd\":${System.currentTimeMillis()}}".toByteArray(Charsets.UTF_8)

            val res = ByteArray(payload.size)
            for (i in payload.indices) {
                res[i] = (payload[i].toInt() xor key[i % key.size].toInt()).toByte()
            }
            res.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun getApiHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "Cf-Control" to getCfControl(),
            "device" to "browser",
            "language" to "tr",
            "site" to "main",
            "Accept" to "application/json, text/plain, */*"
        )
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")

    // -------------------------------------------------------------------------
    // Ana Sayfa
    // -------------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "last-added"          to "Son Eklenen Bölümler",
        "popular"             to "Popüler Animeler",
        "featured"            to "Editörün Seçtikleri",
        "special"             to "Özel Seçki Animeler",
        "4k"                  to "4K Ultra HD Animeler",
        "genre:66407"         to "Türkçe Dublaj Animeler",
        "genre:94032"         to "Shounen",
        "genre:62263"         to "Aksiyon",
        "genre:5263"          to "Macera",
        "genre:43261"         to "Fantastik",
        "genre:82742"         to "Büyü ve Kılıç",
        "genre:90158"         to "Bilim Kurgu",
        "genre:59624"         to "Romantizm",
        "genre:47450"         to "Komedi",
        "genre:57282"         to "Dram",
        "genre:23813"         to "Isekai",
        "genre:11860"         to "Doğaüstü",
        "genre:78746"         to "Gizem & Dedektif",
        "genre:57593"         to "Gerilim & Psikolojik",
        "genre:87910"         to "Seinen",
        "genre:88689"         to "Dövüş Sanatları",
        "genre:84484"         to "Ödüllü Animeler",
        "genre:19214"         to "Süper Güçler",
        "genre:47202"         to "Yaşamdan Kesitler",
        "genre:73505"         to "Okul",
        "genre:15055"         to "Çoklu Altyazı"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureInit()
        val items = mutableListOf<SearchResponse>()
        var hasNext = false

        if (request.data == "last-added") {
            val res = runCatching {
                app.get("$apiHost/page/last-added-episodes?page=$page", headers = getApiHeaders())
                    .parsedSafe<AniziumLastAddedResp>()
            }.getOrNull()

            res?.page?.data?.forEach { item ->
                val id = item.id ?: return@forEach
                val title = item.name ?: "Anime"
                val poster = item.poster ?: item.banner
                val epNum = item.episode ?: 1
                items.add(newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
                    this.posterUrl = fixUrlNull(poster)
                    addDubStatus(DubStatus.Subbed, epNum)
                })
            }
            hasNext = (res?.page?.nextPage != null) || (items.size >= 10)
        } else if (request.data.startsWith("genre:")) {
            val genreId = request.data.removePrefix("genre:")
            val res = runCatching {
                app.get("$apiHost/page/catalog?id=$genreId&type=genre&page=$page", headers = getApiHeaders())
                    .parsedSafe<AniziumSearchResp>()
            }.getOrNull()

            res?.page?.data?.forEach { item ->
                val id = item.id ?: return@forEach
                val title = item.name ?: return@forEach
                val poster = item.poster ?: item.banner
                items.add(newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
                    this.posterUrl = fixUrlNull(poster)
                })
            }
            hasNext = (res?.page?.nextPage != null) || (items.size >= 10)
        } else {
            val res = runCatching {
                app.get("$apiHost/page/home", headers = getApiHeaders()).parsedSafe<AniziumHomeResp>()
            }.getOrNull()

            val allPool = mutableListOf<AniziumItem>()
            res?.settlementTop?.let { allPool.addAll(it) }
            res?.settlementMiddle?.let { allPool.addAll(it) }
            res?.settlementLower?.let { allPool.addAll(it) }
            res?.specialList?.let { allPool.addAll(it) }

            val list = when (request.data) {
                "popular"  -> res?.settlementTop ?: allPool
                "featured" -> res?.settlementMiddle ?: allPool
                "special"  -> res?.specialList ?: allPool
                "4k"       -> allPool.filter { it.quality?.contains("4k", ignoreCase = true) == true }
                else       -> allPool
            }.distinctBy { it.id }

            list.forEach { item ->
                val id = item.id ?: return@forEach
                val title = item.name ?: return@forEach
                val poster = item.poster ?: item.banner
                items.add(newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
                    this.posterUrl = fixUrlNull(poster)
                })
            }
            hasNext = false
        }

        // API boş dönerse DOM fallback
        if (items.isEmpty() && page == 1) {
            val doc = runCatching {
                app.get("$mainUrl/anime-listesi?sayfa=$page", headers = getApiHeaders()).document
            }.getOrNull()

            doc?.select("div.anime-card, article.content-item, div.item")?.mapNotNull { it.toSearchResult() }?.let {
                items.addAll(it)
            }
        }

        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    // -------------------------------------------------------------------------
    // Arama
    // -------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        ensureInit()
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        // 1. Resmi API Araması (?value=...&page=1)
        val apiResults = runCatching {
            val resp = app.get(
                "$apiHost/page/search?value=${trimmed.encodeUrl()}&page=1",
                headers = getApiHeaders()
            ).parsedSafe<AniziumSearchResp>()

            val items = resp?.page?.data ?: resp?.data
            items?.mapNotNull { item ->
                val id = item.id ?: return@mapNotNull null
                val title = item.name ?: return@mapNotNull null
                val poster = item.poster ?: item.banner
                newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
                    this.posterUrl = fixUrlNull(poster)
                }
            }
        }.getOrNull()

        if (!apiResults.isNullOrEmpty()) {
            return apiResults
        }

        // 2. Yedek: Web Arama Fallback
        val webResults = runCatching {
            val doc = app.get("$mainUrl/arama?q=${trimmed.encodeUrl()}", headers = getApiHeaders()).document
            doc.select("div.anime-card, article.content-item, div.item").mapNotNull { it.toSearchResult() }
        }.getOrNull()

        return webResults ?: emptyList()
    }

    // -------------------------------------------------------------------------
    // Detay & Bölümler
    // -------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        ensureInit()
        val animeId = Regex("""(?:/anime/|id=)(\d+)""").find(url)?.groupValues?.get(1)

        if (!animeId.isNullOrEmpty()) {
            val res = runCatching {
                app.get("$apiHost/anime/get?id=$animeId", headers = getApiHeaders()).parsedSafe<AniziumAnimeDetailResp>()
            }.getOrNull()

            val anime = res?.data
            if (anime != null) {
                val title = anime.name ?: "Anime"
                val poster = anime.poster ?: anime.banner
                val overview = anime.overview
                val genres = anime.genres?.mapNotNull { it.name } ?: emptyList()

                val subEpisodes = mutableListOf<Episode>()
                val dubEpisodes = mutableListOf<Episode>()
                val hasAnimeTrDub = anime.soundGroup?.any { it.value?.equals("trdub", ignoreCase = true) == true } == true

                anime.seasons?.forEach { season ->
                    val sNum = season.number ?: 1
                    season.episodes?.forEach { ep ->
                        val epNum = ep.number ?: 1
                        val epName = ep.name?.takeIf { it.isNotBlank() && !it.equals("Bölüm $epNum", ignoreCase = true) }
                        val baseEpUrl = "$mainUrl/watch/$animeId?season=$sNum&episode=$epNum&epId=${ep.id ?: ""}"
                        val hasEpDub = ep.soundGroup?.any { it.equals("trdub", ignoreCase = true) } == true || hasAnimeTrDub

                        subEpisodes.add(newEpisode("$baseEpUrl&audio=sub") {
                            this.name = epName
                            this.season = sNum
                            this.episode = epNum
                            this.description = ep.overview?.takeIf { it.isNotBlank() }
                            this.posterUrl = fixUrlNull(ep.bannerLink)
                        })

                        if (hasEpDub) {
                            dubEpisodes.add(newEpisode("$baseEpUrl&audio=dub") {
                                this.name = if (epName != null) "$epName (TR Dublaj)" else "Bölüm $epNum (TR Dublaj)"
                                this.season = sNum
                                this.episode = epNum
                                this.description = ep.overview?.takeIf { it.isNotBlank() }
                                this.posterUrl = fixUrlNull(ep.bannerLink)
                            })
                        }
                    }
                }

                return newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = fixUrlNull(poster)
                    this.plot = overview
                    this.tags = genres
                    addEpisodes(DubStatus.Subbed, subEpisodes)
                    if (dubEpisodes.isNotEmpty()) {
                        addEpisodes(DubStatus.Dubbed, dubEpisodes)
                    }
                }
            }
        }

        // DOM Fallback
        val doc = app.get(url, headers = getApiHeaders()).document
        val title = doc.selectFirst("h1.content-title, h2.anime-title, h1")?.text()?.trim() ?: "Anime"
        val poster = fixUrlNull(doc.selectFirst("div.content-poster img, img.anime-poster")?.attr("src"))
        val description = doc.selectFirst("div.content-desc, p.anime-desc")?.text()?.trim()

        val rawEpisodes = doc.select("div.episode-list a, ul.bolumler li a, a[href*='bolum']").mapNotNull { el ->
            val epUrl = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val epText = el.text().trim()
            val epNum = Regex("""(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(epUrl) {
                this.name = epText.replace(Regex("""^\d+\.\s*Bölüm\s*[-–:]*\s*"""), "").takeIf { it.isNotBlank() }
                this.episode = epNum
                this.season = 1
            }
        }.distinctBy { it.data }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, rawEpisodes)
        }
    }

    // -------------------------------------------------------------------------
    // Medya Oynatıcıları, Çoklu Altyazı & Dublaj
    // -------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureInit()
        val animeId = Regex("""(?:/watch/|/anime/|id=)(\d+)""").find(data)?.groupValues?.get(1)
        val season = Regex("""season=(\d+)""").find(data)?.groupValues?.get(1) ?: "1"
        val episode = Regex("""episode=(\d+)""").find(data)?.groupValues?.get(1) ?: "1"

        val audioPref = Regex("""audio=(\w+)""").find(data)?.groupValues?.get(1) ?: "sub"

        if (!animeId.isNullOrEmpty()) {
            val sourceUrl = "$apiHost/anime/source?id=$animeId&site=main&plan=free&season=$season&episode=$episode&server=1"
            val res = runCatching {
                app.get(sourceUrl, headers = getApiHeaders()).parsedSafe<AniziumSourceResp>()
            }.getOrNull()

            if (res != null && res.success == true) {
                // 1. Çoklu Altyazı Dosyalarını Ekle (Türkçe, İngilizce, Almanca vb.)
                res.subtitles?.forEach { sub ->
                    val file = sub.link?.takeIf { it.isNotBlank() } ?: return@forEach
                    val label = sub.name ?: sub.group ?: "Altyazı"
                    subtitleCallback(newSubtitleFile(label, file))
                }

                // 2. Grupları Sırala: DUB seçildiyse Türkçe Dublaj en başta gelsin!
                val sortedGroups = res.groups?.sortedByDescending { grp ->
                    val isTrDub = grp.group?.contains("trdub", ignoreCase = true) == true ||
                            grp.name?.contains("trdub", ignoreCase = true) == true ||
                            grp.name?.contains("türkçe", ignoreCase = true) == true
                    if (audioPref == "dub") (if (isTrDub) 2 else 1)
                    else (if (!isTrDub) 2 else 1)
                }

                // 3. Orijinal ve Türkçe Dublaj Video Akışlarını Ekle (4K, 1080p, 720p)
                sortedGroups?.forEach { grp ->
                    val isTrDub = grp.group?.contains("trdub", ignoreCase = true) == true ||
                            grp.name?.contains("trdub", ignoreCase = true) == true ||
                            grp.name?.contains("türkçe", ignoreCase = true) == true
                    val isEnDub = grp.group?.contains("endub", ignoreCase = true) == true ||
                            grp.name?.contains("ingilizce", ignoreCase = true) == true

                    val grpName = when {
                        isTrDub -> "Türkçe Dublaj"
                        isEnDub -> "İngilizce Dublaj"
                        else    -> "Japonca (Altyazılı)"
                    }

                    grp.items?.forEach { item ->
                        val link = item.link?.takeIf { it.isNotBlank() } ?: return@forEach
                        val q = item.quality ?: 1080
                        val qualValue = when (q) {
                            2160 -> Qualities.P2160.value
                            1080 -> Qualities.P1080.value
                            720  -> Qualities.P720.value
                            480  -> Qualities.P480.value
                            else -> Qualities.Unknown.value
                        }
                        val qLabel = if (q >= 2160) "4K (2160p)" else "${q}p"

                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name [$grpName - $qLabel]",
                                url = link,
                                type = if (link.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = qualValue
                                this.headers = mapOf(
                                    "Referer" to "$mainUrl/",
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                                )
                            }
                        )
                    }
                }
                return true
            }
        }

        // DOM Fallback
        val doc = app.get(data, headers = getApiHeaders()).document
        doc.select("source[src]").forEach { source ->
            val src = fixUrlNull(source.attr("src")) ?: return@forEach
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name [Direct]",
                    url = src,
                    type = if (src.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }

        doc.select("iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            loadExtractor(src, mainUrl, subtitleCallback, callback)
        }

        return true
    }

    // -------------------------------------------------------------------------
    // Veri Modelleri
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniziumLastAddedResp(val page: AniziumPageData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniziumPageData(
        val page: Int? = null,
        @JsonProperty("next_page") val nextPage: Int? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null,
        val data: List<AniziumItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniziumHomeResp(
        val success: Boolean? = null,
        @JsonProperty("settlement_top") val settlementTop: List<AniziumItem>? = null,
        @JsonProperty("settlement_middle") val settlementMiddle: List<AniziumItem>? = null,
        @JsonProperty("settlement_lower") val settlementLower: List<AniziumItem>? = null,
        @JsonProperty("special_list") val specialList: List<AniziumItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniziumSearchResp(
        val success: Boolean? = null,
        val page: AniziumPageData? = null,
        val data: List<AniziumItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniziumItem(
        @JsonProperty("ID") val id: String? = null,
        val name: String? = null,
        val poster: String? = null,
        val banner: String? = null,
        val quality: String? = null,
        val episode: Int? = null,
        val overview: String? = null,
        val genre: List<AniziumGenre>? = null,
        @JsonProperty("sound_group") val soundGroup: List<AniziumSoundGroupItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniziumSoundGroupItem(
        val name: String? = null,
        val value: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniziumAnimeDetailResp(val data: AniziumDetailData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniziumDetailData(
        @JsonProperty("ID") val id: String? = null,
        val name: String? = null,
        val poster: String? = null,
        val banner: String? = null,
        val overview: String? = null,
        val genres: List<AniziumGenre>? = null,
        val seasons: List<AniziumSeason>? = null,
        @JsonProperty("sound_group") val soundGroup: List<AniziumSoundGroupItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniziumGenre(val name: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniziumSeason(
        val number: Int? = null,
        val episodes: List<AniziumEpisodeItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniziumEpisodeItem(
        @JsonProperty("ID") val id: String? = null,
        val name: String? = null,
        val number: Int? = null,
        val overview: String? = null,
        @JsonProperty("banner_link") val bannerLink: String? = null,
        @JsonProperty("sound_group") val soundGroup: List<String>? = null,
        @JsonProperty("dubbing_group") val dubbingGroup: List<String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniziumSourceResp(
        val success: Boolean? = null,
        val subtitles: List<AniziumSubtitle>? = null,
        val groups: List<AniziumSourceGroup>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniziumSubtitle(
        val group: String? = null,
        val name: String? = null,
        val link: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniziumSourceGroup(
        val group: String? = null,
        val name: String? = null,
        val items: List<AniziumSourceItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniziumSourceItem(
        val quality: Int? = null,
        val link: String? = null,
        val type: String? = null
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val title = selectFirst("h3, h4, div.title, span.title")?.text()?.trim() ?: a.text().trim()
        val img = selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src")
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(img)
        }
    }
}