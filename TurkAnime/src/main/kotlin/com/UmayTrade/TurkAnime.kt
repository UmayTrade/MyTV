package com.UmayTrade

import android.util.Log
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Base64
import com.lagradost.cloudstream3.extractors.helper.AesHelper

class Tranimeizle : MainAPI() {
    override var mainUrl              = "https://www.tranimeizle.io"
    override var name                 = "Tranimeizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    // NOT: Aşağıdaki tür URL'leri hedef sitenin gerçek yol yapısına göre
    // ayarlandı. Site yapısı değişirse güncellenmelidir.
    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aksiyon"        to "Aksiyon",
        "${mainUrl}/tur/arabalar"       to "Arabalar",
        "${mainUrl}/tur/askeri"         to "Askeri",
        "${mainUrl}/tur/avantgarde"     to "Avangard",
        "${mainUrl}/tur/bilim-kurgu"    to "Bilim Kurgu",
        "${mainUrl}/tur/buyu"           to "Büyü",
        "${mainUrl}/tur/cocuklar"       to "Çocuklar",
        "${mainUrl}/tur/dogauustu-gucler" to "Doğaüstü Güçler",
        "${mainUrl}/tur/dovus-sanatlari"  to "Dövüş Sanatları",
        "${mainUrl}/tur/dram"           to "Dram",
        "${mainUrl}/tur/ecchi"          to "Ecchi",
        "${mainUrl}/tur/fantastik"      to "Fantastik",
        "${mainUrl}/tur/gerilim"        to "Gerilim",
        "${mainUrl}/tur/gizem"          to "Gizem",
        "${mainUrl}/tur/harem"          to "Harem",
        "${mainUrl}/tur/josei"          to "Josei",
        "${mainUrl}/tur/komedi"         to "Komedi",
        "${mainUrl}/tur/korku"          to "Korku",
        "${mainUrl}/tur/macera"         to "Macera",
        "${mainUrl}/tur/mecha"          to "Mecha",
        "${mainUrl}/tur/muzik"          to "Müzik",
        "${mainUrl}/tur/okul"           to "Okul",
        "${mainUrl}/tur/oyun"           to "Oyun",
        "${mainUrl}/tur/parodi"         to "Parodi",
        "${mainUrl}/tur/polisiye"       to "Polisiye",
        "${mainUrl}/tur/psikolojik"     to "Psikolojik",
        "${mainUrl}/tur/romantizm"      to "Romantizm",
        "${mainUrl}/tur/samuray"        to "Samuray",
        "${mainUrl}/tur/seinen"         to "Seinen",
        "${mainUrl}/tur/seytanlar"      to "Şeytanlar",
        "${mainUrl}/tur/shoujo"         to "Shoujo",
        "${mainUrl}/tur/shoujo-ai"      to "Shoujo Ai",
        "${mainUrl}/tur/shounen"        to "Shounen",
        "${mainUrl}/tur/shounen-ai"     to "Shounen Ai",
        "${mainUrl}/tur/spor"           to "Spor",
        "${mainUrl}/tur/super-gucler"   to "Süper Güçler",
        "${mainUrl}/tur/tarihi"         to "Tarihi",
        "${mainUrl}/tur/uzay"           to "Uzay",
        "${mainUrl}/tur/vampir"         to "Vampir",
        "${mainUrl}/tur/yaoi"           to "Yaoi",
        "${mainUrl}/tur/yasamdan-kesitler" to "Yaşamdan Kesitler",
        "${mainUrl}/tur/yuri"           to "Yuri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Sayfalama desteği: ?sayfa=N veya /sayfa/N şeklinde denenir.
        val url = if (page <= 1) request.data else "${request.data}?sayfa=$page"
        val document = app.get(url).document

        // ✅ Tranimeizle.io tipik kart yapısı: div.card veya article.anime-card
        // Aşağıda birden fazla olası seçici denenir.
        val items = document.select(
            "div.card, article.card, div.anime-card, div.movie-list div.item, div#content div.card"
        )

        val home = items.mapNotNull { it.toMainPageResult() }

        // Debug: kaç sonuç bulundu?
        Log.d("TRANM", "getMainPage(${request.name}) -> ${home.size} sonuç | URL: $url")

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // Başlık ve link: genellikle <a> içinde başlık metni bulunur.
        val aTag = this.selectFirst("h3 a, h2 a, a.title, a.card-title, a") ?: return null
        val title = aTag.text().trim().ifBlank { return null }
        val href  = fixUrlNull(aTag.attr("href")) ?: return null

