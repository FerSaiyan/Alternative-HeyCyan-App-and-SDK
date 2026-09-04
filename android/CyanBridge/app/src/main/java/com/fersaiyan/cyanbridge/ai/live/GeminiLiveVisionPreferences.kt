package com.fersaiyan.cyanbridge.ai.live

import android.content.Context

/** Gemini Live-only visual refresh mode. Zero keeps the initial image only. */
object GeminiLiveVisionPreferences {
    // The UI still persists 2 for backward compatibility with the existing dashboard state,
    // but it is now a mode sentinel: 0 = only first image, 2 = every significant speech turn.
    // There is no time-based cooldown in the every-turn mode.
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
        if (imageDelaySeconds(context) > 0) 0L else null

    private const val PREFS_NAME = "gemini_live"
    private const val KEY_IMAGE_DELAY_SECONDS = "live_image_delay"
    private const val DEFAULT_IMAGE_DELAY_SECONDS = 0
}
