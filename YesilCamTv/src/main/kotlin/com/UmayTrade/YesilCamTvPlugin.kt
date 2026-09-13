package com.cloudstream.tr.yesilcamtv

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class YesilCamTvPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(YesilCamTv())
    }
}
