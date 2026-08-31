package com.UmayTrade

import android.content.Context
import com.UmayTrade.extractors.EksenLoadExtractor
import com.UmayTrade.extractors.VidMolyExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmEkseniPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmEkseni())

        registerExtractorAPI(EksenLoadExtractor())
        registerExtractorAPI(VidMolyExtractor())
    }
}
