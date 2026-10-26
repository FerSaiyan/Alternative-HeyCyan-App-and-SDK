package com.fersaiyan.cyanbridge.shared.devices

import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction

object GlassesManagerGating {

    enum class Action {
        MEETING_CAPTURE,
        STATUS_BATTERY,
        STATUS_STORAGE,
        HEY_CYAN_EXTRAS,
        META_RAYBAN_CONTROLS,
        META_RAYBAN_REGISTRATION,
        MEIZU_MYVU_CONTROLS,
        MEMOMIND_CONTROLS,
        EYEVUE_CONTROLS,
        TUNEBUDS_CONTROLS,
        CAPTURE_SETTINGS,
        AI_WAKE_WORD_ROUTING,
        ADVANCED_CONTROLS,
        ADVANCED_LOCAL_AGENT,
        ADVANCED_DEVICE_INFO,
        ADVANCED_DEVICE_VOLUME,
        ADVANCED_IMAGE_QUALITY,
        ADVANCED_DEVELOPER_TOOLS,
        ADVANCED_OTA,
        ADVANCED_SDK_FIRMWARE_UPDATE,
        WIFI_ADB_DEBUG,
    }

    data class UiModel(
        val visibleActions: Set<Action>,
    ) {
        fun isVisible(action: Action): Boolean = visibleActions.contains(action)
    }

    fun uiModel(profile: DeviceProfile?): UiModel = UiModel(visibleActions(profile))

    fun visibleActions(profile: DeviceProfile?): Set<Action> {
        val selected = profile?.selectedClass ?: DeviceClass.UNKNOWN
        return visibleActions(selected)
    }

    fun visibleActions(deviceClass: DeviceClass): Set<Action> =
        visibleActions(GlassesFamilyProfiles.forClass(deviceClass))

    /** Derives the dashboard sections from the family's declared capabilities and panel. */
    fun visibleActions(family: GlassesFamilyProfile): Set<Action> {
        val visible = linkedSetOf(Action.MEETING_CAPTURE)
        when (family.controlPanel) {
            GlassesControlPanel.HEY_CYAN -> visible.add(Action.HEY_CYAN_EXTRAS)
            GlassesControlPanel.EYEVUE -> visible.add(Action.EYEVUE_CONTROLS)
            GlassesControlPanel.TUNEBUDS -> visible.add(Action.TUNEBUDS_CONTROLS)
            GlassesControlPanel.META_RAYBAN -> {
                visible.add(Action.META_RAYBAN_CONTROLS)
                visible.add(Action.META_RAYBAN_REGISTRATION)
            }
            GlassesControlPanel.MEIZU_MYVU -> visible.add(Action.MEIZU_MYVU_CONTROLS)
            GlassesControlPanel.MEMOMIND -> visible.add(Action.MEMOMIND_CONTROLS)
            GlassesControlPanel.NONE -> Unit
        }
        capabilityActions.forEach { (capability, action) ->
            if (family.supports(capability)) visible.add(action)
        }
        if (advancedActions.any { it in visible }) visible.add(Action.ADVANCED_CONTROLS)
        return visible
    }

    private val capabilityActions: List<Pair<GlassesCapability, Action>> = listOf(
        GlassesCapability.BATTERY_STATUS to Action.STATUS_BATTERY,
        GlassesCapability.STORAGE_STATUS to Action.STATUS_STORAGE,
        GlassesCapability.DEVICE_INFO to Action.ADVANCED_DEVICE_INFO,
        GlassesCapability.VOLUME_STATUS to Action.ADVANCED_DEVICE_VOLUME,
        GlassesCapability.IMAGE_QUALITY_SELECTION to Action.ADVANCED_IMAGE_QUALITY,
        GlassesCapability.DEVELOPER_TOOLS to Action.ADVANCED_DEVELOPER_TOOLS,
        GlassesCapability.FIRMWARE_UPDATE to Action.ADVANCED_OTA,
        GlassesCapability.SDK_FIRMWARE_UPDATE to Action.ADVANCED_SDK_FIRMWARE_UPDATE,
        GlassesCapability.RECORDING_SETTINGS to Action.CAPTURE_SETTINGS,
        GlassesCapability.WAKE_WORD to Action.AI_WAKE_WORD_ROUTING,
        GlassesCapability.WIFI_ADB_DEBUG to Action.WIFI_ADB_DEBUG,
    )

