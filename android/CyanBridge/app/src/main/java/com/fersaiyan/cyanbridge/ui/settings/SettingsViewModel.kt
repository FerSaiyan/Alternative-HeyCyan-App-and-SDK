package com.fersaiyan.cyanbridge.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.agent.AgentProviderType
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.ai.router.CliRelayBackend
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs as AgentRuntimePrefs
import com.fersaiyan.cyanbridge.localagent.daily.DailyFactsReminderScheduler
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.localagent.userfacts.ChatMemoryPrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.memoryvault.MemoryPrivacyMode
import com.fersaiyan.cyanbridge.memoryvault.MemorySourceType
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultBootstrap
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultService
import com.fersaiyan.cyanbridge.memoryvault.VaultLockStateManager
import com.fersaiyan.cyanbridge.privacy.LocalDataBackupManager
import com.fersaiyan.cyanbridge.privacy.LocalDataClearer
import com.fersaiyan.cyanbridge.privacy.PrivacyPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val isProSubscribed: Boolean = false,
    val proPlan: String = "none",
    val proExpiresAt: Long = 0L,
    val providerType: AgentProviderType = AgentProviderType.PRO_SUBSCRIPTION,
    val aiProvider: AiProviderType = AiProviderType.CLI_RELAY,
    val relayBaseUrl: String = "",
    val relayBackend: CliRelayBackend = CliRelayBackend.GEMINI,
    val isDarkTheme: Boolean = true,
    val accentColorIndex: Int = 0,
    val isLoading: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val autoCaptureEnabled: Boolean = false,
    val captureIntervalMin: Int = 10,
    val dailyFactsReminderEnabled: Boolean = false,
    val autoSaveDailyFactsEnabled: Boolean = true,
    val extractUserFactCandidatesEnabled: Boolean = true,
    val lastContextInjectionDebug: String = "",
    val memoryMode: MemoryPrivacyMode = MemoryPrivacyMode.PRIVATE_LOCAL,
    val syncExplicit: Boolean = true,
    val syncDaily: Boolean = true,
    val syncOcr: Boolean = false,
    val syncDerived: Boolean = false,
    val ocrRetentionDays: Int = 7,
    val vaultLocked: Boolean = false,
    val vaultRequiresPassphrase: Boolean = false,
    val transcriptStorageEnabled: Boolean = false,
    val redactNamesEnabled: Boolean = true,
    val includeFullTranscriptionInExports: Boolean = false,
    val autoAudioCaptureEnabled: Boolean = false,
    val autoAudioDebugText: String = "",
    val exportResult: String = "",
    val clearResult: String = "",
)

