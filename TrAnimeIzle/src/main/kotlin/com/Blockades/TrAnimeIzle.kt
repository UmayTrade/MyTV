package com.ulgencs3.tranimeizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest
import android.util.Base64

/**
 * TrAnimeİzle Sağlayıcısı (v4)
 *
 * Site: https://www.tranimeizle.io
 * Özellikler:
 * - Otomatik IconCaptcha ve .AitrWeb.Session kalıcı çerez yönetimi
 * - Çoklu bölüm seçici (div.animeDetail-items a, a[href*='bolum']) ve bölüm fallback'i
 * - AitrVip Oynatıcı (optraco.top HLS M3U8 akış regex düzeltmesi)
 * - Tüm alternatif kaynaklar: Vidmoly, Sibnet, Ok.ru, Voe, MixDrop, Mp4Upload vb.
 * - AniList GraphQL Karakterler, Seslendirmenler, Banner ve Puan desteği
 */
class TrAnimeIzle : MainAPI() {

    override var mainUrl = "https://www.tranimeizle.io"
    override var name = "TrAnimeİzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    private val sessionCookies = mutableMapOf<String, String>()

    private fun updateCookiesFromResponse(res: NiceResponse) {
        sessionCookies.putAll(res.cookies)
        try {
            res.headers.values("Set-Cookie").forEach { header ->
                val pair = header.substringBefore(";").trim()
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val cName = pair.substring(0, idx).trim()
                    val cVal = pair.substring(idx + 1).trim()
                    sessionCookies[cName] = cVal
                }
            }
        } catch (_: Exception) { }
    }

    private fun getRequestHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val cookieHeader = sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return if (cookieHeader.isNotBlank()) {
            commonHeaders + extra + mapOf("Cookie" to cookieHeader)
        } else {
            commonHeaders + extra
        }
    }

    private fun String.encodeUrl(): String = URLEncoder.encode(this, "UTF-8")

    private var isInitialized = false

    private suspend fun ensureInit() {
        if (isInitialized) return
        isInitialized = true
        try {
            val config = app.get(
                "https://raw.githubusercontent.com/ulgenzade/ulgencs3/master/domains.json",
                timeout = 5
            ).text
            val d = JSONObject(config).optString("tranimeizle")
            if (!d.isNullOrBlank()) {
                mainUrl = d.trimEnd('/')
            }
        } catch (_: Exception) { }
    }

    // -------------------------------------------------------------------------
    // IconCaptcha (Bot Kontrolü) Otomatik Bypass ve Kalıcı Çerez Sistemi
    // -------------------------------------------------------------------------

    private fun ByteArray.md5Hex(): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(this).joinToString("") { "%02x".format(it) }
    }

    private suspend fun safeGetDoc(url: String): Document {
        var res = app.get(url, headers = getRequestHeaders(), cookies = sessionCookies, timeout = 12)
        updateCookiesFromResponse(res)
        var doc = res.document
        var html = doc.html()

        // Sitede Bot Kontrolü (IconCaptcha) challenge'ı tetiklendi mi kontrol et
        if (res.url.contains("CaptchaChallenge", ignoreCase = true) ||
            doc.selectFirst("div.captcha-holder") != null ||
            html.contains("iconCaptcha", ignoreCase = true) ||
            html.contains("CaptchaChallenge", ignoreCase = true)
        ) {
            val challengeUrl = res.url
            try {
                // 1. POST /api/Captcha/ (cID: 1, rT: 1, tM: "dark") -> 5 adet ikon hash'i ve .AitrWeb.Session döner
                val initRes = app.post(
                    "$mainUrl/api/Captcha/",
                    data = mapOf("cID" to "1", "rT" to "1", "tM" to "dark"),
                    headers = getRequestHeaders(mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to challengeUrl,
                        "Origin" to mainUrl
                    )),
                    cookies = sessionCookies,
                    timeout = 10
                )
                updateCookiesFromResponse(initRes)

                val jsonArray = JSONArray(initRes.text)
                val hashes = (0 until jsonArray.length()).map { jsonArray.getString(it) }

                if (hashes.size == 5) {
                    // 2. Beş ikonun baytlarını indirip MD5 özetlerini çıkar
                    val iconList = hashes.map { h ->
                        val iconRes = app.get(
                            "$mainUrl/api/Captcha/?cid=1&hash=$h",
                            headers = getRequestHeaders(mapOf("Referer" to challengeUrl)),
                            cookies = sessionCookies,
                            timeout = 8
                        )
                        updateCookiesFromResponse(iconRes)
                        h to iconRes.body.bytes().md5Hex()
                    }

                    // 3. 4 ikon aynıdır, sadece 1 tanesi farklıdır (farklı olanı bul)
                    val counts = iconList.groupBy { it.second }
                    val uniqueEntry = counts.values.firstOrNull { it.size == 1 }?.firstOrNull()
                        ?: iconList.first()

                    val oddHash = uniqueEntry.first

                    // 4. Doğrulamayı sunucuya gönder (rT: 2, pC: oddHash)
                    val verifyRes = app.post(
                        "$mainUrl/api/Captcha/",
                        data = mapOf("cID" to "1", "pC" to oddHash, "rT" to "2"),
                        headers = getRequestHeaders(mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to challengeUrl,
                            "Origin" to mainUrl
                        )),
                        cookies = sessionCookies,
                        timeout = 10
                    )
                    updateCookiesFromResponse(verifyRes)

                    // 5. Hedef sayfayı oturum çerezleriyle yeniden yükle
                    res = app.get(
                        url,
                        headers = getRequestHeaders(mapOf("Referer" to challengeUrl)),
                        cookies = sessionCookies,
                        timeout = 12
                    )
                    updateCookiesFromResponse(res)
                    doc = res.document
                }
            } catch (_: Exception) { }
        }

        return doc
    }

    // -------------------------------------------------------------------------
    // Ana Sayfa
    // -------------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "/?sayfa="                to "Son Eklenen Bölümler",
        "/listeler/eklenen/sayfa-" to "Yeni Animeler",
        "/listeler/populer/sayfa-" to "Popüler Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureInit()
        val targetUrl = if (request.data.startsWith("http")) {
            "${request.data}$page"
        } else {
            "$mainUrl${request.data}$page"
        }

        val doc = safeGetDoc(targetUrl)
        val items = doc.select("div.flx-block").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Arama
    // -------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        ensureInit()
        val cleanQuery = query.trim()
        val searchUrl = "$mainUrl/arama/${cleanQuery.encodeUrl()}"
        val doc = safeGetDoc(searchUrl)
        return doc.select("div.flx-block").mapNotNull { it.toSearchResult() }
    }

    // -------------------------------------------------------------------------
    // Detay & Bölüm Listesi
    // -------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        ensureInit()
        var targetUrl = url
        var doc = safeGetDoc(targetUrl)

        // Eğer kullanıcı ana sayfadaki bir bölüm linkine tıklamışsa, sayfadaki ana anime linkini bulup yükle
        if (!targetUrl.contains("/anime/")) {
            val parentAnimeHref = doc.selectFirst("ol.breadcrumb li a[href*='/anime/'], a[href*='/anime/']")?.attr("href")
            if (!parentAnimeHref.isNullOrBlank()) {
                val fullParent = fixUrl(parentAnimeHref)
                val parentDoc = safeGetDoc(fullParent)
                if (parentDoc.select("div.animeDetail-items a, a[href*='-bolum'], a[href*='bolum']").isNotEmpty()) {
                    targetUrl = fullParent
                    doc = parentDoc
                }
            }
        }

        val rawTitle = doc.selectFirst("h1, .anime-title, .title")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Bilinmeyen Anime"

        val cleanTitle = rawTitle.replace(Regex("""\s*İzle\s*-\s*Anime\s*izle.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*Anime\s*izle.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*İzle$""", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".anime-image img, div.poster img, img.img-responsive")?.attr("src")
        )

        val description = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?.replace(Regex("""https://www.tranimeizle.*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: doc.selectFirst("div.summary p, div.anime-desc, div.ozet")?.text()?.trim()

        val tags = doc.select("a[href*='/tur/'], a[href*='/kategori/'], div.genres a").map { it.text().trim() }

        // Bölümleri Topla
        val episodes = mutableListOf<Episode>()
        val seenEps = mutableSetOf<String>()

        doc.select("div.animeDetail-items a, a[href*='-bolum'], a[href*='bolum']").forEach { a ->
            val href = a.attr("href").trim()
            if (href.contains("icerik-denetim", ignoreCase = true)) return@forEach
            val epUrl = fixUrlNull(href) ?: return@forEach
            if (!seenEps.add(epUrl)) return@forEach

            val epText = a.text().trim()
            val seasonNum = Regex("""(\d+)[.-]sezon""", RegexOption.IGNORE_CASE).find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            val epNum = Regex("""(\d+)[.-]bolum""", RegexOption.IGNORE_CASE).find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            val cleanEpName = "Bölüm $epNum"

            episodes.add(newEpisode(epUrl) {
                this.name = cleanEpName
                this.season = seasonNum
                this.episode = epNum
                this.posterUrl = poster
            })
        }

        // Eğer bölüm listesi bulunamadıysa ama sayfa bir bölüm sayfasıysa, en azından bu bölümü ekle (Asla Çok Yakında olmasın)
        if (episodes.isEmpty() && (targetUrl.contains("bolum", ignoreCase = true) || url.contains("bolum", ignoreCase = true))) {
            val fallbackUrl = if (targetUrl.contains("bolum")) targetUrl else url
            val epNum = Regex("""(\d+)[.-]bolum""", RegexOption.IGNORE_CASE).find(fallbackUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            episodes.add(newEpisode(fallbackUrl) {
                this.name = "Bölüm $epNum"
                this.season = 1
                this.episode = epNum
                this.posterUrl = poster
            })
        }

        // AniList Karakterler, Seslendirmenler, Puan ve Banner
        val searchCandidate = cleanTitle.ifBlank { "Anime" }
        val (actors, banner, aniListScore) = fetchAniListMetadata(searchCandidate)

        val finalBanner = banner ?: poster
        val sortedEpisodes = episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 1 }))

        return newAnimeLoadResponse(cleanTitle, targetUrl, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = finalBanner
            this.plot = description
            this.tags = tags
            aniListScore?.let { this.score = Score.from10(it) }
            if (actors.isNotEmpty()) {
                addActors(actors)
            }
            addEpisodes(DubStatus.Subbed, sortedEpisodes)
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
        ensureInit()
        val doc = safeGetDoc(data)
        val html = doc.html()
        val extractedUrls = mutableSetOf<String>()

        // 1. animeWatch.initialize parametrelerini tespit et
        val initMatch = Regex("""animeWatch\.initialize\s*\(\s*(\d+)\s*,\s*(\d+)""").find(html)

        if (initMatch != null) {
            val animeId = initMatch.groupValues[1].toIntOrNull()
            val episodeId = initMatch.groupValues[2].toIntOrNull()
            val defaultFansubId = Regex("""animeWatch\.initialize\s*\(\s*\d+\s*,\s*\d+\s*,\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()

            if (episodeId != null) {
                // Sayfadaki tüm fansub alternatiflerini topla
                val fansubIds = mutableSetOf<Int>()
                defaultFansubId?.let { fansubIds.add(it) }
                doc.select(".fansubSelector[data-fid], [data-fid]").forEach { el ->
                    el.attr("data-fid").toIntOrNull()?.let { fansubIds.add(it) }
                }

                for (fid in fansubIds) {
                    try {
                        val payload = JSONObject().apply {
                            put("EpisodeId", episodeId)
                            put("FansubId", fid)
                        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                        val sourcesHtml = app.post(
                            "$mainUrl/api/fansubSources",
                            requestBody = payload,
                            headers = getRequestHeaders(mapOf(
                                "Content-Type" to "application/json; charset=utf-8",
                                "X-Requested-With" to "XMLHttpRequest",
                                "Referer" to data,
                                "Origin" to mainUrl
                            )),
                            cookies = sessionCookies,
                            timeout = 8
                        ).text

                        val sourcesDoc = Jsoup.parse(sourcesHtml)
                        val sourceBtns = sourcesDoc.select("li.sourceBtn[data-id], .sourceBtn[data-id]")

                        for (btn in sourceBtns) {
                            val sourceId = btn.attr("data-id").trim()
                            val serverName = btn.selectFirst("p.title")?.ownText()?.trim() ?: "Sunucu"
                            if (sourceId.isBlank()) continue

                            try {
                                val playerResp = app.post(
                                    "$mainUrl/api/sourcePlayer/$sourceId",
                                    requestBody = "".toRequestBody(),
                                    headers = getRequestHeaders(mapOf(
                                        "X-Requested-With" to "XMLHttpRequest",
                                        "Referer" to data,
                                        "Origin" to mainUrl
                                    )),
                                    cookies = sessionCookies,
                                    timeout = 8
                                ).text

                                val sourceJson = JSONObject(playerResp)
                                val iframeHtml = sourceJson.optString("source")
                                if (iframeHtml.isBlank()) continue

                                parseAndExtractSource(iframeHtml, serverName, data, extractedUrls, subtitleCallback, callback)
                            } catch (_: Exception) { }
                        }
                    } catch (_: Exception) { }
                }
            }
        }

        // 2. Fallback: Sayfada doğrudan gömülü iframe'ler
        doc.select("iframe[src], iframe[data-src]").forEach { el ->
            val src = fixUrlNull(el.attr("src").takeIf { it.isNotBlank() } ?: el.attr("data-src")) ?: return@forEach
            if (!src.contains("google") && !src.contains("ads") && extractedUrls.add(src)) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return extractedUrls.isNotEmpty()
    }

    private suspend fun parseAndExtractSource(
        iframeHtml: String,
        serverName: String,
        referer: String,
        extractedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sDoc = Jsoup.parse(iframeHtml)
        val rawSrc = sDoc.selectFirst("iframe")?.attr("src") ?: ""
        if (rawSrc.isBlank()) return

        // AitrVip Oynatıcı (optraco.top üzerinden doğrudan HLS M3U8 akışı)
        if (rawSrc.contains("optraco.top/explorer/")) {
            try {
                val optracoPage = app.get(
                    rawSrc,
                    headers = mapOf(
                        "Referer" to mainUrl,
                        "User-Agent" to commonHeaders["User-Agent"]!!
                    ),
                    timeout = 8
                ).text
                val m3u8Match = Regex("""file\s*:\s*["']([^"']+\.m3u8)["']""").find(optracoPage)
                    ?: Regex("""["']([^"']*\.m3u8[^"']*)["']""").find(optracoPage)

                if (m3u8Match != null) {
                    val m3u8Path = m3u8Match.groupValues[1]
                    val fullM3u8 = if (m3u8Path.startsWith("http")) m3u8Path else "https://optraco.top$m3u8Path"

                    if (extractedUrls.add(fullM3u8)) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "AitrVip [TrAnimeİzle]",
                                url = fullM3u8,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = mapOf("Referer" to "https://optraco.top/")
                            }
                        )
                        return
                    }
                }
            } catch (_: Exception) { }
        }

        // Download.ru Base64 Çözümü
        if (rawSrc.contains("downloadru2.php") && rawSrc.contains("url=")) {
            val b64 = Regex("""url=([A-Za-z0-9+/=]+)""").find(rawSrc)?.groupValues?.get(1)
            if (!b64.isNullOrBlank()) {
                try {
                    val decodedUrl = String(Base64.decode(b64, Base64.DEFAULT)).trim()
                    if (decodedUrl.startsWith("http") && extractedUrls.add(decodedUrl)) {
                        loadExtractor(decodedUrl, referer, subtitleCallback, callback)
                        return
                    }
                } catch (_: Exception) { }
            }
        }

        // Luffytra2 / Embed2 Wrapper Çözümü (?id=https://...)
        if (rawSrc.contains("embed2/?id=") || rawSrc.contains("?id=http")) {
            val targetUrl = Regex("""[?&]id=(https?://[^&"'>]+)""").find(rawSrc)?.groupValues?.get(1)
            if (!targetUrl.isNullOrBlank() && extractedUrls.add(targetUrl)) {
                loadExtractor(targetUrl, referer, subtitleCallback, callback)
                return
            }
        }

        // Standart Video Extractors (Vidmoly, Sibnet, Ok.ru, Voe, MixDrop, Mp4Upload, vb.)
        val cleanUrl = fixUrl(rawSrc)
        if (extractedUrls.add(cleanUrl)) {
            loadExtractor(cleanUrl, referer, subtitleCallback, callback)
        }
    }

    // -------------------------------------------------------------------------
    // SearchResult Dönüştürücü
    // -------------------------------------------------------------------------

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a.news-image, a") ?: return null
        val href = a.attr("href").trim()
        val url = fixUrlNull(href) ?: return null

        val rawTitle = selectFirst("div.bar h4, h4, .title, .name")?.text()?.trim()
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: return null

        val cleanTitle = rawTitle.replace(Regex("""\s*\d+\.\s*Bölüm.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*İzle$""", RegexOption.IGNORE_CASE), "")
            .trim()

        val imgEl = selectFirst("img.img-responsive, img")
        val poster = fixUrlNull(
            imgEl?.attr("src")?.takeIf { it.isNotBlank() }
                ?: imgEl?.attr("data-src")
        )

        return newAnimeSearchResponse(cleanTitle.ifBlank { rawTitle }, url, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // -------------------------------------------------------------------------
    // AniList Karakter & Banner Zenginleştirme
    // -------------------------------------------------------------------------

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
