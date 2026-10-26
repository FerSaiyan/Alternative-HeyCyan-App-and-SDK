package com.fersaiyan.cyanbridge.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.shared.appearance.AppearanceSettings
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionAction
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionUiState
import com.fersaiyan.cyanbridge.shared.billing.unavailableProSubscriptionStatus
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.ui.appearance.AppearanceScreen
import com.fersaiyan.cyanbridge.shared.ui.glasses.GlassesDashboardScreen
import com.fersaiyan.cyanbridge.shared.ui.glasses.GlassesSyncFlowPickerDialog

/**
 * Root composable for the CyanBridge app.
 *
 * Renders the shared bottom navigation shell and routes to the appropriate
 * screen for each [AppDestination].
 *
 * The iOS KMP host enables [useSharedDestinations] to render the shared
 * destinations here. Android's single Activity uses this for the Glasses tab
 * only: its other tabs are screens of its back stack, opened through
 * [onOpenDestination].
 */
@Composable
fun CyanBridgeApp(
    initialDestination: AppDestination = AppDestination.GLASSES,
    dashboardState: GlassesDashboardUiState = GlassesDashboardUiState(),
    onDashboardAction: (GlassesDashboardAction) -> Unit = {},
    showSyncFlowPicker: Boolean = false,
    onSyncFlowPickerDismiss: () -> Unit = {},
    onSyncFlowSelected: (GlassesSyncFlow) -> Unit = {},
    appearanceSettings: AppearanceSettings = AppearanceSettings(),
    onAppearanceSettingsChange: (AppearanceSettings) -> Unit = {},
    onAppearanceReset: () -> Unit = {},
    onOpenDestination: (AppDestination) -> Unit = {},
    useSharedDestinations: Boolean = false,
    proSubscriptionState: ProSubscriptionUiState = ProSubscriptionUiState(),
    onProSubscriptionAction: (ProSubscriptionAction) -> String = ::unavailableProSubscriptionStatus,
    liveVideoSlot: @Composable () -> Unit = {},
    hiddenDestinations: Set<AppDestination> = emptySet(),
) {
    var currentDestination by remember(initialDestination) {
        mutableStateOf(initialDestination.takeUnless { it in hiddenDestinations } ?: AppDestination.GLASSES)
    }
    var showAppearance by remember { mutableStateOf(false) }
    var localAppearance by remember(appearanceSettings) { mutableStateOf(appearanceSettings) }

    // Re-selecting the current tab pops it back to its root (chat list, settings...), as on iOS.
    var tabResetCount by remember { mutableStateOf(0) }

    if (showAppearance) {
        SystemBackHandler { showAppearance = false }
        AppearanceScreen(
            settings = localAppearance,
            dynamicColorAvailable = false,
            onSettingsChange = {
                localAppearance = it
                onAppearanceSettingsChange(it)
            },
            onReset = {
                localAppearance = AppearanceSettings()
                onAppearanceReset()
            },
            onBack = { showAppearance = false },
        )
    } else {
        val show: (AppDestination) -> Unit = { destination ->
            if (destination == currentDestination) tabResetCount++
            if (destination !in hiddenDestinations) currentDestination = destination
        }
        CyanBridgeNavShell(
            currentDestination = currentDestination,
            hiddenDestinations = hiddenDestinations,
            onNavigate = { destination ->
                if (useSharedDestinations) {
                    show(destination)
                } else if (destination == AppDestination.GLASSES) {
                    currentDestination = destination
                } else {
                    onOpenDestination(destination)
                }
            },
        ) { destination ->
            when (destination) {
                AppDestination.GLASSES -> {
                    GlassesDashboardScreen(
                        state = dashboardState,
                        onAction = { action ->
                            if (useSharedDestinations && action is GlassesDashboardAction.Navigate) {
                                show(action.destination)
                            } else {
                                onDashboardAction(action)
                            }
                        },
                        liveVideoSlot = liveVideoSlot,
                    )
                    if (showSyncFlowPicker) {
                        GlassesSyncFlowPickerDialog(
                            onDismissRequest = onSyncFlowPickerDismiss,
                            onFlowSelected = onSyncFlowSelected,
                        )
                    }
                }

                AppDestination.CHATS,
                AppDestination.MEDIA,
                AppDestination.PLUGINS,
                AppDestination.SETTINGS,
                -> if (useSharedDestinations) key(destination, tabResetCount) {
                    SharedDestinationScreen(
                        destination = destination,
                        onDestinationSelected = show,
                        onOpenAppearance = { showAppearance = true },
                        proSubscriptionState = proSubscriptionState,
                        onProSubscriptionAction = onProSubscriptionAction,
                    )
                }
            }
        }
    }
}