class SettingsViewModel(private val context: Context) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadState()
    }

    fun loadState() {
        viewModelScope.launch {
            val isPro = ProSubscriptionPrefs.isActiveLocally(context)
            val plan = ProSubscriptionPrefs.getPlan(context)
            val expires = ProSubscriptionPrefs.getExpiresAt(context)
            val providerType = LocalAgentPrefs.getProviderType(context)
            val aiProvider = AiProviderPrefs.getProvider(context)
            val relayUrl = AiProviderPrefs.getRelayBaseUrl(context)
            val relayBackend = AiProviderPrefs.getRelayBackend(context)
            val isDark = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
                .getBoolean("dark_theme", true)
            val accentIdx = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
                .getInt("accent_color_index", 0)

            MemoryVaultBootstrap.ensureInitialized(context)
            val memoryMode = MemoryModeManager.getSelectedMode(context)
            val syncExplicit = MemoryModeManager.isSourceSyncEnabled(context, MemorySourceType.EXPLICIT_USER_FACT)
            val syncDaily = MemoryModeManager.isSourceSyncEnabled(context, MemorySourceType.AUTO_DAILY_FACT)
            val syncOcr = MemoryModeManager.isSourceSyncEnabled(context, MemorySourceType.SCREEN_OCR)
            val syncDerived = MemoryModeManager.isSourceSyncEnabled(context, MemorySourceType.DERIVED_SUMMARY)
            val ocrRetention = MemoryModeManager.getScreenOcrRetentionDays(context)
            val vaultLocked = VaultLockStateManager.isLocked(context)
            val vaultRequiresPassphrase = VaultLockStateManager.requiresPassphrase(context)

            val autoCaptureEnabled = LocalAgentPrefs.isAutoCaptureEnabled(context) && MemoryModeManager.isScreenOcrCaptureEnabled(context)
            val captureInterval = LocalAgentPrefs.getCaptureIntervalMin(context)
            val dailyFactsReminder = LocalAgentPrefs.isDailyFactsReminderEnabled(context)
            val autoSaveDailyFacts = ChatMemoryPrefs.isAutoSaveDailyFactsEnabled(context)
            val extractUserFactCandidates = ChatMemoryPrefs.isExtractUserFactCandidatesEnabled(context)
            val contextDebug = AgentRuntimePrefs.getLastContextInjectionDebug(context)

            val transcriptStorage = PrivacyPrefs.isTranscriptStorageEnabled(context)
            val redactNames = PrivacyPrefs.isRedactNamesEnabled(context)
            val includeFullTranscript = PrivacyPrefs.isIncludeFullTranscriptionInExportsEnabled(context)
            val autoAudioEnabled = AutoAudioCapturePrefs.isEnabled(context)
            val lastPauseReason = AutoAudioCapturePrefs.getLastPauseReason(context).ifBlank { "(none)" }
            val permOk = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                    com.hjq.permissions.XXPermissions.isGranted(context, android.Manifest.permission.POST_NOTIFICATIONS)
            val appNotifsEnabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            val autoAudioDebug = listOf(
                if (autoAudioEnabled) "auto-audio: ON" else "auto-audio: OFF",
                if (permOk) "perm=ok" else "perm=blocked",
                if (appNotifsEnabled) "appNotifs=on" else "appNotifs=off",
                "last=$lastPauseReason"
            ).joinToString(" · ")

            _uiState.value = SettingsUiState(
                isProSubscribed = isPro,
                proPlan = plan,
                proExpiresAt = expires,
                providerType = providerType,
                aiProvider = aiProvider,
                relayBaseUrl = relayUrl,
                relayBackend = relayBackend,
                isDarkTheme = isDark,
                accentColorIndex = accentIdx,
                isLoading = false,
                accessibilityEnabled = false,
                autoCaptureEnabled = autoCaptureEnabled,
                captureIntervalMin = captureInterval,
                dailyFactsReminderEnabled = dailyFactsReminder,
                autoSaveDailyFactsEnabled = autoSaveDailyFacts,
                extractUserFactCandidatesEnabled = extractUserFactCandidates,
                lastContextInjectionDebug = contextDebug,
                memoryMode = memoryMode,
                syncExplicit = syncExplicit,
                syncDaily = syncDaily,
                syncOcr = syncOcr,
                syncDerived = syncDerived,
                ocrRetentionDays = ocrRetention,
                vaultLocked = vaultLocked,
                vaultRequiresPassphrase = vaultRequiresPassphrase,
                transcriptStorageEnabled = transcriptStorage,
                redactNamesEnabled = redactNames,
                includeFullTranscriptionInExports = includeFullTranscript,
                autoAudioCaptureEnabled = autoAudioEnabled,
                autoAudioDebugText = autoAudioDebug,
            )
        }
    }

    fun refreshAccessibilityStatus() {
        val enabled = isLocalAgentAccessibilityServiceEnabled()
        _uiState.value = _uiState.value.copy(accessibilityEnabled = enabled)
    }

    private fun isLocalAgentAccessibilityServiceEnabled(): Boolean {
        return try {
            val enabled = android.provider.Settings.Secure.getInt(
                context.contentResolver,
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1
            if (!enabled) return false
            val expected = android.content.ComponentName(
                context,
                com.fersaiyan.cyanbridge.localagent.accessibility.LocalAgentAccessibilityService::class.java
            ).flattenToString()
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
        } catch (_: Exception) {
            false
        }
    }

    fun setProviderType(type: AgentProviderType) {
        LocalAgentPrefs.setProviderType(context, type)
        val aiProvider = when (type) {
            AgentProviderType.PRO_SUBSCRIPTION -> AiProviderType.CLI_RELAY
            AgentProviderType.LOCAL_AGENT -> AiProviderType.LOCAL_MODELS
            AgentProviderType.TASKER -> AiProviderType.MOCK
        }
        AiProviderPrefs.setProvider(context, aiProvider)
        _uiState.value = _uiState.value.copy(providerType = type, aiProvider = aiProvider)
    }

    fun setRelayBaseUrl(url: String) {
        AiProviderPrefs.setRelayBaseUrl(context, url)
        _uiState.value = _uiState.value.copy(relayBaseUrl = url)
    }

    fun setRelayBackend(backend: CliRelayBackend) {
        AiProviderPrefs.setRelayBackend(context, backend)
        _uiState.value = _uiState.value.copy(relayBackend = backend)
    }

    fun setDarkTheme(isDark: Boolean) {
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("dark_theme", isDark)
            .apply()
        _uiState.value = _uiState.value.copy(isDarkTheme = isDark)
    }

    fun setAccentColorIndex(index: Int) {
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("accent_color_index", index)
            .apply()
        _uiState.value = _uiState.value.copy(accentColorIndex = index)
    }

    fun setAutoCaptureEnabled(enabled: Boolean) {
        LocalAgentPrefs.setAutoCaptureEnabled(context, enabled)
        MemoryModeManager.setScreenOcrCaptureEnabled(context, enabled)
        _uiState.value = _uiState.value.copy(autoCaptureEnabled = enabled)
    }

    fun setCaptureInterval(minutes: Int) {
        LocalAgentPrefs.setCaptureIntervalMin(context, minutes)
        _uiState.value = _uiState.value.copy(captureIntervalMin = minutes)
    }

    fun setDailyFactsReminderEnabled(enabled: Boolean) {
        LocalAgentPrefs.setDailyFactsReminderEnabled(context, enabled)
        DailyFactsReminderScheduler.scheduleIfEnabled(context, enabled)
        _uiState.value = _uiState.value.copy(dailyFactsReminderEnabled = enabled)
    }

    fun setAutoSaveDailyFacts(enabled: Boolean) {
        ChatMemoryPrefs.setAutoSaveDailyFactsEnabled(context, enabled)
        _uiState.value = _uiState.value.copy(autoSaveDailyFactsEnabled = enabled)
    }

    fun setExtractUserFactCandidates(enabled: Boolean) {
        ChatMemoryPrefs.setExtractUserFactCandidatesEnabled(context, enabled)
        _uiState.value = _uiState.value.copy(extractUserFactCandidatesEnabled = enabled)
    }

    fun saveAgentPersona(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            LocalAgentMemoryStore.ensureSeedFiles(context)
            val f = LocalAgentMemoryStore.agentPersonaFile(context)
            LocalAgentMemoryStore.writeText(f, text)
        }
    }

    fun saveUserFacts(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            LocalAgentMemoryStore.ensureSeedFiles(context)
            val f = LocalAgentMemoryStore.userFactsFile(context)
            LocalAgentMemoryStore.writeText(f, text)
        }
    }

    fun readAgentPersona(): String {
        return try {
            LocalAgentMemoryStore.ensureSeedFiles(context)
            LocalAgentMemoryStore.readText(LocalAgentMemoryStore.agentPersonaFile(context))
        } catch (_: Exception) {
            ""
        }
    }

    fun readUserFacts(): String {
        return try {
            LocalAgentMemoryStore.ensureSeedFiles(context)
            LocalAgentMemoryStore.readText(LocalAgentMemoryStore.userFactsFile(context))
        } catch (_: Exception) {
            ""
        }
    }

    fun setMemoryMode(mode: MemoryPrivacyMode) {
        MemoryModeManager.setSelectedMode(context, mode)
        _uiState.value = _uiState.value.copy(memoryMode = mode)
    }

    fun setSyncExplicit(enabled: Boolean) {
        MemoryModeManager.setSourceSyncEnabled(context, MemorySourceType.EXPLICIT_USER_FACT, enabled)
        _uiState.value = _uiState.value.copy(syncExplicit = enabled)
    }

    fun setSyncDaily(enabled: Boolean) {
        MemoryModeManager.setSourceSyncEnabled(context, MemorySourceType.AUTO_DAILY_FACT, enabled)
        _uiState.value = _uiState.value.copy(syncDaily = enabled)
    }

    fun setSyncOcr(enabled: Boolean) {
        MemoryModeManager.setSourceSyncEnabled(context, MemorySourceType.SCREEN_OCR, enabled)
        _uiState.value = _uiState.value.copy(syncOcr = enabled)
    }

    fun setSyncDerived(enabled: Boolean) {
        MemoryModeManager.setSourceSyncEnabled(context, MemorySourceType.DERIVED_SUMMARY, enabled)
        _uiState.value = _uiState.value.copy(syncDerived = enabled)
    }

    fun setOcrRetentionDays(days: Int) {
        MemoryModeManager.setScreenOcrRetentionDays(context, days)
        _uiState.value = _uiState.value.copy(ocrRetentionDays = days)
    }

    fun deletePassiveCapture(onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            LocalAgentMemoryStore.deleteAllPassiveCapture(context)
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    fun lockVault() {
        VaultLockStateManager.lock(context)
        _uiState.value = _uiState.value.copy(vaultLocked = true)
    }

    fun unlockVault(passphrase: String): Boolean {
        val ok = if (VaultLockStateManager.requiresPassphrase(context)) {
            VaultLockStateManager.unlockWithPassphrase(context, passphrase.toCharArray())
        } else {
            VaultLockStateManager.unlockWithDevice(context)
        }
        _uiState.value = _uiState.value.copy(vaultLocked = !ok)
        return ok
    }

    fun setVaultPassphrase(passphrase: String): Boolean {
        val ok = VaultLockStateManager.setPassphrase(context, passphrase.toCharArray())
        _uiState.value = _uiState.value.copy(vaultLocked = true)
        return ok
    }

    fun clearVaultPassphrase() {
        VaultLockStateManager.clearPassphrase(context)
        _uiState.value = _uiState.value.copy(vaultRequiresPassphrase = false)
    }

    fun resetVault(onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            LocalAgentMemoryStore.resetVault(context)
            LocalAgentMemoryStore.ensureSeedFiles(context)
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    fun refreshVaultState() {
        val locked = VaultLockStateManager.isLocked(context)
        val requiresPassphrase = VaultLockStateManager.requiresPassphrase(context)
        _uiState.value = _uiState.value.copy(vaultLocked = locked, vaultRequiresPassphrase = requiresPassphrase)
    }

    fun setTranscriptStorage(enabled: Boolean) {
        PrivacyPrefs.setTranscriptStorageEnabled(context, enabled)
        _uiState.value = _uiState.value.copy(transcriptStorageEnabled = enabled)
    }

    fun setRedactNames(enabled: Boolean) {
        PrivacyPrefs.setRedactNamesEnabled(context, enabled)
        _uiState.value = _uiState.value.copy(redactNamesEnabled = enabled)
    }

    fun setIncludeFullTranscriptionInExports(enabled: Boolean) {
        PrivacyPrefs.setIncludeFullTranscriptionInExportsEnabled(context, enabled)
        _uiState.value = _uiState.value.copy(includeFullTranscriptionInExports = enabled)
    }

    fun setAutoAudioCapture(enabled: Boolean) {
        AutoAudioCapturePrefs.setEnabled(context, enabled)
        if (enabled) {
            AutoAudioCaptureService.start(context)
        } else {
            AutoAudioCaptureService.stop(context)
        }
        _uiState.value = _uiState.value.copy(autoAudioCaptureEnabled = enabled)
    }

    fun refreshAutoAudioDebug() {
        val enabled = AutoAudioCapturePrefs.isEnabled(context)
        val lastReason = AutoAudioCapturePrefs.getLastPauseReason(context).ifBlank { "(none)" }
        val permOk = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                com.hjq.permissions.XXPermissions.isGranted(context, android.Manifest.permission.POST_NOTIFICATIONS)
        val appNotifsEnabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        val autoAudioDebug = listOf(
            if (enabled) "auto-audio: ON" else "auto-audio: OFF",
            if (permOk) "perm=ok" else "perm=blocked",
            if (appNotifsEnabled) "appNotifs=on" else "appNotifs=off",
            "last=$lastReason"
        ).joinToString(" · ")
        _uiState.value = _uiState.value.copy(autoAudioCaptureEnabled = enabled, autoAudioDebugText = autoAudioDebug)
    }

    fun exportData(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                LocalDataBackupManager.exportToZip(context, uri)
            }.onSuccess { result ->
                withContext(Dispatchers.Main) {
                    onResult("Export complete: ${result.threadCount} chats, ${result.messageCount} messages, ${result.memoryFileCount} memory files, ${result.recordingFileCount} recordings, ${result.vaultItemCount} vault items.")
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    onResult("Export failed: ${e.message}")
                }
            }
        }
    }

    fun importData(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                LocalDataBackupManager.importFromZip(context, uri)
            }.onSuccess { result ->
                withContext(Dispatchers.Main) {
                    loadState()
                    onResult("Import complete: ${result.threadCount} chats, ${result.messageCount} messages, ${result.memoryFileCount} memory files, ${result.recordingFileCount} recordings, ${result.vaultItemCount} vault items.")
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    onResult("Import failed: ${e.message}")
                }
            }
        }
    }

    fun clearAllData(onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = LocalDataClearer.clearAll(context)
            withContext(Dispatchers.Main) {
                if (result.errors.isEmpty()) {
                    onResult("Local data cleared (deleted files: ${result.deletedFiles})")
                } else {
                    onResult("Cleared with warnings: ${result.errors.joinToString()}")
                }
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(context.applicationContext) as T
        }
    }
}
