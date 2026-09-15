package com.UmayTrade

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class YesilCamTvPlugin : BasePlugin() {

    override fun load() {
        // Yeşilçam TV ana sağlayıcısı
        registerMainAPI(
            YesilCamTv()
        )

        // Rumble video oynatıcı extractor'ı
        registerExtractorAPI(
            RumbleExtractor()
        )
    }
}
