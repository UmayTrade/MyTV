package com.UmayTrade

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DramaDizilerimPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DramaDizilerim())
    }
}
