package com.kurdora

import com.fasterxml.jackson.annotation.JsonProperty

class KurdoraListResponse(
    @JsonProperty("movies") val movies: List<KurdoraListItem>? = null,
    @JsonProperty("series") val series: List<KurdoraListItem>? = null,
    @JsonProperty("total") val total: Int? = null,
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("totalPages") val totalPages: Int? = null
)

class KurdoraSearchResult(
    @JsonProperty("movies") val movies: List<KurdoraListItem>? = null,
    @JsonProperty("series") val series: List<KurdoraListItem>? = null,
    @JsonProperty("actors") val actors: List<Any>? = null,
    @JsonProperty("suggestions") val suggestions: List<Any>? = null,
    @JsonProperty("total") val total: Int? = null
)

class KurdoraListItem(
    @JsonProperty("_id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("titleEnglish") val titleEnglish: String? = null,
    @JsonProperty("titleKurdish") val titleKurdish: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("backdrop") val backdrop: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("duration") val duration: String? = null,
    @JsonProperty("durationMinutes") val durationMinutes: Int? = null,
    @JsonProperty("totalSeasons") val totalSeasons: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("type") val type: String? = null
)

class KurdoraMovieDetail(
    @JsonProperty("_id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("titleEnglish") val titleEnglish: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("backdrop") val backdrop: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("durationMinutes") val durationMinutes: Int? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("videoServers") val videoServers: List<KurdoraVideoServer>? = null
)

class KurdoraSeriesDetail(
    @JsonProperty("_id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("titleEnglish") val titleEnglish: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("backdrop") val backdrop: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("seasons") val seasons: List<KurdoraSeason>? = null
)

class KurdoraSeason(
    @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("episodes") val episodes: List<KurdoraEpisode>? = null
)

class KurdoraEpisode(
    @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("durationMinutes") val durationMinutes: Int? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("videoServers") val videoServers: List<KurdoraVideoServer>? = null
)

class KurdoraVideoServer(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("isPremium") val isPremium: Boolean? = null,
    @JsonProperty("hasAds") val hasAds: Boolean? = null
)

class KurdoraWasabiResponse(
    @JsonProperty("streams") val streams: Map<String, List<KurdoraStream>>? = null,
    @JsonProperty("totalQualities") val totalQualities: Int? = null
)

class KurdoraStream(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("isHls") val isHls: Boolean? = null
)

private const val PLACEHOLDER = "kurdora"

fun KurdoraVideoServer.hasRealUrl(): Boolean =
    !url.isNullOrBlank() && url != PLACEHOLDER && url.startsWith("http")