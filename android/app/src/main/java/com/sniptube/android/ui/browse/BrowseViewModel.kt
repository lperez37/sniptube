package com.sniptube.android.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sniptube.android.data.api.SniptubeApiException
import com.sniptube.android.data.browse.BrowseOperations
import com.sniptube.android.data.browse.BrowseVideo
import com.sniptube.android.data.local.EnqueueResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val videos: List<BrowseVideo> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val page: Int = 0,
    val error: String? = null,
)

data class LibraryUiState(
    val videos: List<BrowseVideo> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

data class BrowseUiState(
    val search: SearchUiState = SearchUiState(),
    val library: LibraryUiState = LibraryUiState(),
    val queuedYoutubeIds: Set<String> = emptySet(),
    val notice: String? = null,
)

class BrowseViewModel(
    private val repository: BrowseOperations,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()
    private var nextPageJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeQueuedYoutubeIds().collectLatest { queuedIds ->
                _state.value = _state.value.copy(queuedYoutubeIds = queuedIds)
            }
        }
        viewModelScope.launch {
            // collectLatest cancels the HTTP call immediately when the user types again,
            // including during the debounce window.
            query.collectLatest { value ->
                delay(350)
                loadFirstPage(value)
            }
        }
        refreshLibrary()
    }

    fun updateQuery(value: String) {
        val bounded = value.take(200)
        if (bounded == query.value) return
        nextPageJob?.cancel()
        query.value = bounded
        _state.value = _state.value.copy(
            search = SearchUiState(query = bounded, loading = bounded.isNotBlank()),
        )
    }

    fun retrySearch() {
        val value = query.value
        if (value.isBlank() || _state.value.search.loading || nextPageJob?.isActive == true) return
        nextPageJob?.cancel()
        nextPageJob = viewModelScope.launch { loadFirstPage(value) }
    }

    fun loadNextPage() {
        val search = _state.value.search
        if (search.loading || search.loadingMore || nextPageJob?.isActive == true ||
            !search.hasMore || search.query.isBlank()
        ) return
        val expectedQuery = search.query
        nextPageJob = viewModelScope.launch {
            _state.value = _state.value.copy(search = search.copy(loadingMore = true, error = null))
            try {
                val page = repository.search(expectedQuery, search.page + 1)
                if (query.value != expectedQuery) return@launch
                val current = _state.value.search
                _state.value = _state.value.copy(
                    search = current.copy(
                        videos = (current.videos + page.videos).distinctBy(BrowseVideo::youtubeId),
                        loadingMore = false,
                        hasMore = page.hasMore,
                        page = page.page,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (query.value != expectedQuery) return@launch
                val current = _state.value.search
                _state.value = _state.value.copy(
                    search = current.copy(loadingMore = false, error = error.userMessage()),
                )
            }
        }
    }

    private var libraryJob: Job? = null

    fun refreshLibrary() {
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            val previous = _state.value.library
            _state.value = _state.value.copy(
                library = previous.copy(loading = true, error = null),
            )
            try {
                _state.value = _state.value.copy(
                    library = LibraryUiState(videos = repository.library(), loading = false),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    library = previous.copy(loading = false, error = error.userMessage()),
                )
            }
        }
    }

    fun enqueue(video: BrowseVideo) {
        if (video.youtubeId in _state.value.queuedYoutubeIds) return
        viewModelScope.launch {
            val message = try {
                when (repository.enqueue(video)) {
                    EnqueueResult.Inserted -> "${video.title} saved in your offline queue."
                    EnqueueResult.Restored -> "${video.title} added back to your offline queue."
                    EnqueueResult.Duplicate -> "${video.title} is already queued."
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error.userMessage()
            }
            _state.value = _state.value.copy(notice = message)
        }
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    private suspend fun loadFirstPage(value: String) {
        if (value.isBlank()) return
        _state.value = _state.value.copy(
            search = SearchUiState(query = value, loading = true),
        )
        try {
            val page = repository.search(value, 1)
            if (query.value != value) return
            _state.value = _state.value.copy(
                search = SearchUiState(
                    query = value,
                    videos = page.videos,
                    hasMore = page.hasMore,
                    page = page.page,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (query.value != value) return
            _state.value = _state.value.copy(
                search = SearchUiState(query = value, error = error.userMessage()),
            )
        }
    }

    companion object {
        fun factory(repository: BrowseOperations): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BrowseViewModel(repository) as T
            }
    }
}

private fun Throwable.userMessage(): String = when (this) {
    is SniptubeApiException -> userMessage
    else -> message?.takeIf(String::isNotBlank) ?: "Something went wrong. Try again."
}
