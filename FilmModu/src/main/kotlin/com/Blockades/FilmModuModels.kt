// ! Bu araç @SAKLImavi tarafından | @Blockades için yazılmıştır.

package com.Blockades

import com.fasterxml.jackson.annotation.JsonProperty

data class GetSource(
    @JsonProperty("sources") val sources: List<Source>? = null,
    @JsonProperty("subtitle") val subtitle: String? = null
)

data class Source(
    @JsonProperty("src") val src: String? = null,
    @JsonProperty("label") val label: String? = null
)
