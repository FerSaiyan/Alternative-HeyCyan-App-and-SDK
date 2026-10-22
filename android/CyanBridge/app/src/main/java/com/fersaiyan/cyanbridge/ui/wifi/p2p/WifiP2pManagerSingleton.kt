package com.fersaiyan.cyanbridge.ui.wifi.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.heycyan.core.connectivity.p2p.WifiP2pConnectionState
import com.heycyan.core.connectivity.p2p.WifiP2pRetryState
import com.oudmon.ble.base.communication.LargeDataHandler
import java.util.concurrent.CopyOnWriteArrayList

class WifiP2pManagerSingleton private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WifiP2pManagerSingleton"
        @Volatile
        private var instance: WifiP2pManagerSingleton? = null

        /** Last known P2P group owner IP, cached for live preview and other features. */
        var lastGroupOwnerIp: String? = null
            internal set

        fun getInstance(context: Context): WifiP2pManagerSingleton {
            return instance ?: synchronized(this) {
                instance ?: WifiP2pManagerSingleton(context.applicationContext).also { instance = it }
            }
        }
    }

    private val wifiP2pManager: WifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var wifiP2pDevice: WifiP2pDevice? = null
    private var connectionGroupOwnerIntent: Int? = null
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = CopyOnWriteArrayList<WifiP2pCallback>()

    private val discoveryTimeoutMs = 16_000L
    private val connectTimeoutMs = 5_000L
    private val delayedPeerDiscoveryRestart = Runnable {
        startPeerDiscovery(allowDeviceResetOnTimeout = allowDeviceResetOnDiscoveryTimeout)
    }

    private val connectionState = WifiP2pConnectionState()
    private val retryState = WifiP2pRetryState(1, 1)
    @Volatile private var p2pOperationsEnabled = true
    @Volatile private var allowDeviceResetOnDiscoveryTimeout = true

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private var receiver: BroadcastReceiver? = null

    private fun hasWifiP2pPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun canUseP2p(): Boolean = hasWifiP2pPermission()

    init {
        Log.d(TAG, "WifiP2pManagerSingleton initialized")
        initP2P()
    }

    private fun initP2P() {
        Log.d(TAG, "Initializing P2P...")
        wifiP2pChannel?.close()
        wifiP2pChannel = wifiP2pManager.initialize(context, Looper.getMainLooper(), object : WifiP2pManager.ChannelListener {
            override fun onChannelDisconnected() {
                Log.d(TAG, "wifiP2pChannel disconnect")
            }
        })
        Log.d(TAG, "P2P initialized, channel: ${wifiP2pChannel != null}")
    }

    fun addCallback(callback: WifiP2pCallback) {
        if (!callbacks.contains(callback)) {
            callbacks.add(callback)
        }
    }

    fun removeCallback(callback: WifiP2pCallback) {
        callbacks.remove(callback)
    }

    fun registerReceiver() {
        receiver = WifiP2pBroadcastReceiver(this)
        // Use compat API to avoid calling the API 33+ registerReceiver overload on older devices.
        ContextCompat.registerReceiver(
            context,
            receiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregisterReceiver() {
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startPeerDiscovery(allowDeviceResetOnTimeout: Boolean = true) {
        allowDeviceResetOnDiscoveryTimeout = allowDeviceResetOnTimeout
        if (!p2pOperationsEnabled) {
            Log.d(TAG, "Ignoring peer discovery while P2P operations are stopped")
            return
        }
        if (!hasWifiP2pPermission()) {
            Log.w(TAG, "Cannot start P2P discovery: required permission is not granted")
            callbacks.forEach { it.onPeerDiscoveryFailed(WifiP2pManager.ERROR) }
            return
        }
        handler.postDelayed(discoveryTimeOut, discoveryTimeoutMs)
        wifiP2pManager.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Peer discovery started successfully")
                callbacks.forEach { it.onPeerDiscoveryStarted() }
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Peer discovery failed: $reason")
                if (!p2pOperationsEnabled) {
                    Log.d(TAG, "Ignoring discovery failure after P2P operations stopped")
                    return
                }
                // Mirror vendor behavior: reschedule the internal timeout and stop discovery
                // so we can retry in a stable way.
                handler.removeCallbacks(discoveryTimeOut)
                handler.postDelayed(discoveryTimeOut, 2000L)
                callbacks.forEach { it.onPeerDiscoveryFailed(reason) }
                discoverPeersStable()
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun discoverPeersStable() {
        if (!hasWifiP2pPermission()) {
            Log.w(TAG, "Cannot stop P2P discovery: required permission is not granted")
            return
        }
        wifiP2pManager.stopPeerDiscovery(wifiP2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "discoverPeersStable success")
            }

            override fun onFailure(reason: Int) {
                Log.d(TAG, "discoverPeersStable onFailure $reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: WifiP2pDevice, groupOwnerIntent: Int? = null) {
        require(groupOwnerIntent == null || groupOwnerIntent in 0..15) {
            "Wi-Fi Direct group owner intent must be between 0 and 15"
        }
        if (!p2pOperationsEnabled) {
            Log.d(TAG, "Ignoring P2P connect while operations are stopped")
            return
        }
        if (!hasWifiP2pPermission()) {
            Log.w(TAG, "Cannot connect P2P: required permission is not granted")
            callbacks.forEach { it.onConnectRequestFailed(WifiP2pManager.ERROR) }
            return
        }
        // Once we decide to connect, stop peer discovery timeout tracking.
        resetPeerDiscovery()

        if (connectionState.isConnecting()) {
            Log.d(TAG, "P2P is connecting, no connection call back")
            callbacks.forEach { it.connecting() }
            return
        }

        if (connectionState.isConnected()) {
            Log.d(TAG, "P2P is already connected, return directly")
            return
        }

        // Arm internal connect timeout (vendor app uses ~5s).
        handler.postDelayed(connectTimeOut, connectTimeoutMs)

        wifiP2pDevice = device
        connectionGroupOwnerIntent = groupOwnerIntent
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Match decompiled vendor app: WPS PBC.
            wps.setup = 0
            groupOwnerIntent?.let { this.groupOwnerIntent = it }
        }
        if (groupOwnerIntent != null) {
            Log.i(TAG, "Connecting with groupOwnerIntent=$groupOwnerIntent")
        }

        connectionState.setConnecting(true)
        Log.d(TAG, "Already connecting device: ${device.deviceName}---${device.deviceAddress}")

        wifiP2pManager.connect(wifiP2pChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connect request sent successfully")
                callbacks.forEach { it.onConnectRequestSent() }
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Connect request failed: $reason")
                connectionState.markConnectRequestFailed()
                callbacks.forEach { it.onConnectRequestFailed(reason) }
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun cancelP2pConnection() {
        if (!hasWifiP2pPermission()) {
            Log.w(TAG, "Cannot cancel P2P connection: required permission is not granted")
            return
        }
        try {
            initP2P()
            wifiP2pManager.cancelConnect(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Cancel connect successful")
                    handler.removeCallbacks(connectTimeOut)
                    callbacks.forEach { it.cancelConnect() }
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Cancel connect failed: $reason")
                    handler.removeCallbacks(connectTimeOut)
                    callbacks.forEach { it.cancelConnectFail(reason) }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error canceling P2P connection", e)
        }
    }

    fun resetDeviceP2p() {
        Log.d(TAG, "resetDeviceP2p called - sending glassesControl[2,1,15]")
        try {
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, 0x0F)
            ) { cmdType, resp ->
                Log.d(
                    TAG,
                    "resetDeviceP2p callback: cmdType=$cmdType, respType=${resp.dataType}, error=${resp.errorCode}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send resetDeviceP2p command to glasses", e)
        }
    }

    fun resetFailCount() {
        p2pOperationsEnabled = true
        retryState.reset()
        connectionState.reset()
        cancelPendingRetries()
    }

    /** Stop delayed discovery/connect work without discarding the current group state. */
    fun cancelPendingRetries() {
        handler.removeCallbacks(discoveryTimeOut)
        handler.removeCallbacks(connectTimeOut)
        handler.removeCallbacks(delayedPeerDiscoveryRestart)
    }

    /** Stop a completed flow from rearming discovery through late framework callbacks. */
    fun stopP2pOperations() {
        p2pOperationsEnabled = false
        cancelPendingRetries()
        discoverPeersStable()
    }

    fun isConnecting(): Boolean = connectionState.isConnecting()
    fun isConnected(): Boolean = connectionState.isConnected()

    fun resetPeerDiscovery() {
        handler.removeCallbacks(discoveryTimeOut)
    }

    fun restartPeerDiscovery() {
        if (!p2pOperationsEnabled) {
            Log.d(TAG, "Ignoring peer-discovery restart while operations are stopped")
            return
        }
        Log.d(TAG, "restartPeerDiscovery: stopping current discovery and reinitializing")
        cancelPendingRetries()
        initP2P()
        // Stabilization delay: give the P2P stack time to settle after reset
        // before starting discovery. Prevents "Connect request failed: 2" when
        // the glasses peer appears immediately after restart.
        handler.postDelayed(delayedPeerDiscoveryRestart, 1500L)
    }

    fun setConnect(connected: Boolean) {
        connectionState.setConnected(connected)
    }

    @SuppressLint("MissingPermission")
    fun requestPeers() {
        if (!hasWifiP2pPermission()) {
            Log.w(TAG, "Cannot request P2P peers: required permission is not granted")
            return
        }
        wifiP2pChannel?.let { channel ->
            wifiP2pManager.requestPeers(channel, object : WifiP2pManager.PeerListListener {
                override fun onPeersAvailable(peers: WifiP2pDeviceList) {
                    val deviceList = peers.deviceList
                    Log.d(TAG, "Peers available: ${deviceList.size} devices")
                    callbacks.forEach { it.onPeersChanged(deviceList) }
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    fun requestConnectionInfo() {
        if (!hasWifiP2pPermission()) {
            Log.w(TAG, "Cannot request P2P connection info: required permission is not granted")
            return
        }
        wifiP2pChannel?.let { channel ->
            wifiP2pManager.requestConnectionInfo(channel, object : WifiP2pManager.ConnectionInfoListener {
                override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
                    Log.d(TAG, "Connection info available: groupFormed=${info.groupFormed}, isGroupOwner=${info.isGroupOwner}")
                    // Forward to the outer singleton; avoid recursive
                    // calls to this anonymous listener implementation.
                    this@WifiP2pManagerSingleton.onConnectionInfoAvailable(info)
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    fun createGroup(onResult: (Boolean) -> Unit) {
        if (!hasWifiP2pPermission()) {
            Log.w(TAG, "Cannot create P2P group: required permission is not granted")
            onResult(false)
            return
        }
        wifiP2pChannel?.let { channel ->
            wifiP2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "P2P group created successfully")
                    onResult(true)
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to create P2P group: $reason")
                    onResult(false)
                }
            })
        } ?: run {
            Log.e(TAG, "P2P channel not initialized")
            onResult(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun removeGroup(onResult: (Boolean) -> Unit) {
        if (!hasWifiP2pPermission()) {
            Log.w(TAG, "Cannot remove P2P group: required permission is not granted")
            onResult(false)
            return
        }
        wifiP2pChannel?.let { channel ->
            wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "P2P group removed successfully")
                    onResult(true)
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to remove P2P group: $reason")
                    onResult(false)
                }
            })
        } ?: run {
            Log.e(TAG, "P2p channel not initialized")
            onResult(false)
        }
    }

    // Internal methods for handling P2P events
    internal fun onWifiP2pEnabled() {
        callbacks.forEach { it.onWifiP2pEnabled() }
    }

    internal fun onWifiP2pDisabled() {
        callbacks.forEach { it.onWifiP2pDisabled() }
    }

    internal fun onPeersChanged(peers: Collection<WifiP2pDevice>) {
        callbacks.forEach { it.onPeersChanged(peers) }
    }

    internal fun onThisDeviceChanged(device: WifiP2pDevice) {
        callbacks.forEach { it.onThisDeviceChanged(device) }
    }

    internal fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        connectionState.markConnectionInfoAvailable(info.groupFormed)
        handler.removeCallbacks(connectTimeOut)
        lastGroupOwnerIp = info.groupOwnerAddress?.hostAddress
        callbacks.forEach { it.onConnected(info) }
    }

    internal fun onDisconnected() {
        connectionState.markDisconnected()
        handler.removeCallbacks(connectTimeOut)
        lastGroupOwnerIp = null
        callbacks.forEach { it.onDisconnected() }
    }

    // Timeout handlers
    private val discoveryTimeOut = object : Runnable {
        override fun run() {
            if (!p2pOperationsEnabled) return
            Log.d(TAG, "Internal scan retry connection: ${retryState.discoveryRetryCount()}")
            if (!allowDeviceResetOnDiscoveryTimeout) {
                Log.d(TAG, "Discovery timeout reached with device reset disabled")
                return
            }
            if (retryState.shouldRetryDiscovery()) {
                Log.d(TAG, "Internal scan retry connection once")
                resetDeviceP2p()
                initP2P()
                startPeerDiscovery(allowDeviceResetOnTimeout = true)
            }
        }
    }

    private val connectTimeOut = object : Runnable {
        override fun run() {
            connectionState.setConnecting(false)
            if (!p2pOperationsEnabled) return
            if (retryState.shouldRetryConnect()) {
                wifiP2pDevice?.let { device ->
                    Log.d(TAG, "Internal connection retry connection once")
                    connectToDevice(device, connectionGroupOwnerIntent)
                }
            } else {
                Log.d(TAG, "Do not reconnect, wait for external timeout")
                callbacks.forEach { it.retryAlsoFailed() }
            }
        }
    }

    interface WifiP2pCallback : com.heycyan.core.connectivity.p2p.WifiP2pCallback
}
