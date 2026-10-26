package com.fersaiyan.cyanbridge.ai.image

import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.fersaiyan.cyanbridge.ui.nav.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.tasker.TaskerIntegrationManager
import com.fersaiyan.cyanbridge.tasker.TaskerProfileGuidance
import com.fersaiyan.cyanbridge.shared.automation.AssistantAutomationPlatform
import com.fersaiyan.cyanbridge.shared.automation.AssistantAutomationSetupViewModel
import com.fersaiyan.cyanbridge.shared.automation.AssistantAutomationSnapshot
import com.fersaiyan.cyanbridge.shared.automation.AssistantSetupCheck
import com.fersaiyan.cyanbridge.shared.automation.ExternalAssistantSetupUiState
import com.fersaiyan.cyanbridge.shared.automation.ImportTaskerProfile
import com.fersaiyan.cyanbridge.shared.automation.IntegrationHealth
import com.fersaiyan.cyanbridge.shared.automation.IntegrationState
import com.fersaiyan.cyanbridge.ui.CommunityPluginsActivity
import com.fersaiyan.cyanbridge.ui.collectScreenEffects
import java.io.File

/**
 * Unified Tasker integration center.
 *
 * This intentionally reuses the original Gemini/ChatGPT setup and verification path. The same
 * screen is reachable from the glasses flow and from Settings, where it also exposes the shared
 * Tasker/AutoInput environment and the Tasker-dependent CyanBridge plugins.
 */
class ExternalAssistantAutomationSetupDestination : NavScreen() {
    private val viewModel by viewModels<AssistantAutomationSetupViewModel> {
        viewModelFactory { initializer { AssistantAutomationSetupViewModel(AndroidAssistantAutomationPlatform(applicationContext)) } }
    }
    private var pendingProfileAsset: String? = null
    private var pendingProfileFileName: String? = null

