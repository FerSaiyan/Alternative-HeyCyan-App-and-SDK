package com.fersaiyan.cyanbridge.ui.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.audio.MeetingCapturePrefs
import com.fersaiyan.cyanbridge.audio.MeetingCaptureService
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.shared.settings.CaptureSource
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions

/** Meeting capture pipeline on the main screen: start/stop, timer and recording indicator. */
internal class MeetingCaptureUiController(
    host: MainScreenHost,
    /** Called after every capture state broadcast so dependent UI can refresh. */
    private val onCaptureStateChanged: () -> Unit,
) : MainScreenController(host) {
    val meetingTimerOptions: List<Pair<Long?, String>> = listOf(
        null to "No timer",
        15L * 60L to "15 min",
        60L * 60L to "1 hour",
        3L * 60L * 60L to "3 hours",
    )
    private var meetingCaptureStateReceiver: BroadcastReceiver? = null

    fun setupMeetingCaptureUi() {
        val labels = meetingTimerOptions.map { it.second }
        val adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerMeetingTimer.adapter = adapter
        binding.spinnerMeetingTimer.setSelection(dashboardState.meeting.timerIndex)
        syncMeetingCaptureUiFromPrefs()
    }

    private fun selectedMeetingTimerDurationSec(): Long? { 
        val idx = dashboardState.meeting.timerIndex
        return meetingTimerOptions.getOrNull(idx)?.first
    }

    private fun requestMeetingCapturePermissions(onGranted: () -> Unit) {
        val perms = mutableListOf<String>(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        XXPermissions.with(activity)
            .permission(perms)
            .request(object : OnPermissionCallback {
                override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                    if (all) onGranted() else {
                        Toast.makeText(activity, "Missing permissions for recording", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                    super.onDenied(permissions, never)
                    Toast.makeText(activity, "Recording permission denied", Toast.LENGTH_SHORT).show()
                    if (never) {
                        XXPermissions.startPermissionActivity(activity, permissions)
                    }
                }
            })
    }

    fun startMeetingCaptureFromUi() {
        requestMeetingCapturePermissions {
            val deviceClass = DeviceProfileStore.loadLastSelected(activity)?.selectedClass?.name ?: "UNKNOWN"
            val durationSec = selectedMeetingTimerDurationSec()

            // Optimistic UI so user instantly sees a recording indicator.
            setRecordingUi(isRecording = true, source = null)
            binding.tvMeetingBanner.text = "Starting recording…"
            updateDashboardState { state ->
                state.copy(meeting = state.meeting.copy(bannerLabel = "Starting recording…"))
            }

            MeetingCaptureService.start(activity, timerDurationSec = durationSec, deviceClass = deviceClass)
        }
    }

    fun stopMeetingCaptureFromUi() {
        binding.tvMeetingBanner.text = "Stopping…"
        updateDashboardState { state ->
            state.copy(meeting = state.meeting.copy(bannerLabel = "Stopping…"))
        }
        MeetingCaptureService.stop(activity)
    }

    fun registerMeetingCaptureReceiver() {
        if (meetingCaptureStateReceiver != null) return

        meetingCaptureStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != MeetingCaptureService.ACTION_STATE) return

                val isRecording = intent.getBooleanExtra(MeetingCaptureService.EXTRA_IS_RECORDING, false)
                val source = intent.getStringExtra(MeetingCaptureService.EXTRA_SOURCE)?.let {
                    runCatching { CaptureSource.valueOf(it) }.getOrNull()
                }
                val stopReason = intent.getStringExtra(MeetingCaptureService.EXTRA_STOP_REASON)
                val error = intent.getStringExtra(MeetingCaptureService.EXTRA_ERROR)

                setRecordingUi(isRecording = isRecording, source = source)

                if (!isRecording && stopReason == "timer") {
                    Toast.makeText(activity, "Meeting capture auto-stopped (timer)", Toast.LENGTH_SHORT).show()
                }
                if (!error.isNullOrBlank()) {
                    Toast.makeText(activity, "Recording error: $error", Toast.LENGTH_LONG).show()
                }
                onCaptureStateChanged()
            }
        }

        LocalBroadcastManager.getInstance(activity)
            .registerReceiver(meetingCaptureStateReceiver!!, IntentFilter(MeetingCaptureService.ACTION_STATE))
    }

    fun unregisterMeetingCaptureReceiver() {
        val r = meetingCaptureStateReceiver ?: return
        meetingCaptureStateReceiver = null
        runCatching {
            LocalBroadcastManager.getInstance(activity).unregisterReceiver(r)
        }
    }

    fun syncMeetingCaptureUiFromPrefs() {
        val state = MeetingCapturePrefs.getState(activity)
        setRecordingUi(isRecording = state.isRecording, source = state.source)
    }

    private fun setRecordingUi(isRecording: Boolean, source: CaptureSource?) {
        binding.btnMeetingStart.isEnabled = !isRecording
        binding.btnMeetingStop.isEnabled = isRecording
        binding.btnMeetingBannerStop.isEnabled = isRecording

        if (isRecording) {
            binding.meetingRecordingBanner.visibility = android.view.View.VISIBLE
            val src = when (source) {
                CaptureSource.BLUETOOTH_MIC -> "Bluetooth mic"
                CaptureSource.PHONE_MIC -> "Phone mic"
                null -> "(detecting…)"
            }
            binding.tvMeetingBanner.text = "Recording active · $src"
            binding.tvMeetingSource.text = "Source: $src"
            updateDashboardState { state ->
                state.copy(
                    meeting = state.meeting.copy(
                        isRecording = true,
                        sourceLabel = src,
                        bannerLabel = "Recording active · $src",
                    ),
                )
            }
        } else {
            binding.meetingRecordingBanner.visibility = android.view.View.GONE
            binding.tvMeetingSource.text = "Source: (not recording)"
            updateDashboardState { state ->
                state.copy(
                    meeting = state.meeting.copy(
                        isRecording = false,
                        sourceLabel = "(not recording)",
                        bannerLabel = "",
                    ),
                )
            }
        }
    }
}
