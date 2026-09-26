package com.sniptube.android.data.transfer

import android.os.StatFs
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.sniptube.android.SniptubeApplication
import com.sniptube.android.data.api.ServerConnection
import com.sniptube.android.data.local.AssetType
import com.sniptube.android.data.local.DeviceStage
import com.sniptube.android.data.local.DeviceTransferBindingEntity
import com.sniptube.android.data.local.LocalFileEntity
import com.sniptube.android.data.local.LocalFileKind
import com.sniptube.android.data.local.OfflineDao
import com.sniptube.android.data.local.QueueIntentEntity
import com.sniptube.android.data.playback.extrasPath
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import com.sniptube.android.data.library.VideoKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

/** Room owns intent; DownloadIndex/cache owns bytes. Never infer completion from Room alone. */
@OptIn(UnstableApi::class)
class DeviceTransfers(
    private val app: SniptubeApplication,
    private val dao: OfflineDao,
    private val media: MediaDownloads,
    private val onCompleted: suspend (String, String) -> Unit = { _, _ -> },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val timeout = app.getSharedPreferences("device_sync", 0)
    private val verified = mutableSetOf<String>()
    private val manuallyApproved = ConcurrentHashMap.newKeySet<String>()
    private val sourceOwners = ConcurrentHashMap<String, String>()
    private var foreground = false
    private var loop: Job? = null
    val gate = WifiGate(app) { scope.launch { reconcileStops() } }

    /** Consent is in memory, scoped to selected copies, and never survives a process restart. */
    fun approveCellular(keys: Set<VideoKey>) {
        keys.forEach { manuallyApproved.add(transferId(it.serverIdentity, it.youtubeId)) }
        refreshPolicy()
    }

    fun routeReason(url: String): String? =
        if (sourceOwners[url]?.let { it in manuallyApproved } == true) gate.manualEligibility() else gate.eligibility()

    private fun routeReasonForTransfer(id: String): String? =
        if (id in manuallyApproved) gate.manualEligibility() else gate.eligibility()

    init {
        media.manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                scope.launch { mutex.withLock { publish(download, finalException) } }
            }

            override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                scope.launch { pump() }
            }
        })
    }

    fun restore() {
        scope.launch {
            dao.getMediaAssets().filter { it.assetType == AssetType.Media3Cache }.forEach { file ->
                if (file.cacheKey == null || media.complete(file.path.removePrefix("media3:"), file.cacheKey) == null) {
                    mutex.withLock {
                        val extras = auxiliaryFiles(file.serverIdentity, file.youtubeId, file.generation)
                        if (dao.invalidateMissingMedia(file, System.currentTimeMillis())) deleteAuxiliary(extras)
                    }
                }
            }
            sweepOrphanExtras()
            reconcileStops()
        }
    }

    fun enterForeground() {
        foreground = true
        timeout.edit().putBoolean("timed_out", false).apply()
        loop?.cancel()
        loop = scope.launch {
            reconcileStops()
            while (true) {
                pump()
                sampleProgress()
                delay(4_000)
            }
        }
    }

    fun leaveForeground() {
        foreground = false
        loop?.cancel()
        loop = null
        scope.launch { reconcileStops() }
    }

    fun onServiceTimeout() {
        manuallyApproved.clear()
        timeout.edit().putBoolean("timed_out", true).apply()
        media.manager.currentDownloads.forEach { media.manager.setStopReason(it.request.id, STOP_BLOCKED) }
        scope.launch {
            dao.getDeviceCandidates().filter { !it.userPaused }.forEach {
                dao.updateDeviceStage(it.serverIdentity, it.youtubeId, it.generation,
                    DeviceStage.WaitingForNetwork, "service_timeout",
                    "Android paused background sync; open the app to resume.", System.currentTimeMillis())
            }
        }
    }

    private suspend fun reconcileStops() {
        val intents = dao.getDeviceCandidates().associateBy { transferId(it.serverIdentity, it.youtubeId) }
        val budget = app.container.offlineStorageBudget.limitBytes()
        val projected = media.manager.currentDownloads.filter { it.state in listOf(
            Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_STOPPED, Download.STATE_RESTARTING) }
            .fold(media.cache.cacheSpace) { used, download ->
                val pending = (download.contentLength - download.bytesDownloaded).coerceAtLeast(0)
                if (Long.MAX_VALUE - used < pending) Long.MAX_VALUE else used + pending
            }
        val aboveBudget = budget != null && projected > budget
        media.manager.currentDownloads.forEach { download ->
            val intent = intents[download.request.id]
            if (intent?.userPaused == true) manuallyApproved.remove(download.request.id)
            val reason = if (aboveBudget) "Offline storage budget reached. Increase it in Settings or remove phone copies."
                else routeReasonForTransfer(download.request.id)
            val stop = if (intent == null || intent.userPaused || reason != null ||
                timeout.getBoolean("timed_out", false) ||
                !foreground && (download.state == Download.STATE_QUEUED ||
                    download.stopReason != Download.STOP_REASON_NONE)) STOP_BLOCKED
                else Download.STOP_REASON_NONE
            if (download.stopReason != stop) {
                // Resume only after pump has revalidated the source and Room generation.
                if (stop != Download.STOP_REASON_NONE) {
                    verified.remove(download.request.id)
                    media.forget(download.request.uri.toString())
                    media.manager.setStopReason(download.request.id, stop)
                }
            }
            if (intent != null && !intent.userPaused && reason != null) {
                dao.updateDeviceStage(intent.serverIdentity, intent.youtubeId, intent.generation,
                    DeviceStage.WaitingForNetwork, if (aboveBudget) "storage_budget" else "wifi_required", reason,
                    System.currentTimeMillis())
            }
        }
        if (foreground && !timeout.getBoolean("timed_out", false)) pump()
    }

    private suspend fun pump() = mutex.withLock {
        if (!foreground || timeout.getBoolean("timed_out", false)) return@withLock
        val candidates = dao.getDeviceCandidates().filter { !it.userPaused &&
            it.deviceStage != DeviceStage.Failed }.sortedBy { it.requestedAt }
        candidates.forEach { intent ->
            val reason = routeReasonForTransfer(transferId(intent.serverIdentity, intent.youtubeId))
            if (reason != null) {
                dao.updateDeviceStage(intent.serverIdentity, intent.youtubeId, intent.generation,
                    DeviceStage.WaitingForNetwork, "wifi_required", reason, System.currentTimeMillis())
                return@forEach
            }
            try { start(intent) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val lostWifi = routeReasonForTransfer(transferId(intent.serverIdentity, intent.youtubeId))
                if (lostWifi != null) {
                    dao.updateDeviceStage(intent.serverIdentity, intent.youtubeId, intent.generation,
                        DeviceStage.WaitingForNetwork, "wifi_required", lostWifi, System.currentTimeMillis())
                } else {
                    val message = error.message ?: "Device sync failed; retry this item."
                    dao.failDeviceTransfer(intent.serverIdentity, intent.youtubeId, intent.generation,
                        "device_transfer", message, System.currentTimeMillis())
                }
            }
        }
    }

    private suspend fun start(intent: QueueIntentEntity) {
        val id = transferId(intent.serverIdentity, intent.youtubeId)
        val video = dao.getVideo(intent.serverIdentity, intent.youtubeId) ?: return
        val videoId = video.serverVideoId ?: return
        val url = app.container.apiClient(ServerConnection.parse(intent.serverIdentity))
            .sourceUrl(videoId).toString()
        sourceOwners[url] = id
        val previous = dao.getDeviceTransferBinding(intent.serverIdentity, intent.youtubeId)
        val existing = media.manager.downloadIndex.getDownload(id)
        if (existing?.state in listOf(Download.STATE_REMOVING, Download.STATE_RESTARTING)) return
        if (existing != null && (previous == null || previous.generation != intent.generation ||
                existing.request.uri.toString() != url ||
                !existing.request.data.contentEquals(bindingToken(previous)))) {
            verified.remove(id)
            media.forget(existing.request.uri.toString())
            media.manager.removeDownload(id)
            return
        }
        if (existing != null && id in verified &&
            existing.state in listOf(Download.STATE_DOWNLOADING, Download.STATE_QUEUED,
                Download.STATE_STOPPED)) {
            if (existing.stopReason != Download.STOP_REASON_NONE && !intent.userPaused && routeReasonForTransfer(id) == null) {
                resumeThroughService(id)
            }
            return
        }
        if (existing?.state == Download.STATE_COMPLETED && id in verified) {
            if (media.complete(id, id) != null && previous?.generation == intent.generation) {
                publish(existing, null)
                return
            }
            media.forget(url)
            verified.remove(id)
            media.manager.removeDownload(id)
            return
        }
        if (existing?.state == Download.STATE_FAILED) {
            // A restored download may have failed the transport's pre-probe guard before
            // foreground recovery. Remove its index/cache entry before a fresh validated add.
            verified.remove(id)
            media.manager.removeDownload(id)
            return
        }
        // Probe before accepting cached spans, even after process death. ID alone is not an
        // immutable representation; a changed strong validator forces removal through Media3.
        val identity = try {
            withContext(Dispatchers.IO) { media.probe(url) }
        } catch (error: SourceHttpException) {
            if (error.status != 404) throw error
            manuallyApproved.remove(id)
            sourceOwners.remove(url, id)
            // Only an actual missing source resets server acquisition. Auth errors, redirects,
            // invalid representations and transient failures retain their ordinary device error.
            if (dao.requeueMissingServerSource(intent.serverIdentity, intent.youtubeId,
                    intent.generation, System.currentTimeMillis())) {
                verified.remove(id)
                media.forget(url)
                if (existing != null) media.manager.removeDownload(id)
                app.container.acquisitionCoordinator.queued(intent.serverIdentity)
            }
            return
        }
        val current = dao.getIntent(intent.serverIdentity, intent.youtubeId) ?: return
        if (current.generation != intent.generation || current.userPaused ||
            current.deviceStage in listOf(DeviceStage.Removed, DeviceStage.Ready)) return
        val lostWifi = routeReasonForTransfer(id)
        if (lostWifi != null || !foreground) {
            dao.updateDeviceStage(intent.serverIdentity, intent.youtubeId, intent.generation,
                DeviceStage.WaitingForNetwork,
                if (lostWifi != null) "wifi_required" else "open_app_to_sync",
                lostWifi ?: "Open Sniptube to continue device sync.", System.currentTimeMillis())
            return
        }
        val representationChanged = previous != null && (previous.etag != identity.etag ||
            previous.totalBytes != identity.size || previous.generation != intent.generation)
        if (representationChanged) {
            manuallyApproved.remove(id)
            if (existing != null) {
                verified.remove(id)
                media.forget(url)
                media.manager.removeDownload(id)
                return
            }
        }
        routeReasonForTransfer(id)?.let { reason ->
            dao.updateDeviceStage(intent.serverIdentity, intent.youtubeId, intent.generation,
                DeviceStage.WaitingForNetwork, "wifi_required",
                reason + if (representationChanged) " Source changed; confirm Sync now again to use cellular." else "",
                System.currentTimeMillis())
            return
        }
        if (existing == null) {
            // Orphan spans have no index proving their representation or generation.
            media.cache.removeResource(id)
        }
        media.remember(url, identity)
        verified.add(id)
        if (existing?.state == Download.STATE_COMPLETED) {
            if (media.complete(id, id) != null && previous?.generation == intent.generation) {
                publish(existing, null)
            } else {
                verified.remove(id)
                media.forget(url)
                media.manager.removeDownload(id)
            }
            return
        }
        val free = StatFs(app.filesDir.path).availableBytes
        val downloaded = media.cache.getCachedBytes(id, 0, identity.size).coerceIn(0, identity.size)
        val otherIds = dao.getDeviceCandidates().filter { !it.userPaused &&
            it.deviceStage in listOf(DeviceStage.Queued, DeviceStage.Running) }
            .map { transferId(it.serverIdentity, it.youtubeId) }.toSet() - id
        val reserved = dao.getDeviceTransferBindings().filter { it.transferId in otherIds }
            .fold(0L) { total, other ->
                val size = other.totalBytes ?: 0L
                val remaining = (size - media.cache.getCachedBytes(other.transferId, 0, size.coerceAtLeast(1)))
                    .coerceAtLeast(0)
                if (Long.MAX_VALUE - total < remaining) Long.MAX_VALUE else total + remaining
            }
        val usable = (free - 128L * 1024 * 1024).coerceAtLeast(0)
        if (reserved > usable || identity.size - downloaded > usable - reserved) {
            throw IOException("Not enough device space for this video. Free space and retry.")
        }
        val limit = app.container.offlineStorageBudget.limitBytes()
        if (limit != null) {
            val projected = media.cache.cacheSpace.toDouble() + reserved + (identity.size - downloaded)
            if (projected > limit) {
                dao.updateDeviceStage(intent.serverIdentity, intent.youtubeId, intent.generation,
                    DeviceStage.WaitingForNetwork, "storage_budget",
                    "Offline storage budget reached. Increase it in Settings or remove phone copies.",
                    System.currentTimeMillis())
                return
            }
        }
        val binding = DeviceTransferBindingEntity(intent.serverIdentity, intent.youtubeId, id,
            "", "media3:$id", downloaded, identity.size, identity.etag, null,
            intent.generation, System.currentTimeMillis(), id)
        if (!dao.bindAndQueueDevice(binding, System.currentTimeMillis())) return
        if (existing == null) {
            val request = DownloadRequest.Builder(id, android.net.Uri.parse(url))
                .setMimeType(MimeTypes.VIDEO_UNKNOWN)
                .setCustomCacheKey(id)
                .setData(bindingToken(binding))
                .build()
            try {
                DownloadService.sendAddDownload(app, SniptubeDownloadService::class.java,
                    request, true)
            } catch (error: RuntimeException) {
                // A previous tap does not grant indefinite background FGS-start rights.
                dao.updateDeviceStage(intent.serverIdentity, intent.youtubeId, intent.generation,
                    DeviceStage.WaitingForNetwork, "open_app_to_sync",
                    "Open Sniptube to continue device sync.", System.currentTimeMillis())
                if (error.javaClass.simpleName != "ForegroundServiceStartNotAllowedException") throw error
            }
        } else if (existing.stopReason != Download.STOP_REASON_NONE) {
            resumeThroughService(id)
        }
    }

    private suspend fun resumeThroughService(id: String) {
        if (!foreground) return
        try {
            DownloadService.sendSetStopReason(app, SniptubeDownloadService::class.java,
                id, Download.STOP_REASON_NONE, true)
        } catch (error: RuntimeException) {
            val binding = dao.getDeviceTransferBindings().firstOrNull { it.transferId == id } ?: return
            dao.updateDeviceStage(binding.serverIdentity, binding.youtubeId, binding.generation,
                DeviceStage.WaitingForNetwork, "open_app_to_sync",
                "Open Sniptube to continue device sync.", System.currentTimeMillis())
            if (error.javaClass.simpleName != "ForegroundServiceStartNotAllowedException") throw error
        }
    }

    private suspend fun publish(download: Download, error: Exception?) {
        val binding = dao.getDeviceTransferBindings().firstOrNull { it.transferId == download.request.id } ?: return
        val intent = dao.getIntent(binding.serverIdentity, binding.youtubeId) ?: return
        if (binding.generation != intent.generation || intent.userPaused ||
            intent.deviceStage == DeviceStage.Removed ||
            !download.request.data.contentEquals(bindingToken(binding))) return
        when (download.state) {
            Download.STATE_COMPLETED -> {
                // A restored index may finish entirely from cached spans before this process
                // has validated the representation. Only the foreground probe may publish it.
                if (download.request.id !in verified) return
                if (media.complete(binding.transferId, binding.cacheKey ?: "") == null ||
                    download.contentLength != binding.totalBytes) {
                    dao.failDeviceTransfer(intent.serverIdentity, intent.youtubeId, intent.generation,
                        "cache_incomplete", "Media cache is incomplete; retry sync.",
                        System.currentTimeMillis())
                    return
                }
                val published = dao.publishCompletedMedia(LocalFileEntity(intent.serverIdentity, intent.youtubeId,
                    LocalFileKind.Media, path = "media3:${binding.transferId}",
                    byteSize = download.contentLength, contentType = "video/*", etag = binding.etag,
                    generation = intent.generation, validatedAt = System.currentTimeMillis(),
                    assetType = AssetType.Media3Cache, cacheKey = binding.cacheKey),
                    System.currentTimeMillis())
                if (published) scope.launch {
                    manuallyApproved.remove(binding.transferId)
                    sourceOwners.remove(download.request.uri.toString(), binding.transferId)
                    try {
                        onCompleted(intent.serverIdentity, intent.youtubeId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Optional metadata must never make verified media unavailable.
                        android.util.Log.w("DeviceTransfers", "Optional completion work failed")
                    }
                }
            }
            Download.STATE_FAILED -> {
                val lostWifi = routeReasonForTransfer(binding.transferId)
                if (lostWifi != null) {
                    dao.updateDeviceStage(intent.serverIdentity, intent.youtubeId, intent.generation,
                        DeviceStage.WaitingForNetwork, "wifi_required", lostWifi, System.currentTimeMillis())
                } else if (download.request.id !in verified) {
                    // A restored index can run before this process has validated its source.
                    // The transport rejects it without reading bytes; allow foreground pump
                    // to discard the failed index and re-probe, rather than stranding intent.
                    dao.updateDeviceStage(intent.serverIdentity, intent.youtubeId, intent.generation,
                        DeviceStage.WaitingForNetwork, "open_app_to_sync",
                        "Open Sniptube to validate the source and continue sync.",
                        System.currentTimeMillis())
                } else {
                    manuallyApproved.remove(binding.transferId)
                    sourceOwners.remove(download.request.uri.toString(), binding.transferId)
                    dao.failDeviceTransfer(intent.serverIdentity, intent.youtubeId,
                        intent.generation, "device_transfer", error?.message ?: "Device download failed.",
                        System.currentTimeMillis())
                }
            }
            Download.STATE_DOWNLOADING -> {
                val networkReason = routeReasonForTransfer(binding.transferId)
                dao.updateDeviceStage(intent.serverIdentity, intent.youtubeId, intent.generation,
                    if (networkReason == null) DeviceStage.Running else DeviceStage.WaitingForNetwork,
                    if (networkReason == null) null else "wifi_required", networkReason,
                    System.currentTimeMillis())
                if (networkReason != null) media.manager.setStopReason(download.request.id, STOP_BLOCKED)
            }
            else -> Unit // Queued/stopped/removing are not completed media.
        }
    }

    private suspend fun sampleProgress() {
        val bindings = dao.getDeviceTransferBindings().associateBy { it.transferId }
        media.manager.currentDownloads.forEach { download ->
            val binding = bindings[download.request.id] ?: return@forEach
            dao.updateTransferProgress(binding.transferId, binding.generation,
                download.bytesDownloaded, download.contentLength.takeIf { it > 0 } ?: binding.totalBytes,
                System.currentTimeMillis())
        }
    }

    /** Room invalidation precedes Media3 removal so callbacks cannot republish stale bytes. */
    suspend fun removeLocal(serverIdentity: String, youtubeId: String, clearAll: Boolean = false) = mutex.withLock {
        val id = transferId(serverIdentity, youtubeId)
        val oldGeneration = dao.getIntent(serverIdentity, youtubeId)?.generation
        val extras = oldGeneration?.let { auxiliaryFiles(serverIdentity, youtubeId, it) }.orEmpty()
        if (dao.recordLocalRemoval(serverIdentity, youtubeId, System.currentTimeMillis(), clearAll) != null) {
            manuallyApproved.remove(id)
            verified.remove(id)
            try {
                dao.getVideo(serverIdentity, youtubeId)?.serverVideoId?.let { videoId ->
                    media.forget(app.container.apiClient(ServerConnection.parse(serverIdentity))
                        .sourceUrl(videoId).toString())
                }
                media.manager.removeDownload(id)
            } finally {
                deleteAuxiliary(extras)
            }
        }
    }

    /** Explicit storage reset: clear phone bytes but keep metadata, collection membership and progress. */
    suspend fun clearAllLocal(): Pair<Int, Int> {
        val intents = dao.observeQueue().first().filter { it.deviceStage != DeviceStage.Removed }
        var removed = 0
        var failures = 0
        intents.forEach { intent ->
            try {
                removeLocal(intent.serverIdentity, intent.youtubeId, clearAll = true)
                removed++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failures++ }
        }
        return removed to failures
    }

    private suspend fun auxiliaryFiles(server: String, video: String, generation: Long) =
        dao.getLocalFiles(server, video).filter {
            it.generation == generation && it.kind in listOf(LocalFileKind.Subtitle, LocalFileKind.Thumbnail) &&
                it.assetType == AssetType.File
        }

    /** Only remove paths exactly produced by the extras writer, never arbitrary DB paths. */
    private suspend fun deleteAuxiliary(files: List<LocalFileEntity>) = withContext(Dispatchers.IO) {
        val root = File(app.filesDir, "offline-extras")
        files.forEach { file ->
            val expected = extrasPath(root, file.serverIdentity, file.youtubeId,
                file.generation, file.kind, file.trackKey)
            if (File(file.path).absolutePath == expected.absolutePath &&
                expected.canonicalPath == expected.absolutePath && expected.isFile) expected.delete()
        }
    }

    /** Recover interrupted remove/invalidation after a crash. Never sweep an active generation:
     * OfflineExtras may be writing an unindexed target or .part file for it right now.
     */
    private suspend fun sweepOrphanExtras() {
        val references = dao.observeLocalFiles().first().filter { it.assetType == AssetType.File }
            .map { it.path }.toSet()
        val intents = dao.observeQueue().first().associateBy { it.serverIdentity to it.youtubeId }
        withContext(Dispatchers.IO) {
            val root = File(app.filesDir, "offline-extras")
            if (!root.isDirectory || root.canonicalPath != root.absolutePath) return@withContext
            // Hashes in the directory layout are opaque. Derive known owners from the queue,
            // and skip current generations even if their Room row has not yet been published.
            val active = intents.values.map { intent ->
                extrasPath(root, intent.serverIdentity, intent.youtubeId, intent.generation,
                    LocalFileKind.Thumbnail, "").parentFile!!.absolutePath
            }.toSet()
            root.listFiles().orEmpty().filter { it.isDirectory && it.canonicalPath == it.absolutePath }
                .forEach { serverDir ->
                    serverDir.listFiles().orEmpty().filter { it.isDirectory && it.canonicalPath == it.absolutePath }
                        .forEach { videoDir ->
                            videoDir.listFiles().orEmpty().filter { it.isDirectory && it.canonicalPath == it.absolutePath &&
                                it.name.toLongOrNull() != null && it.absolutePath !in active }.forEach { genDir ->
                                genDir.listFiles().orEmpty().filter { it.isFile && it.canonicalPath == it.absolutePath &&
                                    it.name.matches(Regex("(Subtitle-[0-9a-f]{64}\\.vtt|Thumbnail-[0-9a-f]{64}\\.image)")) &&
                                    it.absolutePath !in references }.forEach { it.delete() }
                            }
                        }
                }
        }
    }

    fun refreshPolicy() { scope.launch { reconcileStops() } }

    companion object {
        private fun bindingToken(binding: DeviceTransferBindingEntity): ByteArray =
            "${binding.generation}\u0000${binding.etag}".toByteArray(Charsets.UTF_8)
        private const val STOP_BLOCKED = 1
        fun transferId(serverIdentity: String, youtubeId: String): String = "source-" +
            MessageDigest.getInstance("SHA-256")
                .digest("$serverIdentity\u0000$youtubeId".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
