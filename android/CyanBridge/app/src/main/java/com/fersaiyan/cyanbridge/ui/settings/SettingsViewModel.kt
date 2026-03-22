package com.fersaiyan.cyanbridge.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.agent.AgentProviderType
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.ai.router.CliRelayBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isProSubscribed: Boolean = false,
    val proPlan: String = "none",
    val proExpiresAt: Long = 0L,
    val providerType: AgentProviderType = AgentProviderType.PRO_SUBSCRIPTION,
    val aiProvider: AiProviderType = AiProviderType.CLI_RELAY,
    val relayBaseUrl: String = "",
    val relayBackend: CliRelayBackend = CliRelayBackend.GEMINI,
    val requestsModel: String = "auto",
    val questionsModel: String = "auto",
    val tasksModel: String = "auto",
    val isDarkTheme: Boolean = true,
    val isLoading: Boolean = false,
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
            val requestsModel = com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs.getRequestsModel(context)
            val questionsModel = com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs.getQuestionsModel(context)
            val tasksModel = com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs.getTasksModel(context)
            val isDark = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
                .getBoolean("dark_theme", true)

            _uiState.value = SettingsUiState(
                isProSubscribed = isPro,
                proPlan = plan,
                proExpiresAt = expires,
                providerType = providerType,
                aiProvider = aiProvider,
                relayBaseUrl = relayUrl,
                relayBackend = relayBackend,
                requestsModel = requestsModel,
                questionsModel = questionsModel,
                tasksModel = tasksModel,
                isDarkTheme = isDark,
                isLoading = false,
            )
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

    fun setRequestsModel(model: String) {
        com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs.setRequestsModel(context, model)
        _uiState.value = _uiState.value.copy(requestsModel = model)
    }

    fun setQuestionsModel(model: String) {
        com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs.setQuestionsModel(context, model)
        _uiState.value = _uiState.value.copy(questionsModel = model)
    }

    fun setTasksModel(model: String) {
        com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs.setTasksModel(context, model)
        _uiState.value = _uiState.value.copy(tasksModel = model)
    }

    fun setDarkTheme(isDark: Boolean) {
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("dark_theme", isDark)
            .apply()
        _uiState.value = _uiState.value.copy(isDarkTheme = isDark)
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(context.applicationContext) as T
        }
    }
}
