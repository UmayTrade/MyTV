package com.UmayTrade

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.UmayTrade.extractors.CloseLoadExtractor
import com.UmayTrade.extractors.RapidExtractor




@CloudstreamPlugin
class FilmMakinesiPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmMakinesi())

        registerExtractorAPI(CloseLoadExtractor())
        registerExtractorAPI(RapidExtractor())

    }
}
