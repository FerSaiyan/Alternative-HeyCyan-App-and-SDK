package com.fersaiyan.cyanbridge.ui.glasses

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fersaiyan.cyanbridge.ui.theme.CyanAccent
import com.fersaiyan.cyanbridge.ui.theme.Danger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassesScreen(viewModel: GlassesViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val advancedExpanded = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshConnectionState()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "CyanBridge",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            MeetingRecordingBanner(
                isRecording = state.isRecording,
                source = state.recordingSource,
                onStop = { viewModel.stopMeetingCapture() },
            )

            if (state.isRecording) {
                Spacer(modifier = Modifier.height(8.dp))
            }

            StatusCard(state = state)

            TransferProgressCard(state = state)

            ConnectionSection(viewModel = viewModel)

            MeetingCaptureSection(viewModel = viewModel, state = state)

            AiAssistantSection(viewModel = viewModel, state = state)

            MediaControlsSection(viewModel = viewModel)

            AdvancedSection(
                expandedState = advancedExpanded,
                viewModel = viewModel,
                state = state,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Meeting recording banner
// ---------------------------------------------------------------------------

@Composable
private fun MeetingRecordingBanner(
    isRecording: Boolean,
    source: String,
    onStop: () -> Unit,
) {
    AnimatedVisibility(visible = isRecording) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Danger),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Recording active\u00B7 $source",
                    color = MaterialTheme.colorScheme.background,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = Danger,
                    ),
                ) {
                    Text("Stop")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Status Card
// ---------------------------------------------------------------------------

@Composable
private fun StatusCard(state: GlassesUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "GLASSES STATUS",
                color = CyanAccent,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = (1.2).sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.isConnected) CyanAccent
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (state.isConnected) "Connected" else "Disconnected",
                            color = if (state.isConnected) CyanAccent
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 18.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Class: ${state.deviceClass}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Battery: ${state.batteryLevel?.let { "$it%" } ?: "--%"}",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Storage: ${state.storageInfo}",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Transfer Progress Card
// ---------------------------------------------------------------------------

@Composable
private fun TransferProgressCard(state: GlassesUiState) {
    AnimatedVisibility(visible = state.isTransferring) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "SYNC PROGRESS",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (1.2).sp,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Photos: ${state.transferPhotos}  Videos: ${state.transferVideos}  Audio: ${state.transferAudio}",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { state.transferProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.transferDetail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Connection Section
// ---------------------------------------------------------------------------

@Composable
private fun ConnectionSection(viewModel: GlassesViewModel) {
    SectionHeader("CONNECTION")
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashboardButton(
            text = "Scan",
            icon = Icons.Filled.Search,
            modifier = Modifier.weight(1f),
            onClick = { viewModel.startAutoPair() },
        )
        DashboardButton(
            text = "Reconnect",
            icon = Icons.Filled.Refresh,
            modifier = Modifier.weight(1f),
            outlined = true,
            onClick = { viewModel.reconnect() },
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    DashboardButton(
        text = "Disconnect",
        icon = Icons.Filled.Delete,
        modifier = Modifier.fillMaxWidth(),
        outlined = true,
        dangerColor = true,
        onClick = { viewModel.disconnect() },
    )
    Spacer(modifier = Modifier.height(16.dp))
}

// ---------------------------------------------------------------------------
// Meeting Capture Section
// ---------------------------------------------------------------------------

@Composable
private fun MeetingCaptureSection(
    viewModel: GlassesViewModel,
    state: GlassesUiState,
) {
    val timerOptions = listOf("No timer", "1 min", "2 min", "5 min", "10 min", "30 min")
    val timerDurations = listOf(null, 60L, 120L, 300L, 600L, 1800L)
    var selectedTimer by remember { mutableStateOf(0) }
    var timerDropdownOpen by remember { mutableStateOf(false) }

    SectionHeader("MEETING CAPTURE", accent = true)
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashboardButton(
            text = "Start",
            icon = Icons.Filled.Check,
            modifier = Modifier.weight(1f),
            onClick = { viewModel.startMeetingCapture(timerDurations[selectedTimer]) },
        )
        DashboardButton(
            text = "Stop",
            icon = Icons.Filled.Delete,
            modifier = Modifier.weight(1f),
            outlined = true,
            dangerColor = true,
            onClick = { viewModel.stopMeetingCapture() },
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Timer:",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        TextButton(onClick = { timerDropdownOpen = !timerDropdownOpen }) {
            Text(
                text = timerOptions[selectedTimer],
                color = CyanAccent,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = CyanAccent,
            )
        }
    }
    if (timerDropdownOpen) {
        Column(modifier = Modifier.padding(start = 16.dp)) {
            timerOptions.forEachIndexed { index, option ->
                TextButton(onClick = {
                    selectedTimer = index
                    timerDropdownOpen = false
                }) {
                    Text(
                        text = option,
                        color = if (index == selectedTimer) CyanAccent
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Text(
        text = "Source: ${state.recordingSource}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(modifier = Modifier.height(16.dp))
}

// ---------------------------------------------------------------------------
// AI Assistant Section
// ---------------------------------------------------------------------------

@Composable
private fun AiAssistantSection(
    viewModel: GlassesViewModel,
    state: GlassesUiState,
) {
    val context = LocalContext.current

    SectionHeader("AI ASSISTANT", accent = true)
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashboardButton(
            text = "Gemini",
            icon = Icons.Filled.Star,
            modifier = Modifier.weight(1f),
            onClick = {
                com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs.setProvider(
                    context,
                    com.fersaiyan.cyanbridge.ai.router.AiProviderType.CLI_RELAY,
                )
                com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs.setRelayBackend(
                    context,
                    com.fersaiyan.cyanbridge.ai.router.CliRelayBackend.GEMINI,
                )
                Toast.makeText(context, "Gemini selected", Toast.LENGTH_SHORT).show()
            },
        )
        DashboardButton(
            text = "ChatGPT",
            icon = Icons.Filled.List,
            modifier = Modifier.weight(1f),
            outlined = true,
            onClick = {
                com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs.setProvider(
                    context,
                    com.fersaiyan.cyanbridge.ai.router.AiProviderType.CLI_RELAY,
                )
                com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs.setRelayBackend(
                    context,
                    com.fersaiyan.cyanbridge.ai.router.CliRelayBackend.CODEX,
                )
                Toast.makeText(context, "ChatGPT selected", Toast.LENGTH_SHORT).show()
            },
        )
        DashboardButton(
            text = "Chosen Provider",
            icon = Icons.Filled.Settings,
            modifier = Modifier.weight(1f),
            outlined = true,
            onClick = {
                val provider = com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs.getProvider(context)
                Toast.makeText(context, "Provider: ${provider.label}", Toast.LENGTH_SHORT).show()
            },
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashboardButton(
            text = "Test AI Voice",
            icon = Icons.Filled.Home,
            modifier = Modifier.weight(1f),
            outlined = true,
            onClick = {
                Toast.makeText(context, "Test AI Voice Question", Toast.LENGTH_SHORT).show()
            },
        )
        DashboardButton(
            text = "Test Image AI",
            icon = Icons.Filled.List,
            modifier = Modifier.weight(1f),
            outlined = true,
            onClick = {
                Toast.makeText(context, "Test Image AI description", Toast.LENGTH_SHORT).show()
            },
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Image automation",
            color = MaterialTheme.colorScheme.onBackground,
        )
        Switch(
            checked = state.imageAutomationEnabled,
            onCheckedChange = { viewModel.setImageAutomationEnabled(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = CyanAccent,
                checkedTrackColor = CyanAccent.copy(alpha = 0.3f),
            ),
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
}

// ---------------------------------------------------------------------------
// Media Controls Section
// ---------------------------------------------------------------------------

@Composable
private fun MediaControlsSection(viewModel: GlassesViewModel) {
    val context = LocalContext.current

    SectionHeader("MEDIA CONTROLS")
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashboardButton(
            text = "Photo",
            icon = Icons.Filled.Home,
            modifier = Modifier.weight(1f),
            onClick = { viewModel.sendPhotoCommand() },
        )
        DashboardButton(
            text = "Video",
            icon = Icons.Filled.Home,
            modifier = Modifier.weight(1f),
            onClick = { viewModel.sendVideoCommand() },
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashboardButton(
            text = "Audio",
            icon = Icons.Filled.List,
            modifier = Modifier.weight(1f),
            onClick = { viewModel.sendAudioCommand() },
        )
        DashboardButton(
            text = "Count",
            icon = Icons.Filled.Add,
            modifier = Modifier.weight(1f),
            onClick = {
                viewModel.requestMediaCount()
                Toast.makeText(context, "Requesting media count...", Toast.LENGTH_SHORT).show()
            },
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    DashboardButton(
        text = "Sync Data (P2P)",
        icon = Icons.AutoMirrored.Filled.Send,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            Toast.makeText(context, "Starting P2P sync...", Toast.LENGTH_SHORT).show()
        },
    )
    Spacer(modifier = Modifier.height(16.dp))
}

// ---------------------------------------------------------------------------
// Advanced Section (collapsible)
// ---------------------------------------------------------------------------

@Composable
private fun AdvancedSection(
    expandedState: androidx.compose.runtime.MutableState<Boolean>,
    viewModel: GlassesViewModel,
    state: GlassesUiState,
) {
    val context = LocalContext.current

    TextButton(
        onClick = { expandedState.value = !expandedState.value },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (expandedState.value) "Advanced \u25BE" else "Advanced \u25B8",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    AnimatedVisibility(
        visible = expandedState.value,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Column {
            LocalAgentSubSection(
                agentStatus = state.agentStatus,
                agentLastError = state.agentLastError,
                onStart = {
                    val result = com.fersaiyan.cyanbridge.localagent.LocalAgentController.start(context)
                    Toast.makeText(context, result.userMessage, Toast.LENGTH_SHORT).show()
                },
                onStop = {
                    val result = com.fersaiyan.cyanbridge.localagent.LocalAgentController.stop(context)
                    Toast.makeText(context, result.userMessage, Toast.LENGTH_SHORT).show()
                },
                onDemo = {
                    val result = com.fersaiyan.cyanbridge.localagent.LocalAgentController.demo(context)
                    Toast.makeText(context, result.userMessage, Toast.LENGTH_SHORT).show()
                },
            )

            DeviceInfoSubSection(viewModel = viewModel)

            DevToolsSubSection(viewModel = viewModel)
        }
    }
}

// ---------------------------------------------------------------------------
// Local Agent sub-section
// ---------------------------------------------------------------------------

@Composable
private fun LocalAgentSubSection(
    agentStatus: String,
    agentLastError: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDemo: () -> Unit,
) {
    SectionHeader("LOCAL AGENT")
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Status: $agentStatus",
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 14.sp,
    )
    Text(
        text = "Last error: $agentLastError",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashboardButton(
            text = "Start",
            icon = Icons.Filled.Check,
            modifier = Modifier.weight(1f),
            onClick = onStart,
        )
        DashboardButton(
            text = "Stop",
            icon = Icons.Filled.Delete,
            modifier = Modifier.weight(1f),
            dangerColor = true,
            onClick = onStop,
        )
        DashboardButton(
            text = "Demo",
            icon = Icons.Filled.Star,
            modifier = Modifier.weight(1f),
            onClick = onDemo,
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
}

// ---------------------------------------------------------------------------
// Device Info sub-section
// ---------------------------------------------------------------------------

@Composable
private fun DeviceInfoSubSection(viewModel: GlassesViewModel) {
    SectionHeader("DEVICE INFO")
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashboardButton(
            text = "Battery",
            icon = Icons.Filled.Home,
            modifier = Modifier.weight(1f),
            outlined = true,
            onClick = { viewModel.requestBattery() },
        )
        DashboardButton(
            text = "Version",
            icon = Icons.Filled.List,
            modifier = Modifier.weight(1f),
            outlined = true,
            onClick = { viewModel.requestVersion() },
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashboardButton(
            text = "Sync Time",
            icon = Icons.Filled.Refresh,
            modifier = Modifier.weight(1f),
            outlined = true,
            onClick = { viewModel.syncTime() },
        )
        DashboardButton(
            text = "Volume",
            icon = Icons.Filled.Settings,
            modifier = Modifier.weight(1f),
            outlined = true,
            onClick = { viewModel.requestVolume() },
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
}

// ---------------------------------------------------------------------------
// Dev Tools sub-section
// ---------------------------------------------------------------------------

@Composable
private fun DevToolsSubSection(viewModel: GlassesViewModel) {
    val context = LocalContext.current

    SectionHeader("DEV TOOLS")
    Spacer(modifier = Modifier.height(4.dp))
    DashboardButton(
        text = "Add Listener",
        icon = Icons.Filled.Add,
        modifier = Modifier.fillMaxWidth(),
        outlined = true,
        onClick = {
            viewModel.addDeviceListener()
            Toast.makeText(context, "Device listener registered", Toast.LENGTH_SHORT).show()
        },
    )
    Spacer(modifier = Modifier.height(4.dp))
    DashboardButton(
        text = "BT",
        icon = Icons.Filled.Settings,
        modifier = Modifier.fillMaxWidth(),
        outlined = true,
        enabled = false,
        onClick = {},
    )
    Spacer(modifier = Modifier.height(4.dp))
    DashboardButton(
        text = "OTA Info",
        icon = Icons.Filled.List,
        modifier = Modifier.fillMaxWidth(),
        outlined = true,
        enabled = false,
        onClick = {},
    )
    Spacer(modifier = Modifier.height(4.dp))
    DashboardButton(
        text = "Pull OTA test",
        icon = Icons.Filled.Refresh,
        modifier = Modifier.fillMaxWidth(),
        outlined = true,
        enabled = false,
        onClick = {},
    )
    Spacer(modifier = Modifier.height(24.dp))
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(text: String, accent: Boolean = false) {
    Text(
        text = text,
        color = if (accent) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (accent) FontWeight.Bold else FontWeight.Normal,
        letterSpacing = (1.2).sp,
    )
}

@Composable
private fun DashboardButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    outlined: Boolean = false,
    dangerColor: Boolean = false,
    enabled: Boolean = true,
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        dangerColor -> Danger
        outlined -> CyanAccent
        else -> MaterialTheme.colorScheme.background
    }
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        outlined -> MaterialTheme.colorScheme.surfaceVariant
        else -> CyanAccent
    }

    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(48.dp),
            shape = RoundedCornerShape(8.dp),
            enabled = enabled,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier.height(48.dp),
            shape = RoundedCornerShape(8.dp),
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
