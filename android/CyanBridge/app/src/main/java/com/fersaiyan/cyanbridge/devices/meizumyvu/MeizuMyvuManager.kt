package com.fersaiyan.cyanbridge.devices.meizumyvu

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.bridge.core.BridgeError
import com.fersaiyan.cyanbridge.bridge.core.DeviceInfo
import com.fersaiyan.cyanbridge.bridge.core.DisplayCommand
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridge
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridgeState
import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.bridge.core.GlassesDeviceAdapter
import com.fersaiyan.cyanbridge.bridge.core.InputEvent
import com.myvu.client.core.LogBus
import com.myvu.client.service.ConnectionManager
import com.myvu.client.service.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CyanBridge facade around the hardware-verified MYVU StarryNet client.
 *
 * The upstream client owns the non-optional order of BLE ECDH bonding, relay UUID
 * discovery, RFCOMM session setup, init burst, heartbeat, and HFP/A2DP profile
 * connection. This facade exposes that live transport to CyanBridge's dashboard
 * and native display bridge without reimplementing or bypassing its protocol.
 */
class MeizuMyvuManager private constructor(context: Context) : ConnectionManager.Listener {
    companion object {
        private const val TAG = "MeizuMyvu"
        private const val ADAPTER_ID = "meizu_myvu"
        private const val MAX_PROMPTER_TEXT = 8_000
        private const val MAX_TITLE_LENGTH = 80
        private val MAC_ADDRESS_REGEX = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")

        @Volatile private var instance: MeizuMyvuManager? = null

        fun getInstance(context: Context): MeizuMyvuManager =
            instance ?: synchronized(this) {
                instance ?: MeizuMyvuManager(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val connection = ConnectionManager(appContext, this)
    private val adapter = MyvuDisplayAdapter(this)
    private val _state = MutableStateFlow(MeizuMyvuUiState())
    val state: StateFlow<MeizuMyvuUiState> = _state.asStateFlow()
    private val connectionRequestLock = Any()
    private var autoSearchFallbackPending = false

    init {
        GlassesBridge.registerAdapter(adapter)
    }

    fun connect(macAddress: String, requestContext: Context = appContext) {
        // Start immediately while the caller is foregrounded. The service then
        // adopts the same singleton transport and keeps it alive off-screen.
        connectTransport(macAddress)
        MeizuMyvuConnectionService.start(requestContext, macAddress)
    }

    internal fun connectTransport(macAddress: String) {
        val address = macAddress.trim()
        if (address.isBlank()) {
            _state.value = _state.value.copy(lastError = "No MYVU Bluetooth address was selected")
            return
        }
        synchronized(connectionRequestLock) {
            val currentState = connection.state()
            if (currentState != ConnectionState.IDLE && currentState != ConnectionState.FAILED) {
                Log.d(TAG, "MYVU connect ignored: already $currentState")
                return
            }
            GlassesBridge.setActiveAdapter(ADAPTER_ID)
            autoSearchFallbackPending = true
            _state.value = _state.value.copy(lastError = null, selectedAddress = address)
            connection.start(address)
        }
    }

    fun disconnect() {
        stopTransport()
        appContext.stopService(MeizuMyvuConnectionService.intent(appContext))
    }

    internal fun stopTransport() {
        synchronized(connectionRequestLock) {
            autoSearchFallbackPending = false
            connection.stop()
        }
        _state.value = _state.value.copy(connectionLabel = "Disconnected", lastError = null)
    }

    fun diagnosticsSnapshot(): String = buildString {
        appendLine("State: ${connection.state()}")
        appendLine("Selected address: ${redactMacAddresses(_state.value.selectedAddress ?: "none")}")
        appendLine("SPP UUID: ${connection.sppUuid() ?: "not received"}")
        appendLine("Transport history:")
        LogBus.history().takeLast(150).forEach { line ->
            val safeLine = when {
                line.contains("-> action msgId=") -> line.substringBefore("-> action msgId=") + "-> application payload redacted"
                line.contains("<- msgId=") -> line.substringBefore("<- msgId=") + "<- application payload redacted"
                line.contains("mirrored notification", ignoreCase = true) -> "notification payload redacted"
                else -> line
            }
            appendLine(redactMacAddresses(safeLine))
        }
    }.trimEnd()

    private fun redactMacAddresses(value: String): String = value.replace(MAC_ADDRESS_REGEX) { match ->
        val parts = match.value.split(':')
        "XX:XX:XX:XX:${parts[4]}:${parts[5]}"
    }

    fun isReady(): Boolean = connection.state() == ConnectionState.READY

    fun sendTestNotification() {
        if (!requireReady("send a notification")) return
        connection.sendTestNotification("CyanBridge", "MYVU connection is working")
    }

    fun syncClock() {
        if (!requireReady("sync the clock")) return
        connection.syncTime()
    }

    fun setBrightness(levelPercent: Int) {
        if (!requireReady("set brightness")) return
        connection.setBrightness((levelPercent.coerceIn(0, 100) + 5) / 10)
    }

    fun showTeleprompter(title: String, text: String) {
        if (!requireReady("show content")) return
        connection.openTeleprompter(text.take(MAX_PROMPTER_TEXT), title.take(MAX_TITLE_LENGTH))
    }

    fun queryBattery() {
        if (!requireReady("request battery status")) return
        connection.query("get_device_info")
    }

    private fun requireReady(operation: String): Boolean {
        if (isReady()) return true
        val message = "MYVU must finish connecting before it can $operation"
        Log.w(TAG, message)
        _state.value = _state.value.copy(lastError = message)
        return false
    }

    override fun onStateChanged(connectionState: ConnectionState) {
        val info = connection.glassesInfo()
        val failureReason = if (connectionState == ConnectionState.FAILED) {
            LogBus.history().asReversed()
                .firstOrNull { it.contains("!!") }
                ?.substringAfter("!!")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val label = when (connectionState) {
            ConnectionState.IDLE -> "Disconnected"
            ConnectionState.CONNECTING -> "Connecting to MYVU over BLE"
            ConnectionState.BONDING -> "Preparing MYVU Bluetooth bond"
            ConnectionState.PAIRING -> "Exchanging MYVU encryption keys"
            ConnectionState.SESSION -> "Starting MYVU display session"
            ConnectionState.READY -> "MYVU connected"
            ConnectionState.FAILED -> "MYVU connection failed"
        }
        _state.value = MeizuMyvuUiState(
            connectionLabel = label,
            protocolState = connectionState.name,
            selectedAddress = _state.value.selectedAddress,
            deviceName = info?.name?.takeIf { it.isNotBlank() },
            batteryPercent = info?.battery,
            lastError = if (connectionState == ConnectionState.FAILED) failureReason ?: label else null,
        )
        adapter.updateBridgeState(_state.value)
        if (connectionState == ConnectionState.READY) {
            synchronized(connectionRequestLock) {
                autoSearchFallbackPending = false
            }
            GlassesBridge.setActiveAdapter(ADAPTER_ID)
        } else if (connectionState == ConnectionState.FAILED) {
            synchronized(connectionRequestLock) {
                if (autoSearchFallbackPending) {
                    autoSearchFallbackPending = false
                    Log.w(TAG, "Direct MYVU connection failed; retrying with upstream auto-search: ${failureReason ?: "unknown reason"}")
                    connection.startAutoSearch()
                }
            }
        }
    }

    private class MyvuDisplayAdapter(private val manager: MeizuMyvuManager) : GlassesDeviceAdapter {
        override val adapterId: String = ADAPTER_ID
        override val displayName: String = "Meizu MYVU / Star Air"
        override val capabilities = setOf(
            GlassesCapability.TEXT_DISPLAY,
            GlassesCapability.LINE_DISPLAY,
            GlassesCapability.CARD_DISPLAY,
            GlassesCapability.CLEAR_DISPLAY,
            GlassesCapability.BATTERY_STATUS,
            GlassesCapability.BRIGHTNESS_CONTROL,
            GlassesCapability.MICROPHONE_AUDIO,
            GlassesCapability.SPEAKER_AUDIO,
            GlassesCapability.NOTIFICATIONS,
        )
        private val _bridgeState = MutableStateFlow<GlassesBridgeState>(GlassesBridgeState.Disconnected)
        override val state: StateFlow<GlassesBridgeState> = _bridgeState.asStateFlow()
        private val _events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 16)
        override val events: Flow<InputEvent> = _events.asSharedFlow()

        init {
            manager.state.value.let(::updateBridgeState)
        }

        override suspend fun scan(): List<DeviceInfo> = manager.state.value.selectedAddress?.let { address ->
            listOf(DeviceInfo(address, manager.state.value.deviceName ?: "MYVU", address, adapterId))
        }.orEmpty()

        override suspend fun connect(device: DeviceInfo) = manager.connect(device.address)
        override suspend fun disconnect() = manager.disconnect()

        override suspend fun showText(command: DisplayCommand.Text): Result<Unit> = runCommand {
            manager.showTeleprompter("CyanBridge", command.text)
        }

        override suspend fun showLines(command: DisplayCommand.Lines): Result<Unit> = runCommand {
            manager.showTeleprompter("CyanBridge", command.lines.joinToString("\n"))
        }

        override suspend fun showCard(command: DisplayCommand.Card): Result<Unit> = runCommand {
            manager.showTeleprompter(command.title, "${command.title}\n\n${command.body}")
        }

        override suspend fun clearDisplay(): Result<Unit> = runCommand {
            manager.showTeleprompter("CyanBridge", "")
        }

        override suspend fun setBrightness(level: Int): Result<Unit> = runCommand { manager.setBrightness(level) }

        override suspend fun requestBattery(): Result<Int> {
            if (!manager.isReady()) return Result.failure(BridgeError.NotConnected())
            manager.queryBattery()
            return manager.state.value.batteryPercent?.let { Result.success(it) }
                ?: Result.failure(BridgeError.ProtocolError("MYVU did not report battery information"))
        }

        // The upstream transport brings HFP up after its relay is ready. Android
        // voice plugins then select that Bluetooth communication device themselves.
        override suspend fun startMic(): Result<Unit> =
            if (manager.isReady()) Result.success(Unit) else Result.failure(BridgeError.NotConnected())

        override suspend fun stopMic(): Result<Unit> = Result.success(Unit)

        private fun runCommand(block: () -> Unit): Result<Unit> {
            if (!manager.isReady()) return Result.failure(BridgeError.NotConnected())
            block()
            return Result.success(Unit)
        }

        fun updateBridgeState(ui: MeizuMyvuUiState) {
            _bridgeState.value = when (ui.protocolState) {
                ConnectionState.READY.name -> GlassesBridgeState.Connected
                ConnectionState.FAILED.name -> GlassesBridgeState.Error(ui.lastError ?: "MYVU connection failed")
                ConnectionState.IDLE.name -> GlassesBridgeState.Disconnected
                else -> GlassesBridgeState.Connecting
            }
        }
    }

    data class MeizuMyvuUiState(
        val connectionLabel: String = "Disconnected",
        val protocolState: String = ConnectionState.IDLE.name,
        val selectedAddress: String? = null,
        val deviceName: String? = null,
        val batteryPercent: Int? = null,
        val lastError: String? = null,
    )

}
