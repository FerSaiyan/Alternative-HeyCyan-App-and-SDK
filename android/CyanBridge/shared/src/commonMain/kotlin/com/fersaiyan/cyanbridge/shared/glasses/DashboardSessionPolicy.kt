package com.fersaiyan.cyanbridge.shared.glasses

/** Decides which dashboard actions may run while an exclusive glasses session is active. */
object DashboardSessionPolicy {
    /** Actions that only touch app state and never contend for the glasses connection. */
    fun ignoresExclusiveSession(action: GlassesDashboardAction): Boolean =
        action is GlassesDashboardAction.SubmitFirmwarePatchRequest ||
            action is GlassesDashboardAction.SelectImageThumbnailQuality ||
            action is GlassesDashboardAction.SetGeminiLiveImageDelay ||
            action is GlassesDashboardAction.SetAiWakeWordRoute ||
            action == GlassesDashboardAction.DismissFirmwarePatchRequest ||
            action == GlassesDashboardAction.MetaSendDiagnostics

    fun isAllowedDuring(action: GlassesDashboardAction, activeSession: GlassesSession): Boolean =
        if (activeSession == GlassesSession.WIFI_ADB_DEBUG) {
            action == GlassesDashboardAction.StopWifiAdbDebug
        } else if (activeSession == GlassesSession.META_CAMERA) {
            when (action) {
                is GlassesDashboardAction.Navigate,
                GlassesDashboardAction.StartMeetingCapture,
                GlassesDashboardAction.StopMeetingCapture,
                is GlassesDashboardAction.RunNativePluginShortcut,
                is GlassesDashboardAction.SelectAssistantMode,
                GlassesDashboardAction.TestVoiceQuestion,
                GlassesDashboardAction.TestImageQuestion,
                GlassesDashboardAction.OpenExternalImageAutomationDiagnostics,
                GlassesDashboardAction.StartAgent,
                GlassesDashboardAction.StopAgent,
                GlassesDashboardAction.RunAgentDemo,
                GlassesDashboardAction.MetaStopSession,
                GlassesDashboardAction.MetaStopStream,
                GlassesDashboardAction.MetaStopDisplay,
                GlassesDashboardAction.MetaCapturePhoto,
                GlassesDashboardAction.MetaViewPhoto,
                GlassesDashboardAction.MetaStartSession,
                GlassesDashboardAction.MetaStartStream,
                GlassesDashboardAction.MetaStartDisplay,
                GlassesDashboardAction.MetaSendDiagnostics -> true
                else -> false
            }
        } else {
            when (action) {
                is GlassesDashboardAction.Navigate -> true
                GlassesDashboardAction.StopSync -> activeSession == GlassesSession.MEDIA_SYNC
                GlassesDashboardAction.StopLivePreview -> activeSession == GlassesSession.LIVE_PREVIEW
                GlassesDashboardAction.CancelOta -> activeSession == GlassesSession.OTA
                // Returns to the running SDK firmware update screen.
                GlassesDashboardAction.OpenSdkFirmwareUpdate -> activeSession == GlassesSession.OTA
                else -> false
            }
        }
}
