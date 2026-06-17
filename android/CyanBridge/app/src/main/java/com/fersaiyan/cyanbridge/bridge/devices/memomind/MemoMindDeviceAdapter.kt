package com.fersaiyan.cyanbridge.bridge.devices.memomind

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fersaiyan.cyanbridge.bridge.core.BridgeError
import com.fersaiyan.cyanbridge.bridge.core.DeviceInfo
import com.fersaiyan.cyanbridge.bridge.core.DisplayCommand
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridgeState
import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.bridge.core.GlassesDeviceAdapter
import com.fersaiyan.cyanbridge.bridge.core.InputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * [GlassesDeviceAdapter] implementation for MemoMind glasses over RFCOMM/SPP.
 *
 * ## Capabilities
 * - [GlassesCapability.TEXT_DISPLAY] — maps generic text into MemoMind schedule cards
 * - [GlassesCapability.CLEAR_DISPLAY] — clears our active card route with an empty schedule
 * - [GlassesCapability.BATTERY_STATUS] — requests battery via the confirmed RFCOMM device route
 * - [GlassesCapability.NOTIFICATIONS] — uses the confirmed notification/media metadata route
 *
 * ## Delegates
 * - Scanning: [MemoMindBleScanner]
 * - Connection / RFCOMM: [MemoMindRfcommClient]
 * - Encoding: [MemoMindPacketEncoder]
 *
 * Tag: MemoMindDeviceAdapter
 */
