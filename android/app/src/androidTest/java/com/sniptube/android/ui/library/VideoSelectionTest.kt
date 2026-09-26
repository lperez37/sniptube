package com.sniptube.android.ui.library

import androidx.compose.foundation.layout.Column
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.sniptube.android.data.local.OfflineVideoEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
class VideoSelectionTest {
    @get:Rule val compose = createComposeRule()
    private val first = video("one", "First video")
    private val second = video("two", "Second video")
    private fun video(id: String, title: String) = OfflineVideoEntity("https://example.test", id,
        "https://youtube.com/watch?v=$id", title = title, metadataUpdatedAt = 1)

    @Test fun selectAllOnlyIncludesLoadedResultsAndRetainsSelectionOnRefresh() {
        var videos by mutableStateOf(listOf(first))
        var applied = emptyList<OfflineVideoEntity>()
        compose.setContent {
            MaterialTheme {
                val selection = rememberVideoSelection("search")
                Column {
                    SelectionHeader(selection, videos, false) { chosen -> TextButton(onClick = { applied = chosen }) { Text("Apply") } }
                    videos.forEach { LibraryVideoRow(it, selection) }
                }
            }
        }
        compose.onNodeWithText("Select videos").performClick()
        compose.onNodeWithText("Select all loaded (1)").performClick()
        compose.runOnIdle { videos = listOf(second, first.copy(title = "New title")) }
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(listOf(first.copy(title = "New title")), applied) }
        compose.onNodeWithText("Cancel selection").performClick()
        compose.onNodeWithText("Select videos").assertIsDisplayed()
    }

    @Test fun longPressSelectsAndStateRestorationKeepsServerScopedSelection() {
        val restore = StateRestorationTester(compose)
        var applied = emptyList<OfflineVideoEntity>()
        restore.setContent {
            MaterialTheme {
                val selection = rememberVideoSelection("search")
                Column {
                    SelectionHeader(selection, listOf(first, second), false) { chosen -> TextButton(onClick = { applied = chosen }) { Text("Apply") } }
                    LibraryVideoRow(first, selection)
                    LibraryVideoRow(second, selection)
                }
            }
        }
        compose.onNodeWithText("First video").performTouchInput { longClick() }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(listOf(first), applied) }
    }

    @Test fun systemBackExitsSelectionBeforeNavigatingAway() {
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            MaterialTheme {
                val selection = rememberVideoSelection("downloads")
                Column {
                    SelectionHeader(selection, listOf(first), false) { }
                    LibraryVideoRow(first, selection)
                }
            }
        }
        compose.onNodeWithText("Select videos").performClick()
        compose.onNodeWithText("Select all loaded (1)").performClick()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithText("Select videos").assertIsDisplayed()
        compose.onNodeWithText("Cancel selection").assertDoesNotExist()
    }
}
