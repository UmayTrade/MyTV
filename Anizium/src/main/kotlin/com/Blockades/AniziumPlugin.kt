// ! Bu araç @SAKLImavi tarafından yazılmıştır.

package com.Blockades

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniziumPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Anizium())
    }
}