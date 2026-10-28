package com.fersaiyan.cyanbridge.shared.devices.mentra

import com.fersaiyan.cyanbridge.bridge.core.BridgeError
import com.fersaiyan.cyanbridge.bridge.core.DeviceInfo
import com.fersaiyan.cyanbridge.bridge.core.DisplayCommand
import com.fersaiyan.cyanbridge.bridge.core.GestureType
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridgeState
import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.bridge.core.GlassesDeviceAdapter
import com.fersaiyan.cyanbridge.bridge.core.GlassesNotificationDisplay
import com.fersaiyan.cyanbridge.bridge.core.InputEvent
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the platform's Mentra SDK owner provides behind a [MentraDisplayAdapter]. */
interface MentraDisplayPort {
    /** The selected Mentra display model; null while other glasses (or Mentra Live) are selected. */
    val displayModel: MentraSdkModel?
    val bridgeState: StateFlow<GlassesBridgeState>
    val inputEvents: Flow<InputEvent>
    fun batteryLevel(): Int?

    /** The SDK's `displayText`; the text is already wrapped and fits one screen. */
    suspend fun displayText(text: String)
    suspend fun clearDisplay()
    suspend fun connect(device: DeviceInfo)
    suspend fun disconnect()

    /** Starts or stops the glasses microphone for [owner]; throws when the glasses cannot stream. */
    fun openMicrophone(owner: String)
    fun closeMicrophone(owner: String)
}

/**
 * Mentra SDK display glasses as a [GlassesDeviceAdapter] for the display bridge (assistant answers,
 * plugins, notifications, runtimes). Text is wrapped and paged with the model's
 * [MentraDisplayLimits]; later pages follow at a reading pace ([MentraDisplayText.dwellMs]) and a
 * tap shows the next one at once ([showNextPage]). A new command replaces the pages of the last one.
 */
