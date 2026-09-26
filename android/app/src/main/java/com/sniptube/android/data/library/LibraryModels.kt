package com.sniptube.android.data.library

import com.sniptube.android.data.local.CollectionEntity
import com.sniptube.android.data.local.CollectionMembershipEntity
import com.sniptube.android.data.local.LocalFileEntity
import com.sniptube.android.data.local.OfflineVideoEntity
import com.sniptube.android.data.local.PlaybackProgressEntity
import com.sniptube.android.data.local.QueueIntentEntity

data class VideoKey(val serverIdentity: String, val youtubeId: String)

data class BatchResult(
    val succeeded: Int = 0,
    val skipped: Int = 0,
    val failures: List<String> = emptyList(),
) {
    val summary: String
        get() = "$succeeded updated · $skipped unchanged" +
            if (failures.isEmpty()) "" else " · ${failures.size} failed: ${failures.first()}"
}

data class LibrarySnapshot(
    val videos: List<OfflineVideoEntity> = emptyList(),
    val queue: List<QueueIntentEntity> = emptyList(),
    val collections: List<CollectionEntity> = emptyList(),
    val memberships: List<CollectionMembershipEntity> = emptyList(),
    val assets: List<LocalFileEntity> = emptyList(),
    val playback: List<PlaybackProgressEntity> = emptyList(),
)
