package com.sniptube.android.data.playback

import android.net.Uri
import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import com.sniptube.android.data.local.*
import com.sniptube.android.data.transfer.MediaDownloads
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OfflineUnavailableException : IOException(
    "This offline copy is missing or incomplete. Return to Downloads and retry device sync while connected.",
)

data class OfflinePlaybackAsset(
    val title: String,
    val generation: Long,
    val mediaItem: MediaItem,
    val subtitles: List<LocalFileEntity>,
    val positionMs: Long,
)

/** No API client is accepted here: opening a downloaded title is exclusively local. */
@OptIn(UnstableApi::class)
class OfflinePlayback(private val dao: OfflineDao, private val media: MediaDownloads) {
    suspend fun open(server: String, youtubeId: String): OfflinePlaybackAsset = withContext(Dispatchers.IO) {
        val intent = dao.getIntent(server, youtubeId) ?: throw OfflineUnavailableException()
        if (intent.deviceStage != DeviceStage.Ready) throw OfflineUnavailableException()
        val files = dao.getLocalFiles(server, youtubeId).filter { it.generation == intent.generation }
        val asset = files.firstOrNull { it.kind == LocalFileKind.Media } ?: throw OfflineUnavailableException()
        // A media3: marker is a DownloadIndex ID, never a filesystem path.
        if (asset.assetType != AssetType.Media3Cache || !asset.path.startsWith("media3:") ||
            asset.cacheKey == null
        ) {
            dao.invalidateMissingMedia(asset, System.currentTimeMillis())
            throw OfflineUnavailableException()
        }
        val download = media.complete(asset.path.removePrefix("media3:"), asset.cacheKey)
        if (download == null || download.request.customCacheKey != asset.cacheKey) {
            // The cache may disappear after startup reconciliation. Invalidate only this
            // generation so Downloads can offer a real retry, not an unusable Ready row.
            dao.invalidateMissingMedia(asset, System.currentTimeMillis())
            throw OfflineUnavailableException()
        }
        OfflinePlaybackAsset(
            dao.getVideo(server, youtubeId)?.title ?: youtubeId,
            intent.generation, download.request.toMediaItem(),
            files.filter { it.kind == LocalFileKind.Subtitle && it.assetType == AssetType.File &&
                File(it.path).let { file -> file.isFile && file.length() == it.byteSize } },
            resumePosition(dao.getPlaybackProgress(server, youtubeId)),
        )
    }

    fun source(asset: OfflinePlaybackAsset): MediaSource = offlineMediaSource(
        asset.mediaItem, media.offlineDataSource(), asset.subtitles,
    )
}

@OptIn(UnstableApi::class)
internal fun offlineMediaSource(
    item: MediaItem,
    cacheOnly: DataSource.Factory,
    subtitles: List<LocalFileEntity>,
): MediaSource {
    val video = ProgressiveMediaSource.Factory(cacheOnly).createMediaSource(item)
    val tracks = subtitles.map { track ->
        val configuration = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(track.path)))
            .setMimeType(MimeTypes.TEXT_VTT).setLanguage(track.language)
            .setLabel(track.language ?: "Subtitles").build()
        // FileDataSource cannot perform HTTP and does not route local VTT through the media cache.
        SingleSampleMediaSource.Factory(FileDataSource.Factory())
            .setTreatLoadErrorsAsEndOfStream(true)
            .createMediaSource(configuration, C.TIME_UNSET)
    }
    return if (tracks.isEmpty()) video else MergingMediaSource(video, *tracks.toTypedArray())
}

internal fun resumePosition(progress: PlaybackProgressEntity?): Long = progress?.let {
    if (it.durationMs != null && it.durationMs > 0 && it.positionMs >= it.durationMs) 0
    else it.positionMs.coerceAtLeast(0)
} ?: 0

/** Media3 1.8 disables render-time decoding by default; SingleSample VTT requires this opt-in. */
@OptIn(UnstableApi::class)
internal fun offlineRenderersFactory(context: Context): DefaultRenderersFactory =
    object : DefaultRenderersFactory(context) {
        override fun buildTextRenderers(context: Context, output: TextOutput, outputLooper: Looper,
                                        extensionRendererMode: Int, out: ArrayList<Renderer>) {
            out.add(TextRenderer(output, outputLooper).apply {
                experimentalSetLegacyDecodingEnabled(true)
            })
        }
    }
