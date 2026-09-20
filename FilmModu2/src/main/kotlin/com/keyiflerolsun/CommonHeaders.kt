package com.keyiflerolsun

/**
 * Standard User-Agent, Referer, and Anti-Hotlinking Headers for Aethelon-TV.
 */
object CommonHeaders {

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    var systemUserAgent: String = DEFAULT_USER_AGENT

    /**
     * Creates a standard headers map containing User-Agent and optional Referer.
     */
    fun defaultHeaders(referer: String = ""): Map<String, String> {
        val headers = mutableMapOf("User-Agent" to systemUserAgent)
        if (referer.isNotBlank()) {
            headers["Referer"] = if (referer.endsWith("/")) referer else "$referer/"
        }
        return headers
    }

    /**
     * Applies provider-level anti-bot and anti-hotlinking headers.
     * Injects canonical trailing-slash Referer and optional AJAX headers.
     */
    fun applyProviderHeaders(headers: MutableMap<String, String>, mainUrl: String, isAjax: Boolean = false) {
        headers["User-Agent"] = systemUserAgent
        headers["Referer"] = when {
            mainUrl.isBlank() -> "/"
            mainUrl.endsWith("/") -> mainUrl
            else -> "$mainUrl/"
        }
        if (isAjax) {
            headers["X-Requested-With"] = "XMLHttpRequest"
            headers["Accept"] = "application/json, text/javascript, */*; q=0.01"
        }
    }

    /**
     * Injects stream playback headers for ExtractorLinks.
     * If the referer is empty, omits the Referer header while preserving the User-Agent.
     */
    fun applyExtractorHeaders(streamHeaders: MutableMap<String, String>, referer: String) {
        if (referer.isNotBlank()) {
            streamHeaders["Referer"] = referer
        }
        streamHeaders["User-Agent"] = systemUserAgent
    }
}
