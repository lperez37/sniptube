package com.sniptube.android.data.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** The effective route, not some available Wi-Fi network, controls local media transfers. */
class WifiGate(context: Context, private val changed: () -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private data class Route(val network: android.net.Network, val capabilities: NetworkCapabilities?)
    @Volatile private var route: Route? = manager.activeNetwork?.let {
        Route(it, manager.getNetworkCapabilities(it))
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            route = Route(network, null)
            changed()
        }
        override fun onLost(network: android.net.Network) {
            if (route?.network == network) route = null
            changed()
        }
        override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
            if (route?.network == network) route = Route(network, NetworkCapabilities(capabilities))
            changed()
        }
    }

    init {
        manager.registerDefaultNetworkCallback(callback)
    }

    fun eligibility(): String? {
        val network = manager.activeNetwork ?: return "Connect to Wi-Fi to sync."
        val observed = route ?: return "Waiting for a known Wi-Fi route."
        if (observed.network != network || observed.capabilities == null) return "Waiting for a known Wi-Fi route."
        val caps = manager.getNetworkCapabilities(network) ?: return "Waiting for a known Wi-Fi route."
        if (manager.activeNetwork != network) return "Waiting for a stable Wi-Fi route."
        // Either view may lead during a callback. Fail closed if either is ineligible;
        // never allow a stale synchronous Wi-Fi snapshot to override cellular callbacks.
        return capabilityReason(observed.capabilities) ?: capabilityReason(caps)
    }

    /** An explicit Sync now may use cellular, but only on a known, stable default route. */
    fun manualEligibility(): String? {
        val network = manager.activeNetwork ?: return "Connect to Wi-Fi or cellular to sync."
        val observed = route ?: return "Waiting for a stable network route."
        if (observed.network != network || observed.capabilities == null) return "Waiting for a stable network route."
        val caps = manager.getNetworkCapabilities(network) ?: return "Waiting for a stable network route."
        if (manager.activeNetwork != network) return "Waiting for a stable network route."
        return if (hasUsableTransport(observed.capabilities) && hasUsableTransport(caps)) null
            else "Connect to Wi-Fi or cellular to sync."
    }

    private fun hasUsableTransport(caps: NetworkCapabilities): Boolean =
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    private fun capabilityReason(caps: NetworkCapabilities): String? {
        return reason(
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        )
    }

    companion object {
        internal fun reason(wifi: Boolean, cellular: Boolean, vpn: Boolean): String? = when {
            // Follow the default VPN route rather than binding an underlying physical network.
            // If it advertises cellular as well, its effective transport is ambiguous.
            vpn && cellular -> "VPN may use cellular; waiting for a Wi-Fi-only VPN route."
            cellular -> "Waiting for Wi-Fi; cellular is not used for offline sync."
            wifi -> null // Metered/LAN-only Wi-Fi and Wi-Fi-only VPN both qualify.
            vpn -> "VPN route cannot be proven Wi-Fi-only; waiting for Wi-Fi."
            else -> "Connect to Wi-Fi to sync."
        }
    }
}
