package com.sniptube.android.data.local

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class OfflineDaoTest {
    private lateinit var context: Context
    private lateinit var database: SniptubeDatabase
    private lateinit var dao: OfflineDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = inMemoryDatabase()
        dao = database.offlineDao()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(PERSISTENCE_DATABASE)
    }

    @Test
    fun `viewed persists after replay and a manual reset starts from the beginning`() = runTest {
        val item = video(SERVER_A, "viewed-video")
        dao.enqueue(item, 1)
        assertFalse(watchedEnough(89_999, 100_000))
        assertFalse(watchedEnough(90_000, 100_000))
        assertTrue(watchedEnough(90_001, 100_000))
        dao.upsertPlaybackProgress(PlaybackProgressEntity(SERVER_A, item.youtubeId, 89_999, 100_000, 2))
        assertFalse(dao.getPlaybackProgress(SERVER_A, item.youtubeId)!!.viewed)
        dao.upsertPlaybackProgress(PlaybackProgressEntity(SERVER_A, item.youtubeId, 90_001, 100_000, 3))
        assertTrue(dao.getPlaybackProgress(SERVER_A, item.youtubeId)!!.viewed)
        dao.upsertPlaybackProgress(PlaybackProgressEntity(SERVER_A, item.youtubeId, 0, 100_000, 4))
        assertTrue(dao.getPlaybackProgress(SERVER_A, item.youtubeId)!!.viewed)
        assertTrue(dao.setViewed(SERVER_A, item.youtubeId, false, 5))
        val reset = dao.getPlaybackProgress(SERVER_A, item.youtubeId)!!
        assertFalse(reset.viewed)
        assertEquals(0, reset.positionMs)
        assertTrue(dao.setViewed(SERVER_A, item.youtubeId, true, 6))
        assertTrue(dao.getPlaybackProgress(SERVER_A, item.youtubeId)!!.viewed)
    }

    @Test
    fun `explicit storage reset can remove a collection copy until Sync now restores it`() = runTest {
        val item = video(SERVER_A, "storage-reset")
        dao.enqueue(item, 1)
        dao.insertCollection(CollectionEntity("trip", "Trip", createdAt = 1, updatedAt = 1))
        dao.addCollectionMembership(CollectionMembershipEntity("trip", SERVER_A, item.youtubeId, 1))
        assertEquals(1L, dao.recordLocalRemoval(SERVER_A, item.youtubeId, 2, clearAll = true))
        dao.enforceCollectionsOffline()
        assertTrue(dao.getKeepOfflineCandidates().isEmpty())
        assertEquals(DeviceStage.Removed, dao.getIntent(SERVER_A, item.youtubeId)?.deviceStage)
        assertEquals(EnqueueResult.Restored, dao.enqueue(item, 3))
        assertEquals(DeviceStage.Pending, dao.getIntent(SERVER_A, item.youtubeId)?.deviceStage)
    }

    @Test
    fun `enqueue is atomic deduplicated and scoped to the configured server`() = runTest {
        val first = video(SERVER_A, "youtube-1", title = "Original")

        assertEquals(EnqueueResult.Inserted, dao.enqueue(first, requestedAt = 10))
        assertEquals(
            EnqueueResult.Duplicate,
            dao.enqueue(first.copy(title = "Updated", metadataUpdatedAt = 20), requestedAt = 20),
        )
        assertEquals(
            EnqueueResult.Inserted,
            dao.enqueue(video(SERVER_B, "youtube-1", title = "Other server"), requestedAt = 30),
        )

        assertEquals(2, dao.queueCount())
        assertEquals(
            listOf("Other server", "Updated"),
            dao.observeVideos().first().map(OfflineVideoEntity::title),
        )
    }

    @Test
    fun `restart retains queue pause job playback and collection intent`() = runTest {
        database.close()
        context.deleteDatabase(PERSISTENCE_DATABASE)
        database = persistentDatabase()
        dao = database.offlineDao()

        val video = video(SERVER_A, "youtube-2")
        val failedVideo = video(SERVER_A, "youtube-failed")
        dao.enqueue(video, requestedAt = 100)
        dao.enqueue(failedVideo, requestedAt = 101)
        dao.bindServerJob(
            ServerJobBindingEntity(
                serverIdentity = SERVER_A,
                youtubeId = video.youtubeId,
                jobId = "job-7",
                status = "running",
                progress = 42,
                boundAt = 110,
                updatedAt = 120,
            ),
        )
        dao.upsertPlaybackProgress(
            PlaybackProgressEntity(SERVER_A, video.youtubeId, 12_000, 90_000, 130),
        )
        dao.bindDeviceTransfer(
            DeviceTransferBindingEntity(
                serverIdentity = SERVER_A,
                youtubeId = video.youtubeId,
                transferId = "transfer-3",
                temporaryPath = "/files/video.part",
                destinationPath = "/files/video.webm",
                bytesDownloaded = 4096,
                totalBytes = 8192,
                etag = "etag-1",
                generation = 0,
                updatedAt = 130,
            ),
        )
        dao.insertCollection(CollectionEntity("trip", "Trip", keepOffline = true, 140, 140))
        dao.addCollectionMembership(
            CollectionMembershipEntity("trip", SERVER_A, video.youtubeId, 150),
        )
        assertTrue(dao.pauseDeviceTransfer(SERVER_A, video.youtubeId, updatedAt = 160))
        assertTrue(
            dao.markFailed(
                serverIdentity = SERVER_A,
                youtubeId = failedVideo.youtubeId,
                retryStage = RetryStage.Server,
                errorCode = "offline",
                errorMessage = "Server unavailable",
                nextAttemptAt = 1_000,
                updatedAt = 160,
            ),
        )

        database.close()
        database = persistentDatabase()
        dao = database.offlineDao()

        val restored = dao.getIntent(SERVER_A, video.youtubeId)
        assertEquals(DeviceStage.Paused, restored?.deviceStage)
        assertTrue(restored?.userPaused == true)
        assertEquals(DeviceStage.Pending, restored?.resumeDeviceStage)
        assertEquals("job-7", dao.getServerJobBinding(SERVER_A, video.youtubeId)?.jobId)
        assertEquals("transfer-3", dao.getDeviceTransferBinding(SERVER_A, video.youtubeId)?.transferId)
        assertEquals(12_000L, dao.getPlaybackProgress(SERVER_A, video.youtubeId)?.positionMs)
        assertEquals(listOf(video.youtubeId), dao.getKeepOfflineCandidates().map { it.youtubeId })
        val failed = dao.getIntent(SERVER_A, failedVideo.youtubeId)
        assertEquals(RetryStage.Server, failed?.retryStage)
        assertEquals(1, failed?.retryCount)
        assertEquals(1_000L, failed?.nextAttemptAt)
        assertEquals(listOf(failedVideo.youtubeId), dao.getRecoveryCandidates().map { it.youtubeId })

        assertTrue(dao.resumeDeviceTransfer(SERVER_A, video.youtubeId, updatedAt = 170))
        assertEquals(
            setOf(video.youtubeId, failedVideo.youtubeId),
            dao.getRecoveryCandidates().map { it.youtubeId }.toSet(),
        )
    }

    @Test
    fun `removal increments ownership and stale completion cannot restore readiness`() = runTest {
        val video = video(SERVER_A, "youtube-3")
        dao.enqueue(video, requestedAt = 200)
        dao.insertCollection(CollectionEntity("kept", "Kept", keepOffline = true, 200, 200))
        dao.addCollectionMembership(
            CollectionMembershipEntity("kept", SERVER_A, video.youtubeId, 200),
        )
        val generationZeroFile = mediaFile(video, generation = 0, path = "/files/complete.webm")

        assertTrue(dao.publishCompletedMedia(generationZeroFile, updatedAt = 210))
        assertEquals(DeviceStage.Ready, dao.getIntent(SERVER_A, video.youtubeId)?.deviceStage)
        assertEquals(1, dao.getLocalFiles(SERVER_A, video.youtubeId).size)

        try { dao.recordLocalRemoval(SERVER_A, video.youtubeId, excludedAt = 220); fail("Collection copy must stay") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("collection")) }
        dao.removeMembership("kept", SERVER_A, video.youtubeId)
        assertEquals(1L, dao.recordLocalRemoval(SERVER_A, video.youtubeId, excludedAt = 220))
        assertEquals(DeviceStage.Removed, dao.getIntent(SERVER_A, video.youtubeId)?.deviceStage)
        assertTrue(dao.getLocalFiles(SERVER_A, video.youtubeId).isEmpty())
        assertTrue(dao.getKeepOfflineCandidates().isEmpty())

        assertFalse(dao.publishCompletedMedia(generationZeroFile, updatedAt = 230))
        assertEquals(DeviceStage.Removed, dao.getIntent(SERVER_A, video.youtubeId)?.deviceStage)
        assertTrue(dao.getLocalFiles(SERVER_A, video.youtubeId).isEmpty())

        assertEquals(EnqueueResult.Restored, dao.enqueue(video, requestedAt = 240))
        dao.addCollectionMembership(CollectionMembershipEntity("kept", SERVER_A, video.youtubeId, 240))
        assertEquals(DeviceStage.Pending, dao.getIntent(SERVER_A, video.youtubeId)?.deviceStage)
        assertEquals(1L, dao.getIntent(SERVER_A, video.youtubeId)?.generation)
        assertEquals(listOf(video.youtubeId), dao.getKeepOfflineCandidates().map { it.youtubeId })
        assertFalse(dao.failDeviceTransfer(SERVER_A, video.youtubeId, 0,
            "late_error", "Old transfer failed", 250))
        assertEquals(DeviceStage.Pending, dao.getIntent(SERVER_A, video.youtubeId)?.deviceStage)
    }

    @Test
    fun `overlapping kept collections produce one candidate and exclusions are per video`() = runTest {
        val first = video(SERVER_A, "youtube-4", title = "First")
        val second = video(SERVER_A, "youtube-5", title = "Second")
        dao.enqueue(first, requestedAt = 300)
        dao.enqueue(second, requestedAt = 301)
        dao.insertCollection(CollectionEntity("one", "One", keepOffline = true, 300, 300))
        dao.insertCollection(CollectionEntity("two", "Two", keepOffline = true, 300, 300))
        dao.addCollectionMembership(CollectionMembershipEntity("one", SERVER_A, first.youtubeId, 300))
        dao.addCollectionMembership(CollectionMembershipEntity("two", SERVER_A, first.youtubeId, 300))
        dao.addCollectionMembership(CollectionMembershipEntity("two", SERVER_A, second.youtubeId, 300))

        assertEquals(
            listOf(first.youtubeId, second.youtubeId),
            dao.getKeepOfflineCandidates().map { it.youtubeId },
        )

        try { dao.recordLocalRemoval(SERVER_A, first.youtubeId, excludedAt = 310); fail("Overlapping collections protect the copy") }
        catch (_: IllegalArgumentException) { }
        dao.removeMembership("one", SERVER_A, first.youtubeId)
        dao.removeMembership("two", SERVER_A, first.youtubeId)
        dao.recordLocalRemoval(SERVER_A, first.youtubeId, excludedAt = 310)

        assertEquals(listOf(second.youtubeId), dao.getKeepOfflineCandidates().map { it.youtubeId })
        assertNotNull(dao.getIntent(SERVER_A, first.youtubeId))
        assertNull(dao.getPlaybackProgress(SERVER_A, first.youtubeId))
    }

    @Test
    fun `cache asset and transfer binding recover without inventing a media file`() = runTest {
        database.close()
        database = persistentDatabase()
        dao = database.offlineDao()
        val video = video(SERVER_A, "youtube-cache")
        dao.enqueue(video, 1)
        dao.updateServerAcquisition(SERVER_A, video.youtubeId, 0, ServerStage.Ready,
            video, null, true, 2)
        val binding = DeviceTransferBindingEntity(SERVER_A, video.youtubeId, "source-cache",
            "", "media3:source-cache", 0, 1024, "\"v1\"", null, 0, 3, "source-cache")
        assertTrue(dao.bindAndQueueDevice(binding, 3))
        assertTrue(dao.pauseDeviceTransfer(SERVER_A, video.youtubeId, 4))
        assertFalse(dao.updateDeviceStage(SERVER_A, video.youtubeId, 0,
            DeviceStage.Running, updatedAt = 5))
        assertEquals(DeviceStage.Paused, dao.getIntent(SERVER_A, video.youtubeId)?.deviceStage)

        database.close()
        database = persistentDatabase()
        dao = database.offlineDao()
        assertEquals("source-cache", dao.getDeviceTransferBinding(SERVER_A, video.youtubeId)?.cacheKey)
        assertEquals(DeviceStage.Paused, dao.getIntent(SERVER_A, video.youtubeId)?.deviceStage)
        assertTrue(dao.resumeDeviceTransfer(SERVER_A, video.youtubeId, 8))
        val cacheAsset = LocalFileEntity(SERVER_A, video.youtubeId, LocalFileKind.Media,
            path = "media3:source-cache", byteSize = 1024, etag = "\"v1\"",
            generation = 0, validatedAt = 9, assetType = AssetType.Media3Cache,
            cacheKey = "source-cache")
        assertTrue(dao.publishCompletedMedia(cacheAsset, 9))
        database.close()
        database = persistentDatabase()
        dao = database.offlineDao()
        assertEquals(cacheAsset, dao.getLocalFiles(SERVER_A, video.youtubeId).single())
        assertTrue(dao.invalidateMissingMedia(cacheAsset, 10))
        assertEquals(DeviceStage.Failed, dao.getIntent(SERVER_A, video.youtubeId)?.deviceStage)
        assertEquals(RetryStage.LocalValidation, dao.getIntent(SERVER_A, video.youtubeId)?.retryStage)
    }

    private fun inMemoryDatabase(): SniptubeDatabase =
        Room.inMemoryDatabaseBuilder(context, SniptubeDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun persistentDatabase(): SniptubeDatabase =
        Room.databaseBuilder(context, SniptubeDatabase::class.java, PERSISTENCE_DATABASE)
            .allowMainThreadQueries()
            .addMigrations(*SniptubeDatabase.MIGRATIONS)
            .build()

    private fun video(
        serverIdentity: String,
        youtubeId: String,
        title: String = "Video $youtubeId",
    ) = OfflineVideoEntity(
        serverIdentity = serverIdentity,
        youtubeId = youtubeId,
        sourceUrl = "https://youtu.be/$youtubeId",
        title = title,
        metadataUpdatedAt = 1,
    )

    private fun mediaFile(
        video: OfflineVideoEntity,
        generation: Long,
        path: String,
    ) = LocalFileEntity(
        serverIdentity = video.serverIdentity,
        youtubeId = video.youtubeId,
        kind = LocalFileKind.Media,
        path = path,
        byteSize = 1024,
        generation = generation,
        validatedAt = 1,
    )

    private companion object {
        const val SERVER_A = "https://one.example/"
        const val SERVER_B = "https://two.example/"
        const val PERSISTENCE_DATABASE = "offline-dao-test.db"
    }
}
