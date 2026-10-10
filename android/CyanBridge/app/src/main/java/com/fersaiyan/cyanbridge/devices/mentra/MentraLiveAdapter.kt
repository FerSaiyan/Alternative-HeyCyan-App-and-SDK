package com.fersaiyan.cyanbridge.devices.mentra

import com.fersaiyan.cyanbridge.bridge.core.BridgeError
import com.fersaiyan.cyanbridge.bridge.core.DeviceInfo
import com.fersaiyan.cyanbridge.bridge.core.DisplayCommand
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridgeState
import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.bridge.core.GlassesDeviceAdapter
import com.fersaiyan.cyanbridge.bridge.core.InputEvent
import com.mentra.bluetoothsdk.Device
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mentra Live has a camera and audio, but no display. Never return fake success
 * for text, card, or brightness commands. SDK hardware callbacks own the link.
 */
class MentraLiveAdapter(private val manager: MentraLiveManager) : GlassesDeviceAdapter {
    override val adapterId = "mentra_live"
    override val displayName = "Mentra Live"
    override val capabilities = setOf(
        GlassesCapability.MICROPHONE_AUDIO,
        GlassesCapability.SPEAKER_AUDIO,
        GlassesCapability.PHOTO_CAPTURE,
        GlassesCapability.BUTTON_INPUT,
        GlassesCapability.BATTERY_STATUS,
    )
    override val state: StateFlow<GlassesBridgeState> = manager.bridgeState
    override val events: Flow<InputEvent> = manager.inputEvents

    private fun Device.toDeviceInfo() = DeviceInfo(
        id = id,
        name = name,
        address = address ?: id,
        adapterId = adapterId,
        rssi = rssi,
    )

    override suspend fun scan(): List<DeviceInfo> {
        val firstResults = CompletableDeferred<List<Device>>()
        manager.scan { devices ->
            if (devices.isNotEmpty() && !firstResults.isCompleted) firstResults.complete(devices)
        }
        // The dedicated pairing screen shows progressive results instead.
        val devices = withTimeoutOrNull(10_500L) { firstResults.await() }
            ?: manager.discoveredDevices()
        return devices.map { it.toDeviceInfo() }
    }

    override suspend fun connect(device: DeviceInfo) {
        require(device.adapterId == adapterId) { "Not a Mentra device" }
        val match = manager.findDiscovered(device.id)
            ?: throw BridgeError.ConnectionFailed("Mentra device must be scanned before connecting")
        manager.connect(match)
    }
    override suspend fun disconnect() = manager.disconnect()

    override suspend fun showText(command: DisplayCommand.Text): Result<Unit> =
        Result.failure(BridgeError.UnsupportedCapability(GlassesCapability.TEXT_DISPLAY))
    override suspend fun showLines(command: DisplayCommand.Lines): Result<Unit> =
        Result.failure(BridgeError.UnsupportedCapability(GlassesCapability.LINE_DISPLAY))
    override suspend fun showCard(command: DisplayCommand.Card): Result<Unit> =
        Result.failure(BridgeError.UnsupportedCapability(GlassesCapability.CARD_DISPLAY))
    override suspend fun clearDisplay(): Result<Unit> =
        Result.failure(BridgeError.UnsupportedCapability(GlassesCapability.CLEAR_DISPLAY))
    override suspend fun setBrightness(level: Int): Result<Unit> =
        Result.failure(BridgeError.UnsupportedCapability(GlassesCapability.BRIGHTNESS_CONTROL))

    override suspend fun requestBattery(): Result<Int> =
        manager.batteryLevel()?.let { Result.success(it) }
            ?: Result.failure(BridgeError.NotConnected())

    override suspend fun startMic(): Result<Unit> =
        runCatching { manager.setMicrophoneEnabled(true) }
    override suspend fun stopMic(): Result<Unit> =
        runCatching { manager.setMicrophoneEnabled(false) }
}
