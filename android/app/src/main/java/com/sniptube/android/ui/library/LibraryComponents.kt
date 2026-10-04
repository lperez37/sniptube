package com.sniptube.android.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sync
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sniptube.android.data.library.*
import com.sniptube.android.data.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Activity-scoped: navigation and configuration changes do not cancel a user-initiated batch. */
@Stable
class LibraryCommands : ViewModel() {
    private val notices = Channel<String>(Channel.UNLIMITED)
    val messages = notices.receiveAsFlow()
    var busy by mutableStateOf(false)
        private set
    fun run(block: suspend () -> String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val message = try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { error.message ?: "Could not complete this action. Please try again." }
            finally { busy = false }
            notices.send(message)
        }
    }
}

@Stable
class VideoSelection {
    var active by mutableStateOf(false)
    var ids by mutableStateOf(emptyList<String>())
    fun clear() { active = false; ids = emptyList() }
    fun toggle(video: OfflineVideoEntity) {
        active = true
        val id = video.key.selectionId
        ids = if (id in ids) ids - id else ids + id
    }
}

@Composable
fun rememberVideoSelection(scope: String): VideoSelection {
    val selection = rememberSaveable(scope, saver = androidx.compose.runtime.saveable.listSaver(
        save = { listOf(it.active.toString()) + it.ids },
        restore = { VideoSelection().apply { active = it.first().toBoolean(); ids = it.drop(1) } },
    )) { VideoSelection() }
    BackHandler(selection.active) { selection.clear() }
    return selection
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectionHeader(selection: VideoSelection, videos: List<OfflineVideoEntity>, busy: Boolean,
    actions: @Composable FlowRowScope.(List<OfflineVideoEntity>) -> Unit) {
    if (!selection.active) {
        VideoActionIcon(Icons.Default.Checklist, "Select videos", { selection.active = true }, enabled = videos.isNotEmpty())
        return
    }
    val selected = selectedVideos(videos, selection.ids)
    Surface(color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(summarize(selected).description(), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoActionIcon(Icons.Default.SelectAll, "Select all ${videos.distinctBy { it.key }.size} shown videos",
                    { selection.ids = videos.map { it.key.selectionId }.distinct() })
                VideoActionIcon(Icons.Default.Close, "Cancel video selection", selection::clear)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!busy && selected.isNotEmpty()) actions(selected)
                if (busy) Text("Applying action…", Modifier.padding(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryVideoRow(video: OfflineVideoEntity, selection: VideoSelection, status: String? = null,
    thumbnail: Any? = video.thumbnailUrl, onOpen: (() -> Unit)? = null,
    watched: Boolean = false, progress: SyncProgress? = null,
    actions: @Composable FlowRowScope.() -> Unit = {}) {
    val selected = video.key.selectionId in selection.ids
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selection.active || onOpen == null) selection.toggle(video) else onOpen() },
            onLongClick = { selection.toggle(video) }, onLongClickLabel = "Select ${video.title}",
            onClickLabel = if (selection.active) "Toggle selection" else if (onOpen != null) "Play ${video.title}" else "Select ${video.title}",
        ).semantics { this.selected = selected }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).heightIn(max = 260.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    val image = ImageRequest.Builder(LocalContext.current).data(thumbnail).crossfade(true).build()
                    var showFallback by remember(thumbnail) { mutableStateOf(true) }
                    AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(), onSuccess = { showFallback = false },
                        onLoading = { showFallback = true }, onError = { showFallback = true },
                        alpha = if (watched) 0.5f else 1f,
                        colorFilter = if (watched) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null)
                    if (showFallback) ThumbnailFallback()
                    if (watched) Surface(Modifier.align(Alignment.TopStart).padding(10.dp),
                        shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text("Watched", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium)
                    }
                    video.durationMs?.let {
                        Text(durationText(it), Modifier.align(Alignment.BottomEnd).padding(10.dp)
                            .background(androidx.compose.ui.graphics.Color(0xDD11111B), RoundedCornerShape(5.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color.White)
                    }
                    if (selection.active) Checkbox(checked = selected, onCheckedChange = { selection.toggle(video) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f), MaterialTheme.shapes.small)
                            .semantics { contentDescription = "Select ${video.title}" })
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(video.title, style = MaterialTheme.typography.titleMedium,
                        color = if (watched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    video.uploader?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    uploadDateText(video.uploadDate)?.let { Text(it,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    status?.let { Text(it, style = MaterialTheme.typography.labelMedium,
                        color = if (it == "Ready offline") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis) }
                progress?.let { SyncProgressIndicator(it, Modifier.fillMaxWidth()) }
                if (!selection.active) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
            }
        }
    }
}

/** A minimum 48dp target with a specific TalkBack action name. */
@Composable
fun VideoActionIcon(icon: ImageVector, label: String, onClick: () -> Unit,
    enabled: Boolean = true, destructive: Boolean = false) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = label,
            tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun SyncProgressIndicator(progress: SyncProgress, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(progress.fraction ?: 0f,
        animationSpec = tween(durationMillis = 400), label = "Real transfer progress")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(progress.label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (progress.fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun SyncOverviewBanner(snapshot: LibrarySnapshot, jobs: List<ServerJobBindingEntity>,
    transfers: List<DeviceTransferBindingEntity>, keys: Set<VideoKey>? = null, onClick: (() -> Unit)? = null) {
    val queue = snapshot.queue.filter { (keys == null || VideoKey(it.serverIdentity, it.youtubeId) in keys) &&
        it.deviceStage !in listOf(DeviceStage.Ready, DeviceStage.Removed) }
    if (queue.isEmpty()) return
    val jobsByKey = jobs.associateBy { VideoKey(it.serverIdentity, it.youtubeId) }
    val transfersByKey = transfers.associateBy { VideoKey(it.serverIdentity, it.youtubeId) }
    val titles = snapshot.videos.associateBy { it.key }
    val active = queue.mapNotNull { intent ->
        val key = VideoKey(intent.serverIdentity, intent.youtubeId)
        syncProgress(intent, jobsByKey[key], transfersByKey[key])?.let { key to it }
    }
    val ready = snapshot.readyKeys().count { keys == null || it in keys }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(when {
                    active.isNotEmpty() -> "Syncing ${active.size} now"
                    queue.all { it.deviceStage == DeviceStage.Failed || it.serverStage == ServerStage.Failed } -> "Sync needs attention"
                    else -> "Waiting to sync"
                },
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text("$ready of ${ready + queue.size} ready", style = MaterialTheme.typography.labelLarge)
            }
            active.take(2).forEach { (key, progress) ->
                Text(titles[key]?.title ?: key.youtubeId, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                SyncProgressIndicator(progress)
            }
            if (active.size > 2) Text("${active.size - 2} more syncing", style = MaterialTheme.typography.labelMedium)
            if (active.isEmpty()) Text(queue.first().errorMessage ?: "Queued for Wi-Fi or confirmed Sync now",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ThumbnailFallback() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.PlayArrow, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
    }
}

@Composable
fun LibraryEmpty(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CollectionNameDialog(title: String, initial: String = "", busy: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Collection name") }) },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank() && !busy) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun AddToCollectionDialog(snapshot: LibrarySnapshot, videos: List<OfflineVideoEntity>, actions: LibraryActions,
    commands: LibraryCommands, onDismiss: () -> Unit) {
    var create by rememberSaveable { mutableStateOf(false) }
    if (create) {
        CollectionNameDialog("New collection", busy = commands.busy, onDismiss = { create = false }) { name ->
            onDismiss()
            commands.run {
                val id = actions.createCollection(name)
                val result = actions.addToCollection(id, videos)
                result.summary
            }
        }
    } else AlertDialog(onDismissRequest = onDismiss, title = { Text("Add ${videos.size} to collection") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            if (snapshot.collections.isEmpty()) Text("Create a collection to organize your videos on this phone.")
            snapshot.collections.forEach { collection ->
                TextButton(onClick = { onDismiss(); commands.run { actions.addToCollection(collection.id, videos).summary } },
                    enabled = !commands.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(collection.name) }
            }
        } },
        confirmButton = { TextButton(onClick = { create = true }, enabled = !commands.busy) { Text("New collection") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
