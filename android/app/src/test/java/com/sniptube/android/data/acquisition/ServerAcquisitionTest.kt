package com.sniptube.android.data.acquisition

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sniptube.android.data.api.ServerConnection
import com.sniptube.android.data.api.SniptubeApiClient
import com.sniptube.android.data.local.DeviceStage
import com.sniptube.android.data.local.DeviceTransferBindingEntity
import com.sniptube.android.data.local.OfflineVideoEntity
import com.sniptube.android.data.local.ServerJobBindingEntity
import com.sniptube.android.data.local.ServerStage
import com.sniptube.android.data.local.SniptubeDatabase
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ServerAcquisitionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private lateinit var db: SniptubeDatabase
    private lateinit var connection: ServerConnection
    private lateinit var acquisition: ServerAcquisition
    private val dao get() = db.offlineDao()
    private var clock = 1_000L

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        connection = ServerConnection.parse(server.url("/").toString())
        db = Room.inMemoryDatabaseBuilder(context, SniptubeDatabase::class.java)
            .allowMainThreadQueries().build()
        acquisition = ServerAcquisition(dao, ::SniptubeApiClient) { clock }
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase("acquisition-restart-test.db")
        server.shutdown()
    }

    @Test fun `existing ready source is handed off without POST and remains waiting if backgrounded`() = runTest {
        enqueue("ready")
        respond("[${video("ready", "ready")}]", "[]")
        assertFalse(acquisition.reconcile(connection.identity, foreground = false))
        assertEquals(listOf("/videos", "/jobs/active?type=download"), requests(2))
        val waiting = dao.getIntent(connection.identity, "ready")!!
        assertEquals(ServerStage.Ready, waiting.serverStage)
        assertEquals(DeviceStage.WaitingForNetwork, waiting.deviceStage)
        assertEquals("open_app_to_sync", waiting.errorCode)

        acquisition.reconcile(connection.identity, foreground = true)
        val foreground = dao.getIntent(connection.identity, "ready")!!
        assertEquals(DeviceStage.Queued, foreground.deviceStage)
        assertNull(foreground.errorCode)
        assertTrue(dao.waitForAllowedStart(connection.identity, "ready", foreground.generation, clock))
        assertEquals(DeviceStage.WaitingForNetwork, dao.getIntent(connection.identity, "ready")?.deviceStage)
        assertEquals(2, server.requestCount)
    }

    @Test fun `lost POST response reattaches existing job and hands off after server completion`() = runTest {
        enqueue("orphan")
        val job = job("orphan", "running", 41)
        respond("[${video("orphan", "downloading")}]", "[$job]")
        assertTrue(acquisition.reconcile(connection.identity, foreground = true))
        assertEquals("job-orphan", dao.getServerJobBinding(connection.identity, "orphan")?.jobId)
        assertEquals(41, dao.getServerJobBinding(connection.identity, "orphan")?.progress)
        assertEquals(ServerStage.Running, dao.getIntent(connection.identity, "orphan")?.serverStage)

        respond("[${video("orphan", "ready")}]", "[]")
        assertFalse(acquisition.reconcile(connection.identity, foreground = true))
        assertEquals(DeviceStage.Queued, dao.getIntent(connection.identity, "orphan")?.deviceStage)
        assertEquals(4, server.requestCount) // No second POST even after an uncertain first response.
    }

    @Test fun `new item posts once then polls bounded job and checks completed video detail`() = runTest {
        enqueue("new")
        respond("[]", "[]", """{"video_id":"${id("new")}","job_id":"job-new","status":"queued"}""")
        assertTrue(acquisition.reconcile(connection.identity, foreground = true))
        assertEquals("/videos", requests(3).last())
        assertEquals(ServerStage.Queued, dao.getIntent(connection.identity, "new")?.serverStage)

        respond("[${video("new", "downloading")} ]", "[]", job("new", "completed", 100), video("new", "ready"))
        assertFalse(acquisition.reconcile(connection.identity, foreground = true))
        assertEquals(DeviceStage.Queued, dao.getIntent(connection.identity, "new")?.deviceStage)
        assertEquals("/videos/${id("new")}", requests(4).last())
    }

    @Test fun `one item failing does not stop a second item and transient error backs off`() = runTest {
        enqueue("bad")
        enqueue("good")
        respond("[]", "[]")
        server.enqueue(MockResponse().setResponseCode(403).setBody("{\"detail\":\"Forbidden\"}"))
        server.enqueue(MockResponse().setBody(
            """{"video_id":"${id("good")}","job_id":"job-good","status":"queued"}""",
        ))
        assertTrue(acquisition.reconcile(connection.identity, foreground = true))
        assertEquals(ServerStage.Failed, dao.getIntent(connection.identity, "bad")?.serverStage)
        assertNull(dao.getIntent(connection.identity, "bad")?.nextAttemptAt)
        assertEquals(ServerStage.Queued, dao.getIntent(connection.identity, "good")?.serverStage)
        assertEquals(4, server.requestCount)

        enqueue("offline")
        server.enqueue(MockResponse().setResponseCode(503))
        acquisition.reconcile(connection.identity, foreground = false)
        assertEquals(clock + 30_000L, dao.getIntent(connection.identity, "offline")?.nextAttemptAt)
    }

    @Test fun `server preparation continues for a paused device but removal stops recovery`() = runTest {
        enqueue("paused")
        assertTrue(dao.pauseDeviceTransfer(connection.identity, "paused", clock))
        respond("[${video("paused", "ready")}]", "[]")
        acquisition.reconcile(connection.identity, foreground = false)
        assertEquals(ServerStage.Ready, dao.getIntent(connection.identity, "paused")?.serverStage)
        assertEquals(DeviceStage.Paused, dao.getIntent(connection.identity, "paused")?.deviceStage)
        dao.recordLocalRemoval(connection.identity, "paused", clock)
        assertFalse(acquisition.reconcile(connection.identity, foreground = true))
        assertEquals(DeviceStage.Removed, dao.getIntent(connection.identity, "paused")?.deviceStage)
        assertEquals(2, server.requestCount)
    }

    @Test fun `recreated coordinator reads durable job binding and does not repeat POST`() = runTest {
        db.close()
        context.deleteDatabase("acquisition-restart-test.db")
        db = persistentDatabase()
        acquisition = ServerAcquisition(dao, ::SniptubeApiClient) { clock }
        enqueue("restart")
        respond("[]", "[]", """{"video_id":"${id("restart")}","job_id":"job-restart","status":"queued"}""")
        assertTrue(acquisition.reconcile(connection.identity, foreground = true))
        requests(3)

        db.close()
        db = persistentDatabase()
        acquisition = ServerAcquisition(dao, ::SniptubeApiClient) { clock }
        respond("[${video("restart", "downloading")}]", "[${job("restart", "running", 65)}]")
        acquisition.reconcile(connection.identity, foreground = false)
        assertEquals("job-restart", dao.getServerJobBinding(connection.identity, "restart")?.jobId)
        assertEquals(65, dao.getServerJobBinding(connection.identity, "restart")?.progress)
        assertEquals(listOf("/videos", "/jobs/active?type=download"), requests(2))
    }

    @Test fun `explicit retry drops terminal job binding and starts a fresh job after remote checks`() = runTest {
        enqueue("retry")
        respond("[]", "[]", """{"video_id":"${id("retry")}","job_id":"job-retry","status":"queued"}""")
        acquisition.reconcile(connection.identity, foreground = true)
        requests(3)
        respond("[${video("retry", "failed")}]", "[]", job("retry", "failed", 20))
        acquisition.reconcile(connection.identity, foreground = true)
        assertEquals(ServerStage.Failed, dao.getIntent(connection.identity, "retry")?.serverStage)
        assertEquals("job-retry", dao.getServerJobBinding(connection.identity, "retry")?.jobId)
        requests(3)

        assertTrue(dao.retryFailed(connection.identity, "retry", clock))
        assertNull(dao.getServerJobBinding(connection.identity, "retry"))
        assertEquals(1L, dao.getIntent(connection.identity, "retry")?.generation)
        respond("[${video("retry", "failed")}]", "[]",
            """{"video_id":"${id("retry")}","job_id":"job-retry-new","status":"queued"}""")
        acquisition.reconcile(connection.identity, foreground = true)
        assertEquals(listOf("/videos", "/jobs/active?type=download", "/videos"), requests(3))
        assertEquals("job-retry-new", dao.getServerJobBinding(connection.identity, "retry")?.jobId)
        assertEquals(ServerStage.Queued, dao.getIntent(connection.identity, "retry")?.serverStage)
    }

    @Test fun `explicit retry reattaches live remote job rather than posting duplicate`() = runTest {
        enqueue("reattach")
        dao.bindServerJob(ServerJobBindingEntity(connection.identity, "reattach", "obsolete",
            "failed", 0, clock, clock))
        dao.failServerAcquisition(connection.identity, "reattach", 0,
            "server_job_failed", "Failed", null, clock)
        assertTrue(dao.retryFailed(connection.identity, "reattach", clock))
        respond("[${video("reattach", "downloading")} ]", "[${job("reattach", "running", 51)}]")
        acquisition.reconcile(connection.identity, foreground = true)
        assertEquals(listOf("/videos", "/jobs/active?type=download"), requests(2))
        assertEquals("job-reattach", dao.getServerJobBinding(connection.identity, "reattach")?.jobId)
    }

    @Test fun `missing source 404 requeues acquisition and fences stale transfer callbacks`() = runTest {
        enqueue("missing")
        dao.updateServerAcquisition(connection.identity, "missing", 0, ServerStage.Ready,
            dao.getVideo(connection.identity, "missing")!!.copy(serverVideoId = id("missing"),
                serverStatus = "ready", serverFileSizeBytes = 128), null, true, clock)
        dao.bindServerJob(ServerJobBindingEntity(connection.identity, "missing", "old-job",
            "completed", 100, clock, clock))
        dao.bindDeviceTransfer(DeviceTransferBindingEntity(connection.identity, "missing", "old-transfer",
            "", "destination", generation = 0, updatedAt = clock))
        assertTrue(dao.requeueMissingServerSource(connection.identity, "missing", 0, clock++))
        val intent = dao.getIntent(connection.identity, "missing")!!
        assertEquals(ServerStage.Pending, intent.serverStage)
        assertEquals(DeviceStage.Pending, intent.deviceStage)
        assertEquals(1L, intent.generation)
        assertNull(dao.getVideo(connection.identity, "missing")?.serverVideoId)
        assertNull(dao.getVideo(connection.identity, "missing")?.serverStatus)
        assertNull(dao.getServerJobBinding(connection.identity, "missing"))
        assertNull(dao.getDeviceTransferBinding(connection.identity, "missing"))
        assertFalse(dao.requeueMissingServerSource(connection.identity, "missing", 0, clock))
        assertFalse(dao.updateServerAcquisition(connection.identity, "missing", 0,
            ServerStage.Ready, null, null, true, clock))
        assertFalse(dao.failDeviceTransfer(connection.identity, "missing", 0,
            "late", "late callback", clock))
        respond("[]", "[]", """{"video_id":"${id("missing")}","job_id":"job-missing","status":"queued"}""")
        acquisition.reconcile(connection.identity, foreground = true)
        assertEquals(listOf("/videos", "/jobs/active?type=download", "/videos"), requests(3))
        assertEquals(ServerStage.Queued, dao.getIntent(connection.identity, "missing")?.serverStage)
    }

    @Test fun `uncertain POST response waits for remote state and does not send another POST`() = runTest {
        enqueue("uncertain")
        respond("[]", "[]")
        server.enqueue(MockResponse().setResponseCode(503))
        acquisition.reconcile(connection.identity, foreground = true)
        assertEquals(ServerStage.Failed, dao.getIntent(connection.identity, "uncertain")?.serverStage)
        assertEquals(clock + 30_000, dao.getIntent(connection.identity, "uncertain")?.nextAttemptAt)
        requests(3)
        clock += 30_001
        respond("[${video("uncertain", "downloading")} ]", "[]")
        acquisition.reconcile(connection.identity, foreground = true)
        assertEquals(listOf("/videos", "/jobs/active?type=download"), requests(2))
        assertEquals(ServerStage.Queued, dao.getIntent(connection.identity, "uncertain")?.serverStage)
    }

    @Test fun `paused missing source stays paused until source is reacquired and user resumes`() = runTest {
        enqueue("paused-missing")
        dao.updateServerAcquisition(connection.identity, "paused-missing", 0,
            ServerStage.Ready, null, null, true, clock)
        dao.pauseDeviceTransfer(connection.identity, "paused-missing", clock)
        assertTrue(dao.requeueMissingServerSource(connection.identity, "paused-missing", 0, clock))
        val intent = dao.getIntent(connection.identity, "paused-missing")!!
        assertEquals(ServerStage.Pending, intent.serverStage)
        assertEquals(DeviceStage.Paused, intent.deviceStage)
        assertTrue(intent.userPaused)
        assertEquals(DeviceStage.Pending, intent.resumeDeviceStage)
        respond("[${video("paused-missing", "ready")}]", "[]")
        acquisition.reconcile(connection.identity, foreground = true)
        requests(2)
        assertEquals(DeviceStage.Paused, dao.getIntent(connection.identity, "paused-missing")?.deviceStage)
        assertTrue(dao.resumeDeviceTransfer(connection.identity, "paused-missing", clock))
        assertEquals(DeviceStage.Queued, dao.getIntent(connection.identity, "paused-missing")?.deviceStage)
    }

    private fun persistentDatabase() = Room.databaseBuilder(
        context, SniptubeDatabase::class.java, "acquisition-restart-test.db",
    ).allowMainThreadQueries().addMigrations(*SniptubeDatabase.MIGRATIONS).build()

    private suspend fun enqueue(youtubeId: String) {
        dao.enqueue(OfflineVideoEntity(
            serverIdentity = connection.identity, youtubeId = youtubeId,
            sourceUrl = "https://youtu.be/$youtubeId", title = youtubeId, metadataUpdatedAt = clock,
        ), clock++)
    }

    private fun respond(vararg bodies: String) {
        bodies.forEach { server.enqueue(MockResponse().setBody(it)) }
    }

    private fun requests(count: Int): List<String> = (1..count).map { server.takeRequest().path!! }

    private fun id(youtubeId: String) = MessageDigest.getInstance("SHA-256")
        .digest(youtubeId.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    private fun video(youtubeId: String, status: String) =
        """{"id":"${id(youtubeId)}","youtube_id":"$youtubeId","url":"https://youtu.be/$youtubeId","title":"$youtubeId","status":"$status","created_at":"2026-01-01T00:00:00Z"}"""

    private fun job(youtubeId: String, status: String, progress: Int) =
        """{"id":"job-$youtubeId","video_id":"${id(youtubeId)}","type":"download","status":"$status","progress":$progress,"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"}"""
}
