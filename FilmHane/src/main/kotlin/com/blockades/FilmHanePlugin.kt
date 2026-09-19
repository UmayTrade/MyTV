package com.blockades

import android.content.Context
import com.blockades.Ag2m4Extractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmHanePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmHane())

        registerExtractorAPI(Ag2m4Extractor())
    }
}
