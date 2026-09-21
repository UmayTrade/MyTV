package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

typealias SetFilmIzleProvider = SetFilmIzle

class SetFilmIzle : MainAPI() {
    override var mainUrl: String
        get() = "https://www.setfilmizle.ltd"
        set(_) {}
    override var name = "SetFilmİzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "SetFilmIzle"

    private fun log(msg: String) = Log.d(TAG, msg)
    private fun logErr(msg: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
    }

    /**
     * Base64 decode öncesi string'i temizler.
     * - \/ → / (JS escape)
     * - \\ → silinir
     * - \ → silinir
     * - Tüm whitespace ve quote'ları atar
     * - Sadece geçerli Base64 karakterlerini bırakır
     */
    private fun cleanBase64(s: String): String {
        return s.replace("\\/", "/")
            .replace("\\\\", "")
            .replace("\\", "")
            .replace("\"", "")
            .replace("'", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace("\t", "")
            .replace(" ", "")
            .filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/film/" to "Filmler",
        "$mainUrl/dizi/" to "Diziler",
        "$mainUrl/trend/" to "Trendler",
        "$mainUrl/ag/netflix/" to "Netflix",
        "$mainUrl/ag/apple-tv/" to "Apple TV+",
        "$mainUrl/ag/prime-video/" to "Prime Video",
        "$mainUrl/turkce-dublaj-filmler/" to "Türkçe Dublaj",
        "$mainUrl/tur/aksiyon/" to "Aksiyon",
        "$mainUrl/tur/bilim-kurgu/" to "Bilim-Kurgu",
        "$mainUrl/tur/komedi/" to "Komedi",
        "$mainUrl/tur/korku/" to "Korku"
    )

    // ---------------------------------------------------------------
    // Header yardımcıları
    // ---------------------------------------------------------------
    private fun baseHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "Referer" to referer,
        "User-Agent" to CommonHeaders.DEFAULT_USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    private fun ajaxHeaders(referer: String): Map<String, String> = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to referer,
        "User-Agent" to CommonHeaders.systemUserAgent,
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )

    // ---------------------------------------------------------------
    // Ana sayfa
    // ---------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data.trimEnd('/')}/page/$page/" else request.data
        log("getMainPage → url=$url")

        val document = try {
            app.get(url, headers = baseHeaders()).document
        } catch (e: Exception) {
            logErr("getMainPage HTTP hatası: $url", e)
            return newHomePageResponse(request.name, emptyList())
        }

        val home = document.select(
            "a.card-link, a:has(article.card), article.card, article.item.movies, article.item, div.items article"
        )
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        log("getMainPage ← ${home.size} sonuç bulundu (${request.name})")
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Daha esnek href bulma
        val href = fixUrlNull(
            when {
                this.tagName() == "a" -> this.attr("href")
                else -> this.selectFirst("a[href]")?.attr("href")
                    ?: this.attr("data-href")
                    ?: this.selectFirst("[data-href]")?.attr("data-href")
                    ?: this.selectFirst("[data-url]")?.attr("data-url")
                    ?: this.selectFirst("a")?.attr("href")
            }
        ) ?: run {
            // DEBUG: article yapısını logla (ilk 500 char)
            logErr("toSearchResult: href YOK. HTML: ${this.outerHtml().take(500)}")
            return null
        }

        val title = this.selectFirst("h2.card-ad, h2, h3, a")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: this.attr("data-title")?.trim()
            ?: run {
                logErr("toSearchResult: title YOK, href=$href")
                return null
            }

        val img = this.selectFirst("img")
        val rawPoster = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img?.attr("data-srcset")?.split(" ")?.firstOrNull { it.startsWith("http") }
            ?: img?.attr("srcset")?.split(" ")?.firstOrNull { it.startsWith("http") }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

