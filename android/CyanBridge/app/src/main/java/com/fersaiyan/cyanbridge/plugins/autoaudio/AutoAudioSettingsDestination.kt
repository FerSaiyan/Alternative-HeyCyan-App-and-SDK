package com.fersaiyan.cyanbridge.plugins.autoaudio

import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.plugins.settings.nativePluginViewModel
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.settings.AutoAudioSettingsViewModel
import com.fersaiyan.cyanbridge.shared.prefs.MediaPrefs.AutoAudioCapture
import com.fersaiyan.cyanbridge.ui.NativePluginShortcutPreference

class AutoAudioSettingsDestination : NavScreen() {

    internal val viewModel by nativePluginViewModel(AutoAudioCapture, ::AutoAudioSettingsViewModel)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoAudioSettingsScreen(viewModel, onEnabledChange = ::onEnabledChange, onBack = ::finish)
        }
    }

    /** Capture starts from the Activity: the permission prompts, and the service's own Bluetooth one, need it. */
    internal fun onEnabledChange(enabled: Boolean) {
        viewModel.onEnabledChange(enabled)
        if (!enabled) return
        PluginVoicePermissions.ensure(activity) {
            viewModel.setCatalogEnabled(true)
            AutoAudioCaptureService.start(this)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoAudioSettingsScreen(
    viewModel: AutoAudioSettingsViewModel,
    onEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val state = viewModel.state
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_plugin_settings_title, stringResource(R.string.compose_plugin_name_auto_audio))) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            Text(
                stringResource(R.string.compose_auto_audio_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            SwitchSetting(stringResource(R.string.compose_auto_audio_capture_enabled), state.enabled, onEnabledChange)
            NativePluginShortcutPreference(
                pluginId = NativePluginIds.AUTO_AUDIO,
                pluginTitle = stringResource(R.string.compose_plugin_name_auto_audio),
            )
            SwitchSetting(stringResource(R.string.compose_auto_audio_extend), state.speechExtend, viewModel::onSpeechExtendChange)
            SwitchSetting(stringResource(R.string.compose_auto_audio_visual_notes), state.visualNotes, viewModel::onVisualNotesChange)
            NumberSetting(
                label = stringResource(R.string.compose_auto_audio_loops),
                value = state.loopsPerSync,
                range = 1..96,
                onValueChanged = viewModel::onLoopsPerSyncChange,
            )
            Text(
                stringResource(
                    R.string.compose_status,
                    viewModel.readLastPauseReason().ifBlank {
                        stringResource(R.string.compose_ready)
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { onEnabledChange(false) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.compose_auto_audio_stop_loop)) }
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
private fun NumberSetting(
    label: String,
    value: Int,
    range: IntRange,
    onValueChanged: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label)
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                it.toIntOrNull()?.takeIf { number -> number in range }?.let(onValueChanged)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}
