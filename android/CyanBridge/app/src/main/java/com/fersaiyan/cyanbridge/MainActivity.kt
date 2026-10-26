package com.fersaiyan.cyanbridge

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.ui.VersionUpdateChecker
import com.fersaiyan.cyanbridge.ui.OfficialHeyCyanWarningDialog
import com.fersaiyan.cyanbridge.localagent.LocalAgentController
import com.fersaiyan.cyanbridge.localagent.LocalAgentIntents
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.media.OfficialHeyCyanApp
import com.fersaiyan.cyanbridge.ota.LivePreviewCoordinator
import com.fersaiyan.cyanbridge.ota.OtaCoordinator
import com.fersaiyan.cyanbridge.media.sync.GlassesMediaLibrary
import com.fersaiyan.cyanbridge.media.sync.GlassesMediaSyncCoordinator
import com.fersaiyan.cyanbridge.media.sync.GlassesMediaSyncHost
import com.fersaiyan.cyanbridge.media.sync.TransferProgressPresenter
import com.fersaiyan.cyanbridge.media.sync.VendorMediaSyncController
import com.fersaiyan.cyanbridge.media.sync.VendorMediaSyncHost
import com.fersaiyan.cyanbridge.ota.OtaManager
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionLease
import com.fersaiyan.cyanbridge.glasses.HeyCyanRecordingControls
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.glasses.BackgroundGlassesCommandPermit
import com.fersaiyan.cyanbridge.wifiadb.DefaultWifiAdbDebugControllerFactory
import com.fersaiyan.cyanbridge.wifiadb.WifiAdbDebugCoordinator
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.view.View
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.communication.utils.ByteUtil
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.fersaiyan.cyanbridge.databinding.AcitivytMainBinding
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.plugins.autodiary.AutoDiaryService
import com.fersaiyan.cyanbridge.plugins.localagent.LocalAgentPlugin
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiaryPreferences
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiaryService
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidPreferences
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidImageCapture
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidService
import com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity
import com.fersaiyan.cyanbridge.ui.BluetoothUtils
import com.fersaiyan.cyanbridge.ui.BluetoothEvent
import com.fersaiyan.cyanbridge.ui.AutoPairManager
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLivePreviewManager
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLivePreviewManager
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.fersaiyan.cyanbridge.ui.hasBluetooth
import com.fersaiyan.cyanbridge.ui.hasNotificationPermission
import com.fersaiyan.cyanbridge.ui.requestBluetoothPermission
import com.fersaiyan.cyanbridge.ui.ensureNotificationPermission
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import com.fersaiyan.cyanbridge.ui.BatteryOptimizationGuideActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.File
import java.net.URL
import kotlinx.coroutines.delay
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.ai.router.AssistantTestKind
import com.fersaiyan.cyanbridge.ai.image.ExternalImageAutomationIntents
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionSource
import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.FirmwarePatchRequestUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import com.fersaiyan.cyanbridge.shared.glasses.GlassesTransferUiState
import com.fersaiyan.cyanbridge.shared.glasses.OtaFirmwareSource
import com.fersaiyan.cyanbridge.shared.glasses.WifiAdbDebugUiState
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.ui.main.AgentControlsUiController
import com.fersaiyan.cyanbridge.ui.main.AppDestinationNavigator
import com.fersaiyan.cyanbridge.ui.main.AssistantSetupGate
import com.fersaiyan.cyanbridge.ui.main.DashboardActionHost
import com.fersaiyan.cyanbridge.ui.main.DashboardController
import com.fersaiyan.cyanbridge.ui.main.DeviceConnectionCoordinator
import com.fersaiyan.cyanbridge.ui.main.DeviceConnectionHost
import com.fersaiyan.cyanbridge.ui.main.DeviceManagerRegistry
import com.fersaiyan.cyanbridge.ui.main.glasses.AndroidGlassesDevices
import com.fersaiyan.cyanbridge.ui.main.glasses.GlassesDeviceContext
import com.fersaiyan.cyanbridge.ui.main.glasses.GlassesDeviceHost
import com.fersaiyan.cyanbridge.ui.main.GlassesSessionGuard
import com.fersaiyan.cyanbridge.ui.main.GlassesTransportPermissions
import com.fersaiyan.cyanbridge.ui.main.HeyCyanSettingsController
import com.fersaiyan.cyanbridge.ui.main.LegacyControlsHost
import com.fersaiyan.cyanbridge.ui.main.LegacyGlassesControlsBinder
import com.fersaiyan.cyanbridge.ui.main.MainScreenHost
import com.fersaiyan.cyanbridge.ui.main.MeetingCaptureUiController
import com.fersaiyan.cyanbridge.ui.main.MeizuMyvuUiController
import com.fersaiyan.cyanbridge.ui.main.MetaRaybanUiController
import com.fersaiyan.cyanbridge.ui.main.NativePluginShortcutController
import com.fersaiyan.cyanbridge.ui.main.TaskerCommandHandler
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.shared.ui.CyanBridgeApp
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import android.content.ClipboardManager
import com.meta.wearable.dat.core.Wearables
import com.fersaiyan.cyanbridge.ai.questions.ExternalAssistantHandoff
import com.fersaiyan.cyanbridge.ai.questions.GlassesQuestionHost
import com.fersaiyan.cyanbridge.ai.questions.GlassesQuestions
import com.fersaiyan.cyanbridge.ai.questions.QuestionPhotoSource
import com.fersaiyan.cyanbridge.ui.main.AssistantModeUiController

class MainActivity : AppCompatActivity() {
    /** The glasses questions (process-wide); this screen hosts them while it exists. */
    private val questions: GlassesQuestions by lazy { GlassesQuestions.get(this) }

