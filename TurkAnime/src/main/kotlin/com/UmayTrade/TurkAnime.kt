
package com.UmayTrade

import android.util.Log
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Tranimeizle : MainAPI() {
    override var mainUrl              = "https://www.tranimeizle.io"
    override var name                 = "Tranimeizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    // ✅ Tam tarayıcı benzeri başlıklar (403/Cloudflare bypass için)
    private val headers = mapOf(
        "User-Agent"                to "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language"           to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept-Encoding"           to "gzip, deflate, br",
        "Connection"                to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest"            to "document",
        "Sec-Fetch-Mode"            to "navigate",
        "Sec-Fetch-Site"            to "none",
        "Sec-Fetch-User"            to "?1",
        "Cache-Control"             to "max-age=0",
        "DNT"                       to "1"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/animeizle/aksiyon-anime-izle"           to "Aksiyon",
        "${mainUrl}/animeizle/arabalar-anime-izle"          to "Arabalar",
        "${mainUrl}/animeizle/askeri-anime-izle"            to "Askeri",
        "${mainUrl}/animeizle/bilim-kurgu-anime-izle"       to "Bilim Kurgu",
        "${mainUrl}/animeizle/buyu-anime-izle"              to "Büyü",
        "${mainUrl}/animeizle/cocuk-anime-izle"             to "Çocuklar",
        "${mainUrl}/animeizle/dogaustu-gucler-anime-izle"   to "Doğaüstü Güçler",
        "${mainUrl}/animeizle/dovus-anime-izle"             to "Dövüş",
        "${mainUrl}/animeizle/dram-anime-izle"              to "Dram",
        "${mainUrl}/animeizle/ecchi-anime-izle"             to "Ecchi",
        "${mainUrl}/animeizle/fantastik-anime-izle"         to "Fantastik",
        "${mainUrl}/animeizle/gerilim-anime-izle"           to "Gerilim",
        "${mainUrl}/animeizle/gizem-anime-izle"             to "Gizem",
        "${mainUrl}/animeizle/harem-anime-izle"             to "Harem",
        "${mainUrl}/animeizle/josei-anime-izle"             to "Josei",
        "${mainUrl}/animeizle/komedi-anime-izle"            to "Komedi",
        "${mainUrl}/animeizle/korku-anime-izle"             to "Korku",
        "${mainUrl}/animeizle/macera-anime-izle"            to "Macera",
        "${mainUrl}/animeizle/mecha-anime-izle"             to "Mecha",
        "${mainUrl}/animeizle/movie-anime-izle"             to "Film",
        "${mainUrl}/animeizle/muzik-anime-izle"             to "Müzik",
        "${mainUrl}/animeizle/ova-anime-izle"               to "OVA",
        "${mainUrl}/animeizle/okul-anime-izle"              to "Okul",
        "${mainUrl}/animeizle/oyun-anime-izle"              to "Oyun",
        "${mainUrl}/animeizle/psikolojik-anime-izle"        to "Psikolojik",
        "${mainUrl}/animeizle/romantizm-anime-izle"         to "Romantizm",
        "${mainUrl}/animeizle/seinen-anime-izle"            to "Seinen",
        "${mainUrl}/animeizle/shoujo-anime-izle"            to "Shoujo",
        "${mainUrl}/animeizle/shoujo-ai-anime-izle"         to "Shoujo Ai",
        "${mainUrl}/animeizle/shounen-anime-izle"           to "Shounen",
        "${mainUrl}/animeizle/shounen-ai-anime-izle"        to "Shounen Ai",
        "${mainUrl}/animeizle/yasamdan-kesitler-anime-izle" to "Yaşamdan Kesitler",
        "${mainUrl}/animeizle/spor-anime-izle"              to "Spor",
        "${mainUrl}/animeizle/super-guc-anime-izle"         to "Süper Güç",
        "${mainUrl}/animeizle/tarihi-anime-izle"            to "Tarihi",
        "${mainUrl}/animeizle/uzay-anime-izle"              to "Uzay",
        "${mainUrl}/animeizle/vampir-anime-izle"            to "Vampir",
        "${mainUrl}/animeizle/yaoi-anime-izle"              to "Yaoi",
        "${mainUrl}/animeizle/yuri-anime-izle"              to "Yuri",
        "${mainUrl}/animeizle/polisiye-anime-izle"          to "Polisiye",
        "${mainUrl}/animeizle/samuray-anime-izle"           to "Samuray",
        "${mainUrl}/animeizle/parodi-anime-izle"            to "Parodi",
        "${mainUrl}/animeizle/seytanlar-anime-izle"         to "Şeytanlar",
        "${mainUrl}/animeizle/savas-sanatlari-anime-izle"   to "Savaş Sanatları",
        "${mainUrl}/animeizle/ona-anime-izle"               to "Ona",
        "${mainUrl}/animeizle/kisilik-bolunmesi-anime-izle" to "Kişilik Bölünmesi",
        "${mainUrl}/animeizle/donghua-anime-izle"           to "Donghua",
        "${mainUrl}/animeizle/isekai-anime-izle"            to "Isekai",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "${request.data}-1" else "${request.data}-$page"
        Log.d("TRANM", "getMainPage() URL » $url")

        val document = try {
            app.get(url, headers = headers).document
        } catch (e: Exception) {
            Log.e("TRANM", "getMainPage HATA: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }

        val items = document.select("div.flx-block")
        val home  = items.mapNotNull { it.toMainPageResult() }

        Log.d("TRANM", "getMainPage(${request.name}, page=$page) -> ${home.size} sonuç")

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val href = fixUrlNull(
            this.selectFirst("a.news-image")?.attr("href")
                ?: this.attr("data-href")
        ) ?: return null

        val title = this.selectFirst("div.bar h4")?.text()?.trim() ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img.img-responsive")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "${mainUrl}/arama/$encoded"
        Log.d("TRANM", "search() URL » $url")

        val document = try {
            app.get(url, headers = headers).document
        } catch (e: Exception) {
            Log.e("TRANM", "search HATA: ${e.message}")
            return emptyList()
        }

        val items = document.select("div.flx-block")
        Log.d("TRANM", "search($query) -> ${items.size} sonuç")

        return items.mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ============================================================
    // DETAY SAYFASI
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        Log.d("TRANM", "════════════════════════════════════════")
        Log.d("TRANM", "load() BAŞLADI: $url")

        val response = try {
            app.get(url, headers = headers)
        } catch (e: Exception) {
            Log.e("TRANM", "!!! HTTP İSTEĞİ HATA: ${e.javaClass.simpleName}: ${e.message}")
            throw ErrorLoadingException("HTTP isteği başarısız: ${e.message}")
        }

        Log.d("TRANM", "HTTP Status Code: ${response.code}")
        Log.d("TRANM", "Response URL: ${response.url}")
        Log.d("TRANM", "Content-Type: ${response.headers["Content-Type"]}")
        Log.d("TRANM", "Response length: ${response.text.length}")

        if (!response.isSuccessful) {
            Log.e("TRANM", "!!! BAŞARISIZ HTTP: ${response.code}")
            Log.e("TRANM", "Yanıt ilk 500: ${response.text.take(500)}")
            throw ErrorLoadingException("Site yanıt vermedi: HTTP ${response.code}")
        }

        val document = response.document
        Log.d("TRANM", "HTML title etiketi: '${document.title()}'")
        Log.d("TRANM", "HTML ilk 500 karakter: ${document.outerHtml().take(500)}")

        // Başlık
        val title = document.selectFirst("div.playlist-title h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: document.selectFirst("div.animeDetail-content h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()

        Log.d("TRANM", "Bulunan başlık: '$title'")

        if (title.isNullOrBlank()) {
            Log.e("TRANM", "!!! Başlık bulunamadı, sayfa yapısı beklenmiyor")
            return null
        }

        // Poster
        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.animeDetail-content img")?.attr("src")
                ?: document.selectFirst("div.animeDetail-poster img")?.attr("src")
        )
        Log.d("TRANM", "Bulunan poster: $poster")

        // Açıklama
        val description = document.selectFirst("meta[property='og:description']")
            ?.attr("content")
            ?.let { org.jsoup.parser.Parser.unescapeEntities(it, true) }
            ?.replace(Regex("<[^>]*>"), "")
            ?.trim()
            ?: document.selectFirst("meta[name='description']")
                ?.attr("content")
                ?.let { org.jsoup.parser.Parser.unescapeEntities(it, true) }
                ?.replace(Regex("<[^>]*>"), "")
                ?.trim()

        // Türler
        val tags = document.select("ol.breadcrumb li a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it != "Animeler" }

        // Bölüm listesi — birden fazla seçici denenir
        val selectorList = listOf(
            "div.animeDetail-items ol li a",
            "div.animeDetail-items ul li a",
            "div.animeDetail-playlist ol li a",
            "div.animeDetail-playlist ul li a",
            "div.bolumler a",
            "ul.episode-list a",
            "div.episodes a"
        )

        var episodeElements = document.select(selectorList[0])
        for ((idx, sel) in selectorList.withIndex()) {
            val found = document.select(sel)
            Log.d("TRANM", "Seçici #$idx '$sel' -> ${found.size} element")
            if (found.size > episodeElements.size) {
                episodeElements = found
            }
        }

        Log.d("TRANM", "TOPLAM bölüm elementi: ${episodeElements.size}")

        val episodes = episodeElements.mapNotNull { el ->
            val epHref = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val epName = el.selectFirst("div.etitle span")?.text()?.trim()
                ?: el.selectFirst("span")?.text()?.trim()
                ?: el.text().trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val epNum = Regex("""(\d+)\.\s*Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epSeason = Regex("""(\d+)\.\s*Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name    = epName
                this.season  = epSeason
                this.episode = epNum
            }
        }

        Log.d("TRANM", "Parse edilen bölüm sayısı: ${episodes.size}")
        Log.d("TRANM", "════════════════════════════════════════")

        if (episodes.isEmpty()) {
            Log.w("TRANM", "Bölüm yok, tek yapım olarak dönülüyor")
            return newMovieLoadResponse(title, url, TvType.Anime, url) {
                this.posterUrl = poster
                this.plot      = description
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.tags      = tags
        }
    }

    // ============================================================
    // LİNKLER
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TRANM", "════════════════════════════════════════")
        Log.d("TRANM", "loadLinks BAŞLADI: $data")

        val document = try {
            app.get(data, headers = headers).document
        } catch (e: Exception) {
            Log.e("TRANM", "loadLinks HTTP HATA: ${e.message}")
            return false
        }

        Log.d("TRANM", "Sayfa başlığı: '${document.title()}'")

        // animeWatch.initialize(animeId, episodeId, fansubId, ...)
        val allScripts = document.select("script").map { it.data() }
        Log.d("TRANM", "Toplam ${allScripts.size} script etiketi")

        val scriptText = allScripts.firstOrNull { it.contains("animeWatch.initialize") }
        if (scriptText == null) {
            Log.e("TRANM", "!!! animeWatch.initialize bulunamadı")
            allScripts.forEachIndexed { i, s ->
                if (s.isNotBlank()) Log.d("TRANM", "  script[$i]: ${s.take(200)}")
            }
            return false
        }

        Log.d("TRANM", "animeWatch script bulundu: ${scriptText.take(300)}")

        val match = Regex("""animeWatch\.initialize\((\d+),\s*(\d+),\s*(\d+)""").find(scriptText)
        if (match == null) {
            Log.e("TRANM", "!!! regex eşleşmedi")
            return false
        }

        val animeId   = match.groupValues[1]
        val episodeId = match.groupValues[2]
        val fansubId  = match.groupValues[3]

        Log.d("TRANM", "animeId=$animeId episodeId=$episodeId fansubId=$fansubId")

        // Birden fazla olası endpoint denenir
        val endpointsToTry = listOf(
            "${mainUrl}/Video/GetVideo?animeId=$animeId&episodeId=$episodeId&fansubId=$fansubId",
            "${mainUrl}/Video/GetSources?animeId=$animeId&episodeId=$episodeId&fansubId=$fansubId",
            "${mainUrl}/Video/VideoSources?animeId=$animeId&episodeId=$episodeId&fansubId=$fansubId",
            "${mainUrl}/Anime/GetVideoSources?animeId=$animeId&episodeId=$episodeId&fansubId=$fansubId"
        )

        var sourcesResponse: String? = null
        for (endpoint in endpointsToTry) {
            Log.d("TRANM", "Denenen endpoint: $endpoint")
            try {
                val r = app.get(
                    endpoint,
                    headers = headers + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer"          to data,
                        "Accept"           to "application/json, text/javascript, */*; q=0.01"
                    )
                )
                Log.d("TRANM", "  -> HTTP ${r.code} | length: ${r.text.length}")
                Log.d("TRANM", "  -> ilk 300: ${r.text.take(300)}")
                if (r.isSuccessful && r.text.length > 5) {
                    sourcesResponse = r.text
                    break
                }
            } catch (e: Exception) {
                Log.e("TRANM", "  -> HATA: ${e.message}")
            }
        }

        if (sourcesResponse == null) {
            Log.e("TRANM", "!!! Hiçbir endpoint yanıt vermedi")
            return false
        }

        // 1) JSON formatı: "url":"..."
        val jsonMatches = Regex(""""url"\s*:\s*"([^"]+)"""").findAll(sourcesResponse).toList()
        if (jsonMatches.isNotEmpty()) {
            for (m in jsonMatches) {
                val videoUrl = m.groupValues[1].replace("\\/", "/")
                Log.d("TRANM", "JSON URL » $videoUrl")
                loadExtractor(videoUrl, "${mainUrl}/", subtitleCallback, callback)
            }
            return true
        }

        // 2) HTML iframe
        val subDoc = org.jsoup.Jsoup.parse(sourcesResponse, data)
        val iframes = subDoc.select("iframe[src]")
        if (iframes.isNotEmpty()) {
            for (iframe in iframes) {
                val src = fixUrlNull(iframe.attr("src")) ?: continue
                Log.d("TRANM", "iframe » $src")
                loadExtractor(src, "${mainUrl}/", subtitleCallback, callback)
            }
            return true
        }

        // 3) Doğrudan m3u8/mp4
        val urlMatches = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""").findAll(sourcesResponse).toList()
        if (urlMatches.isNotEmpty()) {
            for (m in urlMatches) {
                Log.d("TRANM", "Direct URL » ${m.groupValues[1]}")
                loadExtractor(m.groupValues[1], "${mainUrl}/", subtitleCallback, callback)
            }
            return true
        }

        Log.w("TRANM", "Hiçbir kaynak çözümlenemedi. Ham yanıt: ${sourcesResponse.take(500)}")
        return false
    }
}
