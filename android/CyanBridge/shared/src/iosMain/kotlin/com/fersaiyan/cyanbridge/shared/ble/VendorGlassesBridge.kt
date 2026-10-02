package com.fersaiyan.cyanbridge.shared.ble

import com.fersaiyan.cyanbridge.shared.platform.toNSData
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBPeripheral
import platform.Foundation.NSData
import kotlin.coroutines.resume

/**
 * Vendor SDK operations for HeyCyan glasses, implemented in Swift by the iOS host
 * with QCSDK.framework (the iOS counterpart of Android's glasses_sdk AAR).
 *
 * QCSDK owns the peripheral after [attach], so the generic CoreBluetooth path in
 * [IosBleManager] stays out of the way while a vendor session is active.
 */
interface VendorGlassesBridge {
    /** 128-bit or 16-bit service UUIDs the vendor glasses advertise. */
    val serviceUuids: List<String>

    fun attach(peripheral: CBPeripheral, completion: VendorResultCallback)
    fun detach(peripheral: CBPeripheral)
    fun setEventListener(listener: VendorGlassesEventListener?)

    fun setDeviceMode(mode: Int, completion: VendorModeCallback)
    fun requestBattery(completion: VendorBatteryCallback)
    fun requestVersion(completion: VendorVersionCallback)
    fun requestMediaCounts(completion: VendorMediaCountsCallback)
    fun syncTime(completion: VendorResultCallback)

    /** Puts the glasses in a Wi-Fi mode and returns the hotspot SSID and passphrase. */
    fun openWifi(mode: Int, completion: VendorWifiCallback)
    fun requestWifiIp(completion: VendorTextCallback)
    fun requestVolume(completion: VendorVolumeCallback)
    fun requestWearingDetection(completion: VendorToggleCallback)
    fun setWearingDetection(enabled: Boolean, completion: VendorResultCallback)
    fun requestVideoSettings(completion: VendorRecordingSettingsCallback)
    fun setVideoSettings(angle: Int, durationSeconds: Int, completion: VendorResultCallback)
    fun requestAudioSettings(completion: VendorRecordingSettingsCallback)
    fun setAudioSettings(angle: Int, durationSeconds: Int, completion: VendorResultCallback)
    fun deleteMedia(filename: String, completion: VendorResultCallback)

    /** Turns the glasses' onboard "Hey Cyan" detector on or off (Android: aiVoiceWake). */
    fun setVoiceWakeup(enabled: Boolean, completion: VendorResultCallback)

    /** Raw glasses command (Android LargeDataHandler.glassesControl), e.g. AI-photo quality. */
    fun sendGlassesControl(payload: NSData, completion: VendorResultCallback)

    /** Thumbnail of the photo at [pocket] (QCSDK getThumbnail); empty data on failure. */
    fun requestThumbnail(pocket: Int, completion: VendorThumbnailCallback)

    /** Drives the glasses' AI speaking/thinking indicator (QGAISpeakMode raw values). */
    fun setAiSpeakMode(mode: Int, completion: VendorResultCallback)
}

fun interface VendorThumbnailCallback {
    fun onResult(success: Boolean, data: NSData?, width: Int, height: Int)
}

fun interface VendorResultCallback {
    fun onResult(success: Boolean)
}

/** [currentMode] is the device's mode when the request is rejected. */
fun interface VendorModeCallback {
    fun onResult(success: Boolean, currentMode: Int)
}

fun interface VendorBatteryCallback {
    fun onResult(success: Boolean, level: Int, charging: Boolean)
}

fun interface VendorVersionCallback {
    fun onResult(
        success: Boolean,
        hardware: String,
        firmware: String,
        wifiHardware: String,
        wifiFirmware: String,
    )
}

fun interface VendorMediaCountsCallback {
    fun onResult(success: Boolean, photos: Int, videos: Int, audio: Int)
}

fun interface VendorWifiCallback {
    fun onResult(success: Boolean, ssid: String, passphrase: String, currentMode: Int)
}

fun interface VendorTextCallback {
    fun onResult(success: Boolean, value: String)
}

