package com.fersaiyan.cyanbridge.ota

import android.content.Context
import android.Manifest
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.MjpegServer
import com.fersaiyan.cyanbridge.RtspFrames
import com.fersaiyan.cyanbridge.ui.wifi.p2p.WifiP2pManagerSingleton
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.oudmon.ble.base.communication.utils.ByteUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

private const val TAG = "LivePreview"

data class LivePreviewState(
    val stateLabel: String = "Idle",
    val detail: String = "",
    val isScanning: Boolean = false,
    val isPlaying: Boolean = false,
    val streamUrl: String? = null,
    val canStart: Boolean = true,
    val canStop: Boolean = false,
)

/**
 * Orchestrates live video preview from the glasses via RTSP.
 *
 * Live stream flow:
 * 1. Verify BLE connection
 * 2. Register the BLE IP listener and start livestream mode
 * 3. Start P2P peer discovery + connection (same as OTA)
 * 4. Wait for the glasses to report their P2P IP via BLE notify (type 0x08)
 * 5. Decode RTSP H.264 frames and publish them on a loopback MJPEG endpoint
 *
 * All logcat tags: "LivePreview". Filter: `adb logcat -s LivePreview`
 */
class LivePreviewManager(
    private val context: Context,
) {

    private val _uiState = MutableStateFlow(LivePreviewState())
    val uiState: StateFlow<LivePreviewState> = _uiState

    private var rtspFrames: RtspFrames? = null
    private var mjpegServer: MjpegServer? = null
    private var liveCommandSent = false
    private var heartbeatJob: Job? = null
    private var mainJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var p2pManager: WifiP2pManagerSingleton? = null
    private var p2pCallback: WifiP2pManagerSingleton.WifiP2pCallback? = null
    private var p2pReceiverRegistered = false
    private var notifyListener: GlassesDeviceNotifyListener? = null
    private var notifyRegistered = false
    private var boundP2pNetwork: Network? = null
    @Volatile private var cleanupComplete = true
    @Volatile private var cleanupInProgress = false
    @Volatile private var sessionFinishNotified = true
    private var releaseScopeAfterCleanup = false
    private var onSessionFinished: () -> Unit = {}

    @Volatile private var bleIpReceived: String? = null
    @Volatile private var p2pConnected = false
    @Volatile private var p2pInfo: WifiP2pInfo? = null
    @Volatile private var p2pFailure: String? = null
    @Volatile private var flowStartTimeMs = 0L

    val isActive: Boolean get() = mainJob?.isActive == true

    companion object {
        private const val RTSP_PORT = 8554
        private const val MJPEG_PORT = 8080
        private const val BLE_IP_TIMEOUT_MS = 45_000L
        private const val P2P_CONNECT_TIMEOUT_MS = 20_000L
        private const val STREAM_START_TIMEOUT_MS = 30_000L
        private const val P2P_GROUP_REMOVAL_RETRY_MS = 1_000L
        private const val P2P_GROUP_REMOVE_ACTION_TIMEOUT_MS = 5_000L
        private const val P2P_GROUP_DISCONNECT_TIMEOUT_MS = 5_000L
        private const val P2P_GROUP_REMOVAL_MAX_ATTEMPTS = 3
    }

    private fun elapsed(): Long = System.currentTimeMillis() - flowStartTimeMs

    fun start(onSessionFinished: () -> Unit) {
        if (mainJob?.isActive == true) {
            Log.w(TAG, "start() called but flow already active, ignoring")
            return
        }
        cleanupComplete = false
        cleanupInProgress = false
        sessionFinishNotified = false
        this.onSessionFinished = onSessionFinished
        flowStartTimeMs = System.currentTimeMillis()
        Log.i(TAG, "========================================")
        Log.i(TAG, "  LIVESTREAM FLOW START")
        Log.i(TAG, "  Time: ${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}")
        Log.i(TAG, "  BLE connected: ${BleOperateManager.getInstance().isConnected}")
        Log.i(TAG, "  Device: ${try { DeviceManager.getInstance().deviceName } catch (_: Exception) { "?" }}")
        Log.i(TAG, "========================================")
        mainJob = scope.launch { runLivestream() }
    }

    fun stop() {
        Log.i(TAG, "[${elapsed()}ms] stop() called")
        mainJob?.cancel()
        mainJob = null
        cleanup()
        _uiState.value = LivePreviewState()
        Log.i(TAG, "[${elapsed()}ms] Stopped, state reset to Idle")
    }

    fun release() {
        Log.i(TAG, "release() called")
        releaseScopeAfterCleanup = true
        stop()
        if (cleanupComplete) {
            scope.cancel()
        }
    }

    fun onBluetoothDisconnected() {
        Log.i(TAG, "Bluetooth disconnected; abandoning preview resources")
        mainJob?.cancel()
        mainJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        liveCommandSent = false
        releaseStream()
        unbindP2pNetwork()
        if (!cleanupComplete) {
            cleanupInProgress = true
            p2pManager?.stopP2pOperations()
            p2pManager?.cancelP2pConnection()
            finishCleanup()
        }
    }

    private fun cleanup() {
        if (cleanupComplete || cleanupInProgress) return
        cleanupInProgress = true
        Log.i(TAG, "[${elapsed()}ms] cleanup: releasing resources...")
        releaseStream()
        sendStopLiveCommand()

        unbindP2pNetwork()

        val manager = p2pManager
        if (manager == null) {
            finishCleanup()
            return
        }
        manager.stopP2pOperations()
        manager.cancelP2pConnection()
        if (!cleanupInProgress) return
        removeP2pGroup(manager, attempt = 1)
    }

    private fun removeP2pGroup(manager: WifiP2pManagerSingleton, attempt: Int) {
        val resultHandled = java.util.concurrent.atomic.AtomicBoolean(false)
        val handleResult: (Boolean) -> Unit = { success ->
            if (success) {
                Log.i(TAG, "[${elapsed()}ms] cleanup: P2P removal accepted on attempt $attempt; waiting for disconnect")
            } else {
                Log.w(TAG, "[${elapsed()}ms] cleanup: P2P removal failed on attempt $attempt; checking group state")
            }
            awaitP2pDisconnect(manager, attempt)
        }
        scope.launch {
            delay(P2P_GROUP_REMOVE_ACTION_TIMEOUT_MS)
            if (cleanupInProgress && resultHandled.compareAndSet(false, true)) {
                Log.w(TAG, "[${elapsed()}ms] cleanup: P2P removal gave no callback on attempt $attempt; checking group state")
                awaitP2pDisconnect(manager, attempt)
            }
        }
        try {
            manager.removeGroup { success ->
                if (resultHandled.compareAndSet(false, true)) {
                    handleResult(success)
                } else {
                    Log.d(TAG, "[${elapsed()}ms] cleanup: ignoring late P2P removal callback for attempt $attempt")
                }
            }
        } catch (e: Exception) {
            if (resultHandled.compareAndSet(false, true)) {
                Log.w(TAG, "[${elapsed()}ms] cleanup: P2P removal threw on attempt $attempt; checking group state", e)
                awaitP2pDisconnect(manager, attempt)
            }
        }
    }

    private fun awaitP2pDisconnect(manager: WifiP2pManagerSingleton, attempt: Int) {
        scope.launch {
            val deadline = System.currentTimeMillis() + P2P_GROUP_DISCONNECT_TIMEOUT_MS
            while (cleanupInProgress && System.currentTimeMillis() < deadline) {
                manager.requestConnectionInfo()
                delay(250)
                if (!manager.isConnecting() && !manager.isConnected()) {
                    Log.i(TAG, "[${elapsed()}ms] cleanup: confirmed P2P group is gone after attempt $attempt")
                    finishCleanup()
                    return@launch
                }
            }

            if (cleanupInProgress) {
                if (!manager.canUseP2p() || attempt >= P2P_GROUP_REMOVAL_MAX_ATTEMPTS) {
                    Log.e(TAG, "[${elapsed()}ms] cleanup: P2P teardown could not be confirmed; keeping the preview lease quarantined until Bluetooth reconnect")
                } else {
                    Log.w(TAG, "[${elapsed()}ms] cleanup: P2P group still present after attempt $attempt; retaining the preview lease and retrying")
                    scheduleP2pRemovalRetry(manager, attempt + 1)
                }
            }
        }
    }

    private fun scheduleP2pRemovalRetry(manager: WifiP2pManagerSingleton, attempt: Int) {
        scope.launch {
            delay(P2P_GROUP_REMOVAL_RETRY_MS)
            if (cleanupInProgress && !sessionFinishNotified) {
                removeP2pGroup(manager, attempt)
            }
        }
    }

    private fun finishCleanup() {
        if (!cleanupInProgress) return
        p2pCallback?.let { callback -> p2pManager?.removeCallback(callback) }
        if (p2pReceiverRegistered) {
            try {
                p2pManager?.unregisterReceiver()
                Log.d(TAG, "[${elapsed()}ms] cleanup: P2P receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "[${elapsed()}ms] cleanup: P2P unregisterReceiver failed: ${e.message}")
            }
            p2pReceiverRegistered = false
        }
        if (notifyRegistered) {
            try {
                LargeDataHandler.getInstance().removeOutDeviceListener(2)
                Log.d(TAG, "[${elapsed()}ms] cleanup: BLE notify listener removed")
            } catch (e: Exception) {
                Log.w(TAG, "[${elapsed()}ms] cleanup: removeOutDeviceListener failed: ${e.message}")
            }
            notifyRegistered = false
        }
        p2pCallback = null
        p2pManager = null
        notifyListener = null
        p2pConnected = false
        p2pInfo = null
        p2pFailure = null
        cleanupInProgress = false
        cleanupComplete = true
        notifySessionFinished()
        Log.i(TAG, "[${elapsed()}ms] cleanup: done")
        if (releaseScopeAfterCleanup) {
            scope.cancel()
        }
    }

    private fun notifySessionFinished() {
        if (sessionFinishNotified) return
        sessionFinishNotified = true
        onSessionFinished()
    }

    private suspend fun runLivestream() {
        try {
            bleIpReceived = null
            p2pConnected = false
            p2pInfo = null
            p2pFailure = null

            // ── Step 1: BLE connection check ─────────────────────────
            Log.i(TAG, "[${elapsed()}ms] [Step 1/5] Checking BLE connection...")
            updateState("Connecting", "Checking BLE connection...", scanning = true)
            val bleConnected = BleOperateManager.getInstance().isConnected
            Log.i(TAG, "[${elapsed()}ms] [Step 1/5] BLE connected=$bleConnected")
            if (!bleConnected) {
                Log.e(TAG, "[${elapsed()}ms] [Step 1/5] FAIL: BLE not connected — aborting")
                updateState("BLE disconnected", "Connect to glasses via Bluetooth first.")
                return
            }
            val deviceName = try { DeviceManager.getInstance().deviceName ?: "?" } catch (_: Exception) { "?" }
            val deviceMac = try { DeviceManager.getInstance().deviceAddress ?: "?" } catch (_: Exception) { "?" }
            Log.i(TAG, "[${elapsed()}ms] [Step 1/5] OK: device=$deviceName mac=$deviceMac")
            if (!hasWifiP2pPermission()) {
                Log.e(TAG, "[${elapsed()}ms] [Step 1/5] FAIL: required Wi-Fi Direct permission is missing")
                updateState("P2P permission required", "Grant Nearby devices or Location permission before starting preview.")
                return
            }
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager?.isWifiEnabled != true) {
                Log.e(TAG, "[${elapsed()}ms] [Step 1/5] FAIL: Wi-Fi is disabled")
                updateState("Wi-Fi disabled", "Enable Wi-Fi before starting live preview.")
                return
            }

            // Listen before the start command because the glasses may report their IP immediately.
            Log.i(TAG, "[${elapsed()}ms] [Step 2/5] Registering BLE IP listener...")
            if (!registerNotifyListener()) {
                updateState("Listener unavailable", "Could not receive the glasses P2P IP notification.")
                return
            }
            sendStartLiveCommand()
            updateState(
                "Starting live mode",
                "Waiting for the glasses to enter livestream mode and report their Wi-Fi Direct address.",
                scanning = true,
            )

            // ── Step 3: Start P2P ────────────────────────────────────
            Log.i(TAG, "[${elapsed()}ms] [Step 3/5] Starting P2P connection...")
            updateState("P2P connecting", "Establishing Wi-Fi Direct with the phone as group owner...", scanning = true)
            val p2pStartTime = System.currentTimeMillis()
            if (!startP2p()) {
                updateState("P2P unavailable", "Could not start Wi-Fi Direct discovery.")
                return
            }
            Log.i(TAG, "[${elapsed()}ms] [Step 3/5] P2P discovery initiated (took ${System.currentTimeMillis() - p2pStartTime}ms)")

            // ── Step 4: Wait for glasses IP via BLE ──────────────────
            Log.i(TAG, "[${elapsed()}ms] [Step 4/5] Waiting for glasses IP via BLE notification 0x08 (timeout=${BLE_IP_TIMEOUT_MS}ms)...")
            updateState("Waiting for IP", "Waiting for glasses to report P2P IP via BLE...", scanning = true)
            val ipStartTime = System.currentTimeMillis()
            val glassesIp = waitForBleIp(BLE_IP_TIMEOUT_MS)
            val ipElapsed = System.currentTimeMillis() - ipStartTime
            if (glassesIp == null) {
                Log.e(TAG, "[${elapsed()}ms] [Step 4/5] FAIL: No glasses IP after ${ipElapsed}ms")
                Log.e(TAG, "[${elapsed()}ms]   bleIpReceived=$bleIpReceived, p2pConnected=$p2pConnected, p2pInfo=$p2pInfo")
                Log.e(TAG, "[${elapsed()}ms]   Possible causes: glasses didn't enter P2P mode, BLE notify not sent, timeout too short")
                updateState("No IP", "Glasses didn't report IP after ${ipElapsed / 1000}s. " +
                    "P2P=$p2pConnected. Try Sync data first to verify P2P works.")
                return
            }
            Log.i(TAG, "[${elapsed()}ms] [Step 4/5] OK: Glasses IP=$glassesIp (waited ${ipElapsed}ms)")

            Log.i(TAG, "[${elapsed()}ms] [Step 4/5] Waiting for P2P group (timeout=${P2P_CONNECT_TIMEOUT_MS}ms)...")
            if (!waitForP2pConnection(P2P_CONNECT_TIMEOUT_MS)) {
                Log.e(TAG, "[${elapsed()}ms] [Step 4/5] FAIL: BLE reported $glassesIp but P2P never connected")
                updateState("P2P unavailable", "Glasses reported $glassesIp, but the Wi-Fi Direct group did not connect.")
                return
            }
            if (!bindToP2pNetwork(glassesIp)) {
                updateState("P2P route unavailable", "Could not bind the RTSP stream to the glasses Wi-Fi Direct network.")
                return
            }

            // ── Step 5: Decode RTSP and publish local MJPEG ───────────
            val rtspUrl = "rtsp://$glassesIp:$RTSP_PORT/ch0"
            Log.i(TAG, "[${elapsed()}ms] [Step 5/5] Connecting RTSP decoder to $rtspUrl...")
            updateState("Starting stream", "Connecting to $rtspUrl...", scanning = true)
            startFrameStream(glassesIp)

            Log.i(TAG, "========================================")
            Log.i(TAG, "  LIVESTREAM SUCCESS")
            Log.i(TAG, "  RTSP: $rtspUrl")
            Log.i(TAG, "  MJPEG: http://127.0.0.1:$MJPEG_PORT/mjpeg")
            Log.i(TAG, "  Total time: ${elapsed()}ms")
            Log.i(TAG, "========================================")
            _uiState.value = LivePreviewState(
                stateLabel = "Playing",
                detail = "RTSP: $rtspUrl\nMJPEG: http://127.0.0.1:$MJPEG_PORT/mjpeg",
                isPlaying = true,
                streamUrl = "http://127.0.0.1:$MJPEG_PORT/mjpeg",
                canStart = false,
                canStop = true,
            )
            // Keep the P2P group and stream endpoint alive until the user stops preview.
            awaitCancellation()

        } catch (e: CancellationException) {
            Log.i(TAG, "[${elapsed()}ms] LIVESTREAM CANCELLED: ${e.message ?: "cancelled"}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[${elapsed()}ms] LIVESTREAM EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
            updateState("Error", "Failed: ${e.message}")
        } finally {
            Log.i(TAG, "[${elapsed()}ms] runLivestream() exiting, running cleanup")
            cleanup()
        }
    }

    /**
     * Start P2P peer discovery and connection.
     * Logs every P2P event with details.
     */
    private fun startP2p(): Boolean {
        p2pManager = WifiP2pManagerSingleton.getInstance(context)
        Log.i(TAG, "[${elapsed()}ms] [P2P] Manager obtained, registering callback")

        p2pCallback = object : WifiP2pManagerSingleton.WifiP2pCallback {
            override fun onWifiP2pEnabled() {
                Log.i(TAG, "[${elapsed()}ms] [P2P] Wi-Fi P2P enabled")
            }
            override fun onWifiP2pDisabled() {
                Log.w(TAG, "[${elapsed()}ms] [P2P] Wi-Fi P2P DISABLED — P2P will not work")
                failP2p("Wi-Fi Direct was disabled.")
            }

            override fun onPeersChanged(peers: Collection<WifiP2pDevice>) {
                if (cleanupInProgress) {
                    Log.d(TAG, "[${elapsed()}ms] [P2P] Ignoring peers during teardown")
                    return
                }
                Log.i(TAG, "[${elapsed()}ms] [P2P] Peers changed: ${peers.size} peer(s) found")
                peers.forEachIndexed { i, peer ->
                    Log.d(TAG, "[${elapsed()}ms] [P2P]   peer[$i]: name='${peer.deviceName}' addr=${peer.deviceAddress} status=${peer.status}")
                }

                if (p2pManager?.isConnecting() == true) {
                    Log.d(TAG, "[${elapsed()}ms] [P2P] Already connecting, skipping peer selection")
                    return
                }
                if (p2pManager?.isConnected() == true) {
                    Log.d(TAG, "[${elapsed()}ms] [P2P] Already connected, skipping peer selection")
                    return
                }

                val pairedName = try { DeviceManager.getInstance().deviceName } catch (_: Exception) { null }
                val pairedMac = try { DeviceManager.getInstance().deviceAddress } catch (_: Exception) { null }
                Log.d(TAG, "[${elapsed()}ms] [P2P] Looking for paired device: name='$pairedName' mac=$pairedMac")

                val target = peers.firstOrNull { peer ->
                    peer.deviceName == pairedName ||
                        peer.deviceAddress == pairedMac ||
                        peer.deviceName?.endsWith("_${pairedMac?.takeLast(5)?.replace(":", "")}") == true
                }

                if (target != null) {
                    Log.i(TAG, "[${elapsed()}ms] [P2P] Matched glasses peer: '${target.deviceName}' (${target.deviceAddress})")
                    Log.i(TAG, "[${elapsed()}ms] [P2P] Sending connect request (WPS PBC)...")
                    p2pManager?.connectToDevice(target, groupOwnerIntent = 15)
                } else {
                    Log.w(TAG, "[${elapsed()}ms] [P2P] No matching glasses peer among ${peers.size} peers")
                    Log.w(TAG, "[${elapsed()}ms] [P2P]   Paired: '$pairedName' / $pairedMac")
                    peers.forEach { peer ->
                        Log.w(TAG, "[${elapsed()}ms] [P2P]   Available: '${peer.deviceName}' / ${peer.deviceAddress}")
                    }
                }
            }

            override fun onConnected(info: WifiP2pInfo) {
                Log.i(TAG, "[${elapsed()}ms] [P2P] Connected!")
                Log.i(TAG, "[${elapsed()}ms] [P2P]   groupFormed=${info.groupFormed}")
                Log.i(TAG, "[${elapsed()}ms] [P2P]   isGroupOwner=${info.isGroupOwner}")
                Log.i(TAG, "[${elapsed()}ms] [P2P]   groupOwnerAddress=${info.groupOwnerAddress?.hostAddress}")
                Log.i(TAG, "[${elapsed()}ms] [P2P]   groupOwnerAddress=${info.groupOwnerAddress}")
                p2pConnected = info.groupFormed
                p2pInfo = info
                if (cleanupInProgress && !info.groupFormed) {
                    Log.i(TAG, "[${elapsed()}ms] [P2P] Confirmed group removal from connection info")
                    finishCleanup()
                }
            }

            override fun onDisconnected() {
                Log.w(TAG, "[${elapsed()}ms] [P2P] Disconnected!")
                val wasConnected = p2pConnected
                p2pConnected = false
                if (cleanupInProgress) {
                    finishCleanup()
                } else if (wasConnected) {
                    failP2p("The Wi-Fi Direct group disconnected.")
                }
            }

            override fun onPeerDiscoveryStarted() {
                Log.i(TAG, "[${elapsed()}ms] [P2P] Peer discovery started")
            }
            override fun onPeerDiscoveryFailed(reason: Int) {
                Log.e(TAG, "[${elapsed()}ms] [P2P] Peer discovery FAILED: reason=$reason")
                failP2p("Wi-Fi Direct peer discovery failed (reason=$reason).")
            }
            override fun onConnectRequestSent() {
                Log.i(TAG, "[${elapsed()}ms] [P2P] Connect request sent to peer")
            }
            override fun onConnectRequestFailed(reason: Int) {
                Log.e(TAG, "[${elapsed()}ms] [P2P] Connect request FAILED: reason=$reason")
                failP2p("Wi-Fi Direct connection failed (reason=$reason).")
            }
            override fun onThisDeviceChanged(device: WifiP2pDevice) {
                Log.d(TAG, "[${elapsed()}ms] [P2P] This device changed: ${device.deviceName}")
            }
            override fun connecting() {
                Log.i(TAG, "[${elapsed()}ms] [P2P] Connecting to peer...")
            }
            override fun cancelConnect() {
                Log.i(TAG, "[${elapsed()}ms] [P2P] Connect cancelled")
            }
            override fun cancelConnectFail(reason: Int) {
                Log.e(TAG, "[${elapsed()}ms] [P2P] Cancel connect failed: reason=$reason")
                if (!cleanupInProgress) {
                    failP2p("Wi-Fi Direct connection cancellation failed (reason=$reason).")
                }
            }
            override fun retryAlsoFailed() {
                Log.e(TAG, "[${elapsed()}ms] [P2P] Retry also failed")
                failP2p("Wi-Fi Direct could not connect after its retry.")
            }
        }

        val manager = p2pManager ?: return false
        val callback = p2pCallback ?: return false
        manager.addCallback(callback)
        try {
            manager.registerReceiver()
            p2pReceiverRegistered = true
        } catch (e: Exception) {
            manager.removeCallback(callback)
            Log.e(TAG, "[${elapsed()}ms] [P2P] Failed to register receiver: ${e.message}", e)
            return false
        }
        manager.resetFailCount()
        Log.i(TAG, "[${elapsed()}ms] [P2P] Registered receiver, reset fail count, starting discovery...")
        // Avoid resetting the glasses automatically while livestream mode is active.
        manager.startPeerDiscovery(allowDeviceResetOnTimeout = false)
        return true
    }

    private fun failP2p(detail: String) {
        if (cleanupInProgress || mainJob?.isActive != true || p2pFailure != null) return
        p2pFailure = detail
        Log.e(TAG, "[${elapsed()}ms] [P2P] Terminal failure: $detail")
        updateState("P2P unavailable", detail)
        mainJob?.cancel(CancellationException(detail))
    }

    /**
     * Register a listener for the glasses IP notification before starting livestream mode.
     * Logs every BLE notification received.
     */
    private fun registerNotifyListener(): Boolean {
        if (notifyRegistered) return true
        Log.i(TAG, "[${elapsed()}ms] [BLE] Registering notify listener (cmdType=2) for IP notification (0x08)...")

        notifyListener = object : GlassesDeviceNotifyListener() {
            override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
                val load = response.loadData
                val loadHex = load.take(12).joinToString(" ") { "0x${it.toString(16).padStart(2, '0')}" }
                Log.d(TAG, "[${elapsed()}ms] [BLE] Notify: cmdType=$cmdType, loadData.size=${load.size}, first12=[$loadHex]")

                if (load.size < 7) {
                    Log.w(TAG, "[${elapsed()}ms] [BLE] Notify too short (${load.size} < 7 bytes), ignoring")
                    return
                }

                val notifyType = load[6].toInt() and 0xFF
                when (notifyType) {
                    0x08 -> {
                        if (load.size >= 11) {
                            val b7 = ByteUtil.byteToInt(load[7])
                            val b8 = ByteUtil.byteToInt(load[8])
                            val b9 = ByteUtil.byteToInt(load[9])
                            val b10 = ByteUtil.byteToInt(load[10])
                            val ip = "$b7.$b8.$b9.$b10"
                            Log.i(TAG, "[${elapsed()}ms] [BLE] *** GLASSES IP RECEIVED: $ip ***")
                            Log.i(TAG, "[${elapsed()}ms] [BLE]   Raw bytes: load[7]=$b7, load[8]=$b8, load[9]=$b9, load[10]=$b10")
                            bleIpReceived = ip
                        } else {
                            Log.w(TAG, "[${elapsed()}ms] [BLE] IP notify (0x08) too short: ${load.size} bytes (need >= 11)")
                        }
                    }
                    0x09 -> {
                        val errorCode = ByteUtil.byteToInt(load.getOrNull(7) ?: 0)
                        Log.e(TAG, "[${elapsed()}ms] [BLE] P2P/WiFi ERROR (0x09): errorCode=$errorCode, raw=${load.getOrNull(7)}")
                    }
                    0x04 -> {
                        val progress = ByteUtil.byteToInt(load.getOrNull(7) ?: 0)
                        val phase = ByteUtil.byteToInt(load.getOrNull(8) ?: 0)
                        Log.d(TAG, "[${elapsed()}ms] [BLE] OTA progress (0x04): progress=$progress%, phase=$phase")
                    }
                    0x07 -> {
                        Log.i(TAG, "[${elapsed()}ms] [BLE] OTA complete (0x07)")
                    }
                    else -> {
                        Log.d(TAG, "[${elapsed()}ms] [BLE] Unknown notify type: 0x${notifyType.toString(16).padStart(2, '0')} (${load.size} bytes)")
                    }
                }
            }
        }

        val listener = notifyListener ?: return false
        return try {
            LargeDataHandler.getInstance().addOutDeviceListener(2, listener)
            notifyRegistered = true
            Log.i(TAG, "[${elapsed()}ms] [BLE] Notify listener registered successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[${elapsed()}ms] [BLE] Failed to register notify listener: ${e.message}", e)
            false
        }
    }

    /** Wait for the already-registered glasses IP notification type 0x08. */
    private suspend fun waitForBleIp(timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var pollCount = 0
        while (System.currentTimeMillis() < deadline) {
            val ip = bleIpReceived
            if (ip != null) {
                Log.i(TAG, "[${elapsed()}ms] [BLE] IP received after ${pollCount} polls: $ip")
                return ip
            }
            pollCount++
            if (pollCount % 10 == 0) {
                val remaining = (deadline - System.currentTimeMillis()) / 1000
                Log.d(TAG, "[${elapsed()}ms] [BLE] Still waiting for IP... ${remaining}s remaining (poll #$pollCount)")
            }
            delay(500)
        }
        Log.e(TAG, "[${elapsed()}ms] [BLE] Timed out waiting for IP after ${timeoutMs}ms ($pollCount polls)")
        Log.e(TAG, "[${elapsed()}ms] [BLE]   bleIpReceived=$bleIpReceived")
        Log.e(TAG, "[${elapsed()}ms] [BLE]   p2pConnected=$p2pConnected")
        Log.e(TAG, "[${elapsed()}ms] [BLE]   p2pInfo=$p2pInfo")
        return null
    }

    private suspend fun waitForP2pConnection(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (p2pConnected) {
                Log.i(TAG, "[${elapsed()}ms] [P2P] P2P group is ready: $p2pInfo")
                return true
            }
            delay(250)
        }
        Log.e(TAG, "[${elapsed()}ms] [P2P] Timed out waiting for a P2P group after ${timeoutMs}ms")
        return false
    }

    private fun hasWifiP2pPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun bindToP2pNetwork(glassesIp: String): Boolean {
        val glassesPrefix = glassesIp.substringBeforeLast('.', missingDelimiterValue = "").takeIf { it.isNotBlank() }
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: run {
                Log.w(TAG, "[${elapsed()}ms] [P2P] ConnectivityManager unavailable")
                return false
            }

        val deadline = System.currentTimeMillis() + P2P_CONNECT_TIMEOUT_MS
        var attempts = 0
        while (System.currentTimeMillis() < deadline) {
            attempts++
            val network = findP2pNetwork(connectivityManager, glassesPrefix)
            if (network != null) {
                try {
                    if (connectivityManager.bindProcessToNetwork(network)) {
                        boundP2pNetwork = network
                        Log.i(TAG, "[${elapsed()}ms] [P2P] Bound process to the P2P route for RTSP")
                        return true
                    }
                    Log.w(TAG, "[${elapsed()}ms] [P2P] bindProcessToNetwork returned false (attempt=$attempts)")
                } catch (e: Exception) {
                    Log.w(TAG, "[${elapsed()}ms] [P2P] Failed to bind RTSP to the P2P route: ${e.message}")
                }
            } else if (attempts % 4 == 0) {
                Log.i(TAG, "[${elapsed()}ms] [P2P] Waiting for a route on the glasses subnet (attempt=$attempts)")
            }
            delay(500)
        }

        Log.e(TAG, "[${elapsed()}ms] [P2P] No usable P2P route after ${P2P_CONNECT_TIMEOUT_MS}ms; RTSP will not use the default network")
        return false
    }

    private fun findP2pNetwork(
        connectivityManager: ConnectivityManager,
        glassesPrefix: String?,
    ): Network? {
        return try {
            connectivityManager.allNetworks.firstOrNull { candidate ->
                val capabilities = connectivityManager.getNetworkCapabilities(candidate) ?: return@firstOrNull false
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) {
                    return@firstOrNull false
                }

                val linkProperties = connectivityManager.getLinkProperties(candidate)
                val interfaceName = linkProperties?.interfaceName.orEmpty()
                val addresses = linkProperties?.linkAddresses
                    ?.mapNotNull { it.address.hostAddress }
                    .orEmpty()
                val matchesGlassesSubnet = glassesPrefix != null && addresses.any { it.startsWith("$glassesPrefix.") }
                val looksLikeP2p = interfaceName.contains("p2p", ignoreCase = true) ||
                    interfaceName.contains("wfd", ignoreCase = true)
                if (matchesGlassesSubnet || looksLikeP2p) {
                    Log.i(
                        TAG,
                        "[${elapsed()}ms] [P2P] Selected route: if=$interfaceName addresses=$addresses " +
                            "(matchesGlassesSubnet=$matchesGlassesSubnet)"
                    )
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[${elapsed()}ms] [P2P] Could not enumerate Wi-Fi routes: ${e.message}")
            null
        }
    }

    private fun unbindP2pNetwork() {
        if (boundP2pNetwork == null) return
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            connectivityManager?.bindProcessToNetwork(null)
            Log.d(TAG, "[${elapsed()}ms] [P2P] Restored the default process network")
        } catch (e: Exception) {
            Log.w(TAG, "[${elapsed()}ms] [P2P] Failed to restore the default network: ${e.message}")
        } finally {
            boundP2pNetwork = null
        }
    }

    private suspend fun startFrameStream(glassesIp: String) {
        val server = withContext(Dispatchers.IO) { MjpegServer(MJPEG_PORT) }
        mjpegServer = server
        val firstFrame = CompletableDeferred<Unit>()
        val receiver = RtspFrames(glassesIp, object : RtspFrames.Listener {
            override fun onFrame(jpeg: ByteArray) {
                if (server.publish(jpeg)) firstFrame.complete(Unit)
            }

            override fun onError(message: String) {
                val failure = IOException("RTSP stream failed: $message")
                if (!firstFrame.completeExceptionally(failure)) {
                    scope.launch {
                        if (mainJob?.isActive == true) {
                            updateState("Stream interrupted", failure.message.orEmpty())
                            mainJob?.cancel(CancellationException(failure.message))
                        }
                    }
                }
            }
        })
        rtspFrames = receiver
        receiver.start()

        val frameReceived = withTimeoutOrNull(STREAM_START_TIMEOUT_MS) {
            firstFrame.await()
            true
        } ?: false
        if (!frameReceived) {
            throw IOException("No video frame received from rtsp://$glassesIp:$RTSP_PORT/ch0")
        }
    }

    private fun sendStartLiveCommand() {
        try {
            LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x14, 0x01)) { _, response ->
                Log.i(
                    TAG,
                    "[${elapsed()}ms] [BLE] Live stream start response: dataType=${response.dataType}, " +
                        "error=${response.errorCode}",
                )
            }
            liveCommandSent = true
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch {
                while (isActive && liveCommandSent) {
                    delay(5_000L)
                    if (!liveCommandSent || !BleOperateManager.getInstance().isConnected) continue
                    try {
                        LargeDataHandler.getInstance().syncHeartBeat(4)
                    } catch (error: Exception) {
                        Log.e(TAG, "[${elapsed()}ms] [BLE] Failed to send livestream heartbeat", error)
                    }
                }
            }
            Log.i(TAG, "[${elapsed()}ms] [BLE] Sent live stream start command")
        } catch (error: Exception) {
            Log.e(TAG, "[${elapsed()}ms] [BLE] Failed to send live stream start command", error)
            throw IOException("Could not start livestream mode over Bluetooth", error)
        }
    }

    private fun sendStopLiveCommand() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        if (!liveCommandSent) return
        liveCommandSent = false
        if (!BleOperateManager.getInstance().isConnected) {
            Log.w(TAG, "[${elapsed()}ms] [BLE] Skipping live stream stop command because Bluetooth is disconnected")
            return
        }
        try {
            LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x15, 0x01)) { _, response ->
                Log.i(
                    TAG,
                    "[${elapsed()}ms] [BLE] Live stream stop response: dataType=${response.dataType}, " +
                        "error=${response.errorCode}",
                )
            }
            Log.i(TAG, "[${elapsed()}ms] [BLE] Sent live stream stop command")
        } catch (error: Exception) {
            Log.e(TAG, "[${elapsed()}ms] [BLE] Failed to send live stream stop command", error)
        }
    }

    private fun releaseStream() {
        rtspFrames?.close()
        rtspFrames = null
        mjpegServer?.close()
        mjpegServer = null
    }

    private fun updateState(label: String, detail: String, scanning: Boolean = false) {
        Log.d(TAG, "[${elapsed()}ms] State: '$label' | detail='$detail' | scanning=$scanning")
        _uiState.value = LivePreviewState(
            stateLabel = label,
            detail = detail,
            isScanning = scanning,
            canStart = !scanning,
            canStop = scanning,
        )
    }
}
