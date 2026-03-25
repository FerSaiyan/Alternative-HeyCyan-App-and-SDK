package com.fersaiyan.cyanbridge.ui.pro

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionRelayClient
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionVerifier
import com.fersaiyan.cyanbridge.agent.SubscriptionCheckoutPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

data class PlanDetails(
    val status: String,
    val plan: String,
    val expiresAt: String,
    val lastVerified: String,
    val provider: String,
)

data class QuotaDetails(
    val text: String,
    val isLoading: Boolean,
)

data class AccountDetails(
    val email: String,
    val token: String,
    val subscription: String,
    val isLoading: Boolean,
)

data class ProScreenState(
    val isSubscribed: Boolean = false,
    val selectedPlan: String = "standard",
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    val webCheckoutEnabled: Boolean = false,
    val planDetails: PlanDetails? = null,
    val quota: QuotaDetails = QuotaDetails("", false),
    val account: AccountDetails = AccountDetails("", "", "", false),
    val betaCloudMessage: String? = null,
    val betaCloudLoading: Boolean = false,
    val availableModels: List<ProSubscriptionRelayClient.ModelOption> = emptyList(),
    val requestsModel: String = "auto",
    val questionsModel: String = "auto",
    val tasksModel: String = "auto",
    val showEmailDialog: Boolean = false,
)

