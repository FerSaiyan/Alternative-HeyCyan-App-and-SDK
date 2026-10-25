package com.fersaiyan.cyanbridge.shared.devices.memomind

import com.fersaiyan.cyanbridge.bridge.core.BridgeError
import com.fersaiyan.cyanbridge.bridge.core.DeviceInfo
import com.fersaiyan.cyanbridge.bridge.core.DisplayCommand
import com.fersaiyan.cyanbridge.bridge.core.GestureType
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridgeState
import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.bridge.core.GlassesDeviceAdapter
import com.fersaiyan.cyanbridge.bridge.core.GlassesNotificationDisplay
import com.fersaiyan.cyanbridge.bridge.core.InputEvent
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Maps the app's generic display output onto MemoMind screens. With a Web Bridge plugin
 * running on the glasses, text and cards are drawn as Web Bridge objects; otherwise text goes
 * to the native teleprompter and cards and notifications to native notification cards (the
 * only text surfaces the firmware offers without a plugin).
 *
 * [localEpochSeconds] is the phone's wall clock in seconds adjusted by its zone offset (the
 * notification `ts` convention).
 */
class MemoMindHud(
    private val session: MemoMindSession,
    private val localEpochSeconds: () -> Long,
    private val appName: String = DEFAULT_APP_NAME,
    private val packageName: String = DEFAULT_PACKAGE,
) {
    suspend fun showText(text: String) = showLines(text.lines())

    suspend fun showLines(lines: List<String>) {
        if (drawOnWebBridge { webBridgeText(lines.joinToString("\n")) }) return
        session.showTeleprompter(lines)
    }

    suspend fun showCard(title: String, body: String) {
        if (drawOnWebBridge { webBridgeCard(title, body) }) return
        showNotification(appName, title, body, packageName)
    }

    /** A phone notification as a native card (never drawn over a running plugin). */
    suspend fun showNotification(sourceApp: String, title: String, body: String, sourcePackage: String) {
        session.showNotification(
            title = title,
            content = body,
            appName = sourceApp,
            packageName = sourcePackage,
            timestampSeconds = localEpochSeconds(),
        )
    }

    /** Clears what this app drew: Web Bridge objects and the native teleprompter. */
    suspend fun clear() {
        if (session.state.value.webBridgeRunning == true) {
            runCatching { session.sendWebBridge(MemoMindWebBridge.CHANNEL_CLEAR, MemoMindWebBridge.clear()) }
                .onFailure { rethrowCancellation(it) }
        }
        if (session.state.value.teleprompterActive) session.stopTeleprompter()
    }

    private suspend fun webBridgeText(text: String) {
        session.sendWebBridge(MemoMindWebBridge.CHANNEL_CLEAR, MemoMindWebBridge.clear())
        session.sendWebBridge(
            MemoMindWebBridge.CHANNEL_TEXT,
            MemoMindWebBridge.text(TEXT_ID, MARGIN, MARGIN, CONTENT_WIDTH, MemoMindWebBridge.DISPLAY_HEIGHT - 2 * MARGIN, text),
        )
    }

    private suspend fun webBridgeCard(title: String, body: String) {
        session.sendWebBridge(MemoMindWebBridge.CHANNEL_CLEAR, MemoMindWebBridge.clear())
        session.sendWebBridge(
            MemoMindWebBridge.CHANNEL_TEXT,
            MemoMindWebBridge.text(TEXT_ID, MARGIN, MARGIN, CONTENT_WIDTH, TITLE_HEIGHT, title),
        )
        session.sendWebBridge(
            MemoMindWebBridge.CHANNEL_TEXT,
            MemoMindWebBridge.text(
                BODY_ID,
                MARGIN,
                MARGIN + TITLE_HEIGHT + MARGIN,
                CONTENT_WIDTH,
                MemoMindWebBridge.DISPLAY_HEIGHT - TITLE_HEIGHT - 3 * MARGIN,
                body,
            ),
        )
    }

    /** Draws with the Web Bridge when it runs; on a rejection falls back to the native screens. */
    private suspend fun drawOnWebBridge(draw: suspend () -> Unit): Boolean {
        if (session.state.value.webBridgeRunning != true) return false
        return try {
            draw()
            true
        } catch (error: Throwable) {
            rethrowCancellation(error)
            PlatformLogger.w(TAG, "Web Bridge drawing failed, using native screens: ${error.message}")
            session.markWebBridgeStopped()
            false
        }
    }

    private fun rethrowCancellation(error: Throwable) {
        if (error is CancellationException) throw error
    }

    companion object {
        private const val TAG = "MemoMindHud"
        const val DEFAULT_APP_NAME = "CyanBridge"
        const val DEFAULT_PACKAGE = "com.fersaiyan.cyanbridge"
        private const val TEXT_ID = 1
        private const val BODY_ID = 2
        private const val MARGIN = 16
        private const val TITLE_HEIGHT = 48
        private const val CONTENT_WIDTH = MemoMindWebBridge.DISPLAY_WIDTH - 2 * MARGIN
    }
}

