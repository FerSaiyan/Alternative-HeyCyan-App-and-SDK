package com.fersaiyan.cyanbridge.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveActivity
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionSettingsUiState
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.shared.ui.pro.ProSubscriptionSettingsScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import com.fersaiyan.cyanbridge.ui.installComposeHostWithLegacyAdapter
import kotlin.math.roundToInt
import kotlin.concurrent.thread

class ProSubscriptionSettingsActivity : AppCompatActivity() {
    private var composeState by mutableStateOf(ProSubscriptionSettingsUiState())
    private var syncComposeState: (() -> Unit)? = null
    private lateinit var composeView: ComposeView

    private val prefs by lazy {
        getSharedPreferences("pro_subscription_settings", MODE_PRIVATE)
    }

    private val isInForeground get() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private inline fun runSafeOnUiThread(crossinline block: () -> Unit) {
        if (!isInForeground) return
        runOnUiThread {
            try {
                block()
                syncComposeState?.invoke()
            } catch (_: Throwable) {
                // Activity may have been destroyed while handler executed
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        composeView = installComposeHostWithLegacyAdapter(R.layout.activity_pro_subscription_settings)

        if (!ProSubscriptionPrefs.isActiveLocally(this)) {
            Toast.makeText(this, "No active Pro plan found.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val switchCloudSync: Switch = findViewById(R.id.switch_cloud_sync)
        val switchPrioritySupport: Switch = findViewById(R.id.switch_priority_support)
        val switchPluginRewards: Switch = findViewById(R.id.switch_plugin_rewards)
        val switchEarlyAccessDevices: Switch = findViewById(R.id.switch_early_access_devices)
        val spinnerBackupFrequency: Spinner = findViewById(R.id.spinner_backup_frequency)
        val spinnerSupportChannel: Spinner = findViewById(R.id.spinner_support_channel)
        val spinnerModelRequests: Spinner = findViewById(R.id.spinner_model_requests)
        val spinnerModelQuestions: Spinner = findViewById(R.id.spinner_model_questions)
        val spinnerModelTasks: Spinner = findViewById(R.id.spinner_model_tasks)
        val tvPlanStatus: TextView = findViewById(R.id.tv_plan_details_status)
        val tvPlanPlan: TextView = findViewById(R.id.tv_plan_details_plan)
        val tvPlanExpires: TextView = findViewById(R.id.tv_plan_details_expires)
        val tvPlanVerified: TextView = findViewById(R.id.tv_plan_details_verified)
        val tvAccountEmail: TextView = findViewById(R.id.tv_account_email)
        val tvAccountToken: TextView = findViewById(R.id.tv_account_token)
        val tvAccountSubscription: TextView = findViewById(R.id.tv_account_subscription)
        val tvQuotaStatus: TextView = findViewById(R.id.tv_quota_status)
        val tvQuotaBreakdown: TextView = findViewById(R.id.tv_quota_breakdown)
        val progressQuota: LinearProgressIndicator = findViewById(R.id.progress_quota)
        val tvBetaCloudStatus: TextView = findViewById(R.id.tv_beta_cloud_status)
        val btnRefreshPlanStatus: MaterialButton = findViewById(R.id.btn_refresh_plan_status)
        val btnRefreshAccount: MaterialButton = findViewById(R.id.btn_refresh_account)
        val btnManageSubscription: MaterialButton = findViewById(R.id.btn_manage_subscription)
        val btnRefreshQuota: MaterialButton = findViewById(R.id.btn_refresh_quota)
        val btnRefreshModels: MaterialButton = findViewById(R.id.btn_refresh_models)
        val btnJoinBetaCloud: MaterialButton = findViewById(R.id.btn_join_beta_cloud)

        val saveButton: MaterialButton = findViewById(R.id.btn_save)
        val backButton: MaterialButton = findViewById(R.id.btn_back)

        fun setupCollapsibleSection(
            cardId: Int,
            containerId: Int,
            titleId: Int,
            prefKey: String,
            defaultExpanded: Boolean,
        ) {
            val card = findViewById<View>(cardId)
            val container = findViewById<LinearLayout>(containerId)
            val title = findViewById<TextView>(titleId)
            val baseTitle = title.text.toString().removePrefix("▾ ").removePrefix("▸ ")

            fun applyExpanded(expanded: Boolean) {
                for (i in 1 until container.childCount) {
                    container.getChildAt(i).visibility = if (expanded) View.VISIBLE else View.GONE
                }
                title.text = if (expanded) "▾ $baseTitle" else "▸ $baseTitle"
            }

            var expanded = prefs.getBoolean(prefKey, defaultExpanded)
            applyExpanded(expanded)

            val toggle = {
                expanded = !expanded
                prefs.edit().putBoolean(prefKey, expanded).apply()
                applyExpanded(expanded)
            }

            card.setOnClickListener { toggle() }
            title.setOnClickListener { toggle() }
        }

        val frequencyItems = listOf("Every 1 hour", "Every 6 hours", "Daily")
        spinnerBackupFrequency.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            frequencyItems,
        )

        val supportItems = listOf("In-app priority queue", "Email", "Discord")
        spinnerSupportChannel.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            supportItems,
        )

        switchCloudSync.isChecked = prefs.getBoolean("cloud_sync", true)
        switchPrioritySupport.isChecked = prefs.getBoolean("priority_support", true)
        switchPluginRewards.isChecked = prefs.getBoolean("plugin_rewards", true)
        switchEarlyAccessDevices.isChecked = prefs.getBoolean("early_access_devices", true)
        spinnerBackupFrequency.setSelection(prefs.getInt("backup_frequency_idx", 1).coerceIn(0, frequencyItems.lastIndex))
        spinnerSupportChannel.setSelection(prefs.getInt("support_channel_idx", 0).coerceIn(0, supportItems.lastIndex))

        val modelIdToLabel = linkedMapOf(
            "openrouter/free" to "Cheap models router (1x)",
            "google/gemma-4-26b-a4b-it" to "Gemma 4 26B Vision (2x)",
            "deepseek/deepseek-v4-flash" to "DeepSeek V4 Flash (2x)",
        )

        val modelLabels = mutableListOf<String>()

        fun rebuildModelLabels() {
            modelLabels.clear()
            val seen = linkedSetOf<String>()
            val normalized = mutableListOf<Pair<String, String>>()
            modelIdToLabel.forEach { (id, baseLabel) ->
                var label = baseLabel.trim().ifBlank { id }
                if (seen.contains(label)) {
                    label = "$label · $id"
                }
                seen += label
                normalized += id to label
                modelLabels += label
            }
            modelIdToLabel.clear()
            normalized.forEach { (id, label) -> modelIdToLabel[id] = label }
        }

        rebuildModelLabels()

        val savedRequestsModel = ProSubscriptionAiPrefs.getRequestsModel(this)
        val savedQuestionsModel = ProSubscriptionAiPrefs.getQuestionsModel(this)
        val savedTasksModel = ProSubscriptionAiPrefs.getTasksModel(this)
        var proSystemPrompt = ProSubscriptionAiPrefs.getSystemPrompt(this)

        listOf(savedRequestsModel, savedQuestionsModel, savedTasksModel).forEach { saved ->
            if (saved.isNotBlank() && modelIdToLabel.keys.none { it.equals(saved, ignoreCase = true) }) {
                modelIdToLabel[saved] = saved
            }
        }
        rebuildModelLabels()

        val modelsAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modelLabels,
        )
        spinnerModelRequests.adapter = modelsAdapter
        spinnerModelQuestions.adapter = modelsAdapter
        spinnerModelTasks.adapter = modelsAdapter

        fun selectSpinnerValue(spinner: Spinner, value: String) {
            val canonical = modelIdToLabel.keys.firstOrNull { it.equals(value, ignoreCase = true) } ?: value
            val label = modelIdToLabel[canonical] ?: value
            val idx = modelLabels.indexOfFirst { it.equals(label, ignoreCase = true) }
            spinner.setSelection(if (idx >= 0) idx else 0)
        }

        selectSpinnerValue(spinnerModelRequests, savedRequestsModel)
        selectSpinnerValue(spinnerModelQuestions, savedQuestionsModel)
        selectSpinnerValue(spinnerModelTasks, savedTasksModel)

        setupCollapsibleSection(
            cardId = R.id.card_section_plan,
            containerId = R.id.section_plan_container,
            titleId = R.id.tv_section_plan,
            prefKey = "section_plan_expanded",
            defaultExpanded = true,
        )
        setupCollapsibleSection(
            cardId = R.id.card_section_beta,
            containerId = R.id.section_beta_container,
            titleId = R.id.tv_section_beta,
            prefKey = "section_beta_expanded",
            defaultExpanded = false,
        )
        setupCollapsibleSection(
            cardId = R.id.card_section_ai,
            containerId = R.id.section_ai_container,
            titleId = R.id.tv_section_ai,
            prefKey = "section_ai_expanded",
            defaultExpanded = true,
        )
        setupCollapsibleSection(
            cardId = R.id.card_section_cloud,
            containerId = R.id.section_cloud_container,
            titleId = R.id.tv_section_cloud,
            prefKey = "section_cloud_expanded",
            defaultExpanded = false,
        )
        setupCollapsibleSection(
            cardId = R.id.card_section_ecosystem,
            containerId = R.id.section_ecosystem_container,
            titleId = R.id.tv_section_ecosystem,
            prefKey = "section_ecosystem_expanded",
            defaultExpanded = false,
        )
        setupCollapsibleSection(
            cardId = R.id.card_section_future,
            containerId = R.id.section_future_container,
            titleId = R.id.tv_section_future,
            prefKey = "section_future_expanded",
            defaultExpanded = false,
        )

        fun selectedModel(spinner: Spinner): String {
            val selectedLabel = spinner.selectedItem?.toString()?.trim().orEmpty().ifBlank { "auto" }
            val byLabel = modelIdToLabel.entries.firstOrNull { it.value.equals(selectedLabel, ignoreCase = true) }
            return byLabel?.key ?: selectedLabel
        }

        fun saveSettings(showToast: Boolean = false) {
            prefs.edit()
                .putBoolean("cloud_sync", switchCloudSync.isChecked)
                .putBoolean("priority_support", switchPrioritySupport.isChecked)
                .putBoolean("plugin_rewards", switchPluginRewards.isChecked)
                .putBoolean("early_access_devices", switchEarlyAccessDevices.isChecked)
                .putInt("backup_frequency_idx", spinnerBackupFrequency.selectedItemPosition)
                .putInt("support_channel_idx", spinnerSupportChannel.selectedItemPosition)
                .apply()

            ProSubscriptionAiPrefs.setRequestsModel(this, selectedModel(spinnerModelRequests))
            ProSubscriptionAiPrefs.setQuestionsModel(this, selectedModel(spinnerModelQuestions))
            ProSubscriptionAiPrefs.setTasksModel(this, selectedModel(spinnerModelTasks))
            ProSubscriptionAiPrefs.setSystemPrompt(this, proSystemPrompt)

            if (showToast) Toast.makeText(this, "Pro settings saved", Toast.LENGTH_SHORT).show()
        }

        fun refreshPlanDetails() {
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

            tvPlanStatus.text = if (local.active) "Status: Active" else "Status: Inactive"
            tvPlanPlan.text = "Plan: $planName"
            tvPlanExpires.text = "Expires: $expiresText"
            tvPlanVerified.text = "Last verified: $verifiedText"
            btnManageSubscription.text = "Cancel subscription"
        }

        fun setButtonBusy(button: MaterialButton, busy: Boolean, busyLabel: String, normalLabel: String) {
            button.isEnabled = !busy
            button.alpha = if (busy) 0.6f else 1f
            button.text = if (busy) busyLabel else normalLabel
        }

        fun formatResetTime(resetAtMs: Long): String {
            if (resetAtMs <= 0L) return "-"
            return java.text.SimpleDateFormat("MMM d, HH:mm 'UTC'", java.util.Locale.US)
                .format(java.util.Date(resetAtMs))
        }

        fun compactCount(value: Int): String {
            val absValue = kotlin.math.abs(value.toLong())
            return when {
                absValue >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1fB", value / 1_000_000_000.0)
                absValue >= 1_000_000L -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
                absValue >= 1_000L -> String.format(java.util.Locale.US, "%.1fk", value / 1_000.0)
                else -> value.toString()
            }.replace(".0", "")
        }

        fun showQuotaLoading(model: String) {
            tvQuotaStatus.text = "Quota: loading for model '$model'..."
            tvQuotaBreakdown.visibility = View.GONE
            progressQuota.visibility = View.VISIBLE
            progressQuota.isIndeterminate = true
        }

        fun showQuotaError(message: String) {
            tvQuotaStatus.text = message
            tvQuotaBreakdown.visibility = View.GONE
            progressQuota.visibility = View.GONE
            progressQuota.isIndeterminate = false
        }

        fun showQuotaInfo(quota: ProSubscriptionRelayClient.QuotaInfo) {
            val displayModel = quota.model.removeSuffix("/free").ifBlank { quota.model }
            val resetText = if (quota.resetAtMs > 0L) {
                "Resets ${formatResetTime(quota.resetAtMs)}"
            } else {
                ""
            }

            if (quota.limit > 0) {
                val usedPercent = ((quota.used.toDouble() / quota.limit.toDouble()) * 100.0)
                    .coerceIn(0.0, 100.0)
                    .roundToInt()
                tvQuotaStatus.text = "Quota ($displayModel): $usedPercent% used"
                tvQuotaBreakdown.text = buildString {
                    append("${compactCount(quota.remaining)} left")
                    append(" · ${compactCount(quota.used)}/${compactCount(quota.limit)} used")
                    if (resetText.isNotBlank()) {
                        append(" · $resetText")
                    }
                }
                tvQuotaBreakdown.visibility = View.VISIBLE
                progressQuota.visibility = View.VISIBLE
                progressQuota.isIndeterminate = false
                progressQuota.progress = usedPercent
            } else {
                tvQuotaStatus.text = "Quota ($displayModel): ${compactCount(quota.remaining)} left"
                tvQuotaBreakdown.text = buildString {
                    append("${compactCount(quota.used)} used")
                    if (resetText.isNotBlank()) {
                        append(" · $resetText")
                    }
                }
                tvQuotaBreakdown.visibility = View.VISIBLE
                progressQuota.visibility = View.GONE
                progressQuota.isIndeterminate = false
            }
        }

        fun refreshQuota() {
            val model = selectedModel(spinnerModelRequests)
            showQuotaLoading(model)
            setButtonBusy(btnRefreshQuota, true, "Refreshing...", "Refresh quota")
            thread {
                val result = ProSubscriptionRelayClient.fetchQuota(this, model)
                if (!isInForeground) return@thread
                runSafeOnUiThread {
                    setButtonBusy(btnRefreshQuota, false, "Refreshing...", "Refresh quota")
                    result.onSuccess { quota ->
                        showQuotaInfo(quota)
                    }.onFailure {
                        val hint = ProSubscriptionRelayClient.relayUnavailableHint(it)
                        showQuotaError(if (hint != null) {
                            "Quota unavailable: $hint"
                        } else {
                            "Quota unavailable: ${it.message ?: "unknown error"}"
                        })
                    }
                }
            }
        }

        fun maskToken(token: String): String {
            if (token.isBlank()) return "-"
            if (token.length <= 12) return token
            return token.take(8) + "..." + token.takeLast(4)
        }

        fun formatExpiry(expiresAtMs: Long): String {
            if (expiresAtMs <= 0L) return "-"
            return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date(expiresAtMs))
        }

        tvAccountEmail.text = "Email: ${ProSubscriptionServerPrefs.getAccountEmail(this).ifBlank { "-" }}"
        tvAccountToken.text = "API token: ${maskToken(ProSubscriptionServerPrefs.getApiToken(this))}"
        tvAccountSubscription.text = "Subscription: loading..."

        fun refreshAccount() {
            tvAccountEmail.text = "Email: loading..."
            tvAccountToken.text = "API token: loading..."
            tvAccountSubscription.text = "Subscription: loading..."
            setButtonBusy(btnRefreshAccount, true, "Refreshing...", "Refresh account")
            thread {
                val result = ProSubscriptionRelayClient.fetchAccountInfo(this)
                if (!isInForeground) return@thread
                runSafeOnUiThread {
                    setButtonBusy(btnRefreshAccount, false, "Refreshing...", "Refresh account")
                    result.onSuccess { account ->
                        if (account.email.isNotBlank()) {
                            ProSubscriptionServerPrefs.setAccountEmail(this@ProSubscriptionSettingsActivity, account.email)
                        }
                        tvAccountEmail.text = "Email: ${account.email.ifBlank { "-" }}"
                        tvAccountToken.text = "API token: ${maskToken(account.apiToken)}"
                        tvAccountSubscription.text = "Subscription: ${account.subscriptionStatus} · ${account.plan}"
                    }.onFailure {
                        val hint = ProSubscriptionRelayClient.relayUnavailableHint(it)
                        tvAccountEmail.text = "Email: -"
                        tvAccountToken.text = "API token: -"
                        tvAccountSubscription.text = if (hint != null) {
                            "Subscription: unavailable ($hint)"
                        } else {
                            "Subscription: unavailable (${it.message ?: "unknown error"})"
                        }
                    }
                }
            }
        }

        fun refreshModels() {
            setButtonBusy(btnRefreshModels, true, "Loading...", "Refresh models")
            thread {
                val result = ProSubscriptionRelayClient.fetchAvailableModels(this)
                if (!isInForeground) return@thread
                runSafeOnUiThread {
                    setButtonBusy(btnRefreshModels, false, "Loading...", "Refresh models")
                    result.onSuccess { models ->
                        if (models.isEmpty()) {
                            Toast.makeText(this, "No models returned by server", Toast.LENGTH_SHORT).show()
                            return@onSuccess
                        }

                        val currentRequests = selectedModel(spinnerModelRequests)
                        val currentQuestions = selectedModel(spinnerModelQuestions)
                        val currentTasks = selectedModel(spinnerModelTasks)

                        val merged = linkedMapOf<String, String>()
                        models.forEach { option ->
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

                        modelIdToLabel.clear()
                        modelIdToLabel.putAll(merged)
                        rebuildModelLabels()
                        modelsAdapter.notifyDataSetChanged()

                        selectSpinnerValue(spinnerModelRequests, currentRequests)
                        selectSpinnerValue(spinnerModelQuestions, currentQuestions)
                        selectSpinnerValue(spinnerModelTasks, currentTasks)

                        Toast.makeText(this, "Loaded ${models.size} models", Toast.LENGTH_SHORT).show()
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

        refreshPlanDetails()
        refreshAccount()
        refreshQuota()
        refreshModels()

        btnRefreshPlanStatus.setOnClickListener {
            it.isEnabled = false
            it.alpha = 0.6f
            Toast.makeText(this, "Checking subscription status…", Toast.LENGTH_SHORT).show()
            thread {
                val result = ProSubscriptionVerifier.verifyNow(this)
                if (!isInForeground) return@thread
                runSafeOnUiThread {
                    it.isEnabled = true
                    it.alpha = 1f
                    refreshPlanDetails()
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnRefreshQuota.setOnClickListener {
            refreshQuota()
        }

        btnRefreshAccount.setOnClickListener {
            refreshAccount()
        }

        btnManageSubscription.setOnClickListener {
            handleCancellation(btnManageSubscription)
        }

        btnRefreshModels.setOnClickListener {
            refreshModels()
        }

        btnJoinBetaCloud.setOnClickListener {
            setButtonBusy(btnJoinBetaCloud, true, "Submitting...", "Sign up for beta cloud")
            tvBetaCloudStatus.text = "Sending your beta cloud signup..."
            thread {
                val result = ProSubscriptionRelayClient.registerBetaCloudInterest(this)
                if (!isInForeground) return@thread
                runSafeOnUiThread {
                    setButtonBusy(btnJoinBetaCloud, false, "Submitting...", "Sign up for beta cloud")
                    result.onSuccess { signup ->
                        val countSuffix = signup.interestedCount?.let { " Current interested users: $it." } ?: ""
                        tvBetaCloudStatus.text = signup.message + countSuffix
                        Toast.makeText(this, "Beta cloud interest recorded", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        val hint = ProSubscriptionRelayClient.relayUnavailableHint(it)
                        tvBetaCloudStatus.text = "Signup failed: ${it.message ?: "unknown error"}"
                        Toast.makeText(
                            this,
                            hint ?: "Could not submit beta cloud interest",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }

        saveButton.setOnClickListener {
            saveSettings(showToast = true)
            refreshQuota()
        }

        backButton.setOnClickListener { finish() }

        fun selectComposeModel(spinner: Spinner, label: String) {
            val index = modelLabels.indexOfFirst { it == label }
            if (index >= 0) spinner.setSelection(index)
            syncComposeState?.invoke()
        }

        syncComposeState = {
            composeState = ProSubscriptionSettingsUiState(
                planStatus = tvPlanStatus.text.toString(),
                plan = tvPlanPlan.text.toString(),
                expires = tvPlanExpires.text.toString(),
                verified = tvPlanVerified.text.toString(),
                accountEmail = tvAccountEmail.text.toString(),
                accountToken = tvAccountToken.text.toString(),
                accountSubscription = tvAccountSubscription.text.toString(),
                quotaStatus = tvQuotaStatus.text.toString(),
                quotaBreakdown = tvQuotaBreakdown.text.toString(),
                quotaProgress = if (
                    progressQuota.visibility == View.VISIBLE && !progressQuota.isIndeterminate
                ) progressQuota.progress else null,
                betaStatus = tvBetaCloudStatus.text.toString(),
                cloudSync = switchCloudSync.isChecked,
                prioritySupport = switchPrioritySupport.isChecked,
                pluginRewards = switchPluginRewards.isChecked,
                earlyAccessDevices = switchEarlyAccessDevices.isChecked,
                backupFrequencyIndex = spinnerBackupFrequency.selectedItemPosition,
                supportChannelIndex = spinnerSupportChannel.selectedItemPosition,
                modelOptions = modelLabels.toList(),
                requestsModel = spinnerModelRequests.selectedItem?.toString().orEmpty(),
                questionsModel = spinnerModelQuestions.selectedItem?.toString().orEmpty(),
                tasksModel = spinnerModelTasks.selectedItem?.toString().orEmpty(),
                systemPrompt = proSystemPrompt,
            )
        }
        syncComposeState?.invoke()

        val appearancePreferences = AppearancePreferences(this)
        composeView.setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                ProSubscriptionSettingsScreen(
                    state = composeState,
                    onRefreshPlan = {
                        btnRefreshPlanStatus.performClick()
                        syncComposeState?.invoke()
                    },
                    onChangePlan = ::startPlanChange,
                    onCancelSubscription = { handleCancellation(btnManageSubscription) },
                    onRefreshAccount = {
                        btnRefreshAccount.performClick()
                        syncComposeState?.invoke()
                    },
                    onRefreshQuota = {
                        btnRefreshQuota.performClick()
                        syncComposeState?.invoke()
                    },
                    onRefreshModels = {
                        btnRefreshModels.performClick()
                        syncComposeState?.invoke()
                    },
                    onJoinBeta = {
                        btnJoinBetaCloud.performClick()
                        syncComposeState?.invoke()
                    },
                    onStartGeminiLive = {
                        startActivity(Intent(this@ProSubscriptionSettingsActivity, GeminiLiveActivity::class.java))
                    },
                    onCloudSyncChange = {
                        switchCloudSync.isChecked = it
                        saveSettings()
                        syncComposeState?.invoke()
                    },
                    onPrioritySupportChange = {
                        switchPrioritySupport.isChecked = it
                        saveSettings()
                        syncComposeState?.invoke()
                    },
                    onPluginRewardsChange = {
                        switchPluginRewards.isChecked = it
                        saveSettings()
                        syncComposeState?.invoke()
                    },
                    onEarlyAccessDevicesChange = {
                        switchEarlyAccessDevices.isChecked = it
                        saveSettings()
                        syncComposeState?.invoke()
                    },
                    onBackupFrequencyChange = {
                        spinnerBackupFrequency.setSelection(it.coerceIn(0, frequencyItems.lastIndex))
                        saveSettings()
                        syncComposeState?.invoke()
                    },
                    onSupportChannelChange = {
                        spinnerSupportChannel.setSelection(it.coerceIn(0, supportItems.lastIndex))
                        saveSettings()
                        syncComposeState?.invoke()
                    },
                    onRequestsModelChange = {
                        selectComposeModel(spinnerModelRequests, it)
                        saveSettings()
                        refreshQuota()
                    },
                    onQuestionsModelChange = {
                        selectComposeModel(spinnerModelQuestions, it)
                        saveSettings()
                    },
                    onTasksModelChange = {
                        selectComposeModel(spinnerModelTasks, it)
                        saveSettings()
                    },
                    onSystemPromptChange = {
                        proSystemPrompt = it
                        ProSubscriptionAiPrefs.setSystemPrompt(this@ProSubscriptionSettingsActivity, it)
                        syncComposeState?.invoke()
                    },
                    onResetSystemPrompt = {
                        ProSubscriptionAiPrefs.resetSystemPrompt(this@ProSubscriptionSettingsActivity)
                        proSystemPrompt = ProSubscriptionAiPrefs.getSystemPrompt(this@ProSubscriptionSettingsActivity)
                        syncComposeState?.invoke()
                    },
                    onBack = ::finish,
                )
            }
        }
    }

    private fun startPlanChange(plan: String) {
        if (ProSubscriptionPrefs.getProvider(this) == "play_billing") {
            openPlaySubscriptionManagement(null)
        } else {
            startActivity(Intent(this, ProSubscriptionActivity::class.java).apply {
                putExtra(ProSubscriptionActivity.EXTRA_INITIAL_PLAN, plan)
                putExtra(ProSubscriptionActivity.EXTRA_CHANGE_PLAN, true)
            })
            finish()
        }
    }

    private fun openPlaySubscriptionManagement(button: MaterialButton?) {
        val normalLabel = button?.text?.toString().orEmpty()
        if (button != null) {
            button.isEnabled = false
            button.alpha = 0.6f
            button.text = "Opening..."
        }

        val productId = PlaySubscriptionCatalog.productIdForPlan(ProSubscriptionPrefs.getPlan(this))
        val manageUrl = Uri.parse("https://play.google.com/store/account/subscriptions").buildUpon().apply {
            appendQueryParameter("package", packageName)
            if (productId.isNotBlank()) {
                appendQueryParameter("sku", productId)
            }
        }.build().toString()

        runSafeOnUiThread {
            if (button != null) {
                button.isEnabled = true
                button.alpha = 1f
                button.text = normalLabel
            }
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

    private fun handleCancellation(button: MaterialButton) {
        if (ProSubscriptionPrefs.getProvider(this) == "play_billing") {
            openPlaySubscriptionManagement(button)
            return
        }
        cancelSubscriptionInApp(button)
    }

    private fun cancelSubscriptionInApp(button: MaterialButton) {
        val normalLabel = button.text.toString()
        button.isEnabled = false
        button.alpha = 0.6f
        button.text = "Cancelling..."
        thread {
            val result = ProSubscriptionRelayClient.cancelSubscription(this)

            runSafeOnUiThread {
                button.isEnabled = true
                button.alpha = 1f
                button.text = normalLabel
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
}
