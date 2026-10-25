package com.fersaiyan.cyanbridge.bridge.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Central manager for the glasses bridge system.
 * Holds the active adapter, routes display commands, and forwards input events.
 *
 * Usage:
 *   GlassesBridge.registerAdapter(mockAdapter)
 *   GlassesBridge.setActiveAdapter("mock")
 *   GlassesBridge.showText(DisplayCommand.Text("Hello"))
 */
object GlassesBridge {

    private const val TAG = "GlassesBridge"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val adapters = mutableMapOf<String, GlassesDeviceAdapter>()
    private var activeAdapter: GlassesDeviceAdapter? = null

    private val _bridgeEvents = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 64)
    /** Events flowing through the bridge (for debug logging / UI). */
    val bridgeEvents: SharedFlow<BridgeEvent> = _bridgeEvents

    // --- Adapter registration ---

    fun registerAdapter(adapter: GlassesDeviceAdapter) {
        adapters[adapter.adapterId] = adapter
        Log.i(TAG, "Registered adapter: ${adapter.adapterId} (${adapter.displayName})")
    }

    fun getAdapter(adapterId: String): GlassesDeviceAdapter? = adapters[adapterId]

    // --- Active adapter ---

    fun setActiveAdapter(adapterId: String): Boolean {
        val adapter = adapters[adapterId]
        if (adapter == null) {
            Log.w(TAG, "Adapter not found: $adapterId")
            return false
        }
        activeAdapter = adapter
        Log.i(TAG, "Active adapter set to: $adapterId")
        emit(BridgeEvent.AdapterChanged(adapterId))
        return true
    }

    // --- Display commands ---

    suspend fun showText(command: DisplayCommand.Text): Result<Unit> {
        val adapter = activeAdapter ?: return Result.failure(BridgeError.NotConnected())
        emit(BridgeEvent.DisplayRequested(adapter.adapterId, command))
        return adapter.showText(command)
    }

    suspend fun showLines(command: DisplayCommand.Lines): Result<Unit> {
        val adapter = activeAdapter ?: return Result.failure(BridgeError.NotConnected())
        emit(BridgeEvent.DisplayRequested(adapter.adapterId, command))
        return adapter.showLines(command)
    }

    suspend fun showCard(command: DisplayCommand.Card): Result<Unit> {
        val adapter = activeAdapter ?: return Result.failure(BridgeError.NotConnected())
        emit(BridgeEvent.DisplayRequested(adapter.adapterId, command))
        return adapter.showCard(command)
    }

    suspend fun clearDisplay(): Result<Unit> {
        val adapter = activeAdapter ?: return Result.failure(BridgeError.NotConnected())
        emit(BridgeEvent.DisplayRequested(adapter.adapterId, DisplayCommand.Clear))
        return adapter.clearDisplay()
    }

    /**
     * Shows a phone notification: as a native notification card on adapters that have one
     * ([GlassesNotificationDisplay]), otherwise as the text "app: title" plus the body.
     */
    suspend fun showNotification(appName: String, title: String, text: String, packageName: String): Result<Unit> {
        val adapter = activeAdapter ?: return Result.failure(BridgeError.NotConnected())
        val command = DisplayCommand.Text(text = "$appName: $title\n$text")
        emit(BridgeEvent.DisplayRequested(adapter.adapterId, command))
        return (adapter as? GlassesNotificationDisplay)?.showNotification(appName, title, text, packageName)
            ?: adapter.showText(command)
    }

    /** True when [adapterId] is the adapter display commands go to. */
    fun isActiveAdapter(adapterId: String): Boolean = activeAdapter?.adapterId == adapterId

    // --- Input forwarding ---

    /** Called by adapters to forward input events to the bridge. */
    fun onInputEvent(adapterId: String, event: InputEvent) {
        scope.launch {
            emit(BridgeEvent.InputReceived(adapterId, event))
        }
    }

    // --- Internal ---

    private fun emit(event: BridgeEvent) {
        _bridgeEvents.tryEmit(event)
    }
}

/**
 * Events emitted by the bridge for logging and UI.
 */
sealed class BridgeEvent {
    data class AdapterChanged(val adapterId: String) : BridgeEvent()
    data class DisplayRequested(val adapterId: String, val command: DisplayCommand) : BridgeEvent()
    data class InputReceived(val adapterId: String, val event: InputEvent) : BridgeEvent()
    data class Error(val source: String, val message: String, val throwable: Throwable? = null) : BridgeEvent()
}
