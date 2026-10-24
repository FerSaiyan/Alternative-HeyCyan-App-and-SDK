package com.fersaiyan.cyanbridge.shared.glasses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
    fun wakeWordRouteIsDashboardOwnedAndDefaultsToVoice() {
        assertEquals(AiWakeWordRoute.VOICE_QUESTION, GlassesDashboardUiState().aiWakeWordRoute)
        assertEquals(
            AiWakeWordRoute.IMAGE_QUESTION,
            GlassesDashboardAction.SetAiWakeWordRoute(AiWakeWordRoute.IMAGE_QUESTION).route,
        )
        assertEquals(AiWakeWordRoute.VOICE_QUESTION, AiWakeWordRoute.fromRaw("unsupported"))
    }
}
