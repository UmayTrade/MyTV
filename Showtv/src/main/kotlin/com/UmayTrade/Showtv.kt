// ! Bu araç @SAKLImavi tarafından | @UmayTrade için yazılmıştır. (Show TV için uyarlanmıştır)

package com.UmayTrade

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.util.Locale

class StarTv : MainAPI() {
    override var mainUrl = "https://www.showtv.com.tr"
    override var name = "Show TV"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live, TvType.AsianDrama)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/programlar" to "Programlar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()
        try {
            val listDoc = app.get(request.data, headers = headers).document
            // Ana sayfadaki kartları ve menülerdeki linkleri alır.
            listDoc.select("a[href*='/dizi/'], a[href*='/programlar/']").forEach { element ->
                element.toSearchResult()?.let { results.add(it) }
            }
            Log.d(name, "Ana sayfa için ${results.size} öğe alındı: ${request.name}")
        } catch (e: Exception) {
            Log.e(name, "Ana sayfa çekme hatası: ${e.message}")
        }
        val uniqueResults = results.distinctBy { it.url }
        return newHomePageResponse(
            listOf(HomePageList(request.name, uniqueResults)),
            hasNext = false
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val hrefRaw = this.attr("href")
        if (hrefRaw.isBlank()) return null

        val fullUrl = fixUrlNull(hrefRaw) ?: return null
        val path = fullUrl.removePrefix(mainUrl).trim('/')

        // URL yapısını kontrol et: dizi/tanitim/slug veya programlar/tanitim/slug gibi.
        if (path.split("/").size < 3) return null

        // Resim şart (kart olduğunu doğrular)
        val img = this.selectFirst("img") ?: return null

        // Başlık
        val title = this.selectFirst("figcaption span, h2, h3, .title")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: img.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        // Poster
        val poster = fixUrlNull(
            img.attr("data-src").ifEmpty { img.attr("src") }
        )

        return newMovieSearchResponse(title, fullUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        // Arama için mevcut olan arama sayfasını kullan (örnek: /arama?ara=query)
        val searchUrl = "$mainUrl/arama?ara=${query.replace(" ", "+")}"
        val results = mutableListOf<SearchResponse>()
        try {
            val doc = app.get(searchUrl, headers = headers).document
            doc.select("a[href*='/dizi/'], a[href*='/programlar/']").forEach { element ->
                element.toSearchResult()?.let { results.add(it) }
            }
        } catch (e: Exception) {
            Log.e(name, "Arama hatası: ${e.message}")
        }
        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        // Sayfa başlığını al (örn: "Muhtemel Aşk 13. Bölüm" veya "Cumartesi Sürprizi | Show TV")
        val rawTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        // Başlığı temizle (| Show TV kısmını kaldır)
        val title = rawTitle.substringBefore("|").trim()

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        // Eğer URL bir bölüm sayfasıysa (tum_bolumler veya videolar içeriyorsa) tek bölümlük bir dizi gibi işle.
        if (url.contains("/tum_bolumler/") || url.contains("/videolar/")) {
            val episode = newEpisode(url) {
                this.name = title
                this.episode = 1
                this.posterUrl = poster
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOfNotNull(episode)) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // Aksi takdirde, bu bir dizi/program detay sayfasıdır. Bölümleri çek.
        val episodes = getEpisodes(url, document)
        if (episodes.isEmpty()) {
            Log.d(name, "Bölüm bulunamadı, sayfa tek bir video olabilir: $url")
            // Ana sayfadaki slider gibi tek video içeren sayfaları da işle
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(newEpisode(url) { this.name = title; this.episode = 1 })) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private suspend fun getEpisodes(url: String, document: org.jsoup.nodes.Document): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()
        try {
            // Bölümler sayfasının linkini bul (BÖLÜMLER sekmesi)
            val episodePageLink = document.selectFirst("nav a[title='BÖLÜMLER']")?.attr("href")

            if (episodePageLink == null) {
                Log.e(name, "Bölümler sayfası linki bulunamadı.")
                return emptyList()
            }

            val episodePageUrl = fixUrl(episodePageLink)
            Log.d(name, "Bölüm listesi sayfası: $episodePageUrl")

            val episodeDoc = app.get(episodePageUrl, headers = headers).document

            // Bölüm listesini seç (genellikle section#default-season içindeki li.iterate elemanları)
            episodeDoc.select("section#default-season ul > li.iterate").forEach { element ->
                val episodeLinkElement = element.selectFirst("a[data-ajax-link]") ?: return@forEach
                val episodeUrl = fixUrl(episodeLinkElement.attr("href"))
                val episodeName = episodeLinkElement.attr("title").trim()
                    .ifEmpty { "Bölüm" }

                // Bölüm numarasını başlıktan veya URL'den çıkar
                val episodeNum = Regex("(\\d+)\\.\\s*Bölüm").find(episodeName)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(episodeUrl) {
                    this.name = episodeName
                    this.episode = episodeNum
                    this.posterUrl = fixUrlNull(episodeLinkElement.selectFirst("img")?.attr("data-src"))
                }?.let { allEpisodes.add(it) }
            }

            // Bölüm numarasına göre tersten sırala (en yeni bölüm en üstte)
            return allEpisodes.distinctBy { it.data }.sortedByDescending { it.episode ?: 0 }

        } catch (e: Exception) {
            Log.e(name, "Bölüm çekme hatası: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "Video linki çekiliyor: $data")
        try {
            val document = app.get(data, headers = headers).document
            var found = false

            // Öncelik 1: JSON-LD içindeki VideoObject'ten contentUrl'i al
            val ldJsonScripts = document.select("script[type=application/ld+json]")
            for (script in ldJsonScripts) {
                val content = script.data()
                if (content.contains("\"@type\":\"VideoObject\"")) {
                    try {
                        val json = JSONObject(content)
                        val videoUrl = json.optString("contentUrl", "")
                        if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                            // Genellikle .mp4 linki verir, biz .m3u8'e çevirelim
                            val finalUrl = videoUrl.replace(".mp4", ".m3u8")
                            Log.d(name, "JSON-LD'den m3u8 linki bulundu: $finalUrl")
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = finalUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                            break // Bir tane bulunca yeterli
                        }
                    } catch (e: Exception) {
                        Log.e(name, "JSON-LD parse hatası: ${e.message}")
                    }
                }
            }

            // Öncelik 2: Eğer JSON-LD'de bulunamazsa, iframe içindeki embed'i dene
            if (!found) {
                Log.d(name, "JSON-LD'de bulunamadı, iframe deneniyor...")
                val iframe = document.selectFirst("iframe[src]")
                if (iframe != null) {
                    val embedUrl = fixUrl(iframe.attr("src"))
                    Log.d(name, "iframe bulundu: $embedUrl")
                    if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }

            return found
        } catch (e: Exception) {
            Log.e(name, "loadLinks hatası: ${e.message}")
            return false
        }
    }
}