package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element

class Anizm : MainAPI() {

    override var mainUrl = "https://anizm.net"
    override var name = "Anizm"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cfInterceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.code == 403 || response.code == 503) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    private val ajaxHeaders get() = commonHeaders + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )

    private var isInitialized = false

    private suspend fun ensureInit() {
        if (isInitialized) return
        isInitialized = true
        try {
            val config = app.get(
                "https://raw.githubusercontent.com/ulgenzade/ulgencs3/master/domains.json"
            ).text
            org.json.JSONObject(config).optString("anizm")
                ?.takeIf { it.isNotBlank() }?.let { mainUrl = it }
        } catch (_: Exception) { }
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")

    // data format: "action|param"
    // action=home     -> anasayfa swiper ve son eklenenler
    // action=filtre   -> /anime-listesi/?filtre=X
    // action=durum    -> /anime-listesi/?durum=X
    // action=tur      -> /anime-listesi/?tur=X
    // action=kategori -> /kategoriler/ID
    // action=tema     -> /temalar/ID

    override val mainPage = mainPageOf(
        "home|home"            to "Son Eklenen Bolumler",
        "tema|3"               to "Isekai",
        "kategori|34"          to "Shounen",
        "kategori|2"           to "Aksiyon",
        "kategori|1"           to "Macera",
        "kategori|13"          to "Fantastik",
        "kategori|3"           to "Komedi",
        "kategori|4"           to "Dram",
        "kategori|8"           to "Bilim Kurgu",
        "kategori|15"          to "Gizem",
        "kategori|10"          to "Dedektif",
        "kategori|11"          to "Dogaustu Gucler",
        "kategori|30"          to "Dovus Sanatlari",
        "kategori|20"          to "Korku",
        "kategori|14"          to "Gerilim",
        "kategori|7"           to "Askeri",
        "kategori|6"           to "Ecchi",
        "kategori|21"          to "Mecha",
        "tema|4"               to "Okul",
        "tema|10"              to "Super Gucler",
        "tema|1"               to "Romantizm"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureInit()
        val parts = request.data.split("|", limit = 2)
        val action = parts[0]
        val param = parts.getOrElse(1) { "" }

        val items = mutableListOf<SearchResponse>()

        when (action) {
            "home" -> {
                val doc = app.get(mainUrl, headers = commonHeaders, interceptor = cfInterceptor).document
                
                // 1. Ana Sayfa Swiper Slide Kartları (.swiper-slide a.slideAnimeLink)
                // Sitede ilk h6 = Anime Adı, ikinci h6 = Bölüm Numarası (örn. "12. Bölüm")
                doc.select("div.swiper-slide, a.slideAnimeLink").forEach { el ->
                    val a = if (el.tagName() == "a") el else el.selectFirst("a.slideAnimeLink, a[href]") ?: return@forEach
                    val href = fixUrlNull(a.attr("href")) ?: return@forEach
                    val finalUrl = cleanAnimeUrl(href) ?: return@forEach
                    
                    val h6List = a.select("h6").map { it.text().trim() }.filter { it.isNotBlank() }
                    val animeName = h6List.firstOrNull()?.takeIf { !isInvalidTitle(it) } ?: titleFromSlug(finalUrl)
                    val epName = if (h6List.size > 1) h6List[1].takeIf { it.isNotBlank() } else null
                    
                    val title = if (epName != null && !animeName.contains(epName, ignoreCase = true)) {
                        "$animeName - $epName"
                    } else {
                        animeName
                    }

                    if (isInvalidTitle(title)) return@forEach

                    val poster = fixUrlNull(
                        a.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                            ?: a.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("base64") }
                    )

                    items.add(newAnimeSearchResponse(title, finalUrl, TvType.Anime) {
                        this.posterUrl = poster
                    })
                }

                // 2. Diğer kartlar (ui card vb.)
                if (items.isEmpty()) {
                    items.addAll(parseAnimeListPage(doc))
                }
            }
            "kategori" -> {
                val url = "$mainUrl/kategoriler/$param?sayfa=$page"
                val doc = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document
                items.addAll(parseAnimeListPage(doc))
            }
            "tema" -> {
                val url = "$mainUrl/temalar/$param?sayfa=$page"
                val doc = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document
                items.addAll(parseAnimeListPage(doc))
            }
        }

        return newHomePageResponse(
            HomePageList(request.name, items.distinctBy { it.url }),
            hasNext = items.isNotEmpty()
        )
    }

    private fun parseAnimeListPage(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // 1. Kategori ve Liste Sayfası Ana Kart Yapısı: div.ui.card / div.card / div.poster-card
        doc.select("div.ui.card, div.card, div.poster-card, div.animeCard").forEach { card ->
            val linkEl = card.selectFirst("a.header.anime-title, a.header, a.anime-title, a.image, a[href]") ?: return@forEach
            val href = fixUrlNull(linkEl.attr("href")) ?: return@forEach
            val finalUrl = cleanAnimeUrl(href) ?: return@forEach

            val title = card.selectFirst("a.header.anime-title, a.header, a.anime-title, .header, h3, h2")?.text()?.trim()
                ?.takeIf { !isInvalidTitle(it) }
                ?: titleFromSlug(finalUrl)

            if (isInvalidTitle(title)) return@forEach

            val poster = fixUrlNull(
                card.selectFirst("a.image img, img")?.let { img ->
                    img.attr("data-src").takeIf { it.isNotBlank() }
                        ?: img.attr("src").takeIf { it.isNotBlank() && !it.contains("base64") }
                }
            )

            results.add(newAnimeSearchResponse(title, finalUrl, TvType.Anime) {
                this.posterUrl = poster
            })
        }
        if (results.isNotEmpty()) return results

        // 2. Swiper Slide Kartları (Ana sayfa & listeler)
        doc.select("div.swiper-slide, a.slideAnimeLink").forEach { el ->
            val a = if (el.tagName() == "a") el else el.selectFirst("a.slideAnimeLink, a[href]") ?: return@forEach
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            val finalUrl = cleanAnimeUrl(href) ?: return@forEach

            val title = a.selectFirst("h6:not(:has(img)):last-child, .animeTitle, h3, h2")?.text()?.trim()
                ?.takeIf { !isInvalidTitle(it) }
                ?: titleFromSlug(finalUrl)

            if (isInvalidTitle(title)) return@forEach

            val poster = fixUrlNull(
                a.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: a.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("base64") }
            )

            results.add(newAnimeSearchResponse(title, finalUrl, TvType.Anime) {
                this.posterUrl = poster
            })
        }
        if (results.isNotEmpty()) return results

        // 3. a.animeTitleLink Arama Sonucu Formatı
        doc.select("a.animeTitleLink").forEach { el ->
            el.toAnizmSearchResult()?.let { results.add(it) }
        }

        return results
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureInit()
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val results = mutableListOf<SearchResponse>()

        // 1. POST /request (AJAX - sitenin kendi hızlı ve detaylı arama mekanizması)
        runCatching {
            val doc = app.post(
                "$mainUrl/request",
                headers = commonHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf("action" to "search", "value" to trimmed),
                interceptor = cfInterceptor
            ).document

            // a.animeTitleLink elemanlarını ve resimlerini doğru eşleştir
            doc.select("div.searchResultItem, div.animeSearchItem, div.item").forEach { item ->
                val titleEl = item.selectFirst("a.animeTitleLink, a.title, h3 a, h4 a") ?: return@forEach
                val href = fixUrlNull(titleEl.attr("href")) ?: return@forEach
                val finalUrl = cleanAnimeUrl(href) ?: return@forEach
                val title = titleEl.text().trim().takeIf { !isInvalidTitle(it) } ?: titleFromSlug(finalUrl)
                val poster = fixUrlNull(
                    item.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                        ?: item.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("base64") }
                )
                results.add(newAnimeSearchResponse(title, finalUrl, TvType.Anime) {
                    this.posterUrl = poster
                })
            }

            if (results.isEmpty()) {
                doc.select("a.animeTitleLink").forEach { el ->
                    el.toAnizmSearchResult()?.let { results.add(it) }
                }
            }
        }
        if (results.isNotEmpty()) return results.distinctBy { it.url }

        // 2. GET /page/search
        runCatching {
            val doc = app.get(
                "$mainUrl/page/search?value=${trimmed.encodeUrl()}&page=1",
                headers = ajaxHeaders,
                interceptor = cfInterceptor
            ).document
            results.addAll(parseAnimeListPage(doc))
        }
        if (results.isNotEmpty()) return results.distinctBy { it.url }

        // 3. Anime listesi ara=
        runCatching {
            val doc = app.get(
                "$mainUrl/anime-listesi/?ara=${trimmed.encodeUrl()}",
                headers = commonHeaders,
                interceptor = cfInterceptor
            ).document
            results.addAll(parseAnimeListPage(doc))
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        ensureInit()
        val doc = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document

        val title = doc.selectFirst("h1.animeTitle, h1.page-title, h2.animeName, div.animeInfo h1, h1")
            ?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: titleFromSlug(url)

        val poster = fixUrlNull(
            doc.selectFirst("div.animePoster img, img.animeCover, div.poster img, div.animeImage img, img.cover")
                ?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("div.animePoster img, img.animeCover, div.poster img, div.animeImage img, img.cover")
                    ?.attr("src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = doc.selectFirst(
            "p.animeDesc, div.animeDescription, div.summary, div.animeInfo p, div.ozet, p.animeSummary"
        )?.text()?.trim()

        val tags = doc.select(
            "div.animeGenres a, a[href*='kategoriler'], a[href*='temalar'], div.tags a"
        ).map { it.text().trim() }.filter { it.isNotBlank() && !it.startsWith("+") }

        // Bölüm listesi
        val rawEpisodes = doc.select(
            "div.episodeListTabContent a[href*='-bolum-izle'], " +
            "div.animeEpisodesSquareListDetay a[href*='-bolum-izle'], " +
            "ul.episodeList li a, a[href*='-bolum-izle'], " +
            "div.bolumler a, ul.bolum-listesi li a"
        ).mapNotNull { el ->
            val epUrl = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val rawText = el.text().trim().takeIf { it.isNotBlank() } ?: el.attr("title").trim()
            val epNum = Regex("""(\d+)""").find(rawText)?.groupValues?.get(1)?.toIntOrNull()

            // "1. Bölüm", "12. Bölüm Final", "1. Bölüm izle" gibi metinleri temizle
            val cleanName = rawText
                .replace(Regex("""^\s*\d+\s*[\.\-:]*\s*B[oöOÖ]l[uüUÜ]m\s*[-:–]*\s*""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*izle\s*$""", RegexOption.IGNORE_CASE), "")
                .trim()

            // Eğer cleanName tamamen boşsa veya sadece "Bölüm" ise name = null bırak (CloudStream çiftleme yapmasın: "1. 1. Bölüm" yerine "1. Bölüm" olsun)
            val finalName = if (cleanName.isNotBlank() && !cleanName.equals("bölüm", ignoreCase = true) && !cleanName.equals("bolum", ignoreCase = true)) {
                cleanName
            } else {
                null
            }

            newEpisode(epUrl) {
                this.name = finalName
                this.episode = epNum
                this.season = 1
                this.posterUrl = poster
                this.description = description
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureInit()
        val doc = app.get(data, headers = commonHeaders, interceptor = cfInterceptor).document
        val extractedUrls = mutableSetOf<String>()

        // 1. Sayfadaki Fansub (Çevirmen) Butonlarını Bul
        val translatorElements = doc.select("a[data-translatorclick], a[translator], .fansubTabs a, .anizm_colorDefault[translator]")
        val fansubs = mutableListOf<Pair<String, String?>>() // Pair(FansubAdı, TranslatorUrl)

        if (translatorElements.isNotEmpty()) {
            translatorElements.forEach { el ->
                val fName = el.text().trim().takeIf { it.isNotBlank() } ?: "Fansub"
                val tUrl = fixUrlNull(el.attr("translator").takeIf { it.isNotBlank() } ?: el.attr("href"))
                fansubs.add(Pair(fName, tUrl))
            }
        } else {
            val defaultFansub = doc.selectFirst(".activeTranslator, .anizm_colorDefault.active, .fansubTitle")?.text()?.trim()
                ?: "Anizm"
            fansubs.add(Pair(defaultFansub, null))
        }

        // 2. Her Fansub İçin SADECE Aincrad Player'ını Çek
        fansubs.distinctBy { it.first }.forEach { (fansubName, translatorUrl) ->
            runCatching {
                // Eğer farklı bir çevirmen sekmesi ise onun butonlarını içeren dokümanı al
                val targetDoc = if (!translatorUrl.isNullOrBlank() && !translatorUrl.equals(data, ignoreCase = true) && !translatorUrl.endsWith("#")) {
                    val resp = app.get(
                        translatorUrl,
                        headers = commonHeaders + mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data),
                        interceptor = cfInterceptor
                    )
                    // Yanıt JSON ({data: "..."}) ise içindeki HTML'i ayrıştır
                    val jsonHtml = runCatching { org.json.JSONObject(resp.text).optString("data") }.getOrNull()
                    if (!jsonHtml.isNullOrBlank()) {
                        org.jsoup.Jsoup.parse(jsonHtml)
                    } else {
                        resp.document
                    }
                } else {
                    doc
                }

                // Bu fansub'ın Aincrad butonunu bul
                val aincradButton = targetDoc.select("a.videoPlayerButtons, button.videoPlayerButtons, a[data-playerclick], a[video], a.anizm_button[data-id]").firstOrNull { btn ->
                    val txt = btn.text().lowercase()
                    txt.contains("aincrad") || txt.contains("reklamsız") || txt.contains("reklamsiz")
                } ?: targetDoc.selectFirst("a.videoPlayerButtons, a[data-playerclick], a[video]")

                val videoId = aincradButton?.attr("data-id")?.takeIf { it.isNotBlank() }
                val videoHref = aincradButton?.attr("video")?.takeIf { it.isNotBlank() }
                    ?: aincradButton?.attr("href")?.takeIf { it.contains("/video/") }

                val targetVideoUrl = when {
                    !videoHref.isNullOrBlank() -> fixUrlNull(videoHref)
                    !videoId.isNullOrBlank() -> "$mainUrl/video/$videoId"
                    else -> null
                }

                if (!targetVideoUrl.isNullOrBlank() && extractedUrls.add(targetVideoUrl)) {
                    extractAincradStream(targetVideoUrl, data, fansubName, callback)
                } else if (!videoId.isNullOrBlank()) {
                    // AJAX fallback: POST /ajax/player action=player
                    val ajaxResp = app.post(
                        "$mainUrl/ajax/player",
                        headers = commonHeaders + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "Referer" to data
                        ),
                        data = mapOf("id" to videoId, "action" to "player"),
                        interceptor = cfInterceptor
                    ).text

                    Regex("""(?:src|href)=["'](https?://[^"']*(?:/video/|/embed/)[^"']*)["']""").findAll(ajaxResp).forEach { m ->
                        val vUrl = m.groupValues[1]
                        if (extractedUrls.add(vUrl)) {
                            extractAincradStream(vUrl, data, fansubName, callback)
                        }
                    }
                }
            }
        }

        return extractedUrls.isNotEmpty()
    }

    private suspend fun extractAincradStream(
        videoPageUrl: String,
        refererUrl: String,
        fansubName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val resp = app.get(
                videoPageUrl,
                headers = commonHeaders + mapOf(
                    "Referer" to refererUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                interceptor = cfInterceptor
            )
            val html = resp.text

            // 1. m3u8 playlist akışlarını ara
            val m3u8Matches = Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""").findAll(html).map { it.groupValues[1] }.toList() +
                    Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(html).map { it.groupValues[1] }.toList()

            m3u8Matches.distinct().forEach { m3u8Url ->
                callback(newExtractorLink(
                    source = name,
                    name = "$name [Aincrad - $fansubName]",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                    )
                })
            }

            // 2. mp4 akışlarını ara
            if (m3u8Matches.isEmpty()) {
                val mp4Matches = Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+\.mp4[^"']*)["']""").findAll(html).map { it.groupValues[1] }.toList() +
                        Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").findAll(html).map { it.groupValues[1] }.toList()

                mp4Matches.distinct().forEach { mp4Url ->
                    callback(newExtractorLink(
                        source = name,
                        name = "$name [Aincrad - $fansubName]",
                        url = mp4Url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        )
                    })
                }
            }

            // 3. İç iframe varsa (embed içi player)
            if (m3u8Matches.isEmpty()) {
                val doc = resp.document
                doc.select("source[src]").forEach { s ->
                    val src = fixUrlNull(s.attr("src")) ?: return@forEach
                    val isHls = s.attr("type").contains("mpegurl") || src.contains("m3u8")
                    callback(newExtractorLink(
                        source = name,
                        name = "$name [Aincrad - $fansubName]",
                        url = src,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ))
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
                lower == "ilk bölümü izle" ||
                lower == "bölümü izle" ||
                lower == "hepsini izle" ||
                lower.matches(Regex("""^\d+$""")) ||
                lower.length < 2
    }

    private fun cleanAnimeUrl(url: String): String? {
        var clean = url
        if (clean.contains("-bolum-izle")) {
            clean = clean.replace(Regex("""-\d+-bolum-izle.*$"""), "")
        }
        if (clean.contains("-bolum-final")) {
            clean = clean.replace(Regex("""-\d+-bolum-final.*$"""), "")
        }
        if (clean.contains("/bolum/") ||
            clean.contains("/kategoriler/") ||
            clean.contains("/temalar/") ||
            clean.contains("/takvim") ||
            clean.contains("/fansublar") ||
            clean.contains("/raporver/") ||
            clean.endsWith(".css") ||
            clean.endsWith(".js")
        ) {
            return null
        }
        return clean
    }

    private fun titleFromSlug(url: String): String {
        return url.substringAfterLast("/")
            .replace(Regex("""-\d+-bolum-.*$"""), "")
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
}