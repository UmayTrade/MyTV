package com.UmayTrade

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.UmayTrade.DiziPal

@CloudstreamPlugin
class DiziPalPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziPal())
    }
}
