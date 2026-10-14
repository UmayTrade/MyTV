// BerkStream tarafindan Nikyokki Turkish Providers deposundan derlendi (XPrime).
// Kaynak paket: com.nikyokki -> com.berkstream.vendor.nikyokki.xprime.nikyokki
// Bu dosyayi elle duzenleme: 'npm run vendor' her calistiginda yeniden uretilir.
package com.nikyokki

import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

class XPrimePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(XPrime())
    }
}
