package com.fersaiyan.cyanbridge.ui.main.glasses

import com.fersaiyan.cyanbridge.ai.questions.QuestionPhotoSource
import com.fersaiyan.cyanbridge.bridge.core.CameraCapture
import com.fersaiyan.cyanbridge.bridge.core.MediaSyncTransport
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.ota.LivePreviewManager
import com.fersaiyan.cyanbridge.ota.LivePreviewState
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDevice
import com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState
import com.fersaiyan.cyanbridge.ui.main.DashboardActionHost
import com.fersaiyan.cyanbridge.ui.main.DeviceConnectionCoordinator
import com.fersaiyan.cyanbridge.ui.main.DeviceManagerRegistry
import com.fersaiyan.cyanbridge.ui.main.GlassesTransportPermissions
import com.fersaiyan.cyanbridge.ui.main.HeyCyanSettingsController
import com.fersaiyan.cyanbridge.ui.main.MainScreenHost
import com.fersaiyan.cyanbridge.ui.main.MetaRaybanUiController
import com.fersaiyan.cyanbridge.ui.main.MeizuMyvuUiController

/**
 * Android side of a glasses family: the shared [GlassesDevice] capabilities plus the hooks the
 * main screen needs around them (status mirroring, selection, activity lifecycle).
 */
internal interface AndroidGlassesDevice : GlassesDevice {
    /** Mirrors the family's connection, battery and storage into the status views and dashboard. */
    fun renderConnectionStatus(connected: Boolean)

    /** Prepares the family's managers after the dashboard gating applied this profile. */
    fun onSelectedForDashboard() {}

    /** The family's live preview state for the dashboard, or null without a live preview. */
    fun dashboardLivePreview(): LivePreviewUiState? = null

    /** What the hidden legacy camera control does instead of the HeyCyan SDK command. */
    val legacyCameraButton: CameraCapture? get() = null

    /** What the hidden legacy Wi-Fi download control starts instead of the HeyCyan SDK sync. */
    val legacyDataDownload: MediaSyncTransport? get() = null

    /** True when the family keeps a foreground connection service that posts notifications. */
    val runsForegroundConnectionService: Boolean get() = false

    /** Called from the main screen's onResume. */
    fun onActivityResumed() {}

    /** Called when enabled background features restart after their permissions were granted. */
    fun onBackgroundFeaturesRestarted() {}

    /** Stops this family's media sync while the activity is destroyed. */
    fun stopMediaSyncForActivityDestroy()

    /**
     * Runs [start] once the family's own camera session is ready, for camera plugins; null
     * when camera plugins start directly.
     */
    val cameraPluginGate: CameraPluginGate? get() = null

    /**
     * The family's own photo transport for image questions; null for families on the HeyCyan SDK
     * path (vision mode and shutter, then the photo-ready notification).
     */
    val questionPhoto: QuestionPhotoSource? get() = null
}

internal fun interface CameraPluginGate {
    fun runWhenCameraReady(start: () -> Unit)
}

/** Activity work and state the glasses families reach. */
internal interface GlassesDeviceHost {
    val heyCyanLivePreviewManager: LivePreviewManager

    /** Stops the HeyCyan SDK Wi-Fi media sync for activity destruction. */
    fun stopHeyCyanMediaSyncForDestroy()

    /** Stops a vendor media sync and releases the media session lease. */
    fun stopVendorMediaSyncForDestroy(stop: () -> Unit)
    fun disconnectEyevueMediaTransport()
}

