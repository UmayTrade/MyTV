package com.Blockades

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TrAnimeIzlePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TrAnimeIzle())
    }
}
