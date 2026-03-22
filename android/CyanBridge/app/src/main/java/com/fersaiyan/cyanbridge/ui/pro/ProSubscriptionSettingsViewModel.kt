package com.fersaiyan.cyanbridge.ui.pro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionRelayClient
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionVerifier
import com.fersaiyan.cyanbridge.agent.SubscriptionCheckoutPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ProSubscriptionSettingsState(
    val isLoading: Boolean = false,
    val statusText: String = "Loading...",
    val planText: String = "Loading...",
    val expiresText: String = "Loading...",
    val verifiedText: String = "Loading...",
    val emailText: String = "Loading...",
    val tokenText: String = "Loading...",
    val subscriptionText: String = "Loading...",
    val betaCloudStatus: String = "",
    val modelsLoading: Boolean = false,
    val requestsModel: String = "auto",
    val questionsModel: String = "auto",
    val tasksModel: String = "auto",
    val availableModels: List<ProSubscriptionRelayClient.ModelOption> = emptyList(),
    val cloudSync: Boolean = true,
    val prioritySupport: Boolean = true,
    val pluginRewards: Boolean = true,
    val earlyAccessDevices: Boolean = true,
    val backupFrequencyIdx: Int = 1,
    val supportChannelIdx: Int = 0,
    val expandedSections: Map<String, Boolean> = mapOf(
        "plan" to true,
        "beta" to false,
        "ai" to true,
        "cloud" to false,
        "ecosystem" to false,
    ),
    val successMessage: String? = null,
)

class ProSubscriptionSettingsViewModel(private val context: Context) : ViewModel() {

    private val prefs = context.getSharedPreferences("pro_subscription_settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(ProSubscriptionSettingsState())
    val uiState: StateFlow<ProSubscriptionSettingsState> = _uiState.asStateFlow()

    init {
        if (!ProSubscriptionPrefs.isActiveLocally(context)) {
            viewModelScope.launch {
                Toast.makeText(context, "No active Pro plan found.", Toast.LENGTH_SHORT).show()
            }
        }
        loadSettings()
    }

    private fun loadSettings() {
        loadLocalSettings()
        refreshPlanDetails()
        refreshAccount()
        refreshModels()
    }

    private fun loadLocalSettings() {
        _uiState.update { state ->
            state.copy(
                cloudSync = prefs.getBoolean("cloud_sync", true),
                prioritySupport = prefs.getBoolean("priority_support", true),
                pluginRewards = prefs.getBoolean("plugin_rewards", true),
                earlyAccessDevices = prefs.getBoolean("early_access_devices", true),
                backupFrequencyIdx = prefs.getInt("backup_frequency_idx", 1),
                supportChannelIdx = prefs.getInt("support_channel_idx", 0),
                requestsModel = ProSubscriptionAiPrefs.getRequestsModel(context),
                questionsModel = ProSubscriptionAiPrefs.getQuestionsModel(context),
                tasksModel = ProSubscriptionAiPrefs.getTasksModel(context),
                expandedSections = mapOf(
                    "plan" to prefs.getBoolean("section_plan_expanded", true),
                    "beta" to prefs.getBoolean("section_beta_expanded", false),
                    "ai" to prefs.getBoolean("section_ai_expanded", true),
                    "cloud" to prefs.getBoolean("section_cloud_expanded", false),
                    "ecosystem" to prefs.getBoolean("section_ecosystem_expanded", false),
                ),
            )
        }
    }

    fun refreshPlanDetails() {
        viewModelScope.launch {
            val local = ProSubscriptionVerifier.localStatus(context)
            val expiresText = if (local.expiresAtMs > 0L) {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(local.expiresAtMs))
            } else {
                "Unknown"
            }
            val lastVerifiedAt = ProSubscriptionPrefs.getLastVerifiedAt(context)
            val verifiedText = if (lastVerifiedAt > 0L) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(lastVerifiedAt))
            } else {
                "Never"
            }

