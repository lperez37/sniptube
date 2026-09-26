package com.sniptube.android.data.transfer

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.sniptube.android.MainActivity
import com.sniptube.android.R
import com.sniptube.android.SniptubeApplication
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

data class SourceIdentity(val size: Long, val etag: String, val contentType: String?)

/** Only a direct HTTP status from the source probe; policy/transport failures stay IOExceptions. */
internal class SourceHttpException(val status: Int) : IOException("Source returned HTTP $status.")

/** Probes discover a new representation; transfer reads must match the last accepted one. */
internal class SourceTransport(private val routeReason: (String) -> String?) {
    private val expected = ConcurrentHashMap<String, SourceIdentity>()
    private class Probe
    val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            routeReason(chain.request().url.toString())?.let { throw IOException(it) }
            chain.proceed(chain.request())
        }
        .addNetworkInterceptor { chain ->
            val original = chain.request()
            val probe = original.tag(Probe::class.java) != null
            val identity = if (probe) null else expected[original.url.toString()]
            val start = rangeStart(original.header("Range"))
            if (!probe && identity == null) {
                // DownloadManager may restore a queued download before Room/source validation.
                // Even an initial (unranged) read must not accept unverified bytes.
                throw IOException("Source must be probed before transferring media.")
            }
            val request = original.newBuilder().header("Accept-Encoding", "identity")
                .apply { if (!probe && start != null && start > 0) header("If-Range", identity!!.etag) }
                .build()
            val allowed = routeReason(original.url.toString())
            if (allowed != null) throw IOException(allowed)
            val response = chain.proceed(request)
            // Non-redirect probe errors are classified by the probe itself. Error pages
            // commonly advertise text/html; that must not hide a real source 404.
            if (probe && response.code !in 200..399) return@addNetworkInterceptor response
            if (response.code in 300..399 || response.request.url != request.url ||
                response.header("Content-Type")?.startsWith("text/html", ignoreCase = true) == true ||
                response.header("Content-Encoding")?.let { !it.equals("identity", ignoreCase = true) } == true
            ) {
                response.close()
                throw IOException("Source redirected or returned an HTML login page.")
            }
            if (identity != null && (if (start != null && response.code == 206) {
                    !validResume(response.code, response.header("Content-Range"),
                        response.header("ETag"), start, identity) ||
                        !validPartialLength(response.header("Content-Range"), response.body?.contentLength())
                } else {
                    response.code != 200 || start != null && start > 0 ||
                        response.header("ETag") != identity.etag ||
                        response.body?.contentLength()?.let { it >= 0 && it != identity.size } == true
                })) {
                response.close()
                throw IOException("Server media changed or returned an invalid partial response.")
            }
            if (response.code !in listOf(200, 206)) {
                response.close()
                throw IOException("Server media request failed (${response.code}).")
            }
            // A response can remain open long after its headers passed the gate. Check
            // every upstream read, including reads after a default-route/VPN change.
            val body = response.body ?: throw IOException("Source returned no body.")
            val guarded = object : ResponseBody() {
                private val source = object : ForwardingSource(body.source()) {
                    private fun checkAccess() {
                        routeReason(original.url.toString())?.let { throw IOException(it) }
                        if (!probe && expected[original.url.toString()] != identity) {
                            throw IOException("Transfer ownership or source identity changed.")
                        }
                    }
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        checkAccess()
                        val count = super.read(sink, byteCount)
                        checkAccess()
                        return count
                    }
                }.buffer()
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }
            response.newBuilder().body(guarded).build()
        }
        .build()

    fun remember(url: String, identity: SourceIdentity) { expected[url] = identity }

    fun forget(url: String) { expected.remove(url) }

    /** A bounded one-byte probe must identify a stable representation before any cached resume. */
    fun probe(url: String): SourceIdentity {
        val request = Request.Builder().url(url).header("Range", "bytes=0-31")
            .header("Accept-Encoding", "identity").tag(Probe::class.java, Probe()).build()
        client.newCall(request).execute().use { response ->
            if (response.code !in listOf(200, 206)) throw SourceHttpException(response.code)
            val etag = response.header("ETag")
            if (etag.isNullOrBlank() || etag.startsWith("W/")) {
                throw IOException("Server source has no strong ETag; safe resume is unavailable.")
            }
            val size = if (response.code == 206) {
                val range = Regex("bytes 0-(\\d+)/(\\d+)").matchEntire(
                    response.header("Content-Range") ?: "")
                    ?: throw IOException("Server source returned an invalid byte range.")
                val last = range.groupValues[1].toLongOrNull()
                val total = range.groupValues[2].toLongOrNull()
                if (last == null || total == null || last >= total || last > 31 ||
                    response.body?.contentLength() != last + 1) {
                    throw IOException("Server source returned an inconsistent byte range.")
                }
                total
            } else {
                response.body?.contentLength() ?: -1
            }
            if (size <= 0) throw IOException("Server source has an unknown length.")
            val type = response.header("Content-Type")
            if (type?.startsWith("text/", ignoreCase = true) == true) {
                throw IOException("Source returned text rather than playable media.")
            }
            val signature = response.body?.source()?.let { source ->
                source.request(8)
                source.buffer.clone().readByteArray(minOf(source.buffer.size, 12))
            } ?: byteArrayOf()
            val mp4 = signature.size >= 8 && String(signature, 4, 4, Charsets.US_ASCII) == "ftyp"
            val ebml = signature.size >= 4 && signature.take(4) ==
                listOf(0x1A, 0x45, 0xDF, 0xA3).map(Int::toByte)
            if (!mp4 && !ebml) throw IOException("Source is not an MP4, MKV or WebM container.")
            return SourceIdentity(size, etag, type)
        }
    }

    companion object {
        private fun validPartialLength(range: String?, length: Long?): Boolean {
            val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(range ?: "") ?: return false
            val start = match.groupValues[1].toLongOrNull() ?: return false
            val end = match.groupValues[2].toLongOrNull() ?: return false
            return length == end - start + 1
        }

        internal fun rangeStart(range: String?): Long? {
            if (range == null) return null
            val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range)
                ?: throw IOException("Malformed media range request.")
            val start = match.groupValues[1].toLongOrNull()
                ?: throw IOException("Invalid range start.")
            val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.let {
                it.toLongOrNull() ?: throw IOException("Invalid range end.")
            }
            if (start < 0 || end != null && end < start) throw IOException("Invalid media range.")
            return start
        }

        internal fun validResume(code: Int, range: String?, etag: String?,
                                 start: Long, identity: SourceIdentity): Boolean {
            val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(range ?: "") ?: return false
            return code == 206 && etag == identity.etag &&
                match.groupValues[1].toLongOrNull() == start &&
                match.groupValues[2].toLongOrNull()?.let { it >= start && it < identity.size } == true &&
                match.groupValues[3].toLongOrNull() == identity.size
        }
    }
}

