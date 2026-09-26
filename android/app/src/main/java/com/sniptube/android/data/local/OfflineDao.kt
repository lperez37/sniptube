package com.sniptube.android.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

enum class EnqueueResult {
    Inserted,
    Restored,
    Duplicate,
}

@Dao
abstract class OfflineDao {
    @Upsert
    protected abstract suspend fun upsertVideoInternal(video: OfflineVideoEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIntent(intent: QueueIntentEntity): Long

    @Update
    protected abstract suspend fun updateIntent(intent: QueueIntentEntity)

    @Query(
        """
        SELECT * FROM queue_intents
        WHERE serverIdentity = :serverIdentity AND youtubeId = :youtubeId
        """,
    )
    abstract suspend fun getIntent(
        serverIdentity: String,
        youtubeId: String,
    ): QueueIntentEntity?

    @Transaction
    open suspend fun enqueue(
        video: OfflineVideoEntity,
        requestedAt: Long,
    ): EnqueueResult {
        upsertVideo(video)
        val existing = getIntent(video.serverIdentity, video.youtubeId)
        if (existing == null) {
            insertIntent(
                QueueIntentEntity(
                    serverIdentity = video.serverIdentity,
                    youtubeId = video.youtubeId,
                    requestedAt = requestedAt,
                    updatedAt = requestedAt,
                ),
            )
            clearKeepOfflineExclusion(video.serverIdentity, video.youtubeId)
            return EnqueueResult.Inserted
        }
        if (existing.deviceStage == DeviceStage.Removed) {
            updateIntent(
                existing.copy(
                    deviceStage = DeviceStage.Pending,
                    retryStage = null,
                    userPaused = false,
                    resumeDeviceStage = null,
                    retryCount = 0,
                    nextAttemptAt = null,
                    errorCode = null,
                    errorMessage = null,
                    requestedAt = requestedAt,
                    updatedAt = requestedAt,
                ),
            )
            clearKeepOfflineExclusion(video.serverIdentity, video.youtubeId)
            return EnqueueResult.Restored
        }
        return EnqueueResult.Duplicate
    }

    /** Sparse search metadata must not erase acquired source metadata. */
    @Transaction
    open suspend fun upsertVideo(video: OfflineVideoEntity) {
        val old = getVideo(video.serverIdentity, video.youtubeId)
        val newest = if (old != null && old.metadataUpdatedAt > video.metadataUpdatedAt) old else video
        val fallback = if (newest === old) video else old
        upsertVideoInternal(newest.copy(
            serverVideoId = newest.serverVideoId ?: fallback?.serverVideoId,
            title = newest.title.takeIf { it.isNotBlank() } ?: fallback?.title.orEmpty(),
            durationMs = newest.durationMs ?: fallback?.durationMs,
            thumbnailUrl = newest.thumbnailUrl ?: fallback?.thumbnailUrl,
            uploader = newest.uploader ?: fallback?.uploader,
            serverStatus = newest.serverStatus ?: fallback?.serverStatus,
            serverFileSizeBytes = newest.serverFileSizeBytes ?: fallback?.serverFileSizeBytes,
        ))
    }

    @Query("SELECT * FROM offline_videos ORDER BY title COLLATE NOCASE")
    abstract fun observeVideos(): Flow<List<OfflineVideoEntity>>

    @Query("SELECT * FROM queue_intents ORDER BY requestedAt, youtubeId")
    abstract fun observeQueue(): Flow<List<QueueIntentEntity>>

    @Query("SELECT COUNT(*) FROM queue_intents")
    abstract suspend fun queueCount(): Int

    @Query(
        """
        SELECT * FROM queue_intents
        WHERE deviceStage NOT IN (:terminalStages) AND userPaused = 0
        ORDER BY requestedAt, youtubeId
        """,
    )
    protected abstract suspend fun getRecoveryCandidatesInternal(
        terminalStages: List<DeviceStage>,
    ): List<QueueIntentEntity>

    suspend fun getRecoveryCandidates(): List<QueueIntentEntity> =
        getRecoveryCandidatesInternal(listOf(DeviceStage.Ready, DeviceStage.Removed))

    @Query("SELECT * FROM queue_intents WHERE deviceStage NOT IN ('Ready', 'Removed') ORDER BY requestedAt, youtubeId")
    abstract suspend fun getAcquisitionCandidates(): List<QueueIntentEntity>

    @Query(
        """
        SELECT * FROM offline_videos
        WHERE serverIdentity = :serverIdentity AND youtubeId = :youtubeId
        """,
    )
    abstract suspend fun getVideo(serverIdentity: String, youtubeId: String): OfflineVideoEntity?

    @Transaction
    open suspend fun updateServerAcquisition(
        serverIdentity: String,
        youtubeId: String,
        generation: Long,
        stage: ServerStage,
        video: OfflineVideoEntity?,
        job: ServerJobBindingEntity?,
        foreground: Boolean,
        updatedAt: Long,
    ): Boolean {
        val intent = getIntent(serverIdentity, youtubeId) ?: return false
        if (intent.generation != generation || intent.deviceStage == DeviceStage.Removed) return false
        if (video != null) upsertVideo(video)
        if (job != null) bindServerJob(job)
        updateIntent(
            intent.copy(
                serverStage = stage,
                deviceStage = if (stage == ServerStage.Ready && !intent.userPaused &&
                    intent.deviceStage in listOf(DeviceStage.Pending, DeviceStage.WaitingForNetwork)
                ) {
                    if (foreground) DeviceStage.Queued else DeviceStage.WaitingForNetwork
                } else {
                    intent.deviceStage
                },
                errorCode = if (stage == ServerStage.Ready && !foreground && !intent.userPaused &&
                    intent.deviceStage in listOf(DeviceStage.Pending, DeviceStage.WaitingForNetwork)
                ) {
                    "open_app_to_sync"
                } else if (intent.retryStage == RetryStage.Server || foreground) null else intent.errorCode,
                errorMessage = if (stage == ServerStage.Ready && !foreground && !intent.userPaused &&
                    intent.deviceStage in listOf(DeviceStage.Pending, DeviceStage.WaitingForNetwork)
                ) {
                    "Open Sniptube to continue device sync."
                } else if (intent.retryStage == RetryStage.Server || foreground) null else intent.errorMessage,
                retryStage = null,
                retryCount = 0,
                nextAttemptAt = null,
                updatedAt = updatedAt,
            ),
        )
        return true
    }

    @Transaction
    open suspend fun failServerAcquisition(
        serverIdentity: String,
        youtubeId: String,
        generation: Long,
        code: String,
        message: String,
        nextAttemptAt: Long?,
        updatedAt: Long,
    ): Boolean {
        val intent = getIntent(serverIdentity, youtubeId) ?: return false
        if (intent.generation != generation || intent.deviceStage == DeviceStage.Removed ||
            intent.serverStage == ServerStage.Ready
        ) return false
        updateIntent(
            intent.copy(
                serverStage = ServerStage.Failed,
                retryStage = RetryStage.Server,
                retryCount = intent.retryCount + 1,
                nextAttemptAt = nextAttemptAt,
                errorCode = code,
                errorMessage = message,
                updatedAt = updatedAt,
            ),
        )
        return true
    }

    @Transaction
    open suspend fun waitForAllowedStart(
        serverIdentity: String,
        youtubeId: String,
        generation: Long,
        updatedAt: Long,
    ): Boolean {
        val intent = getIntent(serverIdentity, youtubeId) ?: return false
        if (intent.generation != generation || intent.serverStage != ServerStage.Ready ||
            intent.deviceStage != DeviceStage.Queued
        ) return false
        updateIntent(intent.copy(
            deviceStage = DeviceStage.WaitingForNetwork,
            errorCode = "open_app_to_sync",
            errorMessage = "Open Sniptube to continue device sync.",
            updatedAt = updatedAt,
        ))
        return true
    }

    @Transaction
    open suspend fun pauseDeviceTransfer(
        serverIdentity: String,
        youtubeId: String,
        updatedAt: Long,
    ): Boolean {
        val intent = getIntent(serverIdentity, youtubeId) ?: return false
        if (
            intent.deviceStage == DeviceStage.Ready ||
            intent.deviceStage == DeviceStage.Removed ||
            intent.deviceStage == DeviceStage.Paused
        ) {
            return false
        }
        updateIntent(
            intent.copy(
                deviceStage = DeviceStage.Paused,
                userPaused = true,
                resumeDeviceStage = intent.deviceStage,
                updatedAt = updatedAt,
            ),
        )
        return true
    }

    @Transaction
    open suspend fun resumeDeviceTransfer(
        serverIdentity: String,
        youtubeId: String,
        updatedAt: Long,
    ): Boolean {
        val intent = getIntent(serverIdentity, youtubeId) ?: return false
        if (!intent.userPaused || intent.deviceStage != DeviceStage.Paused) return false
        updateIntent(
            intent.copy(
                deviceStage = if (intent.serverStage == ServerStage.Ready &&
                    intent.resumeDeviceStage in listOf(null, DeviceStage.Pending, DeviceStage.WaitingForNetwork)
                ) DeviceStage.Queued else intent.resumeDeviceStage ?: DeviceStage.Pending,
                userPaused = false,
                resumeDeviceStage = null,
                updatedAt = updatedAt,
            ),
        )
        return true
    }

    @Transaction
    open suspend fun markFailed(
        serverIdentity: String,
        youtubeId: String,
        retryStage: RetryStage,
        errorCode: String,
        errorMessage: String,
        nextAttemptAt: Long?,
        updatedAt: Long,
    ): Boolean {
        val intent = getIntent(serverIdentity, youtubeId) ?: return false
        if (intent.deviceStage == DeviceStage.Removed) return false
        updateIntent(
            intent.copy(
                serverStage = if (retryStage == RetryStage.Server) ServerStage.Failed else intent.serverStage,
                deviceStage = if (retryStage == RetryStage.Server || intent.userPaused) intent.deviceStage else DeviceStage.Failed,
                resumeDeviceStage = if (intent.userPaused && retryStage != RetryStage.Server) DeviceStage.Failed else intent.resumeDeviceStage,
                retryStage = retryStage,
                retryCount = intent.retryCount + 1,
                nextAttemptAt = nextAttemptAt,
                errorCode = errorCode,
                errorMessage = errorMessage,
                updatedAt = updatedAt,
            ),
        )
        return true
    }

    @Upsert
    abstract suspend fun bindServerJob(binding: ServerJobBindingEntity)

    @Query(
        """
        SELECT * FROM server_job_bindings
        WHERE serverIdentity = :serverIdentity AND youtubeId = :youtubeId
        """,
    )
    abstract suspend fun getServerJobBinding(
        serverIdentity: String,
        youtubeId: String,
    ): ServerJobBindingEntity?

    @Query("DELETE FROM server_job_bindings WHERE serverIdentity = :server AND youtubeId = :youtubeId")
    protected abstract suspend fun deleteServerJobBinding(server: String, youtubeId: String)

    /** A confirmed source 404 invalidates the Ready claim and fences in-flight transfer callbacks. */
    @Transaction
    open suspend fun requeueMissingServerSource(
        server: String, youtubeId: String, generation: Long, at: Long,
    ): Boolean {
        val intent = getIntent(server, youtubeId) ?: return false
        if (intent.generation != generation || intent.serverStage != ServerStage.Ready ||
            intent.deviceStage in listOf(DeviceStage.Ready, DeviceStage.Removed)
        ) return false
        val video = getVideo(server, youtubeId) ?: return false
        // Do not use upsertVideo here: its sparse-metadata merge intentionally restores null fields.
        upsertVideoInternal(video.copy(serverVideoId = null, serverStatus = null,
            serverFileSizeBytes = null, metadataUpdatedAt = at))
        deleteServerJobBinding(server, youtubeId)
        deleteDeviceTransferBinding(server, youtubeId)
        updateIntent(intent.copy(
            generation = intent.generation + 1,
            serverStage = ServerStage.Pending,
            deviceStage = if (intent.userPaused) DeviceStage.Paused else DeviceStage.Pending,
            resumeDeviceStage = if (intent.userPaused) DeviceStage.Pending else null,
            retryStage = null, retryCount = 0, nextAttemptAt = null,
            errorCode = null, errorMessage = null, updatedAt = at,
        ))
        return true
    }

    @Query("SELECT * FROM server_job_bindings")
    abstract fun observeServerJobs(): Flow<List<ServerJobBindingEntity>>

    @Upsert
    abstract suspend fun bindDeviceTransfer(binding: DeviceTransferBindingEntity)

    @Query(
        """
        SELECT * FROM device_transfer_bindings
        WHERE serverIdentity = :serverIdentity AND youtubeId = :youtubeId
        """,
    )
    abstract suspend fun getDeviceTransferBinding(
        serverIdentity: String,
        youtubeId: String,
    ): DeviceTransferBindingEntity?

    @Query("SELECT * FROM device_transfer_bindings")
    abstract suspend fun getDeviceTransferBindings(): List<DeviceTransferBindingEntity>

    @Query("SELECT * FROM device_transfer_bindings")
    abstract fun observeDeviceTransfers(): Flow<List<DeviceTransferBindingEntity>>

    @Query("SELECT * FROM local_files WHERE kind = 'Media'")
    abstract suspend fun getMediaAssets(): List<LocalFileEntity>

    @Query("""
        UPDATE device_transfer_bindings SET bytesDownloaded = :bytes, totalBytes = :total,
            updatedAt = :updatedAt
        WHERE transferId = :id AND generation = :generation
    """)
    abstract suspend fun updateTransferProgress(id: String, generation: Long, bytes: Long,
                                                total: Long?, updatedAt: Long)

    @Transaction
    open suspend fun invalidateMissingMedia(file: LocalFileEntity, updatedAt: Long): Boolean {
        val intent = getIntent(file.serverIdentity, file.youtubeId) ?: return false
        if (intent.generation != file.generation || intent.deviceStage != DeviceStage.Ready) return false
        deleteLocalFileRows(file.serverIdentity, file.youtubeId)
        updateIntent(intent.copy(deviceStage = DeviceStage.Failed, retryStage = RetryStage.LocalValidation,
            errorCode = "media_missing", errorMessage = "Offline media is incomplete. Retry device sync.",
            updatedAt = updatedAt))
        return true
    }

    @Query("SELECT * FROM queue_intents WHERE serverStage = 'Ready' AND deviceStage NOT IN ('Ready', 'Removed')")
    abstract suspend fun getDeviceCandidates(): List<QueueIntentEntity>

    @Transaction
    open suspend fun updateDeviceStage(
        serverIdentity: String, youtubeId: String, generation: Long,
        stage: DeviceStage, code: String? = null, message: String? = null,
        updatedAt: Long,
    ): Boolean {
        val intent = getIntent(serverIdentity, youtubeId) ?: return false
        if (intent.generation != generation || intent.deviceStage == DeviceStage.Removed ||
            intent.deviceStage == DeviceStage.Ready || intent.userPaused) return false
        updateIntent(intent.copy(deviceStage = stage, errorCode = code, errorMessage = message,
            updatedAt = updatedAt))
        return true
    }

    @Transaction
    open suspend fun bindAndQueueDevice(binding: DeviceTransferBindingEntity, updatedAt: Long): Boolean {
        val intent = getIntent(binding.serverIdentity, binding.youtubeId) ?: return false
        if (intent.generation != binding.generation || intent.serverStage != ServerStage.Ready ||
            intent.deviceStage in listOf(DeviceStage.Removed, DeviceStage.Ready) || intent.userPaused) return false
        bindDeviceTransfer(binding)
        updateIntent(intent.copy(deviceStage = DeviceStage.Queued, errorCode = null,
            errorMessage = null, updatedAt = updatedAt))
        return true
    }

    @Transaction
    open suspend fun failDeviceTransfer(
        serverIdentity: String, youtubeId: String, generation: Long,
        code: String, message: String, updatedAt: Long,
    ): Boolean {
        val intent = getIntent(serverIdentity, youtubeId) ?: return false
        if (intent.generation != generation || intent.userPaused ||
            intent.deviceStage in listOf(DeviceStage.Removed, DeviceStage.Ready)) return false
        updateIntent(intent.copy(deviceStage = DeviceStage.Failed, retryStage = RetryStage.Device,
            retryCount = intent.retryCount + 1, nextAttemptAt = null,
            errorCode = code, errorMessage = message, updatedAt = updatedAt))
        return true
    }

    @Query(
        """
        DELETE FROM device_transfer_bindings
        WHERE serverIdentity = :serverIdentity AND youtubeId = :youtubeId
        """,
    )
    protected abstract suspend fun deleteDeviceTransferBinding(
        serverIdentity: String,
        youtubeId: String,
    )

    @Upsert
    protected abstract suspend fun upsertLocalFile(file: LocalFileEntity)

    @Query(
        """
        SELECT * FROM local_files
        WHERE serverIdentity = :serverIdentity AND youtubeId = :youtubeId
        ORDER BY kind, trackKey
        """,
    )
    abstract suspend fun getLocalFiles(
        serverIdentity: String,
        youtubeId: String,
    ): List<LocalFileEntity>

    @Transaction
    open suspend fun publishCompletedMedia(file: LocalFileEntity, updatedAt: Long): Boolean {
        require(file.kind == LocalFileKind.Media && file.trackKey.isEmpty())
        require(file.path.isNotBlank() && file.byteSize > 0)
        require(file.assetType != AssetType.Media3Cache || file.cacheKey != null)
        val intent = getIntent(file.serverIdentity, file.youtubeId) ?: return false
        if (intent.deviceStage == DeviceStage.Removed || intent.userPaused || intent.generation != file.generation) {
            return false
        }
        upsertLocalFile(file)
        deleteDeviceTransferBinding(file.serverIdentity, file.youtubeId)
        updateIntent(
            intent.copy(
                deviceStage = DeviceStage.Ready,
                retryStage = null,
                userPaused = false,
                resumeDeviceStage = null,
                nextAttemptAt = null,
                errorCode = null,
                errorMessage = null,
                updatedAt = updatedAt,
            ),
        )
        return true
    }

    @Upsert
    protected abstract suspend fun upsertPlaybackProgressInternal(progress: PlaybackProgressEntity)

    @Transaction
    open suspend fun upsertPlaybackProgress(progress: PlaybackProgressEntity) {
        val old = getPlaybackProgress(progress.serverIdentity, progress.youtubeId)
        val duration = progress.durationMs?.takeIf { it > 0 } ?: old?.durationMs
        upsertPlaybackProgressInternal(progress.copy(durationMs = duration,
            viewed = progress.viewed || old?.viewed == true || watchedEnough(progress.positionMs, duration)))
    }

    @Transaction
    open suspend fun setViewed(server: String, youtubeId: String, viewed: Boolean, at: Long): Boolean {
        val video = getVideo(server, youtubeId) ?: return false
        val old = getPlaybackProgress(server, youtubeId)
        upsertPlaybackProgressInternal(PlaybackProgressEntity(server, youtubeId,
            positionMs = if (viewed) old?.positionMs ?: 0 else 0,
            durationMs = old?.durationMs ?: video.durationMs,
            updatedAt = at, viewed = viewed))
        return true
    }

    @Query(
        """
        SELECT * FROM playback_progress
        WHERE serverIdentity = :serverIdentity AND youtubeId = :youtubeId
        """,
    )
    abstract suspend fun getPlaybackProgress(
        serverIdentity: String,
        youtubeId: String,
    ): PlaybackProgressEntity?

    @Query(
        """
        DELETE FROM local_files
        WHERE serverIdentity = :serverIdentity AND youtubeId = :youtubeId
        """,
    )
    protected abstract suspend fun deleteLocalFileRows(serverIdentity: String, youtubeId: String)

    @Upsert
    protected abstract suspend fun upsertKeepOfflineExclusion(exclusion: KeepOfflineExclusionEntity)

    @Query(
        """
        DELETE FROM keep_offline_exclusions
        WHERE serverIdentity = :serverIdentity AND youtubeId = :youtubeId
        """,
    )
    abstract suspend fun clearKeepOfflineExclusion(serverIdentity: String, youtubeId: String)

    @Transaction
    open suspend fun recordLocalRemoval(
        serverIdentity: String,
        youtubeId: String,
        excludedAt: Long,
        clearAll: Boolean = false,
    ): Long? {
        val intent = getIntent(serverIdentity, youtubeId) ?: return null
        require(clearAll || !belongsToCollection(serverIdentity, youtubeId)) {
            "Remove this video from all collections before removing its phone copy."
        }
        val nextGeneration = intent.generation + 1
        updateIntent(
            intent.copy(
                deviceStage = DeviceStage.Removed,
                retryStage = null,
                userPaused = false,
                resumeDeviceStage = null,
                nextAttemptAt = null,
                errorCode = null,
                errorMessage = null,
                generation = nextGeneration,
                updatedAt = excludedAt,
            ),
        )
        deleteLocalFileRows(serverIdentity, youtubeId)
        deleteDeviceTransferBinding(serverIdentity, youtubeId)
        upsertKeepOfflineExclusion(
            KeepOfflineExclusionEntity(serverIdentity, youtubeId, excludedAt),
        )
        return nextGeneration
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertCollection(collection: CollectionEntity)

    @Update
    abstract suspend fun updateCollection(collection: CollectionEntity)

    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE")
    abstract fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM collection_memberships WHERE serverIdentity = :server AND youtubeId = :youtubeId)")
    abstract suspend fun belongsToCollection(server: String, youtubeId: String): Boolean

    @Query("UPDATE collections SET keepOffline = 1 WHERE keepOffline = 0")
    protected abstract suspend fun enableAllCollectionsOffline(): Int

    @Query("""DELETE FROM keep_offline_exclusions WHERE EXISTS(
        SELECT 1 FROM collection_memberships member JOIN collections c ON c.id = member.collectionId
        WHERE member.serverIdentity = keep_offline_exclusions.serverIdentity
            AND member.youtubeId = keep_offline_exclusions.youtubeId AND c.keepOffline = 0)""")
    protected abstract suspend fun clearLegacyMemberExclusions(): Int

    /** Reconcile old optional collections before evaluating automatic offline intent. */
    @Transaction
    open suspend fun enforceCollectionsOffline() {
        clearLegacyMemberExclusions()
        enableAllCollectionsOffline()
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addCollectionMembership(membership: CollectionMembershipEntity): Long

    @Delete
    abstract suspend fun removeCollectionMembership(membership: CollectionMembershipEntity)

    @Query(
        """
        SELECT DISTINCT video.* FROM offline_videos AS video
        INNER JOIN collection_memberships AS membership
            ON membership.serverIdentity = video.serverIdentity
            AND membership.youtubeId = video.youtubeId
        INNER JOIN collections AS collection ON collection.id = membership.collectionId
        LEFT JOIN keep_offline_exclusions AS exclusion
            ON exclusion.serverIdentity = video.serverIdentity
            AND exclusion.youtubeId = video.youtubeId
        WHERE collection.keepOffline = 1 AND exclusion.youtubeId IS NULL
        ORDER BY video.title COLLATE NOCASE
        """,
    )
    abstract suspend fun getKeepOfflineCandidates(): List<OfflineVideoEntity>

    @Query("SELECT * FROM local_files")
    abstract fun observeLocalFiles(): Flow<List<LocalFileEntity>>

    @Query("SELECT * FROM playback_progress")
    abstract fun observePlaybackProgress(): Flow<List<PlaybackProgressEntity>>

    @Query("SELECT * FROM collection_memberships")
    abstract fun observeMemberships(): Flow<List<CollectionMembershipEntity>>

    @Query("SELECT * FROM collections WHERE id = :id")
    abstract suspend fun getCollection(id: String): CollectionEntity?

    @Query("SELECT * FROM collections")
    protected abstract suspend fun getCollections(): List<CollectionEntity>

    @Query("DELETE FROM collections WHERE id = :id")
    abstract suspend fun deleteCollection(id: String): Int

    @Query("DELETE FROM collection_memberships WHERE collectionId = :id AND serverIdentity = :server AND youtubeId = :youtubeId")
    abstract suspend fun removeMembership(id: String, server: String, youtubeId: String): Int

    @Query("SELECT video.* FROM offline_videos video INNER JOIN collection_memberships member ON member.serverIdentity = video.serverIdentity AND member.youtubeId = video.youtubeId WHERE member.collectionId = :id")
    abstract suspend fun getCollectionVideos(id: String): List<OfflineVideoEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM keep_offline_exclusions WHERE serverIdentity = :server AND youtubeId = :youtubeId)")
    abstract suspend fun isKeepOfflineExcluded(server: String, youtubeId: String): Boolean

    /** Transactional validation extends SQLite NOCASE to Unicode names as well. */
    @Transaction
    open suspend fun saveCollection(collection: CollectionEntity, create: Boolean) {
        val name = collection.name.trim()
        require(name.isNotEmpty() && name.length <= 100 && name.none { it.isISOControl() }) {
            "Collection name must contain 1–100 characters without control characters."
        }
        require(getCollections().none { it.id != collection.id && it.name.equals(name, ignoreCase = true) }) {
            "A collection with that name already exists."
        }
        if (create) insertCollection(collection.copy(name = name, keepOffline = true)) else {
            requireNotNull(getCollection(collection.id)) { "Collection no longer exists." }
            updateCollection(collection.copy(name = name, keepOffline = true))
        }
    }

    @Transaction
    open suspend fun addCollectionVideo(id: String, video: OfflineVideoEntity, at: Long): Boolean {
        requireNotNull(getCollection(id)) { "Collection no longer exists." }
        upsertVideo(video)
        return addCollectionMembership(CollectionMembershipEntity(id, video.serverIdentity, video.youtubeId, at)) != -1L
    }

    /** Recheck exclusions inside the enqueue transaction to avoid resurrecting a removal. */
    @Transaction
    open suspend fun enqueueKept(video: OfflineVideoEntity, at: Long): EnqueueResult {
        if (isKeepOfflineExcluded(video.serverIdentity, video.youtubeId)) return EnqueueResult.Duplicate
        if (getKeepOfflineCandidates().none { it.serverIdentity == video.serverIdentity && it.youtubeId == video.youtubeId }) {
            return EnqueueResult.Duplicate
        }
        return enqueue(video, at)
    }

    @Transaction
    open suspend fun retryFailed(server: String, youtubeId: String, at: Long): Boolean {
        val intent = getIntent(server, youtubeId) ?: return false
        if (intent.deviceStage in listOf(DeviceStage.Removed, DeviceStage.Ready)) return false
        if (intent.retryStage == null && intent.serverStage != ServerStage.Failed && intent.deviceStage != DeviceStage.Failed) return false
        val retryServer = intent.serverStage == ServerStage.Failed
        // A terminal job ID must not be polled again on explicit retry. Reconciliation still
        // checks the remote library and active jobs before it can issue another POST.
        if (retryServer) deleteServerJobBinding(server, youtubeId)
        val device = if (intent.serverStage == ServerStage.Ready) DeviceStage.Queued else DeviceStage.Pending
        updateIntent(intent.copy(
            serverStage = if (retryServer) ServerStage.Pending else intent.serverStage,
            deviceStage = if (intent.userPaused) DeviceStage.Paused else device,
            resumeDeviceStage = if (intent.userPaused) device else null,
            retryStage = null, retryCount = 0, nextAttemptAt = null,
            errorCode = null, errorMessage = null, updatedAt = at,
            generation = if (retryServer) intent.generation + 1 else intent.generation,
        ))
        return true
    }

    /** Optional files can only attach to a completed copy owned by this generation. */
    @Transaction
    open suspend fun publishAuxiliaryFile(file: LocalFileEntity): Boolean {
        require(file.kind != LocalFileKind.Media && file.assetType == AssetType.File)
        require(file.path.isNotBlank() && file.byteSize > 0)
        val intent = getIntent(file.serverIdentity, file.youtubeId) ?: return false
        if (intent.generation != file.generation || intent.deviceStage != DeviceStage.Ready) return false
        upsertLocalFile(file)
        return true
    }
}
