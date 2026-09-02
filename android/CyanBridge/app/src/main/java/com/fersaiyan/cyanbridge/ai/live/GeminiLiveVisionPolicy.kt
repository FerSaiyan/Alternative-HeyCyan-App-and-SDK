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
    val maxVideoFps: Double,
) {
    enum class Mode {
        NONE,
        MANUAL_STILL,
        OPPORTUNISTIC_STILL,
        LIVE_FRAMES,
    }

    val supportsAutomaticVision: Boolean
        get() = mode == Mode.OPPORTUNISTIC_STILL || mode == Mode.LIVE_FRAMES
}

object GeminiLiveVisionPolicy {
    fun forDevice(deviceClass: DeviceClass): GeminiLiveVisionCapabilities = when (deviceClass) {
        // CyanBridge already has a Meta DAT camera stream. Gemini receives a sampled view,
        // never the full 24 fps source.
        DeviceClass.META_RAYBAN -> GeminiLiveVisionCapabilities(
            mode = GeminiLiveVisionCapabilities.Mode.LIVE_FRAMES,
            audibleStillCapture = false,
            maxVideoFps = 1.0,
        )

        // HeyCyan currently exposes individual thumbnail captures. Programmatic capture makes
        // an audible shutter sound, so refresh only on a new speech window after the configured
        // cadence. The physical AI-photo button remains manual.
        DeviceClass.HEY_CYAN -> GeminiLiveVisionCapabilities(
            mode = GeminiLiveVisionCapabilities.Mode.OPPORTUNISTIC_STILL,
            audibleStillCapture = true,
            maxVideoFps = 0.0,
        )

        DeviceClass.EYEVUE,
        DeviceClass.TUNEBUDS -> GeminiLiveVisionCapabilities(
            mode = GeminiLiveVisionCapabilities.Mode.OPPORTUNISTIC_STILL,
            audibleStillCapture = false,
            maxVideoFps = 0.0,
        )

        else -> GeminiLiveVisionCapabilities(
            mode = GeminiLiveVisionCapabilities.Mode.NONE,
            audibleStillCapture = false,
            maxVideoFps = 0.0,
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

    fun shouldSendVideoFrame(
        capabilities: GeminiLiveVisionCapabilities,
        userSpeaking: Boolean,
        nowMs: Long,
        lastFrameSentMs: Long,
        encodingInProgress: Boolean,
        refreshIntervalMs: Long?,
    ): Boolean {
        if (capabilities.mode != GeminiLiveVisionCapabilities.Mode.LIVE_FRAMES) return false
        if (refreshIntervalMs == null) return false
        if (!userSpeaking || encodingInProgress) return false
        if (lastFrameSentMs <= 0L) return true
        return nowMs - lastFrameSentMs >= refreshIntervalMs
    }
}
