package com.sniptube.android.ui.player

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver

internal const val PLAYER_CHROME_TIMEOUT_MS = 2_000L

/** Only real interactions extend chrome lifetime; recomposition and playback events do not. */
@Stable
internal class PlayerInteractionState(locked: Boolean = false) {
    var locked by mutableStateOf(locked)
        private set
    var chromeVisible by mutableStateOf(!locked)
        private set
    var interactionRevision by mutableLongStateOf(0)
        private set
    private var firstUnlockTap = 0L
    private var lastUnlockTap = 0L
    private var unlockTaps = 0

    fun interact() { if (!locked) interactionRevision++ }
    fun showChrome() { if (!locked) { chromeVisible = true; interact() } }
    fun hideChrome() { chromeVisible = false }
    fun lock() { locked = true; chromeVisible = false; unlockTaps = 0 }
    fun unlock() { locked = false; unlockTaps = 0; showChrome() }

    fun tapLock(nowMs: Long) {
        if (!locked) { lock(); return }
        if (unlockTaps == 0 || nowMs - lastUnlockTap !in 0..500 || nowMs - firstUnlockTap !in 0..2_000) {
            unlockTaps = 0
            firstUnlockTap = nowMs
        }
        lastUnlockTap = nowMs
        unlockTaps++
        if (unlockTaps == 5) unlock()
    }

    companion object {
        // Preserve the lock across rotation; never preserve a partial unlock sequence.
        val Saver = Saver<PlayerInteractionState, Boolean>(save = { it.locked }, restore = { PlayerInteractionState(it) })
    }
}
