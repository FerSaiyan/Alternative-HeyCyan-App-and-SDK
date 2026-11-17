package com.fersaiyan.cyanbridge.shared.ui.pro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.customization.CustomizationPhase
import com.fersaiyan.cyanbridge.shared.customization.GlassesCustomizationActions
import com.fersaiyan.cyanbridge.shared.customization.GlassesCustomizationUiState
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import com.fersaiyan.cyanbridge.shared.ui.components.ExpandableSectionCard
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

/**
 * The collapsible "Glasses customization" card of the Pro settings. Every control carries a
 * `customization_*` test tag (listed in the E2E contract, section 5).
 */
@OptIn(ExperimentalResourceApi::class, ExperimentalLayoutApi::class)
@Composable
fun GlassesCustomizationCard(state: GlassesCustomizationUiState, actions: GlassesCustomizationActions) {
    ExpandableSectionCard(
        title = stringResource(Res.string.customization_title),
        subtitle = stringResource(Res.string.customization_subtitle),
        expanded = state.expanded,
        onToggle = actions::toggleExpanded,
        headerTestTag = "customization_card",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.statusText.isNotBlank()) {
                Text(state.statusText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("customization_status"))
            }
            state.glassesLabel?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("customization_glasses"))
            }
            when (state.phase) {
                CustomizationPhase.LOCKED -> Button(
                    onClick = actions::upgrade,
                    modifier = Modifier.fillMaxWidth().testTag("customization_upgrade"),
                ) { Text(stringResource(Res.string.customization_upgrade)) }
                CustomizationPhase.LOADING -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                CustomizationPhase.READY -> ReadyContent(state, actions)
                else -> OutlinedButton(
                    onClick = actions::refresh,
                    modifier = Modifier.fillMaxWidth().testTag("customization_refresh"),
                ) { Text(stringResource(Res.string.customization_refresh)) }
            }
            if (state.phase != CustomizationPhase.LOCKED) {
                HorizontalDivider()
                BuildsAndInstall(state, actions)
            }
        }
    }
}

