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
    val minAutomaticStillIntervalMs: Long,
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
    const val LIVE_FRAME_INTERVAL_MS = 1_000L
    const val HEY_CYAN_AUTO_STILL_INTERVAL_MS = 20_000L

    fun forDevice(deviceClass: DeviceClass): GeminiLiveVisionCapabilities = when (deviceClass) {
        // CyanBridge already has a Meta DAT camera stream. Gemini receives a sampled view,
        // never the full 24 fps source.
        DeviceClass.META_RAYBAN -> GeminiLiveVisionCapabilities(
            mode = GeminiLiveVisionCapabilities.Mode.LIVE_FRAMES,
            audibleStillCapture = false,
            maxVideoFps = 1.0,
            minAutomaticStillIntervalMs = 0L,
        )

        // HeyCyan currently exposes individual thumbnail captures. Programmatic capture makes
        // an audible shutter sound, so do not fire once per short phrase; refresh only when the
        // previous automatic visual context is old. The physical AI-photo button remains manual.
        DeviceClass.HEY_CYAN -> GeminiLiveVisionCapabilities(
            mode = GeminiLiveVisionCapabilities.Mode.OPPORTUNISTIC_STILL,
            audibleStillCapture = true,
            maxVideoFps = 0.0,
            minAutomaticStillIntervalMs = HEY_CYAN_AUTO_STILL_INTERVAL_MS,
        )

        else -> GeminiLiveVisionCapabilities(
            mode = GeminiLiveVisionCapabilities.Mode.NONE,
            audibleStillCapture = false,
            maxVideoFps = 0.0,
            minAutomaticStillIntervalMs = Long.MAX_VALUE,
        )
    }

    fun shouldCaptureAutomaticStill(
        capabilities: GeminiLiveVisionCapabilities,
        nowMs: Long,
        lastAutomaticStillMs: Long,
        captureInProgress: Boolean,
    ): Boolean {
        if (capabilities.mode != GeminiLiveVisionCapabilities.Mode.OPPORTUNISTIC_STILL) return false
        if (captureInProgress) return false
        if (lastAutomaticStillMs <= 0L) return true
        return nowMs - lastAutomaticStillMs >= capabilities.minAutomaticStillIntervalMs
    }

    fun shouldSendVideoFrame(
        capabilities: GeminiLiveVisionCapabilities,
        userSpeaking: Boolean,
        nowMs: Long,
        lastFrameSentMs: Long,
        encodingInProgress: Boolean,
    ): Boolean {
        if (capabilities.mode != GeminiLiveVisionCapabilities.Mode.LIVE_FRAMES) return false
        if (!userSpeaking || encodingInProgress) return false
        if (lastFrameSentMs <= 0L) return true
        return nowMs - lastFrameSentMs >= LIVE_FRAME_INTERVAL_MS
    }
}
