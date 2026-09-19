package com.Blockades

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmizleChPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmizleCh())
    }
}
