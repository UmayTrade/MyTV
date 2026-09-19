package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

class OpenAnime : MainAPI() {
    override var mainUrl = "https://openanime.tv"
    override var name = "OpenAnime"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val tmdbImageBase = "https://image.tmdb.org/t/p/w500"

    override val mainPage = mainPageOf(
        "$mainUrl/kesfet?page=1" to "Tüm Animeler",
        "$mainUrl/kesfet?status=AIRING&page=1" to "Devam Edenler",
        "$mainUrl/kesfet?sort=SCORE_DESC&page=1" to "En Yüksek Puanlılar",
        "$mainUrl/kesfet?type=MOVIE&page=1" to "Anime Filmleri"
    )

    override async fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = request.data.replace("page=1", "page=$page")
        val doc = app.get(pageUrl).document
        val svelteData = extractSvelteData(doc)

        val homeItems = mutableListOf<SearchResponse>()

        if (svelteData != null) {
            val items = findArrayInJson(svelteData, "slug")
            for (item in items) {
                val animeObj = item as? JSONObject ?: continue
                val title = animeObj.optString("title_tr").takeIf { it.isNotBlank() }
                    ?: animeObj.optString("title_en").takeIf { it.isNotBlank() }
                    ?: animeObj.optString("title_original")
                    ?: continue

                val slug = animeObj.optString("slug")
                if (slug.isBlank()) continue

                val poster = buildPosterUrl(animeObj)

                homeItems.add(
                    newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        if (homeItems.isEmpty()) {
            doc.select("a[href^=/anime/]").forEach { element ->
                val href = element.attr("href")
                val title = element.text().trim()
                val img = element.selectFirst("img")?.attr("src")

                if (href.isNotBlank() && title.isNotBlank()) {
                    homeItems.add(
                        newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                            this.posterUrl = img
                        }
                    )
                }
            }
        }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, homeItems.distinctBy { it.url })),
            hasNext = homeItems.isNotEmpty()
        )
    }

    override async fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/kesfet?search=${query.encodeUri()}"
        val doc = app.get(searchUrl).document
        val svelteData = extractSvelteData(doc)

        val results = mutableListOf<SearchResponse>()

        if (svelteData != null) {
            val items = findArrayInJson(svelteData, "slug")
            for (item in items) {
                val animeObj = item as? JSONObject ?: continue
                val title = animeObj.optString("title_tr").takeIf { it.isNotBlank() }
                    ?: animeObj.optString("title_en").takeIf { it.isNotBlank() }
                    ?: animeObj.optString("title_original")
                    ?: continue

                val slug = animeObj.optString("slug")
                if (slug.isBlank()) continue

                val poster = buildPosterUrl(animeObj)

                results.add(
                    newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        return results.distinctBy { it.url }
    }

    override async fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val svelteData = extractSvelteData(doc)

        var title = doc.selectFirst("h1")?.text()?.trim() ?: "Bilinmeyen Anime"
        var description: String? = doc.selectFirst("p.synopsis, meta[name=description]")?.attr("content")
        var posterUrl: String? = null
        val episodesList = mutableListOf<Episode>()

        if (svelteData != null) {
            val animeObj = findObjectWithKey(svelteData, "title_tr")
                ?: findObjectWithKey(svelteData, "title_en")

            if (animeObj != null) {
                title = animeObj.optString("title_tr").takeIf { it.isNotBlank() }
                    ?: animeObj.optString("title_en").takeIf { it.isNotBlank() }
                    ?: animeObj.optString("title_original")
                    ?: title

                description = animeObj.optString("synopsis").takeIf { it.isNotBlank() } ?: description
                posterUrl = buildPosterUrl(animeObj)
            }

            val seasons = findArrayInJson(svelteData, "season_number")
            for (seasonItem in seasons) {
                val seasonObj = seasonItem as? JSONObject ?: continue
                val seasonNum = seasonObj.optInt("season_number", 1)
                val episodes = seasonObj.optJSONArray("episodes") ?: continue

                for (i in 0 until episodes.length()) {
                    val epObj = episodes.optJSONObject(i) ?: continue
                    val epNum = epObj.optInt("episode_number", i + 1)
                    val epTitle = epObj.optString("title").takeIf { it.isNotBlank() } ?: "$epNum. Bölüm"
                    val epSlug = epObj.optString("slug")

                    val animeSlug = url.substringAfterLast("/")
                    val epUrl = "$mainUrl/anime/$animeSlug/sezon-$seasonNum/bolum-$epNum"

                    episodesList.add(
                        newEpisode(epUrl) {
                            this.name = epTitle
                            this.episode = epNum
                            this.season = seasonNum
                            this.data = epObj.toString()
                        }
                    )
                }
            }
        }

        if (episodesList.isEmpty()) {
            doc.select("a[href*=/sezon-][href*=/bolum-]").forEach { epLink ->
                val epHref = epLink.attr("href")
                val epText = epLink.text().trim()

                episodesList.add(
                    newEpisode(fixUrl(epHref)) {
                        this.name = epText
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = posterUrl
            this.plot = description
            this.episodes = episodesList
        }
    }

    override async fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("{")) {
            val epObj = JSONObject(data)
            val videoUrl = epObj.optString("video_url")
            val iframeUrl = epObj.optString("iframe_url")

            if (videoUrl.isNotBlank()) {
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        videoUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value
                    )
                )
                return true
            }

            if (iframeUrl.isNotBlank()) {
                val embedDoc = app.get(iframeUrl).document
                val videoSrc = embedDoc.selectFirst("video source")?.attr("src")
                    ?: embedDoc.selectFirst("iframe")?.attr("src")

                if (!videoSrc.isNullOrBlank()) {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            name,
                            videoSrc,
                            referer = iframeUrl,
                            quality = Qualities.Unknown.value
                        )
                    )
                    return true
                }
            }
        }

        val doc = app.get(data).document
        val iframeSrc = doc.selectFirst("iframe[src*=/embed/], iframe[src*=/player/]")?.attr("src")

        if (!iframeSrc.isNullOrBlank()) {
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    fixUrl(iframeSrc),
                    referer = mainUrl,
                    quality = Qualities.Unknown.value
                )
            )
            return true
        }

        return false
    }

    private fun extractSvelteData(doc: Document): JSONArray? {
        val script = doc.select("script").firstOrNull { it.html().contains("data:[") } ?: return null
        val html = script.html()

        val start = html.indexOf("data:[")
        if (start == -1) return null

        var arrayStart = start + 5
        var depth = 0
        var end = -1

        for (i in arrayStart until html.length) {
            val char = html[i]
            if (char == '[') depth++
            else if (char == ']') {
                depth--
                if (depth == 0) {
                    end = i
                    break
                }
            }
        }

        if (end == -1) return null

        var source = html.substring(arrayStart, end + 1)

        try {
            // SvelteKit JS özel değerlerini standart JSON uyumlu değerlerle değiştir
            source = source
                .replace(Regex("""\bvoid\s+0\b"""), "null")
                .replace(Regex("""\bundefined\b"""), "null")
                .replace(Regex("""\bNaN\b"""), "null")
                .replace(Regex("""\bInfinity\b"""), "null")

            // JSON anahtarlarını tırnak içine al
            source = source.replace(
                Regex("""([\{,])\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*:"""),
                "$1\"$2\":"
            )

            return JSONArray(source)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun buildPosterUrl(obj: JSONObject?): String? {
        if (obj == null) return null

        fun formatUrl(path: String?): String? {
            if (path.isNullOrBlank() || path == "null") return null
            return if (path.startsWith("http")) {
                path
            } else {
                "$tmdbImageBase${if (path.startsWith("/")) path else "/$path"}"
            }
        }

        // 1. Doğrudan objede bulunan poster yolları
        formatUrl(obj.optString("poster").takeIf { it.isNotBlank() })?.let { return it }
        formatUrl(obj.optString("poster_path").takeIf { it.isNotBlank() })?.let { return it }

        // 2. Pictures objesi altındaki avatar/banner görselleri
        val pictures = obj.optJSONObject("pictures")
        if (pictures != null) {
            formatUrl(pictures.optString("avatar"))?.let { return it }
            formatUrl(pictures.optString("banner"))?.let { return it }
        }

        return null
    }

    private fun findArrayInJson(jsonArray: JSONArray, keyToFind: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()

        fun recurse(element: Any?) {
            when (element) {
                is JSONObject -> {
                    if (element.has(keyToFind)) {
                        result.add(element)
                    }
                    element.keys().forEach { key ->
                        recurse(element.get(key))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until element.length()) {
                        recurse(element.get(i))
                    }
                }
            }
        }

        recurse(jsonArray)
        return result
    }

    private fun findObjectWithKey(jsonArray: JSONArray, keyToFind: String): JSONObject? {
        fun recurse(element: Any?): JSONObject? {
            when (element) {
                is JSONObject -> {
                    if (element.has(keyToFind) && !element.isNull(keyToFind)) {
                        return element
                    }
                    element.keys().forEach { key ->
                        val found = recurse(element.get(key))
                        if (found != null) return found
                    }
                }
                is JSONArray -> {
                    for (i in 0 until element.length()) {
                        val found = recurse(element.get(i))
                        if (found != null) return found
                    }
                }
            }
            return null
        }

        return recurse(jsonArray)
    }
}
