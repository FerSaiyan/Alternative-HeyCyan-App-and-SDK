package com.fersaiyan.cyanbridge.shared.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A Material 3 Expressive connected button group for one choice out of a few (it replaces the
 * segmented buttons and side-by-side filter chips these screens used). Put [ConnectedChoiceButton]s
 * inside, usually with `Modifier.weight(1f)`.
 */
@Composable
fun ConnectedChoiceRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * One option of a [ConnectedChoiceRow]: the outer corners are round, the inner ones square, and
 * the selected option morphs to a full pill (primary) and the others stay tonal (secondary
 * container). Screen readers hear a radio button ("selected" /
 * "not selected"), like the segmented buttons before. At least 48 dp tall.
 */
@Composable
fun ConnectedChoiceButton(
    selected: Boolean,
    onClick: () -> Unit,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    ToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        shapes = when (index) {
            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
            count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
        },
        enabled = enabled,
        // A tinted container for the unselected options, so every option stays visible as a
        // button on card surfaces and in the high-contrast scheme (where surfaces are flat white
        // or black and Material's default unselected surfaceContainer would vanish).
        colors = ToggleButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .then(modifier)
            .heightIn(min = 48.dp),
        content = content,
    )
}
