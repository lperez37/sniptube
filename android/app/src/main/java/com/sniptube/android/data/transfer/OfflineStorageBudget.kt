package com.sniptube.android.data.transfer

import android.content.Context
import android.os.StatFs

/** Stored only on this device; the OS free-space reserve remains mandatory regardless of this limit. */
class OfflineStorageBudget(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("offline-storage", Context.MODE_PRIVATE)

    fun limitBytes(): Long? = preferences.getLong("media_limit", -1L).takeIf { it > 0 }

    fun setLimitBytes(bytes: Long?) {
        require(bytes == null || bytes >= GIB) { "Choose at least 1 GiB, or no app limit." }
        preferences.edit().putLong("media_limit", bytes ?: -1L).apply()
    }

    fun freeBytes(): Long? = runCatching { StatFs(app.filesDir.path).availableBytes }.getOrNull()

    fun safeCapacityBytes(usedBytes: Long): Long? = freeBytes()?.let {
        (it - RESERVE_BYTES).coerceAtLeast(0) + usedBytes
    }

    companion object {
        const val GIB = 1024L * 1024 * 1024
        const val RESERVE_BYTES = 128L * 1024 * 1024
    }
}
