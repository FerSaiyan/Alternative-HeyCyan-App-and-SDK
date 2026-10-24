package com.fersaiyan.cyanbridge.ui.main

import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator

/** Runs Tasker commands delivered to the main screen through the legacy HeyCyan controls. */
internal class TaskerCommandHandler(
    host: MainScreenHost,
    private val controls: LegacyControlsHost,
) : MainScreenController(host) {
    fun handleTaskerCommand(startIntent: Intent?) {
        if (startIntent == null) return

        val isFromTaskerAction = startIntent.action == MainActivity.actionTaskerCommand(activity.packageName)
        val command = startIntent.getStringExtra(MainActivity.EXTRA_TASKER_COMMAND)

        if (!isFromTaskerAction && command.isNullOrBlank()) {
            return
        }

        val normalizedCommand = command?.lowercase() ?: return
        val activeSession = GlassesSessionCoordinator.currentSession()
        if (activeSession != null) {
            Log.w(
                "GlassesSession",
                "Ignoring Tasker command '$normalizedCommand'; ${activeSession.label} owns the SDK BLE/P2P slots",
            )
            Toast.makeText(
                activity,
                "${activeSession.label.replaceFirstChar { it.uppercase() }} is using the glasses connection.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        when (normalizedCommand) {
            "scan" -> binding.btnScan.performClick()
            "connect" -> binding.btnConnect.performClick()
            "disconnect" -> binding.btnDisconnect.performClick()
            "add_listener" -> binding.btnAddListener.performClick()
            "set_time" -> binding.btnSetTime.performClick()
            "version" -> binding.btnVersion.performClick()
            "camera" -> binding.btnCamera.performClick()

            // Video recording controls
            "video" -> binding.btnVideo.performClick()
            "video_start" -> controls.controlVideoRecording(true)
            "video_stop" -> controls.controlVideoRecording(false)

            // Audio recording controls
            "record" -> binding.btnRecord.performClick()
            "record_start" -> controls.controlAudioRecording(true)
            "record_stop" -> controls.controlAudioRecording(false)

            "bt_scan" -> binding.btnBt.performClick()
            "battery" -> binding.btnBattery.performClick()
            "volume" -> binding.btnVolume.performClick()
            "media_count" -> binding.btnMediaCount.performClick()
            "data_download" -> binding.btnDataDownload.performClick()
        }
    }
}
