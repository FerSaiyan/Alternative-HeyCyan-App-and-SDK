package com.fersaiyan.cyanbridge.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.fersaiyan.cyanbridge.shared.settings.SettingsSection
import com.fersaiyan.cyanbridge.shared.ui.settings.SettingsScreen
import com.fersaiyan.cyanbridge.shared.ui.settings.SettingsScreenActions
import com.fersaiyan.cyanbridge.shared.ui.settings.SettingsUiState
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy

class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun opensAppearanceAndExpandsAiControls() {
        var appearanceOpens = 0
        var expanded by mutableStateOf(emptySet<SettingsSection>())
        val actions = Proxy.newProxyInstance(
            SettingsScreenActions::class.java.classLoader,
            arrayOf(SettingsScreenActions::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "openAppearance" -> {
                    appearanceOpens += 1
                    Unit
                }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SettingsScreenActionsTestDouble"
                else -> null
            }
        } as SettingsScreenActions

        composeRule.setContent {
            CyanBridgeTheme {
                SettingsScreen(
                    state = SettingsUiState(),
                    expandedSections = expanded,
                    onToggleSection = { section ->
                        expanded = if (section in expanded) expanded - section else expanded + section
                    },
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithTag("settings_appearance").performClick()
        composeRule.onNodeWithText("Custom AI provider").performClick()
        composeRule.onNodeWithText("Configure Local Agent planning, phone-control safety, and local models from its Native Plugins card.")
            .assertTextContains("Native Plugins")

        composeRule.runOnIdle {
            assertEquals(1, appearanceOpens)
            assertEquals(setOf(SettingsSection.AI_AUTOMATION), expanded)
        }

        composeRule.onNodeWithText("Memory Privacy").performClick()
        composeRule.onAllNodesWithText("Screen OCR retention (days)").assertCountEquals(0)
        composeRule.onNodeWithText("Data").performClick()
        composeRule.onNodeWithText("Import ChatGPT data").assertExists()
        composeRule.onNodeWithText("Import Claude data").assertExists()
    }
}
