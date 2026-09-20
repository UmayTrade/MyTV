// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
        val document = app.get("${request.data}?page=$page").document
        val home = document.select("a.group.block").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (href.contains("/sezon-") || href.contains("/bolum-")) return null
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
        val document = app.get("${mainUrl}/ara?q=$query").document
        return document.select("a.group.block").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.aspect-\\[2\\/3\\] img")?.attr("src")
                ?: document.selectFirst("img[alt]")?.attr("src")
        )

        val description = document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: document.selectFirst("p.leading-relaxed")?.text()?.trim()

        // Yıl — JSON-LD'den çek (datePublished)
        val jsonLd = document.select("script[type='application/ld+json']")
            .map { it.data() }.firstOrNull { it.contains("\"Movie\"") }
        val year = Regex(""""datePublished"\s*:\s*"(\d{4})""")
            .find(jsonLd ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = document.select("div.mt-3 a[href*='/tur/']").map { it.text().trim() }

        val rating = document.selectFirst("p.text-star")?.text()?.trim()?.replace(",", ".")

        val actors = document.select("a[href*='/oyuncu/']")
            .map { Actor(it.selectFirst("p")?.text()?.trim() ?: return@map null) }
            .filterNotNull()

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
        }
    }

    /**
     * YENİ OYNATICI YAPISI:
     * - Detay sayfasında <div data-pv="XXX" data-player-type="embed"> var
     * - pilavyerplay.top/assets/js/core.js bu data-pv'yi alıp video kaynağını çözer
     *
     * Bu yüzden core.js'in ne yaptığını taklit etmemiz gerekiyor.
     * core.js muhtemelen şu endpoint'e istek atıyor:
     *   https://pilavyerplay.top/api/source/{data-pv}
     * ya da
     *   https://pilavyerplay.top/embed/{data-pv}
     *
     * Aşağıdaki kod ÖNCE data-pv'yi bulur, sonra birkaç olası endpoint'i dener.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("FLMMD", "loadLinks başladı: $data")
        val document = app.get(data).document

        // 1) data-pv değerini bul
        val playerDiv = document.selectFirst("div[data-pv]")
        val dataPv = playerDiv?.attr("data-pv")?.takeIf { it.isNotBlank() }
        val playerType = playerDiv?.attr("data-player-type") ?: "embed"

        Log.d("FLMMD", "data-pv=$dataPv, playerType=$playerType")

        if (dataPv.isNullOrEmpty()) {
            Log.e("FLMMD", "data-pv bulunamadı!")
            return false
        }

        // 2) pilavyerplay.top üzerinden embed URL'ini oluştur
        val embedBase = "https://pilavyerplay.top"
        val possibleEmbeds = listOf(
            "$embedBase/embed/$dataPv",
            "$embedBase/e/$dataPv",
            "$embedBase/v/$dataPv",
            "$embedBase/$dataPv",
        )

        for (embedUrl in possibleEmbeds) {
            try {
                Log.d("FLMMD", "Deneniyor: $embedUrl")
                val res = app.get(embedUrl, referer = data, allowRedirects = true)
                if (res.code !in 200..299) {
                    Log.d("FLMMD", "HTTP ${res.code} → atlanıyor")
                    continue
                }

                val html = res.text
                Log.d("FLMMD", "Cevap (ilk 300 char): ${html.take(300)}")

                // 3) m3u8 / mp4 linklerini bul
                val m3u8Links = Regex("""(https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*)""")
                    .findAll(html).map { it.groupValues[1] }.toList()

                val mp4Links = Regex("""(https?://[^\s"'<>]+?\.mp4[^\s"'<>]*)""")
                    .findAll(html).map { it.groupValues[1] }.toList()

                val allLinks = (m3u8Links + mp4Links).distinct()

                if (allLinks.isEmpty()) {
                    Log.d("FLMMD", "Bu embed'de link yok, sonraki deneniyor")
                    continue
                }

                allLinks.forEach { link ->
                    val isM3u8 = link.contains(".m3u8")
                    callback.invoke(
                        newExtractorLink(
                            source = "FilmModu",
                            name = "FilmModu",
                            url = link,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = embedUrl
                            this.quality = getQualityFromName(Regex("""(\d{3,4})p""").find(link)?.value)
                        }
                    )
                    Log.d("FLMMD", "Link eklendi: $link")
                }
                return true

            } catch (e: Exception) {
                Log.e("FLMMD", "Hata ($embedUrl): ${e.message}")
            }
        }

        Log.w("FLMMD", "Hiçbir embed'den link alınamadı")
        return false
    }
}
