// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonProperty


data class GetSource(
    @JsonProperty("subtitle") val subtitle: String?    = null,
    @JsonProperty("sources")  val sources: List<Sources>? = arrayListOf() // arrayListOf() yerine null da olabilir, duruma göre
)

data class Sources(
    @JsonProperty("src")            val src: String,
    @JsonProperty("label")          val label: String,
    @JsonProperty("type")           val type: String? = null, // EKLENDİ
    @JsonProperty("withCredentials") val withCredentials: Boolean? = null, // EKLENDİ
    @JsonProperty("res")            val res: String? = null // EKLENDİ
)