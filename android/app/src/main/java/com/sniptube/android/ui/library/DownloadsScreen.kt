package com.sniptube.android.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.sniptube.android.data.library.*
import com.sniptube.android.data.local.*

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(snapshot: LibrarySnapshot, jobs: List<ServerJobBindingEntity>,
    transfers: List<DeviceTransferBindingEntity>, actions: LibraryActions, commands: LibraryCommands,
    onPlay: (VideoKey) -> Unit, scrollStore: LibraryScrollStore,
    refreshing: Boolean = false, onRefresh: () -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val selection = rememberVideoSelection("$filter:$query")
    var removing by remember { mutableStateOf<List<OfflineVideoEntity>?>(null) }
    var adding by remember { mutableStateOf<List<OfflineVideoEntity>?>(null) }
    val ready = remember(snapshot.queue, snapshot.assets) { snapshot.readyKeys() }
    val intents = remember(snapshot.queue) { snapshot.queue.associateBy { VideoKey(it.serverIdentity, it.youtubeId) } }
    val jobMap = remember(jobs) { jobs.associateBy { VideoKey(it.serverIdentity, it.youtubeId) } }
    val transferMap = remember(transfers) { transfers.associateBy { VideoKey(it.serverIdentity, it.youtubeId) } }
    val playback = remember(snapshot.playback) { snapshot.playback.associateBy { VideoKey(it.serverIdentity, it.youtubeId) } }
    val collectionMembers = remember(snapshot.memberships) {
        snapshot.memberships.map { VideoKey(it.serverIdentity, it.youtubeId) }.toSet()
    }
    val all = snapshot.videos.filter { intents[it.key]?.deviceStage?.let { stage -> stage != DeviceStage.Removed } == true }
    val visible = all.filter { video ->
        (video.title.contains(query, true) || video.uploader.orEmpty().contains(query, true)) &&
            when (filter) { "Ready" -> video.key in ready; "Queue" -> video.key !in ready; else -> true }
    }
    val continued = all.filter { it.key in ready && (playback[it.key]?.positionMs ?: 0) > 0 &&
        playback[it.key]?.let { progress -> progress.durationMs == null || progress.positionMs < progress.durationMs - 1000 } == true }
        .sortedByDescending { playback[it.key]?.updatedAt }.take(3)
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val listState = rememberLibraryListState(scrollStore, "downloads:$filter:$query", visible.size)
    Column(Modifier.fillMaxSize()) {
    OutlinedTextField(query, { query = it }, label = { Text("Search downloads") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "downloads-header") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!keyboardVisible) Text("Ready for the journey", style = MaterialTheme.typography.headlineMedium)
                if (!keyboardVisible) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${ready.size} ready offline", style = MaterialTheme.typography.headlineSmall)
                        Text("${all.count { it.key !in ready }} syncing · ${byteText(snapshot.assets.sumOf { it.byteSize })} on this phone",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("${durationText(all.filter { it.key in ready }.sumOf { it.durationMs ?: 0 })} known viewing time",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                if (!keyboardVisible) SyncOverviewBanner(snapshot, jobs, transfers)
                if (!keyboardVisible) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Ready", "Queue").forEach { value ->
                        FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            if (!keyboardVisible && !selection.active && query.isBlank() && filter != "Queue" && continued.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Continue watching", style = MaterialTheme.typography.titleMedium)
                    continued.forEach { video ->
                        TextButton(onClick = { onPlay(video.key) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("${video.title} · resume at ${durationText(playback.getValue(video.key).positionMs)}")
                        }
                    }
                }
            }
            if (visible.isEmpty()) LibraryEmpty(if (all.isEmpty()) "Your travel library starts here" else "No matching downloads",
                if (all.isEmpty()) "Use Browse to download videos to this phone. Completed copies play here without a server connection." else "Try another search or filter.")
        }
        items(visible, key = { it.key.selectionId }) { video ->
            val intent = intents[video.key]
            val isReady = video.key in ready
            LibraryVideoRow(video, selection, queueLabel(intent, isReady, jobMap[video.key], transferMap[video.key]),
                thumbnail = snapshot.thumbnailFor(video, isReady),
                onOpen = if (isReady) ({ onPlay(video.key) }) else null,
                progress = intent?.let { syncProgress(it, jobMap[video.key], transferMap[video.key]) }) {
                if (isReady) VideoActionIcon(Icons.Default.PlayCircle, "Play ${video.title} offline", { onPlay(video.key) })
                else if (intent != null) {
                    when {
                        intent.userPaused || intent.deviceStage == DeviceStage.Paused -> VideoActionIcon(Icons.Default.PlayArrow,
                            "Resume syncing ${video.title}", { commands.run { actions.resume(listOf(video.key)).summary } }, enabled = !commands.busy)
                        intent.serverStage == ServerStage.Failed || intent.deviceStage == DeviceStage.Failed -> VideoActionIcon(Icons.Default.Refresh,
                            "Retry syncing ${video.title}", { commands.run { actions.retry(listOf(video.key)).summary } }, enabled = !commands.busy)
                        intent.deviceStage != DeviceStage.Ready -> VideoActionIcon(Icons.Default.PauseCircleOutline,
                            "Pause syncing ${video.title}", { commands.run { actions.pause(listOf(video.key)).summary } }, enabled = !commands.busy)
                    }
                }
                if (video.key in collectionMembers) Text("In a collection · remove its membership before deleting the phone copy",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else VideoActionIcon(Icons.Default.DeleteOutline, "Remove ${video.title} from this phone",
                    { removing = listOf(video) }, enabled = !commands.busy, destructive = true)
                intent?.errorMessage?.let { Text(it, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    }
        if (!keyboardVisible) SelectionHeader(selection, visible, commands.busy) { selected ->
            val keys = selected.map { it.key }
            val selectedIntents = keys.mapNotNull(intents::get)
            if (selectedIntents.any { !it.userPaused && it.deviceStage !in listOf(DeviceStage.Ready, DeviceStage.Removed) })
                FilledTonalButton(onClick = { commands.run { actions.pause(keys).summary } }) { Text("Pause") }
            if (selectedIntents.any { it.userPaused || it.deviceStage == DeviceStage.Paused })
                FilledTonalButton(onClick = { commands.run { actions.resume(keys).summary } }) { Text("Resume") }
            if (selectedIntents.any { it.serverStage == ServerStage.Failed || it.deviceStage == DeviceStage.Failed })
                FilledTonalButton(onClick = { commands.run { actions.retry(keys).summary } }) { Text("Retry") }
            OutlinedButton(onClick = { adding = selected }) { Text("Add to collection") }
            if (keys.none { it in collectionMembers }) TextButton(onClick = { removing = selected }) { Text("Remove from phone") }
            else Text("Remove collection membership first (${keys.count { it in collectionMembers }})",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    adding?.let { AddToCollectionDialog(snapshot, it, actions, commands) { adding = null } }
    removing?.let { videos ->
        val keys = videos.map { it.key }.toSet()
        val stored = snapshot.assets.filter { VideoKey(it.serverIdentity, it.youtubeId) in keys }.sumOf { it.byteSize }
        val partial = transfers.filter { VideoKey(it.serverIdentity, it.youtubeId) in keys && VideoKey(it.serverIdentity, it.youtubeId) !in ready }.sumOf { it.bytesDownloaded }
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${videos.size} from this phone?") },
            text = { Text("${byteText(stored)} of recorded local files will be freed${if (partial > 0) ", plus up to ${byteText(partial)} of partial downloads" else ""}. Server copies and collection membership stay. Automatic keep-offline syncing is suppressed for these videos until you explicitly download them again.") },
            confirmButton = { TextButton(onClick = { removing = null; commands.run { val result = actions.removeLocal(keys.toList()); selection.clear(); result.summary } }, enabled = !commands.busy) { Text("Remove from phone") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}
