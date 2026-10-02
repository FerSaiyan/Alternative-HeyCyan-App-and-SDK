package com.fersaiyan.cyanbridge.ota

import android.content.Context
import android.Manifest
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.devices.heycyan.HeyCyanLiveVideoView
import com.fersaiyan.cyanbridge.devices.heycyan.HeyCyanVideoFrameOutput
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
import java.net.InetSocketAddress
import java.net.Socket
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

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
 * HeyCyan realtime preview over the stock V821 Wi-Fi/RTSP path.
 *
 * The official app realtime-preview command is 02 01 14 01. CyanBridge arms
 * the BLE IP listener and Wi-Fi Direct discovery first, sends that command via
 * the vendor glasses-control channel, binds to the resulting P2P network, then
 * plays the RTSP feed with LibVLC.
 *
 * The official app URL (8554/ch0) is tried first. The V821/live555 endpoint
 * found in firmware analysis (554/testH264VideoStreamer) remains a fallback.
 *
 * All logcat tags: "LivePreview". Filter: `adb logcat -s LivePreview`
 */
class LivePreviewManager(
    private val context: Context,
) {

    private val _uiState = MutableStateFlow(LivePreviewState())
    val uiState: StateFlow<LivePreviewState> = _uiState

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var videoView: HeyCyanLiveVideoView? = null
    private var viewAttached: CompletableDeferred<Unit>? = null
    private var frameOutput: HeyCyanVideoFrameOutput? = null
    private var frameConsumer: ((Bitmap) -> Unit)? = null
    private var audioMuted = true
    private var liveCommandAttempted = false
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
        // Realtime-preview command recovered from the official app and
        // hardware-tested by vortex1024. The trailing byte is the enable flag.
        private val START_LIVE_COMMAND = byteArrayOf(0x02, 0x01, 0x14, 0x01)
        private val STOP_LIVE_COMMAND = byteArrayOf(0x02, 0x01, 0x14, 0x00)
        private val WIFI_IP_COMMAND = byteArrayOf(0x02, 0x03)

        // Official app first; V821/live555 firmware endpoint second.
        private val RTSP_PORTS = intArrayOf(8554, 554)
        private val STREAM_PATHS = arrayOf(
            "ch0",
            "testH264VideoStreamer",
            "live",
            "stream",
            "video",
            "h264",
            "",
        )
        private const val PROBE_TIMEOUT_MS = 8_000L
        private const val BLE_IP_TIMEOUT_MS = 45_000L
        private const val P2P_CONNECT_TIMEOUT_MS = 20_000L
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

    /** Headless decoder for Walking Aid. Caller owns each delivered bitmap. */
    fun startFrames(onFrame: (Bitmap) -> Unit, onSessionFinished: () -> Unit) {
        check(!isActive) { "HeyCyan live stream is already active" }
        frameConsumer = onFrame
        audioMuted = true
        start(onSessionFinished)
    }

    suspend fun stopAndJoin() {
        val active = mainJob
        stop()
        active?.join()
    }

    fun getPlayer(): MediaPlayer? = player

    fun attachVideoView(view: HeyCyanLiveVideoView) {
        if (videoView !== view) videoView?.bindPlayer(null)
        videoView = view
        player?.let {
            view.bindPlayer(it)
            viewAttached?.complete(Unit)
        }
    }

    fun detachVideoView(view: HeyCyanLiveVideoView) {
        view.bindPlayer(null)
        if (videoView === view) videoView = null
    }

    fun setAudioMuted(muted: Boolean) {
        audioMuted = muted
        player?.setVolume(if (muted) 0 else 100)
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
        liveCommandAttempted = false
        releasePlayer()
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
        if (liveCommandAttempted && BleOperateManager.getInstance().isConnected) sendStopLiveCommand()
        liveCommandAttempted = false
        val playerWasActive = player != null
        releasePlayer()
        if (playerWasActive) Log.i(TAG, "[${elapsed()}ms] cleanup: LibVLC released")

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

            updateState("Connecting", "Checking Bluetooth and Wi-Fi...", scanning = true)
            if (!BleOperateManager.getInstance().isConnected) {
                updateState("BLE disconnected", "Connect to HeyCyan glasses over Bluetooth first.")
                return
            }
            if (!hasWifiP2pPermission()) {
                updateState("P2P permission required", "Grant Nearby devices or Location permission before starting preview.")
                return
            }
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager?.isWifiEnabled != true) {
                updateState("Wi-Fi disabled", "Enable Wi-Fi before starting live preview.")
                return
            }

            // Do both before the BLE trigger: the IP notify and peer appearance
            // can arrive immediately after V821 realtime mode starts.
            if (!registerNotifyListener()) {
                updateState("Listener unavailable", "Could not receive the glasses Wi-Fi IP notification.")
                return
            }
            updateState("P2P connecting", "Looking for the HeyCyan Wi-Fi Direct peer...", scanning = true)
            if (!startP2p()) {
                updateState("P2P unavailable", "Could not start Wi-Fi Direct discovery.")
                return
            }

            updateState("Starting live mode", "Starting the glasses realtime camera...", scanning = true)
            Log.i(TAG, "[${elapsed()}ms] Sending realtime-preview command 02 01 14 01")
            sendStartLiveCommand()
            delay(350)
            requestWifiIp()

            val glassesIp = waitForBleIp(BLE_IP_TIMEOUT_MS)
            if (glassesIp == null) {
                updateState("No IP", "The glasses did not report their Wi-Fi Direct IP. Reconnect and try again.")
                return
            }
            if (!waitForP2pConnection(P2P_CONNECT_TIMEOUT_MS)) {
                updateState("P2P unavailable", "The glasses reported $glassesIp, but the Wi-Fi Direct group did not connect.")
                return
            }
            if (!bindToP2pNetwork(glassesIp)) {
                updateState("P2P route unavailable", "Could not route RTSP through the glasses Wi-Fi Direct network.")
                return
            }

            updateState("Opening stream", "Trying the official HeyCyan RTSP endpoint...", scanning = true)
            val streamUrl = probeRtsp(glassesIp)
            if (streamUrl == null) {
                updateState("No RTSP found", "Realtime mode is active, but no compatible RTSP endpoint answered.")
                return
            }

            Log.i(TAG, "HEYCYAN LIVESTREAM SUCCESS url=$streamUrl total=${elapsed()}ms")
            _uiState.value = LivePreviewState(
                stateLabel = "Playing",
                detail = streamUrl,
                isPlaying = true,
                streamUrl = streamUrl,
                canStart = false,
                canStop = true,
            )
            awaitCancellation()
        } catch (e: CancellationException) {
            Log.i(TAG, "[${elapsed()}ms] HEYCYAN LIVESTREAM CANCELLED")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[${elapsed()}ms] HeyCyan livestream failed: ${e.message}", e)
            updateState("Error", e.message ?: "HeyCyan live preview failed")
        } finally {
            frameConsumer = null
            cleanup()
        }
    }

    private fun sendStartLiveCommand() {
        liveCommandAttempted = true
        runCatching {
            LargeDataHandler.getInstance().glassesControl(START_LIVE_COMMAND) { _, response ->
                Log.i(TAG, "[${elapsed()}ms] live start response type=${response.dataType} error=${response.errorCode} work=${response.workTypeIng}")
            }
        }.onFailure { throw IOException("Could not send HeyCyan realtime-preview command", it) }
    }

    private fun sendStopLiveCommand() {
        runCatching {
            LargeDataHandler.getInstance().glassesControl(STOP_LIVE_COMMAND) { _, response ->
                Log.i(TAG, "[${elapsed()}ms] live stop response type=${response.dataType} error=${response.errorCode} work=${response.workTypeIng}")
            }
        }.onFailure { Log.w(TAG, "Could not send HeyCyan realtime-preview stop command", it) }
    }

    private fun requestWifiIp() {
        if (!BleOperateManager.getInstance().isConnected) return
        runCatching {
            LargeDataHandler.getInstance().glassesControl(WIFI_IP_COMMAND) { _, _ -> }
        }.onFailure { Log.d(TAG, "Wi-Fi IP poll failed: ${it.message}") }
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
                    p2pManager?.connectToDevice(target)
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
        // Never let generic discovery timeout send 0x0F while realtime preview owns V821.
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
     * Register the glasses IP listener before sending the validated realtime-preview trigger.
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
            bleIpReceived?.let {
                Log.i(TAG, "[${elapsed()}ms] [BLE] IP received after $pollCount polls: $it")
                return it
            }
            pollCount++
            if (pollCount == 1 || pollCount % 6 == 0) requestWifiIp()
            delay(500)
        }
        Log.e(TAG, "[${elapsed()}ms] [BLE] Timed out waiting for glasses IP")
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

    /**
     * Probe RTSP server on the glasses IP.
     * Logs every port check and LibVLC attempt with timing.
     */
    private suspend fun probeRtsp(glassesIp: String): String? {
        var totalAttempts = 0

        for (port in RTSP_PORTS) {
            if (mainJob?.isActive != true) {
                Log.w(TAG, "[${elapsed()}ms] [RTSP] Probe cancelled (job inactive)")
                return null
            }

            Log.i(TAG, "[${elapsed()}ms] [RTSP] Checking port $glassesIp:$port...")
            val portStartTime = System.currentTimeMillis()
            val portOpen = withContext(Dispatchers.IO) {
                isPortOpen(glassesIp, port, 2000)
            }
            val portElapsed = System.currentTimeMillis() - portStartTime
            Log.i(TAG, "[${elapsed()}ms] [RTSP] Port $port: ${if (portOpen) "OPEN" else "CLOSED"} (${portElapsed}ms)")

            if (!portOpen) continue

            for (path in STREAM_PATHS) {
                if (mainJob?.isActive != true) {
                    Log.w(TAG, "[${elapsed()}ms] [RTSP] Probe cancelled mid-path (job inactive)")
                    return null
                }
                totalAttempts++

                val url = if (path.isEmpty()) "rtsp://$glassesIp:$port/"
                          else "rtsp://$glassesIp:$port/$path"

                Log.i(TAG, "[${elapsed()}ms] [RTSP] Attempt #$totalAttempts: $url")
                _uiState.value = _uiState.value.copy(detail = "Trying: $url")

                val attemptStartTime = System.currentTimeMillis()
                val success = tryPlayUrl(url)
                val attemptElapsed = System.currentTimeMillis() - attemptStartTime

                if (success) {
                    Log.i(TAG, "[${elapsed()}ms] [RTSP] *** SUCCESS: $url (${attemptElapsed}ms) ***")
                    return url
                } else {
                    Log.d(TAG, "[${elapsed()}ms] [RTSP] Failed: $url (${attemptElapsed}ms)")
                    updateState("Opening stream", "Trying the next HeyCyan RTSP endpoint...", scanning = true)
                }
            }
        }

        Log.e(TAG, "[${elapsed()}ms] [RTSP] All probes failed: $totalAttempts attempts across ${RTSP_PORTS.size} ports")
        return null
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

        Log.e(TAG, "[${elapsed()}ms] [P2P] No usable P2P route after ${P2P_CONNECT_TIMEOUT_MS}ms; RTSP probe will not use the default network")
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

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Try one RTSP URL with the same LibVLC stack proven on Eyevue. Visible
     * preview attaches a VLC view; Walking Aid attaches an ImageReader sink.
     */
    private suspend fun tryPlayUrl(url: String): Boolean = withContext(Dispatchers.Main.immediate) {
        releasePlayer()
        val ready = CompletableDeferred<Unit>()
        val attached = CompletableDeferred<Unit>()
        viewAttached = attached
        val vlc = LibVLC(context)
        libVlc = vlc
        val vlcPlayer = MediaPlayer(vlc)
        player = vlcPlayer
        try {
            val media = Media(vlc, Uri.parse(url))
            media.addOption(":codec=avcodec")
            media.addOption(":rtsp-tcp")
            media.addOption(":network-caching=300")
            media.addOption(":live-caching=100")
            media.addOption(":drop-late-frames=true")
            media.addOption(":skip-frames=true")
            vlcPlayer.media = media
            media.release()
            vlcPlayer.setVolume(if (audioMuted) 0 else 100)

            var playing = false
            var videoOutputReady = false
            fun completeWhenReady() {
                if (playing && videoOutputReady && !ready.isCompleted) ready.complete(Unit)
            }
            fun fail(error: Throwable) {
                if (!ready.isCompleted) {
                    ready.completeExceptionally(error)
                } else if (player === vlcPlayer && mainJob?.isActive == true) {
                    scope.launch {
                        updateState("Stream interrupted", error.message ?: "HeyCyan RTSP stream stopped")
                        mainJob?.cancel(CancellationException("HeyCyan RTSP stream stopped"))
                    }
                }
            }

            vlcPlayer.setEventListener(MediaPlayer.EventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        playing = true
                        Log.i(TAG, "[${elapsed()}ms] [LibVLC] Playing $url")
                        completeWhenReady()
                    }
                    MediaPlayer.Event.Vout -> if (event.voutCount > 0) {
                        videoOutputReady = true
                        val track = vlcPlayer.currentVideoTrack
                        Log.i(TAG, "[${elapsed()}ms] [LibVLC] video=${track?.width}x${track?.height}")
                        completeWhenReady()
                    }
                    MediaPlayer.Event.EndReached -> fail(IOException("HeyCyan RTSP stream ended"))
                    MediaPlayer.Event.EncounteredError -> fail(IOException("HeyCyan RTSP playback error"))
                    MediaPlayer.Event.Buffering -> Log.d(TAG, "[${elapsed()}ms] [LibVLC] buffering=${event.buffering}%")
                    else -> Unit
                }
            })

            // Publishing the player before play lets Compose attach the inline
            // VLC surface while this candidate is buffering.
            _uiState.value = LivePreviewState(
                stateLabel = "Buffering",
                detail = url,
                isScanning = true,
                isPlaying = true,
                streamUrl = url,
                canStart = false,
                canStop = true,
            )
            val consumer = frameConsumer
            if (consumer != null) {
                frameOutput = HeyCyanVideoFrameOutput(
                    onFrame = consumer,
                    onError = { error -> if (!ready.isCompleted) ready.completeExceptionally(error) },
                ).also { it.attach(vlcPlayer) }
            } else {
                videoView?.let { attachVideoView(it) }
            }
            vlcPlayer.play()
            if (consumer == null && videoView == null) {
                withTimeoutOrNull(2_500L) { attached.await() }
            }

            val success = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                ready.await()
                true
            } == true
            if (!success) releasePlayer()
            success
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.w(TAG, "[${elapsed()}ms] [LibVLC] failed $url: ${error.message}")
            releasePlayer()
            false
        } finally {
            viewAttached = null
        }
    }

    private fun releasePlayer() {
        val oldPlayer = player
        val oldVlc = libVlc
        oldPlayer?.setEventListener(null)
        videoView?.bindPlayer(null)
        frameOutput?.close(oldPlayer)
        frameOutput = null
        player = null
        libVlc = null
        if (oldPlayer != null || oldVlc != null) {
            kotlin.concurrent.thread(name = "HeyCyanVlcRelease", isDaemon = true) {
                runCatching { oldPlayer?.stop() }
                runCatching { oldPlayer?.release() }
                runCatching { oldVlc?.release() }
            }
        }
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
