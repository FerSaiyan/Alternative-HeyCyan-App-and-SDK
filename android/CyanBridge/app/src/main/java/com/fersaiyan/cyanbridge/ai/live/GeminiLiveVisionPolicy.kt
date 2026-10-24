package com.fersaiyan.cyanbridge.ai.live

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass

/**
 * Describes how a selected glasses family can contribute visual context to Gemini Live.
 * This policy is only consulted by an already-active Gemini Live session; it never changes
 * routing for other Pro models/providers.
 */
data class GeminiLiveVisionCapabilities(
    val mode: Mode,
    val audibleStillCapture: Boolean,
) {
    enum class Mode {
        NONE,
        MANUAL_STILL,
        OPPORTUNISTIC_STILL,
    }

    val supportsAutomaticVision: Boolean
        get() = mode == Mode.OPPORTUNISTIC_STILL
}

object GeminiLiveVisionPolicy {
    fun forDevice(deviceClass: DeviceClass): GeminiLiveVisionCapabilities = when (deviceClass) {
        // HeyCyan currently exposes individual thumbnail captures. Programmatic capture makes
        // an audible shutter sound, so refresh only on a new speech window after the configured
        // cadence. The physical AI-photo button remains manual.
        DeviceClass.HEY_CYAN -> GeminiLiveVisionCapabilities(
            mode = GeminiLiveVisionCapabilities.Mode.OPPORTUNISTIC_STILL,
            audibleStillCapture = true,
        )

        DeviceClass.EYEVUE,
        DeviceClass.TUNEBUDS,
        DeviceClass.MENTRA_LIVE -> GeminiLiveVisionCapabilities(
            mode = GeminiLiveVisionCapabilities.Mode.OPPORTUNISTIC_STILL,
            audibleStillCapture = false,
        )

        else -> GeminiLiveVisionCapabilities(
            mode = GeminiLiveVisionCapabilities.Mode.NONE,
            audibleStillCapture = false,
        )
    }

    fun shouldCaptureAutomaticStill(
        capabilities: GeminiLiveVisionCapabilities,
        nowMs: Long,
        lastAutomaticStillMs: Long,
        captureInProgress: Boolean,
        refreshIntervalMs: Long?,
    ): Boolean {
        if (capabilities.mode != GeminiLiveVisionCapabilities.Mode.OPPORTUNISTIC_STILL) return false
        if (refreshIntervalMs == null) return false
        if (captureInProgress) return false
        if (lastAutomaticStillMs <= 0L) return true
        return nowMs - lastAutomaticStillMs >= refreshIntervalMs
    }
}
