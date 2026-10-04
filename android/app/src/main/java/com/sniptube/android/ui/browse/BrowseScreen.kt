package com.sniptube.android.ui.browse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sniptube.android.data.library.*
import com.sniptube.android.data.local.*
import com.sniptube.android.ui.library.*

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(state: BrowseUiState, snapshot: LibrarySnapshot, actions: LibraryActions,
    commands: LibraryCommands, onQueryChange: (String) -> Unit, onLoadMore: () -> Unit,
    onRefreshLibrary: () -> Unit, onDownloads: () -> Unit, onRetrySearch: () -> Unit,
    scrollStore: LibraryScrollStore, serverIdentity: String,
    jobs: List<ServerJobBindingEntity>, transfers: List<DeviceTransferBindingEntity>) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val selection = rememberVideoSelection("$tab:${if (tab == 0) state.search.query else "library"}")
    val source = if (tab == 0) state.search.videos else state.library.videos
    val videos = remember(source) { source.map { it.offlineMetadata(System.currentTimeMillis()) }.distinctBy { it.key } }
    var adding by remember { mutableStateOf<List<OfflineVideoEntity>?>(null) }
    val queued = remember(snapshot.queue) { snapshot.queue.filter { it.deviceStage != DeviceStage.Removed }.map { VideoKey(it.serverIdentity, it.youtubeId) }.toSet() }
    val ready = remember(snapshot.queue, snapshot.assets) { snapshot.readyKeys() }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val listState = rememberLibraryListState(scrollStore,
        "browse:$serverIdentity:$tab:${if (tab == 0) state.search.query else "library"}", videos.size)
    val refreshing = if (tab == 0) state.search.loading else state.library.loading
    val refresh = { if (tab == 0) onRetrySearch() else onRefreshLibrary() }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("YouTube search") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Server library") })
        }
        // Keep focused input outside LazyColumn: IME bring-into-view must not scroll cards to the header.
        if (tab == 0) OutlinedTextField(state.search.query, onQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(16.dp), singleLine = true,
                    label = { Text("Search YouTube") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }))
        TextButton(onClick = refresh, enabled = !refreshing && (tab != 0 || state.search.query.isNotBlank()),
            modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp)) {
            Text(if (tab == 0) "Refresh results" else "Refresh server library")
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh, modifier = Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "browse-header") {
                val error = if (tab == 0) state.search.error else state.library.error
                error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                if (tab == 0 && error != null && videos.isEmpty()) TextButton(onClick = onRetrySearch,
                    enabled = !state.search.loading, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Retry search") }
                if (videos.isEmpty() && !(if (tab == 0) state.search.loading else state.library.loading)) LibraryEmpty(
                    if (tab == 0 && state.search.query.isBlank()) "Find videos for the trip" else "No videos to show",
                    if (tab == 0) "Search by title, topic or @channel. Downloads remain available even when the server is unreachable."
                    else "Refresh when your server is available, or use Downloads to watch phone copies.")
            }
            items(videos, key = { it.key.selectionId }) { video ->
                LibraryVideoRow(video, selection, status = when {
                    video.key in ready -> "Ready offline"
                    video.key in queued -> "In offline queue · see Downloads"
                    video.serverStatus == "ready" -> "Ready on server · not yet on phone"
                    video.serverStatus == "failed" -> "Server preparation failed · download offline to retry"
                    else -> null
                }, thumbnail = snapshot.thumbnailFor(video, video.key in ready),
                    progress = snapshot.queue.firstOrNull { it.serverIdentity == video.serverIdentity && it.youtubeId == video.youtubeId }
                        ?.let { intent -> syncProgress(intent, jobs.firstOrNull { it.serverIdentity == video.serverIdentity && it.youtubeId == video.youtubeId },
                            transfers.firstOrNull { it.serverIdentity == video.serverIdentity && it.youtubeId == video.youtubeId }) }) {
                    VideoActionIcon(Icons.Default.DownloadForOffline,
                        if (video.key in queued) "${video.title} is in Downloads" else "Download ${video.title} offline",
                        { commands.run { actions.enqueue(listOf(video)).summary } },
                        enabled = !commands.busy && video.key !in queued)
                    VideoActionIcon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add ${video.title} to a collection",
                        { adding = listOf(video) }, enabled = !commands.busy)
                }
            }
            if (tab == 0 && (state.search.hasMore || state.search.loadingMore)) item {
                OutlinedButton(onClick = { focus.clearFocus(); onLoadMore() }, enabled = !state.search.loadingMore,
                    modifier = Modifier.padding(16.dp).heightIn(min = 48.dp)) {
                    Text(if (state.search.loadingMore) "Loading…" else "Load more")
                }
            }
        }
        }
        if (!keyboardVisible) SelectionHeader(selection, videos, commands.busy) { selected ->
            Button(onClick = { commands.run { actions.enqueue(selected).summary } }) { Text("Download ${selected.size} offline") }
            OutlinedButton(onClick = { adding = selected }) { Text("Add to collection") }
        }
    }
    adding?.let { AddToCollectionDialog(snapshot, it, actions, commands) { adding = null } }
}
