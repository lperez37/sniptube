package com.sniptube.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGesturesTest {
    @Test fun doubleTapSkipsTenSecondsWithoutLeavingTheMediaBounds() {
        assertEquals(20_000L, skipPosition(10_000, 40_000, 10_000))
        assertEquals(0L, skipPosition(3_000, 40_000, -10_000))
        assertEquals(40_000L, skipPosition(38_000, 40_000, 10_000))
        assertEquals(48_000L, skipPosition(38_000, -1, 10_000))
    }

    @Test fun moreTapsKeepAddingTenSecondsUntilTheGesturePausesOrSwitchesSides() {
        var total = 0L
        for (expected in 10_000L..60_000L step 10_000L) {
            total = nextSkipAmount(total, 150, sameSide = true)
            assertEquals(expected, total)
        }
        assertEquals(10_000L, nextSkipAmount(total, 750, sameSide = true))
        assertEquals(10_000L, nextSkipAmount(total, 80, sameSide = false))
    }
}