fun interface VendorToggleCallback {
    fun onResult(success: Boolean, enabled: Boolean)
}

fun interface VendorRecordingSettingsCallback {
    fun onResult(success: Boolean, angle: Int, durationSeconds: Int)
}

/** Current/max levels per QCVolumeInfoModel mode (music, call, system). */
fun interface VendorVolumeCallback {
    fun onResult(
        success: Boolean,
        musicCurrent: Int,
        musicMax: Int,
        callCurrent: Int,
        callMax: Int,
        systemCurrent: Int,
        systemMax: Int,
    )
}

/** Unsolicited updates pushed by the glasses through QCSDKManagerDelegate. */
interface VendorGlassesEventListener {
    fun onBatteryChanged(level: Int, charging: Boolean)
    fun onMediaCountsChanged(photos: Int, videos: Int, audio: Int)
    fun onAiImage(data: NSData)

    /** Raw serial-port notification ([0xBC, cmd, len LE16, crc LE16, payload]) seen before QCSDK. */
    fun onRawNotify(data: NSData)
}

/** Raw values of QCSDK's QCOperatorDeviceMode. */
object VendorGlassesMode {
    const val PHOTO = 0x01
    const val VIDEO = 0x02
    const val VIDEO_STOP = 0x03
    const val TRANSFER = 0x04
    const val OTA = 0x05
    const val AI_PHOTO = 0x06
    const val SPEECH_RECOGNITION = 0x07
    const val AUDIO = 0x08
    const val TRANSFER_STOP = 0x09
    /** Ends the glasses' AI voice/recognition mode (Android: glassesControl [02 01 0b]). */
    const val SPEECH_RECOGNITION_STOP = 0x0B
    const val AUDIO_STOP = 0x0C
}

/** Raw values of QCSDK's QGAISpeakMode. */
object VendorAiSpeakMode {
    const val START = 0x01
    const val STOP = 0x03
    const val THINKING_START = 0x04
    const val THINKING_STOP = 0x06
}

/** The Swift host registers its bridge here before creating the Compose controller. */
object VendorGlassesRegistry {
    var bridge: VendorGlassesBridge? = null
}

data class VendorVersionInfo(
    val hardware: String,
    val firmware: String,
    val wifiHardware: String,
    val wifiFirmware: String,
)

data class VendorMediaCounts(val photos: Int, val videos: Int, val audio: Int)

private const val VENDOR_COMMAND_TIMEOUT_MS = 8_000L

suspend fun VendorGlassesBridge.awaitModeAccepted(mode: Int): Boolean = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        setDeviceMode(mode) { success, _ ->
            if (continuation.isActive) continuation.resume(success)
        }
    }
} ?: false

suspend fun VendorGlassesBridge.awaitBattery(): Pair<Int, Boolean>? = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        requestBattery { success, level, charging ->
            if (continuation.isActive) continuation.resume(if (success) level to charging else null)
        }
    }
}

suspend fun VendorGlassesBridge.awaitVersion(): VendorVersionInfo? = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        requestVersion { success, hardware, firmware, wifiHardware, wifiFirmware ->
            if (continuation.isActive) {
                continuation.resume(
                    if (success) VendorVersionInfo(hardware, firmware, wifiHardware, wifiFirmware) else null,
                )
            }
        }
    }
}

suspend fun VendorGlassesBridge.awaitMediaCounts(): VendorMediaCounts? = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        requestMediaCounts { success, photos, videos, audio ->
            if (continuation.isActive) continuation.resume(if (success) VendorMediaCounts(photos, videos, audio) else null)
        }
    }
}

suspend fun VendorGlassesBridge.awaitSyncTime(): Boolean = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        syncTime { success -> if (continuation.isActive) continuation.resume(success) }
    }
} ?: false

data class VendorWifiCredentials(val ssid: String, val passphrase: String)

data class VendorRecordingSettings(val angle: Int, val durationSeconds: Int)

data class VendorVolume(
    val musicCurrent: Int,
    val musicMax: Int,
    val callCurrent: Int,
    val callMax: Int,
    val systemCurrent: Int,
    val systemMax: Int,
)

