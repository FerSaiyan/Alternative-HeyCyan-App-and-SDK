package com.fersaiyan.cyanbridge.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.analytics.AnalyticsClient
import com.fersaiyan.cyanbridge.analytics.AnalyticsPreferences
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme

object AcquisitionReasonDialog {
    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed || AnalyticsPreferences.isAcquisitionComplete(activity)) return

        val dialog = ComponentDialog(activity)
        val appearancePreferences = AppearancePreferences(activity)
        dialog.setContentView(
            ComposeView(activity).apply {
                setContent {
                    val appearance by rememberAppearanceSettings(appearancePreferences)
                    CyanBridgeTheme(appearance) {
                        AcquisitionReasonScreen(
                            sharingInitiallyEnabled = AnalyticsPreferences.suggestedSharingChoice(),
                            onSubmit = { primary, secondary, other, shareAnalytics ->
                                AnalyticsPreferences.setSharingEnabled(activity, shareAnalytics)
                                AnalyticsClient.queueAcquisitionResponse(activity, primary, secondary, other)
                                if (shareAnalytics) AnalyticsClient.recordDailyHeartbeat(activity)
                                dialog.dismiss()
                            },
                            onSkip = { shareAnalytics ->
                                AnalyticsPreferences.setSharingEnabled(activity, shareAnalytics)
                                AnalyticsClient.skipAcquisition(activity)
                                if (shareAnalytics) AnalyticsClient.recordDailyHeartbeat(activity)
                                dialog.dismiss()
                            },
                        )
                    }
                }
            },
        )
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

private data class AcquisitionOption(
    val wire: String,
    val title: String,
    val description: String,
)

private val acquisitionOptions = listOf(
    AcquisitionOption("local_ai", "Local AI", "Run models on-device or offline"),
    AcquisitionOption("model_choice", "Model choice", "Pick the provider and model"),
    AcquisitionOption("privacy_control", "Privacy control", "Control how my data is handled"),
    AcquisitionOption("open_source", "Open source", "Inspect and customize the app"),
    AcquisitionOption("accessibility", "Accessibility", "Visual assistance and access tools"),
    AcquisitionOption("glasses_compatibility", "Better glasses support", "Improve device compatibility"),
    AcquisitionOption("curiosity", "Explore", "Experiment with new ideas"),
    AcquisitionOption("other", "Something else", "A different reason"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AcquisitionReasonScreen(
    sharingInitiallyEnabled: Boolean,
    onSubmit: (String, Set<String>, String?, Boolean) -> Unit,
    onSkip: (Boolean) -> Unit,
) {
    var primary by remember { mutableStateOf<String?>(null) }
    var secondary by remember { mutableStateOf(setOf<String>()) }
    var otherText by remember { mutableStateOf("") }
    var shareAnalytics by remember { mutableStateOf(sharingInitiallyEnabled) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 620.dp)
                .fillMaxHeight(0.94f),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "QUICK SETUP / OPTIONAL",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "What brought you to CyanBridge?",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Text(
                        "Choose the main reason you installed the app. Your answer helps prioritize what CyanBridge improves next.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Column(
                        modifier = Modifier.selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        acquisitionOptions.chunked(2).forEach { options ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                options.forEach { option ->
                                    AcquisitionOptionCard(
                                        option = option,
                                        selected = primary == option.wire,
                                        onClick = {
                                            primary = option.wire
                                            secondary = secondary - option.wire
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (options.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    AnimatedVisibility(visible = primary != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column {
                                Text(
                                    "Anything else that mattered?",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Optional, choose up to two.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                acquisitionOptions
                                    .filter { it.wire != primary && it.wire != "other" }
                                    .forEach { option ->
                                        val selected = option.wire in secondary
                                        FilterChip(
                                            selected = selected,
                                            enabled = selected || secondary.size < 2,
                                            onClick = {
                                                secondary = if (selected) {
                                                    secondary - option.wire
                                                } else {
                                                    secondary + option.wire
                                                }
                                            },
                                            label = { Text(option.title) },
                                            leadingIcon = if (selected) {
                                                { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                            } else null,
                                        )
                                    }
                            }
                            OutlinedTextField(
                                value = otherText,
                                onValueChange = { otherText = it.take(500) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Optional context") },
                                placeholder = { Text("Tell us what you hoped CyanBridge would solve") },
                                minLines = 1,
                                maxLines = 3,
                                shape = RoundedCornerShape(16.dp),
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Share anonymous app analytics",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Shares a random install ID, app version, channel, and one daily activity signal. Never photos, audio, prompts, transcripts, contacts, or files.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(checked = shareAnalytics, onCheckedChange = { shareAnalytics = it })
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { onSkip(shareAnalytics) }) {
                        Text("Skip")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { primary?.let { onSubmit(it, secondary, otherText.ifBlank { null }, shareAnalytics) } },
                        enabled = primary != null,
                    ) {
                        Text("Continue")
                    }
                }
            }
        }
    }
}

@Composable
private fun AcquisitionOptionCard(
    option: AcquisitionOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 92.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            ),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    option.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Surface(
                        modifier = Modifier.size(24.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Text(
                option.description,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