class MemoMindDeviceAdapter(
    private val context: Context,
) : GlassesDeviceAdapter {

    companion object {
        private const val TAG = "MemoMindDeviceAdapter"

        /** Duration (ms) to collect scan results before returning. */
        private const val SCAN_TIMEOUT_MS = 6_000L
    }

    override val adapterId: String = "memomind"
    override val displayName: String = "MemoMind"

    override val capabilities: Set<GlassesCapability> = setOf(
        GlassesCapability.TEXT_DISPLAY,
        GlassesCapability.CLEAR_DISPLAY,
        GlassesCapability.BATTERY_STATUS,
        GlassesCapability.BRIGHTNESS_CONTROL,
        GlassesCapability.NOTIFICATIONS,
    )

    // ------------------------------------------------------------------
    // Delegates
    // ------------------------------------------------------------------

    private val scanner = MemoMindBleScanner(context)
    private val rfcommClient = MemoMindRfcommClient(context)

    // ------------------------------------------------------------------
    // Observable state
    // ------------------------------------------------------------------

    private val _state = MutableStateFlow<GlassesBridgeState>(GlassesBridgeState.Disconnected)
    override val state: StateFlow<GlassesBridgeState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 16)
    override val events: Flow<InputEvent> = _events.asSharedFlow()

    /** Holds scan results across scans for UI access. */
    private val _lastScanResults = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val lastScanResults: StateFlow<List<DeviceInfo>> = _lastScanResults.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Connected RFCOMM channels (kept under the old debug property name for UI compatibility). */
    private val _discoveredGattServices = MutableStateFlow<List<String>>(emptyList())
    val discoveredGattServices: StateFlow<List<String>> = _discoveredGattServices.asStateFlow()

    @Volatile
    private var lastBatteryLevel: Int? = null

    /** When true, RFCOMM is unavailable — route display commands as Android notifications. */
    @Volatile
    var companionMode: Boolean = false
        private set

    init {
        // Mirror the RFCOMM client's state into our adapter state.
        scope.launch {
            rfcommClient.state.collect { clientState ->
                _state.value = clientState
            }
        }

        // Forward raw chunks as input events (parsing is still protocol-specific and partial).
        scope.launch {
            rfcommClient.notifications.collect { data ->
                Log.d(TAG, "RFCOMM chunk received: ${data.size} bytes")
            }
        }

        scope.launch {
            rfcommClient.controlFrames.collect { frame ->
                MemoMindPacketEncoder.extractJsonPayload(frame, 0x01, 0x06)?.let { json ->
                    runCatching { JSONObject(json).optInt("bat") }
                        .getOrNull()
                        ?.takeIf { it >= 0 }
                        ?.let { lastBatteryLevel = it }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    override suspend fun scan(): List<DeviceInfo> {
        Log.i(TAG, "scan() — starting BLE discovery for ${SCAN_TIMEOUT_MS}ms")
        _state.value = GlassesBridgeState.Scanning
        val results = mutableListOf<DeviceInfo>()

        try {
            withTimeout(SCAN_TIMEOUT_MS) {
                scanner.scan().collect { device ->
                    results.add(device)
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // Expected after SCAN_TIMEOUT_MS
        }

        _lastScanResults.value = results.toList()
        Log.i(TAG, "scan() — found ${results.size} MemoMind device(s)")

        // Restore state after scan completes (adapter state mirroring from gattClient
        // may not emit during scan, leaving state stuck at "Scanning").
        if (_state.value is GlassesBridgeState.Scanning) {
            _state.value = GlassesBridgeState.Disconnected
        }

        return results.toList()
    }

    override suspend fun connect(device: DeviceInfo) {
        Log.i(TAG, "connect(${device.name} / ${device.address})")
        companionMode = false
        val result = rfcommClient.connect(device.address)
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            Log.w(TAG, "RFCOMM connection failed: ${error?.message} — entering companion mode")
            companionMode = true
            _state.value = GlassesBridgeState.Connected
            return
        }
        _discoveredGattServices.value = rfcommClient.connectedChannels
    }

    override suspend fun disconnect() {
        Log.i(TAG, "disconnect()")
        rfcommClient.disconnect()
        _discoveredGattServices.value = emptyList()
    }

    // ------------------------------------------------------------------
    // Display
    // ------------------------------------------------------------------

    override suspend fun showText(command: DisplayCommand.Text): Result<Unit> {
        Log.i(TAG, "showText: \"${command.text}\" (companionMode=$companionMode)")
        if (companionMode) {
            return postCompanionNotification("CyanBridge", command.text)
        }
        if (!rfcommClient.isConnected()) {
            return Result.failure(BridgeError.NotConnected())
        }
        val packet = MemoMindPacketEncoder.encodeNotification(
            title = "CyanBridge",
            body = command.text,
        )
        return sendPacketToGlasses(packet)
    }

    override suspend fun showLines(command: DisplayCommand.Lines): Result<Unit> {
        if (companionMode) {
            return postCompanionNotification("CyanBridge", command.lines.joinToString("\n"))
        }
        if (!rfcommClient.isConnected()) {
            return Result.failure(BridgeError.NotConnected())
        }
        val packet = MemoMindPacketEncoder.encodeLines(command.lines)
        return sendPacketToGlasses(packet)
    }

    override suspend fun showCard(command: DisplayCommand.Card): Result<Unit> {
        if (companionMode) {
            return postCompanionNotification(command.title, command.body)
        }
        if (!rfcommClient.isConnected()) {
            return Result.failure(BridgeError.NotConnected())
        }
        val packet = MemoMindPacketEncoder.encodeScheduleCard(
            title = command.title,
            body = command.body,
        )
        return sendPacketToGlasses(packet)
    }

    override suspend fun clearDisplay(): Result<Unit> {
        Log.i(TAG, "clearDisplay() (companionMode=$companionMode)")
        if (companionMode) {
            return Result.success(Unit) // No-op in companion mode
        }
        if (!rfcommClient.isConnected()) {
            return Result.failure(BridgeError.NotConnected())
        }
        val packet = MemoMindPacketEncoder.encodeEmptySchedule()
        return sendPacketToGlasses(packet)
    }

    // ------------------------------------------------------------------
    // Packet I/O
    // ------------------------------------------------------------------

    /**
     * Send a packet to the glasses over the primary RFCOMM socket.
     */
    private suspend fun sendPacketToGlasses(packet: ByteArray): Result<Unit> {
        val result = rfcommClient.writeCommand(packet)
        if (result.isFailure) {
            Log.e(TAG, "Failed to send packet: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    // ------------------------------------------------------------------
    // Device
    // ------------------------------------------------------------------

    override suspend fun setBrightness(level: Int): Result<Unit> {
        Log.i(TAG, "setBrightness($level)")
        if (!rfcommClient.isConnected()) {
            return Result.failure(BridgeError.NotConnected())
        }
        val packet = MemoMindPacketEncoder.encodeSetBrightness(level)
        return rfcommClient.writeCommand(packet)
    }

    override suspend fun requestBattery(): Result<Int> {
        Log.i(TAG, "requestBattery()")
        if (!rfcommClient.isConnected()) {
            return Result.failure(BridgeError.NotConnected())
        }
        val packet = MemoMindPacketEncoder.encodeBatteryRequest()
        val sendResult = sendPacketToGlasses(packet)
        if (sendResult.isFailure) {
            return Result.failure(sendResult.exceptionOrNull()!!)
        }
        return try {
            val json = withTimeout(3_000L) {
                rfcommClient.controlFrames.first { frame ->
                    MemoMindPacketEncoder.extractJsonPayload(frame, 0x01, 0x06) != null
                }.let { frame ->
                    MemoMindPacketEncoder.extractJsonPayload(frame, 0x01, 0x06)
                        ?: throw BridgeError.ProtocolError("Battery response had no JSON payload")
                }
            }
            val battery = JSONObject(json).optInt("bat", -1)
            if (battery < 0) {
                Result.failure(BridgeError.ProtocolError("Battery response missing 'bat' field"))
            } else {
                lastBatteryLevel = battery
                Result.success(battery)
            }
        } catch (t: Throwable) {
            lastBatteryLevel?.let { cached ->
                Log.w(TAG, "Battery request failed (${t.message}), falling back to cached value $cached%")
                Result.success(cached)
            } ?: Result.failure(
                if (t is BridgeError) t else BridgeError.Timeout("Timed out waiting for MemoMind battery response")
            )
        }
    }

    // ------------------------------------------------------------------
    // Audio
    // ------------------------------------------------------------------

    override suspend fun startMic(): Result<Unit> {
        Log.w(TAG, "startMic is not yet supported by MemoMind adapter")
        return Result.failure(BridgeError.UnsupportedCapability(GlassesCapability.MICROPHONE_AUDIO))
    }

    override suspend fun stopMic(): Result<Unit> {
        Log.w(TAG, "stopMic is not yet supported by MemoMind adapter")
        return Result.failure(BridgeError.UnsupportedCapability(GlassesCapability.MICROPHONE_AUDIO))
    }

    // ------------------------------------------------------------------
    // Convenience accessors for ViewModel / UI
    // ------------------------------------------------------------------

    /** Whether the RFCOMM client is currently connected. */
    fun isConnected(): Boolean = companionMode || rfcommClient.isConnected()

    /**
     * Post an Android notification that the MemoMind app will forward to the glasses.
     * Used in companion mode when RFCOMM is unavailable.
     */
    private fun postCompanionNotification(title: String, body: String): Result<Unit> {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            // Use the same channel as daily facts — MemoMind only forwards notifications
            // from channels it recognises, and "daily_facts" is known to work.
            val channelId = "daily_facts"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val existing = nm.getNotificationChannel(channelId)
                if (existing == null) {
                    val channel = android.app.NotificationChannel(
                        channelId,
                        "Daily facts",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = "Daily prompts to verify Local Agent facts"
                    }
                    nm.createNotificationChannel(channel)
                }
            }
            val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(com.fersaiyan.cyanbridge.R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), notification)
            Log.i(TAG, "Companion notification posted (daily_facts channel): $title — $body")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post companion notification", e)
            Result.failure(e)
        }
    }

    /**
     * Release all resources held by this adapter.
     *
     * Call from [GlassesViewModel.onCleared] to avoid leaking the coroutine scope
     * and RFCOMM client across configuration changes.
     */
    fun destroy() {
        Log.i(TAG, "destroy() — releasing adapter resources")
        scope.cancel()
        rfcommClient.close()
    }
}
