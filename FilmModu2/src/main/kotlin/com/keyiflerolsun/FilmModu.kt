// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class FilmModu : MainAPI() {
    override var mainUrl = "https://www.filmmodu.live"
    override var name = "FilmModu2"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler" to "Filmler",
        "${mainUrl}/diziler" to "Diziler",
        "${mainUrl}/animes" to "Animeler",
        "${mainUrl}/tur/aksiyon" to "Aksiyon",
        "${mainUrl}/tur/animasyon" to "Animasyon",
        "${mainUrl}/tur/belgesel" to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu" to "Bilim-Kurgu",
        "${mainUrl}/tur/dram" to "Dram",
        "${mainUrl}/tur/fantastik" to "Fantastik",
        "${mainUrl}/tur/gerilim" to "Gerilim",
        "${mainUrl}/tur/gizem" to "Gizem",
        "${mainUrl}/tur/komedi" to "Komedi",
        "${mainUrl}/tur/korku" to "Korku",
        "${mainUrl}/tur/macera" to "Macera",
        "${mainUrl}/tur/romantik" to "Romantik",
        "${mainUrl}/tur/savas" to "Savaş",
        "${mainUrl}/tur/suc" to "Suç",
        "${mainUrl}/tur/tarih" to "Tarih",
        "${mainUrl}/platform/netflix" to "Netflix",
        "${mainUrl}/platform/amazon-prime" to "Amazon Prime",
        "${mainUrl}/platform/disney" to "Disney+",
        "${mainUrl}/platform/hbo-max" to "HBO Max",
        "${mainUrl}/platform/apple-tv" to "Apple TV+",
        "${mainUrl}/platform/blutv" to "BluTV",
        "${mainUrl}/platform/exxen" to "Exxen",
        "${mainUrl}/platform/tv-plus" to "TV+",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}?page=${page}").document
        val home = document.select("a.group.block").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        // Bölüm linklerini atla
        if (href.contains("/sezon-") || href.contains("/bolum-")) return null
        // Reklam / harici linkleri atla
        if (!href.startsWith(mainUrl)) return null

        val title = this.selectFirst("h3")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val score = this.selectFirst("span.badge-rating")?.text()?.trim()

        val type = when {
            href.contains("/dizi/") -> TvType.TvSeries
            href.contains("/anime/") -> TvType.Anime
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.score = Score.from10(score)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/ara?q=${query}").document
        return document.select("a.group.block").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Başlık
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("div.titles h1")?.text()?.trim()
            ?: return null

        // Poster
        val poster = fixUrlNull(
            document.selectFirst("img[alt*='izle']")?.attr("src")
                ?: document.selectFirst("div.poster img")?.attr("src")
                ?: document.selectFirst("img.img-responsive")?.attr("src")
        )

        // Açıklama
        val description = document.selectFirst("p[itemprop='description']")?.text()?.trim()
            ?: document.selectFirst("div.description p")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()

        // Yıl
        val year = document.selectFirst("span[itemprop='dateCreated']")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""")
                .find(document.selectFirst("div.description")?.text() ?: "")
                ?.value?.toIntOrNull()

        // Türler
        val tags = document.select("a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // Puan
        val rating = document.selectFirst("span.badge-rating")?.text()?.trim()
            ?: document.selectFirst("div.description p")?.ownText()?.split(" ")?.last()?.trim()

        // Oyuncular
        val actors = document.select("a[href*='-oyuncu-']")
            .map { Actor(it.text().trim()) }

        // Fragman
        val trailer = document.selectFirst("div.container iframe")?.attr("src")

        val type = when {
            url.contains("/dizi/") -> TvType.TvSeries
            url.contains("/anime/") -> TvType.Anime
            else -> TvType.Movie
        }

        return newMovieLoadResponse(title, url, type, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.score = Score.from10(rating)
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("FLMMD", "Başlatılıyor - loadLinks için data: $data")
        val document = app.get(data).document

        // Yeni site yapısı: alternatif kaynak linkleri
        // (div.alternates a -> yeni yapıda da benzer olabilir, birden fazla selector denenir)
        val alternates = document.select("div.alternates a").ifEmpty {
            document.select("a[data-source], a.source-link, div.sources a")
        }

        if (alternates.isEmpty()) {
            Log.w("FLMMD", "Alternatif bağlantılar bulunamadı! 'div.alternates a' boş.")
            return false
        }

        alternates.forEach { altLinkElement ->
            val altLink = fixUrlNull(altLinkElement.attr("href"))
            val altName = altLinkElement.text()

            if (altLink == null || altName.contains("Fragman", true)) {
                Log.d("FLMMD", "Fragman linki veya geçersiz link. Atlanıyor. Link: $altLink, Name: $altName")
                return@forEach
            }

            Log.d("FLMMD", "Alternatif link bulundu: $altName, URL: $altLink")

            try {
                val altReq = app.get(altLink, referer = data)
                val altText = altReq.text

                val vidId = Regex("""var videoId = '(\d+)';""").find(altText)?.groupValues?.getOrNull(1)
                val vidType = Regex("""var videoType = '(\w+)';""").find(altText)?.groupValues?.getOrNull(1)

                if (vidId.isNullOrEmpty() || vidType.isNullOrEmpty()) {
                    Log.e("FLMMD", "videoId ($vidId) veya videoType ($vidType) bulunamadı. İlk 500 char: ${altText.take(500)}")
                    return@forEach
                }

                Log.d("FLMMD", "Çekilen videoId: $vidId, videoType: $vidType")

                val sourceUrl = "${mainUrl}/get-source?movie_id=${vidId}&type=${vidType}"
                Log.d("FLMMD", "get-source isteği atılıyor: $sourceUrl")

                val vidReqRaw = app.get(sourceUrl, referer = altLink)

                if (vidReqRaw.code != 200) {
                    Log.e("FLMMD", "get-source HTTP hata kodu: ${vidReqRaw.code}. Yanıt: ${vidReqRaw.text}")
                    return@forEach
                }

                Log.d("FLMMD", "get-source ham cevap (ilk 500 char): ${vidReqRaw.text.take(500)}")

                val vidReq = vidReqRaw.parsedSafe<GetSource>()

                if (vidReq == null) {
                    Log.e("FLMMD", "GetSource objesi null döndü. JSON ayrıştırma başarısız.")
                    return@forEach
                }

                vidReq.subtitle?.let { subPath ->
                    val fullSubUrl = fixUrl("${mainUrl}${subPath}")
                    subtitleCallback(SubtitleFile(altName, fullSubUrl))
                    Log.d("FLMMD", "Altyazı bulundu: $fullSubUrl")
                } ?: Log.d("FLMMD", "Altyazı bulunamadı.")

                vidReq.sources?.forEach { source ->
                    callback.invoke(
                        newExtractorLink(
                            source = source.src,
                            name = "FilmModu - $altName",
                            url = fixUrl(source.src),
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = altLink
                            this.quality = getQualityFromName(source.label)
                        }
                    )
                    Log.d("FLMMD", "Video kaynağı eklendi: $altName, URL: ${source.src}, Label: ${source.label}")
                } ?: Log.w("FLMMD", "Video kaynakları (sources) boş veya null.")

            } catch (e: Exception) {
                Log.e("FLMMD", "Alternatif link işleme hatası: $altLink, Hata: ${e.message}", e)
            }
        }
        Log.d("FLMMD", "loadLinks fonksiyonu tamamlandı.")
        return true
    }
}