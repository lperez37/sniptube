package com.sniptube.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerConnectionTest {
    @Test
    fun `normalizes an origin and preserves an optional reverse proxy path`() {
        assertEquals(
            "https://example.test/",
            ServerConnection.parse("  https://example.test  ").identity,
        )
        assertEquals(
            "http://192.168.1.8:8030/sniptube/",
            ServerConnection.parse("http://192.168.1.8:8030/sniptube").identity,
        )
    }

    @Test
    fun `rejects incomplete unsafe or credential-bearing addresses`() {
        listOf(
            "",
            "example.test",
            "ftp://example.test",
            "https://user:secret@example.test",
            "https://example.test/?token=secret",
            "https://example.test/#fragment",
        ).forEach { value ->
            assertThrows(InvalidServerUrlException::class.java) {
                ServerConnection.parse(value)
            }
        }
    }

    @Test
    fun `store validates then persists only the canonical server URL`() {
        val settings = FakeConnectionSettings()
        val store = ServerConnectionStore(settings)

        org.junit.Assert.assertNull(store.loadOrNull())
        assertThrows(IllegalStateException::class.java) { store.load() }
        assertEquals(
            "https://example.test/base/",
            store.update("https://example.test/base").identity,
        )
        assertEquals("https://example.test/base/", settings.value)
        assertEquals("https://example.test/base/", store.load().identity)
        assertEquals("https://example.test/base/", store.loadOrNull()?.identity)
    }

    @Test
    fun `resolved media URL must stay on the configured server`() {
        val connection = ServerConnection.parse("https://example.test/base/")

        assertEquals(
            "https://example.test/files/videos/abc/source.mp4",
            connection.resolveServerUrl("/files/videos/abc/source.mp4").toString(),
        )
        assertThrows(InvalidServerUrlException::class.java) {
            connection.resolveServerUrl("https://other.test/file.mp4")
        }
    }

    private class FakeConnectionSettings : ConnectionSettings {
        var value: String? = null

        override fun readServerUrl(): String? = value

        override fun writeServerUrl(value: String) {
            this.value = value
        }
    }
}
