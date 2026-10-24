package com.fersaiyan.cyanbridge.plugins.autoaudio

import android.content.Context
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs
import com.fersaiyan.cyanbridge.ui.NativePluginShortcutPreference
import com.fersaiyan.cyanbridge.ui.setThemedComposeScreen

data class AutoAudioSettingsState(
    val enabled: Boolean,
    val visualNotes: Boolean,
    val speechExtend: Boolean,
    val loopsPerSync: Int,
) {
    companion object {
        fun load(context: Context) = AutoAudioSettingsState(
            enabled = AutoAudioCapturePrefs.isEnabled(context),
            visualNotes = AutoAudioCapturePrefs.isVisualNotesEnabled(context),
            speechExtend = AutoAudioCapturePrefs.isSpeechExtendEnabled(context),
            loopsPerSync = AutoAudioCapturePrefs.getLoopsPerSync(context),
        )
    }
}

interface AutoAudioSettingsActions {
    fun onBack()

    /** Master switch and the "stop loop" button (which always passes `false`). */
    fun onEnabledChange(enabled: Boolean)
    fun onSpeechExtendChange(enabled: Boolean)
    fun onVisualNotesChange(enabled: Boolean)
    fun onLoopsPerSyncChange(loops: Int)

    /**
     * Read while composing, not cached in state: the capture service updates this
     * preference on its own, and the screen has always shown the value current at
     * its latest recomposition.
     */
    fun readLastPauseReason(): String
}

class AutoAudioSettingsActivity : AppCompatActivity(), AutoAudioSettingsActions {

    private lateinit var uiState: MutableState<AutoAudioSettingsState>

    internal val state: AutoAudioSettingsState get() = uiState.value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiState = mutableStateOf(AutoAudioSettingsState.load(this))
        setThemedComposeScreen {
            AutoAudioSettingsScreen(state = uiState.value, actions = this)
        }
    }

    private inline fun update(transform: AutoAudioSettingsState.() -> AutoAudioSettingsState) {
        uiState.value = uiState.value.transform()
    }

    override fun onBack() = finish()

    override fun onEnabledChange(enabled: Boolean) {
        update { copy(enabled = enabled) }
        AutoAudioCapturePrefs.setEnabled(this, enabled)
        if (!enabled) {
            CommunityPluginPrefs.setNativePluginEnabled(this, NativePluginIds.AUTO_AUDIO, false)
            AutoAudioCaptureService.stop(this)
            return
        }
        PluginVoicePermissions.ensure(this) {
            CommunityPluginPrefs.setNativePluginEnabled(this, NativePluginIds.AUTO_AUDIO, true)
            AutoAudioCaptureService.start(this)
        }
    }

    override fun onSpeechExtendChange(enabled: Boolean) {
        update { copy(speechExtend = enabled) }
        AutoAudioCapturePrefs.setSpeechExtendEnabled(this, enabled)
    }

    override fun onVisualNotesChange(enabled: Boolean) {
        update { copy(visualNotes = enabled) }
        AutoAudioCapturePrefs.setVisualNotesEnabled(this, enabled)
    }

    override fun onLoopsPerSyncChange(loops: Int) {
        update { copy(loopsPerSync = loops) }
        AutoAudioCapturePrefs.setLoopsPerSync(this, loops)
    }

    override fun readLastPauseReason(): String = AutoAudioCapturePrefs.getLastPauseReason(this)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoAudioSettingsScreen(
    state: AutoAudioSettingsState,
    actions: AutoAudioSettingsActions,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_plugin_settings_title, stringResource(R.string.compose_plugin_name_auto_audio))) },
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
            Text(
                stringResource(R.string.compose_auto_audio_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            SwitchSetting(stringResource(R.string.compose_auto_audio_capture_enabled), state.enabled, actions::onEnabledChange)
            NativePluginShortcutPreference(
                pluginId = NativePluginIds.AUTO_AUDIO,
                pluginTitle = stringResource(R.string.compose_plugin_name_auto_audio),
            )
            SwitchSetting(stringResource(R.string.compose_auto_audio_extend), state.speechExtend, actions::onSpeechExtendChange)
            SwitchSetting(stringResource(R.string.compose_auto_audio_visual_notes), state.visualNotes, actions::onVisualNotesChange)
            NumberSetting(
                label = stringResource(R.string.compose_auto_audio_loops),
                value = state.loopsPerSync,
                range = 1..96,
                onValueChanged = actions::onLoopsPerSyncChange,
            )
            Text(
                stringResource(
                    R.string.compose_status,
                    actions.readLastPauseReason().ifBlank {
                        stringResource(R.string.compose_ready)
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { actions.onEnabledChange(false) },
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
