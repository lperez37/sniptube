package com.sniptube.android.ui.player

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.Modifier
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chromeHidesAfterTwoSecondsAndOnlyInteractionRestartsTimeout() {
        val state = PlayerInteractionState()
        var hidden = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PlayerChromeTimeout(state, true) { hidden++ }
            Column {
                Text(if (state.chromeVisible) "Controls" else "Video")
                Text("Touch", Modifier.clickable { state.interact() })
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.runOnIdle { assertTrue(state.chromeVisible) }
        compose.onNodeWithText("Touch").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(1_900)
        compose.runOnIdle { assertTrue(state.chromeVisible) }
        compose.mainClock.advanceTimeBy(150)
        compose.runOnIdle { assertFalse(state.chromeVisible); assertEquals(1, hidden) }
    }

    @Test fun sameQuietButtonLocksAndRequiresFiveClicksToUnlock() {
        val state = PlayerInteractionState()
        compose.setContent { MaterialTheme { ChildLockButton(state) } }
        compose.onNodeWithContentDescription("Enable child lock").performClick()
        compose.runOnIdle { assertTrue(state.locked); assertFalse(state.chromeVisible) }
        repeat(4) {
            compose.onNodeWithContentDescription("Child lock enabled").performClick()
            compose.runOnIdle { assertTrue(state.locked); assertFalse(state.chromeVisible) }
        }
        compose.onNodeWithContentDescription("Child lock enabled").performClick()
        compose.runOnIdle { assertFalse(state.locked); assertTrue(state.chromeVisible) }
    }

    @Test fun slowTapsAndInteractionsCannotUnlockOrRevealControls() {
        val state = PlayerInteractionState()
        state.lock()
        repeat(10) { state.tapLock(it * 600L); state.showChrome(); state.interact() }
        assertTrue(state.locked)
        assertFalse(state.chromeVisible)
        repeat(4) { state.tapLock(10_000 + it * 150L); assertTrue(state.locked) }
        state.tapLock(10_600)
        assertFalse(state.locked)
        state.lock()
        repeat(4) { state.tapLock(20_000 + it * 150L) }
        // A gap discards partial progress, including the fourth tap.
        state.tapLock(22_000)
        assertTrue(state.locked)
    }
}
