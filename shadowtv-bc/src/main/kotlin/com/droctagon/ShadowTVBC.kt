package com.droctagon

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ShadowTVBC : MainAPI() {
    override var mainUrl              = "https://androidapi.site"
    override var name                 = "Shadow TV BC"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)

    companion object {
        private const val BOOTSTRAP_URL    = "https://raw.githubusercontent.com/asdsplus/gulum/refs/heads/main/ssl2.key"
        private const val DEFAULT_API_URL  = "https://androidapi.site/appMainGetData.php"
        private const val SIMGE            = "trskmrskslmzbzcnfstkcshpfstkcshp"
        private const val IMGE             = "trskmrskslmzbzcn"
        private const val TS_KEY           = "HdRyPcAqhpXf92kLmThp4sQzWv7nXeAs"
        private const val TS_IV            = "GbMJtUeB9hGzskmz"
        private const val DEFAULT_EMAIL    = "atarsercan2@gmail.com"
        private const val DEFAULT_PASSWORD    = "Yarrakadam"
        private const val DEFAULT_DEVICE_UUID = "c9a8d7df-6401-4bbc-97b5-6630cc1470e1"
        private const val ASIZ_HASH           = "ZZzIUo5Wrw1T1WMwjybNwg=="
        private const val GLG1_KEY         = "0Ae0+Zxj5pITIE38f+LqpRGO1IQjj9NytSciDvSew+oNq7T6dGsHASlMl+O8DUxu"
        private const val ORMOX_ROKS       = "D8C42BC6CD20C00E85659003F62B1F4A7A882DCB"
        private const val USER_AGENT       = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:80.0) Gecko/20100101 Firefox/79.0"

        private const val VOD_FILM_API     = "https://abibigitya.xyz/api/film/filmmakinesi/filmapi.php"
        private const val VOD_FILM_SEARCH  = "https://abibigitya.xyz/api/film/filmmakinesi/search.php"
        private const val VOD_DIZI_API     = "https://abibigitya.xyz/api/dizi/dizipal/diziapi.php"
        private const val VOD_DIZI_SEARCH  = "https://abibigitya.xyz/api/dizi/dizipal/dizisearch.php"

        private const val REC_HMAC_KEY     = "3508611138826751fdf77beaa6f93eb93fd27e6a5acb910e7aad22665513dd6e"
        private const val REC_AES_KEY_HEX  = "666482389dc76bfa57068407418f7dac9f6c14b6868856b169165b9fac7d812e"
    }

    private val fetchMutex = Mutex()
    private var runningDeferred: Deferred<List<ShadowChannelItem>>? = null
    private var cachedChannels: List<ShadowChannelItem>? = null
    private var lastFetchTime = 0L
    private var serverClockOffset = 0L

    private val recJwtMutex = Mutex()
    @Volatile
    private var cachedRecJwt: String? = null
    @Volatile
    private var recJwtExp: Long = 0L

    // ---- Cryptography Helpers ----
    private fun decryptAesCbc(cipherTextB64: String, key: ByteArray, iv: ByteArray): String {
        val cipherBytes = Base64.decode(cipherTextB64.trim(), Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
    }

    private fun generateTimestampToken(offsetMs: Long = 0L): String {
        val nowStr = (System.currentTimeMillis() + offsetMs + serverClockOffset).toString()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(TS_KEY.toByteArray(StandardCharsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(TS_IV.toByteArray(StandardCharsets.UTF_8))
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(nowStr.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun sha256Hex(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private suspend fun getRecTvJwt(verifyUrl: String): String? {
        val now = System.currentTimeMillis() / 1000L
        val currentJwt = cachedRecJwt
        if (currentJwt != null && now < recJwtExp - 300) {
            return currentJwt
        }

        return recJwtMutex.withLock {
            val currentNow = System.currentTimeMillis() / 1000L
            val recheckJwt = cachedRecJwt
            if (recheckJwt != null && currentNow < recJwtExp - 300) {
                return@withLock recheckJwt
            }

            try {
                val ts = currentNow.toString()
                val nonce = UUID.randomUUID().toString()
                val body = "{}"
                val bodyHash = sha256Hex(body)
                val path = if (verifyUrl.contains("/api/")) "/api/" + verifyUrl.substringAfter("/api/") else "/api/attest/verify"
                val msg = "POST\n$path\n$ts\n$nonce\n$bodyHash"
                val sig = hmacSha256Hex(REC_HMAC_KEY, msg)

                val headers = mapOf(
                    "User-Agent" to "googleusercontent",
                    "Referer" to "https://twitter.com/",
                    "X-Timestamp" to ts,
                    "X-Nonce" to nonce,
                    "X-Signature" to sig,
                    "X-App-Version" to "157",
                    "X-Client-Id" to "rectv-android",
                    "Content-Type" to "application/json"
                )
                val res = app.post(
                    verifyUrl,
                    headers = headers,
                    requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                val respMap = AppUtils.tryParseJson<Map<String, Any>>(res.text)
                val token = respMap?.get("jwt")?.toString()
                if (!token.isNullOrEmpty()) {
                    cachedRecJwt = token
                    recJwtExp = currentNow + 7000L
                    token
                } else null
            } catch (e: Exception) {
                Log.e(name, "Failed to fetch RecTV JWT: ${e.message}")
                cachedRecJwt
            }
        }
    }

    private fun decryptRecAesGcm(encUrl: String): String? {
        return try {
            val cleaned = encUrl.replace("\\/", "/").replace("%2F", "/").replace("%2B", "+").trim()
            val raw = Base64.decode(cleaned, Base64.DEFAULT)
            if (raw.size < 28) return null

            val iv = raw.copyOfRange(0, 12)
            val cipherTextAndTag = raw.copyOfRange(12, raw.size)

            val keyBytes = ByteArray(REC_AES_KEY_HEX.length / 2)
            for (i in keyBytes.indices) {
                val index = i * 2
                keyBytes[i] = REC_AES_KEY_HEX.substring(index, index + 2).toInt(16).toByte()
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            val decrypted = cipher.doFinal(cipherTextAndTag)
            String(decrypted, StandardCharsets.UTF_8).trim()
        } catch (e: Exception) {
            Log.e(name, "Failed to decrypt RecTV enc_url: ${e.message}")
            null
        }
    }

    private fun getApiUrl(): String = DEFAULT_API_URL

    private fun buildQueryString(pairs: List<Pair<String, String>>): String {
        return pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    private suspend fun syncServerClockOffset() {
        try {
            val head = app.head(DEFAULT_API_URL)
            val dateStr = head.headers["Date"] ?: head.headers["date"]
            if (!dateStr.isNullOrBlank()) {
                val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                format.timeZone = TimeZone.getTimeZone("GMT")
                val serverDate = format.parse(dateStr)
                if (serverDate != null) {
                    serverClockOffset = serverDate.time - System.currentTimeMillis()
                    Log.d(name, "Synchronized server clock offset: ${serverClockOffset}ms")
                }
            }
        } catch (e: Exception) {
            Log.w(name, "Failed to sync server clock offset: ${e.message}")
        }
    }

    private suspend fun postApiRequest(
        url: String,
        params: List<Pair<String, String>>
    ): String? {
        val simgeBytes = SIMGE.toByteArray(StandardCharsets.UTF_8)
        val imgeBytes = IMGE.toByteArray(StandardCharsets.UTF_8)

        for (attempt in 0..2) {
            try {
                if (attempt == 1 && serverClockOffset == 0L) {
                    syncServerClockOffset()
                }
                val tsOffset = when (attempt) {
                    2 -> -30_000L
                    else -> 0L
                }
                val tsToken = generateTimestampToken(tsOffset)
                val formPairs = params.toMutableList()
                formPairs.removeAll { it.first == "Authorization" }
                formPairs.add("Authorization" to tsToken)

                val bodyStr = buildQueryString(formPairs)
                val requestBody = bodyStr.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())

                val headers = mapOf(
                    "X-Requested-With" to "com.golge.golgetv2",
                    "User-Agent" to USER_AGENT,
                    "Authorization" to "Bearer $tsToken",
                    "email" to DEFAULT_EMAIL,
                    "password" to DEFAULT_PASSWORD
                )

                val res = app.post(url, headers = headers, requestBody = requestBody)
                val rawText = res.text.trim()
                if (rawText.length > 50) {
                    return decryptAesCbc(rawText, simgeBytes, imgeBytes)
                } else {
                    Log.w(name, "API response too short (${rawText.length}), retrying attempt $attempt")
                }
            } catch (e: Exception) {
                Log.w(name, "API request attempt $attempt failed: ${e.message}")
                if (attempt == 0) {
                    syncServerClockOffset()
                }
            }
        }
        return null
    }

    private suspend fun executeFetchChannels(): List<ShadowChannelItem> {
        val params = listOf(
            "ormoxRoks" to ORMOX_ROKS,
            "ormxArmegedEryxc" to "",
            "uuid" to DEFAULT_DEVICE_UUID,
            "asize" to ASIZ_HASH,
            "serverurl" to BOOTSTRAP_URL,
            "glg1Key" to GLG1_KEY,
            "email" to DEFAULT_EMAIL,
            "password" to DEFAULT_PASSWORD
        )
        val decrypted = postApiRequest(getApiUrl(), params)
        if (decrypted != null) {
            val parsed = AppUtils.tryParseJson<ShadowChannelResponse>(decrypted)
            val list = parsed?.channels ?: emptyList()
            if (list.isNotEmpty()) {
                Log.d(name, "fetchMainChannels: Successfully loaded ${list.size} channels")
                cachedChannels = list
                lastFetchTime = System.currentTimeMillis()
                return list
            }
        }
        return cachedChannels ?: emptyList()
    }

    // ---- Live TV & Playlist APIs ----
    private suspend fun fetchMainChannels(): List<ShadowChannelItem> {
        val now = System.currentTimeMillis()
        val cached = cachedChannels
        if (!cached.isNullOrEmpty() && (now - lastFetchTime) < 300_000) {
            return cached
        }

        val deferred = fetchMutex.withLock {
            val recheckCached = cachedChannels
            if (!recheckCached.isNullOrEmpty() && (System.currentTimeMillis() - lastFetchTime) < 300_000) {
                return recheckCached
            }
            val existing = runningDeferred
            if (existing != null && existing.isActive) {
                existing
            } else {
                val newDeferred = CoroutineScope(Dispatchers.IO).async {
                    executeFetchChannels()
                }
                runningDeferred = newDeferred
                newDeferred
            }
        }

        return try {
            deferred.await()
        } catch (e: Exception) {
            Log.e(name, "Error awaiting channel fetch: ${e.message}", e)
            cachedChannels ?: emptyList()
        }
    }

    private suspend fun fetchSubPlaylist(linkId: String): List<ShadowSubPlaylistItem> {
        val params = listOf(
            "ormoxRoks" to ORMOX_ROKS,
            "qOyOxSzVyL" to linkId,
            "tICFQdmhzR" to "",
            "email" to DEFAULT_EMAIL,
            "password" to DEFAULT_PASSWORD
        )
        val decrypted = postApiRequest(getApiUrl(), params)
        if (decrypted != null) {
            val parsed = AppUtils.tryParseJson<ShadowSubPlaylistWrapper>(decrypted)
            val items = parsed?.list?.items ?: emptyList()
            Log.d(name, "fetchSubPlaylist: Loaded ${items.size} items for linkId $linkId")
            return items
        }
        return emptyList()
    }

    // ---- Main Page Configuration ----
    override val mainPage = mainPageOf(
        "spor_kanallari"       to "Spor Kanalları",
        "canli_maclar"         to "Canlı Maç Yayınları",
        "ulusal_tv"            to "Ulusal ve Canlı TV",
        "haber_kanallari"      to "Haber Kanalları",
        "sinema_kanallari"     to "Sinema Kanalları",
        "belgesel_kanallari"   to "Belgesel Kanalları",
        "cocuk_kanallari"      to "Çocuk ve Çizgi Dizi",
        "dunya_kanallari"      to "Dünya Kanalları (World)",
        "ozel_paneller"        to "Özel Paneller (D-Smart, Tabii, RC)",
        "son_filmler"          to "Son Eklenen Filmler",
        "son_diziler"          to "Son Eklenen Diziler"
    )

    private fun normalizeCat(cat: String?): String {
        if (cat == null) return ""
        return cat.uppercase()
            .replace("İ", "I")
            .replace("I", "I")
            .replace("Ç", "C")
            .replace("Ş", "S")
            .replace("Ğ", "G")
            .replace("Ü", "U")
            .replace("Ö", "O")
            .trim()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val mapper = jacksonObjectMapper()

        if (request.data == "son_filmler") {
            val films = try {
                val url = if (page <= 1) VOD_FILM_API else "$VOD_FILM_API?page=$page"
                val res = app.get(url, headers = mapOf("User-Agent" to USER_AGENT))
                AppUtils.tryParseJson<List<ShadowVodItem>>(res.text) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val list = films.mapNotNull { film ->
                val title = film.title ?: return@mapNotNull null
                val pass = ShadowPassData(type = "vod_film", vodItem = film)
                newMovieSearchResponse(title, mapper.writeValueAsString(pass), TvType.Movie) {
                    this.posterUrl = film.image
                    this.year = film.year?.toIntOrNull()
                }
            }
            return newHomePageResponse(request.name, list)
        }

        if (request.data == "son_diziler") {
            val diziler = try {
                val url = if (page <= 1) VOD_DIZI_API else "$VOD_DIZI_API?page=$page"
                val res = app.get(url, headers = mapOf("User-Agent" to USER_AGENT))
                AppUtils.tryParseJson<List<ShadowVodItem>>(res.text) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val list = diziler.mapNotNull { dizi ->
                val title = dizi.title ?: return@mapNotNull null
                val pass = ShadowPassData(type = "vod_dizi", vodItem = dizi)
                newTvSeriesSearchResponse(title, mapper.writeValueAsString(pass), TvType.TvSeries) {
                    this.posterUrl = dizi.image
                    this.year = dizi.year?.toIntOrNull()
                }
            }
            return newHomePageResponse(request.name, list)
        }

        val allChannels = fetchMainChannels()
        val filtered = when (request.data) {
            "spor_kanallari" -> allChannels.filter {
                normalizeCat(it.kategori) == "SPOR" && !it.player.equals("m3u", ignoreCase = true)
            }
            "canli_maclar" -> allChannels.filter {
                normalizeCat(it.kategori) == "SPOR" && it.player.equals("m3u", ignoreCase = true)
            }
            "ulusal_tv" -> allChannels.filter {
                val cat = normalizeCat(it.kategori)
                (cat == "CANLI" || cat == "ULUSAL") && !it.player.equals("m3u", ignoreCase = true)
            }
            "haber_kanallari" -> allChannels.filter {
                normalizeCat(it.kategori) == "HABER"
            }
            "sinema_kanallari" -> allChannels.filter {
                val cat = normalizeCat(it.kategori)
                cat.startsWith("SINEMA") || cat.contains("SINEMA") || cat.contains("FILM")
            }
            "belgesel_kanallari" -> allChannels.filter {
                normalizeCat(it.kategori) == "BELGESEL"
            }
            "cocuk_kanallari" -> allChannels.filter {
                val cat = normalizeCat(it.kategori)
                cat.contains("COCUK")
            }
            "dunya_kanallari" -> allChannels.filter {
                normalizeCat(it.kategori) == "WORLD"
            }
            "ozel_paneller" -> allChannels.filter {
                normalizeCat(it.kategori) == "PANELLER"
            }
            else -> allChannels
        }

        val homeItems = filtered.mapNotNull { ch ->
            val title = ch.isim ?: return@mapNotNull null
            val isSubPlaylist = ch.player.equals("m3u", ignoreCase = true)
            val pass = ShadowPassData(type = if (isSubPlaylist) "m3u" else "channel", channel = ch)
            val dataStr = mapper.writeValueAsString(pass)

            if (isSubPlaylist) {
                newTvSeriesSearchResponse(title, dataStr, TvType.TvSeries) {
                    this.posterUrl = ch.resim
                }
            } else {
                newLiveSearchResponse(title, dataStr, TvType.Live) {
                    this.posterUrl = ch.resim
                }
            }
        }

        return newHomePageResponse(request.name, homeItems)
    }

    // ---- Search ----
    override suspend fun search(query: String): List<SearchResponse> {
        val mapper = jacksonObjectMapper()
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.trim().lowercase()

        // 1. Live Channel Search
        try {
            val allChannels = fetchMainChannels()
            val matchedChannels = allChannels.filter { ch ->
                ch.isim?.lowercase()?.contains(cleanQuery) == true
            }
            matchedChannels.forEach { ch ->
                val title = ch.isim ?: return@forEach
                val isSub = ch.player.equals("m3u", ignoreCase = true)
                val pass = ShadowPassData(type = if (isSub) "m3u" else "channel", channel = ch)
                val dataStr = mapper.writeValueAsString(pass)
                if (isSub) {
                    results.add(newTvSeriesSearchResponse(title, dataStr, TvType.TvSeries) {
                        this.posterUrl = ch.resim
                    })
                } else {
                    results.add(newLiveSearchResponse(title, dataStr, TvType.Live) {
                        this.posterUrl = ch.resim
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error searching channels: ${e.message}")
        }

        // 2. Film Search
        try {
            val res = app.get("$VOD_FILM_SEARCH?search=${Uri.encode(query)}", headers = mapOf("User-Agent" to USER_AGENT))
            val films = AppUtils.tryParseJson<List<ShadowVodItem>>(res.text) ?: emptyList()
            films.forEach { film ->
                val title = film.title ?: return@forEach
                val pass = ShadowPassData(type = "vod_film", vodItem = film)
                results.add(newMovieSearchResponse(title, mapper.writeValueAsString(pass), TvType.Movie) {
                    this.posterUrl = film.image
                    this.year = film.year?.toIntOrNull()
                } )
            }
        } catch (e: Exception) {
            Log.e(name, "Error searching films: ${e.message}")
        }

        // 3. Dizi Search
        try {
            val res = app.get("$VOD_DIZI_SEARCH?search=${Uri.encode(query)}", headers = mapOf("User-Agent" to USER_AGENT))
            val diziler = AppUtils.tryParseJson<List<ShadowVodItem>>(res.text) ?: emptyList()
            diziler.forEach { dizi ->
                val title = dizi.title ?: return@forEach
                val pass = ShadowPassData(type = "vod_dizi", vodItem = dizi)
                results.add(newTvSeriesSearchResponse(title, mapper.writeValueAsString(pass), TvType.TvSeries) {
                    this.posterUrl = dizi.image
                    this.year = dizi.year?.toIntOrNull()
                })
            }
        } catch (e: Exception) {
            Log.e(name, "Error searching diziler: ${e.message}")
        }

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---- Load Details ----
    override suspend fun load(url: String): LoadResponse? {
        val mapper = jacksonObjectMapper()
        val passData = AppUtils.tryParseJson<ShadowPassData>(url)

        // 1. M3U Sub-Playlist (e.g. CANLI MAÇLAR 1-11, D-Smart, RC Panel)
        if (passData?.type == "m3u" || passData?.channel?.player.equals("m3u", ignoreCase = true)) {
            val ch = passData?.channel ?: return null
            val linkId = ch.link ?: ch.id ?: return null
            val items = fetchSubPlaylist(linkId)

            val episodes = items.mapIndexed { index, subItem ->
                val subPass = ShadowPassData(type = "sub_item", subItem = subItem)
                val subDataStr = mapper.writeValueAsString(subPass)
                newEpisode(subDataStr) {
                    this.name = subItem.title ?: "Yayın ${index + 1}"
                    this.episode = index + 1
                    this.posterUrl = subItem.thumb_square ?: ch.resim
                    this.description = subItem.group
                }
            }

            return newTvSeriesLoadResponse(ch.isim ?: "Oynatma Listesi", url, TvType.TvSeries, episodes) {
                this.posterUrl = ch.resim
                this.plot = "${ch.isim} canlı yayın ve maç kanalları listesi."
                this.tags = listOfNotNull(ch.kategori, "Canlı Liste")
            }
        }

        // 2. Direct Live Channel
        if (passData?.type == "channel" || passData?.channel != null) {
            val ch = passData?.channel ?: return null
            return newLiveStreamLoadResponse(ch.isim ?: "Canlı Kanal", url, url) {
                this.posterUrl = ch.resim
                this.plot = "Kategori: ${ch.kategori ?: "Canlı"}"
                this.tags = listOfNotNull(ch.kategori, "Canlı TV")
            }
        }

        // 3. VOD Film
        if (passData?.type == "vod_film") {
            val film = passData.vodItem ?: return null
            val detailUrl = film.detailUrl
            val detail = if (!detailUrl.isNullOrEmpty()) {
                try {
                    val res = app.get(detailUrl, headers = mapOf("User-Agent" to USER_AGENT))
                    AppUtils.tryParseJson<ShadowVodDetail>(res.text)
                } catch (e: Exception) {
                    null
                }
            } else null

            val title = detail?.film_name ?: film.title ?: "Film"
            val plot = detail?.film_description
            val poster = detail?.film_img ?: film.image

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = film.year?.toIntOrNull()
                this.score = Score.from10((detail?.imdb_rating ?: film.imdb)?.trim())
                this.tags = listOfNotNull(detail?.tur, film.dil)
            }
        }

        // 4. VOD Dizi
        if (passData?.type == "vod_dizi") {
            val dizi = passData.vodItem ?: return null
            val detailUrl = dizi.detailUrl
            val detail = if (!detailUrl.isNullOrEmpty()) {
                try {
                    val res = app.get(detailUrl, headers = mapOf("User-Agent" to USER_AGENT))
                    AppUtils.tryParseJson<ShadowVodDetail>(res.text)
                } catch (e: Exception) {
                    null
                }
            } else null

            val title = detail?.dizi_adi ?: dizi.title ?: "Dizi"
            val episodes = mutableListOf<Episode>()
            val numberRegex = Regex("\\d+")

            detail?.dizi_bolumler?.forEachIndexed { index, bolum ->
                val bUrl = bolum.bolum_url ?: bolum.bolum ?: return@forEachIndexed
                val bName = bolum.bolum_adi ?: bolum.isim ?: "Bölüm ${index + 1}"
                val seasonNum = bolum.sezon?.toIntOrNull() ?: numberRegex.find(bName)?.value?.toIntOrNull() ?: 1
                val epNum = bolum.bolum?.toIntOrNull() ?: (index + 1)

                val epPass = ShadowPassData(type = "vod_ep", directUrl = bUrl)
                episodes.add(newEpisode(mapper.writeValueAsString(epPass)) {
                    this.name = bName
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = detail.dizi_resim ?: dizi.image
                })
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = detail?.dizi_resim ?: dizi.image
                this.plot = detail?.dizi_aciklama
                this.year = dizi.year?.toIntOrNull()
            }
        }


        return null
    }

    // ---- Stream Links Resolution ----
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val passData = AppUtils.tryParseJson<ShadowPassData>(data)

        // Case A: Sub-Playlist Item (e.g. specific live match)
        if (passData?.subItem != null) {
            val item = passData.subItem
            val rawStream = item.url ?: item.media_url ?: return false
            val headers = buildHeaders(
                userAgent = item.userAgent,
                h1K = item.h1Key, h1V = item.h1Val,
                h2K = item.h2Key, h2V = item.h2Val,
                h3K = item.h3Key, h3V = item.h3Val,
                h4K = item.h4Key, h4V = item.h4Val,
                h5K = item.h5Key, h5V = item.h5Val
            )
            return resolveAndEmitStream(item.title ?: this.name, rawStream, headers, subtitleCallback, callback)
        }

        // Case B: Direct Channel Item
        if (passData?.channel != null) {
            val ch = passData.channel
            val rawStream = ch.link ?: return false
            val headers = buildHeaders(
                userAgent = ch.userAgent,
                cookie = ch.cookie,
                h1K = ch.h1Key, h1V = ch.h1Val,
                h2K = ch.h2Key, h2V = ch.h2Val,
                h3K = ch.h3Key, h3V = ch.h3Val,
                h4K = ch.h4Key, h4V = ch.h4Val,
                h5K = ch.h5Key, h5V = ch.h5Val
            )
            return resolveAndEmitStream(ch.isim ?: this.name, rawStream, headers, subtitleCallback, callback)
        }

        // Case C: VOD Film Item
        if (passData?.vodItem != null) {
            val film = passData.vodItem
            val detailUrl = film.detailUrl ?: return false
            return try {
                val res = app.get(detailUrl, headers = mapOf("User-Agent" to USER_AGENT))
                val detail = AppUtils.tryParseJson<ShadowVodDetail>(res.text)
                val iframeUrl = detail?.iframe_url ?: return false

                val iframeRes = app.get(iframeUrl, headers = mapOf("User-Agent" to USER_AGENT))
                val streamResp = AppUtils.tryParseJson<ShadowStreamResponse>(iframeRes.text)
                val streamUrl = streamResp?.stream ?: iframeRes.text

                resolveAndEmitStream(film.title ?: this.name, streamUrl, emptyMap(), subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "Failed to resolve film stream: ${e.message}")
                false
            }
        }

        // Case D: VOD Episode
        if (passData?.type == "vod_ep" && !passData.directUrl.isNullOrEmpty()) {
            val epUrl = passData.directUrl
            return try {
                val res = app.get(epUrl, headers = mapOf("User-Agent" to USER_AGENT))
                val streamResp = AppUtils.tryParseJson<ShadowStreamResponse>(res.text)
                val streamUrl = streamResp?.stream ?: res.text
                resolveAndEmitStream(this.name, streamUrl, emptyMap(), subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "Failed to resolve episode stream: ${e.message}")
                false
            }
        }

        // Fallback: direct URL string
        if (data.startsWith("http://") || data.startsWith("https://") || data.startsWith("golge")) {
            return resolveAndEmitStream(this.name, data, emptyMap(), subtitleCallback, callback)
        }

        return false
    }

    private fun buildHeaders(
        userAgent: String?,
        cookie: String? = null,
        h1K: String? = null, h1V: String? = null,
        h2K: String? = null, h2V: String? = null,
        h3K: String? = null, h3V: String? = null,
        h4K: String? = null, h4V: String? = null,
        h5K: String? = null, h5V: String? = null
    ): Map<String, String> {
        val map = mutableMapOf<String, String>()
        map["User-Agent"] = if (!userAgent.isNullOrBlank()) userAgent else USER_AGENT

        if (!cookie.isNullOrBlank()) {
            map["Cookie"] = cookie
        }

        val pairs = listOf(h1K to h1V, h2K to h2V, h3K to h3V, h4K to h4V, h5K to h5V)
        for ((k, v) in pairs) {
            if (!k.isNullOrBlank() && k != "0" && !v.isNullOrBlank() && v != "0") {
                map[k] = v
            }
        }
        return map
    }

    private fun extractCloseloadStream(html: String): String? {
        return try {
            val fileVarMatcher = Pattern.compile("""sources:\s*\[\{file:\s*([a-zA-Z0-9_]+)""").matcher(html)
            if (!fileVarMatcher.find()) return null
            val targetVar = fileVarMatcher.group(1)

            val callMatcher = Pattern.compile("""var\s+${targetVar}\s*=\s*([a-zA-Z0-9_]+)\s*\(\s*(\[[^\]]+\])\s*\);""").matcher(html)
            if (!callMatcher.find()) return null
            val funcName = callMatcher.group(1)
            val arrArg = callMatcher.group(2)

            val fnMatcher = Pattern.compile("""function\s+${funcName}\s*\([^\)]*\)\s*\{[\s\S]*?return\s+[a-zA-Z0-9_]+;\s*\}""").matcher(html)
            if (!fnMatcher.find()) return null
            val fnBody = fnMatcher.group(0)

            val atobPoly = """
                function atob(s) {
                    var b = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    var o = '', c1, c2, c3, e1, e2, e3, e4, i = 0;
                    s = s.replace(/[^A-Za-z0-9+/=]/g, '');
                    while (i < s.length) {
                        e1 = b.indexOf(s.charAt(i++));
                        e2 = b.indexOf(s.charAt(i++));
                        e3 = b.indexOf(s.charAt(i++));
                        e4 = b.indexOf(s.charAt(i++));
                        c1 = (e1 << 2) | (e2 >> 4);
                        c2 = ((e2 & 15) << 4) | (e3 >> 2);
                        c3 = ((e3 & 3) << 6) | e4;
                        o += String.fromCharCode(c1);
                        if (e3 != 64) o += String.fromCharCode(c2);
                        if (e4 != 64) o += String.fromCharCode(c3);
                    }
                    return o;
                }
            """.trimIndent()

            val jsCode = "$atobPoly\n$fnBody\n$funcName($arrArg);"

            val rhino = Context.enter()
            rhino.optimizationLevel = -1
            val scope: Scriptable = rhino.initStandardObjects()
            val result = rhino.evaluateString(scope, jsCode, "closeload", 1, null)?.toString()
            Context.exit()

            if (result?.startsWith("http://") == true || result?.startsWith("https://") == true) {
                result
            } else null
        } catch (e: Exception) {
            Log.e(name, "Error extracting closeload stream: ${e.message}")
            try { Context.exit() } catch (_: Exception) {}
            null
        }
    }

    private suspend fun extractPlayerjsStream(
        targetUrl: String,
        referer: String,
        sourceTitle: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val embedHost = Uri.parse(targetUrl).host ?: return false
            val embedHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to if (referer.isNotEmpty()) referer else "https://www.dizipal.bid/"
            )
            val embedHtml = app.get(targetUrl, headers = embedHeaders).text

            val subMatcher = Pattern.compile(""""subtitle"\s*:\s*"([^"]+)"""").matcher(embedHtml)
            if (subMatcher.find()) {
                val subRaw = subMatcher.group(1)
                subRaw?.split(",")?.forEach { item ->
                    val trimmed = item.trim()
                    val lang = if (trimmed.startsWith("[")) trimmed.substringAfter("[").substringBefore("]") else "Türkçe"
                    val sUrl = if (trimmed.startsWith("[")) trimmed.substringAfter("]") else trimmed
                    if (sUrl.startsWith("http")) {
                        subtitleCallback.invoke(newSubtitleFile(lang, sUrl))
                    }
                }
            }

            val dlMatcher = Pattern.compile("""(/dl\?op=[^'"]+)""").matcher(embedHtml)
            if (dlMatcher.find()) {
                val dlPath = dlMatcher.group(1)
                val dlUrl = "https://$embedHost$dlPath"
                val dlHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to targetUrl,
                    "Origin" to "https://$embedHost",
                    "X-Requested-With" to "XMLHttpRequest"
                )
                val dlResp = app.get(dlUrl, headers = dlHeaders).text
                val dlJson = AppUtils.tryParseJson<Map<String, Any>>(dlResp)
                val streamUrl = (dlJson?.get("url") as? String)?.trim()

                if (!streamUrl.isNullOrEmpty()) {
                    val streamHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://$embedHost/"
                    )
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = sourceTitle,
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.headers = streamHeaders
                            this.referer = "https://$embedHost/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(name, "Error in extractPlayerjsStream: ${e.message}")
            false
        }
    }

    private fun cleanWorkerProxies(url: String): String {
        var cleaned = url.trim()
        cleaned = cleaned.replace(Regex("""https?://[^/]*lagaluga[^/]*workers\.dev/"""), "")
        cleaned = cleaned.replace(Regex("""https?://corsproxy\.org/\?"""), "")
        return cleaned
    }

    private suspend fun resolveGolge15(
        sourceTitle: String,
        rawUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            var clean = cleanWorkerProxies(rawUrl.substring(10))
            if (clean.startsWith("https//")) clean = clean.replaceFirst("https//", "https://")
            else if (clean.startsWith("http//")) clean = clean.replaceFirst("http//", "http://")

            val parts = if (clean.contains("%7C")) clean.split("%7C") else clean.split("|")
            val configUrl = parts.getOrNull(0)?.trim() ?: return false
            val v6 = parts.getOrNull(1)?.trim() ?: ""
            val targetIp = parts.getOrNull(3)?.trim() ?: ""

            val reqHeaders = mapOf("User-Agent" to USER_AGENT)
            val confRes = app.get(configUrl, headers = reqHeaders)
            val confMap = AppUtils.tryParseJson<Map<String, Any>>(confRes.text) ?: return false

            var apiUrl = confMap["api_url"]?.toString() ?: return false
            if (v6.isNotEmpty() && !apiUrl.startsWith("http")) {
                apiUrl = v6 + apiUrl
            }

            val jsonData = confMap["json_data"]
            val jsonStr = if (jsonData is String) jsonData else jacksonObjectMapper().writeValueAsString(jsonData)
            val proxyUrl = confMap["proxyurl"]?.toString() ?: return false
            val medyaUrl = confMap["medyaurl"]?.toString() ?: return false

            @Suppress("UNCHECKED_CAST")
            val hMap = confMap["headers"] as? Map<String, String> ?: emptyMap()
            val apiHeaders = mutableMapOf(
                "Content-Type" to (hMap["content-type"] ?: "application/json; charset=utf-8"),
                "User-Agent" to (hMap["user-agent"] ?: "Rokkr/1.8.3 (android)"),
                "Referer" to (hMap["referer"] ?: "https://www.dezor.net/"),
                "Origin" to (hMap["origin"] ?: "https://www.dezor.net"),
                "X-Requested-With" to (hMap["x-requested-with"] ?: "com.golge.golgetv2")
            )
            if (hMap.containsKey("x-forwarded-for")) {
                apiHeaders["X-Forwarded-For"] = hMap["x-forwarded-for"]!!
            }

            val apiRes = app.post(
                apiUrl,
                headers = apiHeaders,
                requestBody = jsonStr.toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            val apiObj = AppUtils.tryParseJson<Map<String, Any>>(apiRes.text)
            val addonSig = apiObj?.get("addonSig")?.toString() ?: ""

            val fetchBody = jacksonObjectMapper().writeValueAsString(
                mapOf("language" to "tr", "region" to "TR", "url" to medyaUrl)
            )
            val fetchHeaders = mapOf(
                "watched-sig" to addonSig,
                "mediahubmx-signature" to addonSig,
                "user-agent" to (hMap["user-agent"] ?: USER_AGENT),
                "X-Requested-With" to "com.golge.golgetv",
                "Content-Type" to "application/json"
            )

            val proxyRes = app.post(
                proxyUrl,
                headers = fetchHeaders,
                requestBody = fetchBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            val proxyList = AppUtils.tryParseJson<List<Map<String, Any>>>(proxyRes.text)
            val resolvedUrl = proxyList?.firstOrNull()?.get("url")?.toString() ?: return false

            val parsedUri = Uri.parse(resolvedUrl)
            val origHost = parsedUri.authority ?: parsedUri.host ?: ""

            val finalStreamUrl: String
            val playHeaders: Map<String, String>
            if (targetIp.isNotBlank()) {
                val path = parsedUri.encodedPath ?: ""
                val query = parsedUri.encodedQuery
                finalStreamUrl = "http://$targetIp$path" + (if (query.isNullOrBlank()) "" else "?$query")
                playHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Host" to origHost
                )
            } else {
                finalStreamUrl = resolvedUrl
                playHeaders = mapOf("User-Agent" to USER_AGENT)
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = sourceTitle,
                    url = finalStreamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = playHeaders
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        } catch (e: Exception) {
            Log.e(name, "Error in resolveGolge15: ${e.message}", e)
            return false
        }
    }

    private suspend fun resolveGolge26(
        sourceTitle: String,
        rawUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            var clean = cleanWorkerProxies(rawUrl.substring(10))
            if (clean.startsWith("https//")) clean = clean.replaceFirst("https//", "https://")
            else if (clean.startsWith("http//")) clean = clean.replaceFirst("http//", "http://")

            val split = if (clean.contains("%7C")) clean.split("%7C") else clean.split("|")
            val recUrl = split.getOrNull(0)?.trim() ?: return false

            val res = app.get(recUrl, headers = mapOf("User-Agent" to USER_AGENT))
            val recConfig = AppUtils.tryParseJson<Map<String, Any>>(res.text) ?: return false

            val unlockUrl = recConfig["unlockurl"]?.toString() ?: return false
            val verifyUrl = recConfig["verifyurl"]?.toString()
                ?: (recConfig["baseurl"]?.toString()?.let { "${it.trimEnd('/')}/attest/verify" })
                ?: "https://a.prectv71.lol/api/attest/verify"

            val jwt = getRecTvJwt(verifyUrl) ?: return false

            val deviceId = UUID.randomUUID().toString()
            val postBody = "device_id=$deviceId"
            val unlockPath = "/" + unlockUrl.substringAfter("://").substringAfter("/")

            val ts = (System.currentTimeMillis() / 1000L).toString()
            val nonce = UUID.randomUUID().toString()
            val bodyHash = sha256Hex(postBody)
            val msg = "POST\n$unlockPath\n$ts\n$nonce\n$bodyHash"
            val sig = hmacSha256Hex(REC_HMAC_KEY, msg)

            val unlockHeaders = mapOf(
                "User-Agent" to "googleusercontent",
                "Referer" to "https://twitter.com/",
                "X-Timestamp" to ts,
                "X-Nonce" to nonce,
                "X-Signature" to sig,
                "X-App-Version" to "156",
                "X-Client-Id" to "rectv-android",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Authorization" to "Bearer $jwt"
            )

            val unlockRes = app.post(
                unlockUrl,
                headers = unlockHeaders,
                requestBody = postBody.toRequestBody("application/x-www-form-urlencoded".toMediaType())
            )
            val unlockMap = AppUtils.tryParseJson<Map<String, Any>>(unlockRes.text)
            val encUrl = unlockMap?.get("enc_url")?.toString() ?: return false

            val decryptedStream = decryptRecAesGcm(encUrl) ?: return false
            Log.d(name, "Successfully resolved golge26 stream: $decryptedStream")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = sourceTitle,
                    url = decryptedStream,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        } catch (e: Exception) {
            Log.e(name, "Error in resolveGolge26: ${e.message}", e)
            return false
        }
    }

    private suspend fun resolveGolge19(
        sourceTitle: String,
        rawUrl: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            var clean = cleanWorkerProxies(rawUrl.substring(10))
            if (clean.startsWith("https//")) clean = clean.replaceFirst("https//", "https://")
            else if (clean.startsWith("http//")) clean = clean.replaceFirst("http//", "http://")

            val split = if (clean.contains("%7C")) clean.split("%7C") else clean.split("|")
            val targetUrl = split.getOrNull(0)?.trim() ?: return false
            val referer = split.getOrNull(1)?.trim() ?: ""
            val origin = split.getOrNull(2)?.trim() ?: "https://google.com/"

            val reqHeaders = headers.toMutableMap()
            if (referer.isNotEmpty()) reqHeaders["Referer"] = referer
            if (origin.isNotEmpty()) reqHeaders["Origin"] = origin
            reqHeaders["X-Requested-With"] = "com.pro.golgetv"
            if (!reqHeaders.containsKey("User-Agent")) reqHeaders["User-Agent"] = USER_AGENT

            val res = app.get(targetUrl, headers = reqHeaders)
            if (res.isSuccessful && res.text.contains("#EXTM3U")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = sourceTitle,
                        url = targetUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = reqHeaders
                        this.quality = Qualities.P1080.value
                    }
                )
                return true
            }
            return false
        } catch (e: Exception) {
            Log.w(name, "golge19 resolution failed: ${e.message}")
            return false
        }
    }

    private suspend fun resolveAndEmitStream(
        sourceTitle: String,
        rawUrl: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var url = cleanWorkerProxies(rawUrl)
        if (url.isBlank()) return false

        // 1. Resolve `golge2://` (tvmarkaj / live stream obfuscation)
        if (url.startsWith("golge2://")) {
            try {
                var clean = cleanWorkerProxies(url.substring(9))
                if (clean.startsWith("https//")) clean = clean.replaceFirst("https//", "https://")
                else if (clean.startsWith("http//")) clean = clean.replaceFirst("http//", "http://")

                val split = if (clean.contains("%7C")) clean.split("%7C") else clean.split("|")
                val requestUrl = cleanWorkerProxies(split[0].trim())
                val pathyeni = if (split.size > 1 && split[1].isNotBlank()) split[1].trim() else "bc2b05d321cb80050c5d035a9daeb26d"
                val subdomain = if (split.size > 2 && split[2].isNotBlank()) split[2].trim() else "a"
                val host = if (split.size > 3 && split[3].isNotBlank()) split[3].trim() else null

                val reqHeaders = headers.toMutableMap()
                if (!reqHeaders.containsKey("User-Agent")) reqHeaders["User-Agent"] = USER_AGENT

                val resp = app.get(requestUrl, headers = reqHeaders).text
                val arr = AppUtils.tryParseJson<List<String>>(resp)
                if (arr != null && arr.size >= 6) {
                    val s0 = arr[0]
                    val s5 = arr[5]
                    val matcher = Pattern.compile("""atob\("([^"]+)"""").matcher(s0)
                    if (matcher.find()) {
                        val decodedDomain = String(Base64.decode(matcher.group(1), Base64.DEFAULT), StandardCharsets.UTF_8)
                        val idParam = if (requestUrl.contains("id=")) requestUrl.substringAfter("id=").substringBefore("&") else ""
                        val finalHost = host ?: "$subdomain$decodedDomain"
                        val finalStreamUrl = "https://$finalHost/$pathyeni/-/$idParam/playlist.m3u8$s5"

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = sourceTitle,
                                url = finalStreamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = reqHeaders
                                this.referer = reqHeaders["Referer"] ?: reqHeaders["referer"] ?: "https://izlemac529.sbs/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(name, "Failed to resolve golge2 stream: ${e.message}")
            }
            return false
        }

        // 2. Resolve `golge5://` (match center embed / dynamic links)
        if (url.startsWith("golge5://")) {
            val clean = url.substring(9)
            if (clean.contains("id=")) {
                val matchId = clean.substringAfter("id=").substringBefore("&")
                val origin = if (clean.contains("/wp-content")) clean.substringBefore("/wp-content") else "https://izlemac529.sbs"
                val tUrl = "$origin/t?id=$matchId"
                val rewritten = "golge2://$tUrl%7Ccefc8a875b06cc6da16ca7dd99157dee%7C%7Ce-aga-m.943411d29aa8b67a6485827229af8b16.sbs"
                return resolveAndEmitStream(sourceTitle, rewritten, headers, subtitleCallback, callback)
            } else {
                url = clean
            }
        }

        // 3. Resolve `golge15://` (WHATCHED & DEZOR / Rokkr proxy)
        if (url.startsWith("golge15://")) {
            val resolved = resolveGolge15(sourceTitle, url, callback)
            if (resolved) return true
        }

        // 4. Resolve `golge26://` (RC PANEL / RecTV GCM)
        if (url.startsWith("golge26://")) {
            val resolved = resolveGolge26(sourceTitle, url, callback)
            if (resolved) return true
        }

        // 5. Resolve `golge19://` (KECI SPOR)
        if (url.startsWith("golge19://")) {
            val resolved = resolveGolge19(sourceTitle, url, headers, callback)
            if (resolved) return true
        }

        // 6. Resolve Birazcik Canli Maclar origin (event.html?id=...)
        if (url.contains("event.html?id=", ignoreCase = true) || url.contains("/birazcik/", ignoreCase = true)) {
            val matchId = if (url.contains("id=")) url.substringAfter("id=").substringBefore("&").trim() else ""
            if (matchId.isNotEmpty() && !matchId.contains("/")) {
                val directHls = "https://andro.evrenesoglu101.click/checklist/$matchId.m3u8"
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = sourceTitle,
                        url = directHls,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = headers
                        this.quality = Qualities.P1080.value
                    }
                )
                return true
            }
        }

        // 7. Resolve generic `golge\d+://` (golge1, golge11, golge20, golge21, golge23, etc.)
        val golgeMatch = Regex("""^golge\d*://""").find(url)
        if (golgeMatch != null) {
            val clean = url.substring(golgeMatch.range.last + 1)
            val decoded = try {
                URLDecoder.decode(clean, "UTF-8")
            } catch (_: Exception) {
                clean
            }
            val parts = if (decoded.contains("%7C")) decoded.split("%7C") else decoded.split("|")
            val targetUrl = cleanWorkerProxies(parts.getOrNull(0)?.trim() ?: "")
            val referer = parts.getOrNull(1)?.trim() ?: ""
            val origin = parts.getOrNull(2)?.trim() ?: ""

            for (p in parts) {
                if (p.contains(".vtt", ignoreCase = true) || p.contains(".srt", ignoreCase = true)) {
                    val vttUrl = if (p.contains("idx=")) p.substringAfter("idx=").substringBefore("&") else p
                    if (vttUrl.startsWith("http")) {
                        subtitleCallback.invoke(newSubtitleFile("Türkçe", vttUrl.trim()))
                    }
                }
            }

            if (targetUrl.isNotEmpty()) {
                val reqHeaders = headers.toMutableMap()
                if (referer.isNotEmpty()) reqHeaders["Referer"] = referer
                if (origin.isNotEmpty()) reqHeaders["Origin"] = origin
                return resolveAndEmitStream(sourceTitle, targetUrl, reqHeaders, subtitleCallback, callback)
            }
            return false
        }

        // 4. Closeload resolver
        if (url.contains("closeload", ignoreCase = true)) {
            val embedHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to (headers["Referer"] ?: "https://filmmakinesi.to/")
            )
            try {
                val embedHtml = app.get(url, headers = embedHeaders).text
                val tracksMatcher = Pattern.compile("""tracks:\s*(\[[^\]]+\])""").matcher(embedHtml)
                if (tracksMatcher.find()) {
                    val tracksJson = tracksMatcher.group(1)
                    val arr = AppUtils.tryParseJson<List<Map<String, Any>>>(tracksJson)
                    arr?.forEach { track ->
                        val f = track["file"]?.toString()
                        val label = track["label"]?.toString() ?: "Türkçe"
                        if (!f.isNullOrEmpty() && f.startsWith("http")) {
                            subtitleCallback.invoke(newSubtitleFile(label, f))
                        }
                    }
                }

                val streamUrl = extractCloseloadStream(embedHtml)
                if (!streamUrl.isNullOrEmpty()) {
                    val streamHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://closeload.filmmakinesi.to/"
                    )
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = sourceTitle,
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.headers = streamHeaders
                            this.referer = "https://closeload.filmmakinesi.to/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return true
                }
            } catch (e: Exception) {
                Log.e(name, "Error resolving closeload: ${e.message}")
            }
        }

        // 5. Ag2m4 / Playerjs dl stream resolver
        if (url.contains("ag2m4", ignoreCase = true) || url.contains("/embed-", ignoreCase = true)) {
            val resolved = extractPlayerjsStream(url, headers["Referer"] ?: "", sourceTitle, subtitleCallback, callback)
            if (resolved) return true
        }

        // 6. Direct HTTP/HTTPS streams & redirect dispatchers (.php, /ch/, etc.)
        if (url.startsWith("http://") || url.startsWith("https://")) {
            var streamUrl = cleanWorkerProxies(url)
            val isDispatcher = streamUrl.contains(".php", ignoreCase = true) ||
                    streamUrl.contains("/ch/", ignoreCase = true) ||
                    streamUrl.contains("/panel/", ignoreCase = true)

            if (isDispatcher) {
                try {
                    val res = app.get(streamUrl, headers = headers, allowRedirects = false)
                    val location = res.headers["location"] ?: res.headers["Location"]
                    if (!location.isNullOrBlank()) {
                        val cleanLoc = cleanWorkerProxies(location.trim())
                        val resolvedLoc = if (cleanLoc.startsWith("http://") || cleanLoc.startsWith("https://")) {
                            cleanLoc
                        } else if (cleanLoc.startsWith("/")) {
                            val parsed = Uri.parse(streamUrl)
                            "${parsed.scheme}://${parsed.host}$cleanLoc"
                        } else {
                            cleanLoc
                        }
                        return resolveAndEmitStream(sourceTitle, resolvedLoc, headers, subtitleCallback, callback)
                    } else if (res.text.contains("#EXTM3U")) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = sourceTitle,
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = headers
                                this.referer = headers["Referer"] ?: headers["referer"] ?: ""
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return true
                    }
                } catch (e: Exception) {
                    Log.d(name, "Dispatcher check error: ${e.message}")
                }
            }

            val extractorLoaded = try {
                loadExtractor(streamUrl, headers["Referer"] ?: "", subtitleCallback, callback)
            } catch (_: Exception) {
                false
            }
            if (extractorLoaded) return true

            val linkType = if (streamUrl.contains(".mpd", ignoreCase = true)) {
                ExtractorLinkType.DASH
            } else if (streamUrl.contains(".mp4", ignoreCase = true)) {
                ExtractorLinkType.VIDEO
            } else {
                ExtractorLinkType.M3U8
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = sourceTitle,
                    url = streamUrl,
                    type = linkType
                ) {
                    this.headers = headers
                    this.referer = headers["Referer"] ?: headers["referer"] ?: ""
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        return false
    }
}
