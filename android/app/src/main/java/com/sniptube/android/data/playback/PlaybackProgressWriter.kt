package com.sniptube.android.data.playback

import com.sniptube.android.data.local.OfflineDao
import com.sniptube.android.data.local.PlaybackProgressEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Ordered, conflated writes; closing drains the final snapshot even after Compose leaves. */
internal class PlaybackProgressWriter(private val dao: OfflineDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshots = Channel<PlaybackProgressEntity>(Channel.CONFLATED)
    private val job = scope.launch {
        try {
            for (snapshot in snapshots) {
                try { dao.upsertPlaybackProgress(snapshot) } catch (_: Exception) {
                    // A concurrently deleted metadata row or unavailable disk cannot crash playback.
                }
            }
        } finally { scope.cancel() }
    }
    fun save(snapshot: PlaybackProgressEntity) { snapshots.trySend(snapshot) }
    fun close() { snapshots.close() }
    suspend fun awaitClosed() { job.join() }
}
