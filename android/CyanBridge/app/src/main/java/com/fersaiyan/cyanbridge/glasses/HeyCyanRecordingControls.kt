package com.fersaiyan.cyanbridge.glasses

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.shared.prefs.MediaPrefs
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.oudmon.ble.base.communication.LargeDataHandler

/** Start/stop on-glasses video and audio recording on HeyCyan glasses. */
internal class HeyCyanRecordingControls(
    private val context: Context,
    private val host: Host,
) {
    interface Host {
        fun rejectHeyCyanOnlyFeature(feature: String): Boolean
        fun isGlassesCommandBlocked(source: String): Boolean
        fun acquireBackgroundGlassesCommand(source: String): BackgroundGlassesCommandPermit?
        fun warnIfBackgroundGlassesCommandTimesOut(permit: BackgroundGlassesCommandPermit)
    }

    fun controlVideoRecording(start: Boolean) {
        if (host.rejectHeyCyanOnlyFeature("Video recording")) return
        if (host.isGlassesCommandBlocked("video recording command")) return
        val permit = host.acquireBackgroundGlassesCommand("video recording command") ?: return
        val value = if (start) 0x02 else 0x03

        // While video is recording, pause the auto audio loop.
        if (start) setVideoRecording(true) // optimistic

        try {
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, value.toByte())
            ) { _, rsp ->
                try {
                    if (rsp.dataType == 1) {
                        if (rsp.errorCode == 0) {
                            // Anything other than 2 means not actively recording video.
                            setVideoRecording(rsp.workTypeIng == 2)
                        } else if (start) {
                            // Command failed; revert optimistic state.
                            setVideoRecording(false)
                        }
                    }
                } finally {
                    GlassesSessionCoordinator.releaseBackgroundCommand(permit)
                }
            }
            host.warnIfBackgroundGlassesCommandTimesOut(permit)
        } catch (e: Exception) {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            Log.e("VideoRecording", "Failed to send video recording command", e)
        }
    }

    private fun setVideoRecording(recording: Boolean) {
        MediaPrefs.GlassesMedia.videoRecording[context] = recording
        AutoAudioCapturePrefs.setPausedForVideo(context, recording)
    }

    fun controlAudioRecording(start: Boolean) {
        if (host.rejectHeyCyanOnlyFeature("On-glasses audio recording")) return
        if (host.isGlassesCommandBlocked("audio recording command")) return
        val permit = host.acquireBackgroundGlassesCommand("audio recording command") ?: return
        val value = if (start) 0x08 else 0x0c
        try {
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, value.toByte())
            ) { _, it ->
                try {
                    if (it.dataType == 1) {
                        if (it.errorCode == 0) {
                            when (it.workTypeIng) {
                                2 -> {
                                    //Glasses are recording video
                                }
                                4 -> {
                                    //Glasses are in transfer mode
                                }
                                5 -> {
                                    //Glasses are in OTA mode
                                }
                                1, 6 ->{
                                    //Glasses are in camera mode
                                }
                                7 -> {
                                    //Glasses are in AI conversation
                                }
                                8 ->{
                                    //Glasses are in recording mode
                                }
                            }
                        }
                    } else {
                        //Execute start and end
                    }
                } finally {
                    GlassesSessionCoordinator.releaseBackgroundCommand(permit)
                }
            }
            host.warnIfBackgroundGlassesCommandTimesOut(permit)
        } catch (e: Exception) {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            Log.e("AudioRecording", "Failed to send audio recording command", e)
        }
    }
}
