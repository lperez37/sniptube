package com.sniptube.android.data.library

import com.sniptube.android.AppContainer
import com.sniptube.android.data.local.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Device-local intent operations. No server deletion capability is accepted here.
 * [removeDeviceCopy] must invalidate Room ownership before asking Media3 to remove bytes.
 */
class LibraryActions(
    private val dao: OfflineDao,
    private val onQueued: suspend (String) -> Unit,
    private val refreshPolicy: suspend () -> Unit,
    private val removeDeviceCopy: suspend (String, String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val allowCellular: (Set<VideoKey>) -> Unit = {},
    private val wifiReason: () -> String? = { "Wi-Fi eligibility unavailable." },
    private val manualReason: () -> String? = { "Network eligibility unavailable." },
) {
    constructor(container: AppContainer) : this(
        container.database.offlineDao(),
        { container.acquisitionCoordinator.queued(it) },
        { container.deviceTransfers.refreshPolicy() },
        { server, id -> container.deviceTransfers.removeLocal(server, id) },
        allowCellular = { container.deviceTransfers.approveCellular(it) },
        wifiReason = { container.deviceTransfers.gate.eligibility() },
        manualReason = { container.deviceTransfers.gate.manualEligibility() },
    )

    fun observe(): Flow<LibrarySnapshot> = combine(
        combine(dao.observeVideos(), dao.observeQueue(), dao.observeCollections()) { videos, queue, collections ->
            LibrarySnapshot(videos = videos, queue = queue, collections = collections)
        },
        dao.observeMemberships(), dao.observeLocalFiles(), dao.observePlaybackProgress(),
    ) { snapshot, memberships, assets, playback ->
        snapshot.copy(memberships = memberships, assets = assets, playback = playback)
    }

    suspend fun enqueue(videos: List<OfflineVideoEntity>): BatchResult = enqueueVideos(videos, explicit = true)

    suspend fun pause(keys: List<VideoKey>): BatchResult = batch(keys) { key ->
        dao.pauseDeviceTransfer(key.serverIdentity, key.youtubeId, clock()).also {
            if (it) refreshPolicy()
        }
    }

    suspend fun resume(keys: List<VideoKey>): BatchResult = batch(keys) { key ->
        dao.resumeDeviceTransfer(key.serverIdentity, key.youtubeId, clock()).also {
            if (it) wake(key.serverIdentity)
        }
    }

    suspend fun retry(keys: List<VideoKey>): BatchResult = batch(keys) { key ->
        dao.retryFailed(key.serverIdentity, key.youtubeId, clock()).also {
            if (it) wake(key.serverIdentity)
        }
    }

    suspend fun removeLocal(keys: List<VideoKey>): BatchResult = batch(keys) { key ->
        val intent = dao.getIntent(key.serverIdentity, key.youtubeId)
        if (intent == null || intent.deviceStage == DeviceStage.Removed) false else {
            removeDeviceCopy(key.serverIdentity, key.youtubeId)
            true
        }
    }

    suspend fun setViewed(key: VideoKey, viewed: Boolean): Boolean =
        dao.setViewed(key.serverIdentity, key.youtubeId, viewed, clock())

    suspend fun createCollection(name: String): String {
        val id = UUID.randomUUID().toString()
        val now = clock()
        dao.saveCollection(CollectionEntity(id, name, createdAt = now, updatedAt = now), create = true)
        return id
    }

    suspend fun renameCollection(id: String, name: String) {
        dao.saveCollection(collection(id).copy(name = name, updatedAt = clock()), create = false)
    }

    suspend fun deleteCollection(id: String) {
        require(dao.deleteCollection(id) != 0) { "Collection no longer exists." }
    }

    suspend fun addToCollection(id: String, videos: List<OfflineVideoEntity>): BatchResult {
        collection(id)
        dao.enforceCollectionsOffline()
        val byKey = videos.associateBy { it.key() }
        return batch(byKey.keys.toList()) { key ->
            val video = byKey.getValue(key)
            val added = dao.addCollectionVideo(id, video, clock())
            // Adding to an always-offline collection is an explicit request: clear any
            // exclusion left by a prior removal when the video had no membership.
            val result = dao.enqueue(video, clock())
            if (result != EnqueueResult.Duplicate) wake(key.serverIdentity)
            added
        }
    }

    suspend fun removeFromCollection(id: String, keys: List<VideoKey>): BatchResult {
        collection(id)
        return batch(keys) { dao.removeMembership(id, it.serverIdentity, it.youtubeId) != 0 }
    }

    suspend fun downloadCollection(id: String): BatchResult {
        collection(id)
        return enqueue(dao.getCollectionVideos(id))
    }

    /** Call from application-owned restart reconciliation, not a screen coroutine. */
    suspend fun reconcileKeepOffline(): BatchResult {
        dao.enforceCollectionsOffline()
        return enqueueVideos(dao.getKeepOfflineCandidates(), explicit = false)
    }

    fun wifiEligibility(): String? = wifiReason()
    fun manualEligibility(): String? = manualReason()

    /** Only an explicit cellular confirmation authorizes these collection members for this process. */
    suspend fun syncCollectionNow(id: String, onCellular: Boolean): BatchResult {
        collection(id)
        require(if (onCellular) manualReason() == null else wifiReason() == null) {
            (if (onCellular) manualReason() else wifiReason()) ?: "Network changed; retry Sync now."
        }
        val videos = dao.getCollectionVideos(id).distinctBy { it.key() }
        if (onCellular) allowCellular(videos.map { it.key() }.toSet())
        val result = batch(videos.map { it.key() }) { key ->
            val video = videos.first { it.key() == key }
            val added = dao.enqueue(video, clock())
            val intent = dao.getIntent(key.serverIdentity, key.youtubeId)
            val resumed = intent?.userPaused == true && dao.resumeDeviceTransfer(key.serverIdentity, key.youtubeId, clock())
            val retried = intent != null && (intent.serverStage == ServerStage.Failed || intent.deviceStage == DeviceStage.Failed) &&
                dao.retryFailed(key.serverIdentity, key.youtubeId, clock())
            added != EnqueueResult.Duplicate || resumed || retried
        }
        videos.map { it.serverIdentity }.distinct().forEach { wake(it) }
        return result
    }

    private suspend fun collection(id: String): CollectionEntity =
        requireNotNull(dao.getCollection(id)) { "Collection no longer exists." }

    private suspend fun enqueueVideos(videos: List<OfflineVideoEntity>, explicit: Boolean): BatchResult {
        val byKey = videos.associateBy { it.key() }
        return batch(byKey.keys.toList()) { key ->
            val video = byKey.getValue(key)
            val result = if (explicit) dao.enqueue(video, clock()) else dao.enqueueKept(video, clock())
            if (result != EnqueueResult.Duplicate) wake(key.serverIdentity)
            result != EnqueueResult.Duplicate
        }
    }

    private suspend fun wake(server: String) {
        onQueued(server)
        refreshPolicy()
    }

    private suspend fun batch(keys: List<VideoKey>, action: suspend (VideoKey) -> Boolean): BatchResult {
        var succeeded = 0
        var skipped = 0
        val failures = mutableListOf<String>()
        for (key in keys.distinct()) {
            try {
                if (action(key)) succeeded++ else skipped++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += "${key.youtubeId}: ${error.message ?: "Operation failed"}"
            }
        }
        return BatchResult(succeeded, skipped, failures)
    }

    private fun OfflineVideoEntity.key() = VideoKey(serverIdentity, youtubeId)
}
