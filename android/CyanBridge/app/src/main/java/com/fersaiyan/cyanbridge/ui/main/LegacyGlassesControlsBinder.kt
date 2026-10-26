package com.fersaiyan.cyanbridge.ui.main

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.util.Log
import android.view.View
import android.widget.Toast
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.shared.prefs.MediaPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.ui.AutoPairManager
import com.fersaiyan.cyanbridge.ui.DeviceBindDestination
import com.fersaiyan.cyanbridge.ui.hasBluetooth
import com.fersaiyan.cyanbridge.ui.main.glasses.AndroidGlassesDevices
import com.fersaiyan.cyanbridge.ui.requestBluetoothPermission
import com.fersaiyan.cyanbridge.ui.setOnClickListener
import com.fersaiyan.cyanbridge.ui.startKtxActivity
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener

/** Activity state and work that the legacy controls and Tasker commands reach. */
internal interface LegacyControlsHost {
    var isAiHijackEnabled: Boolean
    var isImageAssistantMode: Boolean
    val deviceNotifyListener: GlassesDeviceNotifyListener
    fun controlAudioRecording(start: Boolean)
    fun controlVideoRecording(start: Boolean)
    fun startVoiceQuestionFromUi()
    fun startImageQuestionFromUi()
    fun selectPhoneAssistant()
    fun selectCustomAiProvider()
    fun refreshAiModeButtons()
    fun showOfficialHeyCyanWarningOrContinue()
    fun cancelDataDownloadAttempt(reason: String, showToast: Boolean)
    fun dumpOtaServerInfo()
    fun testPullModeOta()
}

/**
 * Binds the hidden legacy glasses controls, one section at a time. Dashboard actions and Tasker
 * commands still route through these buttons, so every click first passes the same shared guards
 * (exclusive session, the selected profile's SDK-control rejection, Bluetooth permission,
 * glasses audio stop).
 */
