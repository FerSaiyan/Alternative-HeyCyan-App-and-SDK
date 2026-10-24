package com.fersaiyan.cyanbridge.wifiadb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.network.GlassesNetworkSessionCoordinator
import com.fersaiyan.cyanbridge.network.HeyCyanP2pLink
import com.fersaiyan.cyanbridge.shared.network.session.GlassesLinkClient
import com.fersaiyan.cyanbridge.ui.wifi.p2p.WifiP2pManagerSingleton
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.oudmon.ble.base.communication.utils.ByteUtil
import java.net.InetSocketAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object DefaultWifiAdbDebugControllerFactory : WifiAdbDebugControllerFactory {
    override fun create(context: Context): WifiAdbDebugController =
        DebugWifiAdbDebugController(context.applicationContext)
}

private class DebugWifiAdbDebugController(
    private val context: Context,
) : WifiAdbDebugController {
    companion object {
        private const val TAG = "WifiAdbDebug"
        private const val GLASSES_ADB_PORT = 5555
        private const val RELAY_PORT = 15555
        private const val FLOW_TIMEOUT_MS = 45_000L
        private const val NETWORK_TIMEOUT_MS = 20_000L
        private const val PROBE_TIMEOUT_MS = 3_000
        private const val PREFERRED_COMMAND =
            "adb -s <phone-serial> forward tcp:15555 tcp:15555\nadb connect 127.0.0.1:15555"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(WifiAdbDebugRuntimeState(isAvailable = true))
    override val state: StateFlow<WifiAdbDebugRuntimeState> = mutableState

    private var mainJob: Job? = null
    private var relay: RawTcpRelay? = null
    private var p2pLink: HeyCyanP2pLink? = null
    private var notifyListener: GlassesDeviceNotifyListener? = null
    private var notifyRegistered = false
    private var onSessionFinished: () -> Unit = {}
    private var finishNotified = true
    private var cleanupInProgress = false
    private var releaseAfterCleanup = false
    private var stopRequested = false
    private var bluetoothDisconnected = false
    private var entrySent = false
    private var entryAcknowledged = false
    private var exitSent = false
    private var exitAcknowledged = false
    private var p2pTeardownConfirmed = false
    @Volatile private var activeGeneration = 0L
    @Volatile private var glassesIp: String? = null
    @Volatile private var p2pGroupFormed = false
    @Volatile private var p2pFailure: String? = null

    override val isActive: Boolean
        get() = mainJob?.isActive == true || cleanupInProgress

    override fun start(onSessionFinished: () -> Unit) {
        if (isActive) return
        val generation = activeGeneration + 1
        activeGeneration = generation
        this.onSessionFinished = onSessionFinished
        finishNotified = false
        cleanupInProgress = false
        stopRequested = false
        bluetoothDisconnected = false
        entrySent = false
        entryAcknowledged = false
        exitSent = false
        exitAcknowledged = false
        p2pTeardownConfirmed = false
        glassesIp = null
        p2pGroupFormed = false
        p2pFailure = null
        mutableState.value = WifiAdbDebugRuntimeState(
            isAvailable = true,
            stateLabel = "Preparing",
            detail = "Registering the BLE listener and starting Wi-Fi Direct.",
            canStart = false,
            canStop = true,
        )
        mainJob = scope.launch { runFlow(generation) }
    }

    override fun stop() {
        if (!isActive) return
        val generation = activeGeneration
        stopRequested = true
        mutableState.value = mutableState.value.copy(
            stateLabel = "Stopping",
            detail = "Closing relay sockets and removing the Wi-Fi Direct group.",
            canStart = false,
            canStop = false,
        )
        mainJob?.cancel()
        mainJob = null
        cleanup(generation)
    }

    override fun onBluetoothDisconnected() {
        if (!isActive) return
        val generation = activeGeneration
        bluetoothDisconnected = true
        mutableState.value = mutableState.value.copy(
            stateLabel = "BLE disconnected",
            detail = "The debug relay was stopped because Bluetooth disconnected.",
            canStart = true,
            canStop = false,
        )
        mainJob?.cancel()
        mainJob = null
        relay?.close()
        relay = null
        p2pLink?.let { GlassesNetworkSessionCoordinator.abandonHeyCyanLink(context, it) }
        cleanupInProgress = true
        p2pTeardownConfirmed = true
        exitAcknowledged = true
        finishCleanup(generation)
    }

    override fun release() {
        releaseAfterCleanup = true
        stop()
        if (cleanupInProgress) {
            val generation = activeGeneration
            val manager = p2pLink?.manager
            if (manager != null && !manager.isConnecting() && !manager.isConnected()) {
                p2pTeardownConfirmed = true
                finishCleanup(generation)
            }
            if (isCleanupCurrent(generation)) {
                abandonControllerForRelease(generation)
            }
        }
        if (!isActive) scope.cancel()
    }

    private suspend fun runFlow(generation: Long) {
        try {
            if (!BleOperateManager.getInstance().isConnected) {
                fail("BLE disconnected", "Connect the glasses over Bluetooth first.")
                return
            }
            if (!hasP2pPermission()) {
                fail("P2P permission required", "Grant Nearby devices or Location permission.")
                return
            }
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager?.isWifiEnabled != true) {
                fail("Wi-Fi disabled", "Enable Wi-Fi before starting the debug session.")
                return
            }
            if (!registerNotifyListener(generation)) {
                fail("BLE listener failed", "Could not register the glasses IP listener.")
                return
            }
            if (!startP2p(generation)) {
                fail("P2P unavailable", "Could not start Wi-Fi Direct discovery.")
                return
            }

            entrySent = true
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, 0x04),
            ) { _, _ ->
                scope.launch {
                    if (!isCurrentGeneration(generation)) return@launch
                    entryAcknowledged = true
                    if (cleanupInProgress) sendExitOnce(generation)
                }
            }
            mutableState.value = mutableState.value.copy(
                stateLabel = "Waiting for glasses",
                detail = "Waiting for BLE IP notify 0x08 and an Android P2P group.",
            )

            val ready = withTimeoutOrNull(FLOW_TIMEOUT_MS) {
                while (isActive && (glassesIp == null || !p2pGroupFormed)) {
                    p2pFailure?.let { throw IllegalStateException(it) }
                    delay(200)
                }
                glassesIp != null && p2pGroupFormed
            } == true
            if (!ready) {
                fail("Connection timed out", "Both a BLE-reported IP and Android P2P group are required.")
                return
            }

            val targetIp = requireNotNull(glassesIp)
            mutableState.value = mutableState.value.copy(
                stateLabel = "Selecting P2P route",
                detail = "Waiting for a unique P2P/WFD interface on the glasses /24.",
                glassesIp = targetIp,
            )
            val network = waitForP2pNetwork(targetIp)
            if (network == null) {
                fail("P2P route unavailable", "No unique P2P/WFD interface has a local IPv4 on the glasses /24.")
                return
            }

            val probeSucceeded = withContext(Dispatchers.IO) {
                runCatching {
                    network.socketFactory.createSocket().use { socket ->
                        socket.connect(InetSocketAddress(targetIp, GLASSES_ADB_PORT), PROBE_TIMEOUT_MS)
                    }
                }.isSuccess
            }
            if (!probeSucceeded) {
                fail("ADB probe failed", "The glasses did not accept a P2P connection on $targetIp:$GLASSES_ADB_PORT.")
                return
            }

            val activeRelay = RawTcpRelay(RELAY_PORT) {
                network.socketFactory.createSocket().apply {
                    connect(InetSocketAddress(targetIp, GLASSES_ADB_PORT), PROBE_TIMEOUT_MS)
                }
            }
            activeRelay.start()
            relay = activeRelay
            mutableState.value = WifiAdbDebugRuntimeState(
                isAvailable = true,
                stateLabel = "Relay ready",
                detail = "Loopback-only relay is ready for adb forward; it is not exposed to network peers.",
                glassesIp = targetIp,
                preferredCommand = PREFERRED_COMMAND,
                canStart = false,
                canStop = true,
            )
            while (activeRelay.isRunning) delay(250)
            throw IllegalStateException("The local ADB relay stopped unexpectedly.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (isCurrentGeneration(generation)) {
                fail("Flow failed", error.message ?: "Unexpected debug relay failure.")
            }
        } finally {
            cleanup(generation)
        }
    }

    private fun registerNotifyListener(generation: Long): Boolean {
        notifyListener = object : GlassesDeviceNotifyListener() {
            override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
                if (!isCurrentGeneration(generation) || cleanupInProgress) return
                val data = response.loadData
                if (data.size < 7) return
                when (data[6].toInt() and 0xFF) {
                    0x08 -> if (data.size >= 11) {
                        val ip = (7..10).joinToString(".") { ByteUtil.byteToInt(data[it]).toString() }
                        if (WifiAdbNetworkRules.parseIpv4(ip) != null) {
                            scope.launch {
                                if (!isCurrentGeneration(generation) || cleanupInProgress) return@launch
                                glassesIp = ip
                                mutableState.value = mutableState.value.copy(glassesIp = ip)
                                Log.i(TAG, "Glasses IP received from notify 0x08: $ip")
                            }
                        }
                    }
                    0x09 -> Log.w(TAG, "Glasses P2P notify 0x09: ${ByteUtil.byteToInt(data.getOrNull(7) ?: 0)}")
                }
            }
        }
        return try {
            LargeDataHandler.getInstance().addOutDeviceListener(2, requireNotNull(notifyListener))
            notifyRegistered = true
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not register notify listener", error)
            false
        }
    }

    private suspend fun startP2p(generation: Long): Boolean {
        val manager = WifiP2pManagerSingleton.getInstance(context)
        val callback = object : WifiP2pManagerSingleton.WifiP2pCallback {
            override fun onWifiP2pEnabled() = Unit
            override fun onWifiP2pDisabled() {
                if (isCurrentGeneration(generation)) failP2p(generation, "Wi-Fi Direct was disabled.")
            }
            override fun onPeersChanged(peers: Collection<WifiP2pDevice>) {
                if (!isCurrentGeneration(generation) || cleanupInProgress ||
                    manager.isConnecting() || manager.isConnected()
                ) return
                val pairedName = runCatching { DeviceManager.getInstance().deviceName }.getOrNull()
                val pairedMac = runCatching { DeviceManager.getInstance().deviceAddress }.getOrNull()
                peers.firstOrNull { peer ->
                    WifiAdbNetworkRules.isExpectedPeer(
                        peerName = peer.deviceName,
                        peerAddress = peer.deviceAddress,
                        pairedName = pairedName,
                        pairedAddress = pairedMac,
                    )
                }?.let { peer ->
                    p2pLink?.markConnectRequested()
                    manager.connectToDevice(peer)
                }
            }
            override fun onConnected(info: WifiP2pInfo) {
                if (!isCurrentGeneration(generation)) return
                p2pGroupFormed = info.groupFormed
                p2pLink?.markGroupFormed(info.groupFormed)
            }
            override fun onDisconnected() {
                if (!isCurrentGeneration(generation)) return
                p2pGroupFormed = false
                p2pLink?.markGroupFormed(false)
                if (!cleanupInProgress) {
                    failP2p(generation, "The Wi-Fi Direct group disconnected.")
                }
            }
            override fun onPeerDiscoveryStarted() = Unit
            override fun onPeerDiscoveryFailed(reason: Int) {
                if (isCurrentGeneration(generation)) failP2p(generation, "Peer discovery failed ($reason).")
            }
            override fun onConnectRequestSent() = Unit
            override fun onConnectRequestFailed(reason: Int) {
                if (isCurrentGeneration(generation)) failP2p(generation, "P2P connect failed ($reason).")
            }
            override fun onThisDeviceChanged(device: WifiP2pDevice) = Unit
            override fun connecting() = Unit
            override fun cancelConnect() = Unit
            override fun cancelConnectFail(reason: Int) {
                if (isCurrentGeneration(generation) && !cleanupInProgress) {
                    failP2p(generation, "P2P cancellation failed ($reason).")
                }
            }
            override fun retryAlsoFailed() {
                if (isCurrentGeneration(generation)) failP2p(generation, "P2P connection retry failed.")
            }
        }
        val link = GlassesNetworkSessionCoordinator.openHeyCyanLink(
            context,
            GlassesLinkClient.HEYCYAN_WIFI_ADB,
            callback,
        ) ?: return false
        if (!isCurrentGeneration(generation)) {
            GlassesNetworkSessionCoordinator.closeHeyCyanLink(context, link) {}
            return false
        }
        p2pLink = link
        return try {
            manager.startPeerDiscovery(allowDeviceResetOnTimeout = false)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not start P2P", error)
            false
        }
    }

    private suspend fun waitForP2pNetwork(glassesIp: String): Network? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        return withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
            var selected: Network?
            do {
                selected = WifiAdbNetworkRules.selectP2pNetwork(connectivityManager, glassesIp)
                if (selected == null) delay(250)
            } while (selected == null)
            selected
        }
    }

    private fun failP2p(generation: Long, detail: String) {
        if (!isCurrentGeneration(generation) || cleanupInProgress || p2pFailure != null) return
        p2pFailure = detail
        fail("P2P failed", detail)
        mainJob?.cancel(CancellationException(detail))
    }

    private fun fail(label: String, detail: String) {
        mutableState.value = mutableState.value.copy(
            stateLabel = label,
            detail = detail,
            canStart = false,
            canStop = false,
        )
    }

    private fun cleanup(generation: Long) {
        if (!isCurrentGeneration(generation) || cleanupInProgress) return
        cleanupInProgress = true
        relay?.close()
        relay = null
        sendExitOnce(generation)
        val link = p2pLink
        if (link == null) {
            p2pTeardownConfirmed = true
            finishCleanup(generation)
            return
        }
        // The shared session keeps the link reserved (retrying a quarantined removal) until the
        // group is confirmed gone; only then does this session report itself finished.
        GlassesNetworkSessionCoordinator.closeHeyCyanLink(context, link) {
            if (isCleanupCurrent(generation)) {
                p2pTeardownConfirmed = true
                finishCleanup(generation)
            }
        }
    }

    private fun sendExitOnce(generation: Long) {
        if (!entrySent) {
            exitAcknowledged = true
            finishCleanup(generation)
            return
        }
        if (!entryAcknowledged || exitSent) return
        if (bluetoothDisconnected || !BleOperateManager.getInstance().isConnected) {
            exitAcknowledged = true
            finishCleanup(generation)
            return
        }
        exitSent = true
        runCatching {
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, 0x09),
            ) { _, _ ->
                scope.launch {
                    if (!isCleanupCurrent(generation)) return@launch
                    exitAcknowledged = true
                    finishCleanup(generation)
                }
            }
        }.onFailure { Log.w(TAG, "Exit-transfer command could not be sent", it) }
    }

    private fun finishCleanup(generation: Long) {
        if (!isCleanupCurrent(generation)) return
        if (!p2pTeardownConfirmed || !exitAcknowledged) return
        activeGeneration = generation + 1
        if (notifyRegistered) runCatching { LargeDataHandler.getInstance().removeOutDeviceListener(2) }
        runCatching { LargeDataHandler.getInstance().removeGlassesControlCallback() }
        notifyRegistered = false
        p2pLink = null
        notifyListener = null
        p2pGroupFormed = false
        cleanupInProgress = false
        mainJob = null
        if (stopRequested) {
            mutableState.value = WifiAdbDebugRuntimeState(isAvailable = true)
        } else {
            mutableState.value = mutableState.value.copy(canStart = true, canStop = false)
        }
        notifyFinished()
        if (releaseAfterCleanup) scope.cancel()
    }

    /**
     * The Activity owner is gone, so this controller must never retain a cmdType=2 listener or
     * singleton P2P callback that could later remove a replacement Activity's registrations.
     * The owning session is deliberately not reported finished: a real BLE disconnect remains
     * the quarantine boundary when Android did not confirm P2P teardown.
     */
    private fun abandonControllerForRelease(generation: Long) {
        if (!isCleanupCurrent(generation)) return
        Log.e(TAG, "Owner released before P2P teardown was confirmed; detaching resources and retaining the lease")
        activeGeneration = generation + 1
        relay?.close()
        relay = null
        // The shared session finishes (and retries) the group removal on its own scope.
        if (notifyRegistered) runCatching { LargeDataHandler.getInstance().removeOutDeviceListener(2) }
        runCatching { LargeDataHandler.getInstance().removeGlassesControlCallback() }
        notifyRegistered = false
        p2pLink = null
        notifyListener = null
        cleanupInProgress = false
        mainJob = null
        scope.cancel()
    }

    private fun notifyFinished() {
        if (finishNotified) return
        finishNotified = true
        onSessionFinished()
    }

    private fun isCurrentGeneration(generation: Long): Boolean = activeGeneration == generation

    private fun isCleanupCurrent(generation: Long): Boolean =
        cleanupInProgress && isCurrentGeneration(generation)

    private fun hasP2pPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
