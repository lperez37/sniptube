package com.sniptube.android.ui.library

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryScrollRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun temporaryEmptyRowsDoNotEraseScrollPosition() {
        val store = LibraryScrollStore()
        var count by mutableIntStateOf(40)
        lateinit var list: LazyListState
        compose.setContent {
            list = rememberLibraryListState(store, "search", count)
            LazyColumn(state = list) {
                item { Text("Header", Modifier.height(60.dp)) }
                items(count, key = { "video-$it" }) { Text("Video $it", Modifier.height(180.dp)) }
            }
        }
        compose.runOnIdle { runBlocking { list.scrollToItem(18, 37) } }
        compose.waitForIdle()
        compose.runOnIdle { count = 0 }
        compose.waitForIdle()
        compose.runOnIdle { count = 40 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(ScrollPosition(18, 37), ScrollPosition(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset))
        }
    }

    @Test fun switchingTabsAndReturningAfterPlayerKeepsEachOffset() {
        val store = LibraryScrollStore()
        var screen by mutableStateOf("search")
        var playing by mutableStateOf(false)
        var count by mutableIntStateOf(40)
        lateinit var list: LazyListState
        compose.setContent {
            if (!playing) {
                list = rememberLibraryListState(store, screen, count)
                LazyColumn(state = list) {
                    item { Text("Header", Modifier.height(60.dp)) }
                    items(count, key = { "$screen-video-$it" }) { Text("Video $it", Modifier.height(180.dp)) }
                }
            }
        }
        compose.runOnIdle { runBlocking { list.scrollToItem(18, 37) } }
        compose.runOnIdle { screen = "library" }
        compose.runOnIdle { runBlocking { list.scrollToItem(8, 21) } }
        compose.runOnIdle { screen = "search" }
        compose.runOnIdle { assertEquals(18, list.firstVisibleItemIndex); assertEquals(37, list.firstVisibleItemScrollOffset) }
        compose.runOnIdle { playing = true }
        compose.runOnIdle { count = 0; playing = false }
        compose.runOnIdle { count = 40 }
        compose.runOnIdle { assertEquals(18, list.firstVisibleItemIndex); assertEquals(37, list.firstVisibleItemScrollOffset) }
        compose.runOnIdle { count = 60 }
        compose.runOnIdle { assertEquals(18, list.firstVisibleItemIndex) }
    }
}
