package com.Blockades

fun getBrowserHeaders(referer: String): Map<String, String> {
    return mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to referer
    )
}
