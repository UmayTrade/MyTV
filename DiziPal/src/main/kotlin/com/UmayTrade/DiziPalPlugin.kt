package com.UmayTrade

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.keyiflerolsun.DiziPal

@CloudstreamPlugin
class DiziPalPlugin: Plugin() {
    override fun load(context: Context) {
        // 'DiziPalOriginal' yerine doğru ana sınıf ismi 'DiziPal' çağrılıyor
        registerMainAPI(DiziPal())
    }
}
