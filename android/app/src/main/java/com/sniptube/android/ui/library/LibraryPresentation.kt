package com.sniptube.android.ui.library

import com.sniptube.android.data.browse.BrowseVideo
import com.sniptube.android.data.api.ServerConnection
import com.sniptube.android.data.library.LibrarySnapshot
import com.sniptube.android.data.library.VideoKey
import com.sniptube.android.data.playback.canonicalYoutubeThumbnail
import com.sniptube.android.data.playback.trustedThumbnail
import com.sniptube.android.data.local.*
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

val OfflineVideoEntity.key get() = VideoKey(serverIdentity, youtubeId)
val VideoKey.selectionId get() = "${serverIdentity.length}:$serverIdentity$youtubeId"

fun BrowseVideo.offlineMetadata(now: Long) = OfflineVideoEntity(
    serverIdentity = serverIdentity, youtubeId = youtubeId, sourceUrl = sourceUrl,
    serverVideoId = serverVideoId, title = title, durationMs = durationMs,
    thumbnailUrl = thumbnailUrl, uploader = uploader, serverStatus = serverStatus,
    serverFileSizeBytes = serverFileSizeBytes, metadataUpdatedAt = now,
    uploadDate = uploadDate,
)

fun uploadDateText(value: String?): String? = value?.let {
    runCatching {
        val date = LocalDate.parse(it, if (it.length == 8) DateTimeFormatter.BASIC_ISO_DATE else DateTimeFormatter.ISO_LOCAL_DATE)
        "Uploaded ${date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}"
    }.getOrNull()
}

/** Selection is scoped to the loaded, visible result set; pagination never selects future rows. */
fun selectedVideos(videos: List<OfflineVideoEntity>, ids: List<String>): List<OfflineVideoEntity> {
    val selected = ids.toHashSet()
    return videos.distinctBy { it.key }.filter { it.key.selectionId in selected }
}

data class VideoSummary(val count: Int, val durationMs: Long, val unknownDurations: Int,
    val knownBytes: Long, val unknownSizes: Int)

fun summarize(videos: List<OfflineVideoEntity>): VideoSummary {
    val unique = videos.distinctBy { it.key }
    return VideoSummary(unique.size, unique.sumOf { it.durationMs?.coerceAtLeast(0) ?: 0 },
        unique.count { it.durationMs == null }, unique.sumOf { it.serverFileSizeBytes?.coerceAtLeast(0) ?: 0 },
        unique.count { it.serverFileSizeBytes == null })
}

fun VideoSummary.description() = buildString {
    append("$count selected · ${durationText(durationMs)} known duration")
    if (unknownDurations > 0) append(" · $unknownDurations duration unknown")
    append("\n${byteText(knownBytes)} known size")
    if (unknownSizes > 0) append(" · $unknownSizes size unknown")
}

