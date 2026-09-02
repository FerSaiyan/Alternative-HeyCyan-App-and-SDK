package com.fersaiyan.cyanbridge.ai.live

import android.content.Context

/** Gemini Live-only visual refresh cadence. Zero keeps the initial image only. */
object GeminiLiveVisionPreferences {
    val delayOptionsSeconds = listOf(0, 5, 10, 15)

    fun imageDelaySeconds(context: Context): Int {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_IMAGE_DELAY_SECONDS, DEFAULT_IMAGE_DELAY_SECONDS)
        return stored.takeIf(delayOptionsSeconds::contains) ?: DEFAULT_IMAGE_DELAY_SECONDS
    }

    fun setImageDelaySeconds(context: Context, seconds: Int): Int {
        val normalized = seconds.takeIf(delayOptionsSeconds::contains) ?: DEFAULT_IMAGE_DELAY_SECONDS
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
