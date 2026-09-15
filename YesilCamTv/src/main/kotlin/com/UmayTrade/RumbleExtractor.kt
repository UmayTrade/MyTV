private suspend fun parseJsonForStreams(
    obj: JSONObject,
    seen: MutableSet<String>,
    callback: (ExtractorLink) -> Unit
): Boolean { /* ... */ }

private suspend fun parseRawTextForStreams(
    text: String,
    seen: MutableSet<String>,
    callback: (ExtractorLink) -> Unit
): Boolean { /* ... */ }

private suspend fun emitStream(
    rawValue: String,
    key: String,
    seen: MutableSet<String>,
    callback: (ExtractorLink) -> Unit
): Boolean { /* ... */ }

private suspend fun parseJsonSubtitles(
    obj: JSONObject,
    subtitleCallback: (SubtitleFile) -> Unit
) { /* ... */ }

private suspend fun parseHtmlSubtitles(
    html: String,
    subtitleCallback: (SubtitleFile) -> Unit
) { /* ... */ }
