package com.UmayTrade

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OpenAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OpenAnime())
    }
}