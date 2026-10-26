package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.bridge.core.BatteryReporting
import com.fersaiyan.cyanbridge.bridge.core.DeviceInfo
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridgeState
import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.bridge.core.GlassesDeviceAdapter
import com.fersaiyan.cyanbridge.shared.ble.IosBleManager
import com.fersaiyan.cyanbridge.shared.ble.IosMemoMindAccessoryTransport
import com.fersaiyan.cyanbridge.shared.ble.IosMemoMindBleTransport
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.devices.memomind.GmCodec
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindAssistantTrigger
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindAudioListener
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindDisplayAdapter
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindHud
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindLinkControl
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindRecordingLink
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindSession
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindTransport
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.MemoMindPanelCommand
import com.fersaiyan.cyanbridge.shared.glasses.MemoMindPanelLabels
import com.fersaiyan.cyanbridge.shared.glasses.MemoMindUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * MemoMind glasses on iOS (Android: MemoMindGlasses over the SPP MemoMindManager). The shared
 * [MemoMindSession] runs over iAP2 when this build declares the accessory protocols and the
 * glasses offer them ([IosMemoMindAccessoryTransport]), otherwise over BLE GATT
 * ([IosMemoMindBleTransport], connected by the app's BLE manager). HUD output goes through
 * [MemoMindHud]; a primary long press (Web Bridge plugin) asks a voice question, which uses the
 * glasses' HFP microphone and speaker like other headsets. iOS gives apps no access to other
 * apps' notifications, so phone notifications are not forwarded.
 */
internal class IosMemoMindGlasses(
    private val bleManager: IosBleManager,
    private val scope: CoroutineScope,
    private val host: IosGlassesHost,
    /** Pairing scan, reconnect and BLE disconnect of the app controller. */
    private val bleConnection: GlassesConnectionControl,
    /** Starts the assistant's voice question (HFP microphone). */
    private val onVoiceQuestion: () -> Unit,
) : IosGlassesDevice {
    override val profile: GlassesFamilyProfile = GlassesFamilyProfiles.memoMind

    private enum class Link { DISCONNECTED, CONNECTING, CONNECTED }

    private var link = Link.DISCONNECTED
    private var route: MemoMindTransport? = null
    private var bringUp: Job? = null
    private var pollJob: Job? = null
    private var microphoneLabel: String? = null
    private var lastError: String? = null
    private val bridgeState = MutableStateFlow<GlassesBridgeState>(GlassesBridgeState.Disconnected)

    /** True while MemoMind is the selected class; only then the glasses drive the dashboard. */
    var isSelected: Boolean = false
        private set

    /** The command link of the current connection, so the session survives route changes. */
    private val transport = object : MemoMindTransport {
        override val isConnected: Boolean get() = route?.isConnected == true
        override val maxFrameBytes: Int get() = route?.maxFrameBytes ?: GmCodec.MAX_FRAME_BYTES
        override val awaitsFragmentAcks: Boolean get() = route?.awaitsFragmentAcks ?: false
        override val recordingLink: MemoMindRecordingLink? get() = route?.recordingLink

        override suspend fun writeCommand(frame: ByteArray) =
            (route ?: error("MemoMind is not connected")).writeCommand(frame)
    }

    /** The MemoMind session for every feature (and for a web plugin host to attach to). */
    val session = MemoMindSession(transport)
    val hud = MemoMindHud(session, localEpochSeconds = ::localEpochSeconds)

    private val ble = IosMemoMindBleTransport(bleManager).also { it.attach(session::onCommandBytes) }
    private val accessory = IosMemoMindAccessoryTransport(
        onCommandBytes = session::onCommandBytes,
        onRecordingBytes = session::onRecordingBytes,
        onClosed = { if (route === accessoryRoute) linkLost("MemoMind iAP2 session ended") },
    )
    private val accessoryRoute: MemoMindTransport get() = accessory

    /** MemoMind as a display adapter (HUD text, cards, notifications, input events). */
    override val answerDisplay: GlassesDeviceAdapter get() = displayAdapter

    val displayAdapter = MemoMindDisplayAdapter(
        session = session,
        hud = hud,
        link = object : MemoMindLinkControl {
            override val bridgeState: StateFlow<GlassesBridgeState> = this@IosMemoMindGlasses.bridgeState.asStateFlow()
            override val selectedDevice: DeviceInfo?
                get() = session.state.value.deviceInfo?.let { info ->
                    val id = info.serialNumber ?: info.macAddress ?: "memomind"
                    DeviceInfo(id, info.name ?: "MemoMind", id, MemoMindDisplayAdapter.ADAPTER_ID)
                }

            override suspend fun connect(device: DeviceInfo) = connection.reconnect()

            override suspend fun disconnect() = connection.disconnect()
        },
    )

    init {
        scope.launch { session.state.collect { render() } }
        scope.launch {
            session.events.collect { event ->
                if (isSelected && MemoMindAssistantTrigger.isVoiceQuestion(event)) {
                    PlatformLogger.i(TAG, "Voice question requested by the glasses button")
                    onVoiceQuestion()
                }
            }
        }
    }

    override val isLinked: Boolean get() = link == Link.CONNECTED

    override val connection: GlassesConnectionControl = object : GlassesConnectionControl {
        override fun scan() = bleConnection.scan()

        override fun reconnect() {
            if (!connectAccessoryIfAvailable()) bleConnection.reconnect()
        }

        override fun disconnect() {
            if (route === accessoryRoute) {
                accessory.close()
                linkLost(null)
            } else {
                bleConnection.disconnect()
            }
        }
    }

    override val battery = object : BatteryReporting {
        override val pollsPeriodically: Boolean = false

        override fun requestBattery() = launchCommand("refresh battery") { session.refreshStatus() }

        override fun refreshAfterSelection() {
            if (isLinked) requestBattery()
        }
    }

    /** MemoMind became (or stopped being) the selected class. */
    fun select(selected: Boolean) {
        if (isSelected == selected) return
        isSelected = selected
        if (!selected && route != null) {
            if (route === accessoryRoute) accessory.close()
            linkLost(null)
        }
        render()
    }

    /**
     * Opens the iAP2 link when this build declares the MemoMind protocols and the glasses offer
     * them. Returns false when iAP2 is not available, so the caller uses Bluetooth LE.
     */
    fun connectAccessoryIfAvailable(): Boolean {
        val target = IosMemoMindAccessoryTransport.findAccessory() ?: return false
        if (link == Link.DISCONNECTED) startBringUp(accessory) { accessory.open(target) }
        return true
    }

    /** The shared BLE link came up with MemoMind selected. */
    fun onBleConnected() {
        if (route === accessoryRoute) return
        startBringUp(ble) { ble.awaitReady() }
    }

    /** The shared BLE link dropped. */
    fun onBleDisconnected() {
        if (route === ble) linkLost(null)
    }

    private fun startBringUp(target: MemoMindTransport, open: suspend () -> Unit) {
        if (bringUp?.isActive == true) return
        route = target
        link = Link.CONNECTING
        lastError = null
        render()
        bringUp = scope.launch {
            try {
                open()
                session.initialize()
                // Button input and Web Bridge drawing need the Web Bridge plugin; probe once.
                session.probeWebBridge()
                link = Link.CONNECTED
                bridgeState.value = GlassesBridgeState.Connected
                startPolling()
                PlatformLogger.i(
                    TAG,
                    "Ready over ${if (target === accessoryRoute) "iAP2" else "BLE"} " +
                        "model=${session.state.value.deviceInfo?.model} fw=${session.state.value.deviceInfo?.firmwareVersion} " +
                        "webBridge=${session.state.value.webBridgeRunning}",
                )
                render()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                PlatformLogger.w(TAG, "Bring-up failed: ${error.message}")
                if (target === accessoryRoute) accessory.close() else runCatching { bleManager.disconnect() }
                linkLost(error.message ?: "MemoMind did not answer")
            }
        }
    }

    private fun linkLost(error: String?) {
        if (route == null && link == Link.DISCONNECTED) return
        bringUp?.cancel()
        pollJob?.cancel()
        route = null
        link = Link.DISCONNECTED
        bridgeState.value = GlassesBridgeState.Disconnected
        session.onDisconnected()
        if (error != null) lastError = error
        render()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(BATTERY_POLL_MS)
                runCatching { session.refreshStatus() }.onFailure { PlatformLogger.i(TAG, "Battery poll failed: ${it.message}") }
            }
        }
    }

    override fun handlePanelAction(action: GlassesDashboardAction): Boolean {
        val command = (action as? GlassesDashboardAction.MemoMindPanel)?.command ?: return false
        when (command) {
            MemoMindPanelCommand.CONNECT -> connection.reconnect()
            MemoMindPanelCommand.DISCONNECT -> connection.disconnect()
            MemoMindPanelCommand.SEND_TEST_NOTIFICATION -> launchCommand("send a notification") {
                hud.showNotification(MemoMindHud.DEFAULT_APP_NAME, MemoMindHud.DEFAULT_APP_NAME, TEST_NOTIFICATION, MemoMindHud.DEFAULT_PACKAGE)
            }
            MemoMindPanelCommand.SHOW_TEST_TEXT -> launchCommand("show text") { hud.showText(TEST_TEXT) }
            MemoMindPanelCommand.CLEAR_DISPLAY -> launchCommand("clear the display") { hud.clear() }
            MemoMindPanelCommand.COMFORT_BRIGHTNESS -> launchCommand("set brightness") { session.setBrightness(COMFORT_BRIGHTNESS) }
            MemoMindPanelCommand.TEST_MICROPHONE -> testMicrophone()
            MemoMindPanelCommand.TOGGLE_NOTIFICATION_FORWARDING -> host.updateDashboard { it.copy(agentLastError = NOTIFICATIONS_UNAVAILABLE) }
            // No Web plugin host on iOS yet; the panel hides the entry without its label.
            MemoMindPanelCommand.OPEN_WEB_PLUGINS -> return false
        }
        return true
    }

    /**
     * With the iAP2 recording stream: counts the Opus frames of a 3-second capture. Over BLE the
     * firmware has no recording channel, so the test asks a voice question through HFP instead.
     */
    private fun testMicrophone() {
        if (!isLinked) return showError("MemoMind must be connected to test the microphone")
        if (transport.recordingLink == null) {
            microphoneLabel = "Microphone: Bluetooth LE has no recording stream; asking through the HFP microphone"
            render()
            onVoiceQuestion()
            return
        }
        scope.launch {
            var frames = 0
            microphoneLabel = "Microphone: listening for 3 seconds…"
            render()
            microphoneLabel = try {
                session.audioListener = MemoMindAudioListener { frames += it.size }
                session.startMicrophone()
                delay(MIC_TEST_MS)
                session.stopMicrophone()
                "Microphone: $frames Opus frames in 3 s (iAP2)"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                "Microphone test failed: ${error.message}"
            } finally {
                session.audioListener = null
            }
            render()
        }
    }

    private fun launchCommand(operation: String, block: suspend () -> Unit) {
        if (!isLinked) return showError("MemoMind must be connected to $operation")
        scope.launch {
            lastError = try {
                block()
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                PlatformLogger.w(TAG, "Could not $operation: ${error.message}")
                error.message
            }
            render()
        }
    }

    private fun showError(message: String) {
        lastError = message
        render()
    }

    /** The MemoMind panel, battery and connection label. */
    private fun render() {
        if (!isSelected) return
        val device = session.state.value
        val info = device.deviceInfo
        val label = when (link) {
            Link.CONNECTED -> "Connected"
            Link.CONNECTING -> "Connecting"
            Link.DISCONNECTED -> "Disconnected"
        }
        host.updateDashboard { state ->
            state.copy(
                // While a MemoMind link exists (or none at all), its state is the connection; the BLE
                // label stays for a BLE link that is still up without one.
                connectionLabel = if (link != Link.DISCONNECTED || !host.isBleConnected) label else state.connectionLabel,
                batteryPercent = device.batteryPercent ?: state.batteryPercent,
                memoMind = MemoMindUiState(
                    connectionLabel = label,
                    deviceName = info?.name,
                    detail = info?.let { "Model ${it.model ?: "-"}  Firmware ${it.firmwareVersion ?: "-"}" },
                    batteryPercent = device.batteryPercent,
                    microphoneLabel = microphoneLabel,
                    lastError = lastError ?: device.lastError,
                    canConnect = link == Link.DISCONNECTED,
                    canDisconnect = link != Link.DISCONNECTED || host.isBleConnected,
                    canSend = link == Link.CONNECTED,
                    forwardingNotifications = false,
                    labels = PANEL_LABELS,
                ),
            )
        }
    }

    private fun localEpochSeconds(): Long {
        val now = platformCurrentTimeMillis()
        return now / 1000 + platformUtcOffsetMinutes(now) * 60L
    }

    private companion object {
        const val TAG = "IosMemoMind"
        const val BATTERY_POLL_MS = 5 * 60_000L
        const val MIC_TEST_MS = 3_000L
        const val COMFORT_BRIGHTNESS = 7
        const val TEST_NOTIFICATION = "MemoMind connection is working."
        const val TEST_TEXT = "CyanBridge is connected to MemoMind.\nAssistant answers and plugin text appear here."
        const val NOTIFICATIONS_UNAVAILABLE =
            "iOS does not let apps read other apps' notifications (ANCS is offered to the glasses, not to apps), " +
                "so CyanBridge cannot forward them. Its own cards and assistant text still reach the HUD."
        val PANEL_LABELS = MemoMindPanelLabels(
            description = "On iPhone, MemoMind connects over Bluetooth LE (or iAP2 in builds that declare the " +
                "MemoMind accessory protocols). Close the official MemoMind app first: the glasses accept one app " +
                "connection. Voice questions use the glasses' Bluetooth microphone; button questions need the " +
                "Web Bridge plugin running on the glasses.",
            testMicrophone = "Test microphone",
            forwardNotificationsOn = "Notifications (not on iOS)",
            forwardNotificationsOff = "Notifications (not on iOS)",
        )
    }
}