    /** Sections inside the collapsible Advanced card; the card shows when any of them does. */
    private val advancedActions = setOf(
        Action.ADVANCED_LOCAL_AGENT,
        Action.ADVANCED_DEVICE_INFO,
        Action.ADVANCED_DEVICE_VOLUME,
        Action.ADVANCED_IMAGE_QUALITY,
        Action.ADVANCED_DEVELOPER_TOOLS,
        Action.ADVANCED_OTA,
        Action.ADVANCED_SDK_FIRMWARE_UPDATE,
    )

    /**
     * Families that limit their dashboard to declared capabilities drop every action whose
     * capability or vendor panel they lack; other families accept every action and rely on the
     * dashboard gating to hide what they do not support.
     */
    fun isActionSupported(family: GlassesFamilyProfile, action: GlassesDashboardAction): Boolean {
        if (!family.dashboardActionsLimitedToCapabilities) return true
        val capability = requiredCapability(action)
        if (capability != null && !family.supports(capability)) return false
        val panel = requiredPanel(action)
        return panel == null || panel == family.controlPanel
    }

    /** The capability an action exercises, or null for actions that only touch app state. */
    fun requiredCapability(action: GlassesDashboardAction): GlassesCapability? = when (action) {
        GlassesDashboardAction.CapturePhoto -> GlassesCapability.PHOTO_CAPTURE
        GlassesDashboardAction.ToggleVideo -> GlassesCapability.VIDEO_RECORDING
        GlassesDashboardAction.StartAudioRecording -> GlassesCapability.ONBOARD_AUDIO_RECORDING
        GlassesDashboardAction.RequestMediaCount -> GlassesCapability.MEDIA_COUNT
        GlassesDashboardAction.StartSync,
        GlassesDashboardAction.StopSync -> GlassesCapability.MEDIA_SYNC
        GlassesDashboardAction.StartLivePreview,
        GlassesDashboardAction.StopLivePreview -> GlassesCapability.LIVE_PREVIEW
        GlassesDashboardAction.RequestBattery -> GlassesCapability.BATTERY_STATUS
        GlassesDashboardAction.RequestVersion -> GlassesCapability.DEVICE_INFO
        GlassesDashboardAction.SyncTime -> GlassesCapability.TIME_SYNC
        GlassesDashboardAction.RequestVolume -> GlassesCapability.VOLUME_STATUS
        GlassesDashboardAction.OpenSdkFirmwareUpdate -> GlassesCapability.SDK_FIRMWARE_UPDATE
        is GlassesDashboardAction.SelectImageThumbnailQuality -> GlassesCapability.IMAGE_QUALITY_SELECTION
        is GlassesDashboardAction.SetAiWakeWordRoute -> GlassesCapability.WAKE_WORD
        GlassesDashboardAction.RefreshRecordingSettings,
        is GlassesDashboardAction.SetVideoRecordingDuration,
        is GlassesDashboardAction.SetAudioRecordingDuration -> GlassesCapability.RECORDING_SETTINGS
        is GlassesDashboardAction.SetWearingDetection -> GlassesCapability.WEARING_DETECTION
        GlassesDashboardAction.AddDeviceListener,
        GlassesDashboardAction.StartClassicBluetoothScan,
        GlassesDashboardAction.DumpOtaInfo,
        GlassesDashboardAction.TestPullOta -> GlassesCapability.DEVELOPER_TOOLS
        is GlassesDashboardAction.RequestOtaFirmware,
        is GlassesDashboardAction.SubmitFirmwarePatchRequest,
        GlassesDashboardAction.DismissFirmwarePatchRequest,
        GlassesDashboardAction.CancelOta -> GlassesCapability.FIRMWARE_UPDATE
        GlassesDashboardAction.RequestStartWifiAdbDebug,
        GlassesDashboardAction.StopWifiAdbDebug -> GlassesCapability.WIFI_ADB_DEBUG
        is GlassesDashboardAction.Navigate,
        GlassesDashboardAction.Scan,
        GlassesDashboardAction.Reconnect,
        GlassesDashboardAction.Disconnect,
        is GlassesDashboardAction.SelectMeetingTimer,
        GlassesDashboardAction.StartMeetingCapture,
        GlassesDashboardAction.StopMeetingCapture,
        is GlassesDashboardAction.RunNativePluginShortcut,
        is GlassesDashboardAction.SelectAssistantMode,
        is GlassesDashboardAction.SetGeminiLiveImageDelay,
        GlassesDashboardAction.TestVoiceQuestion,
        GlassesDashboardAction.TestImageQuestion,
        GlassesDashboardAction.OpenExternalImageAutomationDiagnostics,
        GlassesDashboardAction.ToggleAdvanced,
        GlassesDashboardAction.StartAgent,
        GlassesDashboardAction.StopAgent,
        GlassesDashboardAction.RunAgentDemo,
        GlassesDashboardAction.DismissTransportPermissionDialog,
        GlassesDashboardAction.RequestTransportPermission,
        GlassesDashboardAction.MetaRegister,
        GlassesDashboardAction.MetaOpenPairing,
        GlassesDashboardAction.MetaOpenMetaAi,
        GlassesDashboardAction.MetaUnregister,
        GlassesDashboardAction.MetaStartSession,
        GlassesDashboardAction.MetaStopSession,
        GlassesDashboardAction.MetaStartStream,
        GlassesDashboardAction.MetaStopStream,
        GlassesDashboardAction.MetaCapturePhoto,
        GlassesDashboardAction.MetaViewPhoto,
        GlassesDashboardAction.MetaStartDisplay,
        GlassesDashboardAction.MetaStopDisplay,
        GlassesDashboardAction.MetaSendDiagnostics,
        GlassesDashboardAction.MeizuConnect,
        GlassesDashboardAction.MeizuDisconnect,
        GlassesDashboardAction.MeizuSendTestNotification,
        GlassesDashboardAction.MeizuShowTestTeleprompter,
        GlassesDashboardAction.MeizuSyncClock,
        GlassesDashboardAction.MeizuSetComfortBrightness,
        is GlassesDashboardAction.MemoMindPanel -> null
    }

