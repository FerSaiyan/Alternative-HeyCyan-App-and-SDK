package com.fersaiyan.cyanbridge.plugins.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.R

/*
 * Small building blocks shared by the card-style native plugin settings screens
 * (Live Caption Relay, Meeting Spark Notes, Errand Brain, Hands-Free Translator).
 * Each block is a verbatim extraction of markup those screens repeated, so a screen
 * built from them renders exactly as its hand-written version did.
 */

/** Top bar with a back arrow over a padded, scrolling column spaced 12 dp apart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginSettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.compose_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun PluginSettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp),
    )
}

/** Full-width surface-variant card with 16 dp inner padding. */
@Composable
internal fun PluginSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/** A label that fills the row, followed by a switch. */
@Composable
internal fun PluginSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

/** Filter chip whose selected container uses the primary color. */
@Composable
internal fun PluginFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/** Medium-weight caption above a chip row, as used for language/priority pickers. */
@Composable
internal fun PluginFieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
}

/**
 * "Custom instructions" card: a description, then a text field with a character counter.
 * The length limit is enforced by the caller's [onValueChange].
 */
@Composable
internal fun PluginCustomInstructionsCard(
    description: String,
    label: String,
    placeholder: String,
    value: String,
    maxLength: Int,
    minLines: Int,
    maxLines: Int,
    onValueChange: (String) -> Unit,
) {
    PluginSettingsCard {
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            minLines = minLines,
            maxLines = maxLines,
            supportingText = { Text("${value.length}/$maxLength") },
        )
    }
}

/** History card: a max-entries slider followed by a full-width "Clear history" button. */
@Composable
internal fun PluginHistoryCard(
    maxHistoryLabel: String,
    maxHistory: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onMaxHistoryChange: (Int) -> Unit,
    onClearHistory: () -> Unit,
) {
    PluginSettingsCard {
        Text(
            maxHistoryLabel,
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = maxHistory.toFloat(),
            onValueChange = { newValue -> onMaxHistoryChange(newValue.toInt()) },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onClearHistory,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.compose_clear_history))
        }
    }
}
