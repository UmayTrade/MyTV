package com.UmayTrade

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.UmayTrade.RumbleExtractor

@CloudstreamPlugin
class YesilCamTvPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(YesilCamTv())
        registerExtractorAPI(RumbleExtractor())
    }
}
