package com.UmayTrade

import android.content.Context
import com.UmayTrade.VidMolyExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LoveFilmPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LoveFilm())

        registerExtractorAPI(VidMolyExtractor())
    }
}
