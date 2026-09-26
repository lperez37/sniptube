package com.sniptube.android.ui.library

import com.sniptube.android.data.library.LibrarySnapshot
import com.sniptube.android.data.library.VideoKey
import com.sniptube.android.data.local.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LibraryPresentationTest {
    private fun video(id: String, server: String = "https://one.test", duration: Long? = null, size: Long? = null) =
        OfflineVideoEntity(server, id, "https://youtube.com/watch?v=$id", title = id,
            durationMs = duration, serverFileSizeBytes = size, metadataUpdatedAt = 1)

    @Test fun `selection does not accidentally include another server or later page`() {
        val one = video("same")
        val otherServer = video("same", "https://two.test")
        val later = video("later")
        val selectedIds = listOf(one.key.selectionId)
        assertEquals(listOf(one), selectedVideos(listOf(otherServer, later, one), selectedIds))
    }

    @Test fun `refresh keeps selection attached to ID not row position or old metadata`() {
        val old = video("one")
        val refreshed = old.copy(title = "Refreshed title", serverFileSizeBytes = 1234)
        assertEquals(listOf(refreshed), selectedVideos(listOf(video("two"), refreshed), listOf(old.key.selectionId)))
        assertTrue(selectedVideos(listOf(video("two")), listOf(old.key.selectionId)).isEmpty())
    }

    @Test fun `mixed metadata preserves unknown counts and avoids duplicate totals`() {
        val first = video("one", duration = 60_000, size = 1024)
        val second = video("two", duration = null, size = null)
        assertEquals(VideoSummary(2, 60_000, 1, 1024, 1), summarize(listOf(first, second, first)))
        assertTrue(summarize(listOf(second)).description().contains("1 size unknown"))
    }

    @Test fun `zero byte hint is known while null is unknown`() {
        assertEquals(0, summarize(listOf(video("empty", size = 0))).unknownSizes)
        assertEquals(1, summarize(listOf(video("unknown"))).unknownSizes)
    }

    @Test fun `server ready and partial asset cannot become ready offline`() {
        val intent = intent(DeviceStage.Running)
        assertTrue(LibrarySnapshot(queue = listOf(intent), assets = listOf(asset())).readyKeys().isEmpty())
        assertTrue(LibrarySnapshot(queue = listOf(intent.copy(deviceStage = DeviceStage.Ready))).readyKeys().isEmpty())
    }

    @Test fun `stale generation and different server media cannot unlock playback`() {
        val queue = listOf(intent(DeviceStage.Ready))
        assertTrue(LibrarySnapshot(queue = queue, assets = listOf(asset().copy(generation = 0))).readyKeys().isEmpty())
        assertTrue(LibrarySnapshot(queue = queue, assets = listOf(asset().copy(serverIdentity = "https://two.test"))).readyKeys().isEmpty())
        assertEquals(setOf(VideoKey("https://one.test", "one")), LibrarySnapshot(queue = queue, assets = listOf(asset())).readyKeys())
    }

    @Test fun `unknown device total reports bytes without invented percentage`() {
        val transfer = DeviceTransferBindingEntity("https://one.test", "one", "transfer", "", "", bytesDownloaded = 1024, generation = 1, updatedAt = 1)
        val label = queueLabel(intent(DeviceStage.Running), false, null, transfer)
        assertTrue(label.contains("KiB"))
        assertTrue(label.contains("unknown size"))
        assertFalse(label.contains("%"))
    }

    @Test fun `collection runtime and cellular warning preserve unknowns and remaining bytes`() {
        val known = video("one", duration = 90_000, size = 10_000_000)
        val unknown = video("two")
        assertEquals("1:30 known viewing time · 1 unknown", viewingTimeText(listOf(known, unknown, known)))
        val transfer = DeviceTransferBindingEntity(known.serverIdentity, known.youtubeId, "transfer", "", "",
            bytesDownloaded = 2_000_000, totalBytes = 10_000_000, generation = 0, updatedAt = 1)
        val estimate = LibrarySnapshot().syncEstimate(listOf(known, unknown, known), listOf(transfer))
        assertEquals(SyncEstimate(8_000_000, 1, 2), estimate)
        assertTrue(estimate.warningText().contains("8.0 MB"))
        assertTrue(estimate.warningText().contains("1 video has unknown size"))
    }

    @Test fun `watched state uses ninety percent or a manual mark`() {
        val item = video("seen", duration = 100_000)
        assertFalse(isWatched(item, null))
        assertFalse(isWatched(item, PlaybackProgressEntity(item.serverIdentity, item.youtubeId, 89_999, null, 1)))
        assertFalse(isWatched(item, PlaybackProgressEntity(item.serverIdentity, item.youtubeId, 90_000, null, 2)))
        assertTrue(isWatched(item, PlaybackProgressEntity(item.serverIdentity, item.youtubeId, 90_001, null, 2)))
        assertTrue(isWatched(item.copy(durationMs = null), PlaybackProgressEntity(item.serverIdentity, item.youtubeId, 0, null, 3, viewed = true)))
    }

    @Test fun `sync progress only shows a percentage with a real known total`() {
        val running = intent(DeviceStage.Running)
        val unknown = syncProgress(running, null, null)!!
        assertNull(unknown.fraction)
        assertTrue(unknown.label.contains("unknown size"))
        val known = DeviceTransferBindingEntity(running.serverIdentity, running.youtubeId, "id", "", "",
            bytesDownloaded = 3_000_000, totalBytes = 10_000_000, generation = 1, updatedAt = 1)
        assertEquals(0.3f, syncProgress(running, null, known)!!.fraction!!, 0.001f)
        val server = running.copy(deviceStage = DeviceStage.Pending, serverStage = ServerStage.Running)
        val job = ServerJobBindingEntity(server.serverIdentity, server.youtubeId, "job", "running", 42, 1, 1)
        assertEquals(0.42f, syncProgress(server, job, null)!!.fraction!!, 0.001f)
        assertNull(syncProgress(running.copy(userPaused = true), null, known))
    }

    @Test fun `pause remains explicit even while server prepares`() {
        val paused = intent(DeviceStage.Paused).copy(serverStage = ServerStage.Running, userPaused = true)
        assertTrue(queueLabel(paused, false, null, null).startsWith("Paused on phone"))
    }

    @Test fun `offline thumbnail uses only a current local file while browse can show YouTube art`() {
        val video = video("dQw4w9WgXcQ").copy(thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg")
        val entry = intent(DeviceStage.Ready).copy(youtubeId = video.youtubeId)
        val withoutArt = LibrarySnapshot(queue = listOf(entry))
        assertNull(withoutArt.thumbnailFor(video, ready = true))
        assertEquals(video.thumbnailUrl, withoutArt.thumbnailFor(video, ready = false))
        val file = File.createTempFile("sniptube-art", ".jpg")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val thumbnail = LocalFileEntity(video.serverIdentity, video.youtubeId, LocalFileKind.Thumbnail,
                path = file.path, byteSize = file.length(), generation = 1, validatedAt = 1)
            assertEquals(file, withoutArt.copy(assets = listOf(thumbnail)).thumbnailFor(video, ready = true))
            assertNull(withoutArt.copy(assets = listOf(thumbnail.copy(generation = 0))).thumbnailFor(video, ready = true))
        } finally { file.delete() }
    }

    private fun intent(stage: DeviceStage) = QueueIntentEntity("https://one.test", "one",
        serverStage = ServerStage.Ready, deviceStage = stage, generation = 1, requestedAt = 1, updatedAt = 1)

    private fun asset() = LocalFileEntity("https://one.test", "one", LocalFileKind.Media,
        path = "cache:key", byteSize = 1024, generation = 1, validatedAt = 1, assetType = AssetType.Media3Cache, cacheKey = "key")
}
