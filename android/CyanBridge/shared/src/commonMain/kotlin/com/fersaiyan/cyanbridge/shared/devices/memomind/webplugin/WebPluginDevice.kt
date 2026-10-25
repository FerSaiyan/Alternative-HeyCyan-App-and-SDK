package com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin

import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindAudioListener
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindCaptureOptions
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindDeviceInfo
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindEvent
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A microphone capture opened for a Web plugin. */
fun interface WebPluginMicrophone {
    suspend fun close()
}

/**
 * Opens the glasses microphone for a Web plugin through the platform's audio arbitration.
 * Throws [WebPluginBridgeException] (AUDIO_BUSY when another app feature owns the microphone,
 * CAPABILITY_UNAVAILABLE without a recording link) or the start failure.
 */
fun interface WebPluginMicrophoneOpener {
    suspend fun open(options: MemoMindCaptureOptions, listener: MemoMindAudioListener): WebPluginMicrophone
}

/** What a Web plugin host needs from the glasses; [MemoMindWebPluginDevice] maps it onto a session. */
interface WebPluginDevice {
    /** The command link is up and the bring-up finished. */
    val connected: StateFlow<Boolean>

    /** True after the Web Bridge glasses plugin answered; false or null when it is not known to run. */
    val webBridgeRunning: Boolean?

    /** Plugin messages and Web Bridge input from the glasses. */
    val events: Flow<MemoMindEvent>

    val deviceInfo: MemoMindDeviceInfo?
    val batteryPercent: Int?

    /** Delivers an application message; returns the plugin INT8 status (0 delivered). */
    suspend fun sendAppMessage(channel: Int, data: ByteArray): Int

    /** Pings the Web Bridge plugin and records the answer in [webBridgeRunning]. */
    suspend fun probeWebBridge(): Boolean

    /** Text on the native teleprompter, the only text surface without a glasses plugin. */
    suspend fun showFallbackText(lines: List<String>)

    suspend fun clearFallback()

    suspend fun openMicrophone(options: MemoMindCaptureOptions, listener: MemoMindAudioListener): WebPluginMicrophone
}

/** [WebPluginDevice] over the shared [MemoMindSession]; [connected] and [microphone] come from the platform link owner. */
class MemoMindWebPluginDevice(
    private val session: MemoMindSession,
    override val connected: StateFlow<Boolean>,
    private val microphone: WebPluginMicrophoneOpener,
) : WebPluginDevice {
    override val webBridgeRunning: Boolean? get() = session.state.value.webBridgeRunning
    override val events: Flow<MemoMindEvent> get() = session.events
    override val deviceInfo: MemoMindDeviceInfo? get() = session.state.value.deviceInfo
    override val batteryPercent: Int? get() = session.state.value.batteryPercent

    override suspend fun sendAppMessage(channel: Int, data: ByteArray): Int = session.sendAppMessage(channel, data)

    override suspend fun probeWebBridge(): Boolean = session.probeWebBridge()

    override suspend fun showFallbackText(lines: List<String>) = session.showTeleprompter(lines)

    override suspend fun clearFallback() {
        if (session.state.value.teleprompterActive) session.stopTeleprompter()
    }

    override suspend fun openMicrophone(options: MemoMindCaptureOptions, listener: MemoMindAudioListener): WebPluginMicrophone =
        microphone.open(options, listener)
}
