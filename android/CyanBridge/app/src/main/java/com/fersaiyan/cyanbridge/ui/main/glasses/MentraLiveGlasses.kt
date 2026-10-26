package com.fersaiyan.cyanbridge.ui.main.glasses

import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.bridge.core.BatteryReporting
import com.fersaiyan.cyanbridge.bridge.core.CameraCapture
import com.fersaiyan.cyanbridge.bridge.core.DeviceInfoReporting
import com.fersaiyan.cyanbridge.bridge.core.GestureType
import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.bridge.core.InputEvent
import com.fersaiyan.cyanbridge.bridge.core.MediaSyncTransport
import com.fersaiyan.cyanbridge.bridge.core.OnboardRecording
import com.fersaiyan.cyanbridge.bridge.core.VolumeReporting
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.mentra.MentraLiveManager
import com.fersaiyan.cyanbridge.media.sync.GlassesMediaLibrary
import com.fersaiyan.cyanbridge.media.sync.MentraMediaSyncController
import com.fersaiyan.cyanbridge.media.sync.TransferProgressPresenter
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.glasses.GlassesMediaCounts
import com.fersaiyan.cyanbridge.ui.AutoPairManager
import com.fersaiyan.cyanbridge.ui.DeviceBindActivity
import com.fersaiyan.cyanbridge.ui.startKtxActivity
import com.mentra.bluetoothsdk.GlassesRuntimeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Mentra Live over the Mentra Bluetooth SDK ([MentraLiveManager]). Pairing runs through Device
 * Bind like the other families. The action button asks the assistant: a press asks about what
 * the glasses see, a long press asks a spoken question. Photos and videos taken from the
 * dashboard stay in the glasses gallery until Sync downloads them over the glasses hotspot
 * ([MentraMediaSyncController]); AI photos reach the phone through
 * [com.fersaiyan.cyanbridge.glasses.GlassesAiPhotoCapture].
 */
internal class MentraLiveGlasses(private val context: GlassesDeviceContext) : AndroidGlassesDevice {
    override val profile: GlassesFamilyProfile = GlassesFamilyProfiles.mentraLive

    private val activity get() = context.activity
    private val manager: MentraLiveManager get() = MentraLiveManager.getInstance(activity)
    private var observers: Job? = null
    private val gallerySync by lazy {
        MentraMediaSyncController(
            activity = activity,
            transferUi = TransferProgressPresenter({ context.binding }, context.screen::updateDashboardState),
            mediaLibrary = GlassesMediaLibrary(activity),
            onSynced = ::refreshMediaCounts,
        )
    }

    private val isSelected: Boolean
        get() = DeviceProfileStore.selectedClass(activity) == DeviceClass.MENTRA_LIVE

    override val connection: GlassesConnectionControl = object : GlassesConnectionControl {
        override fun scan() {
            activity.startKtxActivity<DeviceBindActivity>()
        }

        override fun reconnect() {
            val saved = DeviceProfileStore.loadLastSelected(activity)
                ?.takeIf { it.selectedClass == DeviceClass.MENTRA_LIVE }
                ?: return activity.startKtxActivity<DeviceBindActivity>()
            AutoPairManager.setAutoReconnectSuppressed(false, reason = "user_reconnect")
            if (!manager.reconnectSaved(saved.advertisedName, saved.macAddress, reason = "user")) {
                toast(R.string.mentra_reconnect_busy)
            }
        }

        override fun disconnect() {
            AutoPairManager.setAutoReconnectSuppressed(true, reason = "user_disconnect_button")
            manager.disconnect()
        }
    }

    override val camera = CameraCapture {
        whenReady {
            runCatching { manager.capturePhotoToGlasses() }
                .onSuccess { toast(R.string.mentra_photo_saved) }
                .onFailure { toast(R.string.mentra_photo_failed, it.message.orEmpty()) }
        }
    }

    override val recording = object : OnboardRecording {
        override fun toggleVideoRecording() = whenReady {
            runCatching { manager.toggleVideoRecording() }
                .onSuccess { recording ->
                    toast(if (recording) R.string.mentra_video_started else R.string.mentra_video_stopped)
                    context.screen.updateDashboardState { it.copy(isVideoRecording = recording) }
                }
                .onFailure { toast(R.string.mentra_video_failed, it.message.orEmpty()) }
        }

        override fun toggleAudioRecording() = toast(R.string.mentra_no_audio_recording)
    }

