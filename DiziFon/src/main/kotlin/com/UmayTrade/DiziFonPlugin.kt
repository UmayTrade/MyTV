package com.UmayTrade

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class DiziFonPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(DiziFun())
        registerExtractorAPI(PlayHouse())
    }
}