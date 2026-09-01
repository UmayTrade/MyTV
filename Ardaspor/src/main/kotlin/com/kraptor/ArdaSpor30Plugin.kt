package com.ardaspor.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ArdaSpor30Plugin: Plugin() {
    override fun load(context: Context) {
        // Ana sağlayıcıyı kaydet
        registerMainAPI(ArdaSpor30Provider())
        // Not: Bu site için özel bir Extractor API'sine gerek yok.
        // Video URL'leri doğrudan sayfadan çekilecek.
    }
}