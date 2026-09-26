package com.sniptube.android.data.api

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SniptubeApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SniptubeApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = SniptubeApiClient(ServerConnection.parse(server.url("api/").toString()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search encodes all contract parameters and parses nullable metadata`() = runBlocking {
        server.enqueue(jsonResponse(SEARCH_RESPONSE))

        val response = api.search(
            query = "cats & dogs/@home",
            maxResults = 24,
            page = 2,
            duration = SearchDuration.Medium,
            sort = SearchSort.Views,
        )

        val request = server.takeRequest()
        assertEquals("/api/search", request.requestUrl?.encodedPath)
        assertEquals("cats & dogs/@home", request.requestUrl?.queryParameter("q"))
        assertEquals("24", request.requestUrl?.queryParameter("max_results"))
        assertEquals("2", request.requestUrl?.queryParameter("page"))
        assertEquals("medium", request.requestUrl?.queryParameter("duration"))
        assertEquals("views", request.requestUrl?.queryParameter("sort_by"))
        assertEquals("abc123", response.results.single().youtubeId)
        assertNull(response.results.single().duration)
        assertTrue(response.results.single().alreadyDownloaded)
        assertTrue(response.hasMore)
    }

    @Test
    fun `library and detail preserve unknown sizes and actual server status`() = runBlocking {
        server.enqueue(jsonResponse("[$VIDEO_RESPONSE]"))
        server.enqueue(jsonResponse(VIDEO_RESPONSE.replace("\"downloading\"", "\"ready\"")))

        val library = api.listVideos()
        val detail = api.getVideo("abc/with slash")

        assertEquals("downloading", library.single().status)
        assertNull(library.single().fileSize)
        assertEquals(listOf(1080, 720), library.single().availableHeights)
        assertEquals("ready", detail.status)
        assertEquals("/api/videos", server.takeRequest().path)
        assertEquals("/api/videos/abc%2Fwith%20slash", server.takeRequest().path)
    }

    @Test
    fun `video acquisition sends only the youtube URL and accepts ready fast path`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"video_id":"abc123def456","job_id":"","status":"already_exists"}""",
            ),
        )

        val response = api.createVideo("https://youtu.be/dQw4w9WgXcQ")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/videos", request.path)
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("{\"url\":\"https://youtu.be/dQw4w9WgXcQ\"}", request.body.readUtf8())
        assertEquals("already_exists", response.status)
        assertEquals("", response.jobId)
    }

    @Test
    fun `active and individual job contracts retain stage-specific fields`() = runBlocking {
        server.enqueue(jsonResponse("[$JOB_RESPONSE]"))
        server.enqueue(jsonResponse(JOB_RESPONSE.replace("\"running\"", "\"failed\"").replace("\"error\":null", "\"error\":\"quota\"")))

        val active = api.listActiveJobs("download")
        val failed = api.getJob("job/id")

        assertEquals("/api/jobs/active?type=download", server.takeRequest().path)
        assertEquals("running", active.single().status)
        assertEquals(37, active.single().progress)
        assertEquals("/api/jobs/job%2Fid", server.takeRequest().path)
        assertEquals("failed", failed.status)
        assertEquals("quota", failed.error)
    }

    @Test
    fun `source request supports resume validators and exposes response metadata`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/webm")
                .setHeader("Content-Range", "bytes 128-131/132")
                .setHeader("ETag", "\"media-v1\"")
                .setBody("data"),
        )

        api.openSource("abc123def456", rangeStart = 128, ifRange = "\"media-v1\"").use { source ->
            assertEquals(206, source.statusCode)
            assertEquals(4L, source.contentLength)
            assertEquals("video/webm", source.contentType)
            assertEquals("bytes 128-131/132", source.contentRange)
            assertEquals("\"media-v1\"", source.etag)
            assertEquals("data", source.body.string())
        }

        val request = server.takeRequest()
        assertEquals("/api/videos/abc123def456/source", request.path)
        assertEquals("bytes=128-", request.getHeader("Range"))
        assertEquals("\"media-v1\"", request.getHeader("If-Range"))
    }

    @Test
    fun `subtitle contract resolves only relative same-server files`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """[{"language":"en","url":"/files/videos/abc/subs/en.vtt"}]""",
            ),
        )

        val track = api.listSubtitles("abc").single()

        assertEquals("/api/videos/abc/subtitles", server.takeRequest().path)
        assertEquals(server.url("files/videos/abc/subs/en.vtt"), api.subtitleUrl(track))
        assertThrows(InvalidServerUrlException::class.java) {
            api.subtitleUrl(SubtitleTrack("en", "https://other.test/sub.vtt"))
        }
        Unit
    }

    @Test
    fun `auth and ordinary HTTP errors expose actionable typed failures`() {
        server.enqueue(jsonResponse("""{"detail":"Cloud access login required"}""", 403))
        server.enqueue(jsonResponse("""{"detail":"Video not found"}""", 404))

        val auth = assertThrows(ServerAuthenticationException::class.java) {
            runBlocking { api.listVideos() }
        }
        val missing = assertThrows(ServerHttpException::class.java) {
            runBlocking { api.getVideo("missing") }
        }

        assertEquals(403, auth.statusCode)
        assertEquals("Cloud access login required", auth.userMessage)
        assertEquals(404, missing.statusCode)
        assertEquals("Video not found", missing.userMessage)
    }

    @Test
    fun `timeout and malformed JSON are distinct actionable failures`() {
        val shortTimeoutClient = OkHttpClient.Builder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .callTimeout(200, TimeUnit.MILLISECONDS)
            .build()
        val shortTimeoutApi = SniptubeApiClient(
            ServerConnection.parse(server.url("/").toString()),
            shortTimeoutClient,
        )
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertThrows(ServerTimeoutException::class.java) {
            runBlocking { shortTimeoutApi.listVideos() }
        }

        server.enqueue(jsonResponse("not-json"))
        assertThrows(InvalidServerResponseException::class.java) {
            runBlocking { api.listVideos() }
        }
    }

    @Test
    fun `search enforces the documented bounded pagination contract locally`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { api.search("cats", page = 6) }
        }
        assertFalse(server.requestCount > 0)
    }

    private fun jsonResponse(body: String, status: Int = 200): MockResponse = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        val SEARCH_RESPONSE = """
            {
              "query":"cats & dogs/@home",
              "results":[{
                "youtube_id":"abc123",
                "url":"https://youtu.be/abc123",
                "title":"Result",
                "duration":null,
                "thumbnail_url":null,
                "uploader":"Channel",
                "uploader_id":"@channel",
                "view_count":12345678901,
                "upload_date":"20260920",
                "description":null,
                "already_downloaded":true,
                "video_id":"abc123def456",
                "future_field":"ignored"
              }],
              "total_fetched":48,
              "filters_applied":{"duration":"medium","sort_by":"views"},
              "page":2,
              "page_size":24,
              "has_more":true
            }
        """.trimIndent()

        val VIDEO_RESPONSE = """
            {
              "id":"abc123def456",
              "youtube_id":"abc123",
              "url":"https://youtu.be/abc123",
              "title":null,
              "duration":null,
              "language":null,
              "thumbnail_url":null,
              "subtitles":[],
              "protected":false,
              "status":"downloading",
              "created_at":"2026-09-20T01:02:03+00:00",
              "file_size":null,
              "derivatives_count":0,
              "derivatives_total_size":0,
              "available_heights":[1080,720],
              "source_height":null
            }
        """.trimIndent()

        val JOB_RESPONSE = """
            {
              "id":"job-1",
              "video_id":"abc123def456",
              "type":"download",
              "params":{"url":"https://youtu.be/abc123"},
              "status":"running",
              "progress":37,
              "result_url":null,
              "error":null,
              "created_at":"2026-09-20T01:02:03+00:00",
              "updated_at":"2026-09-20T01:03:03+00:00"
            }
        """.trimIndent()
    }
}
