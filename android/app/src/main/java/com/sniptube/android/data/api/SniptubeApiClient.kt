package com.sniptube.android.data.api

import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SniptubeApiClient(
    private val connection: ServerConnection,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val json: Json = defaultJson,
) {
    suspend fun search(
        query: String,
        maxResults: Int = 12,
        page: Int = 1,
        duration: SearchDuration = SearchDuration.Any,
        sort: SearchSort = SearchSort.Relevance,
    ): SearchResponse {
        require(query.isNotBlank() && query.length <= 200) { "Search query must contain 1 to 200 characters." }
        require(maxResults in 1..30) { "Search page size must be between 1 and 30." }
        require(page in 1..5) { "Search page must be between 1 and 5." }

        val url = connection.endpoint("search").newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("max_results", maxResults.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("duration", duration.apiValue)
            .addQueryParameter("sort_by", sort.apiValue)
            .build()
        return executeJson(Request.Builder().url(url).get().build(), SearchResponse.serializer())
    }

    suspend fun listVideos(): List<Video> = executeJson(
        Request.Builder().url(connection.endpoint("videos")).get().build(),
        ListSerializer(Video.serializer()),
    )

    suspend fun getVideo(videoId: String): Video = executeJson(
        Request.Builder().url(connection.endpoint("videos", videoId)).get().build(),
        Video.serializer(),
    )

    suspend fun createVideo(youtubeUrl: String): CreateVideoResponse {
        val body = json.encodeToString(CreateVideoRequest(youtubeUrl))
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(connection.endpoint("videos"))
            .post(body)
            .build()
        return executeJson(request, CreateVideoResponse.serializer())
    }

    suspend fun listActiveJobs(type: String? = null): List<Job> {
        val url = connection.endpoint("jobs", "active").newBuilder()
            .apply { type?.let { addQueryParameter("type", it) } }
            .build()
        return executeJson(
            Request.Builder().url(url).get().build(),
            ListSerializer(Job.serializer()),
        )
    }

    suspend fun getJob(jobId: String): Job = executeJson(
        Request.Builder().url(connection.endpoint("jobs", jobId)).get().build(),
        Job.serializer(),
    )

    fun sourceUrl(videoId: String): HttpUrl = connection.endpoint("videos", videoId, "source")

    suspend fun openSource(
        videoId: String,
        rangeStart: Long? = null,
        ifRange: String? = null,
    ): SourceResponse {
        require(rangeStart == null || rangeStart >= 0) { "Range start cannot be negative." }
        val request = Request.Builder()
            .url(sourceUrl(videoId))
            .apply {
                rangeStart?.let { header("Range", "bytes=$it-") }
                ifRange?.let { header("If-Range", it) }
            }
            .get()
            .build()
        val response = execute(request)
        if (!response.isSuccessful) {
            response.use { throw httpException(it) }
        }
        if (response.body == null) {
            response.close()
            throw InvalidServerResponseException()
        }
        return SourceResponse(response)
    }

    suspend fun listSubtitles(videoId: String): List<SubtitleTrack> = executeJson(
        Request.Builder().url(connection.endpoint("videos", videoId, "subtitles")).get().build(),
        ListSerializer(SubtitleTrack.serializer()),
    )

    fun subtitleUrl(track: SubtitleTrack): HttpUrl = connection.resolveServerUrl(track.url)

    private suspend fun <T> executeJson(
        request: Request,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val response = execute(request)
        response.use {
            if (!it.isSuccessful) throw httpException(it)
            val payload = it.body?.string() ?: throw InvalidServerResponseException()
            try {
                return json.decodeFromString(deserializer, payload)
            } catch (error: SerializationException) {
                throw InvalidServerResponseException(error)
            }
        }
    }

    private suspend fun execute(request: Request): Response = try {
        httpClient.newCall(request).await()
    } catch (error: SocketTimeoutException) {
        throw ServerTimeoutException(error)
    } catch (error: UnknownHostException) {
        throw ServerConnectionException(error)
    } catch (error: SSLException) {
        throw ServerConnectionException(error)
    } catch (error: IOException) {
        throw ServerConnectionException(error)
    }

    private fun httpException(response: Response): SniptubeApiException {
        val detail = response.body?.string()?.let { body ->
            try {
                val element = json.parseToJsonElement(body).jsonObject["detail"]
                (element as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.content
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return if (response.code == 401 || response.code == 403) {
            ServerAuthenticationException(response.code, detail)
        } else {
            ServerHttpException(response.code, detail)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}

class SourceResponse internal constructor(
    private val response: Response,
) : Closeable {
    val statusCode: Int = response.code
    val body: ResponseBody = checkNotNull(response.body)
    val contentLength: Long? = body.contentLength().takeIf { it >= 0 }
    val contentType: String? = body.contentType()?.toString()
    val etag: String? = response.header("ETag")
    val lastModified: String? = response.header("Last-Modified")
    val contentRange: String? = response.header("Content-Range")

    override fun close() = response.close()
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                response.close()
            }
        }
    })
}
