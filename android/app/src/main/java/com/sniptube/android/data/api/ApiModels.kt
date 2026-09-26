package com.sniptube.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

enum class SearchDuration(val apiValue: String) {
    Any("any"),
    Short("short"),
    Medium("medium"),
    Long("long"),
}

enum class SearchSort(val apiValue: String) {
    Relevance("relevance"),
    Date("date"),
    Views("views"),
}

@Serializable
data class SearchResponse(
    val query: String,
    val results: List<SearchResult> = emptyList(),
    @SerialName("total_fetched") val totalFetched: Int = 0,
    @SerialName("filters_applied") val filtersApplied: JsonObject = buildJsonObject { },
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 12,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class SearchResult(
    @SerialName("youtube_id") val youtubeId: String,
    val url: String,
    val title: String,
    val duration: Double? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val uploader: String? = null,
    @SerialName("uploader_id") val uploaderId: String? = null,
    @SerialName("view_count") val viewCount: Long? = null,
    @SerialName("upload_date") val uploadDate: String? = null,
    val description: String? = null,
    @SerialName("already_downloaded") val alreadyDownloaded: Boolean = false,
    @SerialName("video_id") val videoId: String? = null,
)

@Serializable
data class Video(
    val id: String,
    @SerialName("youtube_id") val youtubeId: String,
    val url: String,
    val title: String? = null,
    val duration: Double? = null,
    val language: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val subtitles: List<String> = emptyList(),
    val protected: Boolean = false,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("derivatives_count") val derivativesCount: Int = 0,
    @SerialName("derivatives_total_size") val derivativesTotalSize: Long = 0,
    @SerialName("available_heights") val availableHeights: List<Int> = emptyList(),
    @SerialName("source_height") val sourceHeight: Int? = null,
)

@Serializable
data class CreateVideoRequest(val url: String)

@Serializable
data class CreateVideoResponse(
    @SerialName("video_id") val videoId: String,
    @SerialName("job_id") val jobId: String,
    val status: String,
)

@Serializable
data class Job(
    val id: String,
    @SerialName("video_id") val videoId: String,
    val type: String,
    val params: JsonObject = buildJsonObject { },
    val status: String,
    val progress: Int = 0,
    @SerialName("result_url") val resultUrl: String? = null,
    val error: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class SubtitleTrack(
    val language: String,
    val url: String,
)
