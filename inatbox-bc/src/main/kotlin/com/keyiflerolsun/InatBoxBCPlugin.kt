package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class InatBoxBCPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(InatBoxBC())
        registerExtractorAPI(DiskYandexComTr())
        registerExtractorAPI(Vk())
        registerExtractorAPI(DzenRu())
        registerExtractorAPI(CDNJWPlayer())
    }
}
