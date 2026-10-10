package com.fersaiyan.cyanbridge.devices.mentra

import android.content.Context
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridge
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridgeState
import com.fersaiyan.cyanbridge.bridge.core.GestureType
import com.fersaiyan.cyanbridge.bridge.core.InputEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap
import com.mentra.bluetoothsdk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A single owner of the native Mentra BLE connection. This is independent of
 * CyanBridge's MentraOS miniapp HTTP compatibility runtime and Oudmon BLE manager.
 */
class MentraLiveManager private constructor(context: Context) : MentraBluetoothSdkCallback() {
    private val sdk = MentraBluetoothSdk.create(
        context.applicationContext,
        MentraBluetoothSdkConfig(
            deliverCallbacksOnMainThread = false,
            analytics = BluetoothSdkAnalyticsConfig.disabled(),
        ),
        this,
    )
    private val _glasses = MutableStateFlow<GlassesRuntimeState>(sdk.getGlasses())
    val glasses: StateFlow<GlassesRuntimeState> = _glasses
    private val _bridgeState = MutableStateFlow<GlassesBridgeState>(GlassesBridgeState.Disconnected)
    val bridgeState: StateFlow<GlassesBridgeState> = _bridgeState
    private val _inputEvents = MutableSharedFlow<InputEvent>(extraBufferCapacity = 32)
    val inputEvents: Flow<InputEvent> = _inputEvents
    private val discovered = ConcurrentHashMap<String, Device>()

    init { GlassesBridge.registerAdapter(MentraLiveAdapter(this)) }

    @Volatile private var pcmListener: ((ByteArray) -> Unit)? = null

    val connected: Boolean get() = _glasses.value.connected
    val ready: Boolean get() = _glasses.value.ready

    override fun onGlassesChanged(glasses: GlassesRuntimeState) {
        _glasses.value = glasses
        _bridgeState.value = when {
            glasses.ready -> GlassesBridgeState.Connected
            glasses.connected -> GlassesBridgeState.Connecting
            else -> GlassesBridgeState.Disconnected
        }
        if (!glasses.connected) {
            pcmListener = null
        }
    }

    override fun onButtonPress(event: ButtonPressEvent) {
        val gesture = when (event.pressType.lowercase()) {
            "long", "long_press", "hold" -> GestureType.LONG_PRESS
            "double", "double_tap" -> GestureType.DOUBLE_TAP
            else -> GestureType.SINGLE_TAP
        }
        val input = InputEvent.Button(event.buttonId, gesture)
        _inputEvents.tryEmit(input)
        GlassesBridge.onInputEvent("mentra_live", input)
    }

    override fun onMicPcm(event: MicPcmEvent) {
        // Do not send corrupt or unexpected PCM to the realtime agent.
        if (event.sampleRate != 16_000 || event.bitsPerSample != 16 ||
            event.channels != 1 || event.encoding != "pcm_s16le" ||
            event.pcm.isEmpty() || event.pcm.size % 2 != 0
        ) return
        pcmListener?.invoke(event.pcm.copyOf())
    }

    fun scan(onResults: (List<Device>) -> Unit) {
        sdk.scan(DeviceModel.MENTRA_LIVE, 10_000L) { devices ->
            discovered.clear()
            devices.forEach { discovered[it.id] = it }
            onResults(devices)
        }
    }

    fun discoveredDevices(): List<Device> = discovered.values.toList()
    fun findDiscovered(id: String): Device? = discovered[id]
    fun batteryLevel(): Int? = (glasses.value as? GlassesRuntimeState.Connected)?.battery?.level

    fun connect(device: Device) {
        require(device.model == DeviceModel.MENTRA_LIVE) { "Not Mentra Live" }
        GlassesBridge.setActiveAdapter("mentra_live")
        _bridgeState.value = GlassesBridgeState.Connecting
        sdk.connect(device)
    }

    fun disconnect() {
        pcmListener = null
        sdk.setMicState(enabled = false)
        sdk.setMicSourcePin(null)
        sdk.disconnect()
        _bridgeState.value = GlassesBridgeState.Disconnected
    }

    fun setPcmListener(listener: ((ByteArray) -> Unit)?) {
        pcmListener = listener
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        if (!enabled) pcmListener = null
        sdk.setMicSourcePin(if (enabled) "glasses" else null)
        sdk.setMicState(enabled = enabled, useGlassesMic = true)
    }

    fun setOwnAudioPlaying(playing: Boolean) = sdk.setOwnAppAudioPlaying(playing)

    suspend fun requestPhoto(request: PhotoRequest): PhotoResponseEvent = sdk.requestPhoto(request)

    companion object {
        @Volatile private var instance: MentraLiveManager? = null
        fun getInstance(context: Context): MentraLiveManager =
            instance ?: synchronized(this) {
                instance ?: MentraLiveManager(context.applicationContext).also { instance = it }
            }
    }
}
