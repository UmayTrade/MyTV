package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AnizmProvider : MainAPI() {

    override var mainUrl = "https://anizm.com.tr"
    override var name = "Anizm"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    private var isInitialized = false

    private suspend fun ensureInit() {
        if (isInitialized) return
        isInitialized = true
        try {
            val config = app.get(
                "https://raw.githubusercontent.com/ulgenzade/ulgencs3/master/domains.json",
                timeout = 5
            ).text
            val d = JSONObject(config).optString("anizm")
            if (!d.isNullOrBlank() && !d.contains("anizm.net")) {
                mainUrl = d.trimEnd('/')
            }
        } catch (_: Exception) { }
    }

    private suspend fun safeGetDoc(url: String, referer: String? = null): org.jsoup.nodes.Document? {
        val h = if (referer != null) commonHeaders + ("Referer" to referer) else commonHeaders
        return try {
            app.get(url, headers = h, timeout = 10).document
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun safeGetText(url: String, referer: String? = null, isAjax: Boolean = false): String {
        val baseH = if (referer != null) commonHeaders + ("Referer" to referer) else commonHeaders
        val h = if (isAjax) baseH + ("X-Requested-With" to "XMLHttpRequest") else baseH
        return try {
            app.get(url, headers = h, timeout = 10).text
        } catch (_: Exception) {
            ""
        }
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")

    override val mainPage = mainPageOf(
        "home"        to "Son Eklenen Bölümler",
        "kategori|2"  to "Aksiyon",
        "kategori|1"  to "Macera",
        "kategori|13" to "Fantastik",
        "kategori|34" to "Shounen",
        "kategori|3"  to "Komedi",
        "kategori|4"  to "Dram",
        "kategori|8"  to "Bilim Kurgu",
        "kategori|28" to "Romantizm",
        "kategori|11" to "Doğaüstü Güçler",
        "tema|3"      to "Isekai",
        "tema|24"     to "Okul"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureInit()
        val parts = request.data.split("|")
        val action = parts.getOrNull(0) ?: "home"
        val param = parts.getOrNull(1) ?: ""

        val pageUrl = when (action) {
            "home" -> if (page == 1) "$mainUrl/" else "$mainUrl/kategoriler/2?sayfa=$page"
            "kategori" -> "$mainUrl/kategoriler/$param?sayfa=$page"
            "tema" -> "$mainUrl/temalar/$param?sayfa=$page"
            else -> "$mainUrl/kategoriler/2?sayfa=$page"
        }

        val doc = safeGetDoc(pageUrl) ?: return newHomePageResponse(request.name, emptyList())
        val animeList = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        if (action == "home") {
            // 1. Ana Sayfa: Swiper slider ve yeni eklenen bölümler
            doc.select("a.slideAnimeLink, .swiper-slide a.slideAnimeLink, .animeList a, .contentList a").forEach { el ->
                val href = el.attr("href").trim()
                val fullUrl = fixUrlNull(href) ?: return@forEach
                if (fullUrl.contains("/giris-yap") || fullUrl.contains("/kayit-ol") || fullUrl.contains("/kategoriler") || fullUrl.contains("/temalar") || fullUrl.contains("/profil")) return@forEach

                val rawTitle = el.selectFirst(".slideAnimeTitle, .title, .animeName, h6, h5")?.text()?.trim()
                    ?: el.selectFirst("img")?.attr("alt")?.replace("- Anizm.TV", "")?.trim()
                    ?: return@forEach

                if (isInvalidTitle(rawTitle)) return@forEach

                val cleanTitle = rawTitle.replace(Regex("""/\s*\d+\.\s*Bölüm.*$""", RegexOption.IGNORE_CASE), "").trim()
                val finalUrl = cleanAnimeUrl(fullUrl) ?: fullUrl

                val posterImg = el.selectFirst("img[src*='/storage/pcovers/'], img")
                val posterRaw = posterImg?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: posterImg?.attr("data-src")?.takeIf { it.isNotBlank() }
                val posterUrl = fixUrlNull(posterRaw)

                val epNumMatch = Regex("""(?:-(\d+)-bolum|(\d+)\.\s*Bölüm)""", RegexOption.IGNORE_CASE).find("$fullUrl $rawTitle")
                val epNum = epNumMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()

                if (seen.add(finalUrl)) {
                    animeList.add(newAnimeSearchResponse(cleanTitle, finalUrl, TvType.Anime) {
                        this.posterUrl = posterUrl
                        if (epNum != null) {
                            addDubStatus(DubStatus.Subbed, epNum)
                        }
                    })
                }
            }
        } else {
            // 2. Kategori & Temalar: .anime-card, .cat-card
            doc.select(".anime-card, .cat-card, .ui.card").forEach { card ->
                val linkEl = card.selectFirst("a.anime-title, a.image, a[href]") ?: return@forEach
                val href = linkEl.attr("href").trim()
                val fullUrl = fixUrlNull(href) ?: return@forEach
                val finalUrl = cleanAnimeUrl(fullUrl) ?: return@forEach

                val title = card.selectFirst(".anime-title, .header, h5, h4")?.text()?.trim()
                    ?: card.selectFirst("img")?.attr("alt")?.replace("- Anizm.TV", "")?.trim()
                    ?: return@forEach

                if (isInvalidTitle(title)) return@forEach

                val posterImg = card.selectFirst("img.anime-poster, img[src*='/storage/pcovers/'], img")
                val posterRaw = posterImg?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: posterImg?.attr("data-src")?.takeIf { it.isNotBlank() }
                val posterUrl = fixUrlNull(posterRaw)

                if (seen.add(finalUrl)) {
                    animeList.add(newAnimeSearchResponse(title, finalUrl, TvType.Anime) {
                        this.posterUrl = posterUrl
                    })
                }
            }
        }

        return newHomePageResponse(request.name, animeList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureInit()
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        // 1. Resmi AJAX Arama API'si (/searchAnime)
        runCatching {
            val searchUrl = "$mainUrl/searchAnime?query=${trimmed.encodeUrl()}&page=1&limit=20"
            val jsonStr = safeGetText(searchUrl, referer = "$mainUrl/", isAjax = true)
            if (jsonStr.isNotBlank() && (jsonStr.startsWith("{") || jsonStr.startsWith("["))) {
                val jsonObj = JSONObject(jsonStr)
                val dataArr = jsonObj.optJSONArray("data")
                if (dataArr != null) {
                    for (i in 0 until dataArr.length()) {
                        val item = dataArr.optJSONObject(i) ?: continue
                        val slug = item.optString("info_slug").takeIf { it.isNotBlank() } ?: continue
                        val animeUrl = fixUrlNull("$mainUrl/$slug") ?: continue
                        if (!seen.add(animeUrl)) continue

                        val title = item.optString("info_title").takeIf { it.isNotBlank() }
                            ?: item.optString("info_titleenglish").takeIf { it.isNotBlank() }
                            ?: slug
                        val posterFile = item.optString("info_poster").takeIf { it.isNotBlank() }
                        val posterUrl = if (posterFile != null) "$mainUrl/storage/pcovers/$posterFile" else null

                        results.add(newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
                            this.posterUrl = posterUrl
                        })
                    }
                }
            }
        }

        // 2. DOM Yedek Arama
        if (results.isEmpty()) {
            val fallbackDoc = safeGetDoc("$mainUrl/kategoriler/2")
            fallbackDoc?.select("a[href]")?.forEach { el ->
                el.toAnizmSearchResult()?.let {
                    if (it.name.contains(trimmed, ignoreCase = true) && seen.add(it.url)) {
                        results.add(it)
                    }
                }
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        ensureInit()
        val cleanUrl = cleanAnimeUrl(url) ?: url
        val doc = safeGetDoc(cleanUrl) ?: safeGetDoc(url) ?: throw ErrorLoadingException("Sayfa yüklenemedi: $url")

        val slug = cleanUrl.substringAfterLast("/").trim()

        var animeTitle: String? = null
        var englishTitle: String? = null
        var animePlot: String? = null
        var animePoster: String? = null
        var animeYear: Int? = null
        var animeScore: Double? = null
        var animeTags: List<String> = emptyList()

        // 1. Anizm /searchAnime API'sinden zengin Türkçe metaverileri çek
        runCatching {
            val qStr = slug.replace("-", " ").encodeUrl()
            val searchUrl = "$mainUrl/searchAnime?query=$qStr&page=1&limit=5"
            val jsonStr = safeGetText(searchUrl, referer = "$mainUrl/", isAjax = true)
            if (jsonStr.isNotBlank() && jsonStr.startsWith("{")) {
                val jsonObj = JSONObject(jsonStr)
                val dataArr = jsonObj.optJSONArray("data")
                if (dataArr != null && dataArr.length() > 0) {
                    var item: JSONObject? = null
                    for (i in 0 until dataArr.length()) {
                        val obj = dataArr.optJSONObject(i) ?: continue
                        if (obj.optString("info_slug").equals(slug, ignoreCase = true)) {
                            item = obj
                            break
                        }
                    }
                    if (item == null) item = dataArr.optJSONObject(0)

                    item?.let {
                        animeTitle = it.optString("info_title").takeIf { s -> s.isNotBlank() }
                        englishTitle = it.optString("info_titleenglish").takeIf { s -> s.isNotBlank() }
                        animePlot = it.optString("info_summary").takeIf { s -> s.isNotBlank() }
                        val posterFile = it.optString("info_poster").takeIf { s -> s.isNotBlank() }
                        if (posterFile != null) animePoster = "$mainUrl/storage/pcovers/$posterFile"
                        animeYear = it.optString("info_year").toIntOrNull()
                        val malPoint = it.optDouble("info_malpoint")
                        if (!malPoint.isNaN() && malPoint > 0.0) animeScore = malPoint

                        val cats = it.optJSONArray("categories")
                        if (cats != null) {
                            val list = mutableListOf<String>()
                            for (c in 0 until cats.length()) {
                                cats.optJSONObject(c)?.optString("name")?.takeIf { name -> name.isNotBlank() }?.let { name -> list.add(name) }
                            }
                            if (list.isNotEmpty()) animeTags = list
                        }
                    }
                }
            }
        }

        // 2. DOM Fallback
        if (animeTitle.isNullOrBlank()) {
            animeTitle = doc.selectFirst("h1.animeName, h1.title, .animeTitle, h1")?.text()
                ?.replace(Regex("""/\s*\d+\.\s*Bölüm.*$""", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: titleFromSlug(cleanUrl)
        }

        if (animePoster.isNullOrBlank()) {
            animePoster = fixUrlNull(
                doc.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: doc.selectFirst("img[data-src*='/storage/pcovers/'], .anizm_poster img, .animePoster img, img.animePoster")?.let {
                        it.attr("data-src").takeIf { s -> s.isNotBlank() && !s.contains("base64") }
                            ?: it.attr("src").takeIf { s -> s.isNotBlank() && !s.contains("base64") }
                    }
                    ?: doc.selectFirst("img.img-fluid, .poster img, div.poster img")?.let {
                        it.attr("data-src").takeIf { s -> s.isNotBlank() && !s.contains("base64") }
                            ?: it.attr("src").takeIf { s -> s.isNotBlank() && !s.contains("base64") }
                    }
            )
        }

        if (animePlot.isNullOrBlank()) {
            animePlot = doc.selectFirst("meta[property='og:description']")?.attr("content")
                ?: doc.selectFirst(".anizm_conText, .animeDescription, .description, .ozet, p.text-muted")?.text()?.trim()
        }

        // 3. Bölümleri Topla
        val rawEpisodes = mutableListOf<Pair<Int?, String>>()
        val seenEps = mutableSetOf<String>()

        doc.select("a[href*='-bolum']").forEach { el ->
            val href = el.attr("href").trim()
            val epUrl = fixUrlNull(href) ?: return@forEach
            if (!seenEps.add(epUrl)) return@forEach

            val epText = el.text().trim()
            val epMatch = Regex("""(?:-(\d+)-bolum|(\d+)\.\s*Bölüm|(\d+)\s*bolum)""", RegexOption.IGNORE_CASE)
                .find("$epUrl $epText")
            val epNum = epMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()
            rawEpisodes.add(epNum to epUrl)
        }

        val sortedEpisodes = rawEpisodes.sortedBy { it.first ?: 9999 }

        // 4. Anizium & AniList ile Bölüm Kapakları, Başlıkları, Özetleri ve Karakterleri Zenginleştir
        val searchCandidates = listOfNotNull(animeTitle, englishTitle, slug.replace("-", " ")).distinct()

        var aniziumEpMap = emptyMap<Int, EnrichedEpisode>()
        var aniziumBanner: String? = null

        for (candidate in searchCandidates) {
            val (eps, banner) = fetchAniziumMetadata(candidate)
            if (eps.isNotEmpty() || banner != null) {
                aniziumEpMap = eps
                aniziumBanner = banner
                break
            }
        }

        var aniListActors = emptyList<Actor>()
        var aniListBanner: String? = null
        var aniListScore: Double? = null

        for (candidate in searchCandidates) {
            val (actors, banner, score) = fetchAniListMetadata(candidate)
            if (actors.isNotEmpty() || banner != null || score != null) {
                aniListActors = actors
                aniListBanner = banner
                aniListScore = score
                break
            }
        }

        if (animeScore == null && aniListScore != null) {
            animeScore = aniListScore
        }

        val finalBanner = aniziumBanner ?: aniListBanner ?: animePoster

        // 5. Episode Listesini Oluştur
        val episodes = sortedEpisodes.map { (epNum, epUrl) ->
            val num = epNum ?: 1
            val aniziumEp = aniziumEpMap[num]
            val epTitle = aniziumEp?.name?.takeIf { it.isNotBlank() && !it.equals("Bölüm $num", ignoreCase = true) }
            val epDesc = aniziumEp?.overview?.takeIf { it.isNotBlank() }
            val epThumb = aniziumEp?.thumb?.takeIf { it.isNotBlank() } ?: animePoster

            newEpisode(epUrl) {
                this.name = epTitle
                this.episode = epNum
                this.season = 1
                this.description = epDesc
                this.posterUrl = epThumb
            }
        }

        return newAnimeLoadResponse(animeTitle ?: "Anime", cleanUrl, TvType.Anime) {
            this.posterUrl = animePoster
            this.backgroundPosterUrl = finalBanner
            this.plot = animePlot
            this.year = animeYear
            this.tags = animeTags
            animeScore?.let { this.score = Score.from10(it) }
            if (aniListActors.isNotEmpty()) {
                addActors(aniListActors)
            }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureInit()
        val doc = safeGetDoc(data) ?: return false
        val extractedUrls = mutableSetOf<String>()

        // 1. Translator / Fansub bağlantılarını topla
        val translatorElements = doc.select("a[translator]")
        val fansubList = mutableListOf<Pair<String, String>>()
        if (translatorElements.isNotEmpty()) {
            translatorElements.forEach { el ->
                val fName = el.text().trim().takeIf { it.isNotBlank() } ?: "Fansub"
                val tUrl = fixUrlNull(el.attr("translator")) ?: return@forEach
                fansubList.add(Pair(fName, tUrl))
            }
        }

        // 2. Her translator için video butonlarını al
        val videoButtons = mutableListOf<Triple<String, String, String>>() // Triple(DisplayName, VideoUrl, ServerName)

        if (fansubList.isNotEmpty()) {
            for ((fansubName, transUrl) in fansubList.distinctBy { it.second }.take(3)) {
                val transJson = safeGetText(transUrl, referer = data, isAjax = true)
                if (transJson.isBlank()) continue
                val dataHtml = runCatching { JSONObject(transJson).optString("data") }.getOrNull() ?: continue
                if (dataHtml.isBlank()) continue

                val transDoc = Jsoup.parse(dataHtml)
                transDoc.select("a[video]").forEach { btn ->
                    val vUrl = fixUrlNull(btn.attr("video")) ?: return@forEach
                    val sName = btn.attr("data-video-name").takeIf { it.isNotBlank() }
                        ?: btn.text().trim().takeIf { it.isNotBlank() }
                        ?: "Player"
                    videoButtons.add(Triple("$fansubName - $sName", vUrl, sName))
                }
            }
        } else {
            doc.select("a[video]").forEach { btn ->
                val vUrl = fixUrlNull(btn.attr("video")) ?: return@forEach
                val sName = btn.attr("data-video-name").takeIf { it.isNotBlank() }
                    ?: btn.text().trim().takeIf { it.isNotBlank() }
                    ?: "Player"
                videoButtons.add(Triple(sName, vUrl, sName))
            }
        }

        // 3. Butonları öncelik sırasına koy (Aincrad kendi playerları en başta 110 puan, ardından Vidmoly, Ok.ru, Sibnet...)
        val sortedButtons = videoButtons.sortedByDescending { (_, _, serverName) ->
            val s = serverName.lowercase()
            when {
                s.contains("aincrad") || s.contains("reklamsız") || s.contains("reklamsiz") -> 110
                s.contains("vidmoly") -> 100
                s.contains("odnoklassniki") || s.contains("ok.ru") || s.contains("okru") -> 95
                s.contains("sibnet") -> 90
                s.contains("voe") -> 85
                s.contains("uqload") -> 80
                s.contains("dood") -> 75
                s.contains("mail") -> 70
                s.contains("sistenn") -> 60
                else -> 50
            }
        }

        // 4. Her buton için video sayfasını ve oynatıcı iframe'ini çöz
        for ((displayName, videoUrl, _) in sortedButtons.take(6)) {
            runCatching {
                val videoJson = safeGetText(videoUrl, referer = data, isAjax = true)
                if (videoJson.isBlank()) return@runCatching
                val playerHtml = runCatching { JSONObject(videoJson).optString("player") }.getOrNull() ?: ""
                val playerIframe = Jsoup.parse(playerHtml).selectFirst("iframe[src]")?.attr("src")
                    ?: Regex("""src=["']([^"']+)["']""").find(playerHtml)?.groupValues?.get(1)

                val playerUrl = fixUrlNull(playerIframe) ?: return@runCatching

                // /player/{id} sayfasına istek at - 302 yönlendirmesi gerçek embed URL'sini verir!
                val resp = app.get(
                    playerUrl,
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
                    ),
                    timeout = 10
                )

                val finalEmbedUrl = resp.url
                val responseHtml = resp.text

                // A. Aincrad (Anizm'in kendi yerel oynatıcısı: anizmplayer.com)
                if (finalEmbedUrl.contains("anizmplayer.com/video/")) {
                    val hashId = finalEmbedUrl.substringAfter("/video/").substringBefore("?").substringBefore("/")
                    if (hashId.isNotBlank()) {
                        val apiUrl = "https://anizmplayer.com/player/index.php?data=$hashId&do=getVideo"
                        val postData = mapOf("hash" to hashId, "r" to "$mainUrl/")
                        val apiHeaders = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
                            "Referer" to finalEmbedUrl,
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                        val apiResp = app.post(apiUrl, data = postData, headers = apiHeaders, timeout = 8).text
                        val streamUrl = runCatching { JSONObject(apiResp).optString("videoSource") }.getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: runCatching { JSONObject(apiResp).optString("securedLink") }.getOrNull()
                                ?.takeIf { it.isNotBlank() }

                        if (!streamUrl.isNullOrBlank() && extractedUrls.add(streamUrl)) {
                            callback(newExtractorLink(
                                source = name,
                                name = "$name [$displayName - HLS]",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = mapOf(
                                    "Referer" to "https://anizmplayer.com/",
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
                                )
                            })
                        }
                    }
                } else if (finalEmbedUrl != playerUrl && extractedUrls.add(finalEmbedUrl)) {
                    // B. Yönlendirilmiş harici embed linkini (Vidmoly, Ok.ru, Voe, Sibnet) CloudStream extractor'a ver
                    loadExtractor(finalEmbedUrl, mainUrl, subtitleCallback, callback)
                }

                // C. Sayfa içindeki doğrudan akışları veya iframeleri tara
                extractStreamsFromHtml(responseHtml, mainUrl, displayName, subtitleCallback, callback, extractedUrls)
            }
        }

        // 5. Ana sayfadaki doğrudan iframeler (varsa)
        doc.select("iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (!src.contains("a-ads.com") && !src.contains("adsterra") && extractedUrls.add(src)) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
            }
        }

        return extractedUrls.isNotEmpty()
    }

    private suspend fun extractStreamsFromHtml(
        html: String,
        refererUrl: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        extractedUrls: MutableSet<String>
    ) {
        runCatching {
            val unescapedHtml = html.replace("\\/", "/").replace("\\\"", "\"")

            // 1. Iframe ve Embed URL taraması
            val iframeMatches = Regex("""(?:iframe|embed)[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(unescapedHtml).map { it.groupValues[1] }.toList() +
                    Regex("""["'](https?://[^"']*(?:vidmoly|ok\.ru|odnoklassniki|sibnet|voe|uqload|dood|mail\.ru)[^"']*)["']""", RegexOption.IGNORE_CASE)
                        .findAll(unescapedHtml).map { it.groupValues[1] }.toList()

            iframeMatches.distinct().forEach { rawIframe ->
                val cleanIframe = fixUrlNull(rawIframe) ?: return@forEach
                if (extractedUrls.add(cleanIframe)) {
                    loadExtractor(cleanIframe, refererUrl, subtitleCallback, callback)
                }
            }

            // 2. HLS (.m3u8) video akışları
            val m3u8Matches = Regex("""(?:file|src|source|hls)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
                .findAll(unescapedHtml).map { it.groupValues[1] }.toList() +
                    Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
                        .findAll(unescapedHtml).map { it.groupValues[1] }.toList()

            m3u8Matches.distinct().forEach { m3u8Url ->
                val cleanM3u8 = fixUrlNull(m3u8Url) ?: return@forEach
                if (extractedUrls.add(cleanM3u8)) {
                    callback(newExtractorLink(
                        source = name,
                        name = "$name [$serverName]",
                        url = cleanM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
                        )
                    })
                }
            }

            // 3. Doğrudan mp4 video akışları
            val mp4Matches = Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
                .findAll(unescapedHtml).map { it.groupValues[1] }.toList() +
                    Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
                        .findAll(unescapedHtml).map { it.groupValues[1] }.toList()

            mp4Matches.distinct().forEach { mp4Url ->
                val cleanMp4 = fixUrlNull(mp4Url) ?: return@forEach
                if (extractedUrls.add(cleanMp4)) {
                    callback(newExtractorLink(
                        source = name,
                        name = "$name [$serverName]",
                        url = cleanMp4,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
                        )
                    })
                }
            }
        }
    }

    private fun isInvalidTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return true
        val lower = title.lowercase().trim()
        return lower == "izle" ||
                lower == "bölüm" ||
                lower == "bolum" ||
                lower == "giriş yap" ||
                lower == "giris yap" ||
                lower == "kayıt ol" ||
                lower == "kayit ol" ||
                lower == "kategoriler" ||
                lower == "temalar" ||
                lower == "profil" ||
                lower == "hesabım" ||
                lower == "ilk bölümü izle" ||
                lower == "bölümü izle" ||
                lower == "hepsini izle" ||
                lower.matches(Regex("""^\d+$""")) ||
                lower.length < 2
    }

    private fun cleanAnimeUrl(url: String): String? {
        val clean = url.replace(Regex("""-\d+-bolum.*$""", RegexOption.IGNORE_CASE), "")
        val lower = clean.lowercase()
        if (lower.contains("/bolum/") ||
            lower.contains("/kategoriler") ||
            lower.contains("/temalar") ||
            lower.contains("/profil") ||
            lower.contains("/giris-yap") ||
            lower.contains("/kayit-ol") ||
            lower.contains("/sifremi-unuttum") ||
            lower.contains("/takvim") ||
            lower.contains("/fansublar") ||
            lower.contains("/raporver/") ||
            lower.endsWith(".css") ||
            lower.endsWith(".js")
        ) {
            return null
        }
        return clean
    }

    private fun titleFromSlug(url: String): String {
        return url.substringAfterLast("/")
            .replace(Regex("""-\d+-bolum.*$""", RegexOption.IGNORE_CASE), "")
            .replace("-", " ")
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }

    private fun Element.toAnizmSearchResult(): SearchResponse? {
        val rawHref = attr("href").takeIf { it.isNotBlank() } ?: return null
        val url = fixUrlNull(rawHref) ?: return null
        val finalUrl = cleanAnimeUrl(url) ?: return null

        val title = selectFirst("span, h3, div.animeName, div.title")?.text()?.trim()
            ?.takeIf { !isInvalidTitle(it) }
            ?: attr("title").takeIf { !isInvalidTitle(it) }
            ?: text().trim().takeIf { !isInvalidTitle(it) }
            ?: titleFromSlug(finalUrl)

        if (isInvalidTitle(title)) return null

        val poster = fixUrlNull(
            selectFirst("img")?.let {
                it.attr("data-src").takeIf { s -> !s.contains("base64") && s.isNotBlank() }
                    ?: it.attr("src").takeIf { s -> !s.contains("base64") && s.isNotBlank() }
            } ?: parent()?.selectFirst("img")?.let {
                it.attr("data-src").takeIf { s -> !s.contains("base64") && s.isNotBlank() }
                    ?: it.attr("src").takeIf { s -> !s.contains("base64") && s.isNotBlank() }
            } ?: parents().firstOrNull { it.selectFirst("img") != null }
                ?.selectFirst("img")?.let {
                    it.attr("data-src").takeIf { s -> !s.contains("base64") && s.isNotBlank() }
                        ?: it.attr("src").takeIf { s -> !s.contains("base64") && s.isNotBlank() }
                }
        )

        return newAnimeSearchResponse(title, finalUrl, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    data class EnrichedEpisode(
        val name: String? = null,
        val overview: String? = null,
        val thumb: String? = null
    )

    private val aniziumTokenKey = "hlxjl1c2w281ax473rt1ofgrvhyjvi"

    private fun getAniziumCfControl(): String {
        return try {
            val sdf = SimpleDateFormat("EEEE", Locale.ENGLISH)
            sdf.timeZone = TimeZone.getTimeZone("Europe/Istanbul")
            val weekday = sdf.format(Date()).lowercase()
            val key = "${aniziumTokenKey}_$weekday".toByteArray(Charsets.UTF_8)

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

    private suspend fun fetchAniziumMetadata(searchTitle: String): Pair<Map<Int, EnrichedEpisode>, String?> {
        val epMap = mutableMapOf<Int, EnrichedEpisode>()
        var banner: String? = null
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Origin" to "https://anizium.co",
                "Referer" to "https://anizium.co/",
                "device" to "browser",
                "language" to "tr",
                "site" to "main",
                "Cf-Control" to getAniziumCfControl()
            )
            val sUrl = "https://api.anizium.co/page/search?value=${searchTitle.encodeUrl()}&page=1"
            val sResp = app.get(sUrl, headers = headers, timeout = 4).text
            val sJson = JSONObject(sResp)
            val dataArr = sJson.optJSONObject("page")?.optJSONArray("data") ?: sJson.optJSONArray("data")
            val animeId = dataArr?.optJSONObject(0)?.optString("ID")?.takeIf { it.isNotBlank() }
            banner = dataArr?.optJSONObject(0)?.optString("banner")?.takeIf { it.isNotBlank() }
                ?: dataArr?.optJSONObject(0)?.optString("details_banner")?.takeIf { it.isNotBlank() }

            if (!animeId.isNullOrBlank()) {
                val dResp = app.get("https://api.anizium.co/anime/get?id=$animeId", headers = headers, timeout = 4).text
                val dJson = JSONObject(dResp).optJSONObject("data")
                if (banner.isNullOrBlank()) {
                    banner = dJson?.optString("banner")?.takeIf { it.isNotBlank() }
                        ?: dJson?.optString("details_banner")?.takeIf { it.isNotBlank() }
                }
                val seasons = dJson?.optJSONArray("seasons")
                val episodes = seasons?.optJSONObject(0)?.optJSONArray("episodes")
                if (episodes != null) {
                    for (i in 0 until episodes.length()) {
                        val epObj = episodes.optJSONObject(i) ?: continue
                        val epNum = epObj.optInt("number", -1)
                        if (epNum > 0) {
                            val epName = epObj.optString("name").takeIf { it.isNotBlank() && !it.equals("Bölüm $epNum", ignoreCase = true) }
                            val epDesc = epObj.optString("overview").takeIf { it.isNotBlank() }
                            val epThumb = epObj.optString("banner_link").takeIf { it.isNotBlank() }
                            epMap[epNum] = EnrichedEpisode(epName, epDesc, epThumb)
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return epMap to banner
    }

    private suspend fun fetchAniListMetadata(searchTitle: String): Triple<List<Actor>, String?, Double?> {
        val actors = mutableListOf<Actor>()
        var banner: String? = null
        var score: Double? = null
        try {
            val queryStr = """
                query (${'$'}search: String) {
                  Media (search: ${'$'}search, type: ANIME) {
                    bannerImage
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
        return Triple(actors, banner, score)
    }
}
