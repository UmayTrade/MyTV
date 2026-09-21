package com.keyiflerolsun

object CommonHeaders {
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // WebView/system UA – Cloudstream'de systemUserAgent olarak da kullanılabilir
    const val systemUserAgent = DEFAULT_USER_AGENT

    fun defaultHeaders(referer: String = ""): Map<String, String> = buildMap {
        put("User-Agent", DEFAULT_USER_AGENT)
        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        put("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
        if (referer.isNotBlank()) put("Referer", referer)
    }
}
