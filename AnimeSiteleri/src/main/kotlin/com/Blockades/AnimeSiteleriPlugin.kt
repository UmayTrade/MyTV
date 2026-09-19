package com.Blockades

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimeSiteleriPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeciX())
        registerMainAPI(OpenAnime())
        registerExtractorAPI(TauVideo())
    }
}
