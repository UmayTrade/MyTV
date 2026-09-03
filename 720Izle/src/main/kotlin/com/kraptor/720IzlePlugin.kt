// ! Bu araç @Kraptor123 tarafından | @kekikanime için yazılmıştır.
package com.kraptor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FilmIzle720Plugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmIzle720())
        registerExtractorAPI(VidMody720())
    }
}