@OptIn(UnstableApi::class)
class MediaDownloads(private val app: SniptubeApplication) {
    private val databaseProvider = StandaloneDatabaseProvider(app)
    // filesDir survives ordinary cache cleanup; NoOpCacheEvictor prevents silent loss.
    val cache = SimpleCache(app.filesDir.resolve("offline-media"), NoOpCacheEvictor(), databaseProvider)
    private val transport = SourceTransport { url -> app.container.deviceTransfers.routeReason(url) }
    val manager = DownloadManager(
        app, databaseProvider, cache, OkHttpDataSource.Factory(transport.client),
        Executors.newFixedThreadPool(2),
    ).apply {
        maxParallelDownloads = 2
        // The literal transport gate is authoritative; Media3's UNMETERED would reject
        // metered Wi-Fi, and NETWORK may reject a working LAN-only connection.
        requirements = Requirements(0)
    }

    fun complete(id: String, key: String): Download? {
        val download = manager.downloadIndex.getDownload(id) ?: return null
        if (download.state != Download.STATE_COMPLETED || download.contentLength <= 0 ||
            !cache.isCached(key, 0, download.contentLength)) return null
        return download
    }

    fun remember(url: String, identity: SourceIdentity) = transport.remember(url, identity)

    fun forget(url: String) = transport.forget(url)

    fun probe(url: String): SourceIdentity = transport.probe(url)

    companion object {
        internal fun rangeStart(range: String?): Long? = SourceTransport.rangeStart(range)
        internal fun validResume(code: Int, range: String?, etag: String?,
                                 start: Long, identity: SourceIdentity): Boolean =
            SourceTransport.validResume(code, range, etag, start, identity)
    }

    fun offlineDataSource(): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(null)
        .setCacheWriteDataSinkFactory(null)
}

@OptIn(UnstableApi::class)
class SniptubeDownloadService : DownloadService(
    1201, 1000L, "device_downloads", R.string.download_channel, 0,
) {
    override fun getDownloadManager(): DownloadManager =
        (application as SniptubeApplication).container.mediaDownloads.manager

    override fun getScheduler() = null // Reconciliation on foreground entry; no boot FGS launch.

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return DownloadNotificationHelper(this, "device_downloads")
            .buildProgressNotification(this, R.drawable.ic_sniptube, open, null,
                downloads, notMetRequirements)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as SniptubeApplication).container.deviceTransfers.onServiceTimeout()
        stopSelf(startId)
    }
}
