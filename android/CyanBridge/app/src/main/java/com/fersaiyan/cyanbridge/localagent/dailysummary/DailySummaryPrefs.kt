package com.fersaiyan.cyanbridge.localagent.dailysummary

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs.DailySummary
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

object DailySummaryPrefs {
    /**
     * Simple regen rate-limit to avoid accidental hammering of the relay endpoint.
     */
    const val MIN_REGENERATE_INTERVAL_MS: Long = 2 * 60 * 1000L

    fun getLastGeneratedAtMs(context: Context, date: String): Long =
        DailySummary.lastGeneratedAtMs[date.trim()][context]

    fun setLastGeneratedAtMs(context: Context, date: String, tsMs: Long) {
        DailySummary.edit(context) {
            this[DailySummary.lastGeneratedAtMs[date.trim()]] = tsMs
            this[DailySummary.lastCaptureProcessedAtMs[date.trim()]] = tsMs
        }
    }

    /**
     * Returns the timestamp up to which captures have been processed for this date.
     * Used for incremental summarization - only captures AFTER this timestamp are fed to the model.
     */
    fun getLastCaptureProcessedAtMs(context: Context, date: String): Long =
        DailySummary.lastCaptureProcessedAtMs[date.trim()][context]

    /**
     * Sets the capture processing watermark for incremental updates.
     * Normally set automatically when summary is generated.
     */
    fun setLastCaptureProcessedAtMs(context: Context, date: String, tsMs: Long) {
        DailySummary.lastCaptureProcessedAtMs[date.trim()][context] = tsMs
    }

    fun remainingCooldownMs(context: Context, date: String, nowMs: Long = System.currentTimeMillis()): Long {
        val last = getLastGeneratedAtMs(context, date)
        val elapsed = nowMs - last
        return (MIN_REGENERATE_INTERVAL_MS - elapsed).coerceAtLeast(0L)
    }
}
