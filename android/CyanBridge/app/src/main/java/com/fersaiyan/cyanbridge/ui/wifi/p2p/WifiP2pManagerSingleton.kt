package com.fersaiyan.cyanbridge.ui.wifi.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.MacAddress
import android.net.wifi.WpsInfo
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
    private val connectTimeoutMs = 30_000L
    private val discoveryTimeoutRetryDelayMs = 1_000L
    private val maxDiscoveryTimeoutRetries = 2
    private val busyDiscoveryRetryDelayMs = 1_000L
    private val maxBusyDiscoveryRetries = 3
    private val busyConnectRetryDelayMs = 1_000L
    private var busyDiscoveryRetryCount = 0
    private var discoveryTimeoutRetryCount = 0
    private var connectionGroupOperatingBand = WifiP2pConfig.GROUP_OWNER_BAND_AUTO
    private var triedSamsungFiveGhzFallback = false
    private val delayedPeerDiscoveryRestart = Runnable {
        startPeerDiscoveryInternal(
            allowDeviceResetOnTimeout = allowDeviceResetOnDiscoveryTimeout,
            resetTimeoutRetryCount = false,
        )
    }
    private val busyConnectRetry = Runnable {
        wifiP2pDevice?.let { device ->
            connectToDevice(device, connectionGroupOwnerIntent)
        }
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
        if (receiver != null) {
            Log.d(TAG, "P2P broadcast receiver is already registered")
            return
        }
        val p2pReceiver = WifiP2pBroadcastReceiver(this)
        // Use compat API to avoid calling the API 33+ registerReceiver overload on older devices.
        // Wi-Fi P2P broadcasts can originate from the privileged Wi-Fi process, so this
        // receiver must be exported to receive them on recent Android versions.
        ContextCompat.registerReceiver(
            context,
            p2pReceiver,
            intentFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiver = p2pReceiver
    }

    fun unregisterReceiver() {
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        } finally {
            receiver = null
        }
    }

    @SuppressLint("MissingPermission")
    fun startPeerDiscovery(allowDeviceResetOnTimeout: Boolean = true) {
        startPeerDiscoveryInternal(allowDeviceResetOnTimeout, resetTimeoutRetryCount = true)
    }

    @SuppressLint("MissingPermission")
    private fun startPeerDiscoveryInternal(
        allowDeviceResetOnTimeout: Boolean,
        resetTimeoutRetryCount: Boolean,
    ) {
        allowDeviceResetOnDiscoveryTimeout = allowDeviceResetOnTimeout
        if (resetTimeoutRetryCount) {
            discoveryTimeoutRetryCount = 0
        }
        if (!p2pOperationsEnabled) {
            Log.d(TAG, "Ignoring peer discovery while P2P operations are stopped")
            return
        }
        if (!hasWifiP2pPermission()) {
            Log.w(TAG, "Cannot start P2P discovery: required permission is not granted")
            callbacks.forEach { it.onPeerDiscoveryFailed(WifiP2pManager.ERROR) }
            return
        }
        handler.removeCallbacks(discoveryTimeOut)
        handler.postDelayed(discoveryTimeOut, discoveryTimeoutMs)
        wifiP2pManager.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                busyDiscoveryRetryCount = 0
                Log.d(TAG, "Peer discovery started successfully")
                callbacks.forEach { it.onPeerDiscoveryStarted() }
                requestPeers()
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Peer discovery failed: $reason")
                if (!p2pOperationsEnabled) {
                    Log.d(TAG, "Ignoring discovery failure after P2P operations stopped")
                    return
                }
                if (
                    reason == WifiP2pManager.BUSY &&
                    busyDiscoveryRetryCount < maxBusyDiscoveryRetries
                ) {
                    busyDiscoveryRetryCount++
                    Log.w(
                        TAG,
                        "Wi-Fi P2P is busy during discovery; retrying " +
                            "($busyDiscoveryRetryCount/$maxBusyDiscoveryRetries) " +
                            "in ${busyDiscoveryRetryDelayMs}ms",
                    )
                    handler.removeCallbacks(discoveryTimeOut)
                    discoverPeersStable()
                    handler.postDelayed(delayedPeerDiscoveryRestart, busyDiscoveryRetryDelayMs)
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
        handler.removeCallbacks(connectTimeOut)
        handler.postDelayed(connectTimeOut, connectTimeoutMs)

        wifiP2pDevice = device
        connectionGroupOwnerIntent = groupOwnerIntent
        val peerAddress = device.deviceAddress
        val config = buildConnectionConfig(peerAddress, groupOwnerIntent)

        connectionState.setConnecting(true)
        Log.i(
            TAG,
            "Calling WifiP2pManager.connect for ${device.deviceName} at $peerAddress " +
                "(groupOwnerBand=$connectionGroupOperatingBand)",
        )

        wifiP2pManager.connect(wifiP2pChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connect request sent successfully")
                callbacks.forEach { it.onConnectRequestSent() }
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Connect request failed: $reason")
                connectionState.markConnectRequestFailed()
                handler.removeCallbacks(connectTimeOut)
                if (
                    reason == WifiP2pManager.BUSY &&
                    retryState.shouldRetryConnect()
                ) {
                    Log.w(TAG, "Wi-Fi P2P is busy; retrying connect in ${busyConnectRetryDelayMs}ms")
                    handler.postDelayed(busyConnectRetry, busyConnectRetryDelayMs)
                    return
                }
                if (trySamsungFiveGhzFallback(device, reason)) {
                    return
                }
                callbacks.forEach { it.onConnectRequestFailed(reason) }
            }
        })
    }

    @SuppressLint("NewApi")
    private fun buildConnectionConfig(peerAddress: String, groupOwnerIntent: Int?): WifiP2pConfig {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val builder = WifiP2pConfig.Builder()
                .setDeviceAddress(MacAddress.fromString(peerAddress))
                .setGroupOperatingBand(connectionGroupOperatingBand)
            val effectiveGroupOwnerIntent = groupOwnerIntent ?: if (
                connectionGroupOperatingBand == WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
            ) {
                15
            } else {
                null
            }
            return builder.build().apply {
                effectiveGroupOwnerIntent?.let { this.groupOwnerIntent = it }
            }
        }

        return WifiP2pConfig().apply {
            deviceAddress = peerAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent?.let { this.groupOwnerIntent = it }
        }
    }

    private fun trySamsungFiveGhzFallback(device: WifiP2pDevice, reason: Int): Boolean {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            !Build.MANUFACTURER.equals("samsung", ignoreCase = true) ||
            connectionGroupOperatingBand != WifiP2pConfig.GROUP_OWNER_BAND_AUTO ||
            triedSamsungFiveGhzFallback
        ) {
            return false
        }

        triedSamsungFiveGhzFallback = true
        connectionGroupOperatingBand = WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
        retryState.shouldRetryConnect()
        Log.w(
            TAG,
            "P2P connect failed on Samsung with reason=$reason; retrying ${device.deviceName} " +
                "on the 5 GHz group-owner band after the automatic-band attempt",
        )
        handler.postDelayed(busyConnectRetry, busyConnectRetryDelayMs)
        return true
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
        busyDiscoveryRetryCount = 0
        discoveryTimeoutRetryCount = 0
        connectionGroupOperatingBand = WifiP2pConfig.GROUP_OWNER_BAND_AUTO
        triedSamsungFiveGhzFallback = false
        connectionState.reset()
        cancelPendingRetries()
    }

    /** Stop delayed discovery/connect work without discarding the current group state. */
    fun cancelPendingRetries() {
        handler.removeCallbacks(discoveryTimeOut)
        handler.removeCallbacks(connectTimeOut)
        handler.removeCallbacks(busyConnectRetry)
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
        discoveryTimeoutRetryCount = 0
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
        if (info.groupFormed) {
            connectionState.markConnectionInfoAvailable(true)
            handler.removeCallbacks(connectTimeOut)
            lastGroupOwnerIp = info.groupOwnerAddress?.hostAddress
        } else if (!connectionState.isConnecting()) {
            connectionState.setConnected(false)
            lastGroupOwnerIp = null
        }
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
            if (discoveryTimeoutRetryCount >= maxDiscoveryTimeoutRetries) {
                Log.d(TAG, "Peer discovery timed out after $discoveryTimeoutRetryCount recovery scans")
                return
            }
            discoveryTimeoutRetryCount++
            Log.d(
                TAG,
                "Peer discovery timeout; starting recovery scan " +
                    "$discoveryTimeoutRetryCount/$maxDiscoveryTimeoutRetries",
            )
            if (allowDeviceResetOnDiscoveryTimeout && retryState.shouldRetryDiscovery()) {
                Log.d(TAG, "Resetting glasses P2P before the recovery scan")
                resetDeviceP2p()
                initP2P()
            }
            discoverPeersStable()
            handler.postDelayed(delayedPeerDiscoveryRestart, discoveryTimeoutRetryDelayMs)
        }
    }

    private val connectTimeOut = object : Runnable {
        override fun run() {
            connectionState.setConnecting(false)
            if (!p2pOperationsEnabled) return
            val device = wifiP2pDevice ?: run {
                callbacks.forEach { it.retryAlsoFailed() }
                return
            }
            if (trySamsungFiveGhzFallback(device, WifiP2pManager.ERROR)) {
                return
            }
            if (retryState.shouldRetryConnect()) {
                Log.d(TAG, "Internal connection retry connection once")
                connectToDevice(device, connectionGroupOwnerIntent)
            } else {
                Log.d(TAG, "Do not reconnect, wait for external timeout")
                callbacks.forEach { it.retryAlsoFailed() }
            }
        }
    }

    interface WifiP2pCallback : com.heycyan.core.connectivity.p2p.WifiP2pCallback
}