class MentraDisplayAdapter(
    private val port: MentraDisplayPort,
    private val scope: CoroutineScope,
) : GlassesDeviceAdapter, GlassesNotificationDisplay {
    override val adapterId: String = ADAPTER_ID
    override val displayName: String get() = port.displayModel?.deviceClass?.displayName() ?: "Mentra display glasses"
    override val capabilities: Set<GlassesCapability>
        get() = port.displayModel?.let { GlassesFamilyProfiles.forClass(it.deviceClass).capabilities.intersect(ADAPTER_CAPABILITIES) }
            ?: emptySet()
    override val state: StateFlow<GlassesBridgeState> get() = port.bridgeState
    override val events: Flow<InputEvent> get() = port.inputEvents

    private val lock = SynchronizedObject()
    private var pager: Job? = null
    private var pages: List<String> = emptyList()
    private var pageIndex = 0
    private var pagesTtlMs: Long? = null

    override suspend fun scan(): List<DeviceInfo> = emptyList()
    override suspend fun connect(device: DeviceInfo) = port.connect(device)
    override suspend fun disconnect() = port.disconnect()

    override suspend fun showText(command: DisplayCommand.Text) = show(command.text, command.ttlMs)
    override suspend fun showLines(command: DisplayCommand.Lines) = show(command.lines.joinToString("\n"), ttlMs = null)
    override suspend fun showCard(command: DisplayCommand.Card) =
        show(listOf(command.title, command.body).filter { it.isNotBlank() }.joinToString("\n"), ttlMs = null)

    /** Phone notifications show as text and clear themselves after [NOTIFICATION_TTL_MS]. */
    override suspend fun showNotification(appName: String, title: String, body: String, packageName: String) =
        show("$appName: $title\n$body", NOTIFICATION_TTL_MS)

    override suspend fun clearDisplay(): Result<Unit> = whenReady {
        stopPages()
        port.clearDisplay()
    }

    // The 3.2 SDK keeps its brightness call internal.
    override suspend fun setBrightness(level: Int): Result<Unit> =
        Result.failure(BridgeError.UnsupportedCapability(GlassesCapability.BRIGHTNESS_CONTROL))

    override suspend fun requestBattery(): Result<Int> =
        port.batteryLevel()?.let { Result.success(it) } ?: Result.failure(BridgeError.NotConnected())

    override suspend fun startMic(): Result<Unit> = runCatching { port.openMicrophone(adapterId) }
    override suspend fun stopMic(): Result<Unit> = runCatching { port.closeMicrophone(adapterId) }

    /** Shows the next page of the current text now; false when it has no further page. */
    suspend fun showNextPage(): Boolean {
        val next = synchronized(lock) {
            if (pageIndex + 1 >= pages.size) return false
            pager?.cancel()
            pager = null
            Triple(pages, pageIndex + 1, pagesTtlMs)
        }
        val (all, index, ttlMs) = next
        port.displayText(all[index])
        followPages(all, index, ttlMs)
        return true
    }

    private suspend fun show(text: String, ttlMs: Long?): Result<Unit> = whenReady { limits ->
        val screens = MentraDisplayText.pages(text, limits)
        if (screens.isEmpty()) {
            stopPages()
            port.clearDisplay()
            return@whenReady
        }
        stopPages()
        port.displayText(screens.first())
        followPages(screens, 0, ttlMs)
    }

    /** Shows the pages after [index] at a reading pace, then clears after [ttlMs] when set. */
    private fun followPages(screens: List<String>, index: Int, ttlMs: Long?) {
        if (index + 1 >= screens.size && ttlMs == null) {
            synchronized(lock) { setPages(null, screens, index, ttlMs) }
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var current = index
                while (current + 1 < screens.size) {
                    delay(MentraDisplayText.dwellMs(screens[current]))
                    current++
                    synchronized(lock) { pageIndex = current }
                    port.displayText(screens[current])
                }
                if (ttlMs != null) {
                    delay(maxOf(ttlMs, MentraDisplayText.dwellMs(screens[current])))
                    port.clearDisplay()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                PlatformLogger.w(TAG, "Display pages stopped: ${error.message}")
            }
        }
        synchronized(lock) { setPages(job, screens, index, ttlMs) }
        job.start()
    }

    private fun setPages(job: Job?, screens: List<String>, index: Int, ttlMs: Long?) {
        pager?.cancel()
        pager = job
        pages = screens
        pageIndex = index
        pagesTtlMs = ttlMs
    }

    private fun stopPages() = synchronized(lock) { setPages(null, emptyList(), 0, null) }

    private suspend fun whenReady(block: suspend (MentraDisplayLimits) -> Unit): Result<Unit> {
        val limits = port.displayModel?.display
            ?: return Result.failure(BridgeError.UnsupportedCapability(GlassesCapability.TEXT_DISPLAY))
        if (port.bridgeState.value != GlassesBridgeState.Connected) return Result.failure(BridgeError.NotConnected())
        return try {
            Result.success(block(limits))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    companion object {
        const val ADAPTER_ID = "mentra_display"
        const val NOTIFICATION_TTL_MS = 10_000L
        private const val TAG = "MentraDisplay"

        /** What this adapter implements; the selected model's profile narrows it. */
        private val ADAPTER_CAPABILITIES = setOf(
            GlassesCapability.TEXT_DISPLAY,
            GlassesCapability.LINE_DISPLAY,
            GlassesCapability.CARD_DISPLAY,
            GlassesCapability.CLEAR_DISPLAY,
            GlassesCapability.PAGINATION,
            GlassesCapability.NOTIFICATIONS,
            GlassesCapability.BATTERY_STATUS,
            GlassesCapability.MICROPHONE_AUDIO,
            GlassesCapability.TOUCH_INPUT,
            GlassesCapability.BUTTON_INPUT,
            GlassesCapability.HEAD_GESTURE_INPUT,
        )
    }
}

/** The Mentra SDK's input events as [InputEvent]s, and what each one does for display glasses. */
object MentraDisplayInput {
    /** Button name of the SDK's head-up events (looking up: [GestureType.SWIPE_UP], down: [GestureType.SWIPE_DOWN]). */
    const val HEAD = "head"
    const val TOUCH = "touch"

    enum class Action { VOICE_QUESTION, NEXT_PAGE, CLEAR_DISPLAY, NONE }

    /** A `TouchEvent.gestureName` ("single_tap", "double_tap", "long_press", "…swipe…"). */
    fun touch(gestureName: String?): InputEvent.Button? {
        val name = gestureName?.lowercase().orEmpty()
        val gesture = when {
            name.contains("swipe") && (name.contains("forward") || name.contains("right")) -> GestureType.SWIPE_RIGHT
            name.contains("swipe") && (name.contains("back") || name.contains("left")) -> GestureType.SWIPE_LEFT
            name.contains("swipe") && name.contains("up") -> GestureType.SWIPE_UP
            name.contains("swipe") && name.contains("down") -> GestureType.SWIPE_DOWN
            name.startsWith("double") -> GestureType.DOUBLE_TAP
            name.startsWith("long") || name == "hold" -> GestureType.LONG_PRESS
            name.startsWith("single") || name == "tap" || name == "press" -> GestureType.SINGLE_TAP
            else -> return null
        }
        return InputEvent.Button(TOUCH, gesture)
    }

    /** A `ButtonPressEvent.pressType` ("short", "long", "double"). */
    fun button(buttonId: String, pressType: String): InputEvent.Button {
        val gesture = when (pressType.lowercase()) {
            "long", "long_press", "hold" -> GestureType.LONG_PRESS
            "double", "double_tap", "double_press" -> GestureType.DOUBLE_TAP
            else -> GestureType.SINGLE_TAP
        }
        return InputEvent.Button(buttonId, gesture)
    }

    fun headUp(up: Boolean): InputEvent.Button = InputEvent.Button(HEAD, if (up) GestureType.SWIPE_UP else GestureType.SWIPE_DOWN)

    /**
     * Long press: a spoken question (none of these glasses has a camera). Tap: the next page of the
     * shown text. Double tap: clear the display. Head movements only reach the bridge listeners.
     */
    fun actionFor(event: InputEvent): Action {
        val button = event as? InputEvent.Button ?: return Action.NONE
        if (button.button == HEAD) return Action.NONE
        return when (button.gesture) {
            GestureType.LONG_PRESS -> Action.VOICE_QUESTION
            GestureType.SINGLE_TAP -> Action.NEXT_PAGE
            GestureType.DOUBLE_TAP -> Action.CLEAR_DISPLAY
            else -> Action.NONE
        }
    }
}
