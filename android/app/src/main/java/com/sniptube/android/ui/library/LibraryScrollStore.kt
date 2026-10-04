package com.sniptube.android.ui.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

data class ScrollPosition(val index: Int = 0, val offset: Int = 0)

/** Activity-owned so player navigation cannot discard a list's precise scroll offset. */
class LibraryScrollStore : ViewModel() {
    private val positions = linkedMapOf<String, ScrollPosition>()

    fun read(key: String): ScrollPosition = positions[key] ?: ScrollPosition()

    fun save(key: String, index: Int, offset: Int) {
        positions[key] = ScrollPosition(index.coerceAtLeast(0), offset.coerceAtLeast(0))
        if (positions.size > 64) positions.remove(positions.keys.first())
    }
}

@Composable
fun rememberLibraryListState(store: LibraryScrollStore, key: String, videoCount: Int): LazyListState {
    val state = remember(store, key) {
        store.read(key).let { LazyListState(it.index, it.offset) }
    }
    var restored by remember(store, key) { mutableStateOf(false) }
    val currentCount by rememberUpdatedState(videoCount)
    // Room may first emit an empty snapshot; wait for rows before allowing its layout to
    // overwrite the saved position with index 0. Each list also contains a header item.
    LaunchedEffect(store, key, state, videoCount) {
        if (videoCount == 0) {
            restored = false
            return@LaunchedEffect
        }
        // Restoration before the first populated measure is clamped to the empty header.
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it >= videoCount + 1 }
        if (!restored) {
            val previous = store.read(key)
            state.scrollToItem(previous.index.coerceAtMost(videoCount), previous.offset)
            restored = true
        }
        snapshotFlow { state.layoutInfo.totalItemsCount to ScrollPosition(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
            .distinctUntilChanged().collect {
                if (currentCount > 0 && it.first > 1) store.save(key, it.second.index, it.second.offset)
            }
    }
    DisposableEffect(store, key, state) {
        onDispose {
            if (restored && currentCount > 0 && state.layoutInfo.totalItemsCount > 1)
                store.save(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }
    }
    return state
}