/** Which glasses input starts an assistant voice question. */
object MemoMindAssistantTrigger {
    /** A long press of the primary button, reported by the Web Bridge plugin. */
    fun isVoiceQuestion(event: MemoMindEvent): Boolean =
        event is MemoMindEvent.Button && event.button == MemoMindButton.PRIMARY && event.action == MemoMindButtonAction.LONG

    /** The generic [InputEvent] for a button event, or null for releases and unknown input. */
    fun inputEvent(event: MemoMindEvent): InputEvent? {
        if (event !is MemoMindEvent.Button) return null
        val gesture = when (event.button) {
            MemoMindButton.PRIMARY -> when (event.action) {
                MemoMindButtonAction.SINGLE -> GestureType.SINGLE_TAP
                MemoMindButtonAction.DOUBLE -> GestureType.DOUBLE_TAP
                MemoMindButtonAction.LONG, MemoMindButtonAction.VERY_LONG -> GestureType.LONG_PRESS
                else -> return null
            }
            MemoMindButton.UP, MemoMindButton.PAGE_UP, MemoMindButton.SCROLL_UP -> GestureType.SWIPE_UP
            MemoMindButton.DOWN, MemoMindButton.PAGE_DOWN, MemoMindButton.SCROLL_DOWN -> GestureType.SWIPE_DOWN
            MemoMindButton.LEFT -> GestureType.SWIPE_LEFT
            MemoMindButton.RIGHT -> GestureType.SWIPE_RIGHT
            MemoMindButton.BACK, MemoMindButton.HOME -> GestureType.SINGLE_TAP
            else -> return null
        }
        val name = when (event.button) {
            MemoMindButton.BACK -> "back"
            MemoMindButton.HOME -> "home"
            else -> "primary"
        }
        return InputEvent.Button(name, gesture)
    }
}

/** What the platform owns behind a [MemoMindDisplayAdapter]: the link and its state. */
interface MemoMindLinkControl {
    val bridgeState: StateFlow<GlassesBridgeState>
    val selectedDevice: DeviceInfo?
    suspend fun connect(device: DeviceInfo)
    suspend fun disconnect()
}

/** MemoMind as a [GlassesDeviceAdapter] for the display bridge (plugins, notifications, runtimes). */
class MemoMindDisplayAdapter(
    private val session: MemoMindSession,
    private val hud: MemoMindHud,
    private val link: MemoMindLinkControl,
) : GlassesDeviceAdapter, GlassesNotificationDisplay {
    override val adapterId: String = ADAPTER_ID
    override val displayName: String = "MemoMind"
    override val capabilities: Set<GlassesCapability> = setOf(
        GlassesCapability.TEXT_DISPLAY,
        GlassesCapability.LINE_DISPLAY,
        GlassesCapability.CARD_DISPLAY,
        GlassesCapability.CLEAR_DISPLAY,
        GlassesCapability.NOTIFICATIONS,
        GlassesCapability.BATTERY_STATUS,
        GlassesCapability.BRIGHTNESS_CONTROL,
        GlassesCapability.BUTTON_INPUT,
        GlassesCapability.MICROPHONE_AUDIO,
        GlassesCapability.SPEAKER_AUDIO,
    )
    override val state: StateFlow<GlassesBridgeState> get() = link.bridgeState
    override val events: Flow<InputEvent> = session.events.mapNotNull(MemoMindAssistantTrigger::inputEvent)

    override suspend fun scan(): List<DeviceInfo> = listOfNotNull(link.selectedDevice)
    override suspend fun connect(device: DeviceInfo) = link.connect(device)
    override suspend fun disconnect() = link.disconnect()

    override suspend fun showText(command: DisplayCommand.Text) = whenConnected { hud.showText(command.text) }
    override suspend fun showLines(command: DisplayCommand.Lines) = whenConnected { hud.showLines(command.lines) }
    override suspend fun showCard(command: DisplayCommand.Card) = whenConnected { hud.showCard(command.title, command.body) }
    override suspend fun clearDisplay() = whenConnected { hud.clear() }

    /** [level] is a percentage, as for the other display adapters; MemoMind has 11 steps. */
    override suspend fun setBrightness(level: Int) = whenConnected { session.setBrightness((level.coerceIn(0, 100) + 5) / 10) }

    override suspend fun requestBattery(): Result<Int> = whenConnected {
        session.refreshStatus().batteryPercent ?: throw BridgeError.ProtocolError("MemoMind did not report a battery level")
    }

    // Voice features use the glasses' HFP microphone route; the Opus capture is a separate API.
    override suspend fun startMic(): Result<Unit> =
        if (link.bridgeState.value == GlassesBridgeState.Connected) Result.success(Unit) else Result.failure(BridgeError.NotConnected())

    override suspend fun stopMic(): Result<Unit> = Result.success(Unit)

    /** Sends the notification as a native card; used by the phone notification forwarder. */
    override suspend fun showNotification(appName: String, title: String, body: String, packageName: String): Result<Unit> =
        whenConnected { hud.showNotification(appName, title, body, packageName) }

    private suspend fun <T> whenConnected(block: suspend () -> T): Result<T> {
        if (link.bridgeState.value != GlassesBridgeState.Connected) return Result.failure(BridgeError.NotConnected())
        return try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    companion object {
        const val ADAPTER_ID = "memomind"
    }
}
