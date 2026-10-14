// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.UmayTrade

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.StringUtils.decodeUri
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

class DiziBox : MainAPI() {
    override var mainUrl              = "https://diziboxizle.com"
    override var name                 = "DiziBox"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    private val baseCookies = mapOf(
        "LockUser"      to "true",
        "isTrustedUser" to "true",
        "dbxu"          to "1743289650198"
    )

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(10 * 1024).string())

            if (response.code == 503 || doc.selectFirst("meta[name='cloudflare']") != null) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/dizi-turu/"                  to "Tüm Diziler",
        "${mainUrl}/tum-bolumler/"               to "Tüm Bölümler",
        "${mainUrl}/tur/filmler/"                to "Tüm Filmler",
        "${mainUrl}/dizi-arsivi/page/SAYFA/"     to "Dizi Arşivi",
        "${mainUrl}/dizi-arsivi/?filtrele=imdb&sirala=DESC&yil=&imdb=7&kelime=" to "IMDb 7+ Diziler",
        "${mainUrl}/tur/aile/page/SAYFA/"        to "Aile",
        "${mainUrl}/tur/aksiyon/page/SAYFA/"     to "Aksiyon",
        "${mainUrl}/tur/animasyon/page/SAYFA/"   to "Animasyon",
        "${mainUrl}/tur/belgesel/page/SAYFA/"    to "Belgesel",
        "${mainUrl}/tur/bilimkurgu/page/SAYFA/"  to "Bilimkurgu",
        "${mainUrl}/tur/biyografi/page/SAYFA/"   to "Biyografi",
        "${mainUrl}/tur/dram/page/SAYFA/"        to "Dram",
        "${mainUrl}/tur/drama/page/SAYFA/"       to "Drama",
        "${mainUrl}/tur/fantastik/page/SAYFA/"   to "Fantastik",
        "${mainUrl}/tur/gerilim/page/SAYFA/"     to "Gerilim",
        "${mainUrl}/tur/gizem/page/SAYFA/"       to "Gizem",
        "${mainUrl}/tur/komedi/page/SAYFA/"      to "Komedi",
        "${mainUrl}/tur/korku/page/SAYFA/"       to "Korku",
        "${mainUrl}/tur/macera/page/SAYFA/"      to "Macera",
        "${mainUrl}/tur/muzik/page/SAYFA/"       to "Müzik",
        "${mainUrl}/tur/muzikal/page/SAYFA/"     to "Müzikal",
        "${mainUrl}/tur/reality-tv/page/SAYFA/"  to "Reality TV",
        "${mainUrl}/tur/romantik/page/SAYFA/"    to "Romantik",
        "${mainUrl}/tur/savas/page/SAYFA/"       to "Savaş",
        "${mainUrl}/tur/spor/page/SAYFA/"        to "Spor",
        "${mainUrl}/tur/suc/page/SAYFA/"         to "Suç",
        "${mainUrl}/tur/tarih/page/SAYFA/"       to "Tarih",
        "${mainUrl}/tur/western/page/SAYFA/"     to "Western",
        "${mainUrl}/tur/yarisma/page/SAYFA/"     to "Yarışma"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("SAYFA")) {
            request.data.replace("SAYFA", "$page")
        } else {
            request.data
        }
        val document = app.get(
            url,
            cookies     = baseCookies,
            interceptor = interceptor,
            cacheTime   = 60
        ).document

        val home = document.select("article.article-series-small-grid").mapNotNull {
            it.toMainPageResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val linkElement = this.selectFirst("a.kart-icerik") ?: return null
        val title = linkElement.selectFirst("div.tv-title")?.text()?.trim()
            ?: linkElement.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("div.figure img")?.let { img ->
                img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src")
            }
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "${mainUrl}/?s=${query}",
            cookies     = baseCookies,
            interceptor = interceptor
        ).document

        return document.select("article.article-series-small-grid, article.detailed-article").mapNotNull {
            it.toMainPageResult()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            cookies     = baseCookies,
            interceptor = interceptor
        ).document

        // Başlık: h1.title-border metninden "Dizisi izle | DiziBOX" kısmını temizle
        val rawTitle = document.selectFirst("h1.title-border")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null
        val title = rawTitle
            .replace(Regex("""\s*Dizisi izle.*$"""), "")
            .replace(Regex("""\s*\|.*$"""), "")
            .trim()

        // Poster: div.category_image img
        val poster = fixUrlNull(
            document.selectFirst("div.category_image img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )

        // Açıklama: div.category_desc
        val description = document.selectFirst("div.category_desc")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()

        // Yıl: div#icerikcat2 içinde "Yapım Yılı" span'ından sonraki text
        val year = document.select("div#icerikcat2 div").firstOrNull {
            it.text().contains("Yapım Yılı")
        }?.text()?.replace(Regex(""".*Yapım Yılı\s*:\s*"""), "")?.trim()?.toIntOrNull()

        // Oyuncular: div#icerikcat2 içinde "Oyuncular" span'ından sonraki text
        val actorsText = document.select("div#icerikcat2 div").firstOrNull {
            it.text().contains("Oyuncular")
        }?.text()?.replace(Regex(""".*Oyuncular\s*:\s*"""), "")?.trim()
        val actors = actorsText?.split(",")?.mapNotNull {
            val name = it.trim()
            if (name.isNotBlank()) Actor(name) else null
        } ?: emptyList()

        // Türler: div.genres a
        val tags = document.select("div.genres a").map { it.text().trim() }

        // Fragman: iframe.trailer-video
        val trailer = document.selectFirst("iframe.trailer-video")?.attr("src")

        // Bölümler: div#scrollbar-container div.container div.bolumust a
        val episodeList = mutableListOf<Episode>()
        document.select("div#scrollbar-container div.container div.bolumust a").forEach { epElem ->
            val epHref = fixUrlNull(epElem.attr("href")) ?: return@forEach
            val epTitle = epElem.selectFirst("div.baslik")?.text()?.trim() ?: return@forEach
            val epSeason = Regex("""(\d+)\. ?Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            episodeList.add(newEpisode(epHref) {
                this.name = epTitle
                this.season = epSeason
                this.episode = epEpisode
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private suspend fun processIframe(
        data: String,
        iframe: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        @Suppress("NAME_SHADOWING") var iframe = iframe

        // OK.RU embed → doğrudan iframe src
        if (iframe.contains("ok.ru/videoembed")) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "OkRu",
                    url = iframe,
                    type = ExtractorLinkType.VIDEO
                ) {
                    headers = mapOf("Referer" to "$mainUrl/")
                    quality = getQualityFromName("1080p")
                }
            )
            return true
        }

        // Moly player
        if (iframe.contains("/player/moly/moly.php")) {
            iframe = iframe.replace("moly.php?h=", "moly.php?wmode=opaque&h=")
            var subDoc = app.get(
                iframe,
                referer     = data,
                cookies     = baseCookies,
                interceptor = interceptor
            ).document

            val atobData = Regex("""unescape\("(.*)"\)""").find(subDoc.html())?.groupValues?.get(1)
            if (atobData != null) {
                val decodedAtob = atobData.decodeUri()
                val strAtob     = String(Base64.decode(decodedAtob, Base64.DEFAULT), Charsets.UTF_8)
                subDoc          = Jsoup.parse(strAtob)
            }

            val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src") ?: return false
            loadExtractor(subFrame, "$mainUrl/", subtitleCallback, callback)
            return true
        }

        // King player
        if (iframe.contains("/player/king/king.php")) {
            iframe = iframe.replace("king.php?v=", "king.php?wmode=opaque&v=")
            val subDoc = app.get(
                iframe,
                referer     = data,
                cookies     = baseCookies,
                interceptor = interceptor
            ).document
            val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src") ?: return false

            val iDoc          = app.get(subFrame, referer = "$mainUrl/").text
            val cryptData     = Regex("""CryptoJS\.AES\.decrypt\("(.*)","""").find(iDoc)?.groupValues?.get(1) ?: return false
            val cryptPass     = Regex("""","(.*)"\);""").find(iDoc)?.groupValues?.get(1) ?: return false
            val decryptedData = CryptoJS.decrypt(cryptPass, cryptData)
            val decryptedDoc  = Jsoup.parse(decryptedData)
            val vidUrl        = Regex("""file: '(.*)',""").find(decryptedDoc.html())?.groupValues?.get(1) ?: return false

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "King",
                    url = vidUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    headers = mapOf("Referer" to vidUrl)
                    quality = getQualityFromName("1080p")
                }
            )
            return true
        }

        // Haydi player
        if (iframe.contains("/player/haydi.php")) {
            iframe = iframe.replace("haydi.php?v=", "haydi.php?wmode=opaque&v=")
            var subDoc = app.get(
                iframe,
                referer     = data,
                cookies     = baseCookies,
                interceptor = interceptor
            ).document

            val atobData = Regex("""unescape\("(.*)"\)""").find(subDoc.html())?.groupValues?.get(1)
            if (atobData != null) {
                val decodedAtob = atobData.decodeUri()
                val strAtob     = String(Base64.decode(decodedAtob, Base64.DEFAULT), Charsets.UTF_8)
                subDoc          = Jsoup.parse(strAtob)
            }

            val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src") ?: return false
            loadExtractor(subFrame, "$mainUrl/", subtitleCallback, callback)
            return true
        }

        // Diğer durumlarda doğrudan iframe'i extractor olarak dene
        loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZBX", "data » $data")
        val document = app.get(
            data,
            cookies     = baseCookies,
            interceptor = interceptor
        ).document

        // Video iframe: div.video-container iframe veya div.dizialani iframe
        var iframe = document.selectFirst("div.video-container iframe")?.attr("src")
            ?: document.selectFirst("div.dizialani iframe")?.attr("src")
            ?: return false
        Log.d("DZBX", "iframe » $iframe")

        processIframe(data, iframe, subtitleCallback, callback)

        // Alternatif kaynak sayfaları: a.post-page-numbers
        document.select("a.post-page-numbers").forEach {
            val altLink = fixUrlNull(it.attr("href")) ?: return@forEach
            val subDoc = app.get(
                altLink,
                cookies     = baseCookies,
                interceptor = interceptor
            ).document
            val subIframe = subDoc.selectFirst("div.video-container iframe")?.attr("src")
                ?: subDoc.selectFirst("div.dizialani iframe")?.attr("src")
                ?: return@forEach
            Log.d("DZBX", "alt iframe » $subIframe")
            processIframe(data, subIframe, subtitleCallback, callback)
        }

        return true
    }
}
