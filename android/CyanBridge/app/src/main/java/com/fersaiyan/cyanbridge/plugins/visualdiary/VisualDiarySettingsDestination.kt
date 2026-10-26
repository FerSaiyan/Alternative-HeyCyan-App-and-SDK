package com.fersaiyan.cyanbridge.plugins.visualdiary

import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.devices.DeviceCapabilityHelper
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.ui.NativePluginShortcutPreference

data class VisualDiarySettingsState(
    /** Refreshed from preferences on create, resume and every enable/disable. */
    val enabled: Boolean,
    /** Refreshed together with [enabled]. */
    val lastError: String,
    val customPrompt: String,
    val hasCamera: Boolean,
    val cameraUnavailableReason: String?,
) {
    companion object {
        fun load(context: Context) = VisualDiarySettingsState(
            enabled = VisualDiaryPreferences.isEnabled(context),
            lastError = VisualDiaryPreferences.getLastError(context),
            customPrompt = VisualDiaryPreferences.getCustomPrompt(context),
            hasCamera = DeviceCapabilityHelper.hasCamera(context),
            cameraUnavailableReason = DeviceCapabilityHelper.unavailableCameraReason(context),
        )
    }
}

interface VisualDiarySettingsActions {
    fun onBack()

    /** Master switch (only wired while a camera is available) and the stop button (always `false`). */
    fun onEnabledChange(enabled: Boolean)
    fun onOpenTasker()
    fun onCaptureSceneNow()
    fun onCustomPromptChange(prompt: String)
}

class VisualDiarySettingsDestination : NavScreen(), VisualDiarySettingsActions {
    private lateinit var uiState: MutableState<VisualDiarySettingsState>

    internal val state: VisualDiarySettingsState get() = uiState.value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiState = mutableStateOf(VisualDiarySettingsState.load(this))
        refreshUi()
        setContent {
            VisualDiarySettingsScreen(state = uiState.value, actions = this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private inline fun update(transform: VisualDiarySettingsState.() -> VisualDiarySettingsState) {
        uiState.value = uiState.value.transform()
    }

    override fun onBack() = finish()

    override fun onEnabledChange(enabled: Boolean) {
        if (enabled) VisualDiaryService.enable(this) else VisualDiaryService.disable(this)
        refreshUi()
    }

    private fun refreshUi() {
        val enabled = VisualDiaryPreferences.isEnabled(this)
        val lastError = VisualDiaryPreferences.getLastError(this)
        update { copy(enabled = enabled, lastError = lastError) }
        VisualDiaryService.startIfEnabled(this)
    }

    override fun onOpenTasker() {
        packageManager.getLaunchIntentForPackage("net.dinglisch.android.taskerm")?.let(::startActivity)
    }

    override fun onCaptureSceneNow() {
        VisualDiaryService.captureNow(this)
    }

    override fun onCustomPromptChange(prompt: String) {
        update { copy(customPrompt = prompt) }
        VisualDiaryPreferences.setCustomPrompt(this, prompt)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualDiarySettingsScreen(
    state: VisualDiarySettingsState,
    actions: VisualDiarySettingsActions,
) {
    val scrollState = rememberScrollState()
    val hasCamera = state.hasCamera
    val cameraUnavailableReason = state.cameraUnavailableReason

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_plugin_settings_title, stringResource(R.string.compose_plugin_name_visual_diary))) },
                navigationIcon = {
                    IconButton(onClick = actions::onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.compose_back))
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasCamera && cameraUnavailableReason != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = stringResource(R.string.compose_camera_warning),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.compose_camera_hardware_required),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = stringResource(R.string.compose_visual_camera_description, cameraUnavailableReason),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            Text(
                "Visual Diary keeps glasses capture and visual inference inside CyanBridge. " +
                    "Tasker only owns the periodic trigger, avoiding a long-running dataSync scheduler.",
                style = MaterialTheme.typography.bodyMedium,
            )
            SwitchSetting(
                stringResource(R.string.compose_visual_enabled),
                state.enabled && hasCamera,
                if (hasCamera) actions::onEnabledChange else { _ -> },
            )
            NativePluginShortcutPreference(
                pluginId = NativePluginIds.VISUAL_DIARY,
                pluginTitle = stringResource(R.string.compose_plugin_name_visual_diary),
            )
            if (state.lastError.isNotBlank()) {
                Text(
                    stringResource(R.string.compose_last_stop_reason, state.lastError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SectionTitle(stringResource(R.string.compose_capture))
            Text(
                "The imported Tasker profile triggers every 15 minutes by default. Edit the Time profile in Tasker to change periodicity.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = actions::onOpenTasker, modifier = Modifier.fillMaxWidth()) {
                Text("Open Tasker schedule")
            }
            OutlinedButton(
                onClick = actions::onCaptureSceneNow,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.compose_capture_scene_now)) }
            SectionTitle(stringResource(R.string.compose_scene_descriptions))
            OutlinedTextField(
                value = state.customPrompt,
                onValueChange = actions::onCustomPromptChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.compose_scene_description_prompt)) },
                minLines = 3,
                maxLines = 7,
            )
            OutlinedButton(
                onClick = { actions.onEnabledChange(false) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.compose_stop_visual_diary)) }
            SectionTitle(stringResource(R.string.compose_shared_memory))
            Text(
                stringResource(R.string.compose_visual_shared_memory_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchSetting(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}