    // Optional Local Agent UI status
    private var agentReceiverRegistered = false
    private var imageAutomationStatusReceiverRegistered = false
    private val imageAutomationStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ExternalImageAutomationIntents.internalStatusAction(packageName)) {
                handleExternalImageAutomationStatus()
            }
        }
    }
    private val agentStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            val status = intent.getStringExtra(LocalAgentIntents.EXTRA_STATUS)
            val lastError = intent.getStringExtra(LocalAgentIntents.EXTRA_LAST_ERROR)
            val isTerminal = intent.getBooleanExtra(LocalAgentIntents.EXTRA_IS_TERMINAL, false)
            val userMessage = intent.getStringExtra(LocalAgentIntents.EXTRA_USER_MESSAGE)

            if (!status.isNullOrBlank()) {
                LocalAgentPrefs.setStatus(this@MainActivity, status)
            }
            if (!lastError.isNullOrBlank()) {
                LocalAgentPrefs.setLastError(this@MainActivity, lastError)
            }

            if (isTerminal && !userMessage.isNullOrBlank()) {
                Toast.makeText(this@MainActivity, userMessage, Toast.LENGTH_SHORT).show()
            }

            refreshAgentStatusUi()
        }
    }
    companion object {
        const val EXTRA_TASKER_COMMAND = "tasker_command"
        const val EXTRA_START_META_IMAGE_QUESTION = "start_meta_image_question"
        const val EXTRA_DEBUG_GLASSES_EVENT = "debug_glasses_event"
        const val DEBUG_EVENT_WAKE = "wake"
        const val DEBUG_EVENT_PHOTO_BUTTON = "photo_button"
        private const val TAG = "MainActivity"
        private var loggedLargeDataHandlerMethods = false

        fun actionTaskerCommand(appPackageName: String): String =
            "$appPackageName.ACTION_TASKER_COMMAND"

        fun aiEventAction(appPackageName: String): String = ExternalAssistantHandoff.aiEventAction(appPackageName)
    }

    // Keeps the existing Android control handlers alive while Compose owns the visible tree.
    private lateinit var binding: AcitivytMainBinding
    private var dashboardState by mutableStateOf(
        GlassesDashboardUiState(
            wifiAdbDebug = WifiAdbDebugUiState(isAvailable = false),
        ),
    )
    private var showDownloadFlowPicker by mutableStateOf(false)
    private var showOfficialHeyCyanWarningDialog by mutableStateOf(false)
    private val deviceNotifyListener by lazy { MyDeviceNotifyListener() }
    private var otaSessionLease: GlassesSessionLease? = null
    private var livePreviewSessionLease: GlassesSessionLease? = null
    private val eyevueLivePreviewManager: EyevueLivePreviewManager?
        get() = deviceManagers.eyevueLivePreviewManager
    private val tuneBudsLivePreviewManager: TuneBudsLivePreviewManager?
        get() = deviceManagers.tuneBudsLivePreviewManager
    private var tuneBudsLivePlayer by mutableStateOf<org.videolan.libvlc.MediaPlayer?>(null)
    private var tuneBudsLiveAudioMuted by mutableStateOf(false)
    private var tuneBudsLiveStopReceiver: android.content.BroadcastReceiver?
        get() = deviceManagers.tuneBudsLiveStopReceiver
        set(value) {
            deviceManagers.tuneBudsLiveStopReceiver = value
        }
    // Inline EyeVue video: exposed to Compose so the dashboard shows video
    // in-screen (EyeVue only, after live starts). No popup dialog.
    private var eyevueLivePlayer by mutableStateOf<org.videolan.libvlc.MediaPlayer?>(null)
    private var eyevueLiveStopReceiver: android.content.BroadcastReceiver?
        get() = deviceManagers.eyevueLiveStopReceiver
        set(value) {
            deviceManagers.eyevueLiveStopReceiver = value
        }
    private var eyevueLiveAudioMuted by mutableStateOf(false)
    private var heyCyanLivePlayer by mutableStateOf<org.videolan.libvlc.MediaPlayer?>(null)
    private var mediaSessionLease: GlassesSessionLease? = null
    private var wifiAdbDebugSessionLease: GlassesSessionLease? = null
    private val otaManager by lazy { OtaManager(this) }
    private var otaPreparationJob: Job?
        get() = otaCoordinator.otaPreparationJob
        set(value) {
            otaCoordinator.otaPreparationJob = value
        }
    private val personalFirmwarePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            otaCoordinator.onPersonalFirmwareDocumentPicked(uri)
        }
    private val livePreviewManager by lazy { com.fersaiyan.cyanbridge.ota.LivePreviewManager(this) }
    private var livePreviewDialog: AlertDialog? = null
    private val wifiAdbDebugController by lazy { DefaultWifiAdbDebugControllerFactory.create(this) }
    private val glassesFeatureHost = object :
        OtaCoordinator.Host,
        HeyCyanRecordingControls.Host,
        LivePreviewCoordinator.Host,
        WifiAdbDebugCoordinator.Host {
        override var dashboardState: GlassesDashboardUiState
            get() = this@MainActivity.dashboardState
            set(value) {
                this@MainActivity.dashboardState = value
            }

        override var otaSessionLease: GlassesSessionLease?
            get() = this@MainActivity.otaSessionLease
            set(value) {
                this@MainActivity.otaSessionLease = value
            }

        override var livePreviewSessionLease: GlassesSessionLease?
            get() = this@MainActivity.livePreviewSessionLease
            set(value) {
                this@MainActivity.livePreviewSessionLease = value
            }

        override var wifiAdbDebugSessionLease: GlassesSessionLease?
            get() = this@MainActivity.wifiAdbDebugSessionLease
            set(value) {
                this@MainActivity.wifiAdbDebugSessionLease = value
            }

        override var heyCyanLivePlayer: org.videolan.libvlc.MediaPlayer?
            get() = this@MainActivity.heyCyanLivePlayer
            set(value) {
                this@MainActivity.heyCyanLivePlayer = value
            }

        override var livePreviewDialog: AlertDialog?
            get() = this@MainActivity.livePreviewDialog
            set(value) {
                this@MainActivity.livePreviewDialog = value
            }

        override var eyevueLiveAudioMuted: Boolean
            get() = this@MainActivity.eyevueLiveAudioMuted
            set(value) {
                this@MainActivity.eyevueLiveAudioMuted = value
            }

        override var tuneBudsLiveAudioMuted: Boolean
            get() = this@MainActivity.tuneBudsLiveAudioMuted
            set(value) {
                this@MainActivity.tuneBudsLiveAudioMuted = value
            }

        override val glassesTeardownScope: CoroutineScope
            get() = this@MainActivity.glassesTeardownScope

        override fun launchPersonalFirmwarePicker() {
            personalFirmwarePicker.launch(arrayOf("*/*"))
        }

        override fun isHeyCyanSelected(): Boolean = this@MainActivity.isHeyCyanSelected()

        override fun isTuneBudsSelected(): Boolean = this@MainActivity.isTuneBudsSelected()

        override fun ensureGlassesTransportPermissions(feature: String, onGranted: () -> Unit) =
            this@MainActivity.ensureGlassesTransportPermissions(feature, onGranted)

        override fun acquireExclusiveGlassesSession(session: GlassesSession): GlassesSessionLease? =
            this@MainActivity.acquireExclusiveGlassesSession(session)

        override fun releaseExclusiveGlassesSession(lease: GlassesSessionLease?) =
            this@MainActivity.releaseExclusiveGlassesSession(lease)

        override fun isHeyCyanWifiSyncActive(): Boolean =
            downloadInProgress || downloadAttemptJob?.isActive == true || downloadP2pConnected

        override fun getOrCreateEyevueManager(): EyevueManager = this@MainActivity.getOrCreateEyevueManager()

        override fun getOrCreateEyevueLivePreviewManager(): EyevueLivePreviewManager =
            this@MainActivity.getOrCreateEyevueLivePreviewManager()

        override fun isEyevueMediaSyncActive(): Boolean = eyevueMediaJob?.isActive == true

        override fun getOrCreateTuneBudsManager(): TuneBudsManager = this@MainActivity.getOrCreateTuneBudsManager()

        override fun getOrCreateTuneBudsLivePreviewManager(): TuneBudsLivePreviewManager =
            this@MainActivity.getOrCreateTuneBudsLivePreviewManager()

        override fun isTuneBudsMediaSyncActive(): Boolean = tuneBudsMediaJob?.isActive == true

        override fun isTuneBudsAiPhotoInProgress(): Boolean = this@MainActivity.isTuneBudsAiPhotoInProgress()

        override fun isImageQuestionWorkActive(): Boolean = this@MainActivity.isImageQuestionWorkActive()

        override fun rejectHeyCyanOnlyFeature(feature: String): Boolean =
            this@MainActivity.rejectHeyCyanOnlyFeature(feature)

        override fun isGlassesCommandBlocked(source: String): Boolean =
            this@MainActivity.isGlassesCommandBlocked(source)

        override fun acquireBackgroundGlassesCommand(source: String): BackgroundGlassesCommandPermit? =
            this@MainActivity.acquireBackgroundGlassesCommand(source)

        override fun warnIfBackgroundGlassesCommandTimesOut(permit: BackgroundGlassesCommandPermit) =
            this@MainActivity.warnIfBackgroundGlassesCommandTimesOut(permit)
    }
    private val otaCoordinator: OtaCoordinator by lazy {
        OtaCoordinator(this, { otaManager }, glassesFeatureHost)
    }
    private val recordingControls: HeyCyanRecordingControls by lazy {
        HeyCyanRecordingControls(this, glassesFeatureHost)
    }
    private val livePreviewCoordinator: LivePreviewCoordinator by lazy {
        LivePreviewCoordinator(this, { livePreviewManager }, glassesFeatureHost)
    }
    private val wifiAdbDebugCoordinator: WifiAdbDebugCoordinator by lazy {
        WifiAdbDebugCoordinator(this, { wifiAdbDebugController }, glassesFeatureHost)
    }

    // AI Hijack settings
    private var isAiHijackEnabled = true // Default to enabled
    private var overlayPermissionPromptShown = false
    private var isImageAssistantMode = true // Use assistant vs share intent

    // Must outlive Activity destruction long enough to confirm P2P teardown.
    private val glassesTeardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val transferProgress = TransferProgressPresenter(
        bindingProvider = { binding },
        updateDashboardState = { transform -> updateDashboardState(transform) },
    )
    private val glassesMediaLibrary by lazy { GlassesMediaLibrary(this) }
    private val mediaSync by lazy {
        GlassesMediaSyncCoordinator(this, mediaSyncHost, transferProgress, glassesMediaLibrary, glassesTeardownScope)
    }
    private val vendorMediaSync by lazy {
        VendorMediaSyncController(this, mediaSyncHost, transferProgress, glassesMediaLibrary)
    }
    private val downloadP2pConnected: Boolean get() = mediaSync.downloadP2pConnected
    private val downloadInProgress: Boolean get() = mediaSync.downloadInProgress
    private val downloadAttemptJob: Job? get() = mediaSync.downloadAttemptJob
    private val eyevueMediaJob: Job? get() = vendorMediaSync.eyevueMediaJob
    private val tuneBudsMediaJob: Job? get() = vendorMediaSync.tuneBudsMediaJob

    private var batteryPollJob: Job? = null
    private val batteryPollIntervalMs = 60_000L
    private var batteryCallbackRegistered: Boolean
        get() = deviceConnection.batteryCallbackRegistered
        set(value) {
            deviceConnection.batteryCallbackRegistered = value
        }
    private var enabledFeaturePermissionRequestActive = false
    private var enabledMetaCameraCheckActive: Boolean
        get() = metaRaybanUi.enabledMetaCameraCheckActive
        set(value) {
            metaRaybanUi.enabledMetaCameraCheckActive = value
        }

    // Glasses dashboard: device managers and controllers live in ui.main.
    private val mainScreenHost: MainScreenHost = object : MainScreenHost {
        override val activity: AppCompatActivity
            get() = this@MainActivity
        override val binding: AcitivytMainBinding
            get() = this@MainActivity.binding
        override var dashboardState: GlassesDashboardUiState
            get() = this@MainActivity.dashboardState
            set(value) {
                this@MainActivity.dashboardState = value
            }

        override fun updateDashboardState(
            transform: (GlassesDashboardUiState) -> GlassesDashboardUiState,
        ) {
            this@MainActivity.updateDashboardState(transform)
        }
    }
    private val deviceConnectionHost: DeviceConnectionHost = object : DeviceConnectionHost {
        override val isAiHijackEnabled: Boolean
            get() = this@MainActivity.isAiHijackEnabled

        override fun handleAiWakeWordActivation(source: String) {
            this@MainActivity.handleAiWakeWordActivation(source)
        }

        override fun startBatteryPolling() {
            this@MainActivity.startBatteryPolling()
        }

        override fun stopBatteryPolling() {
            this@MainActivity.stopBatteryPolling()
        }

        override fun showEyevueLivePlayer(player: org.videolan.libvlc.MediaPlayer?) {
            eyevueLivePlayer = player
        }

        override fun showTuneBudsLivePlayer(player: org.videolan.libvlc.MediaPlayer?) {
            tuneBudsLivePlayer = player
        }

        override fun dismissLegacyLivePreviewDialog() {
            livePreviewDialog?.let { dialog ->
                livePreviewDialog = null
                runCatching { dialog.dismiss() }
            }
        }
    }
    private val deviceManagers: DeviceManagerRegistry by lazy {
        DeviceManagerRegistry(this) { deviceConnection }
    }
    private val glassesDeviceHost: GlassesDeviceHost = object : GlassesDeviceHost {
        override val heyCyanLivePreviewManager: com.fersaiyan.cyanbridge.ota.LivePreviewManager
            get() = this@MainActivity.livePreviewManager

        override fun stopHeyCyanMediaSyncForDestroy() {
            mediaSync.stopForActivityDestroy()
        }

        override fun stopVendorMediaSyncForDestroy(stop: () -> Unit) {
            stop()
            releaseExclusiveGlassesSession(mediaSessionLease)
            mediaSessionLease = null
        }

        override fun disconnectEyevueMediaTransport() {
            vendorMediaSync.disconnectEyevueTransport()
        }
    }
    private val glassesDevices: AndroidGlassesDevices by lazy {
        AndroidGlassesDevices(
            GlassesDeviceContext(
                screen = mainScreenHost,
                managers = deviceManagers,
                actionsProvider = { dashboardActionHost },
                connectionProvider = { deviceConnection },
                heyCyanSettingsProvider = { heyCyanSettings },
                metaRaybanUiProvider = { metaRaybanUi },
                meizuMyvuUiProvider = { meizuMyvuUi },
                hostProvider = { glassesDeviceHost },
                transportPermissionsProvider = { transportPermissions },
            ),
        )
    }
    private val glassesSessionGuard: GlassesSessionGuard by lazy {
        GlassesSessionGuard(this, deviceManagers::selectedProfile, glassesTeardownScope)
    }
    private val transportPermissions: GlassesTransportPermissions by lazy {
        GlassesTransportPermissions(mainScreenHost)
    }
    private val heyCyanSettings: HeyCyanSettingsController by lazy {
        HeyCyanSettingsController(mainScreenHost)
    }
    private val metaRaybanUi: MetaRaybanUiController by lazy {
        MetaRaybanUiController(
            mainScreenHost,
            deviceManagers,
            metaAndroidPermissionLauncher,
            metaWearablePermissionLauncher,
        )
    }
    private val meizuMyvuUi: MeizuMyvuUiController by lazy {
        MeizuMyvuUiController(mainScreenHost, deviceManagers)
    }
    private val deviceConnection: DeviceConnectionCoordinator by lazy {
        DeviceConnectionCoordinator(
            mainScreenHost,
            deviceConnectionHost,
            glassesDevices,
            glassesSessionGuard,
            metaRaybanUi,
            meizuMyvuUi,
        )
    }
    private val eyevueManager: EyevueManager?
        get() = deviceManagers.eyevueManager
    private val tuneBudsManager: TuneBudsManager?
        get() = deviceManagers.tuneBudsManager
    private val moyoungW620Manager: MoyoungW620Manager?
        get() = deviceManagers.moyoungW620Manager
    private val meetingCaptureUi: MeetingCaptureUiController by lazy {
        MeetingCaptureUiController(mainScreenHost) { refreshNativePluginShortcutState() }
    }
    private val agentControlsUi: AgentControlsUiController by lazy {
        AgentControlsUiController(mainScreenHost)
    }
    private val nativePluginShortcuts: NativePluginShortcutController by lazy {
        NativePluginShortcutController(
            mainScreenHost,
            glassesDevices,
            meetingCaptureUi,
            transportPermissions,
        )
    }
    private val dashboardActionHost: DashboardActionHost = object : DashboardActionHost {
        override fun selectPhoneAssistant() {
            this@MainActivity.selectPhoneAssistant()
        }

        override fun selectCustomAiProvider() {
            this@MainActivity.selectCustomAiProvider()
        }

        override fun onImageThumbnailQualitySelected(quality: ImageThumbnailQuality) {
            questions.thumbnailQuality = quality
        }

        override fun startEyevueMediaSync() {
            this@MainActivity.startEyevueMediaSync()
        }

        override fun startTuneBudsMediaSync() {
            this@MainActivity.startTuneBudsMediaSync()
        }

        override fun startMoyoungW620MediaSync() {
            this@MainActivity.startMoyoungW620MediaSync()
        }

        override fun stopEyevueMediaSync() {
            this@MainActivity.stopEyevueMediaSync()
        }

        override fun stopTuneBudsMediaSync() {
            this@MainActivity.stopTuneBudsMediaSync()
        }

        override fun stopMoyoungW620MediaSync() {
            this@MainActivity.stopMoyoungW620MediaSync()
        }

        override fun requestOtaFirmware(source: OtaFirmwareSource) {
            this@MainActivity.requestOtaFirmware(source)
        }

        override fun submitFirmwarePatchRequest(
            request: FirmwarePatchRequestUiState,
            contactEmail: String,
        ) {
            this@MainActivity.submitFirmwarePatchRequest(request, contactEmail)
        }

        override fun cancelOta() {
            otaCoordinator.cancelFromDashboard()
        }

        override fun startEyevueLivePreview() {
            this@MainActivity.startEyevueLivePreview()
        }

        override fun startTuneBudsLivePreview() {
            this@MainActivity.startTuneBudsLivePreview()
        }

        override fun startLivePreview() {
            this@MainActivity.startLivePreview()
        }

        override fun stopLivePreview() {
            this@MainActivity.stopLivePreview()
        }

        override fun releaseLivePreviewSession() {
            releaseExclusiveGlassesSession(livePreviewSessionLease)
            livePreviewSessionLease = null
        }

        override fun startWifiAdbDebug() {
            this@MainActivity.startWifiAdbDebug()
        }

        override fun stopWifiAdbDebug() {
            wifiAdbDebugController.stop()
        }
    }
    private val appDestinationNavigator: AppDestinationNavigator by lazy {
        AppDestinationNavigator(this)
    }
    private val dashboardController: DashboardController by lazy {
        DashboardController(
            mainScreenHost,
            dashboardActionHost,
            glassesDevices,
            transportPermissions,
            nativePluginShortcuts,
            meetingCaptureUi,
            appDestinationNavigator,
        )
    }
    private val legacyControlsHost: LegacyControlsHost = object : LegacyControlsHost {
        override var isAiHijackEnabled: Boolean
            get() = this@MainActivity.isAiHijackEnabled
            set(value) {
                this@MainActivity.isAiHijackEnabled = value
            }
        override var isImageAssistantMode: Boolean
            get() = this@MainActivity.isImageAssistantMode
            set(value) {
                this@MainActivity.isImageAssistantMode = value
            }
        override val deviceNotifyListener: GlassesDeviceNotifyListener
            get() = this@MainActivity.deviceNotifyListener

        override fun controlAudioRecording(start: Boolean) {
            this@MainActivity.controlAudioRecording(start)
        }

        override fun controlVideoRecording(start: Boolean) {
            this@MainActivity.controlVideoRecording(start)
        }

        override fun startVoiceQuestionFromUi() {
            this@MainActivity.startVoiceQuestionFromUi()
        }

        override fun startImageQuestionFromUi() {
            this@MainActivity.startImageQuestionFromUi()
        }

        override fun selectPhoneAssistant() {
            this@MainActivity.selectPhoneAssistant()
        }

        override fun selectCustomAiProvider() {
            this@MainActivity.selectCustomAiProvider()
        }

        override fun refreshAiModeButtons() {
            this@MainActivity.refreshAiModeButtons()
        }

        override fun showOfficialHeyCyanWarningOrContinue() {
            this@MainActivity.showOfficialHeyCyanWarningOrContinue()
        }

        override fun cancelDataDownloadAttempt(reason: String, showToast: Boolean) {
            this@MainActivity.cancelDataDownloadAttempt(reason, showToast)
        }

        override fun dumpOtaServerInfo() {
            this@MainActivity.dumpOtaServerInfo()
        }

        override fun testPullModeOta() {
            this@MainActivity.testPullModeOta()
        }
    }
    private val legacyGlassesControls: LegacyGlassesControlsBinder by lazy {
        LegacyGlassesControlsBinder(
            mainScreenHost,
            legacyControlsHost,
            glassesDevices,
            deviceConnection,
            heyCyanSettings,
            glassesSessionGuard,
            transportPermissions,
            meetingCaptureUi,
        )
    }
    private val taskerCommands: TaskerCommandHandler by lazy {
        TaskerCommandHandler(mainScreenHost, legacyControlsHost)
    }
    private val assistantSetupGate: AssistantSetupGate by lazy {
        AssistantSetupGate(this) { questions.currentRoute() }
    }
    private val assistantModeUi: AssistantModeUiController by lazy {
        AssistantModeUiController(mainScreenHost, questions)
    }
    private val questionHost: GlassesQuestionHost = object : GlassesQuestionHost {
        override val activity: AppCompatActivity
            get() = this@MainActivity
        override val rootView: View
            get() = binding.root
        override val isAiHijackEnabled: Boolean
            get() = this@MainActivity.isAiHijackEnabled

        override fun questionPhoto(): QuestionPhotoSource? = glassesDevices.selected().questionPhoto

        override fun questionPhotoBlocksVoice(): Boolean = glassesDevices.questionPhotoBlocksVoice()

        override fun showAssistantSetupIfNeeded(kind: AssistantTestKind): Boolean =
            this@MainActivity.showAssistantSetupIfNeeded(kind)

        override fun requestHighQualityImageForQuestion(sourceTag: String) =
            this@MainActivity.requestHighQualityImageForQuestion(sourceTag)

        override val highQualityImageRequestActive: Boolean
            get() = mediaSync.highQualityImageRequest != null
    }

    private val metaAndroidPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            metaRaybanUi.onAndroidPermissionsResult(result)
        }

    private val metaWearablePermissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            metaRaybanUi.onWearablePermissionResult(result)
        }

    // Transcription UI moved to the "Transcriptions & recordings" section

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = AcitivytMainBinding.inflate(layoutInflater)
        questions.attachHost(questionHost)
        initView()
        refreshImageThumbnailQuality()
        refreshGeminiLiveImageDelay()
        setupMeetingCaptureUi()
        setupAgentControlsUi()
        setupMetaRaybanUi()
        refreshNativePluginShortcutState()
        val appearancePreferences = AppearancePreferences(this)
        // Hide the view-based bottom navigation; the shared CMP nav shell owns it now.
        binding.bottomNavigation.visibility = View.GONE
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                CyanBridgeApp(
                    dashboardState = dashboardState,
                    onDashboardAction = ::handleDashboardAction,
                    showSyncFlowPicker = showDownloadFlowPicker,
                    onSyncFlowPickerDismiss = { showDownloadFlowPicker = false },
                    onSyncFlowSelected = { flow ->
                        showDownloadFlowPicker = false
                        Log.i("DataDownload", "User selected sync flow: ${flow.label}")
                        startDataDownload(flow)
                    },
                    appearanceSettings = appearance,
                    onNavigateToActivity = ::navigateToDestination,
                    liveVideoSlot = {
                        val live = dashboardState.livePreview
                        val isEyevueLive = dashboardState.showEyevueControls
                        val isHeyCyanLive = dashboardState.showHeyCyanControls
                        val isTuneBudsLive = dashboardState.showTuneBudsControls
                        val livePlayer = when {
                            isEyevueLive -> eyevueLivePlayer
                            isTuneBudsLive -> tuneBudsLivePlayer
                            else -> heyCyanLivePlayer
                        }
                        if ((isEyevueLive || isHeyCyanLive || isTuneBudsLive) &&
                            live.isPlaying &&
                            live.streamUrl != null &&
                            livePlayer != null
                        ) {
                            val liveUrl = live.streamUrl ?: ""
                            androidx.compose.foundation.layout.Column(
                                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                            ) {
                                androidx.compose.foundation.layout.Spacer(
                                    modifier = androidx.compose.ui.Modifier.height(8.dp),
                                )
                                if (isEyevueLive) {
                                    androidx.compose.ui.viewinterop.AndroidView(
                                        factory = { ctx ->
                                            com.fersaiyan.cyanbridge.devices.eyevue.EyevueLiveVideoView(ctx)
                                        },
                                        update = { view -> eyevueLivePreviewManager?.attachVideoView(view) },
                                        onRelease = { view -> eyevueLivePreviewManager?.detachVideoView(view) },
                                        modifier = androidx.compose.ui.Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(0.75f),
                                    )
                                } else if (isTuneBudsLive) {
                                    androidx.compose.ui.viewinterop.AndroidView(
                                        factory = { ctx -> com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLiveVideoView(ctx) },
                                        update = { view -> tuneBudsLivePreviewManager?.attachVideoView(view) },
                                        onRelease = { view -> tuneBudsLivePreviewManager?.detachVideoView(view) },
                                        modifier = androidx.compose.ui.Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                                    )
                                } else {
                                    androidx.compose.ui.viewinterop.AndroidView(
                                        factory = { ctx ->
                                            com.fersaiyan.cyanbridge.devices.heycyan.HeyCyanLiveVideoView(ctx)
                                        },
                                        update = { view -> livePreviewManager.attachVideoView(view) },
                                        onRelease = { view -> livePreviewManager.detachVideoView(view) },
                                        modifier = androidx.compose.ui.Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(4f / 3f),
                                    )
                                }
                                androidx.compose.foundation.layout.Spacer(
                                    modifier = androidx.compose.ui.Modifier.height(8.dp),
                                )
                                androidx.compose.material3.Text(
                                    text = liveUrl,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                androidx.compose.material3.Text(
                                    text = "Shared local RTSP feed — keep Live preview running",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (isEyevueLive) {
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            eyevueLiveAudioMuted = !eyevueLiveAudioMuted
                                            eyevueLivePreviewManager?.setAudioMuted(eyevueLiveAudioMuted)
                                        },
                                    ) {
                                        androidx.compose.material3.Text(
                                            if (eyevueLiveAudioMuted) "Listen to audio" else "Mute audio",
                                        )
                                    }
                                }
                                if (isTuneBudsLive) {
                                    androidx.compose.material3.TextButton(onClick = {
                                        tuneBudsLiveAudioMuted = !tuneBudsLiveAudioMuted
                                        tuneBudsLivePreviewManager?.setAudioMuted(tuneBudsLiveAudioMuted)
                                    }) {
                                        androidx.compose.material3.Text(if (tuneBudsLiveAudioMuted) "Listen to audio" else "Mute audio")
                                    }
                                }
                                androidx.compose.foundation.layout.Row(
                                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                                ) {
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            val clipboard =
                                                getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                    as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(
                                                android.content.ClipData.newPlainText(
                                                    "Glasses live URL",
                                                    liveUrl,
                                                ),
                                            )
                                            android.widget.Toast.makeText(
                                                this@MainActivity,
                                                "Stream URL copied — open it while live preview is running",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                    ) {
                                        androidx.compose.material3.Text("Copy URL")
                                    }
                                    androidx.compose.foundation.layout.Spacer(
                                        modifier = androidx.compose.ui.Modifier.width(8.dp),
                                    )
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            if (isEyevueLive) {
                                                eyevueLiveAudioMuted = true
                                                eyevueLivePreviewManager?.setAudioMuted(true)
                                            }
                                            if (isTuneBudsLive) {
                                                tuneBudsLiveAudioMuted = true
                                                tuneBudsLivePreviewManager?.setAudioMuted(true)
                                            }
                                            val uri = android.net.Uri.parse(liveUrl)
                                            val vlcIntent = android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                uri,
                                            ).setPackage("org.videolan.vlc")
                                            val opened = runCatching {
                                                startActivity(vlcIntent)
                                                true
                                            }.getOrDefault(false)
                                            if (!opened) {
                                                runCatching {
                                                    startActivity(
                                                        android.content.Intent(
                                                            android.content.Intent.ACTION_VIEW,
                                                            uri,
                                                        ),
                                                    )
                                                }.onFailure {
                                                    android.widget.Toast.makeText(
                                                        this@MainActivity,
                                                        "No app found for RTSP",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        },
                                    ) {
                                        androidx.compose.material3.Text("Open in VLC")
                                    }
                                }
                            }
                        }
                    },
                )
                if (showOfficialHeyCyanWarningDialog) {
                    OfficialHeyCyanWarningDialog(
                        onDismissRequest = { showOfficialHeyCyanWarningDialog = false },
                        onOpenAppInfo = {
                            showOfficialHeyCyanWarningDialog = false
                            runCatching { startActivity(OfficialHeyCyanApp.appInfoIntent()) }
                                .onFailure {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Could not open HeyCyan App info.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                        },
                        onSyncAnyway = { suppressFutureWarnings ->
                            showOfficialHeyCyanWarningDialog = false
                            if (suppressFutureWarnings) {
                                OfficialHeyCyanApp.suppressWarning(this@MainActivity)
                            }
                            showDownloadFlowPicker()
                        },
                    )
                }
            }
        }
        observeOtaState()
        observeLivePreviewState()
        observeWifiAdbDebugState()
        // Transcription UI moved to the "Transcriptions & recordings" section
        logLargeDataHandlerMethodsOnce()
        // Check for app updates
        VersionUpdateChecker.checkForUpdates(this)
        // Initialize TTS
        questions.startSpeech(questionHost)

        // Ensure we always listen for HeyCyan reports. Meta notifications come from DAT,
        // so do not register the vendor listener for a selected Meta profile.
        if (!isMetaRaybanSelected() && !isEyevueSelected() && !isTuneBudsSelected()) {
            LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)
        }

        // Lazily register the import/download notify listener the first time we need it.
        handleMetaRegistrationIntent(intent)
        handleTaskerCommand(intent)
        maybeStartMetaImageQuestion(intent)
        maybeHandleDebugGlassesEvent(intent)

        BatteryOptimizationGuideActivity.launchIfNeeded(this)
    }

    override fun onStart() {
        super.onStart()
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
        val bluetoothConnected = BleOperateManager.getInstance().isConnected
        updateConnectionStatus(bluetoothConnected)
        if (!bluetoothConnected &&
            GlassesSessionCoordinator.currentSession() == GlassesSession.WIFI_ADB_DEBUG
        ) {
            GlassesSessionCoordinator.clearForDisconnectedDevice(DeviceClass.HEY_CYAN)
            wifiAdbDebugSessionLease = null
        }
        registerMeetingCaptureReceiver()
        syncMeetingCaptureUiFromPrefs()

        if (!agentReceiverRegistered) {
            LocalBroadcastManager.getInstance(this)
                .registerReceiver(agentStatusReceiver, IntentFilter(LocalAgentIntents.ACTION_STATUS_CHANGED))
            agentReceiverRegistered = true
        }
        if (!imageAutomationStatusReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                imageAutomationStatusReceiver,
                IntentFilter(ExternalImageAutomationIntents.internalStatusAction(packageName)),
            )
            imageAutomationStatusReceiverRegistered = true
        }
        LocalAgentController.requestStatus(this)
        refreshAgentStatusUi()
    }

    override fun onStop() {
        if (BuildConfig.DEBUG) wifiAdbDebugController.stop()
        // Glasses live previews keep running in the background behind their
        // foreground service so other apps can use the relay. It stops only
        // via user Stop (dashboard, notification) or session end.
        super.onStop()
        stopBatteryPolling()
        unregisterMeetingCaptureReceiver()

        if (agentReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(agentStatusReceiver)
            agentReceiverRegistered = false
        }
        if (imageAutomationStatusReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(imageAutomationStatusReceiver)
            imageAutomationStatusReceiverRegistered = false
        }

        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }

    override fun onDestroy() {
        questions.onHostDestroying(questionHost)
        if (BuildConfig.DEBUG) wifiAdbDebugController.release()
        livePreviewDialog?.dismiss()
        livePreviewDialog = null
        livePreviewManager.release()
        eyevueLivePreviewManager?.release()
        tuneBudsLivePreviewManager?.release()
        tuneBudsLiveStopReceiver?.let { runCatching { unregisterReceiver(it) } }
        tuneBudsLiveStopReceiver = null
        eyevueLiveStopReceiver?.let { runCatching { unregisterReceiver(it) } }
        eyevueLiveStopReceiver = null
        livePreviewCoordinator.unregisterHeyCyanLiveStopReceiver()
        com.fersaiyan.cyanbridge.devices.heycyan.HeyCyanLiveForegroundService.stop(this)
        vendorMediaSync.cancelSyncJobsForDestroy(moyoungW620Manager)
        otaCoordinator.onActivityDestroyed()
        if (GlassesSessionCoordinator.currentSession() == GlassesSession.MEDIA_SYNC) {
            glassesDevices.selected().stopMediaSyncForActivityDestroy()
        }
        try {
            LargeDataHandler.getInstance().removeOutDeviceListener(100)
            LargeDataHandler.getInstance().removeBatteryCallBack("init")
            batteryCallbackRegistered = false
        } catch (e: Exception) {
            Log.w("DeviceNotify", "Failed to unregister SDK callbacks", e)
        }
        super.onDestroy()
        questions.detachHost(questionHost)
    }

    override fun onResume() {
        super.onResume()
        glassesDevices.selected().onActivityResumed()
        showPendingMeizuMyvuFailureIfNeeded()
        handleExternalImageAutomationStatus()
        if (
            com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs.isBridgeConfigured(this) &&
            !com.fersaiyan.cyanbridge.studiobridge.StudioBridgeClient.isRunning() &&
            com.fersaiyan.cyanbridge.studiobridge.StudioApprovalHandler.canCaptureVoice(this)
        ) {
            (application as? com.fersaiyan.cyanbridge.ui.MyApplication)?.startStudioBridge()
        }
        try {
                if (!BluetoothUtils.isEnabledBluetooth(this)) {
                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(
                                this,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            startActivityForResult(intent, 300)
                        }
                    } else {
                        startActivityForResult(intent, 300)
                    }
                }
        } catch (e: Exception) {
        }
        if (!hasBluetooth(this)) {
            requestBluetoothPermission(this, BluetoothPermissionCallback())
        }

        // Check for Overlay permission needed for background launch
        if (isAiHijackEnabled && !Settings.canDrawOverlays(this) && !overlayPermissionPromptShown) {
            overlayPermissionPromptShown = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1234)
            Toast.makeText(this, "Please enable Overlay permission for background AI", Toast.LENGTH_LONG).show()
        }

        refreshAiQueryButtonsState()
        refreshGeminiLiveImageDelay()
        refreshNativePluginShortcutState()
        ensureEnabledBackgroundFeaturePermissions()
        ensureEnabledMetaCameraFeature()
    }

    private fun ensureEnabledBackgroundFeaturePermissions() {
        if (enabledFeaturePermissionRequestActive) return

        val voicePluginEnabled =
            com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs.isBridgeConfigured(this) ||
                AutoAudioCapturePrefs.isEnabled(this) ||
                CommunityPluginPrefs.isNativePluginEnabled(this, NativePluginIds.AUTO_AUDIO)

        if (voicePluginEnabled && !PluginVoicePermissions.hasRequiredPermissions(this)) {
            enabledFeaturePermissionRequestActive = true
            PluginVoicePermissions.ensure(
                this,
                onGranted = {
                    enabledFeaturePermissionRequestActive = false
                    restartEnabledBackgroundFeatures()
                },
                onDenied = {
                    enabledFeaturePermissionRequestActive = false
                },
            )
            return
        }

        val notificationFeatureEnabled =
            WalkingAidPreferences.isEnabled(this) ||
                AutoDiaryService.isEnabled(this) ||
                VisualDiaryPreferences.isEnabled(this) ||
                LocalAgentPlugin.isEnabled(this) ||
                glassesDevices.selected().runsForegroundConnectionService
        if (notificationFeatureEnabled && !hasNotificationPermission(this)) {
            enabledFeaturePermissionRequestActive = true
            ensureNotificationPermission(this, "enabled background features") {
                enabledFeaturePermissionRequestActive = false
                restartEnabledBackgroundFeatures()
            }
            return
        }

    }

    private fun restartEnabledBackgroundFeatures() {
        if (AutoAudioCapturePrefs.isEnabled(this)) AutoAudioCaptureService.start(this)
        if (AutoDiaryService.isEnabled(this)) AutoDiaryService.startIfEnabled(this)
        startEnabledCameraFeatures()
        if (LocalAgentPlugin.isEnabled(this)) {
            LocalAgentController.requestStatus(this)
        }
        glassesDevices.selected().onBackgroundFeaturesRestarted()

        // Mic plugins (Live Caption Relay, Hands-Free Translator, Errand Brain) are user-controlled via
        // the glasses-tab shortcut START/STOP and must not auto-start when merely enabled in Plugins.
        // See fix for Meeting Spark Notes (afef09b) - same pattern keeps microphone off until explicit START.
        if (com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs.isBridgeConfigured(this)) {
            (application as? MyApplication)?.startStudioBridge()
        }
    }

    private fun ensureEnabledMetaCameraFeature() {
        if (glassesDevices.selected().cameraPluginGate == null || !hasNotificationPermission(this)) return
        if (!WalkingAidPreferences.isEnabled(this) && !VisualDiaryPreferences.isEnabled(this)) return
        if (enabledMetaCameraCheckActive) return

        enabledMetaCameraCheckActive = true
        ensureMetaCameraReady {
            enabledMetaCameraCheckActive = false
            if (WalkingAidPreferences.isEnabled(this)) WalkingAidService.start(this)
            if (VisualDiaryPreferences.isEnabled(this)) VisualDiaryService.startIfEnabled(this)
        }
    }

    private fun startEnabledCameraFeatures() {
        if (glassesDevices.selected().cameraPluginGate != null) {
            if (WalkingAidPreferences.isEnabled(this) || VisualDiaryPreferences.isEnabled(this)) {
                ensureEnabledMetaCameraFeature()
            }
            return
        }
        if (WalkingAidPreferences.isEnabled(this)) WalkingAidService.start(this)
        if (VisualDiaryPreferences.isEnabled(this)) VisualDiaryService.startIfEnabled(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleMetaRegistrationIntent(intent)) {
            updateMetaRaybanUiState()
        }
        handleTaskerCommand(intent)
        maybeStartMetaImageQuestion(intent)
        maybeHandleDebugGlassesEvent(intent)
    }

    private fun handleMetaRegistrationIntent(callbackIntent: Intent): Boolean {
        if (!callbackIntent.data?.scheme.equals("cyanbridge", ignoreCase = true)) return false
        val manager = getOrCreateMetaRaybanManager()
        return manager.handleRegistrationCallback(callbackIntent)
    }

    /** Debug builds only: lets instrumentation fire the wake word or the AI photo button. */
    private fun maybeHandleDebugGlassesEvent(sourceIntent: Intent) {
        if (!BuildConfig.DEBUG) return
        val event = sourceIntent.getStringExtra(EXTRA_DEBUG_GLASSES_EVENT) ?: return
        sourceIntent.removeExtra(EXTRA_DEBUG_GLASSES_EVENT)
        // The decor view is always attached; posting runs after onNewIntent/onResume settle.
        window.decorView.post { debugGlassesEvent(event) }
    }

    /** Debug builds only: same entry points as the glasses' wake word and AI photo button. */
    internal fun debugGlassesEvent(event: String) {
        if (!BuildConfig.DEBUG) return
        Log.i("AIHijack", "Debug glasses event=$event")
        when (event) {
            DEBUG_EVENT_WAKE -> handleAiWakeWordActivation("debug")
            DEBUG_EVENT_PHOTO_BUTTON -> questions.debugPhotoButton()
        }
    }

    private fun maybeStartMetaImageQuestion(sourceIntent: Intent) {
        if (!sourceIntent.getBooleanExtra(EXTRA_START_META_IMAGE_QUESTION, false)) return
        sourceIntent.removeExtra(EXTRA_START_META_IMAGE_QUESTION)
        binding.root.post(::startImageQuestionFromUi)
    }

    private fun startImageQuestionFromUi() = questions.startImageQuestionFromUi()

    private fun startVoiceQuestionFromUi() = questions.startVoiceQuestionFromUi()

    private fun showAssistantSetupIfNeeded(kind: AssistantTestKind): Boolean =
        assistantSetupGate.showAssistantSetupIfNeeded(kind)

    private fun getOrCreateMetaRaybanManager(): com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager =
        deviceManagers.getOrCreateMetaRaybanManager()

    private fun showPendingMeizuMyvuFailureIfNeeded() {
        meizuMyvuUi.showPendingMeizuMyvuFailureIfNeeded()
    }

    private fun getOrCreateEyevueManager(): EyevueManager = deviceManagers.getOrCreateEyevueManager()

    private fun getOrCreateTuneBudsManager(): TuneBudsManager = deviceManagers.getOrCreateTuneBudsManager()

    private fun getOrCreateTuneBudsLivePreviewManager(): TuneBudsLivePreviewManager =
        deviceManagers.getOrCreateTuneBudsLivePreviewManager()

    private fun getOrCreateMoyoungW620Manager(): MoyoungW620Manager = deviceManagers.getOrCreateMoyoungW620Manager()

    private fun getOrCreateEyevueLivePreviewManager(): EyevueLivePreviewManager =
        deviceManagers.getOrCreateEyevueLivePreviewManager()

    private fun ensureMetaCameraReady(action: () -> Unit) {
        metaRaybanUi.ensureMetaCameraReady(action)
    }

    inner class BluetoothPermissionCallback : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            if (all) {
                AutoPairManager.requestConnect(this@MainActivity, reason = "bluetooth_permission_granted")
            }
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            super.onDenied(permissions, never)
            Toast.makeText(
                this@MainActivity,
                "Bluetooth permission is required to reconnect to glasses",
                Toast.LENGTH_LONG,
            ).show()
            if (never) {
                XXPermissions.startPermissionActivity(this@MainActivity, permissions)
            }
        }

    }

    private fun ensureGlassesTransportPermissions(feature: String, onGranted: () -> Unit) {
        transportPermissions.ensureGlassesTransportPermissions(feature, onGranted)
    }

    private fun updateDashboardState(
        transform: (GlassesDashboardUiState) -> GlassesDashboardUiState,
    ) {
        runOnUiThread {
            dashboardState = transform(dashboardState)
        }
    }

    private fun acquireExclusiveGlassesSession(session: GlassesSession): GlassesSessionLease? =
        glassesSessionGuard.acquireExclusiveGlassesSession(session)

    private fun releaseExclusiveGlassesSession(lease: GlassesSessionLease?) {
        if (lease != null && GlassesSessionCoordinator.release(lease)) {
            if (mediaSessionLease === lease) mediaSessionLease = null
            if (otaSessionLease === lease) otaSessionLease = null
            if (livePreviewSessionLease === lease) livePreviewSessionLease = null
            if (wifiAdbDebugSessionLease === lease) wifiAdbDebugSessionLease = null
            Log.i("GlassesSession", "Released ${lease.session.label} session")
        }
    }

    private fun isGlassesCommandBlocked(source: String): Boolean =
        glassesSessionGuard.isGlassesCommandBlocked(source)

    private fun acquireBackgroundGlassesCommand(source: String): BackgroundGlassesCommandPermit? =
        glassesSessionGuard.acquireBackgroundGlassesCommand(source)

    private fun warnIfBackgroundGlassesCommandTimesOut(
        permit: BackgroundGlassesCommandPermit,
        timeoutMs: Long = GlassesSessionGuard.ONE_SHOT_BLE_COMMAND_TIMEOUT_MS,
    ) {
        glassesSessionGuard.warnIfBackgroundGlassesCommandTimesOut(permit, timeoutMs)
    }

    private fun handleDashboardAction(action: GlassesDashboardAction) {
        dashboardController.handleDashboardAction(action)
    }

    private fun refreshNativePluginShortcutState() {
        nativePluginShortcuts.refreshNativePluginShortcutState()
    }

    private fun navigateToDestination(destination: AppDestination) {
        appDestinationNavigator.navigateToDestination(destination)
    }

    private fun initView() {
        legacyGlassesControls.bind()
    }

    private fun dumpOtaServerInfo() = otaCoordinator.dumpOtaServerInfo()

    private fun testPullModeOta() = otaCoordinator.testPullModeOta()

    private fun observeOtaState() = otaCoordinator.observeOtaState()

    private fun requestOtaFirmware(source: OtaFirmwareSource) = otaCoordinator.requestOtaFirmware(source)

    private fun resetOtaDashboardToIdle() = otaCoordinator.resetOtaDashboardToIdle()

    private fun submitFirmwarePatchRequest(
        request: FirmwarePatchRequestUiState,
        contactEmail: String,
    ) = otaCoordinator.submitFirmwarePatchRequest(request, contactEmail)

    private fun startLivePreview() = livePreviewCoordinator.startLivePreview()

    private fun startEyevueLivePreview() = livePreviewCoordinator.startEyevueLivePreview()

    private fun startTuneBudsLivePreview() = livePreviewCoordinator.startTuneBudsLivePreview()

    private fun observeLivePreviewState() = livePreviewCoordinator.observeLivePreviewState()

    private fun stopLivePreview() = livePreviewCoordinator.stopLivePreview()

    private fun startWifiAdbDebug() = wifiAdbDebugCoordinator.startWifiAdbDebug()

    private fun observeWifiAdbDebugState() = wifiAdbDebugCoordinator.observeWifiAdbDebugState()

    private fun controlVideoRecording(start: Boolean) = recordingControls.controlVideoRecording(start)

    private fun controlAudioRecording(start: Boolean) = recordingControls.controlAudioRecording(start)

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onBluetoothEvent(event: BluetoothEvent) {
        if (isEyevueSelected()) {
            // Oudmon notifications cannot invalidate a native AA15 photo lease.
            updateConnectionStatus(eyevueManager?.isConnected() == true)
            return
        }
        if (isTuneBudsSelected()) {
            // Vendor-AAR BLE events do not own AB Mate's Classic/SPP session.
            // TuneBuds transport loss is monitored by its live connection.
            updateConnectionStatus(tuneBudsManager?.isConnected() == true)
            return
        }
        updateConnectionStatus(event.connect)
        if (event.connect) {
            otaManager.onBluetoothConnected()
            requestBatteryStatus(showToast = false)
        } else {
            val expectedOtaReconnect = otaManager.isAwaitingFreshBleReadiness
            val otaWasActive = otaManager.isActive
            val otaPreparationWasActive = otaPreparationJob?.isActive == true
            otaManager.onBluetoothDisconnected()
            if (!expectedOtaReconnect) {
                otaPreparationJob?.cancel()
                otaPreparationJob = null
                if (!otaWasActive && otaPreparationWasActive) {
                    resetOtaDashboardToIdle()
                }
                abandonDownloadP2pForBluetoothDisconnect()
                livePreviewManager.onBluetoothDisconnected()
                if (BuildConfig.DEBUG) wifiAdbDebugController.onBluetoothDisconnected()
                if (otaWasActive) {
                    Log.i("Ota", "Keeping the OTA lease until transport cleanup finishes after Bluetooth loss")
                } else {
                    releaseExclusiveGlassesSession(otaSessionLease)
                    GlassesSessionCoordinator.clearForDisconnectedDevice(DeviceClass.HEY_CYAN)
                }
                mediaSessionLease = null
                livePreviewSessionLease = null
                wifiAdbDebugSessionLease = null
            } else {
                Log.i("Ota", "Preserving the OTA lease during its intentional BLE readiness reconnect")
            }
            updateBatteryText(null)
        }
    }

    private fun startBatteryPolling() {
        if (batteryPollJob?.isActive == true) {
            return
        }
        batteryPollJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                if (BleOperateManager.getInstance().isConnected) {
                    requestBatteryStatus(showToast = false)
                } else {
                    updateBatteryText(null)
                }
                delay(batteryPollIntervalMs)
            }
        }
    }

    private fun stopBatteryPolling() {
        batteryPollJob?.cancel()
        batteryPollJob = null
    }

    private fun refreshAiQueryButtonsState() = assistantModeUi.refreshAiQueryButtonsState()

    private fun refreshImageThumbnailQuality() = assistantModeUi.refreshImageThumbnailQuality()

    private fun refreshGeminiLiveImageDelay() = assistantModeUi.refreshGeminiLiveImageDelay()

    private fun refreshAiModeButtons() = assistantModeUi.refreshAiModeButtons()

    private fun selectPhoneAssistant() = assistantModeUi.selectPhoneAssistant()

    private fun selectCustomAiProvider() = assistantModeUi.selectCustomAiProvider()

    private fun handleAiWakeWordActivation(source: String) = questions.handleAiWakeWordActivation(source)

    private fun isImageQuestionWorkActive(): Boolean = questions.isImageQuestionWorkActive()

    private fun handleExternalImageAutomationStatus() = questions.external.handleImageAutomationStatus()

    private fun updateConnectionStatus(connected: Boolean) {
        deviceConnection.updateConnectionStatus(connected)
    }

    private fun isMetaRaybanSelected(): Boolean = deviceManagers.isMetaRaybanSelected()

    private fun isEyevueSelected(): Boolean = deviceManagers.isEyevueSelected()

    private fun isTuneBudsSelected(): Boolean = deviceManagers.isTuneBudsSelected()

    private fun isHeyCyanSelected(): Boolean = deviceManagers.isHeyCyanSelected()

    private fun rejectHeyCyanOnlyFeature(feature: String): Boolean =
        deviceConnection.rejectHeyCyanOnlyFeature(feature)

    // --- Chapter 5: Meeting capture pipeline (start/stop, timer, indicator) ---

    private fun setupMeetingCaptureUi() {
        meetingCaptureUi.setupMeetingCaptureUi()
    }

    private fun setupAgentControlsUi() {
        agentControlsUi.setupAgentControlsUi()
    }

    private fun refreshAgentStatusUi() {
        agentControlsUi.refreshAgentStatusUi()
    }

    // --- Meta Ray-Ban UI setup ---

    private fun setupMetaRaybanUi() {
        metaRaybanUi.setupMetaRaybanUi()
    }

    private fun showMetaError(operation: String, message: String) {
        metaRaybanUi.showMetaError(operation, message)
    }

    private fun updateMetaRaybanUiState() {
        metaRaybanUi.updateMetaRaybanUiState()
    }

    private fun registerMeetingCaptureReceiver() {
        meetingCaptureUi.registerMeetingCaptureReceiver()
    }

    private fun unregisterMeetingCaptureReceiver() {
        meetingCaptureUi.unregisterMeetingCaptureReceiver()
    }

    private fun syncMeetingCaptureUiFromPrefs() {
        meetingCaptureUi.syncMeetingCaptureUiFromPrefs()
    }

    // --- end Chapter 5 meeting capture ---

    // --- Transcription UI moved to RecordingsListActivity (per-item) ---



    private fun updateBatteryText(battery: Int?) {
        deviceConnection.updateBatteryText(battery)
    }

    private fun requestBatteryStatus(showToast: Boolean) {
        deviceConnection.requestBatteryStatus(showToast)
    }

    private fun handleBatteryReport(battery: Int, charging: Boolean) {
        deviceConnection.handleBatteryReport(battery, charging)
    }

    private fun handleTaskerCommand(startIntent: Intent?) {
        taskerCommands.handleTaskerCommand(startIntent)
    }

    private fun showDownloadFlowPicker() {
        showDownloadFlowPicker = true
    }

    private fun showOfficialHeyCyanWarningOrContinue() {
        val selectedClass = DeviceProfileStore.selectedClass(this)
        if (!OfficialHeyCyanApp.shouldWarn(this, selectedClass)) {
            showDownloadFlowPicker()
            return
        }
        showOfficialHeyCyanWarningDialog = true
    }

    private val mediaSyncHost = object : GlassesMediaSyncHost, VendorMediaSyncHost {
        override var mediaSessionLease: GlassesSessionLease?
            get() = this@MainActivity.mediaSessionLease
            set(value) {
                this@MainActivity.mediaSessionLease = value
            }
        override val pendingImageCaptureStartedAtMs: Long
            get() = questions.pendingCaptureStartedAtMs
        override var pendingImageQuestionSource: ImageQuestionSource
            get() = questions.pendingImageQuestionSource
            set(value) {
                questions.pendingImageQuestionSource = value
            }

        override fun ensureGlassesTransportPermissions(feature: String, onGranted: () -> Unit) =
            this@MainActivity.ensureGlassesTransportPermissions(feature, onGranted)

        override fun acquireExclusiveGlassesSession(session: GlassesSession): GlassesSessionLease? =
            this@MainActivity.acquireExclusiveGlassesSession(session)

        override fun releaseExclusiveGlassesSession(lease: GlassesSessionLease?) =
            this@MainActivity.releaseExclusiveGlassesSession(lease)

        override fun getOrCreateEyevueManager(): EyevueManager = this@MainActivity.getOrCreateEyevueManager()

        override fun getOrCreateTuneBudsManager(): TuneBudsManager = this@MainActivity.getOrCreateTuneBudsManager()

        override fun getOrCreateMoyoungW620Manager(): MoyoungW620Manager =
            this@MainActivity.getOrCreateMoyoungW620Manager()

        override fun isTuneBudsAiPhotoInProgress(): Boolean = this@MainActivity.isTuneBudsAiPhotoInProgress()

        override fun isImageQuestionWorkActive(): Boolean = this@MainActivity.isImageQuestionWorkActive()

        override fun rejectHeyCyanOnlyFeature(feature: String): Boolean =
            this@MainActivity.rejectHeyCyanOnlyFeature(feature)

        override fun dashboardTransferState(): GlassesTransferUiState = dashboardState.transfer

        override fun isOtaActive(): Boolean = otaManager.isActive

        override fun onImageReadyForQuestion(
            imagePath: String,
            source: ImageQuestionSource,
            transferDurationMs: Long,
        ) = questions.onImageReadyForQuestion(
            imagePath = imagePath,
            source = source,
            transferDurationMs = transferDurationMs,
        )

        override fun cancelParallelAudioQuestion() = questions.cancelParallelAudioQuestion()

        override fun requestImageThumbnailForQuestion(sourceTag: String) =
            questions.requestImageThumbnailForQuestion(sourceTag)

        override fun clearPendingVoiceImageQuestion(sourceTag: String) =
            questions.clearPendingVoiceImageQuestion(sourceTag)

        override fun isDecodableImageFile(file: File): Boolean = questions.isDecodableImageFile(file)
    }

    private fun isTuneBudsAiPhotoInProgress(): Boolean = glassesDevices.questionPhotoInProgress(DeviceClass.TUNEBUDS)

    private fun requestHighQualityImageForQuestion(sourceTag: String) {
        mediaSync.requestHighQualityImageForQuestion(sourceTag)
    }

    private fun startEyevueMediaSync() {
        vendorMediaSync.startEyevueMediaSync()
    }

    private fun stopEyevueMediaSync() {
        vendorMediaSync.stopEyevueMediaSync()
    }

    private fun startTuneBudsMediaSync() {
        vendorMediaSync.startTuneBudsMediaSync()
    }

    private fun stopTuneBudsMediaSync() {
        vendorMediaSync.stopTuneBudsMediaSync()
    }

    private fun startMoyoungW620MediaSync() {
        vendorMediaSync.startMoyoungW620MediaSync()
    }

    private fun stopMoyoungW620MediaSync() {
        vendorMediaSync.stopMoyoungW620MediaSync()
    }

    private fun startDataDownload(mode: GlassesSyncFlow = GlassesSyncFlow.CUSTOM) {
        mediaSync.startDataDownload(mode)
    }

    private fun formatTransferBytes(bytes: Long): String = TransferProgressPresenter.formatTransferBytes(bytes)

    private fun cancelDataDownloadAttempt(reason: String, showToast: Boolean) {
        mediaSync.cancelDataDownloadAttempt(reason, showToast)
    }

    private fun abandonDownloadP2pForBluetoothDisconnect() {
        mediaSync.abandonDownloadP2pForBluetoothDisconnect()
    }

    private fun isOfficialSyncActive(): Boolean = mediaSync.isOfficialSyncActive()

    private fun onDownloadBleIp(ip: String) {
        mediaSync.onDownloadBleIp(ip)
    }

    private fun maybeResetP2pAfterError255(source: String) {
        mediaSync.maybeResetP2pAfterError255(source)
    }

    /**
     * Debug helper: log all methods on LargeDataHandler so we can
     * discover additional SDK capabilities (such as WiFi transfer APIs)
     * without needing decompiled sources.
     */
    private fun logLargeDataHandlerMethodsOnce() {
        if (loggedLargeDataHandlerMethods) return
        loggedLargeDataHandlerMethods = true
        try {
            val clazz = LargeDataHandler.getInstance()::class.java
            val methods = clazz.declaredMethods
            for (m in methods) {
                val params = m.parameterTypes.joinToString(",") { it.simpleName ?: it.name }
                val ret = m.returnType.simpleName ?: m.returnType.name
                Log.i("LDHMethods", "method=${m.name}, params=($params), return=$ret")
            }
        } catch (e: Exception) {
            Log.e("LDHMethods", "Failed to introspect LargeDataHandler methods", e)
        }
    }

    inner class MyDeviceNotifyListener : GlassesDeviceNotifyListener() {

        @RequiresApi(Build.VERSION_CODES.O)
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            Log.i(
                "DeviceNotify",
                "cmdType=$cmdType, loadData=${response.loadData.joinToString(separator = ",") { it.toInt().toString() }}"
            )
            if (otaManager.isActive) {
                Log.d("DeviceNotify", "Skipping general device-notify handling during OTA")
                return
            }
            if (response.loadData.size <= 6) return
            when (response.loadData[6].toInt()) {
                //Glasses battery report
                0x05 -> {
                    //Current battery
                    val battery = response.loadData[7].toInt()
                    //Is it charging
                    val changing = response.loadData[8].toInt()
                    handleBatteryReport(battery, changing == 1)
                }
                //Glasses pass quick recognition / AI Photo
                0x02 -> {
                    if (com.fersaiyan.cyanbridge.glasses.HeyCyanPhotoCapture.isActive()) {
                        Log.i("DeviceNotify", "Photo-ready belongs to the background capture owner")
                        return
                    }
                    if (WalkingAidImageCapture.isAwaitingPhotoReady()) {
                        Log.i("DeviceNotify", "Walking Aid consumed its requested photo-ready notification")
                        return
                    }
                    questions.onHeyCyanPhotoReady(response.loadData)
                }

                //Glasses activate microphone / AI button
                0x03 -> {
                    if (response.loadData.size > 7 && response.loadData[7].toInt() == 1) {
                        Log.i("DeviceNotify", "AI Button Pressed - Hijacking to Phone Assistant")
                        if (isAiHijackEnabled) {
                            handleAiWakeWordActivation("heycyan")
                        } else {
                            //The glasses activate the microphone to start speaking
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Glasses microphone activated (Original Path)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                //ota upgrade
                0x04 -> {
                    try {
                        val download = response.loadData[7].toInt()
                        val soc = response.loadData[8].toInt()
                        val nor = response.loadData[9].toInt()
                        //download firmware download progress soc download progress nor upgrade progress
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                0x0c -> {
                    //The glasses trigger a pause event, voice broadcast
                    if (response.loadData[7].toInt() == 1) {
                        //to do
                    }
                }

                0x0d -> {
                    //Unbind APP event
                    if (response.loadData[7].toInt() == 1) {
                        //to do
                    }
                }
                //Glasses memory low event
                0x0e -> {

                }
                //Translation pause event
                0x10 -> {

                }
                //Glasses volume change event
                0x12 -> {
                    //Music volume
                    //Minimum volume
                    response.loadData[8].toInt()
                    //Maximum volume
                    response.loadData[9].toInt()
                    //Current volume
                    response.loadData[10].toInt()

                    //Incoming call volume
                    //Minimum volume
                    response.loadData[12].toInt()
                    //Maximum volume
                    response.loadData[13].toInt()
                    //Current volume
                    response.loadData[14].toInt()

                    //Glasses system volume
                    //Minimum volume
                    response.loadData[16].toInt()
                    //Maximum volume
                    response.loadData[17].toInt()
                    //Current volume
                    response.loadData[18].toInt()

                    //Current volume mode
                    val mode = response.loadData[19].toInt()

                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Volume changed (mode=$mode)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                }
                // Glasses report WiFi IP for data download
                0x08 -> {
                    if (isOfficialSyncActive()) {
                        Log.i("DeviceNotify", "Skipping main 0x08 handling during official sync flow")
                        return
                    }
                    if (response.loadData.size >= 11) {
                        val ip = "${ByteUtil.byteToInt(response.loadData[7])}." +
                                "${ByteUtil.byteToInt(response.loadData[8])}." +
                                "${ByteUtil.byteToInt(response.loadData[9])}." +
                                "${ByteUtil.byteToInt(response.loadData[10])}"
                        Log.i("DeviceNotify", "BLE reported WiFi IP: $ip")
                        onDownloadBleIp(ip)
                    } else {
                        Log.w(
                            "DeviceNotify",
                            "0x08 notify with too-short payload, size=${response.loadData.size}"
                        )
                    }
                }
                // Glasses report P2P / WiFi error during data download
                0x09 -> {
                    if (isOfficialSyncActive()) {
                        Log.i("DeviceNotify", "Skipping main 0x09 handling during official sync flow")
                        return
                    }
                    val raw = response.loadData.getOrNull(7) ?: 0
                    val errorCode = ByteUtil.byteToInt(raw)
                    Log.e("DeviceNotify", "P2P/WiFi error from device: $errorCode (raw=$raw)")
                    if (errorCode == 255) {
                        // Mirror the official app: ask the glasses/phone P2P
                        // layer to reset, but do NOT treat this as a fatal
                        // error for the whole download flow. The official app
                        // still proceeds to receive an IP and download.
                        maybeResetP2pAfterError255("main")
                    }
                }
            }
        }
    }
}
