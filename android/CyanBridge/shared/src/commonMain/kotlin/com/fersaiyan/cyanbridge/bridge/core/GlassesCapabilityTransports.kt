package com.fersaiyan.cyanbridge.bridge.core

/*
 * Capability contracts a glasses family implements on each platform. A device profile declares
 * which capabilities the family supports; the platform host resolves the selected family's
 * implementation and calls it instead of branching on the device class. Commands are
 * fire-and-forget: results arrive through the family's own state, which the host mirrors into
 * the dashboard.
 */

/** Opens the family's pairing flow and connects or disconnects the selected glasses. */
interface GlassesConnectionControl {
    fun scan()
    fun reconnect()
    fun disconnect()
}

/** Takes a user-initiated photo with the glasses camera. */
fun interface CameraCapture {
    fun capturePhoto()
}

/** Starts or stops recordings stored on the glasses. */
interface OnboardRecording {
    fun toggleVideoRecording()
    fun toggleAudioRecording()
}

/** Counts and downloads media stored on the glasses. */
interface MediaSyncTransport {
    fun requestMediaCount()
    fun startSync()
    fun stopSync()
}

/** Streams the glasses camera to the phone. */
interface LivePreviewTransport {
    fun startPreview()
    fun stopPreview()
}

/** Opens the family's broadcast screen (the glasses stream to the user's own server). */
fun interface LiveBroadcastControl {
    fun openBroadcast()
}

/** Reads the glasses battery level. */
interface BatteryReporting {
    /** True when the host should poll the battery on a timer while this family is selected. */
    val pollsPeriodically: Boolean

    /** User-requested reading. */
    fun requestBattery()

    /** Reading requested when the family becomes the selected profile, for families that do not poll. */
    fun refreshAfterSelection()
}

/** Firmware/version details and the glasses clock. */
interface DeviceInfoReporting {
    fun requestVersion()
    fun syncTime()
}

/** Opens the family's own firmware update screen (an SDK-run update, not the HeyCyan OTA). */
fun interface SdkFirmwareUpdate {
    fun openFirmwareUpdate()
}

/** Reads the glasses speaker volume. */
fun interface VolumeReporting {
    fun requestVolume()
}

/** Developer diagnostics: asks the glasses to report the functions they support. */
fun interface FeatureReportDiagnostics {
    fun requestFeatureReport()
}

/** Onboard recording limits. */
interface RecordingSettingsControl {
    fun refreshRecordingSettings()
    fun setRecordingDuration(isAudio: Boolean, seconds: Int)
}

/** Wear sensor that pauses capture when the glasses are taken off. */
fun interface WearingDetectionControl {
    fun setWearingDetection(enabled: Boolean)
}

/**
 * Photo for an AI question. The family delivers the image through its own photo callback;
 * [requestAiPhoto] returns false when the request could not be sent.
 */
interface AiPhotoCapture {
    suspend fun requestAiPhoto(): Boolean
}
