package com.fersaiyan.cyanbridge.ui.main

import android.util.Log
import android.widget.Toast
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.utils.ByteUtil

/** HeyCyan onboard settings: the "Hey Cyan" wake word and the recording duration limits. */
internal class HeyCyanSettingsController(host: MainScreenHost) : MainScreenController(host) {
    var wakeWordConfiguredForConnection = false

    fun configureHeyCyanWakeWordIfNeeded() {
        if (wakeWordConfiguredForConnection ||
            !BleOperateManager.getInstance().isConnected ||
            DeviceProfileStore.selectedClass(activity) != com.fersaiyan.cyanbridge.shared.devices.DeviceClass.HEY_CYAN
        ) {
            return
        }
        wakeWordConfiguredForConnection = true
        runCatching {
            // The official app enables the onboard "Hey Cyan" detector with this SDK call.
            LargeDataHandler.getInstance().aiVoiceWake(true, true) { _, response ->
                val enabled = response.isOpen
                wakeWordConfiguredForConnection = enabled
                Log.i("DeviceNotify", "Hey Cyan wake word enabled=$enabled")
            }
        }.onFailure { error ->
            wakeWordConfiguredForConnection = false
            Log.w("DeviceNotify", "Failed to enable Hey Cyan wake word", error)
        }
    }

    fun refreshHeyCyanRecordingSettings(showDisconnectedMessage: Boolean = true) {
        if (!BleOperateManager.getInstance().isConnected ||
            DeviceProfileStore.selectedClass(activity) != com.fersaiyan.cyanbridge.shared.devices.DeviceClass.HEY_CYAN
        ) {
            if (showDisconnectedMessage) {
                Toast.makeText(activity, "Connect HeyCyan glasses to load recording limits", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val handler = LargeDataHandler.getInstance()
        handler.glassesControl(byteArrayOf(0x01, 0x02)) { _, response ->
            updateHeyCyanRecordingSettings(response)
        }
        handler.glassesControl(byteArrayOf(0x01, 0x06)) { _, response ->
            updateHeyCyanRecordingSettings(response)
        }
    }

    private fun updateHeyCyanRecordingSettings(
        response: com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse,
    ) {
        when (response.dataType) {
            2 -> activity.runOnUiThread {
                updateDashboardState { state ->
                    state.copy(
                        videoRecordingDurationSeconds = response.videoDuration,
                        videoRecordingDurationOptionsSeconds = DEFAULT_VIDEO_DURATION_OPTIONS_SECONDS,
                    )
                }
            }
            6 -> activity.runOnUiThread {
                updateDashboardState { state ->
                    state.copy(
                        audioRecordingDurationSeconds = response.recordAudioDuration,
                        audioRecordingDurationOptionsSeconds = AUDIO_DURATION_OPTIONS_SECONDS,
                    )
                }
            }
        }
    }

    fun setHeyCyanRecordingDuration(isAudio: Boolean, seconds: Int) {
        val allowed = if (isAudio) {
            AUDIO_DURATION_OPTIONS_SECONDS
        } else {
            dashboardState.videoRecordingDurationOptionsSeconds.ifEmpty {
                DEFAULT_VIDEO_DURATION_OPTIONS_SECONDS
            }
        }
        if (seconds !in allowed) return
        if (!BleOperateManager.getInstance().isConnected ||
            DeviceProfileStore.selectedClass(activity) != com.fersaiyan.cyanbridge.shared.devices.DeviceClass.HEY_CYAN
        ) {
            Toast.makeText(activity, "Connect HeyCyan glasses first", Toast.LENGTH_SHORT).show()
            return
        }

        val dataType = if (isAudio) 0x06 else 0x02
        val command = byteArrayOf(
            0x02,
            dataType.toByte(),
            0x00,
            ByteUtil.loword(seconds).toByte(),
            ByteUtil.hiword(seconds).toByte(),
        )
        LargeDataHandler.getInstance().glassesControl(command) { _, response ->
            Log.i(
                "Dashboard",
                "Set ${if (isAudio) "audio" else "video"} duration=$seconds responseType=${response.dataType}",
            )
        }
        updateDashboardState { state ->
            if (isAudio) {
                state.copy(audioRecordingDurationSeconds = seconds)
            } else {
                state.copy(videoRecordingDurationSeconds = seconds)
            }
        }
    }

    private companion object {
        val DEFAULT_VIDEO_DURATION_OPTIONS_SECONDS = listOf(15, 30, 60, 180, 540, 720)
        val AUDIO_DURATION_OPTIONS_SECONDS = listOf(1_800, 3_600, 7_200)
    }
}
