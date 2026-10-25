package com.fersaiyan.cyanbridge.devices.memomind

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.bridge.core.DeviceInfo
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridge
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridgeState
import com.fersaiyan.cyanbridge.bridge.notifications.NotificationForwarderService
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindAssistantTrigger
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindDisplayAdapter
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindEvent
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindHud
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindLinkControl
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindSession
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.TimeZone
import kotlin.math.abs

data class MemoMindState(
    val link: MemoMindLinkState = MemoMindLinkState.DISCONNECTED,
    val address: String? = null,
    val name: String? = null,
    /** What the glasses reported over the shared session. */
    val device: MemoMindSessionState = MemoMindSessionState(),
    val forwardingNotifications: Boolean = false,
    /** Microphone test progress or result. */
    val microphoneLabel: String? = null,
    val lastError: String? = null,
) {
    val isConnected: Boolean get() = link == MemoMindLinkState.CONNECTED

    /** "Connected" / "Connecting" / "Disconnected": the labels the dashboard localizes. */
    val connectionLabel: String
        get() = when (link) {
            MemoMindLinkState.CONNECTED -> "Connected"
            MemoMindLinkState.CONNECTING -> "Connecting"
            MemoMindLinkState.DISCONNECTED -> "Disconnected"
        }

    val batteryPercent: Int? get() = device.batteryPercent
}

/**
 * Owns the MemoMind SPP link and the shared [MemoMindSession] on it: connection with bring-up
 * and reconnection, battery polling, the display bridge adapter (plugins, notifications, HUD
 * tests), the Opus microphone and the glasses' button events.
 */
class MemoMindManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transport = MemoMindSppTransport(appContext)
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow(MemoMindState())
    private val _bridgeState = MutableStateFlow<GlassesBridgeState>(GlassesBridgeState.Disconnected)
    private val _voiceQuestionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var connectJob: Job? = null
    private var pollJob: Job? = null

    @Volatile private var userDisconnected = false

    val session: MemoMindSession = transport.session
    val hud = MemoMindHud(session, localEpochSeconds = ::localEpochSeconds)
    val microphone = MemoMindMicrophone(appContext, session, scope)
    val state: StateFlow<MemoMindState> = _state.asStateFlow()

    /** The glasses asked for a voice question (see [MemoMindAssistantTrigger]). */
    val voiceQuestionRequests: SharedFlow<Unit> = _voiceQuestionRequests.asSharedFlow()

    /** Connected and brought up: the session accepts requests. */
    val ready: StateFlow<Boolean> = _bridgeState.map { it == GlassesBridgeState.Connected }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * True while a MemoMind Web plugin runs in the foreground: glasses input then belongs to the
     * plugin and starts no voice question or display-bridge gesture.
     */
    @Volatile var webPluginOwnsInput = false

    private val adapter = MemoMindDisplayAdapter(
        session = session,
        hud = hud,
        link = object : MemoMindLinkControl {
            override val bridgeState: StateFlow<GlassesBridgeState> = _bridgeState
            override val selectedDevice: DeviceInfo?
                get() = _state.value.address?.let { DeviceInfo(it, _state.value.name ?: "MemoMind", it, MemoMindDisplayAdapter.ADAPTER_ID) }

            override suspend fun connect(device: DeviceInfo) = this@MemoMindManager.connect(device.address, device.name)
            override suspend fun disconnect() = this@MemoMindManager.disconnect()
        },
    )

    init {
        GlassesBridge.registerAdapter(adapter)
        transport.onLinkLost = ::onLinkLost
        scope.launch { session.state.collect { device -> _state.update { it.copy(device = session.state.value) } } }
        scope.launch {
            transport.state.collect { link ->
                _state.update { it.copy(link = link) }
                if (link != MemoMindLinkState.CONNECTED) _bridgeState.value = GlassesBridgeState.Disconnected
            }
        }
        scope.launch { session.events.collect(::onEvent) }
    }

    fun isConnected(): Boolean = transport.isConnected

    /** Connects and runs the bring-up; reconnects on its own after an unexpected link loss. */
    fun connect(address: String, name: String? = null) {
        userDisconnected = false
        _state.update { it.copy(address = address, name = name ?: it.name, lastError = null) }
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { connectWithRetries(address) }
    }

    fun disconnect() {
        userDisconnected = true
        connectJob?.cancel()
        pollJob?.cancel()
        setNotificationForwarding(false)
        transport.disconnect()
        _bridgeState.value = GlassesBridgeState.Disconnected
        Log.i(TAG, "Disconnected by the user")
    }

    fun refreshBattery() = launchCommand("refresh battery") { session.refreshStatus() }

    fun sendTestNotification() = launchCommand("send a notification") {
        hud.showNotification(
            appContext.getString(R.string.memomind_test_notification_title),
            appContext.getString(R.string.memomind_test_notification_title),
            appContext.getString(R.string.memomind_test_notification_body),
            appContext.packageName,
        )
    }

    fun showTestText() = launchCommand("show text") { hud.showText(appContext.getString(R.string.memomind_test_text)) }

    fun clearDisplay() = launchCommand("clear the display") { hud.clear() }

    /** Brightness as a percentage; MemoMind has levels 0..10. */
    fun setBrightness(percent: Int) = launchCommand("set brightness") { session.setBrightness((percent.coerceIn(0, 100) + 5) / 10) }

    /**
     * Forwards phone notifications to the HUD while connected. Returns false when the
     * notification listener access is missing (the caller opens its settings).
     */
    fun setNotificationForwarding(enabled: Boolean): Boolean {
        if (enabled && !NotificationForwarderService.isListenerBound(appContext)) return false
        if (enabled || _state.value.forwardingNotifications) NotificationForwarderService.setEnabled(enabled)
        _state.update { it.copy(forwardingNotifications = enabled) }
        return true
    }

    /** Captures three seconds of the Opus microphone stream and reports frames and peak level. */
    fun testMicrophone() {
        if (!requireConnected("test the microphone")) return
        scope.launch {
            _state.update { it.copy(microphoneLabel = appContext.getString(R.string.memomind_microphone_listening)) }
            var peak = 0
            var frames = 0
            val label = try {
                val capture = microphone.open(
                    onPcm = { pcm -> peak = maxOf(peak, peakLevel(pcm)) },
                    onOpus = { frames += it.size },
                )
                delay(MIC_TEST_MS)
                capture.close()
                appContext.getString(R.string.memomind_microphone_result, frames, peak * 100 / Short.MAX_VALUE)
            } catch (error: MemoMindMicrophoneBusyException) {
                appContext.getString(R.string.memomind_microphone_busy, error.owner)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Microphone test failed", error)
                appContext.getString(R.string.memomind_microphone_failed, error.message ?: error::class.simpleName)
            }
            Log.i(TAG, "Microphone test: frames=$frames peak=$peak")
            _state.update { it.copy(microphoneLabel = label) }
        }
    }

    private suspend fun connectWithRetries(address: String) {
        var attempt = 0
        while (scope.isActive && !userDisconnected) {
            val result = operationMutex.withLock { connectOnce(address) }
            if (result.isSuccess) return
            val error = result.exceptionOrNull()
            _state.update { it.copy(lastError = error?.message ?: "MemoMind connection failed") }
            if (error is CancellationException || error is SecurityException || attempt >= RECONNECT_DELAYS_MS.size) return
            delay(RECONNECT_DELAYS_MS[attempt++])
        }
    }

    private suspend fun connectOnce(address: String): Result<Unit> {
        Log.i(TAG, "Connecting")
        transport.connect(address).onFailure { return Result.failure(it) }
        return try {
            session.initialize()
            // The Web Bridge plugin only runs when the official App started it; probe once.
            session.probeWebBridge()
            _bridgeState.value = GlassesBridgeState.Connected
            GlassesBridge.setActiveAdapter(MemoMindDisplayAdapter.ADAPTER_ID)
            _state.update { state -> state.copy(name = session.state.value.deviceInfo?.name ?: state.name, lastError = null) }
            startBatteryPolling()
            Log.i(
                TAG,
                "Ready model=${session.state.value.deviceInfo?.model} fw=${session.state.value.deviceInfo?.firmwareVersion} " +
                    "webBridge=${session.state.value.webBridgeRunning} service=${transport.commandServiceUuid}",
            )
            Result.success(Unit)
        } catch (error: CancellationException) {
            transport.disconnect()
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Bring-up failed: ${error.message}")
            transport.disconnect()
            Result.failure(error)
        }
    }

    private fun onLinkLost() {
        pollJob?.cancel()
        if (_state.value.forwardingNotifications) setNotificationForwarding(false)
        val address = _state.value.address ?: return
        if (userDisconnected) return
        Log.i(TAG, "Link lost; reconnecting")
        if (connectJob?.isActive != true) connectJob = scope.launch {
            delay(RECONNECT_DELAYS_MS.first())
            connectWithRetries(address)
        }
    }

    private fun startBatteryPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(BATTERY_POLL_MS)
                runCatching { session.refreshStatus() }.onFailure { Log.i(TAG, "Battery poll failed: ${it.message}") }
            }
        }
    }

    private fun onEvent(event: MemoMindEvent) {
        if (webPluginOwnsInput) return
        MemoMindAssistantTrigger.inputEvent(event)?.let { GlassesBridge.onInputEvent(MemoMindDisplayAdapter.ADAPTER_ID, it) }
        when {
            MemoMindAssistantTrigger.isVoiceQuestion(event) -> {
                Log.i(TAG, "Voice question requested by the glasses button")
                _voiceQuestionRequests.tryEmit(Unit)
            }
            event is MemoMindEvent.Button || event is MemoMindEvent.HeadGesture -> Log.i(TAG, "Input $event")
        }
    }

    private fun launchCommand(operation: String, block: suspend () -> Unit) {
        if (!requireConnected(operation)) return
        scope.launch {
            try {
                block()
                _state.update { it.copy(lastError = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Could not $operation: ${error.message}")
                _state.update { it.copy(lastError = error.message) }
            }
        }
    }

    private fun requireConnected(operation: String): Boolean {
        if (transport.isConnected) return true
        val message = "MemoMind must be connected to $operation"
        Log.w(TAG, message)
        _state.update { it.copy(lastError = message) }
        return false
    }

    private fun localEpochSeconds(): Long {
        val now = System.currentTimeMillis()
        return (now + TimeZone.getDefault().getOffset(now)) / 1000
    }

    private fun peakLevel(pcm: ByteArray): Int {
        var peak = 0
        var index = 0
        while (index + 1 < pcm.size) {
            val sample = (pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)
            peak = maxOf(peak, abs(sample.toShort().toInt()))
            index += 2
        }
        return peak.coerceAtMost(Short.MAX_VALUE.toInt())
    }

    companion object {
        private const val TAG = "MemoMindManager"
        private const val BATTERY_POLL_MS = 5 * 60_000L
        private const val MIC_TEST_MS = 3_000L
        private val RECONNECT_DELAYS_MS = longArrayOf(5_000L, 15_000L, 30_000L)

        @Volatile
        private var instance: MemoMindManager? = null

        fun getInstance(context: Context): MemoMindManager =
            instance ?: synchronized(this) {
                instance ?: MemoMindManager(context.applicationContext).also { instance = it }
            }
    }
}