        val posterUrl = fixUrlNull(rawPoster)
        val pHeaders = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to CommonHeaders.DEFAULT_USER_AGENT
        )

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.posterHeaders = pHeaders
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.posterHeaders = pHeaders
            }
        }
    }

    // ---------------------------------------------------------------
    // Arama
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        log("=== search başladı → query='$query' ===")

        val mainPage = runCatching {
            app.get(mainUrl, headers = baseHeaders()).document
        }.onFailure {
            logErr("search: ana sayfa çekilemedi", it)
        }.getOrNull()

        val nonce = mainPage?.let {
            Regex("""nonce:\s*'([^']+)'""").find(it.html())?.groupValues?.get(1)
        } ?: ""

        log("search: nonce='$nonce' (length=${nonce.length})")

        val response = runCatching {
            app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = ajaxHeaders("$mainUrl/"),
                data = mapOf(
                    "action" to "ajax_search",
                    "nonce" to nonce,
                    "search" to query
                )
            )
        }.onFailure {
            logErr("search: admin-ajax POST hatası", it)
        }.getOrNull()

        log("search: response code=${response?.code}, body length=${response?.text?.length ?: 0}")

        val results = mutableListOf<SearchResponse>()

        if (response != null && response.text.isNotBlank()) {
            val htmlContent = runCatching {
                JSONObject(response.text).optString("html")
            }.onFailure {
                log("search: JSON parse edilemedi, raw text kullanılıyor")
            }.getOrDefault(response.text)

            log("search: htmlContent length=${htmlContent.length}")
            logErr("search: HTML PREVIEW = ${htmlContent.take(1000)}")

            if (htmlContent.isNotBlank()) {
                val doc = Jsoup.parse(htmlContent)
                val articles = doc.select("article")
                log("search: ${articles.size} article bulundu")

                var errCount = 0
                articles.forEach { art ->
                    val sr = art.toSearchResult()
                    if (sr != null) {
                        results.add(sr)
                    } else {
                        errCount++
                    }
                }
                log("search: ${results.size} başarılı, $errCount başarısız article")
            }
        }

        if (results.isEmpty()) {
            log("search: AJAX boş döndü, fallback deneniyor...")
            val fallbackDoc = runCatching {
                app.get("$mainUrl/?s=$query", headers = baseHeaders()).document
            }.onFailure {
                logErr("search: fallback GET hatası", it)
            }.getOrNull()

            fallbackDoc?.select(
                "a.card-link, a:has(article.card), article.card, article.item.movies, article.item, div.items article"
            )
                ?.mapNotNull { it.toSearchResult() }
                ?.distinctBy { it.url }
                ?.let {
                    log("search: fallback ${it.size} sonuç döndü")
                    results.addAll(it)
                }
        }

        log("=== search bitti → toplam ${results.size} sonuç ===")
        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---------------------------------------------------------------
    // Load
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        log("=== load başladı → url=$url ===")

        val document = runCatching {
            app.get(url, headers = baseHeaders()).document
        }.onFailure {
            logErr("load: HTTP hatası", it)
        }.getOrNull() ?: run {
            logErr("load: document null döndü")
            return null
        }

        val title = document.selectFirst("h1")?.text()?.replace(" izle", "")?.trim()
        log("load: title='$title'")
        if (title.isNullOrBlank()) {
            logErr("load: title bulunamadı, iptal")
            return null
        }

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.poster img")?.attr("src")
                ?: document.selectFirst("img")?.attr("data-src")
        )
        log("load: poster=$poster")

        val plot = document.selectFirst(
            "div.wp-content p, div#info p, meta[property='og:description']"
        )?.text()?.trim()
        log("load: plot length=${plot?.length ?: 0}")

        val year = document.selectFirst("div.extra span.C a, span.C a, div.extra span a")
            ?.text()?.trim()?.toIntOrNull()
            ?: Regex("""(\d{4})""").find(document.html())?.groupValues?.get(1)?.toIntOrNull()
        log("load: year=$year")

        val rating = document.selectFirst("span.dt_rating_vgs, span.rating")?.text()?.trim()
        log("load: rating='$rating'")

        val isSeries = url.contains("/dizi/") || document.select("div#episodes").isNotEmpty()
        log("load: isSeries=$isSeries")

        val postId = document.selectFirst("#stfPlayer[data-post-id]")?.attr("data-post-id")
            ?: document.selectFirst("[data-post-id]")?.attr("data-post-id")
        log("load: postId='$postId'")

        val nonce = Regex("""video:\s*"([^"]+)"""").find(document.html())?.groupValues?.get(1) ?: ""
        log("load: nonce='$nonce' (length=${nonce.length})")

        val firstTab = document.selectFirst(".src-tab.selected") ?: document.selectFirst(".src-tab")
        val playerName = firstTab?.attr("data-player-name") ?: "SetPlay"
        val partKey = firstTab?.attr("data-part-key") ?: ""
        log("load: playerName='$playerName', partKey='$partKey'")
        log("load: toplam .src-tab sayısı=${document.select(".src-tab").size}")

        val linkData = if (!postId.isNullOrBlank()) {
            "$postId|$url|$nonce|$playerName|$partKey"
        } else {
            url
        }
        log("load: linkData=$linkData")

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()

            val epSelectors = listOf(
                "div#episodes ul.episodios li",
                "ul.episodios li",
                "div#episodes li"
            )
            var foundEps = 0
            for (sel in epSelectors) {
                val items = document.select(sel)
                log("load: selector '$sel' → ${items.size} eleman")
                if (items.isNotEmpty()) {
                    items.forEach { epLi ->
                        val epHref = fixUrlNull(epLi.selectFirst("a")?.attr("href")) ?: return@forEach
                        val epTitle = epLi.selectFirst("h4.episodiotitle a, a")?.text()?.trim()

                        val seMatch = Regex("""(\d+)x(\d+)""").find(epLi.text())
                            ?: Regex("""(\d+)\.\s*Sezon\s*(\d+)\.\s*Bölüm""").find(epLi.text())

                        val sNum = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                        val eNum = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

                        episodes.add(
                            newEpisode(epHref) {
                                this.name = epTitle
                                this.season = sNum
                                this.episode = eNum
                            }
                        )
                        foundEps++
                    }
                    if (foundEps > 0) break
                }
            }
            log("load: toplam ${episodes.size} bölüm eklendi")

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = Score.from10(rating)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, linkData) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = Score.from10(rating)
            }
        }
    }

    // ---------------------------------------------------------------
    // LoadLinks
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        log("╔══════════════════════════════════════╗")
        log("║  loadLinks BAŞLADI                    ║")
        log("╚══════════════════════════════════════╝")
        log("loadLinks: data=$data")
        log("loadLinks: isCasting=$isCasting")

        var processed = false

        val tokens = data.split("|")
        var postId = tokens.getOrNull(0)
        val pageUrl = tokens.getOrNull(1) ?: data
        var nonce = tokens.getOrNull(2) ?: ""
        var playerName = tokens.getOrNull(3) ?: "SetPlay"
        var partKey = tokens.getOrNull(4) ?: ""

        log("loadLinks: tokens.size=${tokens.size}")
        log("loadLinks: postId='$postId'")
        log("loadLinks: pageUrl='$pageUrl'")
        log("loadLinks: nonce length=${nonce.length}")
        log("loadLinks: playerName='$playerName'")
        log("loadLinks: partKey='$partKey'")

        // Eksik veri varsa sayfayı yeniden çek
        if ((nonce.isBlank() || postId.isNullOrBlank()) && pageUrl.startsWith("http")) {
            log("loadLinks: eksik veri → sayfa yeniden çekiliyor")

            val pageDoc = runCatching {
                app.get(pageUrl, headers = baseHeaders()).document
            }.onFailure {
                logErr("loadLinks: sayfa çekilemedi", it)
            }.getOrNull()

            if (pageDoc != null) {
                if (postId.isNullOrBlank()) {
                    postId = pageDoc.selectFirst("#stfPlayer[data-post-id]")?.attr("data-post-id")
                        ?: pageDoc.selectFirst("[data-post-id]")?.attr("data-post-id")
                    log("loadLinks: postId (yeniden)='$postId'")
                }
                if (nonce.isBlank()) {
                    nonce = Regex("""video:\s*"([^"]+)"""").find(pageDoc.html())?.groupValues?.get(1) ?: ""
                    log("loadLinks: nonce (yeniden) length=${nonce.length}")
                }
                val tab = pageDoc.selectFirst(".src-tab.selected") ?: pageDoc.selectFirst(".src-tab")
                if (playerName.isBlank()) playerName = tab?.attr("data-player-name") ?: "SetPlay"
                if (partKey.isBlank()) partKey = tab?.attr("data-part-key") ?: ""
                log("loadLinks: playerName='$playerName', partKey='$partKey'")
            } else {
                logErr("loadLinks: pageDoc null, çıkılıyor")
            }
        }

        if (!postId.isNullOrBlank() && nonce.isNotBlank()) {
            log("loadLinks: ADIM 1 → admin-ajax get_video_url çağrısı")

            val response = runCatching {
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    headers = ajaxHeaders("$pageUrl/"),
                    data = mapOf(
                        "action" to "get_video_url",
                        "nonce" to nonce,
                        "post_id" to postId,
                        "player_name" to playerName,
                        "part_key" to partKey
                    )
                )
            }.onFailure {
                logErr("loadLinks: admin-ajax POST hatası", it)
            }.getOrNull()

            log("loadLinks: response code=${response?.code}")
            logErr("loadLinks: AJAX BODY = ${response?.text?.take(1500)}")

            if (response != null && response.text.isNotBlank()) {
                val jsonObj = runCatching { JSONObject(response.text) }
                    .onFailure { logErr("loadLinks: JSON parse hatası", it) }
                    .getOrNull()

                val streamObj = jsonObj?.optJSONObject("data")?.optJSONObject("stream")
                log("loadLinks: data.stream mevcut mu? ${streamObj != null}")

                val bridgeUrl = streamObj?.optString("url")
                    ?: Regex("""src=["']([^"']+)["']""").find(response.text)?.groupValues?.get(1)

                log("loadLinks: bridgeUrl='$bridgeUrl'")

                if (!bridgeUrl.isNullOrBlank() && bridgeUrl.startsWith("http")) {
                    log("loadLinks: ADIM 2 → bridge sayfası çekiliyor: $bridgeUrl")

                    val bridgeHtml = runCatching {
                        app.get(bridgeUrl, headers = baseHeaders("$mainUrl/")).text
                    }.onFailure {
                        logErr("loadLinks: bridge GET hatası", it)
                    }.getOrDefault("")

                    log("loadLinks: bridgeHtml length=${bridgeHtml.length}")
                    logErr("loadLinks: BRIDGE HTML = ${bridgeHtml.take(2000)}")

                    val cerceveMatch = Regex(
                        """SPG\.cerceve\s*\(\s*["'][^"']+["']\s*,\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)"""
                    ).find(bridgeHtml)

                    log("loadLinks: cerceveMatch mevcut mu? ${cerceveMatch != null}")

                    if (cerceveMatch != null) {
                        val rawCipher = cerceveMatch.groupValues[1]
                        val rawKey = cerceveMatch.groupValues[2]

                        logErr("loadLinks: RAW cipherB64 (ilk 200) = ${rawCipher.take(200)}")
                        logErr("loadLinks: RAW keyB64 (ilk 200) = ${rawKey.take(200)}")

                        val cipherB64 = cleanBase64(rawCipher)
                        val keyB64 = cleanBase64(rawKey)

                        log("loadLinks: TEMİZ cipherB64 (ilk 100) = ${cipherB64.take(100)}")
                        log("loadLinks: TEMİZ keyB64 (ilk 100) = ${keyB64.take(100)}")
                        log("loadLinks: cipherB64.length=${cipherB64.length}, keyB64.length=${keyB64.length}")

                        val fastplayUrl = runCatching {
                            val cipherBytes = android.util.Base64.decode(
                                cipherB64,
                                android.util.Base64.DEFAULT
                            )
                            val keyBytes = android.util.Base64.decode(
                                keyB64,
                                android.util.Base64.DEFAULT
                            )
                            val decryptedBytes = ByteArray(cipherBytes.size) { idx ->
                                (cipherBytes[idx].toInt() xor keyBytes[idx % keyBytes.size].toInt()).toByte()
                            }
                            String(decryptedBytes, Charsets.UTF_8).split("|").firstOrNull()?.trim()
                        }.onFailure {
                            logErr("loadLinks: decrypt hatası", it)
                        }.getOrNull()

                        log("loadLinks: fastplayUrl='$fastplayUrl'")

                        if (!fastplayUrl.isNullOrBlank() && fastplayUrl.startsWith("http")) {
                            log("loadLinks: ADIM 3 → FastPlay sayfası çekiliyor: $fastplayUrl")

                            val fpDoc = runCatching {
                                app.get(fastplayUrl, headers = baseHeaders(bridgeUrl)).text
                            }.onFailure {
                                logErr("loadLinks: FastPlay GET hatası", it)
                            }.getOrDefault("")

                            log("loadLinks: fpDoc length=${fpDoc.length}")
                            logErr("loadLinks: FASTPLAY HTML = ${fpDoc.take(2000)}")

                            val streamPath = Regex("""stream:\s*["']([^"']+)["']""")
                                .find(fpDoc)?.groupValues?.get(1)

                            log("loadLinks: streamPath='$streamPath'")

                            if (!streamPath.isNullOrBlank()) {
                                val fpBase = fastplayUrl.substringBefore("/video/")
                                val manifestUrl = if (streamPath.startsWith("http")) {
                                    streamPath
                                } else {
                                    "$fpBase$streamPath"
                                }

                                log("loadLinks: fpBase='$fpBase'")
                                log("loadLinks: manifestUrl='$manifestUrl'")

                                val sp = Regex(""""sp"\s*:\s*"([^"]*)"""").find(fpDoc)?.groupValues?.get(1) ?: ""
                                val spT = Regex(""""spT"\s*:\s*(\d+)"""").find(fpDoc)?.groupValues?.get(1)?.toLongOrNull()
                                    ?: (System.currentTimeMillis() / 1000)

                                log("loadLinks: sp='$sp', spT=$spT")

                                // FNV-1a 32-bit
                                val r = java.lang.Long.toString((Math.random() * 2176782335L).toLong(), 36)
                                val hashInput = "$sp|$spT|$r"
                                var h = 2166136261L
                                for (ch in hashInput) {
                                    h = (h xor ch.code.toLong()) and 0xFFFFFFFFL
                                    h = (h * 16777619L) and 0xFFFFFFFFL
                                }
                                val sig = java.lang.Long.toHexString(h)
                                val xSp = "$spT.$r.$sig"

                                log("loadLinks: xSp='$xSp'")

                                callback(
                                    ExtractorLink(
                                        source = name,
                                        name = "$name FastPlay HD",
                                        url = manifestUrl,
                                        referer = "$fpBase/",
                                        quality = Qualities.P1080.value,
                                        type = ExtractorLinkType.M3U8,
                                        headers = mapOf(
                                            "Referer" to "$fpBase/",
                                            "User-Agent" to CommonHeaders.DEFAULT_USER_AGENT,
                                            "X-Sp" to xSp
                                        )
                                    )
                                )
                                log("loadLinks: ✅ callback TETİKLENDİ → manifestUrl")
                                processed = true

                                // Altyazılar
                                val tracksMatch = Regex("""tracks:\s*(\[[^\]]+\])""")
                                    .find(fpDoc)?.groupValues?.get(1)

                                log("loadLinks: tracksMatch mevcut mu? ${tracksMatch != null}")

                                if (!tracksMatch.isNullOrBlank()) {
                                    var subCount = 0
                                    Regex("""\{"file":"([^"]+)","label":"([^"]+)"""")
                                        .findAll(tracksMatch)
                                        .forEach { tm ->
                                            val subUrl = tm.groupValues[1].replace("\\/", "/")
                                            val subLabel = tm.groupValues[2]
                                            log("loadLinks: altyazı → $subLabel : $subUrl")
                                            subtitleCallback(
                                                SubtitleFile(lang = subLabel, url = subUrl)
                                            )
                                            subCount++
                                        }
                                    log("loadLinks: toplam $subCount altyazı eklendi")
                                }
                            } else {
                                logErr("loadLinks: streamPath bulunamadı!")
                            }
                        } else {
                            logErr("loadLinks: fastplayUrl geçersiz veya boş!")
                        }
                    } else {
                        log("loadLinks: SPG.cerceve yok → bridgeUrl extractor'a veriliyor")
                        if (loadExtractor(bridgeUrl, "$mainUrl/", subtitleCallback, callback)) {
                            log("loadLinks: ✅ loadExtractor başarılı (bridgeUrl)")
                            processed = true
                        } else {
                            logErr("loadLinks: ❌ loadExtractor başarısız (bridgeUrl)")
                        }
                    }
                } else {
                    logErr("loadLinks: bridgeUrl bulunamadı veya geçersiz!")
                }
            } else {
                logErr("loadLinks: admin-ajax yanıtı boş!")
            }
        } else {
            logErr("loadLinks: postId veya nonce eksik → AJAX atlanıyor")
        }

        // Fallback: iframe
        if (!processed && pageUrl.startsWith("http")) {
            log("loadLinks: FALLBACK → sayfadaki iframe'ler deneniyor")

            val doc = runCatching {
                app.get(pageUrl, headers = baseHeaders()).document
            }.onFailure {
                logErr("loadLinks: fallback GET hatası", it)
            }.getOrNull()

            val iframes = doc?.select("iframe")
            log("loadLinks: ${iframes?.size ?: 0} iframe bulundu")

            iframes?.forEachIndexed { idx, iframe ->
                val src = fixUrlNull(iframe.attr("src"))
                log("loadLinks: iframe[$idx] src='$src'")
                if (src != null) {
                    if (loadExtractor(src, "$mainUrl/", subtitleCallback, callback)) {
                        log("loadLinks: ✅ iframe[$idx] extractor başarılı")
                        processed = true
                    } else {
                        logErr("loadLinks: ❌ iframe[$idx] extractor başarısız")
                    }
                }
            }
        }

        log("╔══════════════════════════════════════╗")
        log("║  loadLinks BİTTİ → processed=$processed")
        log("╚══════════════════════════════════════╝")
        return processed
    }
}