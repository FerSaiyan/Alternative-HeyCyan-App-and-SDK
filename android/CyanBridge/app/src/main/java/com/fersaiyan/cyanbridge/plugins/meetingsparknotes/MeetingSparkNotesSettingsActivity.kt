package com.fersaiyan.cyanbridge.plugins.meetingsparknotes

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

class MeetingSparkNotesSettingsActivity : AppCompatActivity() {

    private lateinit var composeView: ComposeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        composeView = installComposeHostWithLegacyAdapter(R.layout.activity_meeting_spark_notes_settings)

        setThemedComposeContent(composeView) {
            MeetingSparkNotesSettingsScreen(
                onBack = ::finish,
                onDeactivate = { MeetingSparkNotesService.deactivate(this) },
                onSummarize = { MeetingSparkNotesService.summarize(this) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingSparkNotesSettingsScreen(
    onBack: () -> Unit,
    onDeactivate: () -> Unit,
    onSummarize: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var enabled by remember { mutableStateOf(MeetingSparkNotesPreferences.isEnabled(context)) }
    var summaryStyle by remember { mutableStateOf(MeetingSparkNotesPreferences.getSummaryStyle(context)) }
    var includeParticipants by remember { mutableStateOf(MeetingSparkNotesPreferences.isIncludeParticipants(context)) }
    var includeActionItems by remember { mutableStateOf(MeetingSparkNotesPreferences.isIncludeActionItems(context)) }
    var maxHistory by remember { mutableIntStateOf(MeetingSparkNotesPreferences.getMaxHistory(context)) }
    var customPrompt by remember { mutableStateOf(MeetingSparkNotesPreferences.getCustomPrompt(context)) }

    val styleOptions = listOf("concise", "detailed", "action_focused")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_plugin_settings_title, stringResource(R.string.compose_plugin_name_meeting_spark_notes))) },
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
                        Text(stringResource(R.string.compose_meeting_enabled), modifier = Modifier.weight(1f))
                        Switch(
                            checked = enabled,
                            onCheckedChange = { newValue ->
                                enabled = newValue
                                MeetingSparkNotesPreferences.setEnabled(context, newValue)
                                CommunityPluginPrefs.setNativePluginEnabled(
                                    context,
                                    NativePluginIds.MEETING_SPARK_NOTES,
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
                pluginId = NativePluginIds.MEETING_SPARK_NOTES,
                pluginTitle = stringResource(R.string.compose_plugin_name_meeting_spark_notes),
            )

            // Section: Summary Style
            SectionTitle(stringResource(R.string.compose_summary_style))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_summary_style_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        styleOptions.forEach { style ->
                            FilterChip(
                                selected = summaryStyle == style,
                                onClick = {
                                    summaryStyle = style
                                    MeetingSparkNotesPreferences.setSummaryStyle(context, style)
                                },
                                 label = {
                                     Text(
                                         stringResource(
                                             when (style) {
                                                 "concise" -> R.string.compose_summary_style_concise
                                                 "detailed" -> R.string.compose_summary_style_detailed
                                                 else -> R.string.compose_summary_style_action_focused
                                             },
                                         ),
                                     )
                                 },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }
            }

            // Section: Content Options
            SectionTitle(stringResource(R.string.compose_content_options))
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
                        Text(stringResource(R.string.compose_include_participants), modifier = Modifier.weight(1f))
                        Switch(
                            checked = includeParticipants,
                            onCheckedChange = { newValue ->
                                includeParticipants = newValue
                                MeetingSparkNotesPreferences.setIncludeParticipants(context, newValue)
                            },
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.compose_include_action_items), modifier = Modifier.weight(1f))
                        Switch(
                            checked = includeActionItems,
                            onCheckedChange = { newValue ->
                                includeActionItems = newValue
                                MeetingSparkNotesPreferences.setIncludeActionItems(context, newValue)
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
                        stringResource(R.string.compose_meeting_instructions_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = customPrompt,
                        onValueChange = { newValue ->
                            if (newValue.length <= 1500) {
                                customPrompt = newValue
                                MeetingSparkNotesPreferences.setCustomPrompt(context, newValue)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                         label = { Text(stringResource(R.string.compose_meeting_instructions)) },
                         placeholder = { Text(stringResource(R.string.compose_meeting_hint)) },
                        minLines = 3,
                        maxLines = 6,
                        supportingText = { Text("${customPrompt.length}/1500") },
                    )
                }
            }

            // Section: History
            SectionTitle(stringResource(R.string.compose_meeting_history))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_max_stored_summaries, maxHistory),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = maxHistory.toFloat(),
                        onValueChange = { newValue ->
                            maxHistory = newValue.toInt()
                            MeetingSparkNotesPreferences.setMaxHistory(context, newValue.toInt())
                        },
                        valueRange = 10f..200f,
                        steps = 18,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            MeetingSparkNotesStore().clear(context)
                            Toast.makeText(context, context.getString(R.string.compose_history_cleared), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.compose_clear_history))
                    }
                }
            }

            // Section: Actions
            SectionTitle(stringResource(R.string.compose_actions))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = onSummarize,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.compose_summarize_current_meeting))
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
