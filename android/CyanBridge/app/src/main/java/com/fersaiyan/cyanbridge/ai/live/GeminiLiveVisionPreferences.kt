package com.fersaiyan.cyanbridge.ai.live

import android.content.Context

/** Gemini Live-only visual refresh cadence. Zero keeps the initial image only. */
object GeminiLiveVisionPreferences {
    // Simplified to two modes: only the first image, or a fresh image on every
    // significant speech turn (energy model). The 2 s guard avoids HeyCyan shutter
    // spam while BLE transfer (~2-4 s) is still in progress.
    val delayOptionsSeconds = listOf(0, 2)
    private val legacyDelaySeconds = listOf(5, 10, 15)

    fun imageDelaySeconds(context: Context): Int {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_IMAGE_DELAY_SECONDS, DEFAULT_IMAGE_DELAY_SECONDS)
        if (stored in delayOptionsSeconds) return stored
        // Migrates 5/10/15 from older builds to the new "every turn" mode.
        if (stored in legacyDelaySeconds) return 2
        return DEFAULT_IMAGE_DELAY_SECONDS
    }

    fun setImageDelaySeconds(context: Context, seconds: Int): Int {
        val normalized = when (seconds) {
            in delayOptionsSeconds -> seconds
            in legacyDelaySeconds -> 2
            else -> DEFAULT_IMAGE_DELAY_SECONDS
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_IMAGE_DELAY_SECONDS, normalized)
            .apply()
        return normalized
    }

    fun automaticRefreshIntervalMs(context: Context): Long? =
        imageDelaySeconds(context).takeIf { it > 0 }?.times(1_000L)

    private const val PREFS_NAME = "gemini_live"
    private const val KEY_IMAGE_DELAY_SECONDS = "live_image_delay"
    private const val DEFAULT_IMAGE_DELAY_SECONDS = 0
}
