package com.ardaspor.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class ArdaSpor30Provider : MainAPI() {
    override var mainUrl = "https://ardaspor30.top"
    override var name = "ArdaSpor TV"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    // Ana sayfada gösterilecek kanal listesi
    override val mainPage = mainPageOf(
        "main" to "📺 Tüm Kanallar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val channelItems = ArrayList<SearchResponse>()

        // Site içindeki kanal butonlarını seç (örnek seçici, site değişirse güncelle)
        // NOT: Bu seçiciyi sitenin gerçek HTML yapısına göre ayarlamalısınız.
        // Örnek: doc.select("a:has(img)") veya doc.select(".channel-btn")
        val channelElements = doc.select("a[href*='watch']") // Varsayımsal seçici

        if (channelElements.isEmpty()) {
            // Eğer hiç kanal bulunamazsa, manuel liste döndürülebilir (geçici çözüm)
            return newHomePageResponse(request.name, getManualChannelList())
        }

        channelElements.map { element ->
            val name = element.text().trim()
            val link = element.attr("href")
            if (name.isNotBlank() && link.isNotBlank()) {
                channelItems.add(
                    newLiveSearchResponse(
                        name,
                        fixUrl(link),
                        TvType.Live
                    ) {
                        // Poster URL'si yok, boş bırakılabilir
                    }
                )
            }
        }

        return newHomePageResponse(request.name, channelItems)
    }

    // Yedek manuel kanal listesi (HTML analizi yapılamazsa)
    private fun getManualChannelList(): List<SearchResponse> {
        val channels = listOf(
            "BEIN SPORTS 1", "BEIN SPORTS 2", "BEIN SPORTS 3", "BEIN SPORTS 4",
            "BEIN SPORTS 5", "BEİN SPORTS MAX 1", "BEİN SPORTS MAX 2",
            "S SPORT", "S SPORT 2", "TRT SPOR", "TRT 1", "A SPOR"
        )
        return channels.map { name ->
            newLiveSearchResponse(
                name,
                "$mainUrl/watch/${name.replace(" ", "-")}", // Varsayımsal link yapısı
                TvType.Live
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(data).document

            // 1. Yöntem: Sayfadaki video etiketinden veya iframe'den URL'yi bul
            // Örnek: video etiketi
            var videoUrl = doc.select("video[src]").attr("src")
            if (videoUrl.isBlank()) {
                // 2. Yöntem: iframe içinde olabilir
                val iframeSrc = doc.select("iframe[src]").attr("src")
                if (iframeSrc.isNotBlank()) {
                    // İframe içine girip orada video aramak gerekebilir
                    // Bu kısım siteye özel geliştirilmelidir.
                    videoUrl = iframeSrc
                }
            }

            if (videoUrl.isNotBlank()) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "ArdaSpor",
                        url = fixUrl(videoUrl),
                        quality = Qualities.Unknown.value,
                        type = "HLS" // veya "MP4"
                    )
                )
                return true
            }

            // 3. Yöntem: Sayfa kaynağında .m3u8 uzantılı bir URL aramak (regex)
            val pageText = doc.html()
            val m3u8Regex = "https?:[^\"]*\\.m3u8[^\"]*".toRegex()
            val match = m3u8Regex.find(pageText)
            if (match != null) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "ArdaSpor",
                        url = match.value,
                        quality = Qualities.Unknown.value,
                        type = "HLS"
                    )
                )
                return true
            }

            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // URL'yi düzelt (göreli ise tam URL yap)
    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl$url"
    }
}