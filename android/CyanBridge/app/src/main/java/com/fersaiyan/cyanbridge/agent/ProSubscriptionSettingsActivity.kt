package com.fersaiyan.cyanbridge.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButton
import com.fersaiyan.cyanbridge.R
import kotlin.concurrent.thread

class ProSubscriptionSettingsActivity : AppCompatActivity() {

    private val prefs by lazy {
        getSharedPreferences("pro_subscription_settings", MODE_PRIVATE)
    }

    private val isInForeground get() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private inline fun runSafeOnUiThread(crossinline block: () -> Unit) {
        if (!isInForeground) return
        runOnUiThread {
            try {
                block()
            } catch (_: Throwable) {
                // Activity may have been destroyed while handler executed
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pro_subscription_settings)

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
        val tvBetaCloudStatus: TextView = findViewById(R.id.tv_beta_cloud_status)
        val btnRefreshPlanStatus: MaterialButton = findViewById(R.id.btn_refresh_plan_status)
        val btnRefreshAccount: MaterialButton = findViewById(R.id.btn_refresh_account)
        val btnChangePlan: MaterialButton = findViewById(R.id.btn_change_plan)
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
            "auto" to "auto",
            "gpt-5.4" to "gpt-5.4",
            "minimax/minimax-m2.5" to "minimax/minimax-m2.5",
            "z-ai/glm-5" to "z-ai/glm-5",
            "google/gemini-3-flash-preview" to "google/gemini-3-flash-preview",
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

        fun refreshPlanDetails() {
            val local = ProSubscriptionVerifier.localStatus(this)
            val planName = local.plan.ifBlank { "none" }
            val provider = ProSubscriptionPrefs.getProvider(this)
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
            btnChangePlan.text = if (provider == "play_billing") "Change in Google Play" else "Change Plan"
            btnManageSubscription.text = when {
                provider == "play_billing" -> "Manage in Google Play"
                planName == "free_trial" -> "End free trial"
                else -> "Cancel subscription"
            }
        }

        fun setButtonBusy(button: MaterialButton, busy: Boolean, busyLabel: String, normalLabel: String) {
            button.isEnabled = !busy
            button.alpha = if (busy) 0.6f else 1f
            button.text = if (busy) busyLabel else normalLabel
        }

        fun refreshQuota() {
            val model = selectedModel(spinnerModelRequests)
            tvQuotaStatus.text = "Quota: loading for model '$model'..."
            setButtonBusy(btnRefreshQuota, true, "Refreshing...", "Refresh quota")
            thread {
                val result = ProSubscriptionRelayClient.fetchQuota(this, model)
                if (!isInForeground) return@thread
                runSafeOnUiThread {
                    setButtonBusy(btnRefreshQuota, false, "Refreshing...", "Refresh quota")
                    result.onSuccess { quota ->
                        val limitText = if (quota.limit > 0) quota.limit.toString() else "unknown"
                        tvQuotaStatus.text =
                            "Quota (${quota.model}): ${quota.remaining} left · used ${quota.used}/$limitText"
                    }.onFailure {
                        val hint = ProSubscriptionRelayClient.relayUnavailableHint(it)
                        tvQuotaStatus.text = if (hint != null) {
                            "Quota unavailable: $hint"
                        } else {
                            "Quota unavailable: ${it.message ?: "unknown error"}"
                        }
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
                        merged["auto"] = "auto"
                        models.forEach { option ->
                            val id = option.id.trim()
                            if (id.isBlank()) return@forEach
                            val label = option.label.trim().ifBlank { id }
                            if (!merged.containsKey(id)) {
                                merged[id] = label
                            }
                        }
                        if (!merged.containsKey(currentRequests)) merged[currentRequests] = currentRequests
                        if (!merged.containsKey(currentQuestions)) merged[currentQuestions] = currentQuestions
                        if (!merged.containsKey(currentTasks)) merged[currentTasks] = currentTasks

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

        btnChangePlan.setOnClickListener {
            showChangePlanDialog()
        }

        btnManageSubscription.setOnClickListener {
            openSubscriptionManagement(btnManageSubscription)
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

            Toast.makeText(this, "Pro settings saved", Toast.LENGTH_SHORT).show()
            refreshQuota()
        }

        backButton.setOnClickListener { finish() }
    }

    private fun showChangePlanDialog() {
        if (ProSubscriptionPrefs.getProvider(this) == "play_billing") {
            openPlaySubscriptionManagement(null)
            return
        }

        val plans = arrayOf("cheap", "standard", "max")
        val labels = arrayOf("Cheap — \$1/month", "Standard — \$5/month", "Max — \$20/month")
        var selected = 0

        AlertDialog.Builder(this)
            .setTitle("Change Plan")
            .setSingleChoiceItems(labels, selected) { _, which ->
                selected = which
            }
            .setPositiveButton("Subscribe") { _, _ ->
                val plan = plans[selected]
                launchWebCheckoutForPlan(plan)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchWebCheckoutForPlan(plan: String) {
        val storedEmail = ProSubscriptionServerPrefs.getAccountEmail(this)
        if (storedEmail.isBlank()) {
            promptForCheckoutEmail(plan)
            return
        }
        launchWebCheckoutWithEmail(plan, storedEmail)
    }

    private fun promptForCheckoutEmail(plan: String) {
        val input = EditText(this).apply {
            hint = "you@example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(ProSubscriptionServerPrefs.getAccountEmail(this@ProSubscriptionSettingsActivity))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Account email")
            .setMessage("Use the same email as your previous purchase so we can restore an active subscription instead of charging again.")
            .setView(input)
            .setPositiveButton("Continue", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawEmail = input.text?.toString().orEmpty()
                val email = ProSubscriptionServerPrefs.normalizeAccountEmail(rawEmail)
                if (!ProSubscriptionServerPrefs.isUsableAccountEmail(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    input.error = "Enter a valid email address"
                    return@setOnClickListener
                }
                ProSubscriptionServerPrefs.setAccountEmail(this, email)
                dialog.dismiss()
                launchWebCheckoutWithEmail(plan, email)
            }
        }

        dialog.show()
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

    private fun launchWebCheckoutWithEmail(plan: String, emailHint: String) {
        val baseUrl = SubscriptionCheckoutPolicy.resolveWebCheckoutUrl(this)
        if (baseUrl.isBlank()) {
            Toast.makeText(this, "Web checkout is not configured yet. Check server URL.", Toast.LENGTH_LONG).show()
            return
        }

        val parsedBase = runCatching { Uri.parse(baseUrl) }.getOrNull()
        if (parsedBase == null || !parsedBase.isAbsolute || parsedBase.scheme.isNullOrBlank()) {
            Toast.makeText(this, "Invalid checkout URL: $baseUrl", Toast.LENGTH_LONG).show()
            return
        }

        val callback = Uri.Builder()
            .scheme("fersaiyan")
            .authority("pro-sub")
            .appendPath("callback")
            .build()

        val target = Uri.parse(baseUrl).buildUpon()
            .appendQueryParameter("plan", plan)
            .appendQueryParameter("change_plan", "1")
            .appendQueryParameter("platform", "android")
            .appendQueryParameter("package_name", packageName)
            .appendQueryParameter("return_url", callback.toString())
            .apply {
                val apiToken = ProSubscriptionServerPrefs.getApiToken(this@ProSubscriptionSettingsActivity)
                if (apiToken.isNotBlank()) {
                    appendQueryParameter("api_token", apiToken)
                }
                val accountEmail = ProSubscriptionServerPrefs.getAccountEmail(this@ProSubscriptionSettingsActivity)
                val finalEmail = emailHint.ifBlank { accountEmail }
                if (finalEmail.isNotBlank()) {
                    appendQueryParameter("email", finalEmail)
                }
            }
            .build()

        val intent = Intent(Intent.ACTION_VIEW, target)
        if (intent.resolveActivityInfo(packageManager, 0) == null) {
            Toast.makeText(this, "No browser found to open checkout.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Opening web checkout...", Toast.LENGTH_SHORT).show()
        try {
            startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(this, "Unable to open checkout: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openSubscriptionManagement(button: MaterialButton) {
        if (ProSubscriptionPrefs.getProvider(this) == "play_billing") {
            openPlaySubscriptionManagement(button)
            return
        }
        val normalLabel = button.text.toString()
        button.isEnabled = false
        button.alpha = 0.6f
        button.text = "Opening..."
        thread {
            val apiToken = runCatching {
                ProSubscriptionServerPrefs.getApiToken(this).trim().ifBlank {
                    ProSubscriptionRelayClient.fetchAccountInfo(this).getOrThrow().apiToken.trim()
                }
            }.getOrDefault("")

            val relayBase = com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs.getRelayBaseUrl(this)
                .trim()
                .trimEnd('/')
            val manageUrl = Uri.parse("$relayBase/web-subscribe/cancel").buildUpon().apply {
                if (apiToken.isNotBlank()) {
                    appendQueryParameter("api_token", apiToken)
                }
            }.build().toString()

            runSafeOnUiThread {
                button.isEnabled = true
                button.alpha = 1f
                button.text = normalLabel
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(manageUrl))
                    if (intent.resolveActivityInfo(packageManager, 0) == null) {
                        Toast.makeText(this, "No browser found to manage the subscription.", Toast.LENGTH_SHORT).show()
                        return@runCatching
                    }
                    startActivity(intent)
                }.onFailure {
                    Toast.makeText(this, "Unable to open subscription management: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
