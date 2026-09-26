package com.sniptube.android.ui.browse

import com.sniptube.android.data.browse.BrowseOperations
import com.sniptube.android.data.browse.BrowsePage
import com.sniptube.android.data.browse.BrowseSource
import com.sniptube.android.data.browse.BrowseVideo
import com.sniptube.android.data.local.EnqueueResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {
    @Test fun `changing query cancels running search and discards stale results`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = FakeBrowse()
            val slowStarted = CompletableDeferred<Unit>()
            val slowCancelled = CompletableDeferred<Unit>()
            fake.searchResult = { query, _ ->
                if (query == "old") {
                    slowStarted.complete(Unit)
                    try { awaitCancellation() } finally { slowCancelled.complete(Unit) }
                }
                BrowsePage(listOf(item("new")), 1, false)
            }
            val model = BrowseViewModel(fake)
            runCurrent()
            model.updateQuery("old")
            advanceTimeBy(350)
            runCurrent()
            assertTrue(slowStarted.isCompleted)

            model.updateQuery("new")
            runCurrent()
            assertTrue(slowCancelled.isCompleted)
            assertTrue(model.state.value.search.videos.isEmpty())
            advanceTimeBy(350)
            runCurrent()
            assertEquals(listOf("new"), model.state.value.search.videos.map { it.youtubeId })
            assertFalse(model.state.value.search.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `a changed query cancels the old page`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = FakeBrowse()
            val pageCancelled = CompletableDeferred<Unit>()
            fake.searchResult = { query, page ->
                when {
                    query == "first" && page == 1 -> BrowsePage(listOf(item("a")), 1, true)
                    query == "first" -> {
                        try { awaitCancellation() } finally { pageCancelled.complete(Unit) }
                    }
                    else -> BrowsePage(listOf(item("b")), 1, false)
                }
            }
            val model = BrowseViewModel(fake)
            runCurrent()
            model.updateQuery("first")
            advanceTimeBy(350)
            runCurrent()
            model.loadNextPage()
            model.loadNextPage()
            runCurrent()
            model.updateQuery("second")
            runCurrent()
            assertTrue(pageCancelled.isCompleted)
            advanceTimeBy(350)
            runCurrent()
            assertEquals(listOf("b"), model.state.value.search.videos.map { it.youtubeId })
            assertFalse(model.state.value.search.loadingMore)
            assertEquals(listOf("first" to 1, "first" to 2, "second" to 1), fake.requests)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `load more appends distinct IDs and stops at the server page boundary`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = FakeBrowse()
            fake.searchResult = { _, page ->
                if (page == 1) BrowsePage(listOf(item("a")), 1, true)
                else BrowsePage(listOf(item("a"), item("b")), 2, false)
            }
            val model = BrowseViewModel(fake)
            runCurrent()
            model.updateQuery("travel")
            advanceTimeBy(350)
            runCurrent()
            model.loadNextPage()
            runCurrent()
            model.loadNextPage()
            runCurrent()
            assertEquals(listOf("a", "b"), model.state.value.search.videos.map { it.youtubeId })
            assertFalse(model.state.value.search.hasMore)
            assertEquals(listOf("travel" to 1, "travel" to 2), fake.requests)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `enqueue leaves browsing available and never repeats on a state read`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = FakeBrowse()
            val model = BrowseViewModel(fake)
            runCurrent()
            model.enqueue(item("a"))
            runCurrent()
            assertEquals(1, fake.enqueued.size)
            assertEquals(setOf("a"), model.state.value.queuedYoutubeIds)
            assertTrue(model.state.value.notice!!.contains("offline queue"))
            model.state.value
            assertEquals(1, fake.enqueued.size)
            model.updateQuery("more")
            advanceTimeBy(350)
            runCurrent()
            assertEquals(listOf("more" to 1), fake.requests)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `retry recovers same query without editing text and duplicate taps do not reissue`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = FakeBrowse()
            fake.searchResult = { _, _ -> throw java.io.IOException("Server unavailable") }
            val model = BrowseViewModel(fake)
            runCurrent()
            model.updateQuery("travel")
            assertTrue(model.state.value.search.loading)
            advanceTimeBy(350)
            runCurrent()
            assertTrue(model.state.value.search.error != null)
            fake.searchResult = { _, _ -> BrowsePage(listOf(item("recovered")), 1, false) }
            model.retrySearch()
            model.retrySearch()
            runCurrent()
            assertEquals(listOf("travel" to 1, "travel" to 1), fake.requests)
            assertEquals("recovered", model.state.value.search.videos.single().youtubeId)
            assertEquals(null, model.state.value.search.error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeBrowse : BrowseOperations {
        val queued = MutableStateFlow(emptySet<String>())
        val enqueued = mutableListOf<BrowseVideo>()
        val requests = mutableListOf<Pair<String, Int>>()
        var searchResult: suspend (String, Int) -> BrowsePage = { _, page ->
            BrowsePage(emptyList(), page, false)
        }

        override suspend fun search(query: String, page: Int): BrowsePage {
            requests.add(query to page)
            return searchResult(query, page)
        }

        override suspend fun library(): List<BrowseVideo> = emptyList()

        override suspend fun enqueue(video: BrowseVideo): EnqueueResult {
            enqueued.add(video)
            queued.value = queued.value + video.youtubeId
            return EnqueueResult.Inserted
        }

        override fun observeQueuedYoutubeIds(): Flow<Set<String>> = queued
    }

    private companion object {
        fun item(id: String) = BrowseVideo(
            serverIdentity = "https://server.example/", youtubeId = id,
            sourceUrl = "https://www.youtube.com/watch?v=$id", title = "Title $id",
            durationMs = null, thumbnailUrl = null, uploader = null,
            serverVideoId = null, serverStatus = null, serverFileSizeBytes = null,
            source = BrowseSource.Search,
        )
    }
}
