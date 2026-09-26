package com.sniptube.android.data.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferPolicyTest {
    @Test fun probeDistinguishesMissingSourceFromAuthAndServerFailure() {
        for (status in listOf(404, 401, 403, 500)) {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(status))
            server.start()
            try {
                val error = assertThrows(SourceHttpException::class.java) {
                    SourceTransport { null }.probe(server.url("/source").toString())
                }
                assertEquals(status, error.status)
                assertEquals(1, server.requestCount)
            } finally { server.shutdown() }
        }
        val login = MockWebServer()
        login.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login"))
        login.start()
        try {
            val error = assertThrows(IOException::class.java) {
                SourceTransport { null }.probe(login.url("/source").toString())
            }
            assertFalse("Redirects cannot masquerade as missing source", error is SourceHttpException)
        } finally { login.shutdown() }
    }

    @Test fun wifiLossAndRemovalInterruptAlreadyOpenedResponseBodies() {
        for (remove in listOf(false, true)) {
            val server = MockWebServer()
            val reason = AtomicReference<String?>(null)
            val transport = SourceTransport { reason.get() }
            val bytes = ByteArray(128 * 1024) { it.toByte() }
            server.enqueue(MockResponse().addHeader("ETag", "\"v1\"")
                .setBody(Buffer().write(bytes)))
            server.start()
            try {
                val url = server.url("/source").toString()
                transport.remember(url, SourceIdentity(bytes.size.toLong(), "\"v1\"", null))
                transport.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val body = response.body!!.source()
                    assertTrue(body.read(Buffer(), 1024) > 0)
                    if (remove) transport.forget(url) else reason.set("Wi-Fi lost")
                    try {
                        // Drain already-buffered bytes too; a subsequent upstream read MUST fail.
                        while (body.read(Buffer(), 8192) != -1L) { }
                        fail("Open response continued after policy/ownership loss")
                    } catch (error: IOException) {
                        assertTrue(error.message!!.contains(if (remove) "ownership" else "Wi-Fi"))
                    }
                }
                assertEquals(1, server.requestCount)
            } finally { server.shutdown() }
        }
    }

    @Test fun callbackCapabilitiesAndLostRouteOverrideStaleSynchronousWifi() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true))
        val network = manager.activeNetwork!!
        val wifi = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }
        shadow.setNetworkCapabilities(network, wifi)
        val before = shadow.networkCallbacks.toSet()
        val gate = WifiGate(context) { }
        val callback = (shadow.networkCallbacks.toSet() - before).single()
        assertNull(gate.eligibility())
        callback.onAvailable(network)
        assertNotNull("onAvailable is not capability proof", gate.eligibility())
        callback.onCapabilitiesChanged(network, wifi)
        assertNull(gate.eligibility())
        val cellular = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
        callback.onCapabilitiesChanged(network, cellular)
        assertNotNull("Stale synchronous Wi-Fi must not override callback", gate.eligibility())
        assertNull("Explicit manual consent may use a stable cellular-capable route", gate.manualEligibility())
        callback.onCapabilitiesChanged(network, wifi)
        assertNull(gate.eligibility())
        callback.onLost(network)
        assertNotNull("Lost callback must win over stale activeNetwork", gate.eligibility())
        assertNotNull("Manual consent does not override a lost route", gate.manualEligibility())
        manager.unregisterNetworkCallback(callback)
    }
}