class ProViewModel(
    private val context: Context,
    private val onNavigateToSettings: () -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProScreenState())
    val uiState: StateFlow<ProScreenState> = _uiState.asStateFlow()

    init {
        loadState()
    }

    fun loadState() {
        viewModelScope.launch(Dispatchers.IO) {
            val isSubscribed = ProSubscriptionPrefs.isActiveLocally(context)
            val webEnabled = SubscriptionCheckoutPolicy.isWebCheckoutEnabled(context)
            val modelMap = buildModelLabelMap()

            _uiState.value = _uiState.value.copy(
                isSubscribed = isSubscribed,
                webCheckoutEnabled = webEnabled,
                availableModels = modelMap,
                requestsModel = ProSubscriptionAiPrefs.getRequestsModel(context),
                questionsModel = ProSubscriptionAiPrefs.getQuestionsModel(context),
                tasksModel = ProSubscriptionAiPrefs.getTasksModel(context),
            )

            if (isSubscribed) {
                loadPlanDetails()
                refreshQuota()
                loadAccount()
            }
        }
    }

    private fun loadPlanDetails() {
        val local = ProSubscriptionVerifier.localStatus(context)
        val expires = local.expiresAtMs
        val expiresText = if (expires > 0L) {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(expires))
        } else {
            "Never"
        }
        val lastVerified = ProSubscriptionPrefs.getLastVerifiedAt(context)
        val verifiedText = if (lastVerified > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(lastVerified))
        } else {
            "Never"
        }

        _uiState.value = _uiState.value.copy(
            planDetails = PlanDetails(
                status = if (local.active) "Active" else "Inactive",
                plan = local.plan.ifBlank { "none" },
                expiresAt = expiresText,
                lastVerified = verifiedText,
                provider = ProSubscriptionPrefs.getProvider(context),
            ),
        )
    }

    fun verifySubscription() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val result = ProSubscriptionVerifier.verifyNow(context)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    successMessage = result.message,
                )
                loadPlanDetails()
            }
        }
    }

    fun refreshQuota() {
        _uiState.value = _uiState.value.copy(
            quota = QuotaDetails("Quota: loading...", true),
        )
        viewModelScope.launch(Dispatchers.IO) {
            val model = _uiState.value.requestsModel
            val result = ProSubscriptionRelayClient.fetchQuota(context, model)
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { quota ->
                        val limit = if (quota.limit > 0) quota.limit.toString() else "unlimited"
                        _uiState.value = _uiState.value.copy(
                            quota = QuotaDetails(
                                "Quota (${quota.model}): ${quota.remaining} left · used ${quota.used}/$limit",
                                false,
                            ),
                        )
                    },
                    onFailure = { err ->
                        val hint = ProSubscriptionRelayClient.relayUnavailableHint(err)
                        _uiState.value = _uiState.value.copy(
                            quota = QuotaDetails(
                                if (hint != null) "Quota unavailable: $hint" else "Quota unavailable: ${err.message}",
                                false,
                            ),
                        )
                    },
                )
            }
        }
    }

    private fun loadAccount() {
        _uiState.value = _uiState.value.copy(
            account = AccountDetails("loading...", "loading...", "loading...", true),
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result = ProSubscriptionRelayClient.fetchAccountInfo(context)
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { account ->
                        if (account.email.isNotBlank()) {
                            ProSubscriptionServerPrefs.setAccountEmail(context, account.email)
                        }
                        if (account.apiToken.isNotBlank()) {
                            ProSubscriptionServerPrefs.setApiToken(context, account.apiToken)
                        }
                        _uiState.value = _uiState.value.copy(
                            account = AccountDetails(
                                email = account.email.ifBlank { "-" },
                                token = maskToken(account.apiToken),
                                subscription = "${account.subscriptionStatus} · ${account.plan}",
                                isLoading = false,
                            ),
                        )
                    },
                    onFailure = { err ->
                        val hint = ProSubscriptionRelayClient.relayUnavailableHint(err)
                        _uiState.value = _uiState.value.copy(
                            account = AccountDetails(
                                email = "-",
                                token = "-",
                                subscription = if (hint != null) "unavailable ($hint)" else "unavailable",
                                isLoading = false,
                            ),
                        )
                    },
                )
            }
        }
    }

    private fun maskToken(token: String): String {
        if (token.isBlank()) return "-"
        return if (token.length > 12) token.take(8) + "..." + token.takeLast(4) else token
    }

    fun setSelectedPlan(plan: String) {
        _uiState.value = _uiState.value.copy(selectedPlan = plan)
    }

    fun subscribe() {
        val plan = _uiState.value.selectedPlan
        if (plan == "free_trial") {
            activateFreeTrial()
            return
        }
        val storedEmail = ProSubscriptionServerPrefs.getAccountEmail(context)
        if (storedEmail.isBlank()) {
            _uiState.value = _uiState.value.copy(showEmailDialog = true)
            return
        }
        launchWebCheckout(plan, storedEmail)
    }

    fun hideEmailDialog() {
        _uiState.value = _uiState.value.copy(showEmailDialog = false)
    }

    fun subscribeWithEmail(plan: String, email: String) {
        if (email.isNotBlank()) {
            ProSubscriptionServerPrefs.setAccountEmail(context, email)
        }
        launchWebCheckout(plan, email)
    }

    private fun launchWebCheckout(plan: String, email: String) {
        val baseUrl = SubscriptionCheckoutPolicy.resolveWebCheckoutUrl(context)
        if (baseUrl.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Web checkout not configured. Check server URL.")
            return
        }
        val callback = Uri.Builder()
            .scheme("fersaiyan")
            .authority("pro-sub")
            .appendPath("callback")
            .build()
        val target = Uri.parse(baseUrl).buildUpon()
            .appendQueryParameter("plan", plan)
            .appendQueryParameter("platform", "android")
            .appendQueryParameter("package_name", context.packageName)
            .appendQueryParameter("return_url", callback.toString())
            .apply {
                val apiToken = ProSubscriptionServerPrefs.getApiToken(context)
                if (apiToken.isNotBlank()) appendQueryParameter("api_token", apiToken)
                val finalEmail = email.ifBlank { ProSubscriptionServerPrefs.getAccountEmail(context) }
                if (finalEmail.isNotBlank()) appendQueryParameter("email", finalEmail)
            }
            .build()
        _uiState.value = _uiState.value.copy(
            successMessage = "OPEN_CHECKOUT:$target",
        )
    }

    fun activateFreeTrial() {
        val baseUrl = AiProviderPrefs.getRelayBaseUrl(context).trimEnd('/')
        if (baseUrl.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Server not configured.")
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val token = ProSubscriptionServerPrefs.getApiToken(context)
                val url = java.net.URL("$baseUrl/pro/activate-trial")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("Content-Type", "application/json")
                if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
                conn.doOutput = true
                conn.outputStream.write("{}".toByteArray())

                val code = conn.responseCode
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = org.json.JSONObject(body)
                val ok = json.optBoolean("ok", false)
                val expiresAtMs = json.optLong("expires_at_ms", 0L)
                val planName = json.optString("plan", "free_trial")
                val message = json.optString("message", "Unknown response")

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    if (ok && expiresAtMs > System.currentTimeMillis()) {
                        ProSubscriptionPrefs.setPlan(context, planName)
                        ProSubscriptionPrefs.setSubscribed(context, true)
                        ProSubscriptionPrefs.setExpiresAt(context, expiresAtMs)
                        ProSubscriptionPrefs.setPurchaseToken(context, "free_trial_${System.currentTimeMillis()}")
                        ProSubscriptionPrefs.setProvider(context, "server_verified")
                        ProSubscriptionPrefs.setLastVerifiedAt(context, System.currentTimeMillis())
                        _uiState.value = _uiState.value.copy(
                            isSubscribed = true,
                            successMessage = "$message — $planName activated",
                        )
                        loadPlanDetails()
                        refreshQuota()
                        loadAccount()
                        onNavigateToSettings()
                    } else {
                        _uiState.value = _uiState.value.copy(error = message)
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Free trial failed: ${e.message}",
                    )
                }
            }
        }
    }

    fun changePlan(plan: String) {
        val storedEmail = ProSubscriptionServerPrefs.getAccountEmail(context)
        launchWebCheckout(plan, storedEmail)
    }

    fun joinBetaCloud() {
        _uiState.value = _uiState.value.copy(betaCloudLoading = true, betaCloudMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            val result = ProSubscriptionRelayClient.registerBetaCloudInterest(context)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(betaCloudLoading = false)
                result.fold(
                    onSuccess = { signup ->
                        val count = signup.interestedCount?.let { " ($it interested)" } ?: ""
                        _uiState.value = _uiState.value.copy(
                            betaCloudMessage = signup.message + count,
                        )
                    },
                    onFailure = { err ->
                        val hint = ProSubscriptionRelayClient.relayUnavailableHint(err)
                        _uiState.value = _uiState.value.copy(
                            betaCloudMessage = if (hint != null) "Signup failed: $hint" else "Signup failed: ${err.message}",
                        )
                    },
                )
            }
        }
    }

    fun setRequestsModel(model: String) {
        ProSubscriptionAiPrefs.setRequestsModel(context, model)
        _uiState.value = _uiState.value.copy(requestsModel = model)
        refreshQuota()
    }

    fun setQuestionsModel(model: String) {
        ProSubscriptionAiPrefs.setQuestionsModel(context, model)
        _uiState.value = _uiState.value.copy(questionsModel = model)
    }

    fun setTasksModel(model: String) {
        ProSubscriptionAiPrefs.setTasksModel(context, model)
        _uiState.value = _uiState.value.copy(tasksModel = model)
    }

    fun refreshModels() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val result = ProSubscriptionRelayClient.fetchAvailableModels(context)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(isLoading = false)
                result.fold(
                    onSuccess = { models ->
                        val merged = linkedMapOf<String, String>()
                        merged["auto"] = "auto"
                        models.forEach { opt ->
                            merged[opt.id] = opt.label
                        }
                        val currentRequests = _uiState.value.requestsModel
                        val currentQuestions = _uiState.value.questionsModel
                        val currentTasks = _uiState.value.tasksModel
                        if (!merged.containsKey(currentRequests)) merged[currentRequests] = currentRequests
                        if (!merged.containsKey(currentQuestions)) merged[currentQuestions] = currentQuestions
                        if (!merged.containsKey(currentTasks)) merged[currentTasks] = currentTasks
                        _uiState.value = _uiState.value.copy(
                            availableModels = models,
                            requestsModel = currentRequests,
                            questionsModel = currentQuestions,
                            tasksModel = currentTasks,
                        )
                        refreshQuota()
                    },
                    onFailure = { err ->
                        val hint = ProSubscriptionRelayClient.relayUnavailableHint(err)
                        _uiState.value = _uiState.value.copy(
                            error = hint ?: "Could not load models: ${err.message}",
                        )
                    },
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearSuccess() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }

    private fun buildModelLabelMap(): List<ProSubscriptionRelayClient.ModelOption> {
        val defaults = listOf(
            "auto" to "auto",
            "minimax/minimax-m2.5" to "minimax/minimax-m2.5",
            "gpt-4o" to "gpt-4o",
            "google/gemini-3-flash-preview" to "google/gemini-3-flash-preview",
        )
        val seen = linkedSetOf<String>()
        val out = mutableListOf<ProSubscriptionRelayClient.ModelOption>()
        for ((id, label) in defaults) {
            if (seen.add(label)) out.add(ProSubscriptionRelayClient.ModelOption(id, label, 1))
        }
        return out
    }

    class Factory(
        private val context: Context,
        private val onNavigateToSettings: () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ProViewModel(context, onNavigateToSettings) as T
        }
    }
}
