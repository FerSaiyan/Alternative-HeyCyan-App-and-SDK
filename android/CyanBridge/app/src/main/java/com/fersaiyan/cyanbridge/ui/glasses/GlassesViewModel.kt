package com.fersaiyan.cyanbridge.ui.glasses

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.audio.CaptureSource
import com.fersaiyan.cyanbridge.audio.MeetingCapturePrefs
import com.fersaiyan.cyanbridge.audio.MeetingCaptureService
import com.fersaiyan.cyanbridge.ui.AutoPairManager
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GlassesUiState(
    val isConnected: Boolean = false,
    val deviceClass: String = "Unknown",
    val batteryLevel: Int? = null,
    val storageInfo: String = "--",
    val transferPhotos: Int = 0,
    val transferVideos: Int = 0,
    val transferAudio: Int = 0,
    val transferProgress: Float = 0f,
    val transferDetail: String = "Idle",
    val isTransferring: Boolean = false,
    val isRecording: Boolean = false,
    val recordingSource: String = "(not recording)",
    val agentStatus: String = "Unknown",
    val agentLastError: String = "(none)",
    val imageAutomationEnabled: Boolean = false,
)

class GlassesViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(GlassesUiState())
    val uiState: StateFlow<GlassesUiState> = _uiState.asStateFlow()

    private var meetingReceiver: BroadcastReceiver? = null
    private var bleReceiver: BroadcastReceiver? = null
    private var batteryCallbackRegistered = false

    init {
        val appCtx = application.applicationContext
        refreshConnectionState()
        syncRecordingState(appCtx)

        _uiState.value = _uiState.value.copy(
            imageAutomationEnabled = CommunityPluginPrefs.isGeminiChatGptImageAutomationEnabled(appCtx),
        )

        registerMeetingReceiver(appCtx)
        registerBleReceiver(appCtx)
        startConnectionPolling()
    }

    fun refreshConnectionState() {
        val connected = BleOperateManager.getInstance().isConnected
        _uiState.value = _uiState.value.copy(isConnected = connected)
    }

    fun startAutoPair() {
        val ctx = getApplication<Application>().applicationContext
        AutoPairManager.start(ctx)
    }

    fun reconnect() {
        AutoPairManager.setAutoReconnectSuppressed(false, reason = "user_reconnect_button")
        try {
            BleOperateManager.getInstance()
                .connectDirectly(DeviceManager.getInstance().deviceAddress)
        } catch (_: Exception) {
        }
        refreshConnectionState()
    }

    fun disconnect() {
        AutoPairManager.setAutoReconnectSuppressed(true, reason = "user_disconnect_button")
        BleOperateManager.getInstance().unBindDevice()
        refreshConnectionState()
    }

    fun requestBattery() {
        if (!BleOperateManager.getInstance().isConnected) return
        try {
            if (!batteryCallbackRegistered) {
                batteryCallbackRegistered = true
                LargeDataHandler.getInstance().addBatteryCallBack("glasses_screen") { _, response ->
                    Log.i("GlassesVM", "Battery callback: $response")
                }
            }
            LargeDataHandler.getInstance().syncBattery()
        } catch (_: Exception) {
        }
    }

    fun requestVersion() {
        if (!BleOperateManager.getInstance().isConnected) return
        try {
            LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
                Log.i("GlassesVM", "DeviceInfo: $response")
            }
        } catch (_: Exception) {
        }
    }

    fun requestMediaCount() {
        if (!BleOperateManager.getInstance().isConnected) return
        try {
            LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x04)) { _, it ->
                Log.i(
                    "GlassesVM",
                    "MediaCount: img=${it.imageCount} vid=${it.videoCount} rec=${it.recordCount}",
                )
            }
        } catch (_: Exception) {
        }
    }

    fun syncTime() {
        if (!BleOperateManager.getInstance().isConnected) return
        try {
            LargeDataHandler.getInstance().syncTime { _, _ -> }
        } catch (_: Exception) {
        }
    }

    fun requestVolume() {
        if (!BleOperateManager.getInstance().isConnected) return
        try {
            LargeDataHandler.getInstance().getVolumeControl { _, response ->
                Log.i("GlassesVM", "Volume: $response")
            }
        } catch (_: Exception) {
        }
    }

    fun sendPhotoCommand() {
        try {
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, 0x01),
            ) { _, _ -> }
        } catch (_: Exception) {
        }
    }

    fun sendVideoCommand() {
        try {
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, 0x02),
            ) { _, _ -> }
        } catch (_: Exception) {
        }
    }

    fun sendAudioCommand() {
        try {
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, 0x08),
            ) { _, _ -> }
        } catch (_: Exception) {
        }
    }

    fun addDeviceListener() {
        try {
            LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)
        } catch (_: Exception) {
        }
    }

    fun startMeetingCapture(timerDurationSec: Long?) {
        val ctx = getApplication<Application>().applicationContext
        val deviceClass = _uiState.value.deviceClass
        MeetingCaptureService.start(ctx, timerDurationSec = timerDurationSec, deviceClass = deviceClass)
    }

    fun stopMeetingCapture() {
        val ctx = getApplication<Application>().applicationContext
        MeetingCaptureService.stop(ctx)
    }

    fun setImageAutomationEnabled(enabled: Boolean) {
        val ctx = getApplication<Application>().applicationContext
        CommunityPluginPrefs.setGeminiChatGptImageAutomationEnabled(ctx, enabled)
        _uiState.value = _uiState.value.copy(imageAutomationEnabled = enabled)
    }

    private val deviceNotifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            Log.i("GlassesVM", "Notify: cmdType=$cmdType")
        }
    }

    private fun syncRecordingState(context: Context) {
        val state = MeetingCapturePrefs.getState(context)
        val source = when (state.source) {
            CaptureSource.BLUETOOTH_MIC -> "Bluetooth mic"
            CaptureSource.PHONE_MIC -> "Phone mic"
            null -> "(not recording)"
        }
        _uiState.value = _uiState.value.copy(
            isRecording = state.isRecording,
            recordingSource = if (state.isRecording) source else "(not recording)",
        )
    }

    private fun registerMeetingReceiver(context: Context) {
        if (meetingReceiver != null) return
        meetingReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != MeetingCaptureService.ACTION_STATE) return
                val isRecording = intent.getBooleanExtra(MeetingCaptureService.EXTRA_IS_RECORDING, false)
                val source = intent.getStringExtra(MeetingCaptureService.EXTRA_SOURCE)?.let {
                    runCatching { CaptureSource.valueOf(it) }.getOrNull()
                }
                val srcText = when (source) {
                    CaptureSource.BLUETOOTH_MIC -> "Bluetooth mic"
                    CaptureSource.PHONE_MIC -> "Phone mic"
                    null -> "(not recording)"
                }
                _uiState.value = _uiState.value.copy(
                    isRecording = isRecording,
                    recordingSource = if (isRecording) srcText else "(not recording)",
                )
            }
        }
        context.registerReceiver(meetingReceiver, IntentFilter(MeetingCaptureService.ACTION_STATE))
    }

    private fun registerBleReceiver(context: Context) {
        if (bleReceiver != null) return
        bleReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                refreshConnectionState()
            }
        }
        val filter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("com.fersaiyan.cyanbridge.BLE_CONNECTION_STATE")
        }
        context.registerReceiver(bleReceiver, filter)
    }

    private fun startConnectionPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                refreshConnectionState()
                delay(5000)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        val ctx = getApplication<Application>().applicationContext
        meetingReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        bleReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
    }
}
