package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonProperty

data class VidBiz(
    @JsonProperty("status") val status: String,
    @JsonProperty("message") val message: String,
    @JsonProperty("sources") val sources: List<Source>,
)

data class Source(
    @JsonProperty("file") val file: String,
    @JsonProperty("label") val label: String,
    @JsonProperty("type") val type: String,
)