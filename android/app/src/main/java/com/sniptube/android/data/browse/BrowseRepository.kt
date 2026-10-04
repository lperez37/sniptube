package com.sniptube.android.data.browse

import com.sniptube.android.data.api.SearchResponse
import com.sniptube.android.data.api.SniptubeApiClient
import com.sniptube.android.data.api.Video
import com.sniptube.android.data.local.EnqueueResult
import com.sniptube.android.data.local.DeviceStage
import com.sniptube.android.data.local.OfflineDao
import com.sniptube.android.data.local.OfflineVideoEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class BrowseSource {
    Search,
    Library,
}

data class BrowseVideo(
    val serverIdentity: String,
    val youtubeId: String,
    val sourceUrl: String,
    val title: String,
    val durationMs: Long?,
    val thumbnailUrl: String?,
    val uploader: String?,
    val serverVideoId: String?,
    val serverStatus: String?,
    val serverFileSizeBytes: Long?,
    val source: BrowseSource,
    val uploadDate: String? = null,
)

data class BrowsePage(
    val videos: List<BrowseVideo>,
    val page: Int,
    val hasMore: Boolean,
)

interface BrowseOperations {
    suspend fun search(query: String, page: Int): BrowsePage

    suspend fun library(): List<BrowseVideo>

    suspend fun enqueue(video: BrowseVideo): EnqueueResult

    fun observeQueuedYoutubeIds(): Flow<Set<String>>
}

internal interface BrowseRemoteDataSource {
    suspend fun search(query: String, page: Int): SearchResponse

    suspend fun library(): List<Video>
}

internal class ApiBrowseRemoteDataSource(
    private val client: SniptubeApiClient,
) : BrowseRemoteDataSource {
    override suspend fun search(query: String, page: Int): SearchResponse =
        client.search(query = query, page = page)

    override suspend fun library(): List<Video> = client.listVideos()
}

class BrowseRepository internal constructor(
    private val serverIdentity: String,
    private val remote: BrowseRemoteDataSource,
    private val offlineDao: OfflineDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val onQueued: (String) -> Unit = {},
) : BrowseOperations {
    constructor(
        serverIdentity: String,
        client: SniptubeApiClient,
        offlineDao: OfflineDao,
        onQueued: (String) -> Unit = {},
    ) : this(serverIdentity, ApiBrowseRemoteDataSource(client), offlineDao, onQueued = onQueued)

    override suspend fun search(query: String, page: Int): BrowsePage = coroutineScope {
        val searchRequest = async { remote.search(query, page) }
        val libraryRequest = async { remote.library() }
        val response = searchRequest.await()
        val libraryByYoutubeId = libraryRequest.await().associateBy(Video::youtubeId)
        BrowsePage(
            videos = response.results.map { result ->
                val serverVideo = libraryByYoutubeId[result.youtubeId]
                BrowseVideo(
                    serverIdentity = serverIdentity,
                    youtubeId = result.youtubeId,
                    sourceUrl = result.url,
                    title = result.title,
                    durationMs = result.duration?.secondsToMilliseconds(),
                    thumbnailUrl = result.thumbnailUrl,
                    uploader = result.uploader,
                    serverVideoId = serverVideo?.id ?: result.videoId,
                    serverStatus = serverVideo?.status,
                    serverFileSizeBytes = serverVideo?.fileSize,
                    source = BrowseSource.Search,
                    uploadDate = result.uploadDate ?: serverVideo?.uploadDate,
                )
            },
            page = response.page,
            hasMore = response.hasMore,
        )
    }

    override suspend fun library(): List<BrowseVideo> = remote.library().map { video ->
        BrowseVideo(
            serverIdentity = serverIdentity,
            youtubeId = video.youtubeId,
            sourceUrl = video.url,
            title = video.title ?: "Untitled video",
            durationMs = video.duration?.secondsToMilliseconds(),
            thumbnailUrl = video.thumbnailUrl,
            uploader = video.uploader,
            serverVideoId = video.id,
            serverStatus = video.status,
            serverFileSizeBytes = video.fileSize,
            source = BrowseSource.Library,
            uploadDate = video.uploadDate,
        )
    }.also { videos ->
        // Refresh known phone metadata too, without creating queue intent or touching local files.
        videos.forEach { video ->
            offlineDao.getVideo(serverIdentity, video.youtubeId)?.let { old ->
                if (video.uploadDate != null || video.uploader != null) offlineDao.upsertVideo(old.copy(
                    uploadDate = video.uploadDate ?: old.uploadDate,
                    uploader = video.uploader ?: old.uploader,
                    metadataUpdatedAt = now(),
                ))
            }
        }
    }

    override suspend fun enqueue(video: BrowseVideo): EnqueueResult {
        require(video.serverIdentity == serverIdentity) {
            "Cannot queue a result from a different Sniptube server."
        }
        val timestamp = now()
        val result = offlineDao.enqueue(
            OfflineVideoEntity(
                serverIdentity = serverIdentity,
                youtubeId = video.youtubeId,
                sourceUrl = video.sourceUrl,
                serverVideoId = video.serverVideoId,
                title = video.title,
                durationMs = video.durationMs,
                thumbnailUrl = video.thumbnailUrl,
                uploader = video.uploader,
                serverStatus = video.serverStatus,
                serverFileSizeBytes = video.serverFileSizeBytes,
                metadataUpdatedAt = timestamp,
                uploadDate = video.uploadDate,
            ),
            requestedAt = timestamp,
        )
        if (result != EnqueueResult.Duplicate) onQueued(serverIdentity)
        return result
    }

    override fun observeQueuedYoutubeIds(): Flow<Set<String>> = offlineDao.observeQueue()
        .map { intents ->
            intents.asSequence()
                .filter { it.serverIdentity == serverIdentity }
                .filter { it.deviceStage != DeviceStage.Removed }
                .map { it.youtubeId }
                .toSet()
        }
}

private fun Double.secondsToMilliseconds(): Long = (this * 1_000).toLong()
