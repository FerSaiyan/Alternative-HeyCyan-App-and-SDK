package com.fersaiyan.cyanbridge.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.SoundEffectConstants
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPromptResolver
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionSettingsUiState
import com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences
import java.util.Locale
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.shared.ui.pro.ProSubscriptionSettingsScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlin.concurrent.thread

class ProSubscriptionSettingsActivity : AppCompatActivity() {
    private var composeState by mutableStateOf(ProSubscriptionSettingsUiState())
    private var liveModeOptions by mutableStateOf(ProSubscriptionRelayClient.defaultLiveModes())
    private lateinit var composeView: ComposeView

    private val draft = ProSubscriptionSettingsDraft()
    private val models = ProSubscriptionModelLabels()
    private var proSystemPrompt = ""

    /** Set once the screen is assembled; [publish] is a no-op before that, as it always was. */
    private var publishEnabled = false

    internal val state: ProSubscriptionSettingsUiState get() = composeState

    private val prefs by lazy {
        getSharedPreferences("pro_subscription_settings", MODE_PRIVATE)
    }

    private val isInForeground get() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private inline fun runSafeOnUiThread(crossinline block: () -> Unit) {
        if (!isInForeground) return
        runOnUiThread {
            try {
                block()
                publish()
            } catch (_: Throwable) {
                // Activity may have been destroyed while handler executed
            }
        }
    }

