package com.sniptube.android.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.sniptube.android.data.library.*
import com.sniptube.android.data.local.*

internal fun LibrarySnapshot.unfinishedJobs() = queue.filter {
    it.deviceStage != DeviceStage.Removed && it.deviceStage != DeviceStage.Ready
}

@Composable
fun JobsIndicator(snapshot: LibrarySnapshot, onClick: () -> Unit) {
    val pending = snapshot.unfinishedJobs()
    if (pending.isEmpty()) return
    val running = pending.any { !it.userPaused && (it.serverStage == ServerStage.Running || it.deviceStage == DeviceStage.Running) }
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        BadgedBox(badge = { Badge { Text(pending.size.toString()) } }) {
            Box(Modifier.size(26.dp).clearAndSetSemantics {
                contentDescription = "${pending.size} unfinished jobs${if (running) ", syncing" else ""}. Show jobs"
            }, contentAlignment = Alignment.Center) {
                if (running) CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 2.dp)
                Icon(Icons.Default.Sync, null, Modifier.size(if (running) 16.dp else 24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(snapshot: LibrarySnapshot, jobs: List<ServerJobBindingEntity>,
    transfers: List<DeviceTransferBindingEntity>, actions: LibraryActions, commands: LibraryCommands,
    scrollStore: LibraryScrollStore, refreshing: Boolean, onRefresh: () -> Unit) {
    val pending = snapshot.unfinishedJobs()
    val videos = snapshot.videos.associateBy { it.key }
    val jobMap = jobs.associateBy { VideoKey(it.serverIdentity, it.youtubeId) }
    val transferMap = transfers.associateBy { VideoKey(it.serverIdentity, it.youtubeId) }
    val state = rememberLibraryListState(scrollStore, "jobs", pending.size)
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "jobs-header") {
                if (pending.isEmpty()) LibraryEmpty("All caught up", "Completed phone copies are in Downloads.")
                else Text("${pending.size} unfinished · server preparation and phone transfers",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(pending, key = { VideoKey(it.serverIdentity, it.youtubeId).selectionId }) { intent ->
                val key = VideoKey(intent.serverIdentity, intent.youtubeId)
                val title = videos[key]?.title ?: intent.youtubeId
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(queueLabel(intent, false, jobMap[key], transferMap[key]), style = MaterialTheme.typography.bodyMedium)
                        syncProgress(intent, jobMap[key], transferMap[key])?.let { SyncProgressIndicator(it) }
                        intent.errorMessage?.let { Text(it, color = if (intent.deviceStage == DeviceStage.Failed || intent.serverStage == ServerStage.Failed)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                        when {
                            intent.userPaused || intent.deviceStage == DeviceStage.Paused -> TextButton(onClick = {
                                commands.run { actions.resume(listOf(key)).summary }
                            }, enabled = !commands.busy) { Icon(Icons.Default.PlayArrow, null); Text("Resume") }
                            intent.serverStage == ServerStage.Failed || intent.deviceStage == DeviceStage.Failed -> TextButton(onClick = {
                                commands.run { actions.retry(listOf(key)).summary }
                            }, enabled = !commands.busy) { Icon(Icons.Default.Refresh, null); Text("Retry") }
                            else -> TextButton(onClick = {
                                commands.run { actions.pause(listOf(key)).summary }
                            }, enabled = !commands.busy) { Icon(Icons.Default.Pause, null); Text("Pause phone sync") }
                        }
                    }
                }
            }
        }
    }
}
