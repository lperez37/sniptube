package com.sniptube.android.data.transfer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OfflineStorageBudgetTest {
    @Test fun storedLimitSurvivesRecreationAndRespectsFreeSpaceReserve() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settings = OfflineStorageBudget(context)
        try {
            settings.setLimitBytes(5 * OfflineStorageBudget.GIB)
            assertEquals(5 * OfflineStorageBudget.GIB, OfflineStorageBudget(context).limitBytes())
            val free = settings.freeBytes()
            if (free != null) assertEquals((free - OfflineStorageBudget.RESERVE_BYTES).coerceAtLeast(0) + 1024,
                settings.safeCapacityBytes(1024))
            settings.setLimitBytes(null)
            assertNull(OfflineStorageBudget(context).limitBytes())
        } finally { settings.setLimitBytes(null) }
    }
}
