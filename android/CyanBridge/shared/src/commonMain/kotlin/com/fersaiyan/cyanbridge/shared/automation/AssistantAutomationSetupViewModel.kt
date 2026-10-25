package com.fersaiyan.cyanbridge.shared.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffect
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffects
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class IntegrationHealth {
    READY,
    NEEDS_SETUP,
    OUTDATED,
    WRONG_PROFILE,
    NOT_SELECTED,
    ENVIRONMENT_BLOCKED,
    NOT_VERSIONED,
}

/** Readiness of one Tasker-powered CyanBridge feature. */
data class IntegrationState(
    val id: String,
    val name: String,
    val installedVersion: String? = null,
    val requiredVersion: String? = null,
    val health: IntegrationHealth = IntegrationHealth.NEEDS_SETUP,
    val detail: String = "",
    val actionHint: String? = null,
) {
    val ready: Boolean get() = health == IntegrationHealth.READY
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

/** What the Tasker setup screen inspects on the phone. */
data class AssistantAutomationSnapshot(
    val taskerInstalled: Boolean,
    val taskerVersion: String?,
    val taskerPackage: String,
    val taskerAccessibilityEnabled: Boolean,
    val autoInputInstalled: Boolean,
    val autoInputVersion: String?,
    val autoInputAccessibilityEnabled: Boolean,
    val integrations: List<IntegrationState>,
    val nextAction: String?,
    /** `gemini` or `chatgpt` when one is the default assistant; null otherwise. */
    val targetId: String?,
    val targetLabel: String,
    val targetPackage: String?,
    val defaultAssistantPackage: String?,
    /** Tasker as the assistant automation sees it. */
    val assistantTaskerInstalled: Boolean,
    val profileCompatible: Boolean,
    val phoneLocked: Boolean,
    val imageShareAvailable: Boolean,
    val voiceBlockingReason: String?,
    val lastImageState: String?,
)

/** Inspection and the Tasker broadcasts behind the setup screen. */
interface AssistantAutomationPlatform {
    fun inspect(): AssistantAutomationSnapshot

    /** Asks the assistant's Tasker profile to report its version. */
    fun beginProfileCheck(targetLabel: String)
    fun sendVoiceTest(targetLabel: String)
}

/** Hands the bundled Tasker profile [assetName] to Tasker. */
data class ImportTaskerProfile(val assetName: String) : ScreenEffect

/** The Tasker integration center: environment checks, profile import/verify and a voice test. */
class AssistantAutomationSetupViewModel(private val platform: AssistantAutomationPlatform) : ViewModel() {
    private val _state = MutableStateFlow(ExternalAssistantSetupUiState())
    val state: StateFlow<ExternalAssistantSetupUiState> = _state.asStateFlow()
    private val effects = ScreenEffects()
    val effectFlow get() = effects.flow

    fun importProfile() {
        val snapshot = platform.inspect()
        val assetName = when (snapshot.targetId) {
            "gemini" -> "tasker/CyanBridge_Gemini.xml"
            "chatgpt" -> "tasker/CyanBridge_ChatGPT.xml"
            else -> {
                effects.toast("Set Gemini or ChatGPT as the default assistant first.", long = true)
                return
            }
        }
        if (!snapshot.assistantTaskerInstalled) {
            effects.toast("Install Tasker before importing the profile.", long = true)
            return
        }
        effects.send(ImportTaskerProfile(assetName))
    }

    fun verifyProfile() {
        val snapshot = platform.inspect()
        if (snapshot.targetId == null) {
            effects.toast("Set Gemini or ChatGPT as the default assistant first.", long = true)
            return
        }
        if (!snapshot.assistantTaskerInstalled) {
            effects.toast("Tasker is not installed.", long = true)
            return
        }
        platform.beginProfileCheck(snapshot.targetLabel)
        effects.toast("Waiting for the Tasker profile handshake...")
        viewModelScope.launch {
            delay(1_500L)
            refresh()
            effects.toast(
                if (platform.inspect().profileCompatible) {
                    "${snapshot.targetLabel} Tasker profile verified."
                } else {
                    "No compatible profile response received. Import/update the current profile and try again."
                },
                long = true,
            )
        }
    }

    fun testVoice() {
        val snapshot = platform.inspect()
        snapshot.voiceBlockingReason?.let {
            effects.toast(it, long = true)
            return
        }
        platform.sendVoiceTest(snapshot.targetLabel)
    }

    fun refresh() {
        _state.value = setupState(platform.inspect())
    }

    companion object {
        fun setupState(s: AssistantAutomationSnapshot): ExternalAssistantSetupUiState {
            val hasTarget = s.targetId != null
            val selectedIntegration = s.integrations.firstOrNull { it.id == (s.targetId ?: "none") }
            val checks = listOf(
                AssistantSetupCheck(
                    title = "Tasker installed",
                    passed = s.taskerInstalled,
                    detail = s.taskerVersion?.let { "Version $it" } ?: s.taskerPackage,
                ),
                AssistantSetupCheck(
                    title = "Tasker Accessibility Access",
                    passed = s.taskerAccessibilityEnabled,
                    detail = if (s.taskerAccessibilityEnabled) {
                        "Enabled. Required for reliable foreground-app detection (%WIN)."
                    } else {
                        "Enable it through Tasker's own Accessibility Access disclosure flow; direct Android settings writes are not sufficient."
                    },
                ),
                AssistantSetupCheck(
                    title = "AutoInput installed",
                    passed = s.autoInputInstalled,
                    detail = s.autoInputVersion?.let { "Version $it" }
                        ?: "Install AutoInput; if actions silently no-op, restore the purchased entitlement through the official AutoApps app.",
                ),
                AssistantSetupCheck(
                    title = "AutoInput Accessibility Access",
                    passed = s.autoInputAccessibilityEnabled,
                    detail = "Required for observing, clicking and typing in Android/external-app UI.",
                ),
                AssistantSetupCheck(
                    title = "Android default assistant",
                    passed = hasTarget,
                    detail = if (hasTarget) {
                        "${s.targetLabel} (${s.defaultAssistantPackage})"
                    } else {
                        "${s.defaultAssistantPackage ?: "Not detected"}; choose Gemini or ChatGPT"
                    },
                ),
                AssistantSetupCheck(
                    title = "Default assistant app installed",
                    passed = s.targetPackage != null,
                    detail = s.targetPackage ?: "No supported assistant package",
                ),
                AssistantSetupCheck(
                    title = "Current assistant Tasker profile",
                    passed = s.profileCompatible,
                    detail = selectedIntegration?.detail ?: "Choose Gemini or ChatGPT as the default assistant to verify its profile.",
                ),
                AssistantSetupCheck(
                    title = "Phone unlocked",
                    passed = !s.phoneLocked,
                    detail = "External UI automation fails immediately while locked.",
                ),
                AssistantSetupCheck(
                    title = "Assistant accepts image shares",
                    passed = s.imageShareAvailable,
                    detail = s.targetPackage ?: "No supported assistant selected",
                ),
            )
            val firstFailure = checks.firstOrNull { !it.passed }
            val migrationNotice = selectedIntegration?.takeIf {
                it.health == IntegrationHealth.OUTDATED ||
                    it.health == IntegrationHealth.WRONG_PROFILE ||
                    it.health == IntegrationHealth.NEEDS_SETUP
            }?.let {
                "If this device still has an older Tasker_AI profile, import the current ${s.targetLabel} profile, replace/enable it in Tasker, then press Verify profile."
            }
            return ExternalAssistantSetupUiState(
                targetLabel = if (hasTarget) "Tasker integrations · ${s.targetLabel} selected" else "Tasker integrations",
                ready = firstFailure == null,
                nextStep = s.nextAction
                    ?: firstFailure?.let { "${it.title}: ${it.detail}" }
                    ?: "Tasker and external assistant automation are ready.",
                checks = checks,
                integrations = s.integrations,
                canImportProfile = hasTarget && s.assistantTaskerInstalled,
                canVerifyProfile = hasTarget && s.assistantTaskerInstalled,
                canTestVoice = s.voiceBlockingReason == null,
                profileActionLabel = if (selectedIntegration?.health == IntegrationHealth.READY) {
                    "Re-import current ${s.targetLabel} profile"
                } else {
                    "Import / update ${s.targetLabel} profile"
                },
                migrationNotice = migrationNotice,
                lastImageState = s.lastImageState,
            )
        }
    }
}
