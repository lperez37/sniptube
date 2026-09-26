package com.sniptube.android.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryScrollStoreTest {
    @Test fun screenAndFilterPositionsRemainIndependentAcrossNavigation() {
        val store = LibraryScrollStore()
        store.save("downloads:ready", 14, 37)
        store.save("collections:trip", 5, 92)
        store.save("browse:search", 8, 10)
        assertEquals(ScrollPosition(14, 37), store.read("downloads:ready"))
        assertEquals(ScrollPosition(5, 92), store.read("collections:trip"))
        assertEquals(ScrollPosition(8, 10), store.read("browse:search"))
        assertEquals(ScrollPosition(), store.read("downloads:queue"))
    }
}
