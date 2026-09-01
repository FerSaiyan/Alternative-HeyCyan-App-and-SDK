package com.fersaiyan.cyanbridge.plugins.handsfreetranslator

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs
import com.fersaiyan.cyanbridge.ui.installComposeHostWithLegacyAdapter
import com.fersaiyan.cyanbridge.ui.NativePluginShortcutPreference
import com.fersaiyan.cyanbridge.ui.setThemedComposeContent

class HandsFreeTranslatorSettingsActivity : AppCompatActivity() {

    private lateinit var composeView: ComposeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        composeView = installComposeHostWithLegacyAdapter(R.layout.activity_hands_free_translator_settings)

        setThemedComposeContent(composeView) {
            HandsFreeTranslatorSettingsScreen(
                onBack = ::finish,
                onDeactivate = { HandsFreeTranslatorService.stop(this) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandsFreeTranslatorSettingsScreen(
    onBack: () -> Unit,
    onDeactivate: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var enabled by remember { mutableStateOf(HandsFreeTranslatorPreferences.isEnabled(context)) }
    var sourceLanguage by remember { mutableStateOf(HandsFreeTranslatorPreferences.getSourceLanguage(context)) }
    var targetLanguage by remember { mutableStateOf(HandsFreeTranslatorPreferences.getTargetLanguage(context)) }
    var autoDetect by remember { mutableStateOf(HandsFreeTranslatorPreferences.isAutoDetect(context)) }
    var speakTranslation by remember { mutableStateOf(HandsFreeTranslatorPreferences.isSpeakTranslation(context)) }
    var maxHistory by remember { mutableIntStateOf(HandsFreeTranslatorPreferences.getMaxHistory(context)) }
    var customPrompt by remember { mutableStateOf(HandsFreeTranslatorPreferences.getCustomPrompt(context)) }

    val languageOptions = listOf("en", "es", "fr", "de", "it", "pt", "zh", "ja", "ko")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_plugin_settings_title, stringResource(R.string.compose_plugin_name_handsfree_translator))) },
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
        ) {
            // Section: General
            SectionTitle(stringResource(R.string.compose_general))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.compose_handsfree_enabled), modifier = Modifier.weight(1f))
                        Switch(
                            checked = enabled,
                            onCheckedChange = { newValue ->
                                enabled = newValue
                                HandsFreeTranslatorPreferences.setEnabled(context, newValue)
                                CommunityPluginPrefs.setNativePluginEnabled(
                                    context,
                                    NativePluginIds.HANDS_FREE_TRANSLATOR,
                                    newValue,
                                )
                                if (!newValue) onDeactivate()
                            },
                        )
                    }
                }
            }

            SectionTitle(stringResource(R.string.compose_glasses_tab))
            NativePluginShortcutPreference(
                pluginId = NativePluginIds.HANDS_FREE_TRANSLATOR,
                pluginTitle = stringResource(R.string.compose_plugin_name_handsfree_translator),
            )

            // Section: Language
            SectionTitle(stringResource(R.string.compose_language))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.compose_auto_detect_language), modifier = Modifier.weight(1f))
                        Switch(
                            checked = autoDetect,
                            onCheckedChange = { newValue ->
                                autoDetect = newValue
                                HandsFreeTranslatorPreferences.setAutoDetect(context, newValue)
                            },
                        )
                    }

                    if (!autoDetect) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.compose_source_language),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            languageOptions.take(5).forEach { lang ->
                                FilterChip(
                                    selected = sourceLanguage == lang,
                                    onClick = {
                                        sourceLanguage = lang
                                        HandsFreeTranslatorPreferences.setSourceLanguage(context, lang)
                                    },
                                    label = { Text(lang.uppercase()) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        stringResource(R.string.compose_target_language),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        languageOptions.take(5).forEach { lang ->
                            FilterChip(
                                selected = targetLanguage == lang,
                                onClick = {
                                    targetLanguage = lang
                                    HandsFreeTranslatorPreferences.setTargetLanguage(context, lang)
                                },
                                label = { Text(lang.uppercase()) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }
            }

            // Section: Output
            SectionTitle(stringResource(R.string.compose_output))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.compose_speak_translations), modifier = Modifier.weight(1f))
                        Switch(
                            checked = speakTranslation,
                            onCheckedChange = { newValue ->
                                speakTranslation = newValue
                                HandsFreeTranslatorPreferences.setSpeakTranslation(context, newValue)
                            },
                        )
                    }

                }
            }

            // Section: Custom Instructions
            SectionTitle(stringResource(R.string.compose_custom_instructions))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_translation_instructions_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = customPrompt,
                        onValueChange = { newValue ->
                            if (newValue.length <= 1000) {
                                customPrompt = newValue
                                HandsFreeTranslatorPreferences.setCustomPrompt(context, newValue)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                         label = { Text(stringResource(R.string.compose_translation_instructions)) },
                         placeholder = { Text(stringResource(R.string.compose_translation_hint)) },
                        minLines = 2,
                        maxLines = 4,
                        supportingText = { Text("${customPrompt.length}/1000") },
                    )
                }
            }

            // Section: History
            SectionTitle(stringResource(R.string.compose_translation_history))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_max_stored_translations, maxHistory),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = maxHistory.toFloat(),
                        onValueChange = { newValue ->
                            maxHistory = newValue.toInt()
                            HandsFreeTranslatorPreferences.setMaxHistory(context, newValue.toInt())
                        },
                        valueRange = 50f..500f,
                        steps = 44,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            HandsFreeTranslatorStore().clear(context)
                            Toast.makeText(context, context.getString(R.string.compose_history_cleared), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.compose_clear_history))
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp),
    )
}