        // Poster: data-src, data-original veya src
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")
                ?: img?.attr("data-original")
                ?: img?.attr("src")
        )

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Tranimeizle.io arama yolu genellikle /arama?adi=... veya /search?q=...
        val document = app.get("${mainUrl}/arama", params = mapOf("adi" to query)).document

        val items = document.select(
            "div.card, article.card, div.anime-card, div.movie-list div.item, div#content div.card"
        )

        Log.d("TRANM", "search($query) -> ${items.size} sonuç")

        return items.mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // NOT: Bu seçiciler de hedef siteye göre uyarlanmalıdır.
        val title = document.selectFirst("h1.anime-title, h1.title, div.anime-detail h1, h1")
            ?.text()?.trim() ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.anime-poster img, div.poster img, img.anime-image")
                ?.attr("data-src")
                ?: document.selectFirst("div.anime-poster img, div.poster img")?.attr("src")
        )

        val description = document.selectFirst("div.anime-summary, div.summary, p.description, div.ozet")
            ?.text()?.trim()

        val year = document.selectFirst("a[href*='/yil/'], span.year")
            ?.text()?.trim()?.toIntOrNull()

        val tags = document.select("a[href*='/tur/'], div.genres a, div.anime-genres a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        // Bölümler: Tranimeizle.io genelde /bolum/ veya /episode/ linkleri kullanır.
        val episodes = document.select("div.episodes a, ul.episode-list a, div.bolumler a, a[href*='/bolum-']")
            .mapNotNull { el ->
                val epHref = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                val epName = el.text().trim().ifBlank { return@mapNotNull null }
                val epNum  = Regex("""(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name    = epName
                    this.season  = 1
                    this.episode = epNum
                }
            }

        if (episodes.isEmpty()) {
            Log.w("TRANM", "Bölüm bulunamadı: $url")
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
        }
    }

    // ✅ suspend eklendi (AesHelper.cryptoAESHandler suspend fonksiyon)
    private suspend fun iframe2AesLink(iframe: String): String? {
        var aesData = iframe.substringAfter("embed/#/url/").substringBefore("?status")
        aesData     = String(Base64.decode(aesData, Base64.DEFAULT))

        val aesKey  = "710^8A@3@>T2}#zN5xK?kR7KNKb@-A!LzYL5~M1qU0UfdWsZoBm4UUat%}ueUv6E--*hDPPbH7K2bp9^3o41hw,khL:}Kx8080@M"
        val aesLink = AesHelper.cryptoAESHandler(aesData, aesKey.toByteArray(), false)?.replace("\\", "")
            ?: throw ErrorLoadingException("failed to decrypt")

        return fixUrlNull(aesLink.replace("\"", ""))
    }

    private suspend fun iframe2Load(
        document: Document,
        @Suppress("UNUSED_PARAMETER") iframe: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        for (button in document.select("button[onclick*='ajax/videosec']")) {
            val butonLink = fixUrlNull(
                button.attr("onclick").substringAfter("IndexIcerik('").substringBefore("'")
            ) ?: continue
            val butonName = button.ownText().trim()
            val subDoc = app.get(
                butonLink,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).document

            val subFrame = fixUrlNull(subDoc.selectFirst("iframe")?.attr("src")) ?: continue
            val subLink  = iframe2AesLink(subFrame) ?: continue
            Log.d("TRANM", "$butonName » $subLink")

            loadExtractor(subLink, "${mainUrl}/", subtitleCallback, callback)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TRANM", "data » $data")
        val document = app.get(data).document

        val iframeElement = document.selectFirst("iframe")
        val iframe        = fixUrlNull(iframeElement?.attr("src"))

        if (iframe == null || iframe.contains("a-ads.com")) {
            val buttons = document.select("button[onclick*='IndexIcerik']")

            for (button in buttons) {
                val onclickAttr = button.attr("onclick")
                val subLink = onclickAttr.substringAfter("IndexIcerik('").substringBefore("'")
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) } ?: continue

                Log.d("TRANM", "Extra seçici ile alınan link: $subLink")

                val subResponse = app.get(
                    subLink,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                )
                val subHtml = subResponse.body?.string().orEmpty()

                val subDoc = org.jsoup.Jsoup.parse(subHtml, subLink)

                val dataUrl = subDoc.selectFirst("div.artplayer-app")?.attr("data-url")
                if (!dataUrl.isNullOrBlank() && dataUrl.endsWith(".m3u8")) {
                    Log.d("TRANM", "M3U8 data-url bulundu: $dataUrl")
                    callback(
                        newExtractorLink(
                            name   = "Tranimeizle",
                            source = "Tranimeizle",
                            url    = dataUrl,
                            type   = ExtractorLinkType.M3U8
                        ) {
                            quality = Qualities.Unknown.value
                            headers = mapOf("Referer" to subLink)
                        }
                    )
                    continue
                }

                val subFrame = fixUrlNull(subDoc.selectFirst("iframe")?.attr("src")) ?: continue
                Log.d("TRANM", "subFrame » $subFrame")

                iframe2Load(subDoc, subFrame, subtitleCallback, callback)
            }
        } else {
            iframe2Load(document, iframe, subtitleCallback, callback)
        }

        return true
    }
}
