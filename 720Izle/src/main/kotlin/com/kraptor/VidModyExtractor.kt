// ! Bu araç @Kraptor123 tarafından | @kekikanime için yazılmıştır.

package com.kraptor

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class VidMody720 : ExtractorApi() {
    override val name = "VidMody"
    override val mainUrl = "https://player.vidmody.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        try {
            Log.d("720izle", "VidMody getUrl: $url")
            
            val pageText = app.get(url).text
            
            val idRegex = Regex(pattern = """var\s+id\s*=\s*['"](tt\d+)['"]""", options = setOf(RegexOption.IGNORE_CASE))
            val idMatch = idRegex.find(pageText)
            
            if (idMatch != null) {
                val videoId = idMatch.groupValues[1]
                val videoUrl = "https://vidmody.com/vs/$videoId"
                
                Log.d("720izle", "VidMody video URL: $videoUrl")
                
                links.add(
                    newExtractorLink(
                        source = name,
                        name = "VidMody",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.Unknown.value
                        headers = mapOf(
                            "Referer" to url,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    }
                )
            } else {
                val jsonRegex = Regex("""\{[^{}]*"file"\s*:\s*"([^"]+)"[^{}]*\}""")
                val jsonMatch = jsonRegex.find(pageText)
                if (jsonMatch != null) {
                    val videoUrl = jsonMatch.groupValues[1]
                    links.add(
                        newExtractorLink(
                            source = name,
                            name = "VidMody",
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.STREAM
                        ) {
                            quality = Qualities.Unknown.value
                            headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                }
            }
            
            return links
        } catch (e: Exception) {
            Log.e("720izle", "VidMody error: ${e.message}")
            return links
        }
    }
}
