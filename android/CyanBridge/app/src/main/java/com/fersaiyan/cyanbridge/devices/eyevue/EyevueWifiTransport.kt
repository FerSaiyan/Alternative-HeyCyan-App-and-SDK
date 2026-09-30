package com.fersaiyan.cyanbridge.devices.eyevue

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.NetworkInfo
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class EyevueWifiMode {
    AP,
    P2P,
}

data class EyevueWifiConnection(
    val mode: EyevueWifiMode,
    val baseIp: String,
    /** Present when the OS surfaced the P2P/AP link for binding; null means default route. */
    val network: Network?,
)

/** Connects Eyevue's AP or Wi-Fi Direct network and binds process traffic to it. */
class EyevueWifiTransport(
    context: Context,
) {
    companion object {
        private const val TAG = "EyevueWifi"
        private const val CONNECT_TIMEOUT_MS = 60_000L
        private const val P2P_ROUTE_TIMEOUT_MS = 15_000L
    }

    private val context = context.applicationContext
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val p2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager

    private var apNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver? = null
    private var p2pContinuation: CancellableContinuation<WifiP2pInfo>? = null
    private var p2pTargetSsid: String? = null
    private var p2pConnecting = false
    private var boundNetwork: Network? = null

    @SuppressLint("MissingPermission")
    suspend fun connect(
        mode: EyevueWifiMode,
        ssid: String,
        password: String,
        baseIp: String,
    ): Result<EyevueWifiConnection> {
        if (!hasWifiPermission()) {
            return Result.failure(SecurityException("Nearby Wi-Fi or location permission is required"))
        }
        if (ssid.isBlank() || baseIp.isBlank()) {
            return Result.failure(IllegalArgumentException("Eyevue Wi-Fi SSID or IP is empty"))
        }

        disconnect()
        return try {
            val network = when (mode) {
                EyevueWifiMode.AP -> connectAp(ssid, password)
                EyevueWifiMode.P2P -> connectP2p(ssid, baseIp)
            }
            Result.success(EyevueWifiConnection(mode, baseIp, network))
        } catch (error: TimeoutCancellationException) {
            disconnect()
            Result.failure(IOException("Timed out connecting to Eyevue Wi-Fi", error))
        } catch (error: Throwable) {
            disconnect()
            Result.failure(error)
        }
    }

    fun disconnect() {
        apNetworkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        apNetworkCallback = null

        p2pContinuation?.let { continuation ->
            p2pContinuation = null
            if (continuation.isActive) {
                continuation.resumeWithException(IOException("Eyevue Wi-Fi connection cancelled"))
            }
        }
        p2pTargetSsid = null
        p2pConnecting = false
        p2pReceiver?.let { receiver ->
            runCatching { context.unregisterReceiver(receiver) }
        }
        p2pReceiver = null
        p2pChannel?.let { channel ->
            runCatching { p2pManager.stopPeerDiscovery(channel, null) }
            runCatching { p2pManager.cancelConnect(channel, null) }
            runCatching { p2pManager.removeGroup(channel, null) }
        }
        p2pChannel = null

        if (boundNetwork != null) {
            runCatching { connectivityManager.bindProcessToNetwork(null) }
            boundNetwork = null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAp(ssid: String, password: String): Network {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("Eyevue AP transport requires Android 10 or newer")
        }
        val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        return withTimeout(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    private var resumed = false

                    override fun onAvailable(network: Network) {
                        if (resumed) return
                        resumed = true
                        apNetworkCallback = this
                        boundNetwork = network
                        connectivityManager.bindProcessToNetwork(network)
                        Log.i(TAG, "Eyevue AP network available: $ssid")
                        continuation.resume(network)
                    }

                    override fun onUnavailable() {
                        if (resumed) return
                        resumed = true
                        if (continuation.isActive) {
                            continuation.resumeWithException(IOException("Eyevue AP network unavailable"))
                        }
                    }

                    override fun onLost(network: Network) {
                        if (boundNetwork == network) {
                            runCatching { connectivityManager.bindProcessToNetwork(null) }
                            boundNetwork = null
                            if (continuation.isActive) {
                                continuation.resumeWithException(IOException("Eyevue AP network was lost"))
                            }
                        }
                    }
                }
                apNetworkCallback = callback
                continuation.invokeOnCancellation {
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                    if (boundNetwork != null) {
                        runCatching { connectivityManager.bindProcessToNetwork(null) }
                        boundNetwork = null
                    }
                }
                try {
                    connectivityManager.requestNetwork(request, callback)
                } catch (error: Throwable) {
                    apNetworkCallback = null
                    continuation.resumeWithException(error)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectP2p(ssid: String, baseIp: String): Network? {
        val manager = p2pManager
        val channel = manager.initialize(context, Looper.getMainLooper(), null)
            ?: throw IOException("Wi-Fi Direct channel initialization failed")
        p2pChannel = channel
        p2pTargetSsid = ssid
        p2pConnecting = false

        val info = withTimeout(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<WifiP2pInfo> { continuation ->
                p2pContinuation = continuation
                val filter = IntentFilter().apply {
                    addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context?, intent: Intent?) {
                        when (intent?.action) {
                            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestP2pPeers()
                            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                                val networkInfo = intent.getParcelableExtra<NetworkInfo>(
                                    WifiP2pManager.EXTRA_NETWORK_INFO,
                                )
                                if (networkInfo?.isConnected == true) {
                                    manager.requestConnectionInfo(channel) { connectionInfo ->
                                        if (connectionInfo.groupFormed && continuation.isActive) {
                                            p2pContinuation = null
                                            continuation.resume(connectionInfo)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                p2pReceiver = receiver
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                continuation.invokeOnCancellation { disconnect() }
                requestP2pPeers()
                manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "Eyevue P2P discovery started for SSID=$ssid")
                    }

                    override fun onFailure(reason: Int) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IOException("Eyevue P2P discovery failed: $reason"),
                            )
                        }
                    }
                })
            }
        }

        val network = awaitP2pRoute(info, ssid, baseIp)
        if (network != null) {
            boundNetwork = network
            connectivityManager.bindProcessToNetwork(network)
        } else {
            Log.i(TAG, "Eyevue P2P proceeding on the default route (no bindable Network surfaced)")
        }
        return network
    }

    @SuppressLint("MissingPermission")
    private fun requestP2pPeers() {
        val channel = p2pChannel ?: return
        p2pManager.requestPeers(channel) { peers ->
            Log.i(
                TAG,
                "Eyevue P2P peers target=${p2pTargetSsid.orEmpty()} peers=" +
                    peers.deviceList.joinToString { "${it.deviceName.orEmpty()}@${it.deviceAddress}" },
            )
            val target = findTargetPeer(peers)
            if (target == null || p2pConnecting) return@requestPeers
            p2pConnecting = true
            val config = WifiP2pConfig().apply {
                deviceAddress = target.deviceAddress
                wps.setup = 0
            }
            p2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Eyevue P2P connect request sent: ${target.deviceName}")
                }

                override fun onFailure(reason: Int) {
                    p2pConnecting = false
                    Log.w(TAG, "Eyevue P2P connect failed: $reason")
                }
            })
        }
    }

    private fun findTargetPeer(peers: WifiP2pDeviceList): WifiP2pDevice? {
        val target = p2pTargetSsid?.trim().orEmpty()
        if (target.isBlank()) return null
        peers.deviceList.firstOrNull { peer ->
            matchesEyevuePeerName(target, peer.deviceName)
        }?.let { return it }

        // The BLE-reported SSID and WifiP2pDevice.deviceName are separate vendor fields and
        // are not guaranteed to match. Falling back is safe only when discovery exposes one
        // candidate; never connect to an arbitrary peer when multiple devices are present.
        return peers.deviceList.singleOrNull()?.also { peer ->
            Log.w(
                TAG,
                "Eyevue P2P SSID '$target' did not match deviceName '${peer.deviceName}'; " +
                    "using the only discovered peer ${peer.deviceAddress}",
            )
        }
    }

    /**
     * Matches the vendor flow: once the P2P group is formed, the glasses HTTP server
     * is probed directly (baseIp port 80) instead of waiting for the OS to surface a
     * Network object, which Samsung builds often never do for P2P links. A matching
     * Network is bound opportunistically when one appears; reachability is the gate.
     */
    private suspend fun awaitP2pRoute(info: WifiP2pInfo, ssid: String, baseIp: String): Network? =
        withContext(Dispatchers.IO) {
            val prefix = "192.168.49."
            val deadline = System.currentTimeMillis() + P2P_ROUTE_TIMEOUT_MS
            var reachable = false
            var boundCandidate: Network? = null
            while (System.currentTimeMillis() < deadline) {
                if (!reachable && isHostReachable(baseIp, 80, 1_500)) {
                    Log.i(TAG, "Eyevue P2P host reachable: $baseIp:80")
                    reachable = true
                }
                if (boundCandidate == null) {
                    boundCandidate = connectivityManager.allNetworks.firstOrNull { network ->
                        val capabilities = connectivityManager.getNetworkCapabilities(network)
                            ?: return@firstOrNull false
                        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            return@firstOrNull false
                        }
                        val properties = connectivityManager.getLinkProperties(network)
                        val interfaceName = properties?.interfaceName.orEmpty()
                        val addresses = properties?.linkAddresses.orEmpty()
                            .mapNotNull { it.address.hostAddress }
                        interfaceName.contains("p2p", ignoreCase = true) ||
                            interfaceName.contains("wfd", ignoreCase = true) ||
                            addresses.any { it.startsWith(prefix) }
                    }
                }
                if (reachable) return@withContext boundCandidate
                delay(500)
            }
            if (reachable) return@withContext boundCandidate
            throw IOException(
                "Eyevue P2P glasses not reachable at $baseIp:80 for $ssid " +
                    "(groupOwner=${info.groupOwnerAddress?.hostAddress})",
            )
        }

    private fun isHostReachable(host: String, port: Int, timeoutMs: Int): Boolean {
        if (host.isBlank()) return false
        try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                return true
            }
        } catch (_: Exception) {
            return false
        }
    }

    private fun hasWifiPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
