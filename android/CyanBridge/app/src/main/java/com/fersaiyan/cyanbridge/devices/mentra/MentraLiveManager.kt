package com.fersaiyan.cyanbridge.devices.mentra

import android.content.Context
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

    @Volatile private var pcmListener: ((ByteArray) -> Unit)? = null

    val connected: Boolean get() = _glasses.value.connected
    val ready: Boolean get() = _glasses.value.ready

    override fun onGlassesChanged(glasses: GlassesRuntimeState) {
        _glasses.value = glasses
        if (!glasses.connected) {
            pcmListener = null
        }
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
        sdk.scan(DeviceModel.MENTRA_LIVE, 10_000L, onResults)
    }

    fun connect(device: Device) {
        require(device.model == DeviceModel.MENTRA_LIVE) { "Not Mentra Live" }
        sdk.connect(device)
    }

    fun disconnect() {
        pcmListener = null
        sdk.setMicState(enabled = false)
        sdk.disconnect()
    }

    fun setPcmListener(listener: ((ByteArray) -> Unit)?) {
        pcmListener = listener
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        if (!enabled) pcmListener = null
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
