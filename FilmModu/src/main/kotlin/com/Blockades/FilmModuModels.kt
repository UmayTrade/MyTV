// ! Bu araç @SAKLImavi tarafından | @Blockades için yazılmıştır.

package com.Blockades

import com.fasterxml.jackson.annotation.JsonProperty

data class GetSource(
    @JsonProperty("subtitle") val subtitle: String? = null,
    @JsonProperty("sources") val sources: List<Sources>? = arrayListOf()
)

data class Sources(
    @JsonProperty("src") val src: String,
    @JsonProperty("label") val label: String,
)