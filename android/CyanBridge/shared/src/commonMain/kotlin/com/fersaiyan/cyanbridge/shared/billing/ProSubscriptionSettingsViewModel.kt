package com.fersaiyan.cyanbridge.shared.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.format.formatLocalDate
import com.fersaiyan.cyanbridge.shared.format.formatLocalDateTime
import com.fersaiyan.cyanbridge.shared.viewmodel.CloseScreen
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffect
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffects
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Pro plan's on-device settings: feature toggles, Pro AI models and the Live mode. */
interface ProSettingsStore {
    fun loadToggles(): ProSettingsToggles
    fun saveToggles(toggles: ProSettingsToggles)
    var requestsModel: String
    var questionsModel: String
    var tasksModel: String
    var systemPrompt: String
    fun resetSystemPrompt()
    var liveEconomy: Boolean
    fun defaultLiveModes(): List<ProLiveMode>
}

data class ProSettingsToggles(
    val cloudSync: Boolean,
    val prioritySupport: Boolean,
    val pluginRewards: Boolean,
    val earlyAccessDevices: Boolean,
    val backupFrequencyIndex: Int,
    val supportChannelIndex: Int,
)

data class ProSubscriptionSettingsScreenState(
    val ui: ProSubscriptionSettingsUiState = ProSubscriptionSettingsUiState(),
    val liveEconomy: Boolean = false,
    val liveModes: List<ProLiveMode> = emptyList(),
)

/** Opens the checkout to switch to [plan]. */
data class ChangeProPlan(val plan: String) : ScreenEffect

/** Starts Gemini Live through the permission check; [useRelay] for plans without direct access. */
data class StartGeminiLive(val useRelay: Boolean) : ScreenEffect

