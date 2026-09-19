package com.Blockades

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI

@Plugin
class FilmizleChPlugin : BasePlugin() {
    override fun load() {
        val providers: List<MainAPI> = listOf(
            FilmizleCh()
        )
        providers.forEach { provider ->
            APIHolder.addPluginProvider(provider)
        }
    }
}
