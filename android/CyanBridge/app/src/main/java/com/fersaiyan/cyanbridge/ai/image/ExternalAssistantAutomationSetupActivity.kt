package com.fersaiyan.cyanbridge.ai.image

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.tasker.IntegrationHealth
import com.fersaiyan.cyanbridge.tasker.IntegrationState
import com.fersaiyan.cyanbridge.tasker.TaskerIntegrationManager
import com.fersaiyan.cyanbridge.tasker.TaskerIntegrationStatus
import com.fersaiyan.cyanbridge.tasker.TaskerProfileGuidance
import com.fersaiyan.cyanbridge.ui.CommunityPluginsActivity
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Unified Tasker integration center.
 *
 * This intentionally reuses the original Gemini/ChatGPT setup and verification path. The same
 * screen is reachable from the glasses flow and from Settings, where it also exposes the shared
 * Tasker/AutoInput environment and the Tasker-dependent CyanBridge plugins.
 */
class ExternalAssistantAutomationSetupActivity : AppCompatActivity() {
    private var uiState by mutableStateOf(ExternalAssistantSetupUiState())
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
            TaskerProfileGuidance.showSavedDialog(this, fileName, uri)
        }.onFailure { error ->
            runCatching { contentResolver.delete(uri, null, null) }
            showLongToast("Could not save the Tasker profile: ${error.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingProfileAsset = savedInstanceState?.getString(STATE_PENDING_PROFILE_ASSET)
        pendingProfileFileName = savedInstanceState?.getString(STATE_PENDING_PROFILE_FILE_NAME)
        refreshSetupState()
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                ExternalAssistantAutomationSetupScreen(
                    state = uiState,
                    onBack = ::finish,
                    onChooseDefaultAssistant = ::openDefaultAssistantSettings,
                    onImportProfile = ::importMatchingProfile,
                    onVerifyProfile = ::verifyImportedProfile,
                    onOpenAccessibility = ::openAccessibilitySettings,
                    onOpenPlugins = ::openPlugins,
                    onWatchTutorial = { TaskerProfileGuidance.openTutorial(this) },
                    onTestVoice = ::testTaskerVoiceLaunch,
                    onRefresh = ::refreshSetupState,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PENDING_PROFILE_ASSET, pendingProfileAsset)
        outState.putString(STATE_PENDING_PROFILE_FILE_NAME, pendingProfileFileName)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        refreshSetupState()
    }

    private fun openDefaultAssistantSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openPlugins() {
        startActivity(Intent(this, CommunityPluginsActivity::class.java))
    }

    private fun importMatchingProfile() {
        val capability = ExternalAssistantAutomationInspector.inspect(this)
        val assetName = when (capability.target) {
            ImageAutomationTarget.GEMINI -> "tasker/CyanBridge_Gemini.xml"
            ImageAutomationTarget.CHATGPT -> "tasker/CyanBridge_ChatGPT.xml"
            ImageAutomationTarget.NONE -> {
                showLongToast("Set Gemini or ChatGPT as the default assistant first.")
                return
            }
        }
        if (!capability.taskerInstalled) {
            showLongToast("Install Tasker before importing the profile.")
            return
        }

        val fileName = profileFileName(assetName)
        val profileFile = File(cacheDir, "tasker/$fileName")
        val imported = runCatching {
            profileFile.parentFile?.mkdirs()
            assets.open(assetName).use { input ->
                profileFile.outputStream().use(input::copyTo)
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", profileFile)
            TaskerProfileGuidance.openImporter(this, uri, fileName)
        }.getOrDefault(false)
        if (!imported) {
            pendingProfileAsset = assetName
            val fallbackFileName = fileName.removeSuffix(".prf.xml") +
                "_${System.currentTimeMillis()}.prf.xml"
            pendingProfileFileName = fallbackFileName
            showLongToast("Tasker could not import directly. Choose where to save the profile, then import it from Tasker.")
            saveProfileLauncher.launch(fallbackFileName)
        }
    }

    private fun profileFileName(assetName: String): String =
        assetName.substringAfterLast('/').removeSuffix(".xml") + ".prf.xml"

    private fun verifyImportedProfile() {
        val capability = ExternalAssistantAutomationInspector.inspect(this)
        if (capability.target == ImageAutomationTarget.NONE) {
            showLongToast("Set Gemini or ChatGPT as the default assistant first.")
            return
        }
        if (!capability.taskerInstalled) {
            showLongToast("Tasker is not installed.")
            return
        }

        val token = TaskerImageProfileStore.beginVerification(this)
        sendBroadcast(Intent(MainActivity.aiEventAction(packageName)).apply {
            setPackage(ExternalImageAutomationIntents.TASKER_PACKAGE)
            putExtra(ImageQuestionBroadcast.EXTRA_TYPE, "profile_check")
            putExtra(ImageQuestionBroadcast.EXTRA_ASSISTANT, capability.target.label)
            putExtra(ExternalImageAutomationIntents.EXTRA_PROFILE_TOKEN, token)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        })
        Toast.makeText(this, "Waiting for the Tasker profile handshake...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            delay(1_500L)
            refreshSetupState()
            val verified = ExternalAssistantAutomationInspector.inspect(this@ExternalAssistantAutomationSetupActivity)
                .profileCompatible
            showLongToast(
                if (verified) {
                    "${capability.target.label} Tasker profile verified."
                } else {
                    "No compatible profile response received. Import/update the current profile and try again."
                },
            )
        }
    }

    private fun testTaskerVoiceLaunch() {
        val capability = ExternalAssistantAutomationInspector.inspect(this)
        val reason = ExternalAssistantAutomationPolicy.voiceBlockingReason(capability)
        if (reason != null) {
            showLongToast(reason)
            return
        }
        sendBroadcast(Intent(MainActivity.aiEventAction(packageName)).apply {
            setPackage(ExternalImageAutomationIntents.TASKER_PACKAGE)
            putExtra(ImageQuestionBroadcast.EXTRA_TYPE, "voice")
            putExtra(ImageQuestionBroadcast.EXTRA_ASSISTANT, capability.target.label)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        })
    }

    private fun refreshSetupState() {
        val capability = ExternalAssistantAutomationInspector.inspect(this)
        val taskerStatus = TaskerIntegrationManager.inspect(this)
        val defaultPackage = DefaultAssistantResolver.packageName(this)
        val selectedIntegration = taskerStatus.integrations.firstOrNull { it.id == capability.target.wireName }
        val checks = listOf(
            AssistantSetupCheck(
                title = "Tasker installed",
                passed = taskerStatus.taskerInstalled,
                detail = taskerStatus.taskerVersion?.let { "Version $it" }
                    ?: ExternalImageAutomationIntents.TASKER_PACKAGE,
            ),
            AssistantSetupCheck(
                title = "Tasker Accessibility Access",
                passed = taskerStatus.taskerAccessibilityEnabled,
                detail = if (taskerStatus.taskerAccessibilityEnabled) {
                    "Enabled. Required for reliable foreground-app detection (%WIN)."
                } else {
                    "Enable it through Tasker's own Accessibility Access disclosure flow; direct Android settings writes are not sufficient."
                },
            ),
            AssistantSetupCheck(
                title = "AutoInput installed",
                passed = taskerStatus.autoInputInstalled,
                detail = taskerStatus.autoInputVersion?.let { "Version $it" }
                    ?: "Install AutoInput; if actions silently no-op, restore the purchased entitlement through the official AutoApps app.",
            ),
            AssistantSetupCheck(
                title = "AutoInput Accessibility Access",
                passed = taskerStatus.autoInputAccessibilityEnabled,
                detail = "Required for observing, clicking and typing in Android/external-app UI.",
            ),
            AssistantSetupCheck(
                title = "Android default assistant",
                passed = capability.target != ImageAutomationTarget.NONE,
                detail = when (capability.target) {
                    ImageAutomationTarget.NONE -> "${defaultPackage ?: "Not detected"}; choose Gemini or ChatGPT"
                    else -> "${capability.target.label} ($defaultPackage)"
                },
            ),
            AssistantSetupCheck(
                title = "Default assistant app installed",
                passed = capability.targetPackage != null,
                detail = capability.targetPackage ?: "No supported assistant package",
            ),
            AssistantSetupCheck(
                title = "Current assistant Tasker profile",
                passed = capability.profileCompatible,
                detail = selectedIntegration?.detail ?: "Choose Gemini or ChatGPT as the default assistant to verify its profile.",
            ),
            AssistantSetupCheck(
                title = "Phone unlocked",
                passed = !capability.phoneLocked,
                detail = "External UI automation fails immediately while locked.",
            ),
            AssistantSetupCheck(
                title = "Assistant accepts image shares",
                passed = capability.imageShareAvailable,
                detail = capability.targetPackage ?: "No supported assistant selected",
            ),
        )
        val firstFailure = checks.firstOrNull { !it.passed }
        val session = ExternalImageAutomationStore.current(this)
        val migrationNotice = selectedIntegration?.takeIf {
            it.health == IntegrationHealth.OUTDATED ||
                it.health == IntegrationHealth.WRONG_PROFILE ||
                it.health == IntegrationHealth.NEEDS_SETUP
        }?.let {
            "If this device still has an older Tasker_AI profile, import the current ${capability.target.label} profile, replace/enable it in Tasker, then press Verify profile."
        }
        uiState = ExternalAssistantSetupUiState(
            targetLabel = capability.target.takeUnless { it == ImageAutomationTarget.NONE }?.let {
                "Tasker integrations · ${it.label} selected"
            } ?: "Tasker integrations",
            ready = firstFailure == null,
            nextStep = taskerStatus.nextAction
                ?: firstFailure?.let { "${it.title}: ${it.detail}" }
                ?: "Tasker and external assistant automation are ready.",
            checks = checks,
            integrations = taskerStatus.integrations,
            canImportProfile = capability.target != ImageAutomationTarget.NONE && capability.taskerInstalled,
            canVerifyProfile = capability.target != ImageAutomationTarget.NONE && capability.taskerInstalled,
            canTestVoice = ExternalAssistantAutomationPolicy.voiceBlockingReason(capability) == null,
            profileActionLabel = if (selectedIntegration?.health == IntegrationHealth.READY) {
                "Re-import current ${capability.target.label} profile"
            } else {
                "Import / update ${capability.target.label} profile"
            },
            migrationNotice = migrationNotice,
            lastImageState = session?.let {
                buildString {
                    append(it.state.stage.wireName)
                    it.state.error?.takeIf(String::isNotBlank)?.let { error -> append(" ($error)") }
                }
            },
        )
    }

    private fun showLongToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val STATE_PENDING_PROFILE_ASSET = "pending_profile_asset"
        const val STATE_PENDING_PROFILE_FILE_NAME = "pending_profile_file_name"
    }
}

data class ExternalAssistantSetupUiState(
    val targetLabel: String = "Checking...",
    val ready: Boolean = false,
    val nextStep: String = "Checking Tasker integration setup",
    val checks: List<AssistantSetupCheck> = emptyList(),
    val integrations: List<IntegrationState> = emptyList(),
    val canImportProfile: Boolean = false,
    val canVerifyProfile: Boolean = false,
    val canTestVoice: Boolean = false,
    val profileActionLabel: String = "Import / update profile",
    val migrationNotice: String? = null,
    val lastImageState: String? = null,
)

data class AssistantSetupCheck(
    val title: String,
    val passed: Boolean,
    val detail: String,
)

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