    /** The vendor panel that emits an action, or null for actions of the shared sections. */
    fun requiredPanel(action: GlassesDashboardAction): GlassesControlPanel? = when (action) {
        GlassesDashboardAction.MetaRegister,
        GlassesDashboardAction.MetaOpenPairing,
        GlassesDashboardAction.MetaOpenMetaAi,
        GlassesDashboardAction.MetaUnregister,
        GlassesDashboardAction.MetaStartSession,
        GlassesDashboardAction.MetaStopSession,
        GlassesDashboardAction.MetaStartStream,
        GlassesDashboardAction.MetaStopStream,
        GlassesDashboardAction.MetaCapturePhoto,
        GlassesDashboardAction.MetaViewPhoto,
        GlassesDashboardAction.MetaStartDisplay,
        GlassesDashboardAction.MetaStopDisplay,
        GlassesDashboardAction.MetaSendDiagnostics -> GlassesControlPanel.META_RAYBAN
        GlassesDashboardAction.MeizuConnect,
        GlassesDashboardAction.MeizuDisconnect,
        GlassesDashboardAction.MeizuSendTestNotification,
        GlassesDashboardAction.MeizuShowTestTeleprompter,
        GlassesDashboardAction.MeizuSyncClock,
        GlassesDashboardAction.MeizuSetComfortBrightness -> GlassesControlPanel.MEIZU_MYVU
        is GlassesDashboardAction.MemoMindPanel -> GlassesControlPanel.MEMOMIND
        else -> null
    }
}
