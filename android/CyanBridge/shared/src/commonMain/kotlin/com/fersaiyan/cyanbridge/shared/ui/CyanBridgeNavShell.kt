package com.fersaiyan.cyanbridge.shared.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.ui.components.CyanBridgeBottomBar

@Composable
fun CyanBridgeNavShell(
    currentDestination: AppDestination,
    onNavigate: (AppDestination) -> Unit,
    // Tabs the host does not offer (iOS hides Plugins).
    hiddenDestinations: Set<AppDestination> = emptySet(),
    content: @Composable (AppDestination) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            CyanBridgeBottomBar(
                selected = currentDestination,
                onSelect = onNavigate,
                hiddenDestinations = hiddenDestinations,
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            content(currentDestination)
        }
    }
}
