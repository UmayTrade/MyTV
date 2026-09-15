package com.UmayTrade

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YesilCamTvPlugin : Plugin() {

    override fun load(context: android.content.Context) {

        /*
         * Rumble özel extractor
         */
        registerExtractorAPI(
            RumbleExtractor()
        )

        /*
         * Ana site provider
         */
        registerMainAPI(
            YesilCamTv()
        )
    }
}
