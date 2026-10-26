package com.fersaiyan.cyanbridge.agent

import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.os.Bundle
import android.view.SoundEffectConstants
import com.fersaiyan.cyanbridge.ui.nav.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fersaiyan.cyanbridge.ai.live.GeminiLivePermissionGateActivity
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPromptResolver
import com.fersaiyan.cyanbridge.shared.billing.ChangeProPlan
import com.fersaiyan.cyanbridge.shared.billing.OpenStoreSubscriptions
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionSettingsUiState
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionSettingsViewModel
import com.fersaiyan.cyanbridge.shared.billing.StartGeminiLive
import com.fersaiyan.cyanbridge.shared.ui.pro.ProSubscriptionSettingsScreen
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffect
import com.fersaiyan.cyanbridge.ui.collectScreenEffects
import com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.Locale
import kotlinx.coroutines.Dispatchers

/** Hosts the shared Pro settings screen; [ProSubscriptionSettingsViewModel] owns its state. */
class ProSubscriptionSettingsDestination : NavScreen() {
    private val viewModel: ProSubscriptionSettingsViewModel by viewModels {
        viewModelFactory {
            initializer {
                ProSubscriptionSettingsViewModel(AndroidProAccountPlatform(applicationContext), AndroidProSettingsStore(applicationContext), Dispatchers.IO)
            }
        }
    }

    internal val state: ProSubscriptionSettingsUiState get() = viewModel.state.value.ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectScreenEffects(viewModel.effectFlow, ::handleEffect)
        if (!viewModel.start()) return
        setContent {
            val screen by viewModel.state.collectAsState()
            val economyMode = screen.liveModes.first { it.id == "economy" }
            val privateMode = screen.liveModes.first { it.id == "private" }
            ProSubscriptionSettingsScreen(
                liveEconomy = screen.liveEconomy,
                liveEconomyLabel = economyMode.displayLabel,
                livePrivateLabel = privateMode.displayLabel,
                liveEconomyDescription = economyMode.description,
                livePrivateDescription = privateMode.description,
                onLiveEconomyChange = viewModel::setLiveEconomy,
                state = screen.ui,
                onRefreshPlan = clicked(viewModel::refreshPlan),
                onChangePlan = viewModel::changePlan,
                onCancelSubscription = viewModel::cancelSubscription,
                onRefreshAccount = clicked(viewModel::refreshAccountNow),
                onRefreshQuota = clicked(viewModel::refreshQuotaNow),
                onRefreshModels = clicked(viewModel::refreshModelsNow),
                onJoinBeta = clicked(viewModel::joinBetaCloud),
                onStartGeminiLive = viewModel::startGeminiLive,
                onCloudSyncChange = viewModel::setCloudSync,
                onPrioritySupportChange = viewModel::setPrioritySupport,
                onPluginRewardsChange = viewModel::setPluginRewards,
                onEarlyAccessDevicesChange = viewModel::setEarlyAccessDevices,
                onBackupFrequencyChange = viewModel::setBackupFrequency,
                onSupportChannelChange = viewModel::setSupportChannel,
                onRequestsModelChange = viewModel::selectRequestsModel,
                onQuestionsModelChange = viewModel::selectQuestionsModel,
                onTasksModelChange = viewModel::selectTasksModel,
                onSystemPromptChange = viewModel::setSystemPrompt,
                onResetSystemPrompt = viewModel::resetSystemPrompt,
                onBack = ::finish,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.visible = true
    }

    override fun onStop() {
        viewModel.visible = false
        super.onStop()
    }

    /** Refresh and sign-up actions were hidden buttons whose click played the standard sound; keep it. */
    private fun clicked(action: () -> Unit): () -> Unit = {
        view.playSoundEffect(SoundEffectConstants.CLICK)
        action()
    }

    private fun handleEffect(effect: ScreenEffect) {
        when (effect) {
            OpenStoreSubscriptions -> activity.openPlaySubscriptionManagement()
            is ChangeProPlan -> startActivity(AppRoute.PRO_SUBSCRIPTION.intent(this).apply {
                putExtra(ProSubscriptionDestination.EXTRA_INITIAL_PLAN, effect.plan)
                putExtra(ProSubscriptionDestination.EXTRA_CHANGE_PLAN, true)
            })
            // Notification-driven Live survives lock/screen-off; this screen only starts it. The base
            // question (no strict "Answer only in X") lets Live switch languages permissively.
            is StartGeminiLive -> GeminiLivePermissionGateActivity.launch(
                context = this,
                language = AppLanguagePreferences.selected(this).languageTag.ifBlank { Locale.getDefault().toLanguageTag() },
                imagePrompt = ImageQuestionPromptResolver.baseQuestion(settings = ImageQuestionPreferences.get(this), userQuestion = null),
                initialImagePath = null,
                initialPrompt = null,
                useRelay = effect.useRelay,
            )
        }
    }
}
