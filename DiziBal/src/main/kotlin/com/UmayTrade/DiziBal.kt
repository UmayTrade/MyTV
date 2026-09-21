package com.panates

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import java.net.URLEncoder

class DiziBal : MainAPI() {
    override var mainUrl = "https://dizibal.org"
    override var name = "DiziBal"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "tr"
    override val hasMainPage = true

    companion object {
        private const val DEFAULT_PLAYER_BASE = "https://play2.pilavyerplay.top"

        private val defaultHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Referer" to "https://dizibal.org/"
        )
    }

    // ── Main Page ───────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val allPages = mutableListOf<HomePageList>()

        val doc = try {
            app.get(mainUrl, headers = defaultHeaders).document
        } catch (_: Exception) {
            return newHomePageResponse(allPages)
        }

        // Parse each section with an h2 heading and a row of content cards
        val sections = doc.select("section, div.container-site > div")
        for (sec in sections) {
            val titleEl = sec.selectFirst("h2") ?: continue
            val rawTitle = titleEl.text().trim()
            if (rawTitle.isBlank() || rawTitle.equals("DiziBal", ignoreCase = true)) continue

            val cards = sec.select("a[href*='/series/'], a[href*='/movie/'], a[href*='/anime/']")
            val items = cards.mapNotNull { a ->
                val href = a.attr("href")
                if (href.isBlank() || href.contains("/season/") || href.contains("/episode/")) return@mapNotNull null

                val img = a.selectFirst("img")
                val poster = img?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("srcset")?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() }

                val rawName = img?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: a.selectFirst("p.font-semibold, h3, h4")?.text()
                    ?: a.text()

                val title = cleanTitle(rawName)
                if (title.isBlank()) return@mapNotNull null

                val fullUrl = fixUrl(href)
                when {
                    fullUrl.contains("/movie/") -> newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                        this.posterUrl = poster?.let { fixUrl(it) }
                    }
                    fullUrl.contains("/anime/") -> newTvSeriesSearchResponse(title, fullUrl, TvType.Anime) {
                        this.posterUrl = poster?.let { fixUrl(it) }
                    }
                    else -> newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                        this.posterUrl = poster?.let { fixUrl(it) }
                    }
                }
            }.distinctBy { it.url }

            if (items.isNotEmpty()) {
                allPages.add(HomePageList(rawTitle, items))
            }
        }

        return newHomePageResponse(allPages)
    }

    // ── Search ──────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/ara?q=${URLEncoder.encode(query.trim(), "UTF-8")}"

        val doc = try {
            app.get(searchUrl, headers = defaultHeaders).document
        } catch (_: Exception) {
            return emptyList()
        }

        val cards = doc.select("a[href*='/movie/'], a[href*='/series/'], a[href*='/anime/']")

        return cards.mapNotNull { a ->
            val href = a.attr("href")
            if (href.isBlank() || href.contains("/season/") || href.contains("/episode/")) return@mapNotNull null

            val img = a.selectFirst("img")
            val poster = img?.attr("src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("srcset")?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() }

            val rawName = img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: a.selectFirst("h3, h4, p.font-semibold")?.text()
                ?: a.text()

            val title = cleanTitle(rawName)
            if (title.isBlank()) return@mapNotNull null

            val fullUrl = fixUrl(href)
            when {
                fullUrl.contains("/movie/") -> newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                    this.posterUrl = poster?.let { fixUrl(it) }
                }
                fullUrl.contains("/anime/") -> newTvSeriesSearchResponse(title, fullUrl, TvType.Anime) {
                    this.posterUrl = poster?.let { fixUrl(it) }
                }
                else -> newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                    this.posterUrl = poster?.let { fixUrl(it) }
                }
            }
        }.distinctBy { it.url }
    }

    // ── Load Details ────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = defaultHeaders).document

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "İçerik"
        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".tb-slot img, img[src*='/posters/']")?.attr("src")?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }

        val plot = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: doc.selectFirst("p.text-content-secondary")?.text()?.trim()

        val year = Regex("""(20\d\d|19\d\d)""").find(doc.text())?.groupValues?.get(1)?.toIntOrNull()

        val tags = doc.select("a[href*='/tur/']").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotBlank() }
        }.distinct()

        val ratingText = doc.selectFirst(".text-star")?.parent()?.text()
        val score = ratingText?.let {
            Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull()
        }

        val isMovie = url.contains("/movie/")

        if (isMovie) {
            val watchUrl = if (url.endsWith("/izle")) url else "${url.removeSuffix("/")}/izle"
            val episodes = listOf(
                newEpisode(watchUrl) {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                    this.posterUrl = poster
                }
            )

            return newMovieLoadResponse(title, url, TvType.Movie, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score?.let { Score.from10(it) }
            }
        }

        // TV Series or Anime: Extract all seasons and episodes
        val cleanBaseUrl = url.split("?").first().removeSuffix("/")

        // Discover available seasons from season tabs (e.g. ?sezon=2#bolumler)
        val seasonNumbers = doc.select("a[href*='sezon=']").mapNotNull {
            Regex("""sezon=(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.distinct().sorted()

        val seasonsToFetch = if (seasonNumbers.isEmpty()) listOf(1) else seasonNumbers
        val episodes = mutableListOf<Episode>()

        for (s in seasonsToFetch) {
            val seasonDoc = if (s == 1) {
                doc
            } else {
                try {
                    app.get("$cleanBaseUrl?sezon=$s", headers = defaultHeaders).document
                } catch (_: Exception) {
                    continue
                }
            }

            // Cards with episode links
            val epCards = seasonDoc.select("a[href*='/season/$s/episode/']")
            val distinctCards = if (epCards.isNotEmpty()) {
                epCards
            } else if (s == 1) {
                seasonDoc.select("a[href*='/episode/']")
            } else {
                emptyList()
            }

            for (card in distinctCards) {
                val epHref = fixUrl(card.attr("href"))
                val epImg = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

                val sNum = Regex("""/season/(\d+)/""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: s
                val epNum = Regex("""/episode/(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                val epNameText = card.selectFirst("p.truncate")?.text()?.trim()
                val epName = if (!epNameText.isNullOrBlank()) {
                    "$epNum. Bölüm - $epNameText"
                } else {
                    "$sNum. Sezon $epNum. Bölüm"
                }

                episodes.add(newEpisode(epHref) {
                    this.name = epName
                    this.season = sNum
                    this.episode = epNum
                    this.posterUrl = epImg ?: poster
                })
            }
        }

        val type = if (url.contains("/anime/")) TvType.Anime else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, type, episodes.distinctBy { it.data }) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = score?.let { Score.from10(it) }
        }
    }

    // ── Load Links ──────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPage = try {
            app.get(data, headers = defaultHeaders).document
        } catch (_: Exception) {
            return false
        }

        var found = false

        // 1. Pilavyer player integration (data-pv attribute)
        val dataPv = watchPage.selectFirst("div[data-pv]")?.attr("data-pv")
        val coreScriptSrc = watchPage.selectFirst("script[src*='pilavyerplay'], script[src*='/assets/js/core.js']")?.attr("src")

        val playerBase = if (!coreScriptSrc.isNullOrBlank()) {
            coreScriptSrc.substringBefore("/assets/js/core.js").substringBefore("/e/c.js")
        } else {
            DEFAULT_PLAYER_BASE
        }

        if (!dataPv.isNullOrBlank()) {
            val iframeUrl = "$playerBase/assets/js/s.php?s=${URLEncoder.encode(dataPv, "UTF-8")}"
            try {
                val iframeHtml = app.get(
                    iframeUrl,
                    headers = mapOf(
                        "User-Agent" to defaultHeaders["User-Agent"]!!,
                        "Referer" to "$mainUrl/"
                    )
                ).text

                val playerMatch = Regex("""window\.__PLAYER__\s*=\s*(\{.*?\});""").find(iframeHtml)
                if (playerMatch != null) {
                    val playerJson = JSONObject(playerMatch.groupValues[1])
                    val streamUrl = playerJson.optString("stream")

                    if (!streamUrl.isNullOrBlank()) {
                        // Extract subtitles
                        val subsArray = playerJson.optJSONArray("subs")
                        if (subsArray != null) {
                            for (i in 0 until subsArray.length()) {
                                val sub = subsArray.optJSONObject(i) ?: continue
                                val subSrc = sub.optString("src")
                                val subLabel = sub.optString("label", "Türkçe")
                                if (!subSrc.isNullOrBlank()) {
                                    subtitleCallback(SubtitleFile(subLabel, subSrc))
                                }
                            }
                        }

                        // Extract available audio tracks
                        val audiosArray = playerJson.optJSONArray("audios")
                        val audioLabels = mutableListOf<String>()
                        if (audiosArray != null) {
                            for (i in 0 until audiosArray.length()) {
                                val audio = audiosArray.optJSONObject(i) ?: continue
                                val label = audio.optString("label")
                                if (!label.isNullOrBlank()) {
                                    audioLabels.add(label)
                                }
                            }
                        }

                        val streamName = if (audioLabels.isNotEmpty()) {
                            "DiziBal (${audioLabels.joinToString(" / ")})"
                        } else {
                            "DiziBal (HLS)"
                        }

                        callback(
                            ExtractorLink(
                                source = "Pilavyer",
                                name = streamName,
                                url = streamUrl,
                                referer = iframeUrl,
                                quality = Qualities.P1080.value,
                                type = ExtractorLinkType.M3U8,
                                headers = mapOf(
                                    "Referer" to iframeUrl,
                                    "User-Agent" to defaultHeaders["User-Agent"]!!
                                )
                            )
                        )
                        found = true
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback: inspect any embedded iframes or video tags
        if (!found) {
            val iframes = watchPage.select("iframe[src]").map { it.attr("src") }
            for (iframe in iframes) {
                val fullIframe = fixUrl(iframe)
                if (fullIframe.contains("youtube.com") || fullIframe.contains("google")) continue

                if (loadExtractor(fullIframe, data, subtitleCallback) { link ->
                    callback(
                        ExtractorLink(
                            link.source ?: "DiziBal",
                            "DiziBal - ${link.name}",
                            link.url ?: "",
                            link.referer ?: mainUrl,
                            link.quality,
                            link.headers ?: emptyMap(),
                            link.extractorData,
                            link.type,
                            link.audioTracks ?: emptyList()
                        )
                    )
                }) {
                    found = true
                }
            }
        }

        return found
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("(?i)\\s*(?:dizi|film|anime)\\s*izle"), "")
            .replace(Regex("(?i)\\s*izle.*"), "")
            .replace(Regex("(?i)\\s*\\(\\d{4}\\).*"), "")
            .trim()
    }
}
