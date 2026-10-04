package com.sniptube.android.data.acquisition

import com.sniptube.android.data.api.Job
import com.sniptube.android.data.api.ServerConnection
import com.sniptube.android.data.api.ServerConnectionException
import com.sniptube.android.data.api.ServerHttpException
import com.sniptube.android.data.api.ServerTimeoutException
import com.sniptube.android.data.api.SniptubeApiClient
import com.sniptube.android.data.api.SniptubeApiException
import com.sniptube.android.data.api.Video
import com.sniptube.android.data.local.DeviceStage
import com.sniptube.android.data.local.OfflineDao
import com.sniptube.android.data.local.OfflineVideoEntity
import com.sniptube.android.data.local.QueueIntentEntity
import com.sniptube.android.data.local.ServerJobBindingEntity
import com.sniptube.android.data.local.ServerStage
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A short, bounded pass. The server is the authority after any uncertain POST response. */
class ServerAcquisition(
    private val dao: OfflineDao,
    private val clientFor: (ServerConnection) -> SniptubeApiClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun reconcile(serverIdentity: String, foreground: Boolean): Boolean = mutex.withLock {
        val connection = ServerConnection.parse(serverIdentity)
        val candidates = dao.getAcquisitionCandidates().filter { it.serverIdentity == serverIdentity }
        val pending = candidates.filter { it.serverStage != ServerStage.Ready }
            .filter { it.serverStage != ServerStage.Failed || it.nextAttemptAt != null }
            .filter { it.nextAttemptAt == null || it.nextAttemptAt <= now() }
            .take(2)
        val ready = candidates.filter {
            foreground && it.serverStage == ServerStage.Ready &&
                it.deviceStage == DeviceStage.WaitingForNetwork
        }
        if (pending.isNotEmpty()) {
            val client = clientFor(connection)
            // A failed shared listing must not suppress other items' per-item retry state.
            val library = try { client.listVideos() } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pending.forEach { fail(it, error) }
                return@withLock hasPending(serverIdentity)
            }
            val active = try { client.listActiveJobs("download") } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pending.forEach { fail(it, error) }
                return@withLock hasPending(serverIdentity)
            }
            pending.forEach { intent ->
                try {
                    reconcileOne(intent, client, library, active, foreground)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    fail(intent, error)
                }
            }
        }
        ready.forEach { intent ->
            dao.updateServerAcquisition(
                intent.serverIdentity, intent.youtubeId, intent.generation,
                ServerStage.Ready, null, null, true, now(),
            )
        }
        hasPending(serverIdentity)
    }

    private suspend fun hasPending(serverIdentity: String) = dao.getAcquisitionCandidates().any {
        it.serverIdentity == serverIdentity && it.serverStage != ServerStage.Ready &&
            (it.serverStage != ServerStage.Failed || it.nextAttemptAt != null)
    }

    private suspend fun reconcileOne(
        intent: QueueIntentEntity,
        client: SniptubeApiClient,
        library: List<Video>,
        active: List<Job>,
        foreground: Boolean,
    ) {
        val video = dao.getVideo(intent.serverIdentity, intent.youtubeId) ?: return
        val expectedId = MessageDigest.getInstance("SHA-256")
            .digest(intent.youtubeId.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it) }
        val remote = library.firstOrNull { it.youtubeId == intent.youtubeId }
        if (remote != null && remote.id != expectedId) {
            throw IllegalStateException("Server video ID does not match this YouTube video.")
        }
        if (remote?.status == "ready") {
            persist(intent, ServerStage.Ready, remote, null, foreground)
            return
        }
        // Always consult fresh remote jobs before the durable binding. A retried job may have
        // replaced the old one, and a lost POST response may have created a job we never bound.
        val activeJob = active.firstOrNull { it.type == "download" && it.videoId == expectedId }
        val binding = dao.getServerJobBinding(intent.serverIdentity, intent.youtubeId)
        val job = activeJob ?: binding?.let {
            try { client.getJob(it.jobId) } catch (error: ServerHttpException) {
                if (error.statusCode == 404) null else throw error
            }
        }
        if (job != null) {
            require(job.videoId == expectedId && job.type == "download") {
                "Server job does not belong to this video."
            }
            when (job.status) {
                "queued", "running" -> persist(
                    intent, if (job.status == "running") ServerStage.Running else ServerStage.Queued,
                    remote, job, foreground,
                )
                "completed" -> {
                    val detail = client.getVideo(expectedId)
                    if (detail.status == "ready" && detail.youtubeId == intent.youtubeId) {
                        persist(intent, ServerStage.Ready, detail, job, foreground)
                    } else {
                        throw IllegalStateException("Server job completed but the video is not ready.")
                    }
                }
                "failed" -> dao.failServerAcquisition(
                    intent.serverIdentity, intent.youtubeId, intent.generation,
                    "server_job_failed", job.error ?: "Server download failed. Retry this video.",
                    null, now(),
                )
                else -> throw IllegalStateException("Unknown server job status: ${job.status}")
            }
            return
        }
        if (remote?.status == "downloading") {
            // The remote POST may have committed while the response was lost. Wait for its job.
            persist(intent, ServerStage.Queued, remote, null, foreground)
            return
        }
        // This POST is only reached after checking both the library and active jobs. The
        // server also deduplicates an existing ready/downloading video by deterministic ID;
        // never retry an uncertain response here without another reconciliation pass.
        val result = client.createVideo(video.sourceUrl)
        require(result.videoId == expectedId) { "Server returned a different video ID." }
        if (result.status == "already_exists") {
            val detail = client.getVideo(expectedId)
            require(detail.status == "ready" && detail.youtubeId == intent.youtubeId) {
                "Server reported an existing video that is not ready."
            }
            persist(intent, ServerStage.Ready, detail, null, foreground)
        } else {
            require(result.jobId.isNotBlank()) { "Server did not return a download job." }
            persistBound(
                intent, ServerStage.Queued, remote,
                ServerJobBindingEntity(intent.serverIdentity, intent.youtubeId, result.jobId,
                    result.status, 0, now(), now()), foreground,
            )
        }
    }

    private suspend fun persist(
        intent: QueueIntentEntity,
        stage: ServerStage,
        video: Video?,
        job: Job?,
        foreground: Boolean,
    ) = persistBound(
        intent, stage, video,
        job?.let {
            ServerJobBindingEntity(intent.serverIdentity, intent.youtubeId, it.id,
                it.status, it.progress, now(), now())
        }, foreground,
    )

    private suspend fun persistBound(
        intent: QueueIntentEntity,
        stage: ServerStage,
        video: Video?,
        job: ServerJobBindingEntity?,
        foreground: Boolean,
    ) {
        val previous = dao.getVideo(intent.serverIdentity, intent.youtubeId) ?: return
        dao.updateServerAcquisition(
            intent.serverIdentity, intent.youtubeId, intent.generation, stage,
            video?.let {
                previous.copy(serverVideoId = it.id, serverStatus = it.status,
                    title = it.title ?: previous.title,
                    durationMs = it.duration?.times(1_000)?.toLong() ?: previous.durationMs,
                    thumbnailUrl = it.thumbnailUrl ?: previous.thumbnailUrl,
                    uploadDate = it.uploadDate ?: previous.uploadDate,
                    uploader = it.uploader ?: previous.uploader,
                    serverFileSizeBytes = it.fileSize, metadataUpdatedAt = now())
            }, job, foreground, now(),
        )
    }

    private suspend fun fail(intent: QueueIntentEntity, error: Exception) {
        val transient = error is ServerConnectionException || error is ServerTimeoutException ||
            (error is ServerHttpException && (error.statusCode == 429 || error.statusCode >= 500))
        val retryAt = if (transient) {
            now() + (30_000L * (1L shl intent.retryCount.coerceIn(0, 6)))
        } else null
        dao.failServerAcquisition(
            intent.serverIdentity, intent.youtubeId, intent.generation,
            if (transient) "server_unreachable" else "server_invalid_response",
            (error as? SniptubeApiException)?.userMessage ?: error.message ?: "Server preparation failed.",
            retryAt, now(),
        )
    }
}
