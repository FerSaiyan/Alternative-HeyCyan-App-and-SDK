package com.fersaiyan.cyanbridge.audio

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.MediaPrefs.MeetingCapture
import com.fersaiyan.cyanbridge.shared.prefs.clear
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.prefs.store
import com.fersaiyan.cyanbridge.shared.settings.CaptureSource

/**
 * Lightweight state snapshot for capture UI surfaces.
 *
 * This is not a source of truth for historical data; completed sessions are persisted in Room.
 * Instead, this helps screens recover "is recording" state if process/activity is recreated.
 */
object MeetingCapturePrefs {
    fun setRecording(
        context: Context,
        isRecording: Boolean,
        startAtMs: Long? = null,
        source: CaptureSource? = null,
        audioPath: String? = null,
        deviceClass: String? = null,
    ) {
        MeetingCapture.edit(context) {
            this[MeetingCapture.isRecording] = isRecording
            this[MeetingCapture.startAtMs] = startAtMs
            this[MeetingCapture.source] = source?.name
            this[MeetingCapture.audioPath] = audioPath
            this[MeetingCapture.deviceClass] = deviceClass
        }
    }

    data class State(
        val isRecording: Boolean,
        val startAtMs: Long?,
        val source: CaptureSource?,
        val audioPath: String?,
        val deviceClass: String?,
    )

    fun getState(context: Context): State {
        val p = MeetingCapture.store(context)
        return State(
            isRecording = p[MeetingCapture.isRecording],
            startAtMs = p[MeetingCapture.startAtMs],
            source = p[MeetingCapture.source]?.let { runCatching { CaptureSource.valueOf(it) }.getOrNull() },
            audioPath = p[MeetingCapture.audioPath],
            deviceClass = p[MeetingCapture.deviceClass],
        )
    }

    fun clear(context: Context) = MeetingCapture.clear(context)
}
