package com.sniptube.android.data.playback

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.WritableDownloadIndex
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sniptube.android.SniptubeApplication
import com.sniptube.android.data.api.SniptubeApiClient
import com.sniptube.android.data.local.*
import com.sniptube.android.data.transfer.MediaDownloads
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class OfflinePlaybackTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun canonicalThumbnailOnlyAcceptsVideoIds() {
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", canonicalYoutubeThumbnail("dQw4w9WgXcQ"))
        assertNull(canonicalYoutubeThumbnail("../../private"))
        assertNull(canonicalYoutubeThumbnail(""))
    }

    @Test fun cacheMissNeverContactsHttpAndLocalSubtitlesRemainFileReadable() {
        val app = isolatedApplication()
        val media = MediaDownloads(app)
        val server = MockWebServer()
        server.start()
        val source = media.offlineDataSource().createDataSource()
        try {
            try {
                source.open(DataSpec.Builder().setUri(server.url("/source").toString()).setKey("absent").build())
                fail("Missing cache must fail")
            } catch (_: IOException) { }
            assertEquals(0, server.requestCount)
            val subtitle = File(context.filesDir, "fixture-subtitle.vtt")
            subtitle.writeText("WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n")
            val fileSource = androidx.media3.datasource.FileDataSource.Factory().createDataSource()
            try {
                assertEquals(subtitle.length(), fileSource.open(DataSpec(Uri.fromFile(subtitle))))
                val buffer = ByteArray(6)
                assertEquals(6, fileSource.read(buffer, 0, 6))
                assertEquals("WEBVTT", String(buffer))
            } finally { fileSource.close(); subtitle.delete() }
            assertEquals(0, server.requestCount)
        } finally {
            source.close()
            releaseFixture(media)
            server.shutdown()
        }
    }

    @Test fun finalSeekSnapshotSurvivesWriterAndDatabaseRecreation() = runBlocking {
        val name = "playback-persistence-test"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, SniptubeDatabase::class.java, name).build()
        var db = open()
        try {
            db.offlineDao().enqueue(video("http://example.test/"), 1)
            val writer = PlaybackProgressWriter(db.offlineDao())
            writer.save(PlaybackProgressEntity("http://example.test/", "video", 2_000, 90_000, 1))
            writer.save(PlaybackProgressEntity("http://example.test/", "video", 81_000, 90_000, 2))
            writer.close()
            writer.awaitClosed()
            db.close()
            db = open()
            assertEquals(81_000L, resumePosition(db.offlineDao().getPlaybackProgress("http://example.test/", "video")))
            assertEquals(0L, resumePosition(PlaybackProgressEntity("s", "v", 90_000, 90_000, 1)))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun readyMarkerResolvesDownloadRequestAndSeeksCachedTailWithoutHttp() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SniptubeDatabase::class.java).build()
        val app = isolatedApplication()
        val media = MediaDownloads(app)
        val server = MockWebServer()
        server.start()
        val identity = server.url("/").toString()
        val key = "playback-fixture-key"
        val bytes = ByteArray(64) { it.toByte() }
        try {
            val hole = media.cache.startReadWrite(key, 0, bytes.size.toLong())
            try {
                val spanFile = media.cache.startFile(key, 0, bytes.size.toLong())
                spanFile.writeBytes(bytes)
                media.cache.commitFile(spanFile, bytes.size.toLong())
            } finally { media.cache.releaseHoleSpan(hole) }
            val request = DownloadRequest.Builder("playback-fixture", Uri.parse(server.url("/source").toString()))
                .setCustomCacheKey(key).build()
            (media.manager.downloadIndex as WritableDownloadIndex).putDownload(Download(request,
                Download.STATE_COMPLETED, 1, 1, bytes.size.toLong(), Download.STOP_REASON_NONE,
                Download.FAILURE_REASON_NONE))
            val dao = db.offlineDao()
            dao.enqueue(video(identity), 1)
            dao.updateServerAcquisition(identity, "video", 0, ServerStage.Ready, null, null, true, 1)
            dao.publishCompletedMedia(LocalFileEntity(identity, "video", LocalFileKind.Media,
                path = "media3:playback-fixture", byteSize = bytes.size.toLong(), generation = 0,
                validatedAt = 1, assetType = AssetType.Media3Cache, cacheKey = key), 1)
            dao.upsertPlaybackProgress(PlaybackProgressEntity(identity, "video", 70_000, 90_000, 1))
            val opened = OfflinePlayback(dao, media).open(identity, "video")
            assertEquals(key, opened.mediaItem.localConfiguration!!.customCacheKey)
            assertEquals(70_000L, opened.positionMs)
            val source = media.offlineDataSource().createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(request.uri).setKey(key).setPosition(60).setLength(4).build())
                val tail = ByteArray(4)
                assertEquals(4, source.read(tail, 0, 4))
                assertArrayEquals(bytes.copyOfRange(60, 64), tail)
            } finally { source.close() }
            media.cache.removeResource(key)
            try { OfflinePlayback(dao, media).open(identity, "video"); fail("Lost cache must invalidate open") }
            catch (_: OfflineUnavailableException) { }
            assertEquals(DeviceStage.Failed, dao.getIntent(identity, "video")!!.deviceStage)
            assertTrue("Downloads must offer an actionable retry", dao.retryFailed(identity, "video", 3))
            assertEquals(DeviceStage.Queued, dao.getIntent(identity, "video")!!.deviceStage)
            assertEquals(0, server.requestCount)
        } finally {
            releaseFixture(media); server.shutdown(); db.close()
        }
    }

    @Test fun extrasAreOptionalBoundedAndCannotPublishAfterRemoval() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SniptubeDatabase::class.java).build()
        val server = MockWebServer()
        server.start()
        val identity = server.url("/").toString()
        val dao = db.offlineDao()
        try {
            dao.enqueue(video(identity), 1)
            dao.publishCompletedMedia(LocalFileEntity(identity, "video", LocalFileKind.Media,
                path = "media3:fixture", byteSize = 10, generation = 0, validatedAt = 1,
                assetType = AssetType.Media3Cache, cacheKey = "fixture"), 1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.endsWith("/subtitles") -> MockResponse().setBody(
                        """[{"language":"../../en","url":"/track.vtt"}]""")
                    else -> {
                        runBlocking { dao.recordLocalRemoval(identity, "video", 2) }
                        MockResponse().setBody("WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n")
                    }
                }
            }
            OfflineExtras(context, dao, ::SniptubeApiClient) { null }.sync(identity, "video")
            assertTrue(dao.getLocalFiles(identity, "video").isEmpty())
            val path = extrasPath(File(context.filesDir, "offline-extras"), identity, "video", 0,
                LocalFileKind.Subtitle, "../../en")
            assertFalse(path.exists())
            assertEquals(DeviceStage.Removed, dao.getIntent(identity, "video")!!.deviceStage)
        } finally { server.shutdown(); db.close() }
    }

    @Test fun extrasPathsAreDeterministicScopedAndCannotEscapeRoot() {
        val root = File(context.filesDir, "extras-path-test")
        val first = extrasPath(root, "../../server", "../../video", 4, LocalFileKind.Subtitle, "../../en")
        assertTrue(first.canonicalPath.startsWith(root.canonicalPath + File.separator))
        assertEquals(first, extrasPath(root, "../../server", "../../video", 4, LocalFileKind.Subtitle, "../../en"))
        assertNotEquals(first, extrasPath(root, "other", "../../video", 4, LocalFileKind.Subtitle, "../../en"))
        assertNotEquals(first, extrasPath(root, "../../server", "../../video", 5, LocalFileKind.Subtitle, "../../en"))
        val output = ByteArrayOutputStream()
        try {
            copyBounded(ByteArrayInputStream(ByteArray(9000)), output, 8192)
            fail("Oversized optional files must be rejected")
        } catch (_: IOException) { assertTrue(output.size() <= 8192) }
    }

    @Test fun extrasPublishLocalTracksAndRetryDoesNotDownloadThemAgain(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SniptubeDatabase::class.java).build()
        val server = MockWebServer()
        server.start()
        val identity = server.url("/").toString()
        val dao = db.offlineDao()
        try {
            dao.enqueue(video(identity), 1)
            dao.publishCompletedMedia(LocalFileEntity(identity, "video", LocalFileKind.Media,
                path = "media3:fixture", byteSize = 10, generation = 0, validatedAt = 1,
                assetType = AssetType.Media3Cache, cacheKey = "fixture"), 1)
            val tracks = """[{"language":"en","url":"/track.vtt"}]"""
            server.enqueue(MockResponse().setBody(tracks))
            server.enqueue(MockResponse().setBody("WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n"))
            val extras = OfflineExtras(context, dao, ::SniptubeApiClient) { null }
            extras.sync(identity, "video")
            val subtitle = dao.getLocalFiles(identity, "video").single { it.kind == LocalFileKind.Subtitle }
            assertTrue(File(subtitle.path).isFile)
            assertEquals("text/vtt", subtitle.contentType)
            assertEquals("en", subtitle.language)
            assertEquals(DeviceStage.Ready, dao.getIntent(identity, "video")!!.deviceStage)
            server.enqueue(MockResponse().setBody(tracks))
            extras.sync(identity, "video")
            assertEquals(3, server.requestCount) // second discovery, no second byte transfer
            File(subtitle.path).delete()
        } finally { server.shutdown(); db.close() }
    }

    @Test fun extrasDoNotContactServerWithoutWifiOrDefaultGate(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SniptubeDatabase::class.java).build()
        val server = MockWebServer().apply { start() }
        val identity = server.url("/").toString()
        try {
            val dao = readyExtrasDao(db, identity)
            OfflineExtras(context, dao, ::SniptubeApiClient).sync(identity, "video")
            OfflineExtras(context, dao, ::SniptubeApiClient) { "Cellular route" }.sync(identity, "video")
            assertEquals(0, server.requestCount)
        } finally { server.shutdown(); db.close() }
    }

    @Test fun extrasStopBeforeNextAssetRequestWhenWifiChanges(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SniptubeDatabase::class.java).build()
        val server = MockWebServer().apply { start() }
        val identity = server.url("/").toString()
        val reason = AtomicReference<String?>(null)
        try {
            val dao = readyExtrasDao(db, identity)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    reason.set("Cellular route")
                    return MockResponse().setBody("""[{"language":"en","url":"/track.vtt"}]""")
                }
            }
            OfflineExtras(context, dao, ::SniptubeApiClient, reason::get).sync(identity, "video")
            assertEquals(1, server.requestCount)
            assertTrue(dao.getLocalFiles(identity, "video").none { it.kind == LocalFileKind.Subtitle })
        } finally { server.shutdown(); db.close() }
    }

    @Test fun extrasDiscardPartialSubtitleWhenWifiChangesMidstream(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SniptubeDatabase::class.java).build()
        val server = MockWebServer().apply { start() }
        val identity = server.url("/").toString()
        val reason = AtomicReference<String?>(null)
        try {
            val dao = readyExtrasDao(db, identity)
            server.enqueue(MockResponse().setBody("""[{"language":"en","url":"/track.vtt"}]"""))
            server.enqueue(MockResponse().setBody("WEBVTT\n" + "a".repeat(200_000))
                .throttleBody(1024, 100, TimeUnit.MILLISECONDS))
            val sync = async(Dispatchers.IO) {
                OfflineExtras(context, dao, ::SniptubeApiClient, reason::get).sync(identity, "video")
            }
            repeat(100) {
                if (server.requestCount >= 2) return@repeat
                delay(20)
            }
            assertEquals(2, server.requestCount)
            delay(250) // The first chunks have arrived; a later upstream read must fail.
            reason.set("Cellular route")
            sync.await()
            assertTrue(dao.getLocalFiles(identity, "video").none { it.kind == LocalFileKind.Subtitle })
            assertFalse(extrasPath(File(context.filesDir, "offline-extras"), identity, "video", 0,
                LocalFileKind.Subtitle, "en").exists())
        } finally { server.shutdown(); db.close() }
    }

    @Test fun thumbnailTrustRejectsUntrustedHostsAndCredentials() {
        val server = MockWebServer().apply { start() }
        try {
            val base = server.url("/")
            assertTrue(trustedThumbnail(server.url("/image"), base))
            assertTrue(trustedThumbnail("https://i.ytimg.com/vi/x/default.jpg".toHttpUrl(), base))
            assertFalse(trustedThumbnail("https://evil.example/image".toHttpUrl(), base))
            assertFalse(trustedThumbnail("http://i.ytimg.com/image".toHttpUrl(), base))
            assertFalse(trustedThumbnail("http://user:pass@${base.host}:${base.port}/image".toHttpUrl(), base))
        } finally { server.shutdown() }
    }

    private suspend fun readyExtrasDao(db: SniptubeDatabase, identity: String): OfflineDao =
        db.offlineDao().also { dao ->
            dao.enqueue(video(identity), 1)
            dao.publishCompletedMedia(LocalFileEntity(identity, "video", LocalFileKind.Media,
                path = "media3:fixture", byteSize = 10, generation = 0, validatedAt = 1,
                assetType = AssetType.Media3Cache, cacheKey = "fixture"), 1)
        }

    private fun video(server: String) = OfflineVideoEntity(server, "video", "https://youtu.be/video",
        serverVideoId = "fixture", title = "Fixture", metadataUpdatedAt = 1)

    // Attach a real application context without onCreate's network/reconciliation startup.
    // Robolectric 4.14 no longer exposes buildApplication.
    private fun isolatedApplication(): SniptubeApplication =
        Instrumentation.newApplication(SniptubeApplication::class.java, context) as SniptubeApplication

    private fun releaseFixture(media: MediaDownloads) {
        media.manager.release()
        media.cache.release()
        // Production owns this provider for the whole process; the fixture has a shorter lifetime.
        ReflectionHelpers.getField<StandaloneDatabaseProvider>(media, "databaseProvider").close()
    }
}
