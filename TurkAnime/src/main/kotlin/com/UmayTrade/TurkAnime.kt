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

    // ✅ HTML'den çıkarılan DOĞRU tür URL'leri
    // Format: /animeizle/{slug}-anime-izle-{sayfa}
    override val mainPage = mainPageOf(
        "${mainUrl}/animeizle/aksiyon-anime-izle"        to "Aksiyon",
        "${mainUrl}/animeizle/arabalar-anime-izle"       to "Arabalar",
        "${mainUrl}/animeizle/askeri-anime-izle"         to "Askeri",
        "${mainUrl}/animeizle/bilim-kurgu-anime-izle"    to "Bilim Kurgu",
        "${mainUrl}/animeizle/buyu-anime-izle"           to "Büyü",
        "${mainUrl}/animeizle/cocuk-anime-izle"          to "Çocuklar",
        "${mainUrl}/animeizle/dogaustu-gucler-anime-izle" to "Doğaüstü Güçler",
        "${mainUrl}/animeizle/dovus-anime-izle"          to "Dövüş",
        "${mainUrl}/animeizle/dram-anime-izle"           to "Dram",
        "${mainUrl}/animeizle/ecchi-anime-izle"          to "Ecchi",
        "${mainUrl}/animeizle/fantastik-anime-izle"      to "Fantastik",
        "${mainUrl}/animeizle/gerilim-anime-izle"        to "Gerilim",
        "${mainUrl}/animeizle/gizem-anime-izle"          to "Gizem",
        "${mainUrl}/animeizle/harem-anime-izle"          to "Harem",
        "${mainUrl}/animeizle/josei-anime-izle"          to "Josei",
        "${mainUrl}/animeizle/komedi-anime-izle"         to "Komedi",
        "${mainUrl}/animeizle/korku-anime-izle"          to "Korku",
        "${mainUrl}/animeizle/macera-anime-izle"         to "Macera",
        "${mainUrl}/animeizle/mecha-anime-izle"          to "Mecha",
        "${mainUrl}/animeizle/movie-anime-izle"          to "Film",
        "${mainUrl}/animeizle/muzik-anime-izle"          to "Müzik",
        "${mainUrl}/animeizle/ova-anime-izle"            to "OVA",
        "${mainUrl}/animeizle/okul-anime-izle"           to "Okul",
        "${mainUrl}/animeizle/oyun-anime-izle"           to "Oyun",
        "${mainUrl}/animeizle/psikolojik-anime-izle"     to "Psikolojik",
        "${mainUrl}/animeizle/romantizm-anime-izle"      to "Romantizm",
        "${mainUrl}/animeizle/seinen-anime-izle"         to "Seinen",
        "${mainUrl}/animeizle/shoujo-anime-izle"         to "Shoujo",
        "${mainUrl}/animeizle/shoujo-ai-anime-izle"      to "Shoujo Ai",
        "${mainUrl}/animeizle/shounen-anime-izle"        to "Shounen",
        "${mainUrl}/animeizle/shounen-ai-anime-izle"     to "Shounen Ai",
        "${mainUrl}/animeizle/yasamdan-kesitler-anime-izle" to "Yaşamdan Kesitler",
        "${mainUrl}/animeizle/spor-anime-izle"           to "Spor",
        "${mainUrl}/animeizle/super-guc-anime-izle"      to "Süper Güç",
        "${mainUrl}/animeizle/tarihi-anime-izle"         to "Tarihi",
        "${mainUrl}/animeizle/uzay-anime-izle"           to "Uzay",
        "${mainUrl}/animeizle/vampir-anime-izle"         to "Vampir",
        "${mainUrl}/animeizle/yaoi-anime-izle"           to "Yaoi",
        "${mainUrl}/animeizle/yuri-anime-izle"           to "Yuri",
        "${mainUrl}/animeizle/polisiye-anime-izle"       to "Polisiye",
        "${mainUrl}/animeizle/samuray-anime-izle"        to "Samuray",
        "${mainUrl}/animeizle/parodi-anime-izle"         to "Parodi",
        "${mainUrl}/animeizle/seytanlar-anime-izle"      to "Şeytanlar",
        "${mainUrl}/animeizle/savas-sanatlari-anime-izle" to "Savaş Sanatları",
        "${mainUrl}/animeizle/ona-anime-izle"            to "Ona",
        "${mainUrl}/animeizle/kisilik-bolunmesi-anime-izle" to "Kişilik Bölünmesi",
        "${mainUrl}/animeizle/donghua-anime-izle"        to "Donghua",
        "${mainUrl}/animeizle/isekai-anime-izle"         to "Isekai",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // ✅ HTML'den: Sayfalama /sayfa-N formatında
        val url = if (page <= 1) "${request.data}-1" else "${request.data}-$page"
        val document = app.get(url).document

        // ✅ HTML'den: Anime kartları div.flx-block
        val items = document.select("div.flx-block")
        val home = items.mapNotNull { it.toMainPageResult() }

        Log.d("TRANM", "getMainPage(${request.name}, page=$page) -> ${home.size} sonuç | URL: $url")

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // ✅ HTML'den: Link a.news-image veya data-href
        val href = fixUrlNull(
            this.selectFirst("a.news-image")?.attr("href")
                ?: this.attr("data-href")
        ) ?: return null

        // ✅ HTML'den: Başlık div.bar h4 içinde
        val title = this.selectFirst("div.bar h4")?.text()?.trim() ?: return null

        // ✅ HTML'den: Poster img.img-responsive
        val posterUrl = fixUrlNull(this.selectFirst("img.img-responsive")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // ✅ HTML'den: Arama GET /arama/{query}
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val document = app.get("${mainUrl}/arama/$encoded").document

        val items = document.select("div.flx-block")
        Log.d("TRANM", "search($query) -> ${items.size} sonuç")

        return items.mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // ✅ HTML'den: Başlık .playlist-title h1 içinde
        val title = document.selectFirst("div.playlist-title h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null

        // ✅ HTML'den: Poster og:image meta etiketinden
        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
        )

        // ✅ HTML'den: Açıklama meta[name=description] içinde (HTML entity çözümlü)
        val description = document.selectFirst("meta[name='description']")
            ?.attr("content")
            ?.let { org.jsoup.parser.Parser.unescapeEntities(it, true) }
            ?.replace(Regex("<[^>]*>"), "")  // HTML tag'lerini temizle
            ?.trim()

        // ✅ HTML'den: Breadcrumb'dan anime adı ve bölüm linkleri
        // Bölümler sağ sütunda div.animeDetail-items ol li a
        val episodes = document.select("div.animeDetail-items ol li a").mapNotNull { el ->
            val epHref = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val epName = el.selectFirst("div.etitle span")?.text()?.trim() ?: return@mapNotNull null
            val epNum  = Regex("""(\d+)\.\s*Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epSeason = Regex("""(\d+)\.\s*Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name    = epName
                this.season  = epSeason
                this.episode = epNum
            }
        }

        Log.d("TRANM", "load($url) -> ${episodes.size} bölüm")

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TRANM", "loadLinks data » $data")
        val document = app.get(data).document

        // ✅ HTML'den: animeWatch.initialize(animeId, episodeId, fansubId, fansubAd, fansubUrl)
        // Bu JS fonksiyonu /js/site.js içinde tanımlı ve AJAX ile kaynakları yüklüyor.
        // En yaygın endpoint: /Video/GetVideo?animeId=X&episodeId=Y&fansubId=Z
        val scriptText = document.select("script")
            .map { it.data() }
            .firstOrNull { it.contains("animeWatch.initialize") }
            ?: return false

        val match = Regex("""animeWatch\.initialize\((\d+),\s*(\d+),\s*(\d+)""").find(scriptText)
            ?: run {
                Log.e("TRANM", "animeWatch.initialize bulunamadı!")
                return false
            }

        val animeId   = match.groupValues[1]
        val episodeId = match.groupValues[2]
        val fansubId  = match.groupValues[3]

        Log.d("TRANM", "animeId=$animeId episodeId=$episodeId fansubId=$fansubId")

        // Kaynak listesini AJAX ile çek
        val sourcesUrl = "${mainUrl}/Video/GetVideo?animeId=$animeId&episodeId=$episodeId&fansubId=$fansubId"
        val sourcesResponse = app.get(
            sourcesUrl,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer"          to data
            )
        ).text

        Log.d("TRANM", "sourcesResponse length: ${sourcesResponse.length}")
        Log.d("TRANM", "sourcesResponse preview: ${sourcesResponse.take(500)}")

        // Muhtemel yanıt formatları:
        // 1) JSON: {"url":"...","name":"..."}
        // 2) HTML: <a data-url="...">...</a> veya <button>...</button>
        // 3) Doğrudan URL veya iframe src

        // 1) JSON dene
        val jsonRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
        val jsonMatches = jsonRegex.findAll(sourcesResponse).toList()
        if (jsonMatches.isNotEmpty()) {
            for (m in jsonMatches) {
                val videoUrl = m.groupValues[1].replace("\\/", "/")
                Log.d("TRANM", "JSON URL: $videoUrl")
                loadExtractor(videoUrl, "${mainUrl}/", subtitleCallback, callback)
            }
            return true
        }

        // 2) HTML parse et
        val subDoc = org.jsoup.Jsoup.parse(sourcesResponse, sourcesUrl)
        val iframes = subDoc.select("iframe[src]")
        if (iframes.isNotEmpty()) {
            for (iframe in iframes) {
                val src = fixUrlNull(iframe.attr("src")) ?: continue
                Log.d("TRANM", "iframe src: $src")
                loadExtractor(src, "${mainUrl}/", subtitleCallback, callback)
            }
            return true
        }

        // 3) Doğrudan m3u8/url regex
        val urlRegex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""")
        val urlMatches = urlRegex.findAll(sourcesResponse).toList()
        if (urlMatches.isNotEmpty()) {
            for (m in urlMatches) {
                Log.d("TRANM", "Direct URL: ${m.groupValues[1]}")
                loadExtractor(m.groupValues[1], "${mainUrl}/", subtitleCallback, callback)
            }
            return true
        }

        Log.w("TRANM", "Hiçbir video kaynağı çözümlenemedi. Ham yanıt loglara bakın.")
        return false
    }
}
