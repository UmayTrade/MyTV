package com.UmayTrade

import android.content.Context
import com.UmayTrade.extractors.HdPlayerExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziMomPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziMom())

        registerExtractorAPI(HdPlayerExtractor())
    }
}