suspend fun VendorGlassesBridge.awaitOpenWifi(mode: Int): VendorWifiCredentials? =
    withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS * 2) {
        suspendCancellableCoroutine { continuation ->
            openWifi(mode) { success, ssid, passphrase, _ ->
                if (continuation.isActive) {
                    continuation.resume(if (success && ssid.isNotBlank()) VendorWifiCredentials(ssid, passphrase) else null)
                }
            }
        }
    }

suspend fun VendorGlassesBridge.awaitWifiIp(): String? = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        requestWifiIp { success, value ->
            if (continuation.isActive) continuation.resume(value.takeIf { success && it.isNotBlank() })
        }
    }
}

suspend fun VendorGlassesBridge.awaitVolume(): VendorVolume? = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        requestVolume { success, musicCurrent, musicMax, callCurrent, callMax, systemCurrent, systemMax ->
            if (continuation.isActive) {
                continuation.resume(
                    if (success) VendorVolume(musicCurrent, musicMax, callCurrent, callMax, systemCurrent, systemMax) else null,
                )
            }
        }
    }
}

suspend fun VendorGlassesBridge.awaitWearingDetection(): Boolean? = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        requestWearingDetection { success, enabled ->
            if (continuation.isActive) continuation.resume(if (success) enabled else null)
        }
    }
}

suspend fun VendorGlassesBridge.awaitSetWearingDetection(enabled: Boolean): Boolean =
    awaitResult { setWearingDetection(enabled, it) }

suspend fun VendorGlassesBridge.awaitVideoSettings(): VendorRecordingSettings? =
    awaitRecordingSettings { requestVideoSettings(it) }

suspend fun VendorGlassesBridge.awaitAudioSettings(): VendorRecordingSettings? =
    awaitRecordingSettings { requestAudioSettings(it) }

suspend fun VendorGlassesBridge.awaitSetVideoSettings(angle: Int, durationSeconds: Int): Boolean =
    awaitResult { setVideoSettings(angle, durationSeconds, it) }

suspend fun VendorGlassesBridge.awaitSetAudioSettings(angle: Int, durationSeconds: Int): Boolean =
    awaitResult { setAudioSettings(angle, durationSeconds, it) }

suspend fun VendorGlassesBridge.awaitDeleteMedia(filename: String): Boolean =
    awaitResult { deleteMedia(filename, it) }

private suspend fun awaitResult(call: (VendorResultCallback) -> Unit): Boolean =
    withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            call(VendorResultCallback { success -> if (continuation.isActive) continuation.resume(success) })
        }
    } ?: false

private suspend fun awaitRecordingSettings(
    call: (VendorRecordingSettingsCallback) -> Unit,
): VendorRecordingSettings? = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        call(
            VendorRecordingSettingsCallback { success, angle, durationSeconds ->
                if (continuation.isActive) {
                    continuation.resume(if (success) VendorRecordingSettings(angle, durationSeconds) else null)
                }
            },
        )
    }
}

suspend fun VendorGlassesBridge.awaitAiSpeakMode(mode: Int): Boolean = awaitResult { setAiSpeakMode(mode, it) }

suspend fun VendorGlassesBridge.awaitThumbnail(pocket: Int): NSData? = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS * 2) {
    suspendCancellableCoroutine { continuation ->
        requestThumbnail(pocket) { success, data, _, _ ->
            if (continuation.isActive) continuation.resume(data?.takeIf { success && it.length > 0u })
        }
    }
}

suspend fun VendorGlassesBridge.awaitGlassesControl(payload: ByteArray): Boolean = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        sendGlassesControl(payload.toNSData()) { success ->
            if (continuation.isActive) continuation.resume(success)
        }
    }
} ?: false

suspend fun VendorGlassesBridge.awaitVoiceWakeup(enabled: Boolean): Boolean = withTimeoutOrNull(VENDOR_COMMAND_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        setVoiceWakeup(enabled) { success -> if (continuation.isActive) continuation.resume(success) }
    }
} ?: false
