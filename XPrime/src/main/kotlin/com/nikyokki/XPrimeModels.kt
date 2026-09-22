package com.nikyokki

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovieResponse(
    @JsonProperty("results") val results: List<XMovie> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class XMovie(
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("genre_ids") val genreIds: List<Int>? = null,
    @JsonProperty("genres") val genres: List<Genre>? = null,
    @JsonProperty("id") val id: Int,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("vote_average") val vote: Double? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("credits") val credits: Credits? = null,
    @JsonProperty("recommendations") val recommendations: Recommendations? = null,
    @JsonProperty("external_ids") val externalIds: ExternalIds? = null,
    @JsonProperty("imdb_id") val imdbIdDirect: String? = null
) {
    val imdb: String?
        get() = externalIds?.imdbId ?: imdbIdDirect
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExternalIds(
    @JsonProperty("imdb_id") val imdbId: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Recommendations(
    @JsonProperty("results") val results: List<XMovie> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Credits(
    @JsonProperty("cast") val cast: List<Cast> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Cast(
    @JsonProperty("name") val name: String,
    @JsonProperty("profile_path") val profilePath: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Genres(
    @JsonProperty("genres") val genres: List<Genre>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Genre(
    @JsonProperty("id") val id: Int,
    @JsonProperty("name") val name: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Stream(
    @JsonProperty("available_qualities") val qualities: List<String> = emptyList(),
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("has_subtitles") val hasSubtitles: Boolean = false,
    @JsonProperty("subtitles") val subtitles: List<PrimeSubs> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PrimeSubs(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Trailers(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("results") val results: List<Trailer> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Trailer(
    @JsonProperty("key") val key: String,
    @JsonProperty("site") val site: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Servers(
    @JsonProperty("servers") val servers: List<Server>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Server(
    @JsonProperty("name") val name: String,
    @JsonProperty("status") val status: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Subtitle(
    @JsonProperty("url") val url: String,
    @JsonProperty("display") val display: String
)