    /** Copies the working draft to the Compose screen. */
    private fun publish() {
        if (!publishEnabled) return
        composeState = draft.toUiState(models, proSystemPrompt)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        composeView = ComposeView(this).also(::setContentView)

        if (!ProSubscriptionPrefs.isActiveLocally(this)) {
            Toast.makeText(this, "No active Pro plan found.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        draft.cloudSync = prefs.getBoolean("cloud_sync", true)
        draft.prioritySupport = prefs.getBoolean("priority_support", true)
        draft.pluginRewards = prefs.getBoolean("plugin_rewards", true)
        draft.earlyAccessDevices = prefs.getBoolean("early_access_devices", true)
        draft.backupFrequencyIndex = prefs.getInt("backup_frequency_idx", 1).coerceIn(0, FREQUENCY_ITEMS.lastIndex)
        draft.supportChannelIndex = prefs.getInt("support_channel_idx", 0).coerceIn(0, SUPPORT_ITEMS.lastIndex)

        val savedRequestsModel = ProSubscriptionAiPrefs.getRequestsModel(this)
        val savedQuestionsModel = ProSubscriptionAiPrefs.getQuestionsModel(this)
        val savedTasksModel = ProSubscriptionAiPrefs.getTasksModel(this)
        proSystemPrompt = ProSubscriptionAiPrefs.getSystemPrompt(this)

        models.includeSaved(listOf(savedRequestsModel, savedQuestionsModel, savedTasksModel))
        draft.requestsModelIndex = models.positionOf(savedRequestsModel)
        draft.questionsModelIndex = models.positionOf(savedQuestionsModel)
        draft.tasksModelIndex = models.positionOf(savedTasksModel)

        draft.accountEmail = "Email: ${ProSubscriptionServerPrefs.getAccountEmail(this).ifBlank { "-" }}"
        draft.accountToken = "API token: ${ProSubscriptionSettingsFormat.maskToken(ProSubscriptionServerPrefs.getApiToken(this))}"
        draft.accountSubscription = "Subscription: loading..."

        refreshPlanDetails()
        refreshAccount()
        refreshQuota()
        refreshModels()

        publishEnabled = true
        publish()

        val appearancePreferences = AppearancePreferences(this)
        composeView.setContent {
            var liveEconomy by androidx.compose.runtime.remember {
                mutableStateOf(com.fersaiyan.cyanbridge.ai.live.GeminiLiveModePreferences.isEconomy(this))
            }
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                val economyMode = liveModeOptions.first { it.id == "economy" }
                val privateMode = liveModeOptions.first { it.id == "private" }
                ProSubscriptionSettingsScreen(
                    liveEconomy = liveEconomy,
                    liveEconomyLabel = economyMode.displayLabel,
                    livePrivateLabel = privateMode.displayLabel,
                    liveEconomyDescription = economyMode.description,
                    livePrivateDescription = privateMode.description,
                    onLiveEconomyChange = { enabled ->
                        com.fersaiyan.cyanbridge.ai.live.GeminiLiveModePreferences.setEconomy(this, enabled)
                        liveEconomy = enabled
                    },
                    state = composeState,
                    onRefreshPlan = {
                        playClickSound()
                        verifyPlanStatus()
                        publish()
                    },
                    onChangePlan = ::startPlanChange,
                    onCancelSubscription = ::handleCancellation,
                    onRefreshAccount = {
                        playClickSound()
                        refreshAccount()
                        publish()
                    },
                    onRefreshQuota = {
                        playClickSound()
                        refreshQuota()
                        publish()
                    },
                    onRefreshModels = {
                        playClickSound()
                        refreshModels()
                        publish()
                    },
                    onJoinBeta = {
                        playClickSound()
                        joinBetaCloud()
                        publish()
                    },
                    onStartGeminiLive = {
                        // Notification-driven Live survives lock/screen-off; activity is now optional viewer.
                        // Use base question (no strict Answer only in X) - Live system handles 97-language switching permissively.
                        val language = AppLanguagePreferences.selected(this@ProSubscriptionSettingsActivity).languageTag
                            .ifBlank { Locale.getDefault().toLanguageTag() }
                        val imagePrompt = ImageQuestionPromptResolver.baseQuestion(
                            settings = ImageQuestionPreferences.get(this@ProSubscriptionSettingsActivity),
                            userQuestion = null,
                        )
                        val isPro = ProSubscriptionPrefs.isActiveLocally(this@ProSubscriptionSettingsActivity) &&
                            ProSubscriptionPrefs.getPlan(this@ProSubscriptionSettingsActivity).lowercase() in setOf("cheap", "standard", "max")
                        com.fersaiyan.cyanbridge.ai.live.GeminiLivePermissionGateActivity.launch(
                            activity = this@ProSubscriptionSettingsActivity,
                            language = language,
                            imagePrompt = imagePrompt,
                            initialImagePath = null,
                            initialPrompt = null,
                            useRelay = !isPro,
                        )
                        Toast.makeText(this@ProSubscriptionSettingsActivity, "Checking Live microphone and notification permissions", Toast.LENGTH_SHORT).show()
                    },
                    onCloudSyncChange = {
                        draft.cloudSync = it
                        saveSettings()
                        publish()
                    },
                    onPrioritySupportChange = {
                        draft.prioritySupport = it
                        saveSettings()
                        publish()
                    },
                    onPluginRewardsChange = {
                        draft.pluginRewards = it
                        saveSettings()
                        publish()
                    },
                    onEarlyAccessDevicesChange = {
                        draft.earlyAccessDevices = it
                        saveSettings()
                        publish()
                    },
                    onBackupFrequencyChange = {
                        draft.backupFrequencyIndex = it.coerceIn(0, FREQUENCY_ITEMS.lastIndex)
                        saveSettings()
                        publish()
                    },
                    onSupportChannelChange = {
                        draft.supportChannelIndex = it.coerceIn(0, SUPPORT_ITEMS.lastIndex)
                        saveSettings()
                        publish()
                    },
                    onRequestsModelChange = {
                        selectComposeModel(it) { index -> draft.requestsModelIndex = index }
                        saveSettings()
                        refreshQuota()
                    },
                    onQuestionsModelChange = {
                        selectComposeModel(it) { index -> draft.questionsModelIndex = index }
                        saveSettings()
                    },
                    onTasksModelChange = {
                        selectComposeModel(it) { index -> draft.tasksModelIndex = index }
                        saveSettings()
                    },
                    onSystemPromptChange = {
                        proSystemPrompt = it
                        ProSubscriptionAiPrefs.setSystemPrompt(this@ProSubscriptionSettingsActivity, it)
                        publish()
                    },
                    onResetSystemPrompt = {
                        ProSubscriptionAiPrefs.resetSystemPrompt(this@ProSubscriptionSettingsActivity)
                        proSystemPrompt = ProSubscriptionAiPrefs.getSystemPrompt(this@ProSubscriptionSettingsActivity)
                        publish()
                    },
                    onBack = ::finish,
                )
            }
        }
    }

    /**
     * Refresh and sign-up actions used to be relayed through hidden buttons, whose performClick()
     * played the standard click sound before running the handler. Keep that audible feedback.
     */
    private fun playClickSound() {
        composeView.playSoundEffect(SoundEffectConstants.CLICK)
    }

    /** Selects the model with exactly [label] (no-op when it is not listed), then publishes. */
    private inline fun selectComposeModel(label: String, select: (Int) -> Unit) {
        val index = models.labels.indexOfFirst { it == label }
        if (index >= 0) select(index)
        publish()
    }

    private fun saveSettings() {
        prefs.edit()
            .putBoolean("cloud_sync", draft.cloudSync)
            .putBoolean("priority_support", draft.prioritySupport)
            .putBoolean("plugin_rewards", draft.pluginRewards)
            .putBoolean("early_access_devices", draft.earlyAccessDevices)
            .putInt("backup_frequency_idx", draft.backupFrequencyIndex)
            .putInt("support_channel_idx", draft.supportChannelIndex)
            .apply()

        ProSubscriptionAiPrefs.setRequestsModel(this, models.modelAt(draft.requestsModelIndex))
        ProSubscriptionAiPrefs.setQuestionsModel(this, models.modelAt(draft.questionsModelIndex))
        ProSubscriptionAiPrefs.setTasksModel(this, models.modelAt(draft.tasksModelIndex))
        ProSubscriptionAiPrefs.setSystemPrompt(this, proSystemPrompt)
    }

    private fun refreshPlanDetails() {
        val local = ProSubscriptionVerifier.localStatus(this)
        val planName = local.plan.ifBlank { "none" }
        val expiresAt = local.expiresAtMs
        val expiresText = if (expiresAt > 0L) {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(expiresAt))
        } else {
            "Unknown"
        }
        val lastVerifiedAt = ProSubscriptionPrefs.getLastVerifiedAt(this)
        val verifiedText = if (lastVerifiedAt > 0L) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(lastVerifiedAt))
        } else {
            "Never"
        }

        draft.planStatus = if (local.active) "Status: Active" else "Status: Inactive"
        draft.plan = "Plan: $planName"
        draft.expires = "Expires: $expiresText"
        draft.verified = "Last verified: $verifiedText"
    }

    private fun verifyPlanStatus() {
        Toast.makeText(this, "Checking subscription status…", Toast.LENGTH_SHORT).show()
        thread {
            val result = ProSubscriptionVerifier.verifyNow(this)
            if (!isInForeground) return@thread
            runSafeOnUiThread {
                refreshPlanDetails()
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshQuota() {
        val model = models.modelAt(draft.requestsModelIndex)
        draft.showQuotaLoading(model)
        thread {
            val result = ProSubscriptionRelayClient.fetchQuota(this, model)
            if (!isInForeground) return@thread
            runSafeOnUiThread {
                result.onSuccess { quota ->
                    draft.showQuotaInfo(quota)
                }.onFailure {
                    val hint = ProSubscriptionRelayClient.relayUnavailableHint(it)
                    draft.showQuotaError(if (hint != null) {
                        "Quota unavailable: $hint"
                    } else {
                        "Quota unavailable: ${it.message ?: "unknown error"}"
                    })
                }
            }
        }
    }

    private fun refreshAccount() {
        draft.accountEmail = "Email: loading..."
        draft.accountToken = "API token: loading..."
        draft.accountSubscription = "Subscription: loading..."
        thread {
            val result = ProSubscriptionRelayClient.fetchAccountInfo(this)
            if (!isInForeground) return@thread
            runSafeOnUiThread {
                result.onSuccess { account ->
                    if (account.email.isNotBlank()) {
                        ProSubscriptionServerPrefs.setAccountEmail(this@ProSubscriptionSettingsActivity, account.email)
                    }
                    draft.accountEmail = "Email: ${account.email.ifBlank { "-" }}"
                    draft.accountToken = "API token: ${ProSubscriptionSettingsFormat.maskToken(account.apiToken)}"
                    draft.accountSubscription = "Subscription: ${account.subscriptionStatus} · ${account.plan}"
                }.onFailure {
                    val hint = ProSubscriptionRelayClient.relayUnavailableHint(it)
                    draft.accountEmail = "Email: -"
                    draft.accountToken = "API token: -"
                    draft.accountSubscription = if (hint != null) {
                        "Subscription: unavailable ($hint)"
                    } else {
                        "Subscription: unavailable (${it.message ?: "unknown error"})"
                    }
                }
            }
        }
    }

    private fun refreshModels() {
        thread {
            val result = ProSubscriptionRelayClient.fetchModelCatalog(this)
            if (!isInForeground) return@thread
            runSafeOnUiThread {
                result.onSuccess { catalog ->
                    val catalogModels = catalog.models
                    if (catalogModels.isEmpty()) {
                        Toast.makeText(this, "No models returned by server", Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    liveModeOptions = catalog.liveModes

                    val currentRequests = models.modelAt(draft.requestsModelIndex)
                    val currentQuestions = models.modelAt(draft.questionsModelIndex)
                    val currentTasks = models.modelAt(draft.tasksModelIndex)

                    val merged = linkedMapOf<String, String>()
                    catalogModels.forEach { option ->
                        val id = option.id.trim()
                        if (id.isBlank()) return@forEach
                        val baseLabel = option.label.trim().ifBlank { id }
                        val label = if (option.supportsVision && !baseLabel.contains("vision", ignoreCase = true)) {
                            "$baseLabel · Vision"
                        } else {
                            baseLabel
                        }
                        if (!merged.containsKey(id)) {
                            merged[id] = label
                        }
                    }

                    models.replaceAll(merged)

                    draft.requestsModelIndex = models.positionOf(currentRequests)
                    draft.questionsModelIndex = models.positionOf(currentQuestions)
                    draft.tasksModelIndex = models.positionOf(currentTasks)

                    Toast.makeText(this, "Loaded ${catalogModels.size} models", Toast.LENGTH_SHORT).show()
                    refreshQuota()
                }.onFailure {
                    val hint = ProSubscriptionRelayClient.relayUnavailableHint(it)
                    Toast.makeText(
                        this,
                        hint ?: "Could not load models: ${it.message ?: "unknown error"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun joinBetaCloud() {
        draft.betaStatus = "Sending your beta cloud signup..."
        thread {
            val result = ProSubscriptionRelayClient.registerBetaCloudInterest(this)
            if (!isInForeground) return@thread
            runSafeOnUiThread {
                result.onSuccess { signup ->
                    val countSuffix = signup.interestedCount?.let { " Current interested users: $it." } ?: ""
                    draft.betaStatus = signup.message + countSuffix
                    Toast.makeText(this, "Beta cloud interest recorded", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    val hint = ProSubscriptionRelayClient.relayUnavailableHint(it)
                    draft.betaStatus = "Signup failed: ${it.message ?: "unknown error"}"
                    Toast.makeText(
                        this,
                        hint ?: "Could not submit beta cloud interest",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun startPlanChange(plan: String) {
        if (ProSubscriptionPrefs.getProvider(this) == "play_billing") {
            openPlaySubscriptionManagement()
        } else {
            startActivity(Intent(this, ProSubscriptionActivity::class.java).apply {
                putExtra(ProSubscriptionActivity.EXTRA_INITIAL_PLAN, plan)
                putExtra(ProSubscriptionActivity.EXTRA_CHANGE_PLAN, true)
            })
            finish()
        }
    }

    private fun openPlaySubscriptionManagement() {
        val productId = PlaySubscriptionCatalog.productIdForPlan(ProSubscriptionPrefs.getPlan(this))
        val manageUrl = Uri.parse("https://play.google.com/store/account/subscriptions").buildUpon().apply {
            appendQueryParameter("package", packageName)
            if (productId.isNotBlank()) {
                appendQueryParameter("sku", productId)
            }
        }.build().toString()

        runSafeOnUiThread {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(manageUrl))
                if (intent.resolveActivityInfo(packageManager, 0) == null) {
                    Toast.makeText(this, "No app found to manage the Google Play subscription.", Toast.LENGTH_SHORT).show()
                    return@runCatching
                }
                startActivity(intent)
            }.onFailure {
                Toast.makeText(this, "Unable to open Google Play subscription management: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleCancellation() {
        if (ProSubscriptionPrefs.getProvider(this) == "play_billing") {
            openPlaySubscriptionManagement()
            return
        }
        cancelSubscriptionInApp()
    }

    private fun cancelSubscriptionInApp() {
        thread {
            val result = ProSubscriptionRelayClient.cancelSubscription(this)

            runSafeOnUiThread {
                result.onSuccess { cancel ->
                    ProSubscriptionPrefs.setSubscribed(this, cancel.active)
                    ProSubscriptionPrefs.setPlan(this, cancel.plan)
                    ProSubscriptionPrefs.setExpiresAt(this, cancel.expiresAtMs)
                    ProSubscriptionPrefs.setProvider(this, "server_verified")
                    ProSubscriptionPrefs.setLastVerifiedAt(this, System.currentTimeMillis())
                    if (!cancel.active) {
                        ProSubscriptionPrefs.setPurchaseToken(this, "")
                    }

                    Toast.makeText(this, cancel.message, Toast.LENGTH_LONG).show()

                    if (!cancel.active) {
                        finish()
                    } else {
                        recreate()
                    }
                }.onFailure {
                    Toast.makeText(this, "Unable to cancel subscription: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private companion object {
        /** Option counts of the backup-frequency and support-channel pickers rendered by the shared screen. */
        val FREQUENCY_ITEMS = listOf("Every 1 hour", "Every 6 hours", "Daily")
        val SUPPORT_ITEMS = listOf("In-app priority queue", "Email", "Discord")
    }
}