/** Pro plan, account, quota and model settings for an active subscriber. */
class ProSubscriptionSettingsViewModel(
    private val platform: ProAccountPlatform,
    private val settings: ProSettingsStore,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    private val _state = MutableStateFlow(ProSubscriptionSettingsScreenState())
    val state: StateFlow<ProSubscriptionSettingsScreenState> = _state.asStateFlow()
    private val effects = ScreenEffects()
    val effectFlow get() = effects.flow

    private var draft = ProSubscriptionSettingsDraft()
    private var models = ProSubscriptionModelLabels()
    private var systemPrompt = ""
    private var started = false

    /** Server answers are applied only while the screen is visible, as before. */
    var visible = false

    /** Copies the working draft to the screen. */
    private fun publish() {
        _state.value = _state.value.copy(ui = draft.toUiState(models, systemPrompt))
    }

    /** Loads the screen once; false (with a message) when there is no active plan. */
    fun start(): Boolean {
        if (started) return true
        if (!platform.entitlement.isActiveLocally()) {
            effects.toast("No active Pro plan found.")
            effects.send(CloseScreen)
            return false
        }
        started = true
        load()
        return true
    }

    private fun load() {
        draft = ProSubscriptionSettingsDraft()
        models = ProSubscriptionModelLabels()
        _state.value = ProSubscriptionSettingsScreenState(
            liveEconomy = settings.liveEconomy,
            liveModes = settings.defaultLiveModes(),
        )
        val toggles = settings.loadToggles()
        draft.cloudSync = toggles.cloudSync
        draft.prioritySupport = toggles.prioritySupport
        draft.pluginRewards = toggles.pluginRewards
        draft.earlyAccessDevices = toggles.earlyAccessDevices
        draft.backupFrequencyIndex = toggles.backupFrequencyIndex.coerceIn(0, FREQUENCY_COUNT - 1)
        draft.supportChannelIndex = toggles.supportChannelIndex.coerceIn(0, SUPPORT_COUNT - 1)

        val savedRequestsModel = settings.requestsModel
        val savedQuestionsModel = settings.questionsModel
        val savedTasksModel = settings.tasksModel
        systemPrompt = settings.systemPrompt

        models.includeSaved(listOf(savedRequestsModel, savedQuestionsModel, savedTasksModel))
        draft.requestsModelIndex = models.positionOf(savedRequestsModel)
        draft.questionsModelIndex = models.positionOf(savedQuestionsModel)
        draft.tasksModelIndex = models.positionOf(savedTasksModel)

        draft.accountEmail = "Email: ${platform.accountEmail().ifBlank { "-" }}"
        draft.accountToken = "API token: ${ProSubscriptionSettingsFormat.maskToken(platform.apiToken())}"
        draft.accountSubscription = "Subscription: loading..."

        refreshPlanDetails()
        refreshAccount()
        refreshQuota()
        refreshModels()
        publish()
    }

    /** Applies a server answer while the screen is visible, then publishes. */
    private fun onVisible(block: () -> Unit) {
        if (!visible) return
        block()
        publish()
    }

    // ── Actions (each publishes where the screen always did) ──

    fun setLiveEconomy(enabled: Boolean) {
        settings.liveEconomy = enabled
        _state.value = _state.value.copy(liveEconomy = enabled)
    }

    fun refreshPlan() {
        effects.toast("Checking subscription status…")
        viewModelScope.launch {
            val result = withContext(io) { platform.verifyNow(strict = null) }
            onVisible {
                refreshPlanDetails()
                effects.toast(result.message)
            }
        }
        publish()
    }

    fun refreshAccountNow() {
        refreshAccount()
        publish()
    }

    fun refreshQuotaNow() {
        refreshQuota()
        publish()
    }

    fun refreshModelsNow() {
        refreshModels()
        publish()
    }

    fun joinBetaCloud() {
        draft.betaStatus = "Sending your beta cloud signup..."
        viewModelScope.launch {
            val result = withContext(io) { platform.registerBetaCloudInterest() }
            onVisible {
                result.onSuccess { signup ->
                    val countSuffix = signup.interestedCount?.let { " Current interested users: $it." } ?: ""
                    draft.betaStatus = signup.message + countSuffix
                    effects.toast("Beta cloud interest recorded")
                }.onFailure {
                    draft.betaStatus = "Signup failed: ${it.message ?: "unknown error"}"
                    effects.toast(platform.relayUnavailableHint(it) ?: "Could not submit beta cloud interest", long = true)
                }
            }
        }
        publish()
    }

    fun startGeminiLive() {
        effects.send(StartGeminiLive(useRelay = !platform.entitlement.hasPaidPlan()))
        effects.toast("Checking Live microphone and notification permissions")
    }

    fun setCloudSync(enabled: Boolean) = editToggles { draft.cloudSync = enabled }
    fun setPrioritySupport(enabled: Boolean) = editToggles { draft.prioritySupport = enabled }
    fun setPluginRewards(enabled: Boolean) = editToggles { draft.pluginRewards = enabled }
    fun setEarlyAccessDevices(enabled: Boolean) = editToggles { draft.earlyAccessDevices = enabled }
    fun setBackupFrequency(index: Int) = editToggles { draft.backupFrequencyIndex = index.coerceIn(0, FREQUENCY_COUNT - 1) }
    fun setSupportChannel(index: Int) = editToggles { draft.supportChannelIndex = index.coerceIn(0, SUPPORT_COUNT - 1) }

    private inline fun editToggles(change: () -> Unit) {
        change()
        saveSettings()
        publish()
    }

    fun selectRequestsModel(label: String) {
        selectModel(label) { draft.requestsModelIndex = it }
        saveSettings()
        refreshQuota()
    }

    fun selectQuestionsModel(label: String) {
        selectModel(label) { draft.questionsModelIndex = it }
        saveSettings()
    }

    fun selectTasksModel(label: String) {
        selectModel(label) { draft.tasksModelIndex = it }
        saveSettings()
    }

    fun setSystemPrompt(prompt: String) {
        systemPrompt = prompt
        settings.systemPrompt = prompt
        publish()
    }

    fun resetSystemPrompt() {
        settings.resetSystemPrompt()
        systemPrompt = settings.systemPrompt
        publish()
    }

    fun changePlan(plan: String) {
        if (platform.entitlement.provider == "play_billing") {
            effects.send(OpenStoreSubscriptions)
        } else {
            effects.send(ChangeProPlan(plan))
            effects.send(CloseScreen)
        }
    }

    fun cancelSubscription() {
        if (platform.entitlement.provider == "play_billing") {
            effects.send(OpenStoreSubscriptions)
            return
        }
        viewModelScope.launch {
            val result = withContext(io) { platform.cancelSubscription() }
            onVisible {
                result.onSuccess { cancel ->
                    platform.entitlement.applyCancellation(cancel)
                    effects.toast(cancel.message, long = true)
                    if (!cancel.active) effects.send(CloseScreen) else load()
                }.onFailure {
                    effects.toast("Unable to cancel subscription: ${it.message}", long = true)
                }
            }
        }
    }

    // ── Loading ──

    /** Selects the model with exactly [label] (no-op when it is not listed), then publishes. */
    private inline fun selectModel(label: String, select: (Int) -> Unit) {
        val index = models.labels.indexOfFirst { it == label }
        if (index >= 0) select(index)
        publish()
    }

    private fun saveSettings() {
        settings.saveToggles(
            ProSettingsToggles(
                cloudSync = draft.cloudSync,
                prioritySupport = draft.prioritySupport,
                pluginRewards = draft.pluginRewards,
                earlyAccessDevices = draft.earlyAccessDevices,
                backupFrequencyIndex = draft.backupFrequencyIndex,
                supportChannelIndex = draft.supportChannelIndex,
            ),
        )
        settings.requestsModel = models.modelAt(draft.requestsModelIndex)
        settings.questionsModel = models.modelAt(draft.questionsModelIndex)
        settings.tasksModel = models.modelAt(draft.tasksModelIndex)
        settings.systemPrompt = systemPrompt
    }

    private fun refreshPlanDetails() {
        val local = platform.localStatus()
        val lastVerifiedAt = platform.entitlement.lastVerifiedAt
        draft.planStatus = if (local.active) "Status: Active" else "Status: Inactive"
        draft.plan = "Plan: ${local.plan.ifBlank { "none" }}"
        draft.expires = "Expires: ${if (local.expiresAtMs > 0L) formatLocalDate(local.expiresAtMs) else "Unknown"}"
        draft.verified = "Last verified: ${if (lastVerifiedAt > 0L) formatLocalDateTime(lastVerifiedAt) else "Never"}"
    }

    private fun refreshQuota() {
        val model = models.modelAt(draft.requestsModelIndex)
        draft.showQuotaLoading(model)
        viewModelScope.launch {
            val result = withContext(io) { platform.fetchQuota(model) }
            onVisible {
                result.onSuccess(draft::showQuotaInfo).onFailure {
                    val reason = platform.relayUnavailableHint(it) ?: it.message ?: "unknown error"
                    draft.showQuotaError("Quota unavailable: $reason")
                }
            }
        }
    }

    private fun refreshAccount() {
        draft.accountEmail = "Email: loading..."
        draft.accountToken = "API token: loading..."
        draft.accountSubscription = "Subscription: loading..."
        viewModelScope.launch {
            val result = withContext(io) { platform.fetchAccount() }
            onVisible {
                result.onSuccess { account ->
                    if (account.email.isNotBlank()) platform.setAccountEmail(account.email)
                    draft.accountEmail = "Email: ${account.email.ifBlank { "-" }}"
                    draft.accountToken = "API token: ${ProSubscriptionSettingsFormat.maskToken(account.apiToken)}"
                    draft.accountSubscription = "Subscription: ${account.subscriptionStatus} · ${account.plan}"
                }.onFailure {
                    draft.accountEmail = "Email: -"
                    draft.accountToken = "API token: -"
                    draft.accountSubscription = "Subscription: unavailable (${platform.relayUnavailableHint(it) ?: it.message ?: "unknown error"})"
                }
            }
        }
    }

    private fun refreshModels() {
        viewModelScope.launch {
            val result = withContext(io) { platform.fetchModelCatalog() }
            onVisible {
                result.onSuccess { catalog ->
                    if (catalog.models.isEmpty()) {
                        effects.toast("No models returned by server")
                        return@onSuccess
                    }
                    _state.value = _state.value.copy(liveModes = catalog.liveModes)
                    val currentRequests = models.modelAt(draft.requestsModelIndex)
                    val currentQuestions = models.modelAt(draft.questionsModelIndex)
                    val currentTasks = models.modelAt(draft.tasksModelIndex)

                    val merged = linkedMapOf<String, String>()
                    catalog.models.forEach { option ->
                        val id = option.id.trim()
                        if (id.isBlank()) return@forEach
                        val baseLabel = option.label.trim().ifBlank { id }
                        val label = if (option.supportsVision && !baseLabel.contains("vision", ignoreCase = true)) "$baseLabel · Vision" else baseLabel
                        if (!merged.containsKey(id)) merged[id] = label
                    }
                    models.replaceAll(merged)
                    draft.requestsModelIndex = models.positionOf(currentRequests)
                    draft.questionsModelIndex = models.positionOf(currentQuestions)
                    draft.tasksModelIndex = models.positionOf(currentTasks)

                    effects.toast("Loaded ${catalog.models.size} models")
                    refreshQuota()
                }.onFailure {
                    effects.toast(platform.relayUnavailableHint(it) ?: "Could not load models: ${it.message ?: "unknown error"}", long = true)
                }
            }
        }
    }

    private companion object {
        /** Option counts of the backup-frequency and support-channel pickers on the shared screen. */
        const val FREQUENCY_COUNT = 3
        const val SUPPORT_COUNT = 3
    }
}
