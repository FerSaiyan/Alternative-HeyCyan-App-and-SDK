package com.fersaiyan.cyanbridge.shared.network.session

import com.fersaiyan.cyanbridge.shared.network.GlassesWifiNotify

/** Reaction to a glasses Wi-Fi error notify (0x09). */
enum class GlassesWifiErrorAction {
    /** Log only. */
    IGNORE,

    /** No sync is running; a reset would disturb camera or recording use. */
    IGNORE_OUTSIDE_SESSION,

    /** Strict HeyCyan flow: stop for a manual retry and reset the glasses Wi-Fi Direct state. */
    STOP_FOR_RETRY_WITH_DEVICE_RESET,

    /** Adaptive flow: record it and let its bounded checkpoint recovery decide on a reset. */
    DEFER_TO_ADAPTIVE_RECOVERY,

    /** Firmware update: any error other than the noisy 255 fails the Wi-Fi stage. */
    FAIL_OTA,
}

/** Which workflow received the error. */
enum class GlassesWifiErrorContext {
    OFFICIAL_MEDIA_SYNC,
    ADAPTIVE_MEDIA_SYNC,
    OTA,
    LIVE_PREVIEW,
}

/**
 * One table for the vendor error codes every glasses-link workflow sees. Error 255 is frequent
 * and usually harmless; only the media-sync flows react to it, and only while a sync runs.
 */
object GlassesWifiErrorPolicy {
    fun decide(
        context: GlassesWifiErrorContext,
        errorCode: Int,
        sessionActive: Boolean,
    ): GlassesWifiErrorAction {
        val noisy = errorCode == GlassesWifiNotify.NOISY_WIFI_ERROR_CODE
        return when (context) {
            GlassesWifiErrorContext.OTA ->
                if (noisy) GlassesWifiErrorAction.IGNORE else GlassesWifiErrorAction.FAIL_OTA
            GlassesWifiErrorContext.LIVE_PREVIEW -> GlassesWifiErrorAction.IGNORE
            GlassesWifiErrorContext.OFFICIAL_MEDIA_SYNC -> when {
                !noisy -> GlassesWifiErrorAction.IGNORE
                !sessionActive -> GlassesWifiErrorAction.IGNORE_OUTSIDE_SESSION
                else -> GlassesWifiErrorAction.STOP_FOR_RETRY_WITH_DEVICE_RESET
            }
            GlassesWifiErrorContext.ADAPTIVE_MEDIA_SYNC -> when {
                !noisy -> GlassesWifiErrorAction.IGNORE
                !sessionActive -> GlassesWifiErrorAction.IGNORE_OUTSIDE_SESSION
                else -> GlassesWifiErrorAction.DEFER_TO_ADAPTIVE_RECOVERY
            }
        }
    }
}