            _uiState.update { state ->
                state.copy(
                    statusText = if (local.active) "Status: Active" else "Status: Inactive",
                    planText = "Plan: ${local.plan.ifBlank { "none" }}",
                    expiresText = "Expires: $expiresText",
                    verifiedText = "Last verified: $verifiedText",
                )
            }
        }
    }

    fun verifyNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, successMessage = "Checking subscription status...") }
            try {
                val result = ProSubscriptionVerifier.verifyNow(context)
                refreshPlanDetails()
                _uiState.update { it.copy(successMessage = result.message) }
            } catch (e: Exception) {
                _uiState.update { it.copy(successMessage = "Verification failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun refreshAccount() {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    emailText = "Email: loading...",
                    tokenText = "API token: loading...",
                    subscriptionText = "Subscription: loading...",
                )
            }

            try {
                val result = ProSubscriptionRelayClient.fetchAccountInfo(context)
                result.onSuccess { account ->
                    if (account.email.isNotBlank()) {
                        ProSubscriptionServerPrefs.setAccountEmail(context, account.email)
                    }
                    _uiState.update { state ->
                        state.copy(
                            emailText = "Email: ${account.email.ifBlank { "-" }}",
                            tokenText = "API token: ${maskToken(account.apiToken)}",
                            subscriptionText = "Subscription: ${account.subscriptionStatus} · ${account.plan}",
                        )
                    }
                }.onFailure { error ->
                    val hint = ProSubscriptionRelayClient.relayUnavailableHint(error)
                    _uiState.update { state ->
                        state.copy(
                            emailText = "Email: -",
                            tokenText = "API token: -",
                            subscriptionText = if (hint != null) {
                                "Subscription: unavailable ($hint)"
                            } else {
                                "Subscription: unavailable (${error.message ?: "unknown error"})"
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        emailText = "Email: -",
                        tokenText = "API token: -",
                        subscriptionText = "Subscription: error",
                    )
                }
            }
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            _uiState.update { it.copy(modelsLoading = true) }
            try {
                val result = ProSubscriptionRelayClient.fetchAvailableModels(context)
                result.onSuccess { models ->
                    val defaultModels = listOf(
                        ProSubscriptionRelayClient.ModelOption("auto", "auto", 1),
                        ProSubscriptionRelayClient.ModelOption("gpt-5.4", "gpt-5.4", 1),
                        ProSubscriptionRelayClient.ModelOption("minimax/minimax-m2.5", "minimax/minimax-m2.5", 1),
                        ProSubscriptionRelayClient.ModelOption("z-ai/glm-5", "z-ai/glm-5", 1),
                        ProSubscriptionRelayClient.ModelOption("google/gemini-3-flash-preview", "google/gemini-3-flash-preview", 1),
                    )

                    val merged = mutableMapOf<String, ProSubscriptionRelayClient.ModelOption>()
                    defaultModels.forEach { merged[it.id] = it }
                    models.forEach { merged[it.id] = it }

                    val requests = _uiState.value.requestsModel
                    val questions = _uiState.value.questionsModel
                    val tasks = _uiState.value.tasksModel
                    if (!merged.containsKey(requests)) merged[requests] = ProSubscriptionRelayClient.ModelOption(requests, requests, 1)
                    if (!merged.containsKey(questions)) merged[questions] = ProSubscriptionRelayClient.ModelOption(questions, questions, 1)
                    if (!merged.containsKey(tasks)) merged[tasks] = ProSubscriptionRelayClient.ModelOption(tasks, tasks, 1)

                    _uiState.update { state ->
                        state.copy(
                            modelsLoading = false,
                            availableModels = merged.values.toList(),
                        )
                    }
                }.onFailure { error ->
                    _uiState.update { it.copy(modelsLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(modelsLoading = false) }
            }
        }
    }

    fun setRequestsModel(model: String) {
        _uiState.update { it.copy(requestsModel = model) }
    }

    fun setQuestionsModel(model: String) {
        _uiState.update { it.copy(questionsModel = model) }
    }

    fun setTasksModel(model: String) {
        _uiState.update { it.copy(tasksModel = model) }
    }

    fun setCloudSync(enabled: Boolean) {
        _uiState.update { it.copy(cloudSync = enabled) }
    }

    fun setPrioritySupport(enabled: Boolean) {
        _uiState.update { it.copy(prioritySupport = enabled) }
    }

    fun setPluginRewards(enabled: Boolean) {
        _uiState.update { it.copy(pluginRewards = enabled) }
    }

    fun setEarlyAccessDevices(enabled: Boolean) {
        _uiState.update { it.copy(earlyAccessDevices = enabled) }
    }

    fun setBackupFrequency(idx: Int) {
        _uiState.update { it.copy(backupFrequencyIdx = idx) }
    }

    fun setSupportChannel(idx: Int) {
        _uiState.update { it.copy(supportChannelIdx = idx) }
    }

    fun toggleSection(section: String) {
        _uiState.update { state ->
            val current = state.expandedSections[section] ?: false
            val new = state.expandedSections.toMutableMap()
            new[section] = !current
            state.copy(expandedSections = new)
        }
    }

    fun saveSettings() {
        val state = _uiState.value

        prefs.edit()
            .putBoolean("cloud_sync", state.cloudSync)
            .putBoolean("priority_support", state.prioritySupport)
            .putBoolean("plugin_rewards", state.pluginRewards)
            .putBoolean("early_access_devices", state.earlyAccessDevices)
            .putInt("backup_frequency_idx", state.backupFrequencyIdx)
            .putInt("support_channel_idx", state.supportChannelIdx)
            .putBoolean("section_plan_expanded", state.expandedSections["plan"] ?: true)
            .putBoolean("section_beta_expanded", state.expandedSections["beta"] ?: false)
            .putBoolean("section_ai_expanded", state.expandedSections["ai"] ?: true)
            .putBoolean("section_cloud_expanded", state.expandedSections["cloud"] ?: false)
            .putBoolean("section_ecosystem_expanded", state.expandedSections["ecosystem"] ?: false)
            .apply()

        ProSubscriptionAiPrefs.setRequestsModel(context, state.requestsModel)
        ProSubscriptionAiPrefs.setQuestionsModel(context, state.questionsModel)
        ProSubscriptionAiPrefs.setTasksModel(context, state.tasksModel)

        _uiState.update { it.copy(successMessage = "Pro settings saved") }
    }

    fun joinBetaCloud() {
        viewModelScope.launch {
            _uiState.update { it.copy(betaCloudStatus = "Sending your beta cloud signup...") }

            kotlin.concurrent.thread {
                val result = ProSubscriptionRelayClient.registerBetaCloudInterest(context)
                val status = result.fold(
                    onSuccess = { signup ->
                        val countSuffix = signup.interestedCount?.let { " Current interested users: $it." } ?: ""
                        signup.message + countSuffix
                    },
                    onFailure = { error ->
                        val hint = ProSubscriptionRelayClient.relayUnavailableHint(error)
                        "Signup failed: ${error.message ?: "unknown error"}"
                    },
                )

                _uiState.update { it.copy(betaCloudStatus = status) }
            }
        }
    }

    fun showChangePlanDialog(onPlanSelected: (String) -> Unit) {
        val email = ProSubscriptionServerPrefs.getAccountEmail(context)
        if (email.isBlank()) {
            onPlanSelected("prompt_email")
        } else {
            onPlanSelected("show_plans")
        }
    }

    fun launchWebCheckoutWithEmail(emailHint: String = "") {
        val baseUrl = SubscriptionCheckoutPolicy.resolveWebCheckoutUrl(context)
        if (baseUrl.isBlank()) {
            _uiState.update { it.copy(successMessage = "Web checkout is not configured yet") }
            return
        }

        val parsedBase = runCatching { Uri.parse(baseUrl) }.getOrNull()
        if (parsedBase == null || !parsedBase.isAbsolute || parsedBase.scheme.isNullOrBlank()) {
            _uiState.update { it.copy(successMessage = "Invalid checkout URL: $baseUrl") }
            return
        }

        val callback = Uri.Builder()
            .scheme("fersaiyan")
            .authority("pro-sub")
            .appendPath("callback")
            .build()

        val target = Uri.parse(baseUrl).buildUpon()
            .appendQueryParameter("plan", "standard")
            .appendQueryParameter("platform", "android")
            .appendQueryParameter("package_name", context.packageName)
            .appendQueryParameter("return_url", callback.toString())
            .apply {
                val apiToken = ProSubscriptionServerPrefs.getApiToken(context)
                if (apiToken.isNotBlank()) {
                    appendQueryParameter("api_token", apiToken)
                }
                val accountEmail = ProSubscriptionServerPrefs.getAccountEmail(context)
                val finalEmail = emailHint.ifBlank { accountEmail }
                if (finalEmail.isNotBlank()) {
                    appendQueryParameter("email", finalEmail)
                }
            }
            .build()

        val intent = Intent(Intent.ACTION_VIEW, target)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            _uiState.update { it.copy(successMessage = "Opening web checkout...") }
        } catch (e: Exception) {
            _uiState.update { it.copy(successMessage = "Unable to open checkout: ${e.message}") }
        }
    }

    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }

    private fun maskToken(token: String): String {
        if (token.isBlank()) return "-"
        if (token.length <= 12) return token
        return token.take(8) + "..." + token.takeLast(4)
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ProSubscriptionSettingsViewModel(context.applicationContext) as T
        }
    }
}