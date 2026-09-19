package com.UmayTrade

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * OpenAni.me Cloudstream Eklentisi
 *
 * Bu eklenti, OpenAni.me sitesindeki animeleri Cloudstream üzerinden
 * izlenebilir hale getirir.
 *
 * Site: https://openani.me / https://openanime.org
 * Yapı: Next.js (Pages Router) tabanlı SPA
 * Oynatıcılar: Doğrudan HLS (m3u8), progressive MP4, harici embed
 *              (Vidmoly, Sibnet, Doodstream vb.)
 * DRM: Yok (clear HLS)
 */
@CloudstreamPlugin
class OpenAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OpenAnime())
    }
}
