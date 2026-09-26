package com.sniptube.android.data.browse

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sniptube.android.data.api.SearchResponse
import com.sniptube.android.data.api.SearchResult
import com.sniptube.android.data.api.Video
import com.sniptube.android.data.local.DeviceStage
import com.sniptube.android.data.local.EnqueueResult
import com.sniptube.android.data.local.SniptubeDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class BrowseRepositoryTest {
    private lateinit var database: SniptubeDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SniptubeDatabase::class.java,
        ).build()
    }

    @After fun tearDown() = database.close()

    @Test fun `search cross references every server status and honors bounded page metadata`() = runTest {
        val remote = FakeRemote()
        val repo = BrowseRepository(SERVER, remote, database.offlineDao()) { 100 }
        remote.videos = listOf(video("alpha", "downloading"), video("beta", "ready"))
        remote.results = listOf(result("alpha"), result("beta"), result("gamma"))
        remote.hasMore = true

        val page = repo.search("@channel", 2)

        assertEquals(listOf("@channel" to 2), remote.searchRequests)
        assertEquals(2, page.page)
        assertEquals(true, page.hasMore)
        assertEquals(listOf("downloading", "ready", null), page.videos.map { it.serverStatus })
        assertEquals("server-alpha", page.videos[0].serverVideoId)
        assertEquals(BrowseSource.Search, page.videos[0].source)
        assertNull(page.videos[2].serverStatus)
        assertEquals(91_500L, page.videos[0].durationMs)
    }

    @Test fun `library enqueue persists only one server scoped intent and removed item can be requeued`() = runTest {
        val remote = FakeRemote()
        val dao = database.offlineDao()
        val repo = BrowseRepository(SERVER, remote, dao) { 100 }
        remote.videos = listOf(video("alpha", "failed"))
        val item = repo.library().single()

        assertEquals(BrowseSource.Library, item.source)
        assertEquals("failed", item.serverStatus)
        assertEquals(EnqueueResult.Inserted, repo.enqueue(item))
        assertEquals(EnqueueResult.Duplicate, repo.enqueue(item))
        assertEquals(1, dao.queueCount())
        assertEquals(setOf("alpha"), repo.observeQueuedYoutubeIds().first())
        assertEquals("server-alpha", dao.observeVideos().first().single().serverVideoId)

        dao.recordLocalRemoval(SERVER, "alpha", excludedAt = 200)
        assertEquals(emptySet<String>(), repo.observeQueuedYoutubeIds().first())
        assertEquals(EnqueueResult.Restored, repo.enqueue(item))
        assertEquals(DeviceStage.Pending, dao.getIntent(SERVER, "alpha")?.deviceStage)
        assertEquals(setOf("alpha"), repo.observeQueuedYoutubeIds().first())
        assertEquals(emptySet<String>(), BrowseRepository(
            "https://another.example/", remote, dao,
        ).observeQueuedYoutubeIds().first())
    }

    private class FakeRemote : BrowseRemoteDataSource {
        var videos = emptyList<Video>()
        var results = emptyList<SearchResult>()
        var hasMore = false
        val searchRequests = mutableListOf<Pair<String, Int>>()

        override suspend fun search(query: String, page: Int): SearchResponse {
            searchRequests.add(query to page)
            return SearchResponse(query = query, results = results, page = page, hasMore = hasMore)
        }

        override suspend fun library(): List<Video> = videos
    }

    private companion object {
        const val SERVER = "https://server.example/"

        fun result(id: String) = SearchResult(
            youtubeId = id, url = "https://www.youtube.com/watch?v=$id",
            title = "Title $id", duration = 91.5, alreadyDownloaded = true,
        )

        fun video(id: String, status: String) = Video(
            id = "server-$id", youtubeId = id,
            url = "https://www.youtube.com/watch?v=$id", title = "Title $id",
            duration = 91.5, status = status, createdAt = "2026-01-01T00:00:00Z",
        )
    }
}
