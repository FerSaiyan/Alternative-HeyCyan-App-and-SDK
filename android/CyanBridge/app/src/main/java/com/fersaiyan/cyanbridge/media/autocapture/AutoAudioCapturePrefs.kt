package com.fersaiyan.cyanbridge.media.autocapture

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.MediaPrefs.AutoAudioCapture
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

object AutoAudioCapturePrefs {
    fun isEnabled(context: Context): Boolean = AutoAudioCapture.enabled[context]

    fun setEnabled(context: Context, enabled: Boolean) {
        AutoAudioCapture.enabled[context] = enabled
    }

    fun getSuccessfulLoops(context: Context): Int = AutoAudioCapture.successfulLoops[context].coerceAtLeast(0)

    fun incrementSuccessfulLoops(context: Context): Int {
        val next = (AutoAudioCapture.successfulLoops[context] + 1).coerceAtLeast(0)
        AutoAudioCapture.successfulLoops[context] = next
        return next
    }

    fun getLoopsPerSync(context: Context): Int = AutoAudioCapture.loopsPerSync[context]

    fun setLoopsPerSync(context: Context, loops: Int) {
        AutoAudioCapture.loopsPerSync[context] = loops
    }

    fun isVisualNotesEnabled(context: Context): Boolean = AutoAudioCapture.visualNotesEnabled[context]

    fun setVisualNotesEnabled(context: Context, enabled: Boolean) {
        AutoAudioCapture.visualNotesEnabled[context] = enabled
    }

    fun isSpeechExtendEnabled(context: Context): Boolean = AutoAudioCapture.speechExtendEnabled[context]

    fun setSpeechExtendEnabled(context: Context, enabled: Boolean) {
        AutoAudioCapture.speechExtendEnabled[context] = enabled
    }

    fun isPausedForMeeting(context: Context): Boolean = AutoAudioCapture.pausedForMeeting[context]

    fun setPausedForMeeting(context: Context, paused: Boolean) {
        AutoAudioCapture.pausedForMeeting[context] = paused
    }

    fun isPausedForVideo(context: Context): Boolean = AutoAudioCapture.pausedForVideo[context]

    fun setPausedForVideo(context: Context, paused: Boolean) {
        AutoAudioCapture.pausedForVideo[context] = paused
    }

    fun getPauseUntilMs(context: Context): Long = AutoAudioCapture.pauseUntilMs[context]

    fun pauseForMs(context: Context, durationMs: Long) {
        val now = System.currentTimeMillis()
        AutoAudioCapture.pauseUntilMs[context] = (now + durationMs).coerceAtLeast(now)
    }

    fun getLastPauseReason(context: Context): String = AutoAudioCapture.lastPauseReason[context]

    fun setLastPauseReason(context: Context, reason: String) {
        AutoAudioCapture.lastPauseReason[context] = reason.take(200)
    }

    fun clearLastPauseReason(context: Context) {
        setLastPauseReason(context, "")
    }
}