    override val mediaSync = object : MediaSyncTransport {
        override fun requestMediaCount() = whenReady {
            runCatching { manager.galleryCounts() }
                .onSuccess { (photos, videos) ->
                    toast(R.string.mentra_media_count, photos, videos)
                    context.screen.updateDashboardState {
                        it.copy(mediaCounts = GlassesMediaCounts(photos, videos, 0))
                    }
                }
                .onFailure { toast(R.string.mentra_request_failed, it.message.orEmpty()) }
        }

        override fun startSync() = gallerySync.start(manager)

        override fun stopSync() = gallerySync.stop()
    }

    /** Updates the dashboard media counts after a sync, without the count toast. */
    private fun refreshMediaCounts() {
        if (MentraLiveManager.instanceOrNull()?.ready != true) return
        activity.lifecycleScope.launch {
            runCatching { manager.galleryCounts() }.onSuccess { (photos, videos) ->
                context.screen.updateDashboardState { it.copy(mediaCounts = GlassesMediaCounts(photos, videos, 0)) }
            }
        }
    }

    override val battery = object : BatteryReporting {
        override val pollsPeriodically: Boolean = false

        override fun requestBattery() {
            val level = MentraLiveManager.instanceOrNull()?.batteryLevel()
            when {
                level == null -> toast(R.string.mentra_battery_unknown)
                manager.isCharging() -> toast(R.string.mentra_battery_charging, level)
                else -> toast(R.string.mentra_battery, level)
            }
        }

        override fun refreshAfterSelection() = renderConnectionStatus(manager.ready)
    }

    override val deviceInfo = object : DeviceInfoReporting {
        override fun requestVersion() = whenReady {
            runCatching { manager.requestVersionInfo().firmwareVersion }
                .onSuccess { version -> toast(R.string.mentra_version, version ?: manager.firmwareSummary().orEmpty()) }
                .onFailure { toast(R.string.mentra_request_failed, it.message.orEmpty()) }
        }

        override fun syncTime() = whenReady {
            runCatching { manager.syncTime() }
                .onSuccess { toast(R.string.mentra_time_synced) }
                .onFailure { toast(R.string.mentra_request_failed, it.message.orEmpty()) }
        }
    }

    override val volume = VolumeReporting {
        whenReady {
            runCatching { manager.mediaVolume() }
                .onSuccess { level -> level?.let { toast(R.string.mentra_volume, it) } }
                .onFailure { toast(R.string.mentra_request_failed, it.message.orEmpty()) }
        }
    }

    /** The SDK keeps its own foreground service with a notification while it runs. */
    override val runsForegroundConnectionService: Boolean = true

    override fun renderConnectionStatus(connected: Boolean) {
        val state = manager.glasses.value
        val label = when {
            state is GlassesRuntimeState.Connected && state.ready -> activity.getString(
                R.string.mentra_status_connected,
                state.device?.bluetoothName ?: DeviceProfileStore.loadLastSelected(activity)?.advertisedName.orEmpty(),
            )
            state.connected || state.connection.isBusy -> activity.getString(R.string.mentra_status_connecting)
            else -> activity.getString(R.string.mentra_status_disconnected)
        }
        context.binding.statusText.text = label
        context.connection.updateBatteryText(manager.batteryLevel())
        context.screen.updateDashboardState { it.copy(connectionLabel = label) }
        context.connection.updateDeviceClassText()
    }

    /** Mirrors the SDK state into the dashboard and routes the button to the assistant. */
    override fun onSelectedForDashboard() {
        if (observers?.isActive == true) return
        observers = activity.lifecycleScope.launch {
            launch {
                manager.glasses.collect { state ->
                    if (isSelected) context.connection.updateConnectionStatus(state.ready)
                }
            }
            launch {
                manager.inputEvents.collect { event -> if (isSelected) onButton(event) }
            }
        }
    }

    private fun onButton(event: InputEvent) {
        val button = event as? InputEvent.Button ?: return
        when (button.gesture) {
            GestureType.SINGLE_TAP -> {
                Log.i(MentraLiveManager.TAG, "Button press -> image question")
                context.binding.btnTestHijackImage.performClick()
            }
            GestureType.LONG_PRESS -> {
                Log.i(MentraLiveManager.TAG, "Button long press -> voice question")
                context.binding.btnTestHijackVoice.performClick()
            }
            else -> Log.i(MentraLiveManager.TAG, "Button ${button.gesture} has no assistant action")
        }
    }

    override fun stopMediaSyncForActivityDestroy() = gallerySync.cancelForDestroy()

    private fun whenReady(block: suspend () -> Unit) {
        if (MentraLiveManager.instanceOrNull()?.ready != true) return toast(R.string.mentra_not_ready)
        activity.lifecycleScope.launch { block() }
    }

    private fun toast(@StringRes message: Int, vararg args: Any) {
        Toast.makeText(activity, activity.getString(message, *args), Toast.LENGTH_SHORT).show()
    }
}