/** Everything a family implementation needs from the main screen. */
internal class GlassesDeviceContext(
    val screen: MainScreenHost,
    val managers: DeviceManagerRegistry,
    private val actionsProvider: () -> DashboardActionHost,
    private val connectionProvider: () -> DeviceConnectionCoordinator,
    private val heyCyanSettingsProvider: () -> HeyCyanSettingsController,
    private val metaRaybanUiProvider: () -> MetaRaybanUiController,
    private val meizuMyvuUiProvider: () -> MeizuMyvuUiController,
    private val hostProvider: () -> GlassesDeviceHost,
    private val transportPermissionsProvider: () -> GlassesTransportPermissions,
) {
    val activity get() = screen.activity
    val binding get() = screen.binding
    val actions: DashboardActionHost get() = actionsProvider()
    val connection: DeviceConnectionCoordinator get() = connectionProvider()
    val heyCyanSettings: HeyCyanSettingsController get() = heyCyanSettingsProvider()
    val metaRaybanUi: MetaRaybanUiController get() = metaRaybanUiProvider()
    val meizuMyvuUi: MeizuMyvuUiController get() = meizuMyvuUiProvider()
    val host: GlassesDeviceHost get() = hostProvider()
    val transportPermissions: GlassesTransportPermissions get() = transportPermissionsProvider()

    fun livePreviewUiState(state: LivePreviewState?, isAvailable: Boolean): LivePreviewUiState {
        val live = state ?: LivePreviewState()
        return LivePreviewUiState(
            isAvailable = isAvailable, stateLabel = live.stateLabel, detail = live.detail,
            isScanning = live.isScanning, isPlaying = live.isPlaying, streamUrl = live.streamUrl,
            canStart = live.canStart, canStop = live.canStop,
        )
    }
}

/**
 * The one table that maps a [DeviceClass] to its Android implementation. Adding a glasses
 * family means adding its profile in `:shared` and one entry here.
 */
internal class AndroidGlassesDevices(context: GlassesDeviceContext) {
    private val devices: Map<DeviceClass, Lazy<AndroidGlassesDevice>> = mapOf(
        DeviceClass.HEY_CYAN to lazy { HeyCyanGlasses(context) },
        DeviceClass.EYEVUE to lazy { EyevueGlasses(context) },
        DeviceClass.TUNEBUDS to lazy { TuneBudsGlasses(context) },
        DeviceClass.MOYOUNG_W620 to lazy { MoyoungW620Glasses(context) },
        DeviceClass.META_RAYBAN to lazy { MetaRaybanGlasses(context) },
        DeviceClass.MEIZU_MYVU to lazy { MeizuMyvuGlasses(context) },
        DeviceClass.MEMOMIND to lazy { MemoMindGlasses(context) },
        DeviceClass.MENTRA_LIVE to lazy { MentraLiveGlasses(context) },
        DeviceClass.EVEN_G1 to lazy { MentraDisplayGlasses(context, GlassesFamilyProfiles.evenG1) },
        DeviceClass.EVEN_G2 to lazy { MentraDisplayGlasses(context, GlassesFamilyProfiles.evenG2) },
        DeviceClass.MENTRA_DISPLAY to lazy { MentraDisplayGlasses(context, GlassesFamilyProfiles.mentraDisplay) },
        DeviceClass.NIMO to lazy { MentraDisplayGlasses(context, GlassesFamilyProfiles.nimo) },
        DeviceClass.XINGYI_AR99 to lazy { MentraDisplayGlasses(context, GlassesFamilyProfiles.xingyiAr99) },
        DeviceClass.GENERIC_AUDIO to lazy { LegacySdkGlasses(GlassesFamilyProfiles.genericAudio, context) },
        DeviceClass.UNKNOWN to lazy { LegacySdkGlasses(GlassesFamilyProfiles.unknown, context) },
    )

    private val activity = context.activity
    private val managers = context.managers

    init {
        check(devices.keys == DeviceClass.entries.toSet()) { "Every device class needs a glasses implementation" }
    }

    /** The implementation of the currently selected device profile. */
    fun selected(): AndroidGlassesDevice = devices.getValue(DeviceProfileStore.selectedClass(activity)).value

    fun selectedProfile(): GlassesFamilyProfile = managers.selectedProfile()

    /** A family photo for a question that also refuses voice questions is being taken (any family). */
    fun questionPhotoBlocksVoice(): Boolean = devices.values.any { device ->
        device.isInitialized() && device.value.questionPhoto?.let { it.blocksVoiceQuestions && it.inProgress } == true
    }

    /** [deviceClass]'s question photo is being taken. */
    fun questionPhotoInProgress(deviceClass: DeviceClass): Boolean =
        devices.getValue(deviceClass).let { it.isInitialized() && it.value.questionPhoto?.inProgress == true }
}
