package com.UmayTrade

import com.fasterxml.jackson.annotation.JsonProperty

data class WListResponse(
    @JsonProperty("success") val success: Boolean?,
    @JsonProperty("data") val data: List<WItem>?
)

data class WItem(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("_id") val _id: String?,
    @JsonProperty("imdb_id") val imdbId: String?,
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("title_tr") val titleTr: String?,
    @JsonProperty("title_en") val titleEn: String?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("name_tr") val nameTr: String?,
    @JsonProperty("name_en") val nameEn: String?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("year") val year: String?,
    @JsonProperty("genres") val genres: String?,
    @JsonProperty("imdb_rating") val imdbRating: String?,
    @JsonProperty("description") val description: String?
)

data class WDetailResponse(
    @JsonProperty("success") val success: Boolean?,
    @JsonProperty("data") val data: WItemDetail?
)

data class WItemDetail(
    @JsonProperty("streamUrl") val streamUrl: String?,
    @JsonProperty("seasons") val seasons: List<WSeason>?,
    @JsonProperty("episodes") val episodes: List<WEpisode>?
)

data class WSeason(
    @JsonProperty("id") val id: String?,
    @JsonProperty("season_number") val seasonNumber: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("episodes") val episodes: List<WEpisode>?
)

data class WEpisode(
    @JsonProperty("id") val id: String?,
    @JsonProperty("episode_number") val episodeNumber: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("streamUrl") val streamUrl: String?,
    @JsonProperty("season_number") val seasonNumber: Int?
)

data class WStreamResponse(
    @JsonProperty("success") val success: Boolean?,
    @JsonProperty("data") val data: WStreamData?
)

data class WStreamData(
    @JsonProperty("streamUrl") val streamUrl: String?,
    @JsonProperty("subtitles") val subtitles: List<WSubtitle>?
)

data class WSubtitle(
    @JsonProperty("lang") val lang: String?,
    @JsonProperty("url") val url: String?
)