package com.sniptube.android.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import coil.compose.AsyncImage
import com.sniptube.android.data.library.*
import com.sniptube.android.data.local.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionsScreen(snapshot: LibrarySnapshot, actions: LibraryActions, commands: LibraryCommands,
    jobs: List<ServerJobBindingEntity>, transfers: List<DeviceTransferBindingEntity>, onPlay: (VideoKey) -> Unit,
    scrollStore: LibraryScrollStore) {
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<CollectionEntity?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CollectionEntity?>(null) }
    var confirmingCellular by remember { mutableStateOf<CollectionEntity?>(null) }
    var choosingMembers by rememberSaveable { mutableStateOf(false) }
    val collection = snapshot.collections.firstOrNull { it.id == openedId }
    val selection = rememberVideoSelection("${collection?.id}:$choosingMembers")
    BackHandler(collection != null && !selection.active) {
        if (choosingMembers) choosingMembers = false else openedId = null
    }
    val ready = remember(snapshot.queue, snapshot.assets) { snapshot.readyKeys() }
    val progressByKey = remember(snapshot.playback) { snapshot.playback.associateBy { VideoKey(it.serverIdentity, it.youtubeId) } }
    val memberships = snapshot.memberships.filter { it.collectionId == collection?.id }.map { VideoKey(it.serverIdentity, it.youtubeId) }.toSet()
    val members = snapshot.videos.filter { it.key in memberships }
    val visible = if (choosingMembers) snapshot.videos.filter { it.key !in memberships } else members
    val listState = rememberLibraryListState(scrollStore,
        "collections:${collection?.id ?: "index"}:$choosingMembers", if (collection == null) snapshot.collections.size else visible.size)
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (collection == null) {
                    Text("Collections", style = MaterialTheme.typography.headlineMedium)
                    Text("Organize videos on this phone. Each video shares one offline copy across collections.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { creating = true }, enabled = !commands.busy) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("New collection")
                    }
                } else {
                    VideoActionIcon(Icons.AutoMirrored.Filled.ArrowBack, if (choosingMembers) "Back to collection" else "All collections",
                        { if (choosingMembers) choosingMembers = false else openedId = null; selection.clear() })
                    Text(collection.name, style = MaterialTheme.typography.headlineMedium)
                    Text("${members.size} videos · ${members.count { it.key in ready }} ready offline")
                    Text(viewingTimeText(members), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    SyncOverviewBanner(snapshot, jobs, transfers, keys = memberships)
                    if (choosingMembers) Text("Select from videos known to this phone. Find more using Browse → Add to collection.")
                    else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                if (actions.wifiEligibility() == null) commands.run {
                                    syncNotice(actions.syncCollectionNow(collection.id, false), members.size)
                                }
                                else if (actions.manualEligibility() == null) confirmingCellular = collection
                                else commands.run { actions.manualEligibility() ?: "Connect to Wi-Fi or cellular to sync." }
                            }, enabled = !commands.busy && members.any { it.key !in ready }) {
                                Icon(Icons.Default.Sync, null); Spacer(Modifier.width(8.dp)); Text("Sync now")
                            }
                            VideoActionIcon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add videos to ${collection.name}",
                                { choosingMembers = true; selection.clear() })
                            VideoActionIcon(Icons.Default.DriveFileRenameOutline, "Rename ${collection.name}",
                                { editing = collection }, enabled = !commands.busy)
                            VideoActionIcon(Icons.Default.DeleteOutline, "Delete collection ${collection.name}",
                                { deleting = collection }, enabled = !commands.busy, destructive = true)
                        }
                        Text("Collection videos sync automatically on Wi-Fi. After clearing phone copies, tap Sync now to restore them. Cellular sync needs a data-use confirmation.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (collection != null && visible.isEmpty()) LibraryEmpty(
                if (choosingMembers) "No more known videos" else "This collection is empty",
                "Find videos in Browse and choose Add to collection. Removing membership never removes server or phone copies.")
            if (collection == null && snapshot.collections.isEmpty()) LibraryEmpty("Plan a trip, a topic, or a playlist", "Create a collection, then add videos from Browse or Downloads.")
        }
        if (collection == null) items(snapshot.collections, key = { it.id }) { row ->
            val keys = snapshot.memberships.filter { it.collectionId == row.id }.map { VideoKey(it.serverIdentity, it.youtubeId) }.toSet()
            val collectionVideos = snapshot.videos.filter { it.key in keys }
            val cover = snapshot.videos.firstOrNull { it.key in keys }
                ?.let { snapshot.thumbnailFor(it, it.key in ready) }
            Card(onClick = { openedId = row.id; choosingMembers = false; selection.clear() },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(108.dp, 76.dp).clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        if (cover != null) AsyncImage(model = cover, contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Default.CollectionsBookmark, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(row.name, style = MaterialTheme.typography.titleLarge)
                        Text("${keys.size} videos · ${keys.count { it in ready }} ready offline",
                            style = MaterialTheme.typography.bodySmall)
                        Text(viewingTimeText(collectionVideos), color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        } else items(visible, key = { it.key.selectionId }) { video ->
            val watched = isWatched(video, progressByKey[video.key])
            LibraryVideoRow(video, selection, if (video.key in ready) "Ready offline" else "Not ready on this phone",
                thumbnail = snapshot.thumbnailFor(video, video.key in ready),
                onOpen = if (video.key in ready && !choosingMembers) ({ onPlay(video.key) }) else null,
                watched = watched,
                progress = snapshot.queue.firstOrNull { it.serverIdentity == video.serverIdentity && it.youtubeId == video.youtubeId }
                    ?.let { intent -> syncProgress(intent, jobs.firstOrNull { it.serverIdentity == video.serverIdentity && it.youtubeId == video.youtubeId },
                        transfers.firstOrNull { it.serverIdentity == video.serverIdentity && it.youtubeId == video.youtubeId }) }) {
                if (choosingMembers) VideoActionIcon(Icons.Default.CheckCircleOutline, "Select ${video.title}",
                    { selection.toggle(video) })
                else {
                    if (video.key in ready) VideoActionIcon(Icons.Default.PlayCircle, "Play ${video.title} offline",
                        { onPlay(video.key) })
                    else VideoActionIcon(Icons.Default.DownloadForOffline, "Download ${video.title} offline",
                        { commands.run { actions.enqueue(listOf(video)).summary } }, enabled = !commands.busy)
                    VideoActionIcon(if (watched) Icons.Default.Replay else Icons.Default.DoneAll,
                        if (watched) "Mark ${video.title} unwatched and restart progress" else "Mark ${video.title} watched",
                        { commands.run {
                            actions.setViewed(video.key, !watched)
                            if (watched) "Marked unwatched; playback starts at the beginning" else "Marked watched"
                        } }, enabled = !commands.busy)
                    VideoActionIcon(Icons.Default.PlaylistRemove, "Remove ${video.title} from ${collection.name}",
                        { commands.run { actions.removeFromCollection(collection.id, listOf(video.key)).summary } },
                        enabled = !commands.busy)
                }
            }
        }
    }
    if (collection != null) SelectionHeader(selection, visible, commands.busy) { selected ->
        if (choosingMembers) Button(onClick = { commands.run {
            val result = actions.addToCollection(collection.id, selected)
            choosingMembers = false; selection.clear(); result.summary
        } }) { Text("Add ${selected.size} videos") }
        else {
            Button(onClick = { commands.run { actions.enqueue(selected).summary } }) { Text("Download ${selected.size} offline") }
            OutlinedButton(onClick = { commands.run {
                val result = actions.removeFromCollection(collection.id, selected.map { it.key }); selection.clear(); result.summary
            } }) { Text("Remove membership") }
        }
    }
    }
    if (creating) CollectionNameDialog("New collection", busy = commands.busy, onDismiss = { creating = false }) { name ->
        creating = false
        commands.run { openedId = actions.createCollection(name); "Collection created" }
    }
    editing?.let { row -> CollectionNameDialog("Rename collection", row.name, commands.busy, { editing = null }) { name ->
        editing = null
        commands.run { actions.renameCollection(row.id, name); "Collection renamed" }
    } }
    deleting?.let { row -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${row.name}?") },
        text = { Text("Only this collection and its memberships are deleted from this phone. No server videos or downloaded phone copies are removed. Ongoing downloads continue.") },
        confirmButton = { TextButton(onClick = { deleting = null; commands.run {
            actions.deleteCollection(row.id); openedId = null; "Collection deleted; video copies kept"
        } }, enabled = !commands.busy) { Text("Delete collection") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    confirmingCellular?.let { row ->
        val videos = snapshot.videos.filter { video -> snapshot.memberships.any {
            it.collectionId == row.id && it.serverIdentity == video.serverIdentity && it.youtubeId == video.youtubeId
        } }
        val estimate = snapshot.syncEstimate(videos, transfers)
        AlertDialog(onDismissRequest = { confirmingCellular = null }, title = { Text("Sync ${row.name} over cellular?") },
            text = { Text(estimate.warningText()) },
            confirmButton = { Button(onClick = {
                confirmingCellular = null
                commands.run { syncNotice(actions.syncCollectionNow(row.id, true), videos.size) }
            }, enabled = !commands.busy && estimate.itemCount > 0) { Text("Use cellular data") } },
            dismissButton = { TextButton(onClick = { confirmingCellular = null }) { Text("Cancel") } })
    }
}

private fun syncNotice(result: BatchResult, total: Int): String =
    if (result.failures.isEmpty()) "Checking $total collection videos for offline sync."
    else "Checking $total videos; ${result.failures.size} could not be queued: ${result.failures.first()}"
