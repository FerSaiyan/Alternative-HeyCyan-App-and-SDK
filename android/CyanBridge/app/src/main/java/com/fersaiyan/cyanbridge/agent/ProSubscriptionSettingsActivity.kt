package com.fersaiyan.cyanbridge.agent

import android.content.Intent
import android.os.Bundle
import android.view.SoundEffectConstants
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
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
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.collectScreenEffects
import com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers

/** Hosts the shared Pro settings screen; [ProSubscriptionSettingsViewModel] owns its state. */
class ProSubscriptionSettingsActivity : AppCompatActivity() {
    private val viewModel: ProSubscriptionSettingsViewModel by viewModels {
        viewModelFactory {
            initializer {
                ProSubscriptionSettingsViewModel(AndroidProAccountPlatform(applicationContext), AndroidProSettingsStore(applicationContext), Dispatchers.IO)
            }
        }
    }

    internal val state: ProSubscriptionSettingsUiState get() = viewModel.state.value.ui
    private lateinit var composeView: ComposeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        composeView = ComposeView(this).also(::setContentView)
        collectScreenEffects(viewModel.effectFlow, ::handleEffect)
        if (!viewModel.start()) return

        val appearancePreferences = AppearancePreferences(this)
        composeView.setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            val screen by viewModel.state.collectAsState()
            CyanBridgeTheme(appearance) {
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
        composeView.playSoundEffect(SoundEffectConstants.CLICK)
        action()
    }

    private fun handleEffect(effect: ScreenEffect) {
        when (effect) {
            OpenStoreSubscriptions -> openPlaySubscriptionManagement()
            is ChangeProPlan -> startActivity(Intent(this, ProSubscriptionActivity::class.java).apply {
                putExtra(ProSubscriptionActivity.EXTRA_INITIAL_PLAN, effect.plan)
                putExtra(ProSubscriptionActivity.EXTRA_CHANGE_PLAN, true)
            })
            // Notification-driven Live survives lock/screen-off; this screen only starts it. The base
            // question (no strict "Answer only in X") lets Live switch languages permissively.
            is StartGeminiLive -> GeminiLivePermissionGateActivity.launch(
                activity = this,
                language = AppLanguagePreferences.selected(this).languageTag.ifBlank { Locale.getDefault().toLanguageTag() },
                imagePrompt = ImageQuestionPromptResolver.baseQuestion(settings = ImageQuestionPreferences.get(this), userQuestion = null),
                initialImagePath = null,
                initialPrompt = null,
                useRelay = effect.useRelay,
            )
        }
    }
}
