package com.fersaiyan.cyanbridge.media

import android.content.Context

object GlassesMediaPrefs {
    private const val PREFS = "glasses_media"
    private const val KEY_VIDEO_RECORDING = "video_recording"
    private const val KEY_PHOTO_LED_SIMULATION = "photo_led_simulation"

    fun isVideoRecording(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VIDEO_RECORDING, false)
    }

    fun setVideoRecording(context: Context, recording: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VIDEO_RECORDING, recording)
            .apply()
    }

    fun isPhotoLedSimulationEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PHOTO_LED_SIMULATION, false)

    fun setPhotoLedSimulationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PHOTO_LED_SIMULATION, enabled)
            .apply()
    }
}
