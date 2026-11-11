package com.fersaiyan.cyanbridge.shared.plugins.orallesion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Oral Lesion Bench screen. [photo] draws the case photo (decoded by the platform). The full
 * model answers stay on screen; the short spoken versions go to the speaker and glasses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OralLesionBenchScreen(
    viewModel: OralLesionBenchViewModel,
    glassesPhotoUnavailableReason: String?,
    photo: @Composable (String) -> Unit,
    onBack: () -> Unit,
) {
    val settings = viewModel.state
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.oral_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("oral_lesion_bench"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        stringResource(Res.string.oral_research_notice),
                        modifier = Modifier.padding(12.dp).testTag("oral_research_notice"),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            item {
                SwitchRow(Res.string.oral_enable, settings.enabled, viewModel::onEnabledChange)
                SwitchRow(Res.string.oral_speak_replies, settings.speakReplies, viewModel::onSpeakRepliesChange)
                if (!settings.enabled) Text(stringResource(Res.string.oral_disabled_hint), style = MaterialTheme.typography.bodySmall)
            }
            item { SectionTitle(Res.string.oral_section_model) }
            items(viewModel.models, key = { it.id }) { model -> ModelRow(viewModel, model, model.id == settings.selectedModelId) }
            item { SectionTitle(Res.string.oral_section_case) }
            item { CaseInputs(viewModel, glassesPhotoUnavailableReason, photo) }
            item { Actions(viewModel) }
            viewModel.status?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            viewModel.guardrail?.let { guard -> guard.line?.let { line -> item { GuardrailBanner(line, guard.addedReferral) } } }
            if (viewModel.turn1Text.isNotEmpty()) item { OutputCard(Res.string.oral_turn1, viewModel.turn1Text) }
            if (viewModel.questions.isNotEmpty()) item { Questions(viewModel) }
            item { RedFlags(viewModel) }
            if (viewModel.turn2Text.isNotEmpty()) item { OutputCard(Res.string.oral_turn2, viewModel.turn2Text) }
            if (viewModel.summaryText.isNotEmpty()) item { OutputCard(Res.string.oral_summary, viewModel.summaryText) }
            item { SectionTitle(Res.string.oral_section_cases) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.oral_cases_count, viewModel.cases.size), modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = viewModel::onExport, enabled = viewModel.cases.isNotEmpty()) {
                        Text(stringResource(Res.string.oral_export))
                    }
                }
            }
            viewModel.editingCaseId?.let { item { DentistFields(viewModel) } }
            items(viewModel.cases, key = { it.caseId }) { case -> CaseRow(viewModel, case) }
        }
    }
}

