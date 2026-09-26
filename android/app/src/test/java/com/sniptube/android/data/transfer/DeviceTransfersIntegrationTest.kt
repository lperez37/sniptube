package com.sniptube.android.data.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.media3.exoplayer.offline.Download
import com.sniptube.android.SniptubeApplication
import com.sniptube.android.data.local.AssetType
import com.sniptube.android.data.local.DeviceStage
import com.sniptube.android.data.local.LocalFileKind
import com.sniptube.android.data.local.LocalFileEntity
import com.sniptube.android.data.playback.extrasPath
import com.sniptube.android.data.local.OfflineVideoEntity
import com.sniptube.android.data.local.ServerStage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.io.File

/** Exercises the real application container, DownloadService dispatch, DownloadIndex and Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SniptubeApplication::class)
class DeviceTransfersIntegrationTest {
    @Test fun readyServerSourcePublishesOnlyValidatedCacheAndRemovalCannotResurrectIt() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<SniptubeApplication>()
        ShadowStatFs.registerStats(app.filesDir.path, 1_000_000, 900_000, 900_000)
        var service: ServiceController<SniptubeDownloadService>? = null
        var startId = 0
        fun driveService() {
            while (true) {
                val intent = shadowOf(app).nextStartedService ?: break
                if (intent.component?.className != SniptubeDownloadService::class.java.name) continue
                if (service == null) service = Robolectric.buildService(SniptubeDownloadService::class.java).create()
                service!!.get().onStartCommand(intent, 0, ++startId)
            }
        }
        val network = app.getSystemService(ConnectivityManager::class.java)
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true)
        val shadow = shadowOf(network)
        shadow.setActiveNetworkInfo(info)
        shadow.setNetworkCapabilities(network.activeNetwork,
            ShadowNetworkCapabilities.newInstance().also {
                shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            })
        shadow.networkCallbacks.forEach {
            it.onAvailable(network.activeNetwork!!)
            it.onCapabilitiesChanged(network.activeNetwork!!, network.getNetworkCapabilities(network.activeNetwork)!!)
        }
        val server = MockWebServer()
        val bytes = byteArrayOf(0, 0, 0, 16) + "ftyp".toByteArray() + ByteArray(64 * 1024) { it.toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                val start = range?.let { Regex("bytes=(\\d+)-(?:\\d+)?").matchEntire(it)
                    ?.groupValues?.get(1)?.toInt() } ?: 0
                val end = if (range == "bytes=0-31") 31 else bytes.lastIndex
                return MockResponse().setResponseCode(if (range == null) 200 else 206)
                    .addHeader("ETag", "\"integration-v1\"")
                    .apply { if (range != null) addHeader("Content-Range", "bytes $start-$end/${bytes.size}") }
                    .setBody(Buffer().write(bytes, start, end - start + 1))
                    .apply { if (range != "bytes=0-31") throttleBody(1024, 20, TimeUnit.MILLISECONDS) }
            }
        }
        server.start()
        val serverIdentity = app.container.serverConnections.update(server.url("/").toString()).identity
        val youtubeId = "fixture-transfer"
        val dao = app.container.database.offlineDao()
        val video = OfflineVideoEntity(serverIdentity, youtubeId, "https://youtu.be/$youtubeId",
            serverVideoId = "0123456789ab", title = "Transfer fixture", metadataUpdatedAt = 1)
        try {
            runBlocking {
                for (id in listOf(youtubeId, "second-transfer", "third-transfer")) {
                    val item = video.copy(youtubeId = id, serverVideoId = id)
                    dao.enqueue(item, 1)
                    dao.updateServerAcquisition(serverIdentity, id, 0, ServerStage.Ready,
                        item, null, true, 2)
                }
            }
            val transfers = app.container.deviceTransfers
            assertEquals(null, transfers.gate.eligibility())
            transfers.enterForeground()
            fun await(message: String, condition: () -> Boolean) {
                val end = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < end) {
                    shadowOf(Looper.getMainLooper()).idle()
                    driveService()
                    if (condition()) return
                    Thread.sleep(20)
                }
                assertTrue(message, condition())
            }
            val transferId = DeviceTransfers.transferId(serverIdentity, youtubeId)
            await("Transfer must actually start before pausing") {
                app.container.mediaDownloads.manager.currentDownloads.any {
                    it.request.id == transferId && it.bytesDownloaded >= 8192
                }
            }
            runBlocking { assertTrue(dao.pauseDeviceTransfer(serverIdentity, youtubeId, 3)) }
            transfers.refreshPolicy()
            await("Persisted pause must stop Media3") {
                app.container.mediaDownloads.manager.downloadIndex.getDownload(transferId)?.state == Download.STATE_STOPPED
            }
            // A lost/default-route callback followed by Wi-Fi recovery cannot override a user pause.
            shadow.networkCallbacks.forEach { it.onLost(network.activeNetwork!!) }
            shadowOf(Looper.getMainLooper()).idle()
            shadow.networkCallbacks.forEach {
                it.onAvailable(network.activeNetwork!!)
                it.onCapabilitiesChanged(network.activeNetwork!!, network.getNetworkCapabilities(network.activeNetwork)!!)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(DeviceStage.Paused, runBlocking { dao.getIntent(serverIdentity, youtubeId)?.deviceStage })
            assertTrue(app.container.mediaDownloads.manager.downloadIndex.getDownload(transferId)!!.stopReason != 0)
            runBlocking { assertTrue(dao.resumeDeviceTransfer(serverIdentity, youtubeId, 4)) }
            transfers.refreshPolicy()
            var ready = false
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                driveService()
                ready = runBlocking { dao.getIntent(serverIdentity, youtubeId)?.deviceStage == DeviceStage.Ready }
                if (ready) break
                Thread.sleep(25)
            }
            assertTrue("App-owned DownloadService should publish a fully cached asset: " +
                runBlocking { dao.getIntent(serverIdentity, youtubeId) }, ready)
            val file = runBlocking { dao.getLocalFiles(serverIdentity, youtubeId) }
                .single { it.kind == LocalFileKind.Media }
            assertEquals(AssetType.Media3Cache, file.assetType)
            assertEquals(bytes.size.toLong(), file.byteSize)
            assertNotNull(app.container.mediaDownloads.complete(file.path.removePrefix("media3:"), file.cacheKey!!))
            assertTrue(server.requestCount >= 2) // probe and verified download
            assertNotNull("DownloadService lifecycle must actually execute", service)
            val extrasRoot = File(app.filesDir, "offline-extras")
            val subtitle = extrasPath(extrasRoot, serverIdentity, youtubeId, 0, LocalFileKind.Subtitle, "en")
            val thumbnail = extrasPath(extrasRoot, serverIdentity, youtubeId, 0, LocalFileKind.Thumbnail, "")
            for ((kind, path, key) in listOf(Triple(LocalFileKind.Subtitle, subtitle, "en"),
                    Triple(LocalFileKind.Thumbnail, thumbnail, ""))) {
                path.parentFile!!.mkdirs()
                path.writeText("asset")
                runBlocking { assertTrue(dao.publishAuxiliaryFile(LocalFileEntity(serverIdentity, youtubeId,
                    kind, key, path.absolutePath, 5, generation = 0, validatedAt = 10))) }
            }
            val replacement = extrasPath(extrasRoot, serverIdentity, youtubeId, 1, LocalFileKind.Subtitle, "en")
            replacement.parentFile!!.mkdirs()
            replacement.writeText("replacement")
            await("More than two queued items must eventually publish") {
                runBlocking { listOf("second-transfer", "third-transfer").all {
                    dao.getIntent(serverIdentity, it)?.deviceStage == DeviceStage.Ready
                } }
            }

            val removal = CompletableFuture.runAsync {
                runBlocking { transfers.removeLocal(serverIdentity, youtubeId) }
            }
            await("Removal must not block the main-thread transfer pump") { removal.isDone }
            removal.get(1, TimeUnit.SECONDS)
            val removalDeadline = System.currentTimeMillis() + 5_000
            while (app.container.mediaDownloads.manager.downloadIndex.getDownload(file.path.removePrefix("media3:")) != null &&
                System.currentTimeMillis() < removalDeadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            assertEquals(DeviceStage.Removed, runBlocking { dao.getIntent(serverIdentity, youtubeId)?.deviceStage })
            assertTrue(runBlocking { dao.getLocalFiles(serverIdentity, youtubeId) }.isEmpty())
            assertFalse(subtitle.exists())
            assertFalse(thumbnail.exists())
            assertEquals("replacement", replacement.readText())
            val orphan = extrasPath(extrasRoot, serverIdentity, youtubeId, 0, LocalFileKind.Thumbnail, "orphan")
            orphan.writeText("unlinked after crash")
            val part = File(orphan.parentFile, "extra-in-progress.part")
            part.writeText("in-progress")
            transfers.restore()
            await("Restore must sweep orphan files from obsolete generations") { !orphan.exists() }
            assertTrue("In-progress staging is never swept", part.exists())
            assertEquals("Current generation is not swept", "replacement", replacement.readText())
            assertFalse(app.container.mediaDownloads.cache.isCached(file.cacheKey, 0, bytes.size.toLong()))

            val removedId = "remove-in-flight"
            runBlocking {
                val item = video.copy(youtubeId = removedId, serverVideoId = removedId)
                dao.enqueue(item, 5)
                dao.updateServerAcquisition(serverIdentity, removedId, 0, ServerStage.Ready,
                    item, null, true, 6)
            }
            transfers.refreshPolicy()
            val removedTransferId = DeviceTransfers.transferId(serverIdentity, removedId)
            await("Removal race must involve real in-flight bytes") {
                app.container.mediaDownloads.manager.currentDownloads.any {
                    it.request.id == removedTransferId && it.bytesDownloaded >= 8192
                }
            }
            val inFlightRemoval = CompletableFuture.runAsync {
                runBlocking { transfers.removeLocal(serverIdentity, removedId) }
            }
            await("In-flight removal must not block the main-thread transfer pump") { inFlightRemoval.isDone }
            inFlightRemoval.get(1, TimeUnit.SECONDS)
            await("In-flight removal must finish through Media3") {
                app.container.mediaDownloads.manager.downloadIndex.getDownload(removedTransferId) == null
            }
            assertEquals(DeviceStage.Removed, runBlocking { dao.getIntent(serverIdentity, removedId)?.deviceStage })
            assertTrue(runBlocking { dao.getLocalFiles(serverIdentity, removedId) }.isEmpty())
            assertTrue(app.container.mediaDownloads.cache.getCachedSpans(removedTransferId).isEmpty())

            val invalidId = "invalid-media"
            val invalidExtra = extrasPath(extrasRoot, serverIdentity, invalidId, 0,
                LocalFileKind.Subtitle, "en")
            runBlocking {
                val item = video.copy(youtubeId = invalidId, serverVideoId = invalidId)
                dao.enqueue(item, 15)
                dao.updateServerAcquisition(serverIdentity, invalidId, 0, ServerStage.Ready,
                    item, null, true, 16)
                assertTrue(dao.publishCompletedMedia(LocalFileEntity(serverIdentity, invalidId,
                    LocalFileKind.Media, path = "media3:missing-index", byteSize = 123,
                    generation = 0, validatedAt = 17, assetType = AssetType.Media3Cache,
                    cacheKey = "missing-index"), 17))
                invalidExtra.parentFile!!.mkdirs()
                invalidExtra.writeText("asset")
                assertTrue(dao.publishAuxiliaryFile(LocalFileEntity(serverIdentity, invalidId,
                    LocalFileKind.Subtitle, "en", invalidExtra.absolutePath, 5,
                    generation = 0, validatedAt = 18)))
            }
            transfers.restore()
            await("Invalid media restore must unlink its auxiliary file") {
                runBlocking { dao.getIntent(serverIdentity, invalidId)?.deviceStage == DeviceStage.Failed } &&
                    !invalidExtra.exists()
            }

            // A missing server file must return to server acquisition, not strand device sync.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
            }
            val missingId = "missing-source"
            runBlocking {
                val item = video.copy(youtubeId = missingId, serverVideoId = missingId)
                dao.enqueue(item, 20)
                dao.updateServerAcquisition(serverIdentity, missingId, 0, ServerStage.Ready,
                    item, null, true, 21)
            }
            transfers.refreshPolicy()
            await("Confirmed source 404 must reset acquisition and device stage") {
                runBlocking { dao.getIntent(serverIdentity, missingId)?.let {
                    it.generation == 1L && it.serverStage != ServerStage.Ready &&
                        it.deviceStage == DeviceStage.Pending
                } == true }
            }
        } finally {
            app.container.deviceTransfers.leaveForeground()
            service?.destroy()
            server.shutdown()
        }
    }
}