fun byteText(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format(Locale.getDefault(), "%.1f GiB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f MiB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

fun durationText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}

/** Avoid claiming an exact collection runtime when some durations have not been discovered. */
fun viewingTimeText(videos: List<OfflineVideoEntity>): String {
    val summary = summarize(videos)
    return "${durationText(summary.durationMs)} known viewing time" +
        if (summary.unknownDurations > 0) " · ${summary.unknownDurations} unknown" else ""
}

data class SyncEstimate(val knownBytes: Long, val unknownItems: Int, val itemCount: Int) {
    fun warningText(): String = buildString {
        append("Estimated cellular download: ")
        append(String.format(Locale.getDefault(), "%.1f MB", knownBytes / 1_000_000.0))
        append(" across $itemCount videos")
        if (unknownItems > 0) append("; $unknownItems video${if (unknownItems == 1) " has" else "s have"} unknown size")
        append(". Actual data use may be higher. Continue over cellular?")
    }
}

fun LibrarySnapshot.syncEstimate(videos: List<OfflineVideoEntity>, transfers: List<DeviceTransferBindingEntity>): SyncEstimate {
    val ready = readyKeys()
    val transferByKey = transfers.associateBy { VideoKey(it.serverIdentity, it.youtubeId) }
    val pending = videos.distinctBy { it.key }.filter { it.key !in ready }
    var knownBytes = 0L
    var unknown = 0
    pending.forEach { video ->
        val transfer = transferByKey[video.key]
        val size = transfer?.totalBytes?.takeIf { it > 0 } ?: video.serverFileSizeBytes?.takeIf { it > 0 }
        if (size == null) unknown++ else knownBytes += (size - (transfer?.bytesDownloaded ?: 0L)).coerceAtLeast(0)
    }
    return SyncEstimate(knownBytes, unknown, pending.size)
}

/** A status alone is insufficient: require the reconciled media asset from the same generation. */
fun LibrarySnapshot.readyKeys(): Set<VideoKey> {
    val media = assets.filter { it.kind == LocalFileKind.Media && it.byteSize > 0 }
        .associateBy { VideoKey(it.serverIdentity, it.youtubeId) }
    return queue.filter { it.deviceStage == DeviceStage.Ready &&
        media[VideoKey(it.serverIdentity, it.youtubeId)]?.generation == it.generation }
        .mapTo(mutableSetOf()) { VideoKey(it.serverIdentity, it.youtubeId) }
}

fun isWatched(video: OfflineVideoEntity, progress: PlaybackProgressEntity?): Boolean =
    progress?.let { it.viewed || watchedEnough(it.positionMs, it.durationMs ?: video.durationMs) } == true

data class SyncProgress(val label: String, val fraction: Float?)

/** A percentage is shown only when the server reports one or media size is known. */
fun syncProgress(intent: QueueIntentEntity, job: ServerJobBindingEntity?, transfer: DeviceTransferBindingEntity?): SyncProgress? = when {
    intent.userPaused || intent.deviceStage == DeviceStage.Ready || intent.deviceStage == DeviceStage.Removed -> null
    intent.deviceStage == DeviceStage.Running -> {
        val bytes = transfer?.bytesDownloaded?.coerceAtLeast(0) ?: 0
        val size = transfer?.totalBytes?.takeIf { it > 0 }
        val fraction = size?.let { (bytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
        SyncProgress("Phone · ${fraction?.let { "${(it * 100).toInt()}% · " } ?: ""}${byteText(bytes)} of ${size?.let(::byteText) ?: "unknown size"}", fraction)
    }
    intent.serverStage == ServerStage.Running -> {
        val fraction = job?.progress?.takeIf { it in 0..100 }?.div(100f)
        SyncProgress("Server preparing${fraction?.let { " · ${(it * 100).toInt()}%" } ?: ""}", fraction)
    }
    else -> null
}

/** Offline rows never request a network image. A stored track must belong to the current copy. */
fun LibrarySnapshot.thumbnailFor(video: OfflineVideoEntity, ready: Boolean): Any? {
    val generation = queue.firstOrNull { it.serverIdentity == video.serverIdentity && it.youtubeId == video.youtubeId }?.generation
    val local = assets.firstOrNull { it.serverIdentity == video.serverIdentity && it.youtubeId == video.youtubeId &&
        it.kind == LocalFileKind.Thumbnail && it.generation == generation }
        ?.path?.let(::File)?.takeIf { it.isFile && it.length() > 0 }
    if (local != null || ready) return local
    val server = runCatching { ServerConnection.parse(video.serverIdentity).baseUrl }.getOrNull()
    val supplied = video.thumbnailUrl?.toHttpUrlOrNull()?.takeIf { server != null && trustedThumbnail(it, server) }
    return supplied?.toString() ?: canonicalYoutubeThumbnail(video.youtubeId)
}

fun queueLabel(intent: QueueIntentEntity?, ready: Boolean, job: ServerJobBindingEntity?, transfer: DeviceTransferBindingEntity?): String = when {
    ready -> "Ready offline"
    intent == null || intent.deviceStage == DeviceStage.Removed -> "Not on this phone"
    intent.userPaused || intent.deviceStage == DeviceStage.Paused -> "Paused on phone · server preparation may continue"
    intent.serverStage == ServerStage.Failed -> "Server preparation failed"
    intent.deviceStage == DeviceStage.Failed -> "Phone download failed"
    intent.deviceStage == DeviceStage.Ready -> "Local copy unavailable · remove from phone, then download again"
    intent.deviceStage == DeviceStage.WaitingForNetwork -> intent.errorMessage?.takeIf(String::isNotBlank)
        ?: "Waiting for Wi-Fi or confirmed Sync now"
    intent.deviceStage == DeviceStage.Running -> "Transferring to phone · " +
        (transfer?.let { "${byteText(it.bytesDownloaded)} of ${it.totalBytes?.takeIf { total -> total > 0 }?.let(::byteText) ?: "unknown size"}" } ?: "checking progress")
    intent.serverStage == ServerStage.Ready -> "Ready on server · waiting for phone transfer"
    intent.serverStage == ServerStage.Running -> "Preparing on server" + (job?.let { " · ${it.progress.coerceIn(0, 100)}%" } ?: "")
    intent.serverStage == ServerStage.Queued -> "Queued on server"
    else -> "Waiting to submit to server"
}
