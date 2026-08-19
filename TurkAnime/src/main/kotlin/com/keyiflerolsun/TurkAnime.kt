//Bu eklenti @feroxxcs3 için @patr0n tarafından gelistirildi.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AsyaAnimeleri : MainAPI() {
    override var mainUrl              = "https://www.turkanime.tv"
    override var name                 = "TurkAnime"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay        = 250L
    override var sequentialMainPageScrollDelay  = 250L

    override val mainPage = mainPageOf(
        "${mainUrl}/anime-turu/1/Aksiyon"                                   to "Aksiyon",
        "${mainUrl}/anime-turu/3/Arabalar"                                  to "Arabalar",
        "${mainUrl}/anime-turu/38/Askeri"                                   to "Askeri",
        "${mainUrl}/anime-turu/5/Avangard"                                  to "Avangard",
        "${mainUrl}/anime-turu/24/Bilim_Kurgu"                              to "Bilim Kurgu",
        "${mainUrl}/anime-turu/16/B%C3%BCy%C3%BC"                           to "Büyü",
        "${mainUrl}/anime-turu/15/%C3%87ocuklar"                            to "Çocuklar",
        "${mainUrl}/anime-turu/37/Do%C4%9Fa%C3%BCst%C3%BC_G%C3%BC%C3%A7ler" to "Doğaüstü Güçler",
        "${mainUrl}/anime-turu/17/D%C3%B6v%C3%BC%C5%9F_Sanatlar%C4%B1"      to "Dövüş Sanatları",
        "${mainUrl}/anime-turu/8/Dram"                                      to "Dram",
        "${mainUrl}/anime-turu/9/Ecchi"                                     to "Ecchi",
        "${mainUrl}/anime-turu/10/Fantastik"                                to "Fantastik",
        "${mainUrl}/anime-turu/41/Gerilim"                                  to "Gerilim",
        "${mainUrl}/anime-turu/7/Gizem"                                     to "Gizem",
        "${mainUrl}/anime-turu/35/Harem"                                    to "Harem",
        "${mainUrl}/anime-turu/43/Josei"                                    to "Josei",
        "${mainUrl}/anime-turu/4/Komedi"                                    to "Komedi",
        "${mainUrl}/anime-turu/14/Korku"                                    to "Korku",
        "${mainUrl}/anime-turu/2/Macera"                                    to "Macera",
        "${mainUrl}/anime-turu/18/Mecha"                                    to "Mecha",
        "${mainUrl}/anime-turu/19/M%C3%BCzik"                               to "Müzik",
        "${mainUrl}/anime-turu/23/Okul"                                     to "Okul",
        "${mainUrl}/anime-turu/11/Oyun"                                     to "Oyun",
        "${mainUrl}/anime-turu/20/Parodi"                                   to "Parodi",
        "${mainUrl}/anime-turu/39/Polisiye"                                 to "Polisiye",
        "${mainUrl}/anime-turu/40/Psikolojik"                               to "Psikolojik",
        "${mainUrl}/anime-turu/22/Romantizm"                                to "Romantizm",
        "${mainUrl}/anime-turu/21/Samuray"                                  to "Samuray",
        "${mainUrl}/anime-turu/42/Seinen"                                   to "Seinen",
        "${mainUrl}/anime-turu/6/%C5%9Eeytanlar"                            to "Şeytanlar",
        "${mainUrl}/anime-turu/25/Shoujo"                                   to "Shoujo",
        "${mainUrl}/anime-turu/26/Shoujo_Ai"                                to "Shoujo Ai",
        "${mainUrl}/anime-turu/27/Shounen"                                  to "Shounen",
        "${mainUrl}/anime-turu/28/Shounen_Ai"                               to "Shounen Ai",
        "${mainUrl}/anime-turu/30/Spor"                                     to "Spor",
        "${mainUrl}/anime-turu/31/S%C3%BCper_G%C3%BC%C3%A7ler"              to "Süper Güçler",
        "${mainUrl}/anime-turu/13/Tarihi"                                   to "Tarihi",
        "${mainUrl}/anime-turu/29/Uzay"                                     to "Uzay",
        "${mainUrl}/anime-turu/32/Vampir"                                   to "Vampir",
        "${mainUrl}/anime-turu/33/Yaoi"                                     to "Yaoi",
        "${mainUrl}/anime-turu/36/Ya%C5%9Famdan_Kesitler"                   to "Yaşamdan Kesitler",
        "${mainUrl}/anime-turu/34/Yuri"                                     to "Yuri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/${page}/" else request.data
        val document = app.get(url).document

        val home = if (request.data == "${mainUrl}/") {
            
            document.select("div.listupd div.utimes").mapNotNull { it.toLatestResult() }
        } else {
            
            document.select("div.listupd article div.bsx").mapNotNull { it.toSearchResult() }
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toLatestResult(): SearchResponse? {
        val anchor    = this.selectFirst("a") ?: return null
        val title     = anchor.selectFirst("div.tt")?.text()?.trim() ?: return null
        val href      = fixUrlNull(anchor.attr("href")) ?: return null
        val posterUrl = fixUrlNull(anchor.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor    = this.selectFirst("a") ?: return null
        val title     = anchor.attr("title").ifEmpty {
            this.selectFirst("div.tt")?.text()?.trim()
        } ?: return null
        val href      = fixUrlNull(anchor.attr("href")) ?: return null
        val posterUrl = fixUrlNull(anchor.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } })

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("div.listupd article div.bsx").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(
            document.selectFirst("div.thumb img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        )
        val description = document.selectFirst("div.entry-content p")?.text()?.trim()
        val tags        = document.select("div.genxed a").map { it.text().trim() }

        val episodes = mutableListOf<Episode>()

        document.select("div.eplister ul li").forEach { epItem ->
            val epAnchor  = epItem.selectFirst("a") ?: return@forEach
            val epHref    = fixUrlNull(epAnchor.attr("href")) ?: return@forEach
            val epName    = epAnchor.selectFirst("div.epl-title")?.text()?.trim()
                ?: epAnchor.text().trim()
            val epNumText = epAnchor.selectFirst("div.epl-num")?.text()?.trim()
            val epNum     = epNumText?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

            episodes.add(newEpisode(epHref) {
                this.name    = epName
                this.episode = epNum
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.tags      = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("AAE", "data » $data")
        val document = app.get(data).document

        
        val mirrorOptions = document.select("select.mirror option")
        Log.d("AAE", "Mirror sayısı: ${mirrorOptions.size}")

        for (option in mirrorOptions) {
            val base64Value = option.attr("value")
            if (base64Value.isBlank()) continue

            try {
               
                val decodedHtml = String(Base64.decode(base64Value, Base64.DEFAULT))
                Log.d("AAE", "Decoded HTML » $decodedHtml")

                
                val iframeSrc = Regex("""src="([^"]+)"""").find(decodedHtml)?.groupValues?.get(1)
                if (iframeSrc.isNullOrBlank()) continue
                Log.d("AAE", "iframe src » $iframeSrc")

                loadExtractor(iframeSrc, "${mainUrl}/", subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("AAE", "Mirror çözme hatası: ${e.message}")
            }
        }

        
        document.select("div#pembed iframe, div.player-embed iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                Log.d("AAE", "Mevcut iframe » $iframeSrc")
                loadExtractor(iframeSrc, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }
}
