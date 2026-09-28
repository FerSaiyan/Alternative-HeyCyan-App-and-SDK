package com.fersaiyan.cyanbridge.bridge.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-neutral commands that a glasses transport translates into its
 * device-specific protocol.
 */
sealed class DisplayCommand {
    data class Text(
        val text: String,
        val priority: DisplayPriority = DisplayPriority.NORMAL,
        val ttlMs: Long? = null,
    ) : DisplayCommand()

    data class Lines(
        val lines: List<String>,
        val page: Int = 0,
        val totalPages: Int? = null,
    ) : DisplayCommand()

    data class Card(
        val title: String,
        val body: String,
        val actions: List<DisplayAction> = emptyList(),
    ) : DisplayCommand()

    data object Clear : DisplayCommand()

    companion object
}

enum class DisplayPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT,
}

data class DisplayAction(
    val label: String,
    val actionId: String,
)

sealed class InputEvent {
    data class Button(
        val button: String,
        val gesture: GestureType,
    ) : InputEvent()

    data class Touch(
        val side: Side? = null,
        val gesture: GestureType,
    ) : InputEvent()

    data class HeadGesture(
        val direction: HeadDirection,
        val confidence: Float? = null,
    ) : InputEvent()

    data class VoiceText(
        val text: String,
        val isFinal: Boolean = true,
    ) : InputEvent()

    data class Battery(
        val level: Int,
        val charging: Boolean? = null,
    ) : InputEvent()
}

enum class GestureType {
    SINGLE_TAP,
    DOUBLE_TAP,
    LONG_PRESS,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    SWIPE_UP,
    SWIPE_DOWN,
}

enum class Side {
    LEFT,
    RIGHT,
}

enum class HeadDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    NOD,
    SHAKE,
}

enum class GlassesCapability {
    TEXT_DISPLAY,
    LINE_DISPLAY,
    CARD_DISPLAY,
    IMAGE_DISPLAY,
    CLEAR_DISPLAY,
    TOUCH_INPUT,
    BUTTON_INPUT,
    HEAD_GESTURE_INPUT,
    BATTERY_STATUS,
    BRIGHTNESS_CONTROL,
    MICROPHONE_AUDIO,
    PHOTO_CAPTURE,
    SPEAKER_AUDIO,
    NOTIFICATIONS,
    DASHBOARD,
    PAGINATION,
}

sealed class GlassesBridgeState {
    data object Disconnected : GlassesBridgeState()
    data object Scanning : GlassesBridgeState()
    data object Connecting : GlassesBridgeState()
    data object Connected : GlassesBridgeState()
    data class Error(val message: String, val cause: Throwable? = null) : GlassesBridgeState()
}

data class DeviceInfo(
    val id: String,
    val name: String,
    val address: String,
    val adapterId: String,
    val rssi: Int? = null,
    val firmwareVersion: String? = null,
    val batteryLevel: Int? = null,
)

sealed class BridgeError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data class UnsupportedCapability(val capability: GlassesCapability) :
        BridgeError("Capability not supported: $capability")

    data class NotConnected(override val message: String = "Not connected to glasses") :
        BridgeError(message)

    data class ConnectionFailed(override val message: String, override val cause: Throwable? = null) :
        BridgeError(message, cause)

    data class ProtocolError(override val message: String, override val cause: Throwable? = null) :
        BridgeError(message, cause)

    data class Timeout(override val message: String = "Operation timed out") :
        BridgeError(message)
}

/**
 * Contract implemented by Android and iOS transports. It intentionally has no
 * Bluetooth, Wi-Fi, or vendor SDK types so platform code owns those concerns.
 */
interface GlassesDeviceAdapter {
    val adapterId: String
    val displayName: String
    val capabilities: Set<GlassesCapability>
    val state: StateFlow<GlassesBridgeState>
    val events: Flow<InputEvent>

    suspend fun scan(): List<DeviceInfo>
    suspend fun connect(device: DeviceInfo)
    suspend fun disconnect()

    suspend fun showText(command: DisplayCommand.Text): Result<Unit>
    suspend fun showLines(command: DisplayCommand.Lines): Result<Unit>
    suspend fun showCard(command: DisplayCommand.Card): Result<Unit>
    suspend fun clearDisplay(): Result<Unit>

    suspend fun setBrightness(level: Int): Result<Unit>
    suspend fun requestBattery(): Result<Int>

    suspend fun startMic(): Result<Unit>
    suspend fun stopMic(): Result<Unit>
}
