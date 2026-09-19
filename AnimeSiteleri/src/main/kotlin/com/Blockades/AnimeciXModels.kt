package com.Blockades

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Category(
    @JsonProperty("pagination") val pagination: Pagination,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Search(
    @JsonProperty("results") val results: List<AnimeSearch>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Title(
    @JsonProperty("title") val title: Anime,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Pagination(
    @JsonProperty("current_page") val currentPage: Int,
    @JsonProperty("last_page") val lastPage: Int,
    @JsonProperty("per_page") val perPage: Int,
    @JsonProperty("data") val data: List<AnimeSearch>,
    @JsonProperty("total") val total: Int,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimeSearch(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title_type") val titleType: String?,
    @JsonProperty("name") val title: String,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("mal_vote_average") val rating: String?,
    @JsonProperty("tmdb_vote_average") val tmdbVoteAverage: Double?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Anime(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title_type") val titleType: String?,
    @JsonProperty("name") val title: String,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("year") val year: Int?,
    @JsonProperty("tmdb_id") val tmdbId: Int?,
    @JsonProperty("mal_id") val malId: Int?,
    @JsonProperty("imdb_id") val imdbId: String?,
    @JsonProperty("mal_vote_average") val rating: String?,
    @JsonProperty("genres") val tags: List<Genre> = emptyList(),
    @JsonProperty("trailer") val trailer: String?,
    @JsonProperty("credits") val actors: List<Credit> = emptyList(),
    @JsonProperty("season_count") val seasonCount: Int?,
    @JsonProperty("seasons") val seasons: List<Season> = emptyList(),
    @JsonProperty("videos") val videos: List<Video> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LastEpisode(
    @JsonProperty("title_id") val titleId: Int,
    @JsonProperty("title_name") val titleName: String,
    @JsonProperty("title_poster") val titlePoster: String?,
    @JsonProperty("season_number") val seasonNumber: Int,
    @JsonProperty("episode_number") val episodeNumber: Int
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LastEpisodesResponse(
    @JsonProperty("data") val data: List<LastEpisode>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Genre(
    @JsonProperty("display_name") val name: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Credit(
    @JsonProperty("name") val name: String,
    @JsonProperty("poster") val poster: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Video(
    @JsonProperty("episode_num") val episodeNum: Int?,
    @JsonProperty("season_num") val seasonNum: Int?,
    @JsonProperty("url") val url: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TitleVideos(
    @JsonProperty("videos") val videos: List<Video>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Season(@JsonProperty("number") val number: Int)