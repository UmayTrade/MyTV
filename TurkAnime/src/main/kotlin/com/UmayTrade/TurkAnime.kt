
package com.UmayTrade

import java.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class TurkAnime : MainAPI() {
    override var mainUrl = "https://www.turkanime.tv"
    override var name = "TurkAnime"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "${mainUrl}/anime-turu/1/Aksiyon" to "Aksiyon",
        "${mainUrl}/anime-turu/24/Bilim_Kurgu" to "Bilim Kurgu",
        "${mainUrl}/anime-turu/4/Komedi" to "Komedi",
        "${mainUrl}/anime-turu/8/Dram" to "Dram",
        "${mainUrl}/anime-turu/10/Fantastik" to "Fantastik",
        "${mainUrl}/anime-turu/2/Macera" to "Macera",
        "${mainUrl}/anime-turu/27/Shounen" to "Shounen",
        "${mainUrl}/anime-turu/22/Romantizm" to "Romantizm",
        "${mainUrl}/anime-turu/37/Do%C4%9Fa%C3%BCst%C3%BC_G%C3%BC%C3%A7ler" to "Doğaüstü Güçler",
        "${mainUrl}/anime-turu/41/Gerilim" to "Gerilim",
        "${mainUrl}/anime-turu/7/Gizem" to "Gizem",
        "${mainUrl}/anime-turu/30/Spor" to "Spor"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            "${request.data}&page=${page}"
        }

        val document = app.get(targetUrl).document
        val home = document.select("div#orta-icerik div.panel").mapNotNull { toMainPageResult(it) }

        return newHomePageResponse(request.name, home)
    }

    fun toMainPageResult(element: Element): SearchResponse? {
        val titleEl = element.selectFirst("div.panel-title a") ?: return null
        val title = titleEl.text().trim()
        val href = fixUrlNull(titleEl.attr("href")) ?: return null
        val posterEl = element.selectFirst("img[data-src], img[src]")
        val posterUrl = fixUrlNull(
            posterEl?.attr("data-src")?.ifBlank { null }
                ?: posterEl?.attr("src")?.ifBlank { null }
        )

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.post(
            "${mainUrl}/arama",
            data = mapOf("arama" to query),
            headers = mapOf(
                "Referer" to "${mainUrl}/"
            )
        ).document

        val items = document.select("div#orta-icerik div.panel").mapNotNull { toMainPageResult(it) }
        return newSearchResponseList(items, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        return parseLoadMetadata(document, url)
    }

    suspend fun parseLoadMetadata(document: Document, url: String): LoadResponse? {
        val title = document.selectFirst("div#detayPaylas div.panel-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("div#detayPaylas div.imaj img")?.let {
                it.attr("data-src").ifBlank { null } ?: it.attr("src").ifBlank { null }
            }
        )
        val description = document.selectFirst("div#detayPaylas p.ozet")?.text()?.trim()
        val year = document.selectFirst("div#detayPaylas a[href*='yil/']")?.attr("href")?.substringAfter("yil/")?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val tags = document.select("div#animedetay a[href*='anime-turu']").map { it.text().trim() }.filter { it.isNotBlank() }
        val score = document.selectFirst("span.puan")?.text()?.trim()

        val bolumlerUrl = fixUrlNull(document.selectFirst("a[data-url*='ajax/bolumler&animeId=']")?.attr("data-url"))
        val episodes = mutableListOf<Episode>()

        if (bolumlerUrl != null) {
            val token = document.selectFirst("meta[name='_token']")?.attr("content") ?: ""
            val bolumlerDoc = app.get(
                bolumlerUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "token" to token,
                    "Referer" to url
                ),
                cookies = mapOf("yasOnay" to "1")
            ).document

            bolumlerDoc.select("div#bolum-list li").forEach { it ->
                val epLinkEl = it.selectFirst("a[href*='/video/']") ?: return@forEach
                val epHref = fixUrlNull(epLinkEl.attr("href")) ?: return@forEach
                val epName = it.selectFirst("span.bolumAdi")?.text()?.trim() ?: epLinkEl.text().trim()
                val epTitle = epLinkEl.attr("title").trim()
                val epNum = Regex("(\\d+)\\.\\s*Bölüm").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("-?(\\d+)-bolum").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.season = 1
                        this.episode = epNum
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.score = Score.from10(score)
        }
    }

    private data class CryptoJsPayload(
        @JsonProperty("ct") val ct: String? = null,
        @JsonProperty("iv") val iv: String? = null,
        @JsonProperty("s") val s: String? = null
    )

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        var currentHash = ByteArray(0)
        var concatenated = ByteArray(0)
        while (concatenated.size < keyLen + ivLen) {
            md5.reset()
            md5.update(currentHash)
            md5.update(password)
            md5.update(salt)
            currentHash = md5.digest()
            concatenated += currentHash
        }
        val key = concatenated.copyOfRange(0, keyLen)
        val iv = concatenated.copyOfRange(keyLen, keyLen + ivLen)
        return Pair(key, iv)
    }

    private fun iframe2AesLink(iframe: String): String? {
        return try {
            val aesDataRaw = iframe.substringAfter("embed/#/url/").substringBefore("?status")
            val aesJson = String(Base64.getDecoder().decode(aesDataRaw), Charsets.UTF_8)
            val payload = AppUtils.tryParseJson<CryptoJsPayload>(aesJson) ?: return null

            val ct = Base64.getDecoder().decode(payload.ct ?: return null)
            val salt = hexStringToByteArray(payload.s ?: return null)
            val passphrase = "710^8A@3@>T2}#zN5xK?kR7KNKb@-A!LzYL5~M1qU0UfdWsZoBm4UUat%}ueUv6E--*hDPPbH7K2bp9^3o41hw,khL:}Kx8080@M".toByteArray(Charsets.UTF_8)

            val (key, derivedIv) = evpBytesToKey(passphrase, salt, 32, 16)
            val iv = payload.iv?.let { hexStringToByteArray(it) } ?: derivedIv

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val decrypted = String(cipher.doFinal(ct), Charsets.UTF_8).replace("\\", "").replace("\"", "").trim()

            fixUrlNull(decrypted)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveIframesAndExtract(
        doc: Document,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val iframes = doc.select("iframe").mapNotNull { fixUrlNull(it.attr("src")) }
        for (rawFrame in iframes) {
            val frameLink = if (rawFrame.contains("embed/#/url/")) iframe2AesLink(rawFrame) else rawFrame
            if (frameLink != null) {
                try {
                    loadExtractor(frameLink, "${mainUrl}/", subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun processVideosecUrl(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(
                url,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "${mainUrl}/"
                )
            ).document

            // 1. Direct iframes in this response
            resolveIframesAndExtract(doc, subtitleCallback, callback)

            // 2. Secondary/nested buttons (e.g. video hosts under fansub selection)
            val nestedButtons = doc.select("button[onclick*='ajax/videosec']")
            for (nestedBtn in nestedButtons) {
                val onclick = nestedBtn.attr("onclick")
                val subPath = onclick.substringAfter("IndexIcerik('").substringBefore("'")
                val nestedUrl = fixUrlNull(subPath) ?: continue
                if (nestedUrl != url) {
                    try {
                        val hostDoc = app.get(
                            nestedUrl,
                            headers = mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Referer" to "${mainUrl}/"
                            )
                        ).document
                        resolveIframesAndExtract(hostDoc, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            found = true
            callback(link)
        }

        val document = app.get(data).document

        // Direct iframes on page
        resolveIframesAndExtract(document, subtitleCallback, wrappedCallback)

        // Buttons (fansubs or direct video hosts)
        val buttons = document.select("button[onclick*='ajax/videosec']")
        for (button in buttons) {
            val onclick = button.attr("onclick")
            val subPath = onclick.substringAfter("IndexIcerik('").substringBefore("'")
            val buttonLink = fixUrlNull(subPath) ?: continue
            processVideosecUrl(buttonLink, subtitleCallback, wrappedCallback)
        }

        return found
    }
}
