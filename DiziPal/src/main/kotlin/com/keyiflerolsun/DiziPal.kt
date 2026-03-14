// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal2034.com"  // ! Güncel domain
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler/son-bolumler"                          to "Son Bölümler",
        "${mainUrl}/diziler"                                       to "Yeni Diziler",
        "${mainUrl}/filmler"                                       to "Yeni Filmler",
        "${mainUrl}/koleksiyon/netflix"                            to "Netflix",
        "${mainUrl}/koleksiyon/exxen"                              to "Exxen",
        "${mainUrl}/koleksiyon/blutv"                              to "BluTV",
        "${mainUrl}/koleksiyon/disney"                             to "Disney+",
        "${mainUrl}/koleksiyon/amazon-prime"                       to "Amazon Prime",
        "${mainUrl}/koleksiyon/tod-bein"                           to "TOD (beIN)",
        "${mainUrl}/koleksiyon/gain"                               to "Gain",
        "${mainUrl}/tur/mubi"                                      to "Mubi",
        "${mainUrl}/diziler?kelime=&durum=&tur=26&type=&siralama=" to "Anime",
        "${mainUrl}/diziler?kelime=&durum=&tur=5&type=&siralama="  to "Bilimkurgu Dizileri",
        "${mainUrl}/tur/bilimkurgu"                                to "Bilimkurgu Filmleri",
        "${mainUrl}/diziler?kelime=&durum=&tur=11&type=&siralama=" to "Komedi Dizileri",
        "${mainUrl}/tur/komedi"                                    to "Komedi Filmleri",
        "${mainUrl}/diziler?kelime=&durum=&tur=4&type=&siralama="  to "Belgesel Dizileri",
        "${mainUrl}/tur/belgesel"                                  to "Belgesel Filmleri",
        "${mainUrl}/diziler?kelime=&durum=&tur=25&type=&siralama=" to "Erotik Diziler",
        "${mainUrl}/tur/erotik"                                    to "Erotik Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home     = if (request.data.contains("/diziler/son-bolumler")) {
            document.select("div.episode-item").mapNotNull { it.sonBolumler() } 
        } else {
            document.select("article.type2 ul li").mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home, hasNext=false)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name      = this.selectFirst("div.name")?.text() ?: return null
        val episode   = this.selectFirst("div.episode")?.text()?.trim()?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: return null
        val title     = "$name $episode"

        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val puan      = this.selectFirst("span.imdb.lessDot")?.text()?.trim()

        return newTvSeriesSearchResponse(title, href.substringBefore("/sezon"), TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.score     = Score.from10(puan)
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title     = this.selectFirst("span.title")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val puan      = this.selectFirst("span.imdb.lessDot")?.text()?.trim()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.score     = Score.from10(puan)
        }
    }

    private fun SearchItem.toPostSearchResult(): SearchResponse {
        val title     = this.title
        val href      = "${mainUrl}${this.url}"
        val posterUrl = this.poster
        val puan      = this.imdb

        return if (this.type == "series") {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score = Score.from10(puan)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score = Score.from10(puan)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val responseRaw = app.post(
                "${mainUrl}/api/search-autocomplete",
                headers     = mapOf(
                    "Accept"           to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Referer"          to "${mainUrl}/"
                ),
                referer     = "${mainUrl}/",
                data        = mapOf("query" to query)
            )

            if (responseRaw.text.isBlank() || responseRaw.text == "null") {
                Log.w("DZP", "Arama sonucu boş döndü")
                return emptyList()
            }

            val searchItemsMap = jacksonObjectMapper().readValue<Map<String, SearchItem>>(responseRaw.text)

            val searchResponses = mutableListOf<SearchResponse>()

            for ((_, searchItem) in searchItemsMap) {
                searchResponses.add(searchItem.toPostSearchResult())
            }

            return searchResponses
        } catch (e: Exception) {
            Log.e("DZP", "Arama hatası: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = app.get(url).document

            val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
            val year        = document.selectXpath("//div[text()='Yapım Yılı']//following-sibling::div").text().trim().toIntOrNull()
            val description = document.selectFirst("div.summary p")?.text()?.trim()
            val tags        = document.selectXpath("//div[text()='Türler']//following-sibling::div").text().trim().split(" ").map { it.trim() }.filter { it.isNotEmpty() }
            val rating      = document.selectXpath("//div[text()='IMDB Puanı']//following-sibling::div").text().trim()
            val duration    = Regex("(\\d+)").find(document.selectXpath("//div[text()='Ortalama Süre']//following-sibling::div").text())?.value?.toIntOrNull()

            if (url.contains("/dizi/")) {
                val title       = document.selectFirst("div.cover h5")?.text() ?: return null

                val episodes    = document.select("div.episode-item").mapNotNull {
                    val epName    = it.selectFirst("div.name")?.text()?.trim() ?: return@mapNotNull null
                    val epHref    = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                    val epEpisode = it.selectFirst("div.episode")?.text()?.trim()?.split(" ")?.get(2)?.replace(".", "")?.toIntOrNull()
                    val epSeason  = it.selectFirst("div.episode")?.text()?.trim()?.split(" ")?.get(0)?.replace(".", "")?.toIntOrNull()

                    newEpisode(epHref) {
                        this.name    = epName
                        this.episode = epEpisode
                        this.season  = epSeason
                    }
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year      = year
                    this.plot      = description
                    this.tags      = tags
                    this.score     = Score.from10(rating)
                    this.duration  = duration
                }
            } else { 
                val title = document.selectXpath("//div[@class='g-title'][2]/div").text().trim()

                return newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.year      = year
                    this.tags      = tags
                    this.score     = Score.from10(rating)
                    this.duration  = duration
                }
            }
        } catch (e: Exception) {
            Log.e("DZP", "Yükleme hatası: ${e.message}")
            return null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZP", "data » $data")
        
        try {
            val document = app.get(data).document
            
            // ! Güncellenmiş iframe seçicileri - site yapısı değişmiş olabilir
            val iframe = document.selectFirst(".series-player-container iframe")?.attr("src") 
                ?: document.selectFirst("div#vast_new iframe")?.attr("src")
                ?: document.selectFirst("iframe[src*=\"player\"]")?.attr("src")
                ?: document.selectFirst("div.player iframe")?.attr("src")
                ?: return false
                
            Log.d("DZP", "iframe » $iframe")

            val iSource = app.get(iframe, referer="${mainUrl}/").text
            
            // ! Güncellenmiş regex pattern'leri
            val m3uLink = Regex("""file["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(iSource)?.groupValues?.get(1)
                ?: Regex("""file["']?\s*:\s*["']([^"']+)["']""").find(iSource)?.groupValues?.get(1)
                ?: Regex("""src=["']([^"']+\.m3u8[^"']*)["']""").find(iSource)?.groupValues?.get(1)

            if (m3uLink == null) {
                Log.d("DZP", "M3U8 link bulunamadı, extractor deneniyor...")
                Log.d("DZP", "iSource » ${iSource.take(500)}...")
                return loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
            }

            // ! Altyazı parsing - güncellenmiş
            val subtitles = Regex("""subtitle["']?\s*:\s*["']([^"']+)["']""").find(iSource)?.groupValues?.get(1)
                ?: Regex("""tracks.*?file["']?\s*:\s*["']([^"']+)["']""").find(iSource)?.groupValues?.get(1)
            
            Log.d("DZPAL", "Altyazi: ${subtitles}")
            
            if (subtitles != null) {
                if (subtitles.contains(",")) {
                    subtitles.split(",").forEach {
                        val subLang = it.substringAfter("[").substringBefore("]")
                        val subUrl  = it.replace("[${subLang}]", "")

                        subtitleCallback.invoke(
                            SubtitleFile(
                                lang = subLang,
                                url  = fixUrl(subUrl)
                            )
                        )
                    }
                } else {
                    val subLang = subtitles.substringAfter("[").substringBefore("]")
                    val subUrl  = subtitles.replace("[${subLang}]", "")

                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = subLang,
                            url  = fixUrl(subUrl)
                        )
                    )
                }
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3uLink,
                    type = ExtractorLinkType.M3U8
                ) {
                    headers = mapOf("Referer" to "${mainUrl}/")
                    quality = Qualities.Unknown.value
                }
            )

            return true
        } catch (e: Exception) {
            Log.e("DZP", "Link yükleme hatası: ${e.message}")
            return false
        }
    }
}
