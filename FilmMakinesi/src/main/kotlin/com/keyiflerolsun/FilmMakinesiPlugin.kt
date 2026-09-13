package com.keyiflerolsun

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.keyiflerolsun.CloseLoadExtractor
import com.keyiflerolsun.RapidExtractor




@CloudstreamPlugin
class FilmMakinesiPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmMakinesi())

        registerExtractorAPI(CloseLoadExtractor())
        registerExtractorAPI(RapidExtractor())

    }
}