    private val saveProfileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/xml"),
    ) { uri ->
        val assetName = pendingProfileAsset
        val fileName = pendingProfileFileName
        pendingProfileAsset = null
        pendingProfileFileName = null
        if (uri == null || assetName == null || fileName == null) return@registerForActivityResult
        runCatching {
            assets.open(assetName).use { input ->
                contentResolver.openOutputStream(uri)?.use(input::copyTo)
                    ?: error("The selected folder could not create the file")
            }
        }.onSuccess {
            TaskerProfileGuidance.showSavedDialog(activity, fileName, uri)
        }.onFailure { error ->
            runCatching { contentResolver.delete(uri, null, null) }
            Toast.makeText(this, "Could not save the Tasker profile: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingProfileAsset = savedInstanceState?.getString(STATE_PENDING_PROFILE_ASSET)
        pendingProfileFileName = savedInstanceState?.getString(STATE_PENDING_PROFILE_FILE_NAME)
        collectScreenEffects(viewModel.effectFlow) { effect ->
            if (effect is ImportTaskerProfile) importProfile(effect.assetName)
        }
        viewModel.refresh()
        setContent {
            val state by viewModel.state.collectAsState()
            ExternalAssistantAutomationSetupScreen(
                state = state,
                onBack = ::finish,
                onChooseDefaultAssistant = ::openDefaultAssistantSettings,
                onImportProfile = viewModel::importProfile,
                onVerifyProfile = viewModel::verifyProfile,
                onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onOpenPlugins = { startActivity(Intent(this, CommunityPluginsActivity::class.java)) },
                onWatchTutorial = { TaskerProfileGuidance.openTutorial(this) },
                onTestVoice = viewModel::testVoice,
                onRefresh = viewModel::refresh,
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PENDING_PROFILE_ASSET, pendingProfileAsset)
        outState.putString(STATE_PENDING_PROFILE_FILE_NAME, pendingProfileFileName)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun openDefaultAssistantSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    /** Opens Tasker's importer with the bundled profile, or saves it where the user chooses. */
    private fun importProfile(assetName: String) {
        val fileName = assetName.substringAfterLast('/').removeSuffix(".xml") + ".prf.xml"
        val profileFile = File(cacheDir, "tasker/$fileName")
        val imported = runCatching {
            profileFile.parentFile?.mkdirs()
            assets.open(assetName).use { input -> profileFile.outputStream().use(input::copyTo) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", profileFile)
            TaskerProfileGuidance.openImporter(this, uri, fileName)
        }.getOrDefault(false)
        if (!imported) {
            pendingProfileAsset = assetName
            val fallbackFileName = fileName.removeSuffix(".prf.xml") + "_${System.currentTimeMillis()}.prf.xml"
            pendingProfileFileName = fallbackFileName
            Toast.makeText(this, "Tasker could not import directly. Choose where to save the profile, then import it from Tasker.", Toast.LENGTH_LONG).show()
            saveProfileLauncher.launch(fallbackFileName)
        }
    }

    private companion object {
        const val STATE_PENDING_PROFILE_ASSET = "pending_profile_asset"
        const val STATE_PENDING_PROFILE_FILE_NAME = "pending_profile_file_name"
    }
}

/** The phone inspection and Tasker broadcasts behind the shared setup screen. */
private class AndroidAssistantAutomationPlatform(private val context: Context) : AssistantAutomationPlatform {
    override fun inspect(): AssistantAutomationSnapshot {
        val capability = ExternalAssistantAutomationInspector.inspect(context)
        val tasker = TaskerIntegrationManager.inspect(context)
        return AssistantAutomationSnapshot(
            taskerInstalled = tasker.taskerInstalled,
            taskerVersion = tasker.taskerVersion,
            taskerPackage = ExternalImageAutomationIntents.TASKER_PACKAGE,
            taskerAccessibilityEnabled = tasker.taskerAccessibilityEnabled,
            autoInputInstalled = tasker.autoInputInstalled,
            autoInputVersion = tasker.autoInputVersion,
            autoInputAccessibilityEnabled = tasker.autoInputAccessibilityEnabled,
            integrations = tasker.integrations,
            nextAction = tasker.nextAction,
            targetId = capability.target.takeUnless { it == ImageAutomationTarget.NONE }?.wireName,
            targetLabel = capability.target.label,
            targetPackage = capability.targetPackage,
            defaultAssistantPackage = DefaultAssistantResolver.packageName(context),
            assistantTaskerInstalled = capability.taskerInstalled,
            profileCompatible = capability.profileCompatible,
            phoneLocked = capability.phoneLocked,
            imageShareAvailable = capability.imageShareAvailable,
            voiceBlockingReason = ExternalAssistantAutomationPolicy.voiceBlockingReason(capability),
            lastImageState = ExternalImageAutomationStore.current(context)?.let {
                buildString {
                    append(it.state.stage.wireName)
                    it.state.error?.takeIf(String::isNotBlank)?.let { error -> append(" ($error)") }
                }
            },
        )
    }

    override fun beginProfileCheck(targetLabel: String) {
        val token = TaskerImageProfileStore.beginVerification(context)
        sendTaskerEvent("profile_check", targetLabel) { putExtra(ExternalImageAutomationIntents.EXTRA_PROFILE_TOKEN, token) }
    }

    override fun sendVoiceTest(targetLabel: String) = sendTaskerEvent("voice", targetLabel)

    private fun sendTaskerEvent(type: String, assistant: String, extras: Intent.() -> Unit = {}) {
        context.sendBroadcast(Intent(MainActivity.aiEventAction(context.packageName)).apply {
            setPackage(ExternalImageAutomationIntents.TASKER_PACKAGE)
            putExtra(ImageQuestionBroadcast.EXTRA_TYPE, type)
            putExtra(ImageQuestionBroadcast.EXTRA_ASSISTANT, assistant)
            extras()
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalAssistantAutomationSetupScreen(
    state: ExternalAssistantSetupUiState,
    onBack: () -> Unit,
    onChooseDefaultAssistant: () -> Unit,
    onImportProfile: () -> Unit,
    onVerifyProfile: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenPlugins: () -> Unit,
    onWatchTutorial: () -> Unit,
    onTestVoice: () -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasker integrations") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.compose_external_back))
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.compose_external_refresh_status))
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SetupSummaryCard(state) }
            item {
                Text(
                    text = "One place to diagnose Tasker, AutoInput, Gemini/ChatGPT image questions, Local Agent, AutoDiary and Visual Diary. CyanBridge keeps policy/model logic; Tasker executes or observes Android UI.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.migrationNotice?.let { notice ->
                item { MigrationNoticeCard(notice) }
            }
            item {
                Text(
                    text = "Setup and repair",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onChooseDefaultAssistant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.compose_external_choose_assistant))
                    }
                    OutlinedButton(
                        onClick = onImportProfile,
                        enabled = state.canImportProfile,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(state.profileActionLabel)
                    }
                    OutlinedButton(
                        onClick = onVerifyProfile,
                        enabled = state.canVerifyProfile,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.compose_external_verify_profile))
                    }
                    OutlinedButton(
                        onClick = onOpenAccessibility,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open Accessibility settings")
                    }
                    OutlinedButton(
                        onClick = onOpenPlugins,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open Plugins / Tasker profile downloads")
                    }
                    OutlinedButton(
                        onClick = onWatchTutorial,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Watch Tasker setup videos")
                    }
                    Button(
                        onClick = onTestVoice,
                        enabled = state.canTestVoice,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.compose_external_test_voice))
                    }
                }
            }
            item {
                Text(
                    text = "Environment checks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(state.checks) { check -> SetupCheckCard(check) }
            item {
                HorizontalDivider()
                Text(
                    text = "CyanBridge Tasker projects",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Gemini and ChatGPT report a real profile version. Local Agent, AutoDiary and Visual Diary currently expose environment readiness only, so CyanBridge will not claim a project version it cannot verify.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.integrations) { integration -> IntegrationStatusCard(integration) }
            state.lastImageState?.let { lastState ->
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.compose_external_last_state, lastState),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                TextButton(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.compose_external_refresh_status))
                }
            }
        }
    }
}

