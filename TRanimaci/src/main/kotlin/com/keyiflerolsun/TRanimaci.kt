// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.jsoup.Jsoup

class TRanimaci : MainAPI() {
    override var mainUrl              = "https://tranimaci.com"
    override var name                 = "TrAnimeci"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 500L
    override var sequentialMainPageScrollDelay = 500L

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "https://tranimaci.com/"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/category/action"         to "Aksiyon",
        "${mainUrl}/category/cars"           to "Arabalar",
        "${mainUrl}/category/supernatural"   to "Doğaüstü",
        "${mainUrl}/category/drama"          to "Dram",
        "${mainUrl}/category/ecchi"          to "Ecchi",
        "${mainUrl}/category/fantasy"        to "Fantastik",
        "${mainUrl}/category/mystery"        to "Gizem",
        "${mainUrl}/category/comedy"         to "Komedi",
        "${mainUrl}/category/horror"         to "Korku",
        "${mainUrl}/category/adventure"      to "Macera",
        "${mainUrl}/category/mecha"          to "Mecha",
        "${mainUrl}/category/music"          to "Müzik",
        "${mainUrl}/category/romance"        to "Romantik",
        "${mainUrl}/category/sports"         to "Spor"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = headers).document
        val home     = document.select("article.bs div.bsx").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val aTag      = this.selectFirst("a") ?: return null
        val title     = aTag.text()?.trim() ?: return null
        val href      = fixUrlNull(aTag.attr("href")) ?: return null

        val imgTag    = this.selectFirst("div.limit img") ?: return null
        val posterUrl = fixUrlNull(
            imgTag.attr("data-src").takeIf { it.isNotBlank() }
                ?: imgTag.attr("src").takeIf { it.isNotBlank() }
                ?: imgTag.attr("data-lazy-src").takeIf { it.isNotBlank() }
        )

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/search?name=${query}", headers = headers).document
        return document.select("article.bs div.bsx").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title       = document.selectFirst("h1")?.text()?.trim() ?: return null

        val poster      = fixUrlNull(
            document.selectFirst("div.thumb img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("div.thumb img")?.attr("src")
        )

        val description = document.selectFirst("div.anime-description")?.text()?.trim()
        val tags        = document.select("div#genxed a[href*='/category']").map { it.text() }

        val episodeses = mutableListOf<Episode>()

        for (bolum in document.select("div.eplister ul li a")) {
            val epHref = fixUrlNull(bolum.attr("href")) ?: continue
            val epName = bolum.selectFirst(".epl-title")?.text()?.trim() ?: continue
            val epEpisode = epName.replace(Regex("""[^\d]"""), "").trim().toIntOrNull()

            val newEpisode = newEpisode(epHref) {
                this.name = epName
                this.episode = epEpisode
            }
            episodeses.add(newEpisode)
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addEpisodes(episodeses)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("ANI", "data » $data")
        val document = app.get(data, headers = headers).document

        val scriptContent = document.select("script").firstOrNull {
            it.html().contains("video_source")
        }?.html() ?: run {
            Log.d("ANI", "video_source script bulunamadı")
            return false
        }

        val videoSourceJson = try {
            Regex("""video_source\s*=\s*`(\[.*?])`""", RegexOption.DOT_MATCHES_ALL)
                .find(scriptContent)?.groups?.get(1)?.value
                ?: Regex("""video_source\s*=\s*"(\[.*?])"""", RegexOption.DOT_MATCHES_ALL)
                    .find(scriptContent)?.groups?.get(1)?.value
                ?: Regex("""video_source\s*=\s*'(\[.*?])'""", RegexOption.DOT_MATCHES_ALL)
                    .find(scriptContent)?.groups?.get(1)?.value
        } catch (e: Exception) {
            Log.d("ANI", "video_source parse hatası: ${e.message}")
            return false
        } ?: run {
            Log.d("ANI", "video_source JSON bulunamadı")
            return false
        }

        val videoSourceArray = try {
            JSONArray(videoSourceJson)
        } catch (e: Exception) {
            Log.d("ANI", "video_source JSONArray hatası: ${e.message}")
            return false
        }

        for (i in 0 until videoSourceArray.length()) {
            val source = videoSourceArray.getJSONObject(i)
            val apiUrl = source.optString("url") ?: continue
            Log.d("ANI", "apiUrl » $apiUrl")

            val apiHtml = try {
                app.get(apiUrl, headers = mapOf(
                    "Referer" to "https://tranimaci.com/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )).text
            } catch (e: Exception) {
                Log.d("ANI", "API isteği hatası: ${e.message}")
                continue
            }

            val apiDoc = Jsoup.parse(apiHtml)

            val sourcesScript = apiDoc.select("script").firstOrNull {
                val html = it.html()
                html.contains("const sources") || html.contains("var sources")
            } ?: continue

            val scriptHtml = sourcesScript.html()

            val sourcesArrayRaw = try {
                Regex("""(?:const|var)\s+sources\s*=\s*(\[[\s\S]*?])\s*;""")
                    .find(scriptHtml)?.groups?.get(1)?.value
            } catch (e: Exception) {
                Log.d("ANI", "sources regex hatası: ${e.message}")
                continue
            } ?: continue

            val mp4Array = try {
                JSONArray(sourcesArrayRaw)
            } catch (e: Exception) {
                Log.d("ANI", "mp4Array parse hatası: ${e.message}")
                continue
            }

            Log.d("ANI", "mp4Array » $mp4Array")

            for (j in 0 until mp4Array.length()) {
                val mp4 = mp4Array.getJSONObject(j)
                val rawSrc = mp4.optString("src") ?: continue

                val videoUrl = when {
                    rawSrc.startsWith("http") -> rawSrc
                    rawSrc.startsWith("//") -> "https:$rawSrc"
                    rawSrc.startsWith("/") -> "https://api.animeuzayi.com$rawSrc"
                    else -> "https://api.animeuzayi.com/$rawSrc"
                }

                val quality = when {
                    mp4.has("size") -> mp4.optInt("size", Qualities.Unknown.value)
                    mp4.has("label") -> {
                        val label = mp4.optString("label", "")
                        when {
                            label.contains("1080") -> Qualities.P1080.value
                            label.contains("720") -> Qualities.P720.value
                            label.contains("480") -> Qualities.P480.value
                            label.contains("360") -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                    }
                    else -> Qualities.Unknown.value
                }

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} - ${quality}p",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://api.animeuzayi.com/"
                        this.quality = quality
                    }
                )
            }
        }

        return true
    }
}
