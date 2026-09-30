package com.fersaiyan.cyanbridge.shared.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.appearance.AppearanceSettings
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionAction
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionUiState
import com.fersaiyan.cyanbridge.shared.billing.unavailableProSubscriptionStatus
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.action_open
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
 * Android can keep the legacy Activity presenters by leaving
 * [useSharedDestinations] disabled. The iOS KMP host enables it to render the
 * migrated shared destinations directly.
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
    onNavigateToActivity: (AppDestination) -> Unit = {},
    useSharedDestinations: Boolean = false,
    proSubscriptionState: ProSubscriptionUiState = ProSubscriptionUiState(),
    onProSubscriptionAction: (ProSubscriptionAction) -> String = ::unavailableProSubscriptionStatus,
    liveVideoSlot: @Composable () -> Unit = {},
) {
    var currentDestination by remember(initialDestination) { mutableStateOf(initialDestination) }
    var showAppearance by remember { mutableStateOf(false) }
    var localAppearance by remember(appearanceSettings) { mutableStateOf(appearanceSettings) }

    if (showAppearance) {
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
        CyanBridgeNavShell(
            currentDestination = currentDestination,
            onNavigate = { destination ->
                if (useSharedDestinations) {
                    currentDestination = destination
                } else if (destination == AppDestination.GLASSES) {
                    currentDestination = destination
                } else {
                    onNavigateToActivity(destination)
                }
            },
        ) { destination ->
            when (destination) {
                AppDestination.GLASSES -> {
                    GlassesDashboardScreen(
                        state = dashboardState,
                        onAction = { action ->
                            if (useSharedDestinations && action is GlassesDashboardAction.Navigate) {
                                currentDestination = action.destination
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
                -> if (useSharedDestinations) {
                    SharedDestinationScreen(
                        destination = destination,
                        onDestinationSelected = { currentDestination = it },
                        onOpenAppearance = { showAppearance = true },
                        proSubscriptionState = proSubscriptionState,
                        onProSubscriptionAction = onProSubscriptionAction,
                    )
                } else {
                        ActivityLaunchPlaceholder(
                            title = localizedDestinationLabel(destination),
                            subtitle = localizedDestinationSubtitle(destination),
                            onOpen = { onNavigateToActivity(destination) },
                        )
                }
            }
        }
    }
}

@Composable
private fun ActivityLaunchPlaceholder(
    title: String,
    subtitle: String,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onOpen) {
            Text(org.jetbrains.compose.resources.stringResource(Res.string.action_open))
        }
    }
}
