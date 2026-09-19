package com.UmayTrade

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAnimeSearchItem(
    @JsonProperty("type") val type: String?,
    @JsonProperty("english") val english: String?,
    @JsonProperty("turkish") val turkish: String?,
    @JsonProperty("originalName") val originalName: String?,
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("pictures") val pictures: OpenAnimePictures?,
    @JsonProperty("id") val id: String?,
    @JsonProperty("episode") val episode: Int?,
    @JsonProperty("season") val season: Int?,
    @JsonProperty("genres") val genres: List<String>?,
    @JsonProperty("score") val score: Double?,
    @JsonProperty("tmdbScore") val tmdbScore: Double?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAnimePictures(
    @JsonProperty("banner") val banner: String?,
    @JsonProperty("avatar") val avatar: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAnime4kResponse(
    @JsonProperty("animes") val animes: List<OpenAnimeSearchItem>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAnimeEpisodesResponse(
    @JsonProperty("episodes") val episodes: List<OpenAnimeSearchItem>?,
    @JsonProperty("totalPages") val totalPages: Int?,
    @JsonProperty("page") val page: Int?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAnimeListResponse(
    @JsonProperty("animes") val animes: List<OpenAnimeSearchItem>?,
    @JsonProperty("totalPages") val totalPages: Int?,
    @JsonProperty("page") val page: Int?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAnimeDetail(
    @JsonProperty("pictures") val pictures: OpenAnimePictures?,
    @JsonProperty("english") val english: String?,
    @JsonProperty("turkish") val turkish: String?,
    @JsonProperty("originalName") val originalName: String?,
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("summary") val summary: String?,
    @JsonProperty("genres") val genres: List<String>?,
    @JsonProperty("tmdbScore") val tmdbScore: Double?,
    @JsonProperty("malID") val malID: Int?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("seasons") val seasons: List<OpenAnimeSeason>?,
    @JsonProperty("numberOfEpisodes") val numberOfEpisodes: Int?,
    @JsonProperty("hasEpisode") val hasEpisode: Boolean?,
    @JsonProperty("firstAirDate") val firstAirDate: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAnimeSeason(
    @JsonProperty("season_number") val seasonNumber: Int?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("episode_count") val episodeCount: Int?,
    @JsonProperty("hasEpisode") val hasEpisode: Boolean?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAnimeSeasonResponse(
    @JsonProperty("season") val season: OpenAnimeSeasonDetail?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAnimeSeasonDetail(
    @JsonProperty("season_number") val seasonNumber: Int?,
    @JsonProperty("episodes") val episodes: List<OpenAnimeEpisodeItem>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAnimeEpisodeItem(
    @JsonProperty("name") val name: String?,
    @JsonProperty("episodeNumber") val episodeNumber: Int?,
    @JsonProperty("summary") val summary: String?,
    @JsonProperty("avatar") val avatar: String?,
    @JsonProperty("airDate") val airDate: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAnimeStreamResponse(
    @JsonProperty("episodeData") val episodeData: OpenAnimeEpisodeData?,
    @JsonProperty("fansubs") val fansubs: List<OpenAnimeFansub>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAnimeEpisodeData(
    @JsonProperty("files") val files: List<OpenAnimeFile>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAnimeFile(
    @JsonProperty("resolution") val resolution: Int?,
    @JsonProperty("file") val file: String?,
    @JsonProperty("storage_cluster_id") val storageClusterId: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAnimeFansub(
    @JsonProperty("id") val id: String?,
    @JsonProperty("name") val name: String?
)
