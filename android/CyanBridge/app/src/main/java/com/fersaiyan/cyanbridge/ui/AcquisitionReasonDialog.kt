package com.fersaiyan.cyanbridge.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.analytics.AnalyticsClient
import com.fersaiyan.cyanbridge.analytics.AnalyticsPreferences
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme

object AcquisitionReasonDialog {
    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed || AnalyticsPreferences.isAcquisitionComplete(activity)) return

        val dialog = Dialog(activity)
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
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

private data class AcquisitionOption(val wire: String, val label: String)

private val acquisitionOptions = listOf(
    AcquisitionOption("local_ai", "Run AI models locally or offline"),
    AcquisitionOption("model_choice", "Choose which AI models/providers I use"),
    AcquisitionOption("privacy_control", "Have more control over how my data is handled"),
    AcquisitionOption("open_source", "Use an open-source app I can inspect"),
    AcquisitionOption("accessibility", "Use accessibility / visual-assistance features"),
    AcquisitionOption("glasses_compatibility", "Get better support for my smart glasses"),
    AcquisitionOption("curiosity", "I was curious / wanted to experiment"),
    AcquisitionOption("other", "Other"),
)

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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp)
            .heightIn(max = 720.dp),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("What brought you to CyanBridge?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Choose the main reason you downloaded the app. This one-minute question helps us validate which problems CyanBridge is actually solving.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            acquisitionOptions.forEach { option ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = primary == option.wire,
                            onClick = {
                                primary = option.wire
                                secondary = secondary - option.wire
                            },
                        )
                        Text(option.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (primary != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Anything else that mattered?", style = MaterialTheme.typography.titleSmall)
                        Text("Optional — choose up to two.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        acquisitionOptions
                            .filter { it.wire != primary && it.wire != "other" }
                            .forEach { option ->
                                val checked = option.wire in secondary
                                val enabled = checked || secondary.size < 2
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = checked,
                                        enabled = enabled,
                                        onCheckedChange = { value ->
                                            secondary = if (value) secondary + option.wire else secondary - option.wire
                                        },
                                    )
                                    Text(option.label, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                    }
                }
            }

            TextField(
                value = otherText,
                onValueChange = { otherText = it.take(500) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Optional context") },
                placeholder = { Text("For example: the official app did not support my glasses well") },
                minLines = 2,
                maxLines = 4,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Share anonymous usage analytics", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Sends a random installation ID, app version, distribution channel, and one daily activity heartbeat. No heartbeat is sent until you save this choice, and CyanBridge never includes photos, audio, prompts, transcripts, contacts, or files in product analytics.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = shareAnalytics, onCheckedChange = { shareAnalytics = it })
                }
            }

            Button(
                onClick = { primary?.let { onSubmit(it, secondary, otherText.ifBlank { null }, shareAnalytics) } },
                enabled = primary != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue")
            }
            OutlinedButton(onClick = { onSkip(shareAnalytics) }, modifier = Modifier.fillMaxWidth()) {
                Text("Skip this question")
            }
        }
    }
}
