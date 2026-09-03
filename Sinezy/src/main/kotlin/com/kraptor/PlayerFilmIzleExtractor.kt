// ! Bu araç @Kraptor123 tarafından | @kekikanime için yazılmıştır.
package com.kraptor

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import android.util.Base64
import android.util.Log
import java.nio.charset.Charset

open class PlayerFilmIzle : ExtractorApi() {
    override val name = "PlayerFilmIzle"
    override val mainUrl = "https://player.filmizle.in"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val extRef = mainUrl
            Log.d("kraptor_$name", "getUrl başladı - url: $url")
            
            // Sayfayı al
            val videoReq = app.get(url, referer = extRef).text
            Log.d("kraptor_$name", "Sayfa içeriği alındı, uzunluk: ${videoReq.length}")
            
            // 1. Altyazıları bul
            try {
                val regexSub = Regex(pattern = """playerjsSubtitle = "([^"]*)"""", options = setOf(RegexOption.IGNORE_CASE))
                val subYakala = regexSub.find(videoReq)?.groupValues?.get(1).toString()
                
                if (subYakala.isNotEmpty()) {
                    val subUrl = subYakala.substringAfter("]")
                    val subLang = subYakala.substringBefore("]").removePrefix("[")
                    
                    Log.d("kraptor_$name", "Altyazı bulundu - URL: $subUrl, Dil: $subLang")
                    
                    subtitleCallback(
                        SubtitleFile(
                            url = subUrl,
                            lang = subLang
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("kraptor_$name", "Altyazı bulma hatası: ${e.message}")
            }
            
            // 2. Ana video linkini bul - FirePlayer yöntemi
            try {
                val regex = Regex(pattern = """FirePlayer\|([^|]+)\|""", options = setOf(RegexOption.IGNORE_CASE))
                val data = regex.find(videoReq)?.groupValues?.get(1)
                
                if (!data.isNullOrEmpty()) {
                    Log.d("kraptor_$name", "FirePlayer verisi bulundu: $data")
                    
                    val urlPost = "https://player.filmizle.in/player/index.php?data=$data&do=getVideo"
                    
                    val getUrl = app.post(
                        urlPost, 
                        referer = extRef, 
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "User-Agent" to "Mozilla/5.0"
                        ),
                        data = mapOf("hash" to data, "r" to "")
                    ).text.replace("\\", "")
                    
                    Log.d("kraptor_$name", "Post yanıtı: $getUrl")
                    
                    // securedLink'i bul
                    val urlYakala = Regex(pattern = """"securedLink":"([^"]*)"""", options = setOf(RegexOption.IGNORE_CASE))
                    val m3u8 = urlYakala.find(getUrl)?.groupValues?.get(1).toString()
                    
                    if (m3u8.isNotEmpty() && m3u8.startsWith("http")) {
                        Log.d("kraptor_$name", "M3U8 linki bulundu: $m3u8")
                        
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = m3u8,
                                type = ExtractorLinkType.M3U8
                            ) {
                                quality = Qualities.Unknown.value
                                headers = mapOf(
                                    "Referer" to extRef,
                                    "User-Agent" to "Mozilla/5.0"
                                )
                            }
                        )
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e("kraptor_$name", "FirePlayer yöntemi hatası: ${e.message}")
            }
            
            // 3. Alternatif yöntem - Doğrudan M3U8 linki ara
            try {
                val m3u8Regex = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
                val m3u8Matches = m3u8Regex.findAll(videoReq).toList()
                
                if (m3u8Matches.isNotEmpty()) {
                    for (match in m3u8Matches) {
                        val m3u8Url = match.value
                        if (m3u8Url.isNotEmpty() && m3u8Url.startsWith("http")) {
                            Log.d("kraptor_$name", "Alternatif M3U8 linki bulundu: $m3u8Url")
                            
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${this.name} (Alt)",
                                    url = m3u8Url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    quality = Qualities.Unknown.value
                                    headers = mapOf(
                                        "Referer" to extRef,
                                        "User-Agent" to "Mozilla/5.0"
                                    )
                                }
                            )
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("kraptor_$name", "Alternatif M3U8 yöntemi hatası: ${e.message}")
            }
            
            // 4. Son çare - Video elementlerini ara
            try {
                val videoRegex = Regex("""<video[^>]+src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
                val videoMatch = videoRegex.find(videoReq)
                
                if (videoMatch != null) {
                    val videoUrl = videoMatch.groupValues[1]
                    if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                        Log.d("kraptor_$name", "Video elementi bulundu: $videoUrl")
                        
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} (Video)",
                                url = videoUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                quality = Qualities.Unknown.value
                                headers = mapOf(
                                    "Referer" to extRef,
                                    "User-Agent" to "Mozilla/5.0"
                                )
                            }
                        )
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e("kraptor_$name", "Video elementi yöntemi hatası: ${e.message}")
            }
            
            Log.e("kraptor_$name", "Hiçbir video linki bulunamadı!")
            throw ErrorLoadingException("Video linki bulunamadı")
            
        } catch (e: Exception) {
            Log.e("kraptor_$name", "getUrl genel hata: ${e.message}", e)
            throw ErrorLoadingException("Video yüklenemedi: ${e.message}")
        }
    }
}
