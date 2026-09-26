package com.sniptube.android.data.library

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sniptube.android.data.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class LibraryActionsTest {
    private lateinit var db: SniptubeDatabase
    private lateinit var dao: OfflineDao
    private lateinit var actions: LibraryActions
    private val wakes = mutableListOf<String>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, SniptubeDatabase::class.java).allowMainThreadQueries().build()
        bind()
    }

    private fun bind() {
        dao = db.offlineDao()
        actions = LibraryActions(dao, { wakes += it }, {}, { server, id ->
            dao.recordLocalRemoval(server, id, 30)
            assertEquals(DeviceStage.Removed, dao.getIntent(server, id)?.deviceStage)
        }, { 10 })
    }

    @After fun cleanup() { db.close(); context.deleteDatabase("library-restart") }

    @Test fun `dedup partial failures and server scoping`() = runTest {
        val good = video("one")
        val bad = video("bad").copy(serverVideoId = "collision")
        dao.upsertVideo(video("existing").copy(serverVideoId = "collision"))
        val result = actions.enqueue(listOf(bad, good, good, good.copy(serverIdentity = "https://other/")))
        assertEquals(2, result.succeeded)
        assertEquals(1, result.failures.size)
        assertEquals(2, dao.queueCount())
        assertEquals(1, actions.enqueue(listOf(good)).skipped)
        assertEquals(setOf("https://server/", "https://other/"), wakes.toSet())
    }

    @Test fun `collections queue additions immediately and removal requires leaving every collection`() = runTest {
        val one = actions.createCollection(" One ")
        val two = actions.createCollection("Two")
        val v = video("one")
        actions.addToCollection(one, listOf(v, v))
        actions.addToCollection(two, listOf(v))
        assertEquals(1, dao.queueCount())
        assertEquals(2, actions.observe().first().memberships.size)
        assertEquals(true, dao.getCollection(one)?.keepOffline)
        assertEquals(1, actions.removeLocal(listOf(key(v))).failures.size)
        assertEquals(0, actions.reconcileKeepOffline().succeeded)
        actions.removeFromCollection(one, listOf(key(v)))
        assertEquals(1, actions.removeLocal(listOf(key(v))).failures.size)
        actions.removeFromCollection(two, listOf(key(v)))
        assertEquals(1, actions.removeLocal(listOf(key(v))).succeeded)
        assertEquals(0, actions.reconcileKeepOffline().succeeded)
        assertEquals(1, actions.addToCollection(one, listOf(v)).succeeded)
        assertEquals(1L, dao.getIntent(v.serverIdentity, v.youtubeId)?.generation)
        dao.publishCompletedMedia(media(v, 1), 40)
        actions.removeFromCollection(one, listOf(key(v)))
        actions.deleteCollection(two)
        assertEquals(1, dao.getLocalFiles(v.serverIdentity, v.youtubeId).size)
        assertEquals(DeviceStage.Ready, dao.getIntent(v.serverIdentity, v.youtubeId)?.deviceStage)
    }

    @Test fun `pause retry preserves ready server and ownership guards auxiliaries`() = runTest {
        val v = video("one")
        actions.enqueue(listOf(v))
        dao.updateServerAcquisition(v.serverIdentity, v.youtubeId, 0, ServerStage.Ready, v, null, true, 11)
        dao.failDeviceTransfer(v.serverIdentity, v.youtubeId, 0, "broken", "Broken", 12)
        actions.pause(listOf(key(v)))
        actions.retry(listOf(key(v)))
        val paused = dao.getIntent(v.serverIdentity, v.youtubeId)!!
        assertTrue(paused.userPaused)
        assertEquals(ServerStage.Ready, paused.serverStage)
        assertNull(paused.retryStage)
        actions.resume(listOf(key(v)))
        assertEquals(DeviceStage.Queued, dao.getIntent(v.serverIdentity, v.youtubeId)?.deviceStage)
        val aux = media(v, 0).copy(kind = LocalFileKind.Thumbnail, path = "/thumb")
        assertFalse(dao.publishAuxiliaryFile(aux))
        assertTrue(dao.publishCompletedMedia(media(v, 0), 20))
        assertTrue(dao.publishAuxiliaryFile(aux))
        assertEquals(1, actions.enqueue(listOf(v)).skipped)
        actions.removeLocal(listOf(key(v)))
        assertFalse(dao.publishAuxiliaryFile(aux))
        assertFalse(dao.publishCompletedMedia(media(v, 0), 21))
    }

    @Test fun `sparse stale metadata does not erase acquired details or playback`() = runTest {
        val v = video("one").copy(title = "Acquired", serverVideoId = "source", durationMs = 123,
            serverStatus = "ready", metadataUpdatedAt = 100)
        actions.enqueue(listOf(v))
        dao.upsertPlaybackProgress(PlaybackProgressEntity(v.serverIdentity, v.youtubeId, 50, 123, 100))
        val id = actions.createCollection("Saved")
        actions.addToCollection(id, listOf(video("one")))
        assertEquals(v, dao.getVideo(v.serverIdentity, v.youtubeId))
        assertEquals(50L, actions.observe().first().playback.single().positionMs)
    }

    @Test fun `names reject blank and case insensitive duplicates`() = runTest {
        val id = actions.createCollection(" Trip ")
        assertEquals("Trip", dao.getCollection(id)?.name)
        for (name in listOf("trip", "   ", "bad\nname")) {
            try { actions.createCollection(name); fail("Should reject name") } catch (_: IllegalArgumentException) { }
        }
        val other = actions.createCollection("Other")
        try { actions.renameCollection(other, "TRIP"); fail("Should reject collision") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `restart reconciles persisted kept metadata without reviving excluded copies`() = runTest {
        db.close()
        db = Room.databaseBuilder(context, SniptubeDatabase::class.java, "library-restart").allowMainThreadQueries().build()
        bind()
        val id = actions.createCollection("Travel")
        val kept = video("kept")
        val removed = video("removed")
        actions.addToCollection(id, listOf(kept))
        actions.enqueue(listOf(removed))
        actions.removeLocal(listOf(key(removed)))
        val collection = dao.getCollection(id)!!
        // Persist a legacy optional collection and a legacy removed member from v0.1.
        dao.updateCollection(collection.copy(keepOffline = false))
        dao.addCollectionMembership(CollectionMembershipEntity(id, removed.serverIdentity, removed.youtubeId, 4))
        db.close()
        db = Room.databaseBuilder(context, SniptubeDatabase::class.java, "library-restart").allowMainThreadQueries().build()
        bind()
        assertEquals(1, actions.reconcileKeepOffline().succeeded)
        assertEquals(true, dao.getCollection(id)?.keepOffline)
        assertEquals(DeviceStage.Pending, dao.getIntent(removed.serverIdentity, removed.youtubeId)?.deviceStage)
        assertEquals(0, actions.reconcileKeepOffline().succeeded)
    }

    @Test fun `cancellation escapes batch and stops unrelated work`() = runTest {
        val cancelled = LibraryActions(dao, { throw CancellationException("stop") }, {}, { _, _ -> })
        try { cancelled.enqueue(listOf(video("one"), video("two"))); fail("Should cancel") }
        catch (_: CancellationException) { }
        assertEquals(1, dao.queueCount())
    }

    @Test fun `server retry resets backoff while device retry does not reset ready source`() = runTest {
        val v = video("server-failed")
        actions.enqueue(listOf(v))
        dao.failServerAcquisition(v.serverIdentity, v.youtubeId, 0, "offline", "Offline", 1000, 11)
        assertEquals(1, actions.retry(listOf(key(v), key(v))).succeeded)
        val retried = dao.getIntent(v.serverIdentity, v.youtubeId)!!
        assertEquals(ServerStage.Pending, retried.serverStage)
        assertEquals(0, retried.retryCount)
        assertNull(retried.nextAttemptAt)
        assertNull(retried.errorCode)
        assertEquals(1, actions.retry(listOf(key(v))).skipped)
    }

    @Test fun `manual cellular sync authorizes only collection members and retries failed or paused work`() = runTest {
        val approved = mutableSetOf<VideoKey>()
        val manual = LibraryActions(dao, { wakes += it }, {}, { _, _ -> }, { 10 },
            { approved += it }, { "Wi-Fi required" }, { null })
        val id = manual.createCollection("Travel")
        val failed = video("failed")
        val paused = video("paused")
        val unrelated = video("other")
        manual.addToCollection(id, listOf(failed, paused))
        actions.enqueue(listOf(unrelated))
        dao.failServerAcquisition(failed.serverIdentity, failed.youtubeId, 0, "offline", "Server offline", null, 11)
        dao.pauseDeviceTransfer(paused.serverIdentity, paused.youtubeId, 11)
        wakes.clear()
        try { manual.syncCollectionNow(id, false); fail("Automatic Wi-Fi policy must still apply") }
        catch (_: IllegalArgumentException) { }
        assertTrue(approved.isEmpty())
        assertEquals(2, manual.syncCollectionNow(id, true).succeeded)
        assertEquals(setOf(key(failed), key(paused)), approved)
        assertEquals(listOf(failed.serverIdentity), wakes.distinct())
        assertEquals(ServerStage.Pending, dao.getIntent(failed.serverIdentity, failed.youtubeId)?.serverStage)
        assertFalse(dao.getIntent(paused.serverIdentity, paused.youtubeId)!!.userPaused)
        assertNotNull(dao.getIntent(unrelated.serverIdentity, unrelated.youtubeId))
    }

    @Test fun `failed media removal does not block later item or permit stale completion`() = runTest {
        val first = video("first")
        val second = video("second")
        actions.enqueue(listOf(first, second))
        val removals = LibraryActions(dao, {}, {}, { server, id ->
            dao.recordLocalRemoval(server, id, 20)
            assertFalse(dao.publishCompletedMedia(media(if (id == first.youtubeId) first else second, 0), 21))
            if (id == first.youtubeId) error("Native removal unavailable")
        })
        val result = removals.removeLocal(listOf(key(first), key(second)))
        assertEquals(1, result.succeeded)
        assertEquals(1, result.failures.size)
        assertEquals(DeviceStage.Removed, dao.getIntent(second.serverIdentity, second.youtubeId)?.deviceStage)
    }

    private fun video(id: String) = OfflineVideoEntity("https://server/", id, "https://youtu.be/$id",
        title = id, metadataUpdatedAt = 1)
    private fun key(v: OfflineVideoEntity) = VideoKey(v.serverIdentity, v.youtubeId)
    private fun media(v: OfflineVideoEntity, generation: Long) = LocalFileEntity(v.serverIdentity, v.youtubeId,
        LocalFileKind.Media, path = "/media/${v.youtubeId}", byteSize = 100, generation = generation, validatedAt = 20)
}