@Composable
private fun SectionTitle(title: StringResource) {
    Column {
        HorizontalDivider()
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SwitchRow(label: StringResource, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ModelRow(viewModel: OralLesionBenchViewModel, model: OralLesionModel, selected: Boolean) {
    val state = viewModel.modelStates[model.id] ?: OralLesionModelState.Checking
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = { viewModel.onSelectModel(model.id) })
        Column(Modifier.weight(1f)) {
            Text(model.displayName, fontWeight = FontWeight.SemiBold)
            Text("${model.description} ${model.sizeBytes / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
            when (state) {
                OralLesionModelState.Checking -> Text(stringResource(Res.string.oral_model_checking), style = MaterialTheme.typography.bodySmall)
                OralLesionModelState.NotInstalled -> TextButton(onClick = { viewModel.onInstall(model) }) {
                    Text(stringResource(Res.string.oral_model_download))
                }
                is OralLesionModelState.Downloading -> {
                    Text(stringResource(Res.string.oral_model_downloading, state.percent), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                OralLesionModelState.Verifying -> Text(stringResource(Res.string.oral_model_verifying), style = MaterialTheme.typography.bodySmall)
                is OralLesionModelState.Installed -> Text(stringResource(Res.string.oral_model_installed), style = MaterialTheme.typography.bodySmall)
                OralLesionModelState.NeedsToken -> {
                    Text(stringResource(Res.string.oral_model_needs_token), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = viewModel::onOpenModelSettings) { Text(stringResource(Res.string.oral_model_open_settings)) }
                        TextButton(onClick = { viewModel.onInstall(model) }) { Text(stringResource(Res.string.oral_model_download)) }
                    }
                    if (model.gated) {
                        TextButton(onClick = { viewModel.onRequestAccess(model) }) { Text(stringResource(Res.string.oral_request_access)) }
                    }
                }
                OralLesionModelState.AccessDenied -> {
                    Text(stringResource(Res.string.oral_model_access_denied), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { viewModel.onRequestAccess(model) }, modifier = Modifier.testTag("oral_request_access")) {
                            Text(stringResource(Res.string.oral_request_access))
                        }
                        TextButton(onClick = { viewModel.onInstall(model) }) { Text(stringResource(Res.string.oral_model_retry)) }
                    }
                    Text(model.repoUrl, style = MaterialTheme.typography.bodySmall)
                }
                is OralLesionModelState.Failed -> {
                    Text(stringResource(Res.string.oral_model_failed, state.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { viewModel.onInstall(model) }) { Text(stringResource(Res.string.oral_model_retry)) }
                }
            }
        }
    }
}

@Composable
private fun CaseInputs(viewModel: OralLesionBenchViewModel, glassesUnavailable: String?, photo: @Composable (String) -> Unit) {
    val idle = !viewModel.busy && viewModel.phase in setOf(OralLesionPhase.IDLE, OralLesionPhase.DONE)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::onCaptureGlasses, enabled = idle && glassesUnavailable == null) {
                Text(stringResource(Res.string.oral_photo_glasses))
            }
            OutlinedButton(onClick = viewModel::onTakeCameraPhoto, enabled = idle) { Text(stringResource(Res.string.oral_photo_camera)) }
            OutlinedButton(onClick = viewModel::onPickGallery, enabled = idle) { Text(stringResource(Res.string.oral_photo_gallery)) }
        }
        glassesUnavailable?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        viewModel.photoPath?.let { photo(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = viewModel.ageText,
                onValueChange = viewModel::onAgeChange,
                label = { Text(stringResource(Res.string.oral_age)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.width(96.dp),
            )
            PatientSex.entries.forEach { sex ->
                FilterChip(
                    selected = viewModel.sex == sex,
                    onClick = { viewModel.onSexChange(sex) },
                    label = {
                        Text(
                            stringResource(
                                when (sex) {
                                    PatientSex.MALE -> Res.string.oral_sex_male
                                    PatientSex.FEMALE -> Res.string.oral_sex_female
                                    PatientSex.UNKNOWN -> Res.string.oral_sex_unknown
                                },
                            ),
                        )
                    },
                )
            }
        }
        OutlinedTextField(
            value = viewModel.site,
            onValueChange = viewModel::onSiteChange,
            label = { Text(stringResource(Res.string.oral_site)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = viewModel::onDictateDemographics) {
            Text(stringResource(if (viewModel.listening && viewModel.currentQuestion < 0) Res.string.oral_listening else Res.string.oral_dictate))
        }
    }
}

@Composable
private fun Actions(viewModel: OralLesionBenchViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::onAssess, enabled = viewModel.canAssess, modifier = Modifier.testTag("oral_assess")) {
                Text(stringResource(Res.string.oral_assess))
            }
            OutlinedButton(onClick = viewModel::onStop, enabled = viewModel.busy || viewModel.phase == OralLesionPhase.QUESTIONS) {
                Text(stringResource(Res.string.oral_stop))
            }
            OutlinedButton(onClick = viewModel::onNewCase, enabled = !viewModel.busy) { Text(stringResource(Res.string.oral_new_case)) }
        }
        if (viewModel.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (viewModel.phase == OralLesionPhase.DONE && viewModel.turn1Text.isNotEmpty() && viewModel.summaryText.isEmpty() && !viewModel.busy) {
            OutlinedButton(onClick = viewModel::onSummary) { Text(stringResource(Res.string.oral_run_summary)) }
        }
    }
}

@Composable
private fun GuardrailBanner(line: String, added: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp).testTag("oral_guardrail")) {
            Text(line, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            if (added) Text(stringResource(Res.string.oral_guard_added), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OutputCard(title: StringResource, text: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Questions(viewModel: OralLesionBenchViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.oral_questions), style = MaterialTheme.typography.titleSmall)
            viewModel.questions.forEachIndexed { index, question ->
                val active = index == viewModel.currentQuestion
                Text("${index + 1}. $question", fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                viewModel.answers.getOrNull(index)?.let { Text("→ $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (viewModel.phase == OralLesionPhase.QUESTIONS && viewModel.currentQuestion >= 0) {
                OutlinedTextField(
                    value = viewModel.answerDraft,
                    onValueChange = viewModel::onAnswerDraftChange,
                    label = { Text(stringResource(if (viewModel.listening) Res.string.oral_listening else Res.string.oral_answer)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::onSubmitAnswer) { Text(stringResource(Res.string.oral_answer_send)) }
                    OutlinedButton(onClick = viewModel::onListenAnswer) { Text(stringResource(Res.string.oral_answer_listen)) }
                    OutlinedButton(onClick = viewModel::onSkipAnswer) { Text(stringResource(Res.string.oral_answer_skip)) }
                }
                TextButton(onClick = viewModel::onSendAnswers) { Text(stringResource(Res.string.oral_send_answers)) }
            }
        }
    }
}

@Composable
private fun RedFlags(viewModel: OralLesionBenchViewModel) {
    Column {
        Text(stringResource(Res.string.oral_red_flags), style = MaterialTheme.typography.titleSmall)
        RedFlag.entries.forEach { flag ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = flag in viewModel.redFlags, onCheckedChange = { viewModel.onToggleRedFlag(flag) })
                Text(
                    stringResource(
                        when (flag) {
                            RedFlag.INDURATION -> Res.string.oral_flag_induration
                            RedFlag.BLEEDING -> Res.string.oral_flag_bleeding
                            RedFlag.FIXED_NODE -> Res.string.oral_flag_fixed_node
                            RedFlag.ULCER_OVER_TWO_WEEKS -> Res.string.oral_flag_ulcer
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun DentistFields(viewModel: OralLesionBenchViewModel) {
    val fields = viewModel.dentist
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.oral_section_dentist) + " · " + viewModel.editingCaseId.orEmpty(), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = fields.finalDiagnosis,
                onValueChange = { viewModel.onDentistChange(fields.copy(finalDiagnosis = it.take(300))) },
                label = { Text(stringResource(Res.string.oral_final_dx)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = fields.biopsyResult,
                onValueChange = { viewModel.onDentistChange(fields.copy(biopsyResult = it.take(300))) },
                label = { Text(stringResource(Res.string.oral_biopsy)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "useful" to Res.string.oral_rating_useful,
                    "wrong" to Res.string.oral_rating_wrong,
                    "unsafe" to Res.string.oral_rating_unsafe,
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = fields.rating == value,
                        onClick = { viewModel.onDentistChange(fields.copy(rating = if (fields.rating == value) "" else value)) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            OutlinedTextField(
                value = fields.notes,
                onValueChange = { viewModel.onDentistChange(fields.copy(notes = it.take(1_000))) },
                label = { Text(stringResource(Res.string.oral_notes)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = viewModel::onSaveDentistFields) { Text(stringResource(Res.string.oral_save_case)) }
        }
    }
}

@Composable
private fun CaseRow(viewModel: OralLesionBenchViewModel, case: OralLesionBenchCase) {
    val referral = case.guardrail.lastOrNull()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${case.caseId} · ${case.modelId}", style = MaterialTheme.typography.bodyMedium)
            val timing = case.metrics.firstOrNull()?.let { metricsLine(it) }.orEmpty()
            val guard = when {
                referral?.urgent == true -> "URGENT"
                referral?.refer == true -> "REFER"
                else -> ""
            }
            Text(listOf(case.backend, timing, guard, case.dentist.rating).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { viewModel.onEditCase(case.caseId) }) { Text(stringResource(Res.string.oral_edit)) }
        TextButton(onClick = { viewModel.onDeleteCase(case.caseId) }) { Text(stringResource(Res.string.oral_delete)) }
    }
}

/** "TTFT 2100 ms, 310/12.4 tok/s" without platform number formatting. */
internal fun metricsLine(metrics: OralLesionTurnMetrics): String = buildList {
    metrics.ttftMs?.let { add("TTFT $it ms") }
    val prefill = metrics.prefillTokensPerSecond?.let(::oneDecimal)
    val decode = metrics.decodeTokensPerSecond?.let(::oneDecimal)
    if (prefill != null || decode != null) add("${prefill ?: "?"}/${decode ?: "?"} tok/s")
    add("${metrics.latencyMs} ms")
}.joinToString(", ")

private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
}
