package com.fersaiyan.cyanbridge.shared.glasses

import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlassesDashboardPresentationTest {
    @Test
    fun defaultDashboardStateIsSafeBeforeADeviceIsSelected() {
        val state = GlassesDashboardUiState()

        assertEquals("Disconnected", state.connectionLabel)
        assertEquals("Unknown", state.deviceClassLabel)
        assertFalse(state.showHeyCyanControls)
        assertFalse(state.showMetaRaybanControls)
        assertFalse(state.showCaptureSettings)
        assertFalse(state.showAiWakeWordRouting)
        assertFalse(state.showAdvancedControls)
        assertFalse(state.showAdvancedDeviceVolume)
        assertFalse(state.showAdvancedOta)
        assertNull(state.transfer.progress)
        assertFalse(state.wifiAdbDebug.isAvailable)
        assertEquals("Idle", state.wifiAdbDebug.stateLabel)
        assertEquals(emptyList(), state.wifiAdbDebug.relayEndpoints)
        assertFalse(state.wifiAdbDebug.canStop)
        assertEquals(4, state.imageThumbnailQualitySdkValue)
        assertEquals("Clearer", state.imageThumbnailQualityLabel)
        assertFalse(state.showGeminiLiveImageDelay)
        assertEquals(0, state.geminiLiveImageDelaySeconds)
        assertNull(state.wearingDetectionEnabled)
        assertEquals(emptyList(), state.videoRecordingDurationOptionsSeconds)
        assertEquals(emptyList(), state.audioRecordingDurationOptionsSeconds)
    }

    @Test
    fun navigationActionKeepsTheTypedDestination() {
        val action = GlassesDashboardAction.Navigate(AppDestination.MEDIA)

        assertEquals(AppDestination.MEDIA, action.destination)
    }

    @Test
    fun imageThumbnailQualityActionKeepsTheVendorValue() {
        val action = GlassesDashboardAction.SelectImageThumbnailQuality(4)

        assertEquals(4, action.sdkValue)
    }

    @Test
    fun geminiLiveImageDelayActionKeepsTheSelectedCadence() {
        val action = GlassesDashboardAction.SetGeminiLiveImageDelay(10)

        assertEquals(10, action.seconds)
    }

    @Test
    fun wakeWordRouteIsDashboardOwnedAndDefaultsToVoice() {
        assertEquals(AiWakeWordRoute.VOICE_QUESTION, GlassesDashboardUiState().aiWakeWordRoute)
        assertEquals(
            AiWakeWordRoute.IMAGE_QUESTION,
            GlassesDashboardAction.SetAiWakeWordRoute(AiWakeWordRoute.IMAGE_QUESTION).route,
        )
        assertEquals(AiWakeWordRoute.VOICE_QUESTION, AiWakeWordRoute.fromRaw("unsupported"))
    }

    @Test
    fun captureSettingsActionsKeepTheirDeviceValues() {
        assertEquals(
            true,
            GlassesDashboardAction.SetWearingDetection(true).enabled,
        )
        assertEquals(
            180,
            GlassesDashboardAction.SetVideoRecordingDuration(180).seconds,
        )
        assertEquals(
            3600,
            GlassesDashboardAction.SetAudioRecordingDuration(3600).seconds,
        )
    }

    @Test
    fun syncFlowLabelsKeepTheExistingProtocolChoicesDistinct() {
        assertEquals("HeyCyan app flow", GlassesSyncFlow.OFFICIAL_HEYCYAN.label)
        assertEquals("Custom flow", GlassesSyncFlow.CUSTOM.label)
    }

    @Test
    fun metaDisplayControlsRequireReportedDisplayCapability() {
        val cameraOnly = MetaRaybanUiState()
        val displayDevice = MetaRaybanUiState(displayCapable = true)

        assertFalse(cameraOnly.displayCapable)
        assertFalse(cameraOnly.displayActive)
        assertTrue(displayDevice.displayCapable)
    }
}