@OptIn(ExperimentalResourceApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ReadyContent(state: GlassesCustomizationUiState, actions: GlassesCustomizationActions) {
    state.reviewNotice?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.testTag("customization_review_notice"),
        )
    }
    if (state.tones.isNotEmpty()) {
        SectionTitle(stringResource(Res.string.customization_tones))
        Text(stringResource(Res.string.customization_tones_hint), style = MaterialTheme.typography.bodySmall)
        state.tones.forEach { tone ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tone.label, style = MaterialTheme.typography.bodyMedium)
                    (tone.error ?: tone.fileName)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (tone.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("customization_tone_file_${tone.id}"),
                        )
                    }
                    if (tone.error == null && tone.fileName == null) {
                        tone.hint?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (tone.fileName != null) {
                    TextButton(onClick = { actions.clearTone(tone.id) }, modifier = Modifier.testTag("customization_tone_clear_${tone.id}")) {
                        Text(stringResource(Res.string.customization_remove))
                    }
                }
                TextButton(
                    onClick = { actions.pickTone(tone.id) },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("customization_tone_pick_${tone.id}"),
                ) { Text(stringResource(Res.string.customization_choose_file)) }
            }
        }
    }
    if (state.showDisableLed || state.showMuteShutter) {
        SectionTitle(stringResource(Res.string.customization_options))
        if (state.showDisableLed) {
            SwitchRow(stringResource(Res.string.customization_disable_led), state.disableLed, "customization_led_toggle", actions::setDisableLed)
        }
        if (state.showMuteShutter) {
            SwitchRow(stringResource(Res.string.customization_mute_shutter), state.muteShutter, "customization_shutter_toggle", actions::setMuteShutter)
        }
    }
    if (state.wakeSupported) {
        SectionTitle(stringResource(Res.string.customization_wake_word))
        OutlinedTextField(
            value = state.wakePhrase,
            onValueChange = actions::setWakePhrase,
            label = { Text(stringResource(Res.string.customization_wake_phrase)) },
            singleLine = true,
            enabled = !state.recording && !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("customization_wake_phrase"),
        )
        if (state.wakeLanguages.size > 1) {
            Text(stringResource(Res.string.customization_wake_language), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.wakeLanguages.forEach { code ->
                    FilterChip(
                        selected = code == state.wakeLanguage,
                        onClick = { actions.setWakeLanguage(code) },
                        label = { Text(code.uppercase()) },
                        modifier = Modifier.testTag("customization_wake_language_$code"),
                    )
                }
            }
        }
        state.phonemesNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(stringResource(Res.string.customization_wake_guidance), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = actions::recordSample,
                enabled = state.canRecord,
                modifier = Modifier.weight(1f).testTag("customization_record"),
            ) { Text(stringResource(Res.string.customization_record)) }
            OutlinedButton(
                onClick = actions::stopRecording,
                enabled = state.recording,
                modifier = Modifier.weight(1f).testTag("customization_stop"),
            ) { Text(stringResource(Res.string.customization_stop)) }
        }
        val meterLabel = stringResource(Res.string.customization_mic_level)
        LinearProgressIndicator(
            progress = { state.level },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp))
                .testTag("customization_level")
                .semantics { contentDescription = meterLabel },
        )
        if (state.recordingHint.isNotBlank()) {
            Text(state.recordingHint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("customization_record_hint"))
        }
        Text(
            stringResource(Res.string.customization_samples, state.samples.size, state.sampleMin, state.sampleMax),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("customization_sample_count"),
        )
        state.samples.forEach { sample ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                val seconds = "${sample.durationMs / 1000}.${(sample.durationMs % 1000 / 100)}"
                Text(
                    stringResource(Res.string.customization_sample_row, sample.index + 1, seconds, sample.peakDbfs),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { actions.deleteSample(sample.index) },
                    enabled = !state.recording && !state.busy,
                    modifier = Modifier.testTag("customization_sample_delete_${sample.index + 1}"),
                ) { Text(stringResource(Res.string.customization_delete)) }
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("customization_consent")
            .toggleable(value = state.consentChecked, role = Role.Checkbox, onValueChange = actions::setConsent),
    ) {
        Checkbox(checked = state.consentChecked, onCheckedChange = null)
        Text(
            stringResource(Res.string.customization_consent, state.consentVersion),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
    Button(
        onClick = actions::submit,
        enabled = state.canSubmit,
        modifier = Modifier.fillMaxWidth().testTag("customization_submit"),
    ) { Text(stringResource(Res.string.customization_submit)) }
    if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    state.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("customization_progress")) }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun BuildsAndInstall(state: GlassesCustomizationUiState, actions: GlassesCustomizationActions) {
    SectionTitle(stringResource(Res.string.customization_builds))
    if (state.jobs.isEmpty()) {
        Text(stringResource(Res.string.customization_no_builds), style = MaterialTheme.typography.bodySmall)
    }
    state.jobs.forEachIndexed { index, job ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("customization_job_$index")) {
            Column(modifier = Modifier.weight(1f)) {
                Text(job.title, style = MaterialTheme.typography.bodyMedium)
                Text(job.statusLabel, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("customization_job_status_$index"))
                if (job.message.isNotBlank()) Text(job.message, style = MaterialTheme.typography.bodySmall)
                job.baseNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("customization_job_base_$index"))
                }
            }
            if (job.active) {
                TextButton(onClick = { actions.cancelJob(job.id) }, modifier = Modifier.testTag("customization_job_cancel_$index")) {
                    Text(stringResource(Res.string.customization_cancel_build))
                }
            }
        }
    }
    Text(stringResource(Res.string.customization_install_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(
        onClick = actions::installFirmware,
        enabled = !state.installing,
        modifier = Modifier.fillMaxWidth().testTag("customization_install"),
    ) { Text(stringResource(Res.string.customization_install)) }
    if (state.installing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    state.installStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("customization_install_status")) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
