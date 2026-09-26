package com.sniptube.android.data.playback

import android.content.Context
import android.graphics.BitmapFactory
import com.sniptube.android.data.api.ServerConnection
import com.sniptube.android.data.api.SniptubeApiClient
import com.sniptube.android.data.api.SubtitleTrack
import com.sniptube.android.data.local.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/** Optional pre-travel enrichment; never called by the player and never changes media readiness. */
class OfflineExtras(
    context: Context,
    private val dao: OfflineDao,
    private val apiClient: (ServerConnection) -> SniptubeApiClient,
    private val wifiReason: () -> String? = { "Wi-Fi eligibility was not configured." },
) {
    private val root = File(context.applicationContext.filesDir, "offline-extras")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.request().tag(Job::class.java)?.ensureActive()
            checkWifi()
            chain.proceed(chain.request())
        }
        .addNetworkInterceptor { chain ->
            val job = chain.request().tag(Job::class.java)
            job?.ensureActive()
            checkWifi()
            val response = chain.proceed(chain.request())
            if (response.code in 300..399 || response.request.url != chain.request().url) {
                response.close()
                throw IOException("Optional asset redirected")
            }
            val body = response.body ?: return@addNetworkInterceptor response
            val guarded = object : ResponseBody() {
                private val source = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        job?.ensureActive()
                        checkWifi()
                        val count = super.read(sink, byteCount)
                        job?.ensureActive()
                        checkWifi()
                        return count
                    }
                }.buffer()
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }
            response.newBuilder().body(guarded).build()
        }.build()

    private fun checkWifi() { wifiReason()?.let { throw IOException(it) } }

    private suspend fun <T> request(url: HttpUrl, block: (Response) -> T): T {
        currentCoroutineContext().ensureActive()
        checkWifi()
        val job = currentCoroutineContext()[Job]
        val call: Call = http.newCall(Request.Builder().url(url).tag(Job::class.java, job).build())
        val cancellation = job?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            return call.execute().use { response -> block(response) }
        } finally {
            cancellation?.dispose()
            currentCoroutineContext().ensureActive()
        }
    }

    suspend fun sync(serverIdentity: String, youtubeId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            optional {
                checkWifi() // The route may have changed while another sync held the lock.
                withTimeoutOrNull(90_000) {
                    val intent = dao.getIntent(serverIdentity, youtubeId) ?: return@withTimeoutOrNull
                    if (intent.deviceStage != DeviceStage.Ready) return@withTimeoutOrNull
                    val video = dao.getVideo(serverIdentity, youtubeId) ?: return@withTimeoutOrNull
                    val connection = ServerConnection.parse(serverIdentity)
                    val client = apiClient(connection)
                    val supplied = video.thumbnailUrl?.toHttpUrlOrNull()
                        ?.takeIf { trustedThumbnail(it, connection.baseUrl) }
                    val fallback = canonicalYoutubeThumbnail(video.youtubeId)?.toHttpUrlOrNull()
                    for (url in listOfNotNull(supplied, fallback).distinct()) {
                        optional { fetch(intent, LocalFileKind.Thumbnail, "", null, url, null, 4L * 1024 * 1024) }
                        if (dao.getLocalFiles(serverIdentity, youtubeId).any { file ->
                                file.kind == LocalFileKind.Thumbnail && file.generation == intent.generation &&
                                    File(file.path).isFile
                            }) break
                    }
                    optional {
                        video.serverVideoId?.let { id ->
                            // Discovery uses the same gated, bounded transport as the assets.
                            val tracks = request(connection.endpoint("videos", id, "subtitles")) { response ->
                                if (!response.isSuccessful) throw IOException("Subtitles unavailable")
                                val body = response.body ?: throw IOException("Empty subtitle listing")
                                val output = java.io.ByteArrayOutputStream()
                                body.byteStream().use { copyBounded(it, output, 256L * 1024) }
                                json.decodeFromString(
                                    ListSerializer(SubtitleTrack.serializer()), output.toString(Charsets.UTF_8.name()))
                            }
                            tracks.take(8).forEach { track -> optional {
                                fetch(intent, LocalFileKind.Subtitle, track.language, track.language,
                                    client.subtitleUrl(track), "text/vtt", 2L * 1024 * 1024)
                            } }
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetch(intent: QueueIntentEntity, kind: LocalFileKind, key: String,
                               language: String?, url: HttpUrl, type: String?, limit: Long) {
        checkWifi()
        val existing = dao.getLocalFiles(intent.serverIdentity, intent.youtubeId).firstOrNull {
            it.kind == kind && it.trackKey == key && it.generation == intent.generation
        }
        if (existing != null && File(existing.path).let { it.isFile && it.length() == existing.byteSize }) return
        val target = extrasPath(root, intent.serverIdentity, intent.youtubeId, intent.generation, kind, key)
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        // Sibling staging + rename means Room never sees a partial file. Generation separates late writers.
        val temporary = File.createTempFile("extra-", ".part", target.parentFile)
        var published = false
        try {
            var contentType = type
            request(url) { response ->
                if (!response.isSuccessful) throw IOException("Optional asset unavailable")
                val body = response.body ?: throw IOException("Empty optional asset")
                if (body.contentLength() > limit) throw IOException("Optional asset too large")
                val receivedType = body.contentType()?.toString()
                if (kind == LocalFileKind.Thumbnail && receivedType?.startsWith("image/") != true) {
                    throw IOException("Invalid thumbnail")
                }
                if (kind == LocalFileKind.Thumbnail) contentType = receivedType
                body.byteStream().use { input -> temporary.outputStream().use { copyBounded(input, it, limit) } }
            }
            currentCoroutineContext().ensureActive()
            checkWifi()
            if (kind == LocalFileKind.Subtitle && !temporary.bufferedReader().use {
                    it.readLine()?.removePrefix("\uFEFF")?.startsWith("WEBVTT") == true
                }) throw IOException("Invalid subtitle")
            if (kind == LocalFileKind.Thumbnail) {
                val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temporary.path, dimensions)
                if (dimensions.outWidth !in 1..4096 || dimensions.outHeight !in 1..4096 ||
                    dimensions.outWidth.toLong() * dimensions.outHeight > 16_000_000L) {
                    throw IOException("Invalid thumbnail dimensions")
                }
            }
            val current = dao.getIntent(intent.serverIdentity, intent.youtubeId)
            if (current?.generation != intent.generation || current.deviceStage != DeviceStage.Ready) return
            currentCoroutineContext().ensureActive()
            checkWifi()
            if (!temporary.renameTo(target)) throw IOException("Cannot publish optional asset")
            published = dao.publishAuxiliaryFile(LocalFileEntity(
                intent.serverIdentity, intent.youtubeId, kind, key, target.absolutePath, target.length(),
                contentType = contentType, language = language, generation = intent.generation,
                validatedAt = System.currentTimeMillis(),
            ))
        } finally {
            temporary.delete()
            if (!published) target.delete()
        }
    }

    private suspend fun optional(block: suspend () -> Unit) {
        try { block() } catch (error: CancellationException) { throw error } catch (_: Exception) {
            // Metadata and media remain usable; a later explicit sync can retry enrichment.
        }
    }

    private companion object {
        // Serialize even if different completion callbacks construct separate helpers.
        val mutex = Mutex()
    }
}

/** Server-local thumbnails and the known YouTube thumbnail hosts only; never fetch arbitrary metadata URLs. */
internal fun trustedThumbnail(url: HttpUrl, server: HttpUrl): Boolean =
    url.username.isEmpty() && url.password.isEmpty() &&
        ((url.scheme == server.scheme && url.host == server.host && url.port == server.port) ||
            (url.scheme == "https" && url.port == 443 &&
                url.host in setOf("i.ytimg.com", "img.youtube.com")))

/** YouTube's public thumbnail endpoint, restricted to a validated 11-character video ID. */
fun canonicalYoutubeThumbnail(youtubeId: String): String? =
    youtubeId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

internal fun extrasPath(root: File, server: String, youtubeId: String, generation: Long,
                        kind: LocalFileKind, key: String): File {
    fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    require(generation >= 0 && kind != LocalFileKind.Media)
    return File(root, "${hash(server)}/${hash(youtubeId)}/$generation/${kind.name}-${hash(key)}" +
        if (kind == LocalFileKind.Subtitle) ".vtt" else ".image")
}

internal fun copyBounded(input: InputStream, output: OutputStream, limit: Long) {
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        total += count
        if (total > limit) throw IOException("Optional asset exceeds size limit")
        output.write(buffer, 0, count)
    }
    if (total == 0L) throw IOException("Empty optional asset")
}
