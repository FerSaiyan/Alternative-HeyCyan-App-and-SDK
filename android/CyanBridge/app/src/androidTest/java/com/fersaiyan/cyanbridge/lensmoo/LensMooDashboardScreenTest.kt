package com.fersaiyan.cyanbridge.lensmoo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.LensMooUiState
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.ui.CyanBridgeApp
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LensMooDashboardScreenTest {
    @get:Rule val compose=createComposeRule()

    @Test fun lensMooUsesAllFiveStandardBottomDestinations() {
        var selected:AppDestination?=null
        compose.setContent { CyanBridgeTheme {
            CyanBridgeApp(dashboardState=GlassesDashboardUiState(lensMoo=LensMooUiState()),onNavigateToActivity={selected=it})
        } }
        for(destination in AppDestination.entries) {
            compose.onNodeWithTag("nav_${destination.name.lowercase()}").assertIsDisplayed()
            if(destination!=AppDestination.GLASSES) {
                compose.onNodeWithTag("nav_${destination.name.lowercase()}").performClick()
                compose.runOnIdle { assertEquals(destination,selected) }
            }
        }
    }

    @Test fun foregroundAssistantDisablesManualCaptureWithoutHidingNavigation() {
        compose.setContent { CyanBridgeTheme {
            CyanBridgeApp(dashboardState=GlassesDashboardUiState(lensMoo=LensMooUiState(hasOwnerId=true,assistantRunning=true,assistantReady=true)))
        } }
        compose.onNodeWithTag("glasses_dashboard").performScrollToNode(hasTestTag("lensmoo_capture"))
        compose.onNodeWithTag("lensmoo_capture").assertIsNotEnabled()
        compose.onNodeWithTag("nav_settings").assertIsDisplayed()
        compose.onNodeWithTag("wifi_adb_debug_section").assertDoesNotExist()
    }
}
