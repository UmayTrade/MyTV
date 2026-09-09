package com.droctagon

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class RecTVBC : MainAPI() {
    override var mainUrl              = "https://a.prectv71.lol"
    override var name                 = "RecTV BC"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.Live, TvType.TvSeries)

    private val swKey      = "4F5A9C3D9A86FA54EACEDDD635185/c3c5bd17-e37b-4b94-a944-8a3688a30452"
    private val hmacKey    = "3508611138826751fdf77beaa6f93eb93fd27e6a5acb910e7aad22665513dd6e"
    private val appVersion = "157"
    private val clientId   = "rectv-android"
    private val aesKeyHex  = "666482389dc76bfa57068407418f7dac9f6c14b6868856b169165b9fac7d812e"

    private val jwtMutex = Mutex()

    @Volatile
    private var cachedJwt: String? = null
    @Volatile
    private var jwtExpirationTimestamp: Long = 0L

    // ---- HMAC and Hashing ----
    private fun sha256Hex(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    // ---- JWT Session Manager ----
    private suspend fun getJwt(): String? {
        val now = System.currentTimeMillis() / 1000L
        val currentJwt = cachedJwt
        if (currentJwt != null && now < jwtExpirationTimestamp - 300) {
            return currentJwt
        }

        return jwtMutex.withLock {
            val currentNow = System.currentTimeMillis() / 1000L
            val recheckJwt = cachedJwt
            if (recheckJwt != null && currentNow < jwtExpirationTimestamp - 300) {
                return@withLock recheckJwt
            }

            try {
                val path = "/api/attest/verify"
                val body = "{}"
                val headers = getSignedHeaders("POST", path, body, includeAuth = false).toMutableMap()
                headers["Content-Type"] = "application/json"

                val requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType())
                val response = app.post(
                    "$mainUrl$path",
                    headers = headers,
                    requestBody = requestBody
                )
                val verifyResp = AppUtils.tryParseJson<RecVerifyResponse>(response.text)
                val token = verifyResp?.jwt
                if (token != null) {
                    cachedJwt = token
                    var exp = verifyResp.exp ?: (currentNow + 7000L)
                    try {
                        val parts = token.split(".")
                        if (parts.size >= 2) {
                            val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                            val payloadJson = String(payloadBytes, Charsets.UTF_8)
                            val expRegex = Regex("\"exp\"\\s*:\\s*(\\d+)")
                            expRegex.find(payloadJson)?.groupValues?.get(1)?.toLongOrNull()?.let {
                                exp = it
                            }
                        }
                    } catch (_: Exception) {}
                    jwtExpirationTimestamp = exp
                    token
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("RecTVBC", "Failed to fetch JWT: ${e.message}")
                cachedJwt
            }
        }
    }

    private suspend fun getSignedHeaders(
        method: String,
        path: String,
        body: String = "",
        includeAuth: Boolean = true
    ): Map<String, String> {
        val ts        = (System.currentTimeMillis() / 1000L).toString()
        val nonce     = UUID.randomUUID().toString()
        val bodyHash  = sha256Hex(body)
        val message   = "$method\n$path\n$ts\n$nonce\n$bodyHash"
        val signature = hmacSha256Hex(hmacKey, message)

        val headers = mutableMapOf(
            "User-Agent"     to "googleusercontent",
            "Referer"        to "https://twitter.com/",
            "X-Timestamp"    to ts,
            "X-Nonce"        to nonce,
            "X-Signature"    to signature,
            "X-App-Version"  to appVersion,
            "X-Client-Id"    to clientId
        )

        if (includeAuth) {
            val authToken = getJwt()
            if (!authToken.isNullOrEmpty()) {
                headers["Authorization"] = "Bearer $authToken"
            }
        }

        return headers
    }

    // ---- Stream URL Decryption (AES-GCM-256) ----
    private fun decryptEncUrl(encUrl: String): String? {
        return try {
            val raw = Base64.decode(encUrl, Base64.DEFAULT)
            if (raw.size < 28) return null

            val iv = raw.copyOfRange(0, 12)
            val cipherTextAndTag = raw.copyOfRange(12, raw.size)

            val keyBytes = ByteArray(aesKeyHex.length / 2)
            for (i in keyBytes.indices) {
                val index = i * 2
                keyBytes[i] = aesKeyHex.substring(index, index + 2).toInt(16).toByte()
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            val decrypted = cipher.doFinal(cipherTextAndTag)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("RecTVBC", "Failed to decrypt URL: ${e.message}")
            null
        }
    }

    // ---- Source Unlocker (for AD_UNLOCK_REQUIRED sources like Bein Sports 1-5) ----
    private suspend fun unlockSource(sourceId: Int): String? {
        return try {
            val path = "/api/source/unlock-ad/$sourceId/$swKey/"
            val body = "{}"
            val headers = getSignedHeaders("POST", path, body).toMutableMap()
            headers["Content-Type"] = "application/json"

            val requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType())
            val response = app.post(
                "$mainUrl$path",
                headers = headers,
                requestBody = requestBody
            )
            val unlockResp = AppUtils.tryParseJson<RecUnlockResponse>(response.text)
            unlockResp?.enc_url
        } catch (e: Exception) {
            Log.e("RecTVBC", "Failed to unlock source $sourceId: ${e.message}")
            null
        }
    }

    // ---- Categories & Homepage ----
    override val mainPage = mainPageOf(
        "${mainUrl}/api/channel/by/filtres/1/0/SAYFA/${swKey}/"       to "Spor",
        "${mainUrl}/api/channel/by/filtres/0/0/SAYFA/${swKey}/"       to "Canlı TV",
        "${mainUrl}/api/movie/by/filtres/0/created/SAYFA/${swKey}/"   to "Son Filmler",
        "${mainUrl}/api/serie/by/filtres/0/created/SAYFA/${swKey}/"   to "Son Diziler",
        "${mainUrl}/api/movie/by/filtres/14/created/SAYFA/${swKey}/"  to "Aile",
        "${mainUrl}/api/movie/by/filtres/1/created/SAYFA/${swKey}/"   to "Aksiyon",
        "${mainUrl}/api/movie/by/filtres/13/created/SAYFA/${swKey}/"  to "Animasyon",
        "${mainUrl}/api/movie/by/filtres/19/created/SAYFA/${swKey}/"  to "Belgesel",
        "${mainUrl}/api/movie/by/filtres/4/created/SAYFA/${swKey}/"   to "Bilim Kurgu",
        "${mainUrl}/api/movie/by/filtres/2/created/SAYFA/${swKey}/"   to "Dram",
        "${mainUrl}/api/movie/by/filtres/10/created/SAYFA/${swKey}/"  to "Fantastik",
        "${mainUrl}/api/movie/by/filtres/3/created/SAYFA/${swKey}/"   to "Komedi",
        "${mainUrl}/api/movie/by/filtres/8/created/SAYFA/${swKey}/"   to "Korku",
        "${mainUrl}/api/movie/by/filtres/17/created/SAYFA/${swKey}/"  to "Macera",
        "${mainUrl}/api/movie/by/filtres/5/created/SAYFA/${swKey}/"   to "Romantik"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageIndex = page - 1
        val url = request.data.replace("SAYFA", "$pageIndex")
        val uri = java.net.URI(url)
        val path = uri.rawPath
        val headers = getSignedHeaders("GET", path)
        val res = app.get(url, headers = headers)

        val items = AppUtils.tryParseJson<List<RecItem>>(res.text) ?: emptyList()

        val filteredItems = if (request.name == "Canlı TV") {
            // Spor kanallarını Canlı TV sekmesinde tekrar göstermemek için hariç tut
            items.filter { item ->
                item.categories?.none { it.id == 1 } ?: true
            }
        } else {
            items
        }

        val movies = filteredItems.mapNotNull { item ->
            val toDict = jacksonObjectMapper().writeValueAsString(item)
            val isLive = item.label.equals("CANLI", ignoreCase = true) ||
                         request.name == "Spor" ||
                         request.name == "Canlı TV"

            if (isLive) {
                newLiveSearchResponse(item.title, toDict, TvType.Live) {
                    this.posterUrl = item.image
                }
            } else if (item.type == "serie") {
                newTvSeriesSearchResponse(item.title, toDict, TvType.TvSeries) {
                    this.posterUrl = item.image
                }
            } else {
                newMovieSearchResponse(item.title, toDict, TvType.Movie) {
                    this.posterUrl = item.image
                }
            }
        }

        return newHomePageResponse(request.name, movies)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val path = "/api/search/${query}/${swKey}/"
        val headers = getSignedHeaders("GET", path)
        val home = app.get("$mainUrl$path", headers = headers)
        val veriler = AppUtils.tryParseJson<RecSearch>(home.text)

        val sonuclar = mutableListOf<SearchResponse>()

        veriler?.channels?.forEach { item ->
            val toDict = jacksonObjectMapper().writeValueAsString(item)
            sonuclar.add(newLiveSearchResponse(item.title, toDict, TvType.Live) {
                this.posterUrl = item.image
            })
        }

        veriler?.posters?.forEach { item ->
            val toDict = jacksonObjectMapper().writeValueAsString(item)
            if (item.type == "serie") {
                sonuclar.add(newTvSeriesSearchResponse(item.title, toDict, TvType.TvSeries) {
                    this.posterUrl = item.image
                })
            } else {
                sonuclar.add(newMovieSearchResponse(item.title, toDict, TvType.Movie) {
                    this.posterUrl = item.image
                })
            }
        }

        return sonuclar
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val veri = AppUtils.tryParseJson<RecItem>(url) ?: return null

        // 1. Dizi (TV Series)
        if (veri.type == "serie") {
            val path     = "/api/season/by/serie/${veri.id}/${swKey}/"
            val headers  = getSignedHeaders("GET", path)
            val diziReq  = app.get("$mainUrl$path", headers = headers)
            val sezonlar = AppUtils.tryParseJson<List<RecDizi>>(diziReq.text) ?: return null

            val episodes = mutableMapOf<DubStatus, MutableList<Episode>>()
            val numberRegex = Regex("\\d+")

            for (sezon in sezonlar) {
                val seasonDubStatus = if (sezon.title.contains("altyazı", ignoreCase = true) || sezon.title.contains("altyazi", ignoreCase = true)) {
                    DubStatus.Subbed
                } else if (sezon.title.contains("dublaj", ignoreCase = true)) {
                    DubStatus.Dubbed
                } else {
                    DubStatus.None
                }

                for (bolum in sezon.episodes) {
                    val bolumJson = jacksonObjectMapper().writeValueAsString(bolum)
                    episodes.getOrPut(seasonDubStatus) { mutableListOf() }.add(
                        newEpisode(bolumJson) {
                            this.name        = bolum.title
                            this.season      = numberRegex.find(sezon.title)?.value?.toIntOrNull()
                            this.episode     = numberRegex.find(bolum.title)?.value?.toIntOrNull()
                            this.description = if (sezon.title.contains(".S ")) sezon.title.substringAfter(".S ") else sezon.title
                            this.posterUrl   = veri.image
                        }
                    )
                }
            }

            return newAnimeLoadResponse(veri.title, url, TvType.TvSeries, comingSoonIfNone = false) {
                this.episodes = episodes.mapValues { it.value.toList() }.toMutableMap()
                this.posterUrl = veri.image
                this.plot      = veri.description
                this.year      = veri.year
                this.tags      = veri.genres?.map { it.title }
            }
        }

        // 2. Canlı TV veya Spor Kanalı
        val isLive = veri.label.equals("CANLI", ignoreCase = true) || veri.categories?.isNotEmpty() == true
        if (isLive) {
            val fullChannel = if (veri.sources.isNullOrEmpty()) {
                val chPath = "/api/channel/by/${veri.id}/${swKey}/"
                val chResp = app.get("$mainUrl$chPath", headers = getSignedHeaders("GET", chPath))
                AppUtils.tryParseJson<RecItem>(chResp.text) ?: veri
            } else {
                veri
            }
            val chJson = jacksonObjectMapper().writeValueAsString(fullChannel)
            return newLiveStreamLoadResponse(fullChannel.title, url, chJson) {
                this.posterUrl = fullChannel.image ?: veri.image
                this.plot      = fullChannel.description ?: veri.description
                this.tags      = fullChannel.categories?.map { it.title }
            }
        }

        // 3. Film (Movie)
        val fullMovie = if (veri.sources.isNullOrEmpty()) {
            val mPath = "/api/movie/by/${veri.id}/${swKey}/"
            val mResp = app.get("$mainUrl$mPath", headers = getSignedHeaders("GET", mPath))
            AppUtils.tryParseJson<RecItem>(mResp.text) ?: veri
        } else {
            veri
        }
        val movieJson = jacksonObjectMapper().writeValueAsString(fullMovie)
        return newMovieLoadResponse(fullMovie.title, url, TvType.Movie, movieJson) {
            this.posterUrl = fullMovie.image ?: veri.image
            this.plot      = fullMovie.description ?: veri.description
            this.year      = fullMovie.year ?: veri.year
            this.tags      = fullMovie.genres?.map { it.title }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("http://") || data.startsWith("https://")) {
            val linkType = if (data.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else if (data.contains(".mp4", ignoreCase = true)) {
                ExtractorLinkType.VIDEO
            } else {
                INFER_TYPE
            }
            callback.invoke(
                newExtractorLink(
                    source  = this.name,
                    name    = this.name,
                    url     = data,
                    type    = linkType
                ) {
                    this.referer = "https://twitter.com/"
                    this.headers = mapOf(
                        "Referer" to "https://twitter.com/",
                        "User-Agent" to "googleusercontent"
                    )
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        val sources = mutableListOf<RecSource>()

        // Try as RecItem
        val item = AppUtils.tryParseJson<RecItem>(data)
        item?.sources?.let {
            sources.addAll(it)
        }

        // If sources empty and item has id, fetch full details from API
        if (sources.isEmpty() && item != null && item.id > 0) {
            try {
                if (item.type == "movie") {
                    val mPath = "/api/movie/by/${item.id}/${swKey}/"
                    val mResp = app.get("$mainUrl$mPath", headers = getSignedHeaders("GET", mPath))
                    AppUtils.tryParseJson<RecItem>(mResp.text)?.sources?.let {
                        sources.addAll(it)
                    }
                } else if (item.label.equals("CANLI", ignoreCase = true) || item.categories?.isNotEmpty() == true) {
                    val chPath = "/api/channel/by/${item.id}/${swKey}/"
                    val chResp = app.get("$mainUrl$chPath", headers = getSignedHeaders("GET", chPath))
                    AppUtils.tryParseJson<RecItem>(chResp.text)?.sources?.let {
                        sources.addAll(it)
                    }
                }
            } catch (e: Exception) {
                Log.e("RecTVBC", "Failed to fetch item sources in loadLinks: ${e.message}")
            }
        }

        // If not found, try as RecEpisode
        if (sources.isEmpty()) {
            AppUtils.tryParseJson<RecEpisode>(data)?.sources?.let {
                sources.addAll(it)
            }
        }

        // If not found, try as List<RecSource>
        if (sources.isEmpty()) {
            AppUtils.tryParseJson<List<RecSource>>(data)?.let {
                sources.addAll(it)
            }
        }

        if (sources.isEmpty()) return false

        for (source in sources) {
            val enc = if (!source.enc_url.isNullOrEmpty()) {
                source.enc_url
            } else if ((source.locked == true || source.enc_url.isNullOrEmpty()) && source.id != null) {
                unlockSource(source.id)
            } else {
                null
            }

            val streamUrl = if (!enc.isNullOrEmpty()) {
                decryptEncUrl(enc)
            } else {
                source.url
            }

            if (streamUrl.isNullOrEmpty()) continue

            val srcTitle = source.title ?: source.quality ?: source.type ?: "Kaynak"
            val linkType = if (streamUrl.contains(".m3u8", ignoreCase = true) || source.type.equals("m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else if (source.type.equals("mp4", ignoreCase = true) || streamUrl.endsWith(".mp4", ignoreCase = true)) {
                ExtractorLinkType.VIDEO
            } else {
                INFER_TYPE
            }

            callback.invoke(
                newExtractorLink(
                    source  = this.name,
                    name    = "${this.name} - $srcTitle",
                    url     = streamUrl,
                    type    = linkType
                ) {
                    this.referer = "https://twitter.com/"
                    this.headers = mapOf(
                        "Referer" to "https://twitter.com/",
                        "User-Agent" to "googleusercontent"
                    )
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val originalRequest = chain.request()
            val modifiedRequest = originalRequest.newBuilder()
                .removeHeader("If-None-Match")
                .header("User-Agent", "googleusercontent")
                .header("Referer", "https://twitter.com/")
                .build()
            chain.proceed(modifiedRequest)
        }
    }
}
