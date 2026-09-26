package com.sniptube.android.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ServerStage {
    Pending,
    Queued,
    Running,
    Ready,
    Failed,
}

enum class DeviceStage {
    Pending,
    WaitingForNetwork,
    Queued,
    Running,
    Paused,
    Ready,
    Failed,
    Removed,
}

enum class RetryStage {
    Server,
    Device,
    LocalValidation,
}

enum class LocalFileKind {
    Media,
    Thumbnail,
    Subtitle,
}

enum class AssetType { File, Media3Cache }

@Entity(
    tableName = "offline_videos",
    primaryKeys = ["serverIdentity", "youtubeId"],
    indices = [Index(value = ["serverIdentity", "serverVideoId"], unique = true)],
)
data class OfflineVideoEntity(
    val serverIdentity: String,
    val youtubeId: String,
    val sourceUrl: String,
    val serverVideoId: String? = null,
    val title: String,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    val uploader: String? = null,
    val serverStatus: String? = null,
    val serverFileSizeBytes: Long? = null,
    val metadataUpdatedAt: Long,
)

@Entity(
    tableName = "queue_intents",
    primaryKeys = ["serverIdentity", "youtubeId"],
    foreignKeys = [
        ForeignKey(
            entity = OfflineVideoEntity::class,
            parentColumns = ["serverIdentity", "youtubeId"],
            childColumns = ["serverIdentity", "youtubeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class QueueIntentEntity(
    val serverIdentity: String,
    val youtubeId: String,
    val serverStage: ServerStage = ServerStage.Pending,
    val deviceStage: DeviceStage = DeviceStage.Pending,
    val retryStage: RetryStage? = null,
    val userPaused: Boolean = false,
    val resumeDeviceStage: DeviceStage? = null,
    val retryCount: Int = 0,
    val nextAttemptAt: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val generation: Long = 0,
    val requestedAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "server_job_bindings",
    primaryKeys = ["serverIdentity", "youtubeId"],
    foreignKeys = [
        ForeignKey(
            entity = QueueIntentEntity::class,
            parentColumns = ["serverIdentity", "youtubeId"],
            childColumns = ["serverIdentity", "youtubeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["serverIdentity", "jobId"], unique = true)],
)
data class ServerJobBindingEntity(
    val serverIdentity: String,
    val youtubeId: String,
    val jobId: String,
    val status: String,
    val progress: Int,
    val boundAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "device_transfer_bindings",
    primaryKeys = ["serverIdentity", "youtubeId"],
    foreignKeys = [
        ForeignKey(
            entity = QueueIntentEntity::class,
            parentColumns = ["serverIdentity", "youtubeId"],
            childColumns = ["serverIdentity", "youtubeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["transferId"], unique = true)],
)
data class DeviceTransferBindingEntity(
    val serverIdentity: String,
    val youtubeId: String,
    val transferId: String,
    val temporaryPath: String,
    val destinationPath: String,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val generation: Long,
    val updatedAt: Long,
    val cacheKey: String? = null,
)

@Entity(
    tableName = "local_files",
    primaryKeys = ["serverIdentity", "youtubeId", "kind", "trackKey"],
    foreignKeys = [
        ForeignKey(
            entity = OfflineVideoEntity::class,
            parentColumns = ["serverIdentity", "youtubeId"],
            childColumns = ["serverIdentity", "youtubeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["path"], unique = true),
        Index(value = ["serverIdentity", "youtubeId"]),
    ],
)
data class LocalFileEntity(
    val serverIdentity: String,
    val youtubeId: String,
    val kind: LocalFileKind,
    val trackKey: String = "",
    val path: String,
    val byteSize: Long,
    val contentType: String? = null,
    val language: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val generation: Long,
    val validatedAt: Long,
    val assetType: AssetType = AssetType.File,
    val cacheKey: String? = null,
)

@Entity(
    tableName = "playback_progress",
    primaryKeys = ["serverIdentity", "youtubeId"],
    foreignKeys = [
        ForeignKey(
            entity = OfflineVideoEntity::class,
            parentColumns = ["serverIdentity", "youtubeId"],
            childColumns = ["serverIdentity", "youtubeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaybackProgressEntity(
    val serverIdentity: String,
    val youtubeId: String,
    val positionMs: Long,
    val durationMs: Long? = null,
    val updatedAt: Long,
    val viewed: Boolean = false,
)

/** Completion is durable even if the player later restarts at the beginning. */
fun watchedEnough(positionMs: Long, durationMs: Long?): Boolean =
    durationMs != null && durationMs > 0 && positionMs.toDouble() / durationMs > 0.9

@Entity(
    tableName = "collections",
    indices = [Index(value = ["name"], unique = true)],
)
data class CollectionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE)
    val name: String,
    val keepOffline: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "collection_memberships",
    primaryKeys = ["collectionId", "serverIdentity", "youtubeId"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = OfflineVideoEntity::class,
            parentColumns = ["serverIdentity", "youtubeId"],
            childColumns = ["serverIdentity", "youtubeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["serverIdentity", "youtubeId"])],
)
data class CollectionMembershipEntity(
    val collectionId: String,
    val serverIdentity: String,
    val youtubeId: String,
    val addedAt: Long,
)

@Entity(
    tableName = "keep_offline_exclusions",
    primaryKeys = ["serverIdentity", "youtubeId"],
    foreignKeys = [
        ForeignKey(
            entity = OfflineVideoEntity::class,
            parentColumns = ["serverIdentity", "youtubeId"],
            childColumns = ["serverIdentity", "youtubeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class KeepOfflineExclusionEntity(
    val serverIdentity: String,
    val youtubeId: String,
    val excludedAt: Long,
)
