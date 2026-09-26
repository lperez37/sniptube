package com.sniptube.android.ui.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryCommandsTest {
    @Test fun `rapid taps cannot run a second mutation while first batch is pending`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val commands = LibraryCommands()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            commands.run { calls++; release.await(); "2 updated · 1 unchanged" }
            commands.run { calls++; "duplicate" }
            runCurrent()
            assertEquals(1, calls)
            assertTrue(commands.busy)
            release.complete(Unit)
            runCurrent()
            assertFalse(commands.busy)
            assertEquals("2 updated · 1 unchanged", commands.messages.first())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `failed action reports once and allows later actions`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val commands = LibraryCommands()
            val message = async { commands.messages.first() }
            commands.run { error("Storage unavailable") }
            runCurrent()
            assertEquals("Storage unavailable", message.await())
            assertFalse(commands.busy)
            commands.run { "Recovered" }
            runCurrent()
            assertEquals("Recovered", commands.messages.first())
        } finally { Dispatchers.resetMain() }
    }
}
