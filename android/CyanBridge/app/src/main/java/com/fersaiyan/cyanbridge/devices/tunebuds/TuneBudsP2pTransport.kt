package com.fersaiyan.cyanbridge.devices.tunebuds

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** AB Mate P2P only: no HeyCyan SDK resets, static HTTP target, or process binding. */
internal class TuneBudsP2pTransport(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val _isActive = MutableStateFlow(false)
    val isActive = _isActive.asStateFlow()

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, ssid: String): Network = withContext(Dispatchers.Main) {
        val manager = manager ?: throw IOException("Wi-Fi Direct is not available on this phone")
        val current = manager.initialize(context, Looper.getMainLooper()) { _isActive.value = false }
            ?: throw IOException("TuneBuds P2P channel initialization failed")
        channel = current
        val formed = CompletableDeferred<String>()
        var connectIssued = false
        fun checkGroup() {
            if (channel !== current || !connectIssued) return
            manager.requestConnectionInfo(current) { info ->
                if (channel !== current) return@requestConnectionInfo
                if (!info.groupFormed) {
                    if (_isActive.value) _isActive.value = false
                    return@requestConnectionInfo
                }
                manager.requestGroupInfo(current) { group ->
                    if (channel !== current || group == null) return@requestGroupInfo
                    val devices = listOfNotNull(group.owner) + group.clientList
                    if (devices.none { matchesTuneBudsP2pPeer(address, ssid, it.deviceAddress, it.deviceName) }) return@requestGroupInfo
                    val interfaceName = group.`interface`?.takeIf(String::isNotBlank) ?: return@requestGroupInfo
                    _isActive.value = true
                    formed.complete(interfaceName)
                    Log.i("TuneBudsP2p", "Group ${group.`interface`} owner=${info.groupOwnerAddress}; HTTP target comes from E7")
                }
            }
        }
        fun requestPeers() {
            if (channel !== current || connectIssued) return
            manager.requestPeers(current) { peers ->
                if (channel !== current || connectIssued) return@requestPeers
                val target: WifiP2pDevice = peers.deviceList.firstOrNull {
                    matchesTuneBudsP2pPeer(address, ssid, it.deviceAddress, it.deviceName)
                } ?: return@requestPeers
                connectIssued = true
                manager.connect(current, WifiP2pConfig().apply {
                    deviceAddress = target.deviceAddress
                    wps.setup = WpsInfo.PBC
                    groupOwnerIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 15 else 0
                }, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { checkGroup() }
                    override fun onFailure(reason: Int) {
                        formed.completeExceptionally(IOException("TuneBuds P2P connect failed: $reason"))
                    }
                })
            }
        }
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> checkGroup()
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> if (
                        intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    ) {
                        _isActive.value = false
                        formed.completeExceptionally(IOException("Wi-Fi Direct was disabled"))
                    }
                }
            }
        }
        receiver = listener
        ContextCompat.registerReceiver(context, listener, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        withTimeout(120_000L) {
            val interfaceName = coroutineScope {
                val discovery = launch {
                    while (!formed.isCompleted) {
                        if (!connectIssued) manager.discoverPeers(current, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { requestPeers() }
                            override fun onFailure(reason: Int) {
                                // BUSY can mean discovery is already in progress; keep awaiting peers.
                                if (reason != WifiP2pManager.BUSY) formed.completeExceptionally(IOException("TuneBuds P2P discovery failed: $reason"))
                            }
                        })
                        checkGroup()
                        delay(2_000L)
                    }
                }
                try { formed.await() } finally { discovery.cancel() }
            }
            withTimeout(15_000L) route@ {
                while (true) {
                    if (!_isActive.value) throw IOException("TuneBuds P2P disconnected before routing was ready")
                    val network = connectivity.allNetworks.firstOrNull {
                        connectivity.getLinkProperties(it)?.interfaceName == interfaceName
                    }
                    if (network != null) return@route network
                    delay(250L)
                }
                @Suppress("UNREACHABLE_CODE")
                throw IOException("No routable TuneBuds P2P network")
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun close() = withContext(Dispatchers.Main) {
        _isActive.value = false
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        val current = channel ?: return@withContext
        channel = null // Ignore any late Android callbacks from this attempt.
        try {
            manager?.stopPeerDiscovery(current, null)
            manager?.cancelConnect(current, null)
            val removed = CompletableDeferred<Unit>()
            manager?.removeGroup(current, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { removed.complete(Unit) }
                override fun onFailure(reason: Int) { removed.complete(Unit) }
            })
            if (withTimeoutOrNull(5_000L) { removed.await(); true } != true) {
                throw IOException("TuneBuds P2P group teardown timed out")
            }
        } finally { current.close() }
    }
}

internal fun matchesTuneBudsP2pPeer(address: String, ssid: String, peerAddress: String?, peerName: String?): Boolean =
    address.equals(peerAddress, ignoreCase = true) || ssid.equals(peerName, ignoreCase = true)
