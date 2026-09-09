package com.droctagon

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecItem(
    @JsonProperty("id")             val id: Int,
    @JsonProperty("type")           val type: String? = null,
    @JsonProperty("title")          val title: String,
    @JsonProperty("label")          val label: String? = null,
    @JsonProperty("sublabel")       val sublabel: String? = null,
    @JsonProperty("description")    val description: String? = null,
    @JsonProperty("year")           val year: Int? = null,
    @JsonProperty("rating")         val rating: Float? = null,
    @JsonProperty("duration")       val duration: String? = null,
    @JsonProperty("image")          val image: String? = null,
    @JsonProperty("cover")          val cover: String? = null,
    @JsonProperty("genres")         val genres: List<RecGenre>? = null,
    @JsonProperty("categories")     val categories: List<RecCategory>? = null,
    @JsonProperty("sources")        val sources: List<RecSource>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecGenre(
    @JsonProperty("id")    val id: Int,
    @JsonProperty("title") val title: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecCategory(
    @JsonProperty("id")    val id: Int,
    @JsonProperty("title") val title: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecSource(
    @JsonProperty("id")       val id: Int? = null,
    @JsonProperty("title")    val title: String? = null,
    @JsonProperty("quality")  val quality: String? = null,
    @JsonProperty("type")     val type: String? = null,
    @JsonProperty("url")      val url: String? = null,
    @JsonProperty("enc_url")  var enc_url: String? = null,
    @JsonProperty("locked")   val locked: Boolean? = null,
    @JsonProperty("reason")   val reason: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecSearch(
    @JsonProperty("channels") val channels: List<RecItem>? = emptyList(),
    @JsonProperty("posters")  val posters: List<RecItem>? = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecDizi(
    @JsonProperty("id")       val id: Int,
    @JsonProperty("title")    val title: String,
    @JsonProperty("episodes") val episodes: List<RecEpisode> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecEpisode(
    @JsonProperty("id")       val id: Int,
    @JsonProperty("title")    val title: String,
    @JsonProperty("sources")  val sources: List<RecSource> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecVerifyResponse(
    @JsonProperty("jwt") val jwt: String? = null,
    @JsonProperty("exp") val exp: Long? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecUnlockResponse(
    @JsonProperty("ok")      val ok: Boolean? = null,
    @JsonProperty("enc_url") val enc_url: String? = null
)