@Composable
private fun SetupSummaryCard(state: ExternalAssistantSetupUiState) {
    val containerColor = if (state.ready) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (state.ready) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (state.ready) Icons.Default.CheckCircle else Icons.Default.Assistant,
                contentDescription = null,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.targetLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = state.nextStep, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MigrationNoticeCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Default.Update, contentDescription = null)
            Column {
                Text("Profile update may be required", fontWeight = FontWeight.SemiBold)
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SetupCheckCard(check: AssistantSetupCheck) {
    val iconColor = if (check.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (check.passed) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = if (check.passed) "Passed" else "Needs attention",
                tint = iconColor,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(check.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun IntegrationStatusCard(integration: IntegrationState) {
    val healthy = integration.health == IntegrationHealth.READY || integration.health == IntegrationHealth.NOT_VERSIONED
    val tint = if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val status = when (integration.health) {
        IntegrationHealth.READY -> "Verified"
        IntegrationHealth.NEEDS_SETUP -> "Needs setup"
        IntegrationHealth.OUTDATED -> "Update required"
        IntegrationHealth.WRONG_PROFILE -> "Wrong profile"
        IntegrationHealth.NOT_SELECTED -> "Not selected"
        IntegrationHealth.ENVIRONMENT_BLOCKED -> "Environment blocked"
        IntegrationHealth.NOT_VERSIONED -> "Environment ready · profile not versioned"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (healthy) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = tint,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(integration.name, fontWeight = FontWeight.SemiBold)
                    Text(status, style = MaterialTheme.typography.labelSmall, color = tint)
                }
                if (integration.installedVersion != null || integration.requiredVersion != null) {
                    Text(
                        text = "Installed: ${integration.installedVersion ?: "not verified"} · Required: ${integration.requiredVersion ?: "not versioned"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    integration.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                integration.actionHint?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
