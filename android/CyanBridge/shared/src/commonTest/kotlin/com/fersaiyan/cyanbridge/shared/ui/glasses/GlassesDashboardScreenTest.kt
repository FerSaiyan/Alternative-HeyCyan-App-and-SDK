package com.fersaiyan.cyanbridge.shared.ui.glasses

import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import com.fersaiyan.cyanbridge.shared.glasses.GlassesTransferUiState
import com.fersaiyan.cyanbridge.shared.glasses.MetaRaybanUiState
import com.fersaiyan.cyanbridge.shared.glasses.OtaSectionUiState
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GlassesDashboardScreenTest {

    @Test
    fun defaultStateShowsDisconnected() {
        val state = GlassesDashboardUiState()
        assertEquals("Disconnected", state.connectionLabel)
        assertEquals("Unknown", state.deviceClassLabel)
        assertFalse(state.showHeyCyanControls)
        assertFalse(state.showMetaRaybanControls)
        assertNull(state.transfer.progress)
    }

    @Test
    fun connectedStatePreservesLabels() {
        val state = GlassesDashboardUiState(
            connectionLabel = "Connected: CyanBridge V2",
            deviceClassLabel = "HeyCyan Smart Glasses",
            deviceInfoLabel = "Firmware: 0.1.0.6",
        )
        assertEquals("Connected: CyanBridge V2", state.connectionLabel)
        assertEquals("HeyCyan Smart Glasses", state.deviceClassLabel)
        assertEquals("Firmware: 0.1.0.6", state.deviceInfoLabel)
    }

    @Test
    fun transferProgressReflectsSyncState() {
        val state = GlassesDashboardUiState(
            transfer = GlassesTransferUiState(progress = 0.42f),
        )
        assertEquals(0.42f, state.transfer.progress)
    }

    @Test
    fun otaStateReflectsIdleByDefault() {
        val state = GlassesDashboardUiState()
        assertNull(state.ota.progress)
    }

    @Test
    fun navigateActionPreservesTypedDestination() {
        val action = GlassesDashboardAction.Navigate(AppDestination.MEDIA)
        assertEquals(AppDestination.MEDIA, action.destination)
    }

    @Test
    fun syncFlowLabelsDistinguishProtocols() {
        assertEquals("HeyCyan app flow", GlassesSyncFlow.OFFICIAL_HEYCYAN.label)
        assertEquals("Custom flow", GlassesSyncFlow.CUSTOM.label)
    }

    @Test
    fun assistantModesExposeOnlyPhoneAndCustomAi() {
        val modes = GlassesAssistantMode.entries
        assertEquals(
            listOf(GlassesAssistantMode.PHONE_ASSISTANT, GlassesAssistantMode.CUSTOM_AI_PROVIDER),
            modes,
        )
    }
}
