package com.fersaiyan.cyanbridge.ai.live

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

/** Gemini Live-only visual refresh mode. Zero keeps the initial image only. */
object GeminiLiveVisionPreferences {
    // The UI still persists 2 for backward compatibility with the existing dashboard state,
    // but it is now a mode sentinel: 0 = only first image, 2 = every significant speech turn.
    // There is no time-based cooldown in the every-turn mode.
    val delayOptionsSeconds = listOf(0, 2)
    private val legacyDelaySeconds = listOf(5, 10, 15)

    fun imageDelaySeconds(context: Context): Int {
        val stored = AssistantPrefs.GeminiLive.imageDelaySeconds[context]
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
        AssistantPrefs.GeminiLive.imageDelaySeconds[context] = normalized
        return normalized
    }

    fun automaticRefreshIntervalMs(context: Context): Long? =
        if (imageDelaySeconds(context) > 0) 0L else null

    private const val DEFAULT_IMAGE_DELAY_SECONDS = 0
}
