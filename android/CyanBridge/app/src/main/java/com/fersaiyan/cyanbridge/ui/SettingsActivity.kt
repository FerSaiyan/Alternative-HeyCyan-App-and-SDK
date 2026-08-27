package com.fersaiyan.cyanbridge.ui

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.LocalModelsConfigureActivity
import com.fersaiyan.cyanbridge.agent.ProSubscriptionActivity
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionSettingsActivity
import com.fersaiyan.cyanbridge.agent.ProSubscriptionVerifier
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationSetupActivity
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.shared.settings.MemoryPrivacyMode
import com.fersaiyan.cyanbridge.shared.settings.MemorySourceType
import com.fersaiyan.cyanbridge.memoryvault.MemorySyncPreparationService
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultBootstrap
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultService
import com.fersaiyan.cyanbridge.memoryvault.VaultLockStateManager
import com.fersaiyan.cyanbridge.privacy.LocalDataBackupManager
import com.fersaiyan.cyanbridge.privacy.LocalDataClearer
import com.fersaiyan.cyanbridge.privacy.PrivacyPrefs
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.settings.CaptureSource
import com.fersaiyan.cyanbridge.audio.MeetingCapturePrefs
import com.fersaiyan.cyanbridge.audio.MeetingCaptureService
import com.fersaiyan.cyanbridge.ui.appearance.AppearanceActivity
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport
import com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.shared.settings.SettingsSection
import com.fersaiyan.cyanbridge.shared.ui.settings.SettingsScreen
import com.fersaiyan.cyanbridge.shared.ui.settings.SettingsScreenActions
import com.fersaiyan.cyanbridge.shared.ui.settings.SettingsUiState
import com.fersaiyan.cyanbridge.ui.localization.AppLanguage
import com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity(), SettingsScreenActions {

    private var settingsUiState by mutableStateOf(SettingsUiState(taskerIntegrationsAvailable = true))
    private var expandedSections by mutableStateOf<Set<SettingsSection>>(emptySet())
    private var meetingReceiverRegistered = false

    private val sectionPrefs by lazy {
        getSharedPreferences("settings_sections", MODE_PRIVATE)
    }

    private val exportDataLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        uri?.let(::exportLocalDataToUri)
    }

    private val importDataLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(::importLocalDataFromUri)
    }

    private val meetingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != MeetingCaptureService.ACTION_STATE) return
            val source = intent.getStringExtra(MeetingCaptureService.EXTRA_SOURCE)
                ?.let { runCatching { CaptureSource.valueOf(it) }.getOrNull() }
            settingsUiState = settingsUiState.copy(
                meetingRecording = intent.getBooleanExtra(MeetingCaptureService.EXTRA_IS_RECORDING, false),
                meetingCaptureSource = source,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        expandedSections = SettingsSection.entries.filterTo(mutableSetOf()) { section ->
            sectionPrefs.getBoolean(sectionPreferenceKey(section), true)
        }
        refreshSettingsUi()

        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                SettingsScreen(
                    state = settingsUiState,
                    expandedSections = expandedSections,
                    onToggleSection = ::toggleSection,
                    actions = this@SettingsActivity,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSettingsUi()
        val shouldVerifyProState = ProSubscriptionPrefs.isSubscribed(this) || (
            ProSubscriptionPrefs.getProvider(this) != "play_billing" &&
                ProSubscriptionServerPrefs.getApiToken(this).isNotBlank()
            )
        if (shouldVerifyProState) {
            lifecycleScope.launch(Dispatchers.IO) {
                ProSubscriptionVerifier.verifyNow(this@SettingsActivity)
                withContext(Dispatchers.Main) { refreshSettingsUi() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerMeetingReceiver()
        refreshSettingsUi()
    }

    override fun onStop() {
        unregisterMeetingReceiver()
        super.onStop()
    }

    private fun registerMeetingReceiver() {
        val broadcasts = LocalBroadcastManager.getInstance(this)
        if (!meetingReceiverRegistered) {
            broadcasts.registerReceiver(meetingStateReceiver, IntentFilter(MeetingCaptureService.ACTION_STATE))
            meetingReceiverRegistered = true
        }
    }

    private fun unregisterMeetingReceiver() {
        val broadcasts = LocalBroadcastManager.getInstance(this)
        if (meetingReceiverRegistered) {
            broadcasts.unregisterReceiver(meetingStateReceiver)
            meetingReceiverRegistered = false
        }
    }

    private fun syncAgentProviderToAiProvider(type: AgentProviderType) {
        AiProviderPrefs.setProvider(
            this,
            when (type) {
                AgentProviderType.PRO_SUBSCRIPTION -> AiProviderType.CLI_RELAY
                AgentProviderType.LOCAL_AGENT -> AiProviderType.LOCAL_MODELS
                AgentProviderType.TASKER -> AiProviderType.MOCK
            },
        )
    }

    private fun toggleSection(section: SettingsSection) {
        expandedSections = if (section in expandedSections) {
            expandedSections - section
        } else {
            expandedSections + section
        }
        sectionPrefs.edit()
            .putBoolean(sectionPreferenceKey(section), section in expandedSections)
            .apply()
    }

    private fun refreshSettingsUi() {
        MemoryVaultBootstrap.ensureInitialized(this)
        val meeting = MeetingCapturePrefs.getState(this)
        val memoryMode = MemoryModeManager.getSelectedMode(this)
        val providerType = AutomationPrefs.getProviderType(this)
        val imageQuestionSettings = ImageQuestionPreferences.get(this)
        settingsUiState = SettingsUiState(
            isProSubscribed = ProSubscriptionPrefs.isActiveLocally(this),
            proPlan = formatPlan(ProSubscriptionPrefs.getPlan(this)),
            appLanguageLabel = AppLanguagePreferences.selected(this).displayName(this),
            providerType = providerType,
            taskerIntegrationsAvailable = true,
            defaultImageQuestion = imageQuestionSettings.defaultQuestion,
            memoryMode = memoryMode,
            memoryModeAvailability = MemoryModeManager.modeAvailabilityText(memoryMode),
            memorySyncStatus = "Encrypted Sync: ${MemoryModeManager.modeAvailabilityText(MemoryPrivacyMode.ENCRYPTED_SYNC)}",
            memoryCloudStatus = "Cloud: ${MemoryModeManager.modeAvailabilityText(MemoryPrivacyMode.FAST_CLOUD_MEMORY)}\n" +
                "Confidential: ${MemoryModeManager.modeAvailabilityText(MemoryPrivacyMode.CONFIDENTIAL_CLOUD_BETA)}",
            syncExplicit = MemoryModeManager.isSourceSyncEnabled(this, MemorySourceType.EXPLICIT_USER_FACT),
            syncDaily = MemoryModeManager.isSourceSyncEnabled(this, MemorySourceType.AUTO_DAILY_FACT),
            syncOcr = MemoryModeManager.isSourceSyncEnabled(this, MemorySourceType.SCREEN_OCR),
            syncDerived = MemoryModeManager.isSourceSyncEnabled(this, MemorySourceType.DERIVED_SUMMARY),
            ocrRetentionDays = MemoryModeManager.getScreenOcrRetentionDays(this),
            vaultLocked = VaultLockStateManager.isLocked(this),
            vaultRequiresPassphrase = VaultLockStateManager.requiresPassphrase(this),
            transcriptStorageEnabled = PrivacyPrefs.isTranscriptStorageEnabled(this),
            redactNamesEnabled = PrivacyPrefs.isRedactNamesEnabled(this),
            includeFullTranscriptionInExports = PrivacyPrefs.isIncludeFullTranscriptionInExportsEnabled(this),
            meetingRecording = meeting.isRecording,
            meetingCaptureSource = meeting.source,
        )
    }

    override fun onDestinationSelected(destination: AppDestination) {
        val target = when (destination) {
            AppDestination.GLASSES -> Intent(this, MainActivity::class.java)
            AppDestination.CHATS -> buildRecentChatIntent()
            AppDestination.MEDIA -> Intent(this, RecordingsListActivity::class.java)
            AppDestination.PLUGINS -> Intent(this, CommunityPluginsActivity::class.java)
            AppDestination.SETTINGS -> return
        }
        target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(target)
    }

    override fun openAppearance() {
        startActivity(Intent(this, AppearanceActivity::class.java))
    }

    override fun openAppLanguageSelection() {
        val languages = AppLanguage.entries
        val selected = AppLanguagePreferences.selected(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.language_selection_title)
            .setSingleChoiceItems(
                languages.map { it.displayName(this) }.toTypedArray(),
                languages.indexOf(selected),
            ) { dialog, which ->
                AppLanguagePreferences.select(this, languages[which])
                dialog.dismiss()
                refreshSettingsUi()
            }
            .show()
    }

    override fun openSubscription() {
        val target = if (ProSubscriptionPrefs.isActiveLocally(this)) {
            ProSubscriptionSettingsActivity::class.java
        } else {
            ProSubscriptionActivity::class.java
        }
        startActivity(Intent(this, target))
    }

    override fun setMemoryMode(mode: MemoryPrivacyMode) {
        if (!ProSubscriptionPrefs.isActiveLocally(this) && mode != MemoryPrivacyMode.PRIVATE_LOCAL) {
            Toast.makeText(this, "This memory mode requires a Pro subscription", Toast.LENGTH_SHORT).show()
            return
        }
        MemoryModeManager.setSelectedMode(this, mode)
        if (mode != MemoryPrivacyMode.ENCRYPTED_SYNC) {
            lifecycleScope.launch(Dispatchers.IO) {
                MemorySyncPreparationService.cancelAllQueued("Mode switched away from Encrypted Sync")
            }
        }
        refreshSettingsUi()
    }

    override fun setMemorySync(source: MemorySourceType, enabled: Boolean) {
        MemoryModeManager.setSourceSyncEnabled(this, source, enabled)
        if (!enabled) {
            lifecycleScope.launch(Dispatchers.IO) {
                MemorySyncPreparationService.cancelAllQueued("Sync eligibility tightened for ${source.name.lowercase()}")
            }
        }
        refreshSettingsUi()
    }

    override fun setProviderType(type: AgentProviderType) {
        AutomationPrefs.setProviderType(this, type)
        syncAgentProviderToAiProvider(type)
        if (type != AgentProviderType.LOCAL_AGENT) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { LocalChatSessionManager.unload() }
            }
        }
        refreshSettingsUi()
    }

    override fun openLocalModels() {
        startActivity(Intent(this, LocalModelsConfigureActivity::class.java))
    }

    override fun openTaskerIntegrations() {
        startActivity(Intent(this, ExternalAssistantAutomationSetupActivity::class.java))
    }

    override fun setDefaultImageQuestion(question: String) {
        ImageQuestionPreferences.setDefaultQuestion(this, question)
        refreshSettingsUi()
    }

    override fun resetDefaultImageQuestion() {
        ImageQuestionPreferences.resetDefaultQuestion(this)
        refreshSettingsUi()
    }

    override fun setOcrRetentionDays(value: Int) {
        MemoryModeManager.setScreenOcrRetentionDays(this, value)
        lifecycleScope.launch(Dispatchers.IO) {
            MemoryVaultService.enforceScreenOcrRetention(this@SettingsActivity)
        }
        refreshSettingsUi()
    }

    override fun deletePassiveCapture() {
        AlertDialog.Builder(this)
            .setTitle("Delete passive OCR capture?")
            .setMessage("This deletes local OCR snapshots and their search index artifacts. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    LocalAgentMemoryStore.deleteAllPassiveCapture(this@SettingsActivity)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SettingsActivity, "Passive OCR capture deleted", Toast.LENGTH_SHORT).show()
                        refreshSettingsUi()
                    }
                }
            }
            .show()
    }

    override fun lockVault() {
        VaultLockStateManager.lock(this)
        Toast.makeText(this, "Vault locked", Toast.LENGTH_SHORT).show()
        refreshSettingsUi()
    }

    override fun unlockVault() {
        if (VaultLockStateManager.requiresPassphrase(this)) {
            showPassphraseDialog("Unlock vault") { passphrase ->
                val unlocked = VaultLockStateManager.unlockWithPassphrase(this, passphrase.toCharArray())
                Toast.makeText(this, if (unlocked) "Vault unlocked" else "Invalid passphrase", Toast.LENGTH_SHORT).show()
                refreshSettingsUi()
            }
        } else {
            val unlocked = VaultLockStateManager.unlockWithDevice(this)
            Toast.makeText(this, if (unlocked) "Vault unlocked" else "Unable to unlock vault", Toast.LENGTH_SHORT).show()
            refreshSettingsUi()
        }
    }

    override fun setVaultPassphrase() {
        showPassphraseDialog("Set vault passphrase") { passphrase ->
            val set = VaultLockStateManager.setPassphrase(this, passphrase.toCharArray())
            Toast.makeText(
                this,
                if (set) "Passphrase set. Vault locked." else "Could not set passphrase. Unlock vault first.",
                Toast.LENGTH_LONG,
            ).show()
            refreshSettingsUi()
        }
    }

    override fun clearVaultPassphrase() {
        VaultLockStateManager.clearPassphrase(this)
        Toast.makeText(this, "Passphrase requirement cleared", Toast.LENGTH_SHORT).show()
        refreshSettingsUi()
    }

    override fun resetVault() {
        AlertDialog.Builder(this)
            .setTitle("Reset memory vault?")
            .setMessage("This removes encrypted memory payloads, policy metadata, sync queue state, and lock keys. Existing plain files remain. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    LocalAgentMemoryStore.resetVault(this@SettingsActivity)
                    LocalAgentMemoryStore.ensureSeedFiles(this@SettingsActivity)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SettingsActivity, "Memory vault reset", Toast.LENGTH_LONG).show()
                        refreshSettingsUi()
                    }
                }
            }
            .show()
    }

    override fun setTranscriptStorageEnabled(enabled: Boolean) {
        PrivacyPrefs.setTranscriptStorageEnabled(this, enabled)
        refreshSettingsUi()
    }

    override fun setRedactNamesEnabled(enabled: Boolean) {
        PrivacyPrefs.setRedactNamesEnabled(this, enabled)
        refreshSettingsUi()
    }

    override fun setIncludeFullTranscriptionEnabled(enabled: Boolean) {
        PrivacyPrefs.setIncludeFullTranscriptionInExportsEnabled(this, enabled)
        refreshSettingsUi()
    }

    override fun exportLocalData() {
        val fileName = "cyanbridge_backup_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.zip"
        exportDataLauncher.launch(fileName)
    }

    override fun importLocalData() {
        AlertDialog.Builder(this)
            .setTitle("Import local data?")
            .setMessage("This will overwrite current local chats, memory files, recordings, and settings from the selected backup ZIP.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Import") { _, _ ->
                importDataLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            }
            .show()
    }

    override fun clearLocalData() {
        AlertDialog.Builder(this)
            .setTitle("Clear local data?")
            .setMessage("This will delete all chats, notes, capture sessions, and audio recordings stored on this device. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                Toast.makeText(this, "Clearing data...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = LocalDataClearer.clearAll(this@SettingsActivity)
                    withContext(Dispatchers.Main) {
                        val message = if (result.errors.isEmpty()) {
                            "Local data cleared (deleted files: ${result.deletedFiles})"
                        } else {
                            "Cleared with warnings: ${result.errors.joinToString()}"
                        }
                        Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                        refreshSettingsUi()
                    }
                }
            }
            .show()
    }

    override fun sendDebugLogs() {
        showLogSubmissionDialog()
    }

    override fun stopMeetingCapture() {
        MeetingCaptureService.stop(this)
    }

    private fun exportLocalDataToUri(uri: Uri) {
        Toast.makeText(this, "Exporting data...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { LocalDataBackupManager.exportToZip(this@SettingsActivity, uri) }
                .onSuccess { result ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@SettingsActivity,
                            "Export complete: ${result.threadCount} chats, ${result.messageCount} messages, ${result.memoryFileCount} memory files, ${result.recordingFileCount} recordings, ${result.vaultItemCount} vault items.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SettingsActivity, "Export failed: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    private fun importLocalDataFromUri(uri: Uri) {
        Toast.makeText(this, "Importing data...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { LocalDataBackupManager.importFromZip(this@SettingsActivity, uri) }
                .onSuccess { result ->
                    withContext(Dispatchers.Main) {
                        refreshSettingsUi()
                        Toast.makeText(
                            this@SettingsActivity,
                            "Import complete: ${result.threadCount} chats, ${result.messageCount} messages, ${result.memoryFileCount} memory files, ${result.recordingFileCount} recordings, ${result.vaultItemCount} vault items.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SettingsActivity, "Import failed: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    private fun showLogSubmissionDialog() {
        val issueTypes = arrayOf(
            "P2P/WiFi sync issue",
            "Image query failed",
            "Voice command not working",
            "BLE connection issue",
            "App crash/ANR",
            "Other/General",
        )
        var selectedType = issueTypes.first()
        val descriptionInput = EditText(this).apply {
            hint = "Describe what happened (optional)"
            minLines = 3
        }
        val contactEmailInput = EditText(this).apply {
            hint = "Contact email (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            filters = arrayOf(InputFilter.LengthFilter(DebugLogSupport.MAX_CONTACT_EMAIL_LENGTH))
            setText(ProSubscriptionServerPrefs.getAccountEmail(this@SettingsActivity))
            contentDescription = "Contact email for log follow-up"
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(descriptionInput)
            addView(contactEmailInput)
            addView(TextView(this@SettingsActivity).apply {
                text = "Optional. This lets CyanBridge support reply to you about these logs."
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Send Debug Logs")
            .setSingleChoiceItems(issueTypes, 0) { _, which -> selectedType = issueTypes[which] }
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val contactEmail = contactEmailInput.text?.toString().orEmpty().trim()
                if (!DebugLogSupport.isValidOptionalContactEmail(contactEmail)) {
                    contactEmailInput.error = "Enter a valid email or leave this blank"
                    return@setOnClickListener
                }
                dialog.dismiss()
                submitDebugLogs(
                    issueType = selectedType,
                    description = descriptionInput.text?.toString()?.trim()?.take(2_000).orEmpty(),
                    contactEmail = contactEmail,
                )
            }
        }
        dialog.show()
    }

    private fun submitDebugLogs(issueType: String, description: String, contactEmail: String) {
        Toast.makeText(this, "Collecting logs...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                DebugLogSupport.sendLogsToServer(
                    context = this@SettingsActivity,
                    issueType = issueType,
                    description = description,
                    logs = DebugLogSupport.collectLogcat(),
                    deviceInfo = DebugLogSupport.buildDeviceInfo(this@SettingsActivity),
                    contactEmail = contactEmail.ifBlank { null },
                )
            }.onSuccess { result ->
                withContext(Dispatchers.Main) {
                    val message = if (result.isSuccess) {
                        "Logs sent successfully. Thank you for helping debug."
                    } else {
                        "Failed to send logs: ${result.exceptionOrNull()?.message}"
                    }
                    Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Error collecting logs: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showPassphraseDialog(title: String, onSubmit: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "Passphrase"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ ->
                val passphrase = input.text?.toString().orEmpty()
                if (passphrase.isBlank()) {
                    Toast.makeText(this, "Passphrase cannot be empty", Toast.LENGTH_SHORT).show()
                } else {
                    onSubmit(passphrase)
                }
            }
            .show()
    }

    private fun buildRecentChatIntent(): Intent {
        val last = ChatStore.listNonEmptyThreads().firstOrNull()
        val lastUserAt = last?.let { thread ->
            ChatStore.listMessages(thread.id)
                .lastOrNull { it.role == ChatRole.USER }
                ?.createdAt
        } ?: 0L
        val openChatId = last?.id?.takeIf {
            lastUserAt > 0L && System.currentTimeMillis() - lastUserAt < 30 * 60 * 1_000
        }
        return Intent(this, ChatThreadActivity::class.java).apply {
            if (openChatId != null) putExtra(ChatThreadActivity.EXTRA_CHAT_ID, openChatId)
        }
    }

    private fun formatPlan(raw: String): String = when (raw.lowercase(Locale.US)) {
        "monthly" -> "Monthly"
        "yearly" -> "Yearly"
        else -> "Pro"
    }

    private fun sectionPreferenceKey(section: SettingsSection): String {
        val legacyCardName = when (section) {
            SettingsSection.AI_AUTOMATION -> "card_agent_provider"
            SettingsSection.MEMORY_PRIVACY -> "card_memory_privacy"
            SettingsSection.TRANSCRIPTS -> "card_transcripts"
            SettingsSection.DATA -> "card_data"
            SettingsSection.FAQ -> "card_faq"
            SettingsSection.SUPPORT -> "support"
        }
        return "section_expanded_$legacyCardName"
    }
}
