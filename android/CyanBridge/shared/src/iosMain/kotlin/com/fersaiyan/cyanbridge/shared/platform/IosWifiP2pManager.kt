package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.shared.network.P2pConnectionState
import com.fersaiyan.cyanbridge.shared.network.P2pPeer
import com.fersaiyan.cyanbridge.shared.network.WifiP2pManager
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.NetworkExtension.NEHotspotConfiguration
import platform.NetworkExtension.NEHotspotConfigurationManager
import platform.NetworkExtension.NEHotspotNetwork

// ── iOS local Wi-Fi manager using NEHotspotConfiguration ──

/**
 * iOS local Wi-Fi manager using NEHotspotConfiguration.
 * iOS does not support Android-style Wi-Fi Direct peer discovery. This adapter
 * joins a glasses-owned hotspot and relies on the BLE-reported device IP.
 */
internal class IosWifiP2pManager : WifiP2pManager {
    private val _isAvailable = MutableStateFlow(true)
    override val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()
    override val supportsTrueWifiDirect: Boolean = false

    private val _connectionState = MutableStateFlow(P2pConnectionState.IDLE)
    override val connectionState: Flow<P2pConnectionState> = _connectionState.asStateFlow()

    private val _glassesIpAddress = MutableStateFlow<String?>(null)
    override val glassesIpAddress: StateFlow<String?> = _glassesIpAddress.asStateFlow()
    private var connectedSsid: String? = null

    override fun discoverPeers(): Flow<P2pPeer> = flow {
        PlatformLogger.i(TAG, "Wi-Fi discovery on iOS uses NEHotspotConfiguration")
        // iOS doesn't support Wi-Fi Direct peer discovery like Android.
        // The glasses expose a Wi-Fi hotspot that the phone joins via NEHotspotConfiguration.
        // Discovery is handled by BLE scanning instead.
    }

    override fun stopDiscovery() {
        PlatformLogger.i(TAG, "Stopping Wi-Fi discovery")
    }

    override suspend fun connect(peerAddress: String) {
        val separator = peerAddress.indexOf('|')
        val ssid = if (separator >= 0) peerAddress.substring(0, separator) else peerAddress
        val passphrase = if (separator >= 0) peerAddress.substring(separator + 1) else ""
        connectToHotspot(ssid, passphrase)
    }

    suspend fun connectToHotspot(ssidValue: String, passphrase: String) {
        val ssid = ssidValue.trim()
        require(ssid.isNotEmpty()) { "An iOS hotspot SSID is required" }
        PlatformLogger.i(TAG, "Preparing to join Wi-Fi hotspot: $ssid")
        _connectionState.value = P2pConnectionState.CONNECTING
        try {
            if (currentNetworkSsid() == ssid) {
                connectedSsid = ssid
                _connectionState.value = P2pConnectionState.CONNECTED
                PlatformLogger.i(TAG, "Already connected to Wi-Fi hotspot: $ssid")
                return
            }

            val configuration = if (passphrase.isBlank()) {
                NEHotspotConfiguration(sSID = ssid)
            } else {
                NEHotspotConfiguration(sSID = ssid, passphrase = passphrase, isWEP = false)
            }
            configuration.joinOnce = true
            val applyError = applyConfiguration(configuration)
            if (applyError != null) {
                PlatformLogger.w(TAG, "iOS hotspot configuration was not accepted: $applyError")
            }

            check(waitForCurrentNetwork(ssid)) {
                if (applyError == null) {
                    "iOS accepted the hotspot request, but is not connected to $ssid. " +
                        "Open Settings > Wi-Fi and join the glasses hotspot, then retry."
                } else {
                    "iOS could not join $ssid ($applyError). " +
                        "Open Settings > Wi-Fi and join the glasses hotspot, then retry."
                }
            }
            connectedSsid = ssid
            _connectionState.value = P2pConnectionState.CONNECTED
            PlatformLogger.i(TAG, "Wi-Fi hotspot connected: $ssid")
        } catch (error: Exception) {
            _connectionState.value = P2pConnectionState.ERROR
            PlatformLogger.e(TAG, "Wi-Fi hotspot connection failed", error)
            throw error
        }
    }

    override suspend fun disconnect() {
        PlatformLogger.i(TAG, "Disconnecting from Wi-Fi hotspot")
        _connectionState.value = P2pConnectionState.DISCONNECTING
        connectedSsid?.let { ssid ->
            NEHotspotConfigurationManager.sharedManager.removeConfigurationForSSID(ssid)
        }
        connectedSsid = null
        _connectionState.value = P2pConnectionState.IDLE
    }

    override fun isConnected(): Boolean = _connectionState.value == P2pConnectionState.CONNECTED

    override fun setGlassesIpAddress(ip: String) {
        PlatformLogger.i(TAG, "Glasses IP address set: $ip")
        _glassesIpAddress.value = ip
    }

    override suspend fun bindToP2pNetwork(): Boolean {
        // iOS has no process-level equivalent of Android's bindProcessToNetwork().
        // If the SSID is known, verify it; otherwise the media.config request is
        // the end-to-end readiness probe for an already-connected network.
        val expectedSsid = connectedSsid ?: return true
        if (currentNetworkSsid() != expectedSsid) {
            _connectionState.value = P2pConnectionState.IDLE
            return false
        }
        return true
    }

    override fun cancelConnection() {
        _connectionState.value = P2pConnectionState.IDLE
    }

    suspend fun hasCurrentWifiConnection(): Boolean = currentNetworkSsid() != null

    /**
     * Asks iOS to join the glasses hotspot. Returns null when iOS accepted the
     * request, or the error text when it did not (for example, builds signed
     * without the Hotspot entitlement). Callers verify the link over HTTP, which
     * works without the Wi-Fi information entitlement.
     */
    suspend fun requestHotspotJoin(ssidValue: String, passphrase: String): String? {
        val ssid = ssidValue.trim()
        if (ssid.isEmpty()) return "Missing hotspot SSID"
        _connectionState.value = P2pConnectionState.CONNECTING
        val configuration = if (passphrase.isBlank()) {
            NEHotspotConfiguration(sSID = ssid)
        } else {
            NEHotspotConfiguration(sSID = ssid, passphrase = passphrase, isWEP = false)
        }
        configuration.joinOnce = true
        return applyConfiguration(configuration).also { error ->
            connectedSsid = if (error == null) ssid else null
        }
    }

    fun markConnected() {
        _connectionState.value = P2pConnectionState.CONNECTED
    }

    private suspend fun applyConfiguration(configuration: NEHotspotConfiguration): String? =
        suspendCancellableCoroutine { continuation ->
            NEHotspotConfigurationManager.sharedManager.applyConfiguration(configuration) { error ->
                if (continuation.isActive) {
                    continuation.resume(error?.localizedDescription)
                }
            }
        }

    private suspend fun waitForCurrentNetwork(expectedSsid: String): Boolean {
        repeat(20) { attempt ->
            if (currentNetworkSsid() == expectedSsid) return true
            if (attempt < 19) delay(1_000L)
        }
        return false
    }

    private suspend fun currentNetworkSsid(): String? =
        suspendCancellableCoroutine { continuation ->
            NEHotspotNetwork.fetchCurrentWithCompletionHandler { network ->
                if (continuation.isActive) {
                    continuation.resume(network?.SSID)
                }
            }
        }

    companion object {
        private const val TAG = "IosWifiP2p"
    }
}
