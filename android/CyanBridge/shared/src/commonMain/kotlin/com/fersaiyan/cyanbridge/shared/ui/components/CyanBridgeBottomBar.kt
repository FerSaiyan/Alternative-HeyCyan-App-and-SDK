package com.fersaiyan.cyanbridge.shared.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.fersaiyan.cyanbridge.shared.icons.imageVector
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.navigation.icon
import com.fersaiyan.cyanbridge.shared.ui.localizedDestinationLabel

/**
 * The app's bottom navigation: the Material 3 Expressive short navigation bar. Every tab screen
 * (and the shared navigation shell used by the glasses screen and by iOS) draws this one bar, so
 * its colors no longer change between tabs. Items keep the tab role and the visible label, which
 * TalkBack and VoiceOver read; icons are decorative.
 */
@Composable
fun CyanBridgeBottomBar(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
    // Tabs the host does not offer (iOS hides Plugins).
    hiddenDestinations: Set<AppDestination> = emptySet(),
) {
    val colors = ShortNavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColorTopIconPosition = MaterialTheme.colorScheme.onSurface,
        selectedIndicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ShortNavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        AppDestination.entries.filterNot { it in hiddenDestinations }.forEach { destination ->
            val isSelected = destination == selected
            ShortNavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon.imageVector(),
                        contentDescription = null,
                    )
                },
                label = {
                    Text(
                        text = localizedDestinationLabel(destination),
                        style = if (isSelected) {
                            MaterialTheme.typography.labelMediumEmphasized
                        } else {
                            MaterialTheme.typography.labelMedium
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = colors,
            )
        }
    }
}
