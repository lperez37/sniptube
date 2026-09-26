package com.sniptube.android.data.transfer

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaCacheTest {
    @Test fun explicitCellularApprovalOnlyLetsTheSelectedSourceRead() {
        val server = MockWebServer()
        val payload = byteArrayOf(0, 0, 0, 16) + "ftyp".toByteArray() + ByteArray(64)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val probe = request.getHeader("Range") == "bytes=0-31"
                val end = if (probe) 31 else payload.lastIndex
                return MockResponse().setResponseCode(if (probe) 206 else 200)
                    .addHeader("Content-Range", "bytes 0-$end/${payload.size}")
                    .addHeader("ETag", "\"v1\"")
                    .setBody(okio.Buffer().write(payload, 0, end + 1))
            }
        }
        server.start()
        try {
            val approved = server.url("/approved").toString()
            val other = server.url("/other").toString()
            val allowed = mutableSetOf<String>()
            val transport = SourceTransport { url -> if (url in allowed) null else "Wi-Fi required" }
            try { transport.probe(approved); org.junit.Assert.fail("No consent yet") }
            catch (_: IOException) { assertEquals(0, server.requestCount) }
            allowed.add(approved)
            val identity = transport.probe(approved)
            transport.remember(approved, identity)
            transport.client.newCall(Request.Builder().url(approved).build()).execute().use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.bytes().isNotEmpty())
            }
            try { transport.probe(other); org.junit.Assert.fail("Another queue item must not use cellular") }
            catch (_: IOException) { assertEquals(2, server.requestCount) }
            allowed.clear()
            try { transport.probe(approved); org.junit.Assert.fail("Revoked consent must stop reads") }
            catch (_: IOException) { assertEquals(2, server.requestCount) }
        } finally { server.shutdown() }
    }

    @Test fun wifiPolicyIsLiteralAndLanFriendly() {
        assertEquals(null, WifiGate.reason(wifi = true, cellular = false, vpn = false))
        assertEquals(null, WifiGate.reason(wifi = true, cellular = false, vpn = true))
        assertTrue(WifiGate.reason(wifi = false, cellular = true, vpn = false)!!.contains("cellular"))
        assertTrue(WifiGate.reason(wifi = true, cellular = true, vpn = true)!!.contains("VPN"))
        assertTrue(WifiGate.reason(wifi = false, cellular = false, vpn = true)!!.contains("VPN"))
        assertTrue(WifiGate.reason(wifi = false, cellular = false, vpn = false)!!.contains("Wi-Fi"))
    }

    @Test fun resumeRequiresMatchingStrongRepresentationAndOffsets() {
        val source = SourceIdentity(200, "\"v1\"", "video/mp4")
        assertEquals(50L, MediaDownloads.rangeStart("bytes=50-199"))
        assertEquals(50L, MediaDownloads.rangeStart("bytes=50-"))
        assertTrue(MediaDownloads.validResume(206, "bytes 50-199/200", "\"v1\"", 50, source))
        assertFalse(MediaDownloads.validResume(200, null, "\"v1\"", 50, source))
        assertFalse(MediaDownloads.validResume(206, "bytes 0-149/200", "\"v1\"", 50, source))
        assertFalse(MediaDownloads.validResume(206, "bytes 50-199/201", "\"v1\"", 50, source))
        assertFalse(MediaDownloads.validResume(206, "bytes 50-199/200", "\"v2\"", 50, source))
    }

    @Test fun restoredUnverifiedDownloadCannotReadEvenAnUnrangedFirstByte() {
        val server = MockWebServer()
        server.start()
        try {
            val transport = SourceTransport { null }
            val url = server.url("/videos/abc/source").toString()
            for (range in listOf(null, "bytes=0-", "bytes=4096-")) {
                val request = Request.Builder().url(url).apply {
                    if (range != null) header("Range", range)
                }.build()
                try {
                    transport.client.newCall(request).execute().close()
                    org.junit.Assert.fail("Unverified media read was allowed: $range")
                } catch (error: IOException) {
                    assertTrue(error.message!!.contains("probed"))
                }
            }
            assertEquals(0, server.requestCount)
            val payload = byteArrayOf(0, 0, 0, 16) + "ftyp".toByteArray() + ByteArray(40)
            server.enqueue(MockResponse().setResponseCode(206)
                .addHeader("Content-Range", "bytes 0-31/${payload.size}")
                .addHeader("ETag", "\"verified\"")
                .setBody(okio.Buffer().write(payload, 0, 32)))
            val identity = transport.probe(url)
            transport.remember(url, identity)
            server.enqueue(MockResponse().setResponseCode(206)
                .addHeader("Content-Range", "bytes 32-${payload.lastIndex}/${payload.size}")
                .addHeader("ETag", identity.etag)
                .setBody(okio.Buffer().write(payload, 32, payload.size - 32)))
            transport.client.newCall(Request.Builder().url(url).header("Range", "bytes=32-").build())
                .execute().use { assertEquals(206, it.code) }
            assertEquals(2, server.requestCount)
            assertEquals("bytes=0-31", server.takeRequest().getHeader("Range"))
            assertEquals(identity.etag, server.takeRequest().getHeader("If-Range"))
        } finally { server.shutdown() }
    }

    @Test fun reprobeDetectsChangedSourceBeforeAnyStalePartialBytesAreAccepted() {
        val server = MockWebServer()
        val observed = java.util.Collections.synchronizedList(mutableListOf<Pair<String?, String?>>())
        var version = 1
        val payload = byteArrayOf(0, 0, 0, 16) + "ftyp".toByteArray() + ByteArray(120) { it.toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                observed.add(request.getHeader("Range") to request.getHeader("If-Range"))
                val etag = "\"v$version\""
                val range = request.getHeader("Range")
                val start = range?.let { Regex("bytes=(\\d+)-(?:\\d+)?").matchEntire(it)
                    ?.groupValues?.get(1)?.toInt() } ?: 0
                val end = if (range == "bytes=0-31") 31 else payload.lastIndex
                return MockResponse().setResponseCode(if (range == null) 200 else 206)
                    .addHeader("ETag", etag)
                    .addHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .addHeader("Content-Type", "application/octet-stream")
                    .setBody(okio.Buffer().write(payload, start, end - start + 1))
            }
        }
        server.start()
        try {
            val transport = SourceTransport { null }
            val url = server.url("/videos/abc/source").toString()
            val first = transport.probe(url)
            transport.remember(url, first)
            transport.client.newCall(Request.Builder().url(url).header("Range", "bytes=32-").build())
                .execute().use { assertEquals(206, it.code) }
            version = 2
            try {
                transport.client.newCall(Request.Builder().url(url).header("Range", "bytes=64-").build())
                    .execute().close()
                org.junit.Assert.fail("Changed representation must not extend cached v1 bytes")
            } catch (error: IOException) {
                assertTrue(error.message!!.contains("changed"))
            }
            val second = transport.probe(url)
            assertEquals("\"v2\"", second.etag)
            assertEquals(first.size, second.size)
            assertEquals(listOf(
                "bytes=0-31" to null,
                "bytes=32-" to "\"v1\"",
                "bytes=64-" to "\"v1\"",
                "bytes=0-31" to null,
            ), observed)
        } finally { server.shutdown() }
    }

    @Test fun probeRejectsInconsistentPartialLengthEvenWithPlausibleMediaSignature() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(206)
            .addHeader("Content-Range", "bytes 0-31/128")
            .addHeader("ETag", "\"v1\"")
            .setBody(okio.Buffer().write(byteArrayOf(0, 0, 0, 16) + "ftyp".toByteArray())))
        server.start()
        try {
            try {
                SourceTransport { null }.probe(server.url("/source").toString())
                org.junit.Assert.fail("Truncated range cannot identify a safe cached representation")
            } catch (error: IOException) {
                assertTrue(error.message!!.contains("inconsistent"))
            }
        } finally { server.shutdown() }
    }

    @Test fun completedProgressiveCacheReadsWithoutAnyHttpUpstream() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtures = listOf(
            byteArrayOf(0, 0, 0, 16) + "ftyp".toByteArray() + ByteArray(32768) { it.toByte() },
            byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) + ByteArray(32768) { it.toByte() },
            byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) + ByteArray(4096) { it.toByte() },
        )
        val server = MockWebServer()
        server.start()
        val url = server.url("/videos/abc/source").toString()
        fixtures.forEachIndexed { index, bytes ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes))
                .addHeader("ETag", "\"fixture$index\"")
                .addHeader("Content-Type", "application/octet-stream"))
        }
        val directory = context.filesDir.resolve("media-cache-test-${System.nanoTime()}")
        val provider = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(directory, NoOpCacheEvictor(), provider)
        val executor = Executors.newSingleThreadExecutor()
        val manager = DownloadManager(context, provider, cache,
            OkHttpDataSource.Factory(OkHttpClient()), executor)
        manager.requirements = Requirements(0)
        try {
            fixtures.forEachIndexed { index, fixture ->
                val id = "fixture-cache-$index"
                val request = DownloadRequest.Builder(id, Uri.parse(url))
                    .setMimeType(MimeTypes.VIDEO_UNKNOWN).setCustomCacheKey(id).build()
                manager.addDownload(request)
                manager.resumeDownloads()
                var finished: Download? = null
                val end = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < end) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    finished = manager.downloadIndex.getDownload(id)
                    if (finished?.state == Download.STATE_COMPLETED || finished?.state == Download.STATE_FAILED) break
                    Thread.sleep(20)
                }
                assertEquals(Download.STATE_COMPLETED, finished?.state)
                assertEquals(fixture.size.toLong(), finished?.contentLength)
                assertTrue(cache.isCached(id, 0, fixture.size.toLong()))
            }
            server.shutdown() // any later HTTP access fails; also check request count below.
            fixtures.forEachIndexed { index, fixture ->
                val id = "fixture-cache-$index"
                val offline = CacheDataSource.Factory().setCache(cache)
                    .setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null).createDataSource()
                val bytes = ByteArray(fixture.size)
                try {
                    offline.open(DataSpec.Builder().setUri(url).setKey(id).build())
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = offline.read(bytes, offset, bytes.size - offset)
                        if (count < 0) break
                        offset += count
                    }
                    assertEquals(fixture.size, offset)
                } finally { offline.close() }
                assertArrayEquals(fixture, bytes)
                val missing = CacheDataSource.Factory().setCache(cache)
                    .setUpstreamDataSourceFactory(null).createDataSource()
                try {
                    missing.open(DataSpec.Builder().setUri(url).setKey(id)
                        .setPosition(fixture.size.toLong() + 1).build())
                    org.junit.Assert.fail("Cache miss must not fall back to HTTP")
                } catch (_: IOException) { /* expected */ } finally { missing.close() }
            }
            assertEquals(3, server.requestCount)
        } finally {
            manager.release()
            cache.release()
            executor.shutdownNow()
            server.shutdown()
        }
    }

    @Test fun pausedTransferResumesFromCachedOffsetWithoutRepeatingPrefix() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = byteArrayOf(0, 0, 0, 16) + "ftyp".toByteArray() +
            ByteArray(256 * 1024) { (it % 251).toByte() }
        val ranges = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val ifRanges = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                ranges.add(range)
                ifRanges.add(request.getHeader("If-Range"))
                val offset = range?.let { Regex("bytes=(\\d+)-(?:\\d+)?").matchEntire(it)?.groupValues?.get(1)?.toInt() } ?: 0
                val end = if (range == "bytes=0-31") 31 else payload.size - 1
                return MockResponse().apply {
                    setResponseCode(if (range == null) 200 else 206)
                    if (range != null) addHeader("Content-Range", "bytes $offset-$end/${payload.size}")
                    addHeader("ETag", "\"fixture-stable\"")
                    setBody(okio.Buffer().write(payload, offset, end - offset + 1))
                    if (range != "bytes=0-31") throttleBody(1024, 30, TimeUnit.MILLISECONDS)
                }
            }
        }
        server.start()
        val provider = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(context.filesDir.resolve("resume-test-${System.nanoTime()}"),
            NoOpCacheEvictor(), provider)
        val executor = Executors.newSingleThreadExecutor()
        val transport = SourceTransport { null }
        val url = server.url("/source").toString()
        transport.remember(url, transport.probe(url))
        val manager = DownloadManager(context, provider, cache,
            OkHttpDataSource.Factory(transport.client), executor)
        manager.requirements = Requirements(0)
        val id = "resume-fixture"
        try {
            manager.addDownload(DownloadRequest.Builder(id, Uri.parse(url))
                .setMimeType(MimeTypes.VIDEO_UNKNOWN).setCustomCacheKey(id).build())
            manager.resumeDownloads()
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline &&
                (manager.currentDownloads.firstOrNull { it.request.id == id }?.bytesDownloaded ?: 0) < 8192) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            assertTrue("First request must make partial progress",
                (manager.currentDownloads.firstOrNull { it.request.id == id }?.bytesDownloaded ?: 0) >= 8192)
            manager.setStopReason(id, 1)
            while (System.currentTimeMillis() < deadline &&
                manager.downloadIndex.getDownload(id)?.state != Download.STATE_STOPPED) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            assertEquals(Download.STATE_STOPPED, manager.downloadIndex.getDownload(id)?.state)
            manager.setStopReason(id, 0)
            while (System.currentTimeMillis() < deadline &&
                manager.downloadIndex.getDownload(id)?.state != Download.STATE_COMPLETED) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            assertEquals(Download.STATE_COMPLETED, manager.downloadIndex.getDownload(id)?.state)
            assertTrue(cache.isCached(id, 0, payload.size.toLong()))
            assertTrue("Resumed request should start after cached bytes: $ranges",
                ranges.indices.any { index -> index > 0 && ranges[index]?.let {
                    (Regex("bytes=(\\d+)-(?:\\d+)?").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0) > 0
                } == true && ifRanges[index] == "\"fixture-stable\"" })
        } finally {
            manager.release()
            cache.release()
            executor.shutdownNow()
            server.shutdown()
        }
    }
}