internal class LegacyGlassesControlsBinder(
    host: MainScreenHost,
    private val controls: LegacyControlsHost,
    private val glasses: AndroidGlassesDevices,
    private val connection: DeviceConnectionCoordinator,
    private val heyCyanSettings: HeyCyanSettingsController,
    private val sessionGuard: GlassesSessionGuard,
    private val transportPermissions: GlassesTransportPermissions,
    private val meetingCaptureUi: MeetingCaptureUiController,
) : MainScreenController(host) {
    fun bind() {
        bindConnectionButtons()
        bindDeviceCommandButtons()
        bindTransferAndOtaButtons()
        bindAssistantButtons()
        bindAdvancedToggle()
        bindMeetingButtons()

        controls.refreshAiModeButtons()

        bindAssistantToggles()
    }

    private fun bindGuarded(vararg views: View, onClick: (View) -> Unit) {
        setOnClickListener(*views) {
            if (!passesSharedGuards(this)) return@setOnClickListener
            onClick(this)
        }
    }

    /** Returns false when [view]'s click was consumed by a shared guard. */
    private fun passesSharedGuards(view: View): Boolean {
        val activeSession = GlassesSessionCoordinator.currentSession()
        val isAllowedStop = view == binding.btnTransferStop &&
            activeSession == GlassesSession.MEDIA_SYNC
        if (activeSession != null && !isAllowedStop) {
            Toast.makeText(
                activity,
                "${activeSession.label.replaceFirstChar { it.uppercase() }} is using the glasses connection.",
                Toast.LENGTH_SHORT,
            ).show()
            return false
        }

        if (glasses.selectedProfile().heyCyanSdkControlsRejection != null && view in setOf(
                binding.btnConnect,
                binding.btnDisconnect,
                binding.btnAddListener,
                binding.btnSetTime,
                binding.btnVersion,
                binding.btnVideo,
                binding.btnRecord,
                binding.btnBt,
                binding.btnBattery,
                binding.btnVolume,
                binding.btnMediaCount,
                binding.btnDataDownload,
                binding.btnOtaInfo,
                binding.btnPullOtaTest,
            )
        ) {
            connection.rejectHeyCyanOnlyFeature("This HeyCyan control")
            return false
        }

        val needsBluetoothPermission = view == binding.btnConnect ||
            view == binding.btnDisconnect ||
            view == binding.btnAddListener ||
            view == binding.btnSetTime ||
            view == binding.btnVersion ||
            view == binding.btnCamera ||
            view == binding.btnVideo ||
            view == binding.btnRecord ||
            view == binding.btnBt ||
            view == binding.btnBattery ||
            view == binding.btnVolume ||
            view == binding.btnMediaCount ||
            view == binding.btnDataDownload ||
            view == binding.btnTestHijackImage ||
            view == binding.btnOtaInfo ||
            view == binding.btnPullOtaTest
        if (needsBluetoothPermission && !hasBluetooth(activity)) {
            transportPermissions.ensureBluetoothPermission("this glasses feature") {
                view.performClick()
            }
            return false
        }

        // Do not queue an unawaited audio-stop command immediately before another command
        // that needs the vendor SDK's single glassesControl response slot.
        val actionSendsGlassesControl = view == binding.btnCamera ||
            view == binding.btnVideo ||
            view == binding.btnRecord ||
            view == binding.btnMediaCount ||
            view == binding.btnDataDownload ||
            view == binding.btnTestHijackVoice ||
            view == binding.btnTestHijackImage ||
            view == binding.btnPullOtaTest
        val shouldStopGlassesAudio = view != binding.btnScan &&
            view != binding.btnConnect &&
            view != binding.btnTransferStop &&
            !actionSendsGlassesControl
        if (shouldStopGlassesAudio) {
            controls.controlAudioRecording(false)
            // If auto audio capture is enabled, give the user a short window to operate other controls.
            if (AutoAudioCapturePrefs.isEnabled(activity) && view != binding.btnRecord) {
                AutoAudioCapturePrefs.pauseForMs(activity, 90_000)
            }
        }
        return true
    }

    private fun bindConnectionButtons() {
        bindGuarded(
            binding.btnScan,
            binding.btnConnect,
            binding.btnDisconnect,
            binding.btnAddListener,
            binding.btnBt,
        ) { view ->
            when (view) {
                binding.btnScan -> {
                    requestBluetoothPermission(activity, PermissionCallback())
                }

                binding.btnConnect -> {
                    // User explicitly wants to reconnect, so re-enable auto pairing.
                    AutoPairManager.setAutoReconnectSuppressed(false, reason = "user_reconnect_button")
                    Toast.makeText(activity, "Reconnecting to glasses…", Toast.LENGTH_SHORT).show()
                    BleOperateManager.getInstance()
                        .connectDirectly(DeviceManager.getInstance().deviceAddress)
                }

                binding.btnDisconnect -> {
                    // Prevent the background reconnection loop from immediately reconnecting.
                    AutoPairManager.setAutoReconnectSuppressed(true, reason = "user_disconnect_button")
                    Toast.makeText(activity, "Disconnecting from glasses…", Toast.LENGTH_SHORT).show()
                    BleOperateManager.getInstance().unBindDevice()
                }

                binding.btnAddListener -> {
                    Toast.makeText(activity, "Registering device event listener…", Toast.LENGTH_SHORT).show()
                    LargeDataHandler.getInstance().addOutDeviceListener(100, controls.deviceNotifyListener)
                }

                binding.btnBt -> {
                    Toast.makeText(activity, "Starting classic Bluetooth scan…", Toast.LENGTH_SHORT).show()
                    //BT scan
                    BleOperateManager.getInstance().classicBluetoothStartScan()

                }
            }
        }
    }

    private fun bindDeviceCommandButtons() {
        bindGuarded(
            binding.btnSetTime,
            binding.btnVersion,
            binding.btnCamera,
            binding.btnVideo,
            binding.btnRecord,
            binding.btnBattery,
            binding.btnVolume,
            binding.btnMediaCount,
        ) { view ->
            when (view) {
                binding.btnSetTime -> {
                    Toast.makeText(activity, "Syncing glasses time…", Toast.LENGTH_SHORT).show()
                    Log.i("setTime", "setTime" + BleOperateManager.getInstance().isConnected)
                    LargeDataHandler.getInstance().syncTime { _, _ -> }
                }

                binding.btnVersion -> {
                    Toast.makeText(activity, "Reading device version…", Toast.LENGTH_SHORT).show()
                    LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
                        if (response != null) {
                            val message =
                                "WiFi FW: ${response.wifiFirmwareVersion}, BT FW: ${response.firmwareVersion}"
                            Log.i("DeviceInfo", message)
                            activity.runOnUiThread {
                                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                            }
                        } else {
                            activity.runOnUiThread {
                                Toast.makeText(
                                    activity,
                                    "Failed to get device version",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }

                binding.btnCamera -> onCameraClicked()

                binding.btnVideo -> {
                    // Toggle video recording. While video is active, pause the auto audio loop.
                    val isRecording = MediaPrefs.GlassesMedia.videoRecording[activity]
                    if (isRecording) {
                        Toast.makeText(activity, "Stopping video recording…", Toast.LENGTH_SHORT).show()
                        controls.controlVideoRecording(false)
                    } else {
                        Toast.makeText(activity, "Starting video recording…", Toast.LENGTH_SHORT).show()
                        controls.controlVideoRecording(true)
                    }
                }

                binding.btnRecord -> {
                    // Default UI behavior: start audio recording
                    controls.controlAudioRecording(true)
                }

                binding.btnBattery -> {
                    connection.requestBatteryStatus(showToast = true)
                }
                binding.btnVolume ->{
                    Toast.makeText(activity, "Requesting volume info…", Toast.LENGTH_SHORT).show()
                    //Read volume control and show values
                    LargeDataHandler.getInstance().getVolumeControl { _, response ->
                        if (response != null) {
                            val msg = """
                                Music: ${response.currVolumeMusic}/${response.maxVolumeMusic}
                                Call: ${response.currVolumeCall}/${response.maxVolumeCall}
                                System: ${response.currVolumeSystem}/${response.maxVolumeSystem}
                                Mode: ${response.currVolumeType}
                            """.trimIndent()
                            Log.i("VolumeControl", msg.replace('\n', ' '))
                            activity.runOnUiThread {
                                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                            }
                        } else {
                            activity.runOnUiThread {
                                Toast.makeText(
                                    activity,
                                    "Failed to read volume info",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                binding.btnMediaCount -> onMediaCountClicked()
            }
        }
    }

    private fun onCameraClicked() {
        glasses.selected().legacyCameraButton?.let { camera ->
            camera.capturePhoto()
            return
        }
        val permit = sessionGuard.acquireBackgroundGlassesCommand("camera command")
            ?: return
        try {
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, 0x01)
            ) { _, it ->
                try {
                    if (it.dataType == 1 && it.errorCode == 0) {
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
                } finally {
                    GlassesSessionCoordinator.releaseBackgroundCommand(permit)
                }
            }
            sessionGuard.warnIfBackgroundGlassesCommandTimesOut(permit)
        } catch (e: Exception) {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            Log.e("Camera", "Failed to send camera command", e)
        }
    }

    private fun onMediaCountClicked() {
        Toast.makeText(activity, "Requesting media count…", Toast.LENGTH_SHORT).show()
        val permit = sessionGuard.acquireBackgroundGlassesCommand("media-count command")
            ?: return
        try {
            LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x04)) { _, it ->
                try {
                    if (it.dataType == 4) {
                        val mediaCount = it.imageCount + it.videoCount + it.recordCount
                        val msg = if (mediaCount > 0) {
                            "Media not uploaded - Photos: ${it.imageCount}, Videos: ${it.videoCount}, Records: ${it.recordCount}"
                        } else {
                            "No pending media on glasses"
                        }
                        Log.i("MediaCount", msg)
                        activity.runOnUiThread {
                            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                } finally {
                    GlassesSessionCoordinator.releaseBackgroundCommand(permit)
                }
            }
            sessionGuard.warnIfBackgroundGlassesCommandTimesOut(permit)
        } catch (e: Exception) {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            Log.e("MediaCount", "Failed to request media count", e)
        }
    }

    private fun bindTransferAndOtaButtons() {
        bindGuarded(
            binding.btnDataDownload,
            binding.btnTransferStop,
            binding.btnOtaInfo,
            binding.btnPullOtaTest,
        ) { view ->
            when (view) {
                binding.btnDataDownload -> {
                    transportPermissions.ensureGlassesTransportPermissions("Wi-Fi media sync") {
                        glasses.selected().legacyDataDownload?.startSync()
                            ?: controls.showOfficialHeyCyanWarningOrContinue()
                    }
                }
                binding.btnTransferStop -> {
                    controls.cancelDataDownloadAttempt(
                        reason = "Sync stopped by user",
                        showToast = true,
                    )
                }
                binding.btnOtaInfo -> {
                    Toast.makeText(activity, "Dumping OTA server info…", Toast.LENGTH_SHORT).show()
                    controls.dumpOtaServerInfo()
                }
                binding.btnPullOtaTest -> {
                    Toast.makeText(activity, "Triggering pull‑mode OTA test…", Toast.LENGTH_SHORT).show()
                    controls.testPullModeOta()
                }
            }
        }
    }

    private fun bindAssistantButtons() {
        bindGuarded(
            binding.btnModeGemini,
            binding.btnModeChatgpt,
            binding.btnModeTasker,
            binding.btnTestHijackVoice,
            binding.btnTestHijackImage,
        ) { view ->
            when (view) {
                binding.btnTestHijackVoice -> {
                    controls.startVoiceQuestionFromUi()
                }

                binding.btnTestHijackImage -> {
                    controls.startImageQuestionFromUi()
                }

                binding.btnModeGemini -> {
                    controls.selectPhoneAssistant()
                }

                binding.btnModeChatgpt -> {
                    controls.selectPhoneAssistant()
                }

                binding.btnModeTasker -> {
                    controls.selectCustomAiProvider()
                }
            }
        }
    }

    private fun bindAdvancedToggle() {
        bindGuarded(binding.btnToggleAdvanced) {
            val container = binding.layoutAdvancedContainer
            if (container.visibility == android.view.View.VISIBLE) {
                container.visibility = android.view.View.GONE
                binding.btnToggleAdvanced.text = "Advanced ▼"
            } else {
                container.visibility = android.view.View.VISIBLE
                binding.btnToggleAdvanced.text = "Advanced ▲"
            }
        }
    }

    private fun bindMeetingButtons() {
        // Notes & Summaries entry removed (moved to Transcriptions & recordings section)
        bindGuarded(
            binding.btnMeetingStart,
            binding.btnMeetingStop,
            binding.btnMeetingBannerStop,
        ) { view ->
            when (view) {
                binding.btnMeetingStart -> {
                    meetingCaptureUi.startMeetingCaptureFromUi()
                }

                binding.btnMeetingStop, binding.btnMeetingBannerStop -> {
                    meetingCaptureUi.stopMeetingCaptureFromUi()
                }
            }
        }
    }

    private fun bindAssistantToggles() {
        binding.cbHijackEnabled.setOnCheckedChangeListener { _, isChecked ->

            controls.isAiHijackEnabled = isChecked
            if (isChecked) heyCyanSettings.configureHeyCyanWakeWordIfNeeded()
            Toast.makeText(activity, "Hijack ${if (isChecked) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }

        binding.cbImageAsAssistant.isChecked = controls.isImageAssistantMode
        binding.cbImageAsAssistant.text = if (controls.isImageAssistantMode) "Direct Assistant" else "App Sharing"

        binding.cbImageAsAssistant.setOnCheckedChangeListener { _, isChecked ->
            controls.isImageAssistantMode = isChecked
            val modeName = if (isChecked) "Direct Assistant" else "App Sharing"
            binding.cbImageAsAssistant.text = modeName
            Toast.makeText(activity, "Image Hijack: $modeName", Toast.LENGTH_SHORT).show()
        }
    }

    inner class PermissionCallback : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            if (!all) {
                // Permissions not fully granted; do nothing for now
            } else {
                activity.startActivity(AppRoute.DEVICE_BIND.intent(activity))
            }
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            super.onDenied(permissions, never)
            Toast.makeText(
                activity,
                "Bluetooth permission is required to find glasses",
                Toast.LENGTH_LONG,
            ).show()
            if (never) {
                XXPermissions.startPermissionActivity(activity, permissions)
            }
        }

    }
}
