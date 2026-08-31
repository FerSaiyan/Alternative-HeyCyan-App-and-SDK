package com.fersaiyan.cyanbridge.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.google.android.material.button.MaterialButton
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.shared.billing.BillingProvider
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionUiState
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.shared.ui.pro.ProSubscriptionScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import com.fersaiyan.cyanbridge.ui.installComposeHostWithLegacyAdapter
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Pro Subscription Activity
 * Displays subscription features and handles subscription flow
 */
class ProSubscriptionActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var rgPlan: RadioGroup
    private lateinit var rbTrial: RadioButton
    private lateinit var rbCheap: RadioButton
    private lateinit var rbStandard: RadioButton
    private lateinit var rbMax: RadioButton
    private lateinit var cardWebCheckout: View
    private lateinit var btnSubscribeWeb: MaterialButton
    private lateinit var cardUnsubscribe: View
    private lateinit var btnUnsubscribe: MaterialButton
    private lateinit var btnSubscribe: MaterialButton
    private var billing: PlayBillingManager? = null
    private var playProducts: Map<String, ProductDetails> = emptyMap()
    private var lastBillingError: String = ""
    private var changePlanRequested = false
    private var pendingEmailVerification = ""
    private var emailVerificationRefreshInFlight = false
    private var restoreExistingSubscriptionPending = false
    private var restoreNotFoundEmail by mutableStateOf<String?>(null)
    private var restoreLogsSending by mutableStateOf(false)
    private var composeState by mutableStateOf(ProSubscriptionUiState())
    private lateinit var composeView: ComposeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        composeView = installComposeHostWithLegacyAdapter(R.layout.activity_pro_subscription)

        tvStatus = findViewById(R.id.tv_status)
        rgPlan = findViewById(R.id.rg_plan)
        rbTrial = findViewById(R.id.rb_trial)
        rbCheap = findViewById(R.id.rb_cheap)
        rbStandard = findViewById(R.id.rb_standard)
        rbMax = findViewById(R.id.rb_max)
        cardWebCheckout = findViewById(R.id.card_web_checkout)
        btnSubscribeWeb = findViewById(R.id.btn_subscribe_web)
        cardUnsubscribe = findViewById(R.id.card_unsubscribe)
        btnUnsubscribe = findViewById(R.id.btn_unsubscribe)

        btnSubscribe = findViewById(R.id.btn_subscribe)
        val btnCancel: MaterialButton = findViewById(R.id.btn_cancel)
        val btnDonate: MaterialButton = findViewById(R.id.btn_donate)

        updateStatusDisplay()
        applyWebCheckoutVisibility()
        maybeShowCallbackToast(intent)

        // Set default selection if none selected
        if (rgPlan.checkedRadioButtonId == View.NO_ID) {
            rbTrial.isChecked = true
        }
        applyRequestedPlanFromIntent()
        changePlanRequested = intent?.getBooleanExtra(EXTRA_CHANGE_PLAN, false) == true
        composeState = composeState.copy(googlePlayCheckoutAllowed = isGooglePlayCheckoutAllowed())

        if (ProSubscriptionPrefs.getProvider(this) == "debug_mock") {
            ProSubscriptionPrefs.clearEntitlement(
                context = this,
                provider = "debug_mock_cleared",
                clearPurchaseToken = true,
            )
            Toast.makeText(this, "Cleared old debug mock entitlement", Toast.LENGTH_SHORT).show()
            updateStatusDisplay()
        }

        // If user already has an active plan, guide them to settings instead of repurchasing
        if (maybeRedirectToSettingsIfActive(showToast = false)) return

        btnDonate.setOnClickListener {
            showAsaasDonationDialog()
        }

        btnUnsubscribe.setOnClickListener {
            showUnsubscribeConfirmation()
        }

        btnSubscribe.setOnClickListener {
            if (maybeRedirectToSettingsIfActive(showToast = true)) return@setOnClickListener
            val plan = selectedPlan()
            promptForSubscriptionEmail(plan) { email ->
                if (plan == "free_trial") {
                    activateFreeTrial(email)
                } else {
                    startGooglePlaySubscriptionFlow(plan)
                }
            }
        }

        btnSubscribeWeb.setOnClickListener {
            if (maybeRedirectToSettingsIfActive(showToast = true)) return@setOnClickListener
            if (selectedPlan() == "free_trial") {
                btnSubscribe.performClick()
            } else {
                launchWebCheckout(selectedPlan(), BillingProvider.ASAAS)
            }
        }

        btnCancel.setOnClickListener {
            finish()
        }

        setupBilling()

        val appearancePreferences = AppearancePreferences(this)
        composeView.setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                ProSubscriptionScreen(
                    state = composeState,
                    restoreNotFoundEmail = restoreNotFoundEmail,
                    restoreLogsSending = restoreLogsSending,
                    onPlanSelected = ::selectPlanFromCompose,
                    onStartFreeTrial = { btnSubscribe.performClick() },
                    onSubscribeWithGooglePlay = {
                        val plan = selectedPlan()
                        promptForSubscriptionEmail(plan) { startGooglePlaySubscriptionFlow(plan) }
                    },
                    onSubscribeOnWebsite = { provider ->
                        launchWebCheckout(
                            plan = selectedPlan(),
                            provider = provider,
                            changePlan = changePlanRequested,
                        )
                    },
                    onCheckoutUnavailable = { showCheckoutUnavailableMessage() },
                    onRestoreExistingSubscription = ::restoreExistingProSubscription,
                    onDismissRestoreNotFound = { restoreNotFoundEmail = null },
                    onSendRestoreFailureLogs = ::sendRestoreFailureLogs,
                    onDonate = { btnDonate.performClick() },
                    onCancelSubscription = { btnUnsubscribe.performClick() },
                    onBack = ::finish,
                )
            }
        }
        refreshComposeState()
        prepareEmailVerificationReturn(intent)
    }

    override fun onResume() {
        super.onResume()
        updateStatusDisplay()
        refreshPurchaseStatusFromStore()
        maybePromptPendingDonation()
        refreshPendingEmailVerification()

        // If verification just completed and user now has an active plan, send to settings
        if (maybeRedirectToSettingsIfActive(showToast = false)) return

        val shouldVerifyServerState = ProSubscriptionPrefs.isSubscribed(this) || (
            ProSubscriptionPrefs.getProvider(this) != "play_billing" &&
                ProSubscriptionServerPrefs.getApiToken(this).isNotBlank()
            )

        if (shouldVerifyServerState) {
            thread {
                val verified = ProSubscriptionVerifier.verifyNow(this, strictForTesting = shouldForceStrictVerification())
                runOnUiThread {
                    updateStatusDisplay()
                    // After server sync, if now active and we are not in change-plan mode, open settings
                    if (verified.active) maybeRedirectToSettingsIfActive(showToast = true)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeShowCallbackToast(intent)
        prepareEmailVerificationReturn(intent)
        refreshPendingEmailVerification()
    }

    override fun onDestroy() {
        billing?.destroy()
        super.onDestroy()
    }

    private fun setupBilling() {
        billing = PlayBillingManager(
            context = this,
            onPurchasesUpdated = { purchases ->
                handlePurchases(purchases, applyActivationRouting = true)
            },
            onError = { msg ->
                lastBillingError = msg
                runOnUiThread {
                    Toast.makeText(this, "Billing: $msg", Toast.LENGTH_SHORT).show()
                }
            },
        )

        billing?.start {
            refreshPurchaseStatusFromStore()
            refreshPlayProducts()
        }
    }

    private fun refreshPlayProducts() {
        val productIds = PlaySubscriptionCatalog.allProductIds()
        if (productIds.isEmpty()) {
            playProducts = emptyMap()
            applyProductPricingToUi()
            return
        }

        billing?.querySubscriptionProducts(productIds) { details ->
            runOnUiThread {
                playProducts = details
                applyProductPricingToUi()
            }
        }
    }

    private fun selectedPlan(): String {
        return when (rgPlan.checkedRadioButtonId) {
            R.id.rb_trial -> "free_trial"
            R.id.rb_cheap -> "cheap"
            R.id.rb_max -> "max"
            else -> "standard"
        }
    }

    private fun applyWebCheckoutVisibility() {
        val enabled = SubscriptionCheckoutPolicy.isWebCheckoutEnabled(this)
        cardWebCheckout.visibility = if (enabled) View.VISIBLE else View.GONE
        composeState = composeState.copy(webCheckoutAvailable = enabled)
    }

    private fun selectPlanFromCompose(plan: String) {
        when (plan) {
            "cheap" -> rbCheap.isChecked = true
            "standard" -> rbStandard.isChecked = true
            "max" -> rbMax.isChecked = true
            else -> rbTrial.isChecked = true
        }
        refreshComposeState()
    }

    private fun refreshComposeState() {
        val status = ProSubscriptionVerifier.localStatus(this)
        composeState = composeState.copy(
            status = tvStatus.text?.toString().orEmpty().ifBlank { status.message },
            selectedPlan = selectedPlan(),
            webCheckoutAvailable = SubscriptionCheckoutPolicy.isWebCheckoutEnabled(this),
            isSubscribed = status.active,
            googlePlayCheckoutAllowed = isGooglePlayCheckoutAllowed(),
        )
    }

    private fun isGooglePlayCheckoutAllowed(): Boolean =
        SubscriptionCheckoutPolicy.isGooglePlayCheckoutAllowed(
            changePlanRequested = changePlanRequested,
            currentPlan = ProSubscriptionPrefs.getPlan(this),
            isSubscribed = ProSubscriptionPrefs.isSubscribed(this),
        )

    private fun activateFreeTrial(emailHint: String = "") {
        val finalEmail = ProSubscriptionServerPrefs.normalizeAccountEmail(
            emailHint.ifBlank { ProSubscriptionServerPrefs.getAccountEmail(this) },
        )
        if (!ProSubscriptionServerPrefs.isUsableAccountEmail(finalEmail) || !Patterns.EMAIL_ADDRESS.matcher(finalEmail).matches()) {
            promptForAccountEmail(
                title = "Account email",
                message = "Use a valid email so your free trial can be restored later on another device.",
            ) { confirmedEmail ->
                activateFreeTrial(confirmedEmail)
            }
            return
        }

        ProSubscriptionServerPrefs.setAccountEmail(this, finalEmail)
        if (!ProSubscriptionServerPrefs.isAccountEmailVerified(this, finalEmail)) {
            confirmAccountEmail(finalEmail) { activateFreeTrial(finalEmail) }
            return
        }
        val baseUrl = AiProviderPrefs.getRelayBaseUrl(this).trimEnd('/')
        if (baseUrl.isBlank()) {
            Toast.makeText(this, "Server not configured.", Toast.LENGTH_SHORT).show()
            return
        }
        val url = "$baseUrl/pro/activate-trial"
        Toast.makeText(this, "Activating free trial...", Toast.LENGTH_SHORT).show()

        thread {
            try {
                var token = ProSubscriptionServerPrefs.getApiToken(this)
                if (token.isBlank()) {
                    token = ProSubscriptionRelayClient.fetchAccountInfo(this)
                        .getOrThrow()
                        .apiToken
                        .trim()
                }
                check(token.isNotBlank()) { "Server account token unavailable" }

                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                val body = org.json.JSONObject()
                    .put("email", finalEmail)
                    .toString()
                conn.doOutput = true
                conn.outputStream.write(body.toByteArray())

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val responseBody = (stream ?: conn.inputStream).bufferedReader().use { it.readText() }
                conn.disconnect()

                if (code !in 200..299) {
                    val message = runCatching {
                        org.json.JSONObject(responseBody).optString("error")
                    }.getOrDefault("").ifBlank {
                        runCatching {
                            org.json.JSONObject(responseBody).optString("message")
                        }.getOrDefault("")
                    }.ifBlank {
                        "HTTP $code"
                    }
                    throw IllegalStateException(message)
                }

                val json = org.json.JSONObject(responseBody)
                val ok = json.optBoolean("ok", false)
                val message = json.optString("message", "Unknown response")
                val expiresAtMs = json.optLong("expires_at_ms", 0L)
                val plan = json.optString("plan", "free_trial")

                runOnUiThread {
                    if (ok && expiresAtMs > System.currentTimeMillis()) {
                        ProSubscriptionPrefs.setPlan(this, plan)
                        ProSubscriptionPrefs.setSubscribed(this, true)
                        ProSubscriptionPrefs.setExpiresAt(this, expiresAtMs)
                        ProSubscriptionPrefs.setPurchaseToken(this, "free_trial_${System.currentTimeMillis()}")
                        ProSubscriptionPrefs.setProvider(this, "server_verified")
                        ProSubscriptionPrefs.setLastVerifiedAt(this, System.currentTimeMillis())
                        val routeAction = ProSubscriptionRoutingPolicy.applyAfterActivation(this)
                        val routeNote = ProSubscriptionRoutingPolicy.actionNote(routeAction)
                        val displayPlan = when (plan) {
                            "free_trial" -> "Free Trial (30 days)"
                            "cheap" -> "Cheap"
                            "standard" -> "Standard"
                            "max" -> "Max"
                            else -> plan
                        }
                        val finalMessage = if (routeNote.isBlank()) {
                            "✓ $message — $displayPlan"
                        } else {
                            "✓ $message — $displayPlan · $routeNote"
                        }
                        Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show()
                        updateStatusDisplay()
                        setResult(RESULT_OK)
                        openProSettingsAfterSubscribe()
                    } else {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Free trial failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun applyRequestedPlanFromIntent() {
        when (intent?.getStringExtra(EXTRA_INITIAL_PLAN)?.trim()?.lowercase()) {
            "free_trial" -> rbTrial.isChecked = true
            "cheap" -> rbCheap.isChecked = true
            "max" -> rbMax.isChecked = true
            "standard" -> rbStandard.isChecked = true
        }
    }

    private fun launchWebCheckout(
        plan: String,
        provider: BillingProvider,
        changePlan: Boolean = false,
    ) {
        if (plan == "free_trial") {
            btnSubscribe.performClick()
            return
        }
        promptForSubscriptionEmail(plan) {
            launchWebCheckoutWithEmail(plan, provider, changePlan)
        }
    }

    private fun promptForSubscriptionEmail(plan: String, onConfirmed: (String) -> Unit) {
        val message = if (plan == "free_trial") {
            "Use a valid email so your free trial can be restored later on another device."
        } else {
            "Use the same email as your previous purchase so we can restore an active subscription instead of charging again."
        }
        promptForAccountEmail(
            title = "Account email",
            message = message,
            onConfirmed = onConfirmed,
        )
    }

    private fun restoreExistingProSubscription() {
        if (maybeRedirectToSettingsIfActive(showToast = true)) return
        restoreExistingSubscriptionPending = true
        promptForAccountEmail(
            title = "Restore Pro access",
            message = "Enter the email used for your existing Pro subscription. We will verify it and link this app to that account without starting a checkout.",
        ) { email ->
            verifyRestoredProAccount(email)
        }
    }

    private fun verifyRestoredProAccount(email: String) {
        Toast.makeText(this, "Checking for an active Pro subscription...", Toast.LENGTH_SHORT).show()
        thread {
            val result = runCatching {
                val account = ProSubscriptionRelayClient.fetchAccountInfo(this).getOrThrow()
                check(account.emailVerified && account.email.equals(email, ignoreCase = true)) {
                    "The verified relay account does not match that email."
                }
                ProSubscriptionVerifier.verifyNow(
                    context = this,
                    strictForTesting = shouldForceStrictVerification(),
                    applyActivationRouting = true,
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                restoreExistingSubscriptionPending = false
                result.onSuccess { verified ->
                    updateStatusDisplay()
                    if (verified.active) {
                        setResult(RESULT_OK)
                        Toast.makeText(
                            this,
                            "Pro restored. Your ${verified.plan} plan is active.",
                            Toast.LENGTH_LONG,
                        ).show()
                        openProSettingsAfterSubscribe()
                    } else {
                        val message = "Email verified, but no active Pro subscription was found for this account."
                        tvStatus.text = message
                        refreshComposeState()
                        restoreNotFoundEmail = email
                    }
                }.onFailure { error ->
                    val message = ProSubscriptionRelayClient.relayUnavailableHint(error)
                        ?: error.message
                        ?: "Unable to restore Pro access."
                    tvStatus.text = message
                    refreshComposeState()
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun sendRestoreFailureLogs() {
        val email = restoreNotFoundEmail ?: return
        if (restoreLogsSending) return
        restoreLogsSending = true
        lifecycleScope.launch(Dispatchers.IO) {
            val localStatus = ProSubscriptionVerifier.localStatus(this@ProSubscriptionActivity)
            val result = DebugLogSupport.sendLogsToServer(
                context = this@ProSubscriptionActivity,
                issueType = "pro_subscription_restore_failed",
                description = "User tried to recover an existing Pro subscription for $email, but no active subscription was found after email verification.",
                logs = DebugLogSupport.collectLogcat(),
                deviceInfo = DebugLogSupport.buildDeviceInfo(
                    context = this@ProSubscriptionActivity,
                    extraInfo = linkedMapOf(
                        "Pro recovery email" to email,
                        "Recovery result" to "No active subscription found",
                        "Local subscription active" to localStatus.active.toString(),
                        "Local subscription plan" to localStatus.plan,
                        "Local subscription provider" to ProSubscriptionPrefs.getProvider(this@ProSubscriptionActivity),
                    ),
                ),
                contactEmail = email,
                requestMetadata = "Pro recovery failed for verified email: $email",
            )
            withContext(Dispatchers.Main) {
                restoreLogsSending = false
                if (isFinishing || isDestroyed) return@withContext
                result.onSuccess { logId ->
                    restoreNotFoundEmail = null
                    Toast.makeText(
                        this@ProSubscriptionActivity,
                        "Logs sent to the developer. Reference: $logId",
                        Toast.LENGTH_LONG,
                    ).show()
                }.onFailure { error ->
                    val message = ProSubscriptionRelayClient.relayUnavailableHint(error)
                        ?: error.message
                        ?: "Unable to send logs."
                    Toast.makeText(this@ProSubscriptionActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun promptForAccountEmail(
        title: String,
        message: String,
        onConfirmed: (String) -> Unit,
    ) {
        val input = EditText(this).apply {
            hint = "you@example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(ProSubscriptionServerPrefs.getAccountEmail(this@ProSubscriptionActivity))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
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
                confirmAccountEmail(email) { onConfirmed(email) }
            }
        }

        dialog.show()
    }

    private fun requestAccountEmailVerification(email: String, onEmailMatchConfirmed: () -> Unit) {
        if (ProSubscriptionPrefs.isActiveLocally(this) && !changePlanRequested) {
            if (maybeRedirectToSettingsIfActive(showToast = true)) return
        }
        Toast.makeText(this, "Sending verification email...", Toast.LENGTH_SHORT).show()
        thread {
            val result = ProSubscriptionRelayClient.requestAccountEmailVerification(this, email)
            runOnUiThread {
                result.onSuccess { verification ->
                    if (verification.isEmailMatchFallback) {
                        pendingEmailVerification = ""
                        ProSubscriptionServerPrefs.setVerifiedAccountEmail(this, email)
                        Toast.makeText(this, verification.message, Toast.LENGTH_LONG).show()
                        // If already active, don't re-trigger purchase - go to settings
                        if (ProSubscriptionPrefs.isActiveLocally(this) && !changePlanRequested) {
                            if (maybeRedirectToSettingsIfActive(showToast = true)) return@onSuccess
                        }
                        onEmailMatchConfirmed()
                    } else {
                        pendingEmailVerification = email
                        showCodeEntryDialog(email, verification.message, onEmailMatchConfirmed, verification.verificationUrl)
                    }
                }.onFailure { error ->
                    Toast.makeText(this, "Unable to verify email: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showCodeEntryDialog(
        email: String,
        initialMessage: String,
        onVerified: () -> Unit,
        verificationUrl: String? = null,
    ) {
        pendingEmailVerification = email
        val codeInput = EditText(this).apply {
            hint = "123456"
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(48, 32, 48, 32)
        }
        val message = buildString {
            append(initialMessage)
            append("\n\nWe sent a 6-digit code to ")
            append(email)
            append(" (expires in 15 min). Enter it below, or tap the link in your email (link expires in 60 min).")
            if (verificationUrl != null) append("\n\nTip: in debug builds the link opens directly.")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Verify your email")
            .setMessage(message)
            .setView(codeInput)
            .setPositiveButton("Verify", null)
            .setNeutralButton("Resend code", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)

            positive.setOnClickListener {
                val rawCode = codeInput.text?.toString().orEmpty()
                if (!rawCode.trim().replace(Regex("[\\s-]"), "").matches(Regex("^[0-9]{6}$"))) {
                    codeInput.error = "Enter 6 digits"
                    return@setOnClickListener
                }
                positive.isEnabled = false
                neutral.isEnabled = false
                Toast.makeText(this, "Verifying code...", Toast.LENGTH_SHORT).show()
                thread {
                    val result = ProSubscriptionRelayClient.verifyAccountEmailCode(this, rawCode)
                    runOnUiThread {
                        result.onSuccess {
                            pendingEmailVerification = ""
                            ProSubscriptionServerPrefs.setVerifiedAccountEmail(this, email)
                            dialog.dismiss()
                            // If verification also synced an active subscription, send to settings
                            thread {
                                val serverState = ProSubscriptionVerifier.verifyNow(this, strictForTesting = shouldForceStrictVerification())
                                runOnUiThread {
                                    if (serverState.active || ProSubscriptionPrefs.isActiveLocally(this)) {
                                        Toast.makeText(this, "Email verified. Your ${serverState.plan} plan is already active.", Toast.LENGTH_LONG).show()
                                        maybeRedirectToSettingsIfActive(showToast = false)
                                        // Fall through to onVerified only if not redirected and not in change-plan mode
                                        if (!ProSubscriptionPrefs.isActiveLocally(this) || changePlanRequested) onVerified()
                                    } else {
                                        Toast.makeText(this, "Email verified. Choose your plan to continue.", Toast.LENGTH_LONG).show()
                                        onVerified()
                                    }
                                }
                            }
                        }.onFailure { error ->
                            positive.isEnabled = true
                            neutral.isEnabled = true
                            val hint = ProSubscriptionRelayClient.relayUnavailableHint(error)
                            val msg = hint ?: error.message ?: "Invalid code"
                            codeInput.error = msg
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            neutral.setOnClickListener {
                dialog.dismiss()
                requestAccountEmailVerification(email, onVerified)
            }

            negative.setOnClickListener {
                dialog.dismiss()
            }

            // In draft/debug where server returns a direct link, offer it as an extra tap target
            if (verificationUrl != null) {
                codeInput.hint = "123456 or tap Open link"
                neutral.text = "Open link"
                neutral.setOnClickListener {
                    dialog.dismiss()
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl)))
                        Toast.makeText(this, "Opened verification link. Return here after confirming.", Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {
                        Toast.makeText(this, "Unable to open link. Copy the code from your email.", Toast.LENGTH_LONG).show()
                    }
                }
                // Long-press neutral to resend instead
                neutral.setOnLongClickListener {
                    dialog.dismiss()
                    requestAccountEmailVerification(email, onVerified)
                    true
                }
            }
        }
        dialog.show()
    }

    private fun confirmAccountEmail(email: String, onConfirmed: () -> Unit) {
        if (ProSubscriptionPrefs.isActiveLocally(this) && !changePlanRequested) {
            if (maybeRedirectToSettingsIfActive(showToast = true)) return
        }
        if (ProSubscriptionServerPrefs.isAccountEmailVerified(this, email)) {
            onConfirmed()
            return
        }

        Toast.makeText(this, "Checking account verification...", Toast.LENGTH_SHORT).show()
        thread {
            val accountResult = ProSubscriptionRelayClient.fetchAccountInfo(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ProSubscriptionServerPrefs.isAccountEmailVerified(this, email)) {
                    pendingEmailVerification = ""
                    onConfirmed()
                } else if (accountResult.isFailure) {
                    Toast.makeText(
                        this,
                        "Unable to check verification. Check your connection and try again.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else if (pendingEmailVerification.equals(email, ignoreCase = true)) {
                    Toast.makeText(
                        this,
                        "Verification is still being confirmed. Wait a moment, then try again.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    requestAccountEmailVerification(email, onConfirmed)
                }
            }
        }
    }

    private fun prepareEmailVerificationReturn(intent: Intent?) {
        if (!SubscriptionCheckoutPolicy.isEmailVerificationReturn(intent?.data)) return
        pendingEmailVerification = ProSubscriptionServerPrefs.getAccountEmail(this)
    }

    private fun refreshPendingEmailVerification() {
        val email = pendingEmailVerification
        if (
            email.isBlank() ||
            emailVerificationRefreshInFlight ||
            ProSubscriptionServerPrefs.isAccountEmailVerified(this, email)
        ) return

        emailVerificationRefreshInFlight = true
        thread {
            var lastResult: Result<ProSubscriptionRelayClient.AccountInfo>? = null
            for (attempt in 0 until 3) {
                lastResult = ProSubscriptionRelayClient.fetchAccountInfo(this)
                if (ProSubscriptionServerPrefs.isAccountEmailVerified(this, email)) break
                if (attempt < 2) Thread.sleep(750L)
            }
            runOnUiThread {
                emailVerificationRefreshInFlight = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ProSubscriptionServerPrefs.isAccountEmailVerified(this, email)) {
                    pendingEmailVerification = ""
                    if (restoreExistingSubscriptionPending) {
                        verifyRestoredProAccount(email)
                    } else {
                        Toast.makeText(this, "Email verified. Choose your plan to continue.", Toast.LENGTH_LONG).show()
                    }
                } else if (lastResult?.isFailure == true) {
                    Toast.makeText(
                        this,
                        "Unable to confirm verification. Check your connection and try again.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "Verification is not confirmed yet. Wait a moment, then try again.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun startGooglePlaySubscriptionFlow(plan: String) {
        if (!isGooglePlayCheckoutAllowed()) {
            Toast.makeText(
                this,
                "Change this web subscription through website checkout to avoid overlapping subscriptions.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val playOffer = PlaySubscriptionCatalog.offerForPlan(plan)
        if (playOffer != null) {
            val playProduct = playProducts[playOffer.productId]
            if (playProduct != null) {
                billing?.launchSubscriptionPurchase(
                    activity = this,
                    productDetails = playProduct,
                    offer = playOffer,
                    obfuscatedAccountId = playBillingAccountId(),
                )
                return
            }
        }

        val detail = buildString {
            append("Google Play checkout is unavailable for this plan. Choose website checkout instead.")
            if (lastBillingError.isNotBlank()) {
                append(" (billing: ")
                append(lastBillingError)
                append(")")
            }
        }
        Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
    }

    private fun playBillingAccountId(): String? {
        val apiToken = ProSubscriptionServerPrefs.getApiToken(this).trim()
        if (apiToken.isBlank()) return null
        return MessageDigest.getInstance("SHA-256")
            .digest(apiToken.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun showCheckoutUnavailableMessage() {
        val detail = buildString {
            append("No checkout is available for this plan right now.")
            if (lastBillingError.isNotBlank()) {
                append(" (billing: ")
                append(lastBillingError)
                append(")")
            }
        }
        Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
    }

    private fun openPlaySubscriptionManagement() {
        val productId = PlaySubscriptionCatalog.productIdForPlan(ProSubscriptionPrefs.getPlan(this))

        val uri = Uri.parse("https://play.google.com/store/account/subscriptions").buildUpon().apply {
            appendQueryParameter("package", packageName)
            if (productId.isNotBlank()) {
                appendQueryParameter("sku", productId)
            }
        }.build()

        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            if (intent.resolveActivityInfo(packageManager, 0) == null) {
                Toast.makeText(this, "No app found to manage the Google Play subscription.", Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, "Unable to open Google Play subscription management: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchWebCheckoutWithEmail(
        plan: String,
        provider: BillingProvider,
        changePlan: Boolean = false,
    ) {
        val providerName = when (provider) {
            BillingProvider.ASAAS -> "Asaas"
            BillingProvider.PADDLE -> "Paddle"
            BillingProvider.GOOGLE_PLAY -> {
                Toast.makeText(this, "Google Play is not a website checkout provider.", Toast.LENGTH_LONG).show()
                return
            }
        }
        Toast.makeText(this, "Preparing $providerName checkout...", Toast.LENGTH_SHORT).show()
        thread {
            val result = runCatching {
                val callbackResult = ProSubscriptionServerPrefs.createWebCallbackResult(
                    context = this,
                    purpose = ProSubscriptionServerPrefs.WebCallbackPurpose.SUBSCRIPTION,
                )
                val callbackUrl = SubscriptionCheckoutPolicy.createVerifiedCallbackUrl(callbackResult)
                ProSubscriptionRelayClient.createWebCheckoutSession(
                    context = this,
                    plan = plan,
                    provider = provider,
                    returnUrl = callbackUrl,
                    changePlan = changePlan,
                ).getOrThrow()
            }
            runOnUiThread {
                result.onSuccess { checkoutUrl ->
                    openExternalUrl(checkoutUrl)
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        "Unable to prepare checkout: ${error.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun showDonationIntroDialog(pending: PendingDonationPrefs.PendingDonation) {
        AlertDialog.Builder(this)
            .setTitle(R.string.donation_intro_dialog_title)
            .setMessage(getString(R.string.donation_intro_dialog_message))
            .setPositiveButton(R.string.donation_intro_open_button) { _, _ ->
                PendingDonationPrefs.setAwaitingReturn(this, true)
                openExternalUrl(pending.invoiceUrl)
            }
            .setNegativeButton("Cancel") { _, _ ->
                PendingDonationPrefs.clear(this)
            }
            .show()
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            InAppBrowser.open(this, url)
        }.onFailure {
            Toast.makeText(this, "Unable to open website checkout: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun maybeShowCallbackToast(intent: Intent?) {
        val msg = intent?.getStringExtra(EXTRA_CALLBACK_MESSAGE).orEmpty().trim()
        if (msg.isNotBlank()) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            intent?.removeExtra(EXTRA_CALLBACK_MESSAGE)
            intent?.removeExtra(EXTRA_FROM_WEB_CALLBACK)
        }
    }

    private fun applyProductPricingToUi() {
        fun priceFor(plan: String): String? {
            val offer = PlaySubscriptionCatalog.offerForPlan(plan) ?: return null
            val product = playProducts[offer.productId] ?: return null
            return PlayBillingManager.localizedOfferDescription(product, offer)
        }

        val plans = listOf("cheap", "standard", "max")
        val prices = plans
            .mapNotNull { plan -> priceFor(plan)?.let { plan to it } }
            .toMap()
        val availablePlans = plans.filter { plan ->
            val offer = PlaySubscriptionCatalog.offerForPlan(plan)
            val product = offer?.let { playProducts[it.productId] }
            offer != null && product != null && PlaySubscriptionCatalog.configuredOffer(product, offer) != null
        }.toSet()

        fun updateLabel(button: RadioButton, plan: String, name: String) {
            val price = prices[plan] ?: "Google Play price unavailable"
            button.text = "$name - $price"
        }

        updateLabel(rbCheap, "cheap", "Cheap")
        updateLabel(rbStandard, "standard", "Standard")
        updateLabel(rbMax, "max", "Max")
        composeState = composeState.copy(
            playPriceLabels = prices,
            playCheckoutAvailablePlans = availablePlans,
        )
    }

    private fun refreshPurchaseStatusFromStore() {
        billing?.queryActivePurchases { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases, applyActivationRouting = false)
            }
        }
    }

    private fun handlePurchases(
        purchases: List<Purchase>,
        applyActivationRouting: Boolean,
    ) {
        val active = purchases.firstOrNull { p ->
            p.purchaseState == Purchase.PurchaseState.PURCHASED &&
                p.products.any { PlaySubscriptionCatalog.planForProductId(it).isNotBlank() }
        }

        if (active != null) {
            billing?.acknowledgeIfNeeded(active)
            val plan = active.products
                .firstNotNullOfOrNull { productId ->
                    PlaySubscriptionCatalog.planForProductId(productId).ifBlank { null }
                }
            if (plan != null) {
                applyLocalSubscription(
                    plan = plan,
                    purchaseToken = active.purchaseToken,
                    source = "play_billing",
                    applyActivationRouting = applyActivationRouting,
                )
            }

            // Optional server verification path (if endpoint is configured).
            thread {
                ProSubscriptionVerifier.verifyNow(this, strictForTesting = shouldForceStrictVerification())
                runOnUiThread { updateStatusDisplay() }
            }
        } else {
            maybeClearStalePlayEntitlement()
        }
    }

    private fun maybeClearStalePlayEntitlement() {
        val provider = ProSubscriptionPrefs.getProvider(this)
        // Restrict clearing logic strictly to play_billing.
        // Web / server_verified / Asaas / Paddle subscribers must NOT be cleared when Google Play returns no purchases.
        if (provider != "play_billing") return
        if (!ProSubscriptionPrefs.isSubscribed(this)) return

        ProSubscriptionPrefs.clearEntitlement(
            context = this,
            provider = "play_purchase_missing",
            clearPurchaseToken = false,
        )
        Toast.makeText(this, "No active Play subscription found for this account.", Toast.LENGTH_LONG).show()
        updateStatusDisplay()
    }

    private fun shouldForceStrictVerification(): Boolean {
        if (!BuildConfig.DEBUG) return false
        return when (ProSubscriptionPrefs.getProvider(this)) {
            "play_billing", "verification_required" -> true
            else -> false
        }
    }

    private fun applyLocalSubscription(
        plan: String,
        purchaseToken: String,
        source: String,
        applyActivationRouting: Boolean,
    ) {
        val now = System.currentTimeMillis()
        ProSubscriptionPrefs.setPlan(this, plan)
        ProSubscriptionPrefs.setSubscribed(this, true)
        ProSubscriptionPrefs.setPurchaseToken(this, purchaseToken)
        ProSubscriptionPrefs.setProvider(this, source)

        // For play_billing, immediately request backend verification if a verifier URL is configured.
        val verifyUrl = ProSubscriptionServerPrefs.getVerifyUrl(this).ifBlank { BuildConfig.PRO_SUB_VERIFY_URL }
        if (source == "play_billing" && verifyUrl.isNotBlank()) {
            thread {
                val result = ProSubscriptionVerifier.verifyNow(this, strictForTesting = false)
                runOnUiThread { updateStatusDisplay() }
            }
        } else {
            val expiresAt = when (plan) {
                "max" -> now + 365L * 24L * 60L * 60L * 1000L
                "free_trial" -> now + 30L * 24L * 60L * 60L * 1000L
                else -> now + 31L * 24L * 60L * 60L * 1000L
            }
            ProSubscriptionPrefs.setExpiresAt(this, expiresAt)
            ProSubscriptionPrefs.setLastVerifiedAt(this, now)
        }

        val routeAction = if (applyActivationRouting) {
            ProSubscriptionRoutingPolicy.applyAfterActivation(this)
        } else {
            ProSubscriptionRoutingPolicy.Action.NO_CHANGE
        }
        val routeNote = ProSubscriptionRoutingPolicy.actionNote(routeAction)

        val planName = when (plan) {
            "free_trial" -> "Free Trial (30 days)"
            "cheap" -> "Cheap${composeState.playPriceLabels[plan]?.let { " ($it)" }.orEmpty()}"
            "standard" -> "Standard${composeState.playPriceLabels[plan]?.let { " ($it)" }.orEmpty()}"
            "max" -> "Max${composeState.playPriceLabels[plan]?.let { " ($it)" }.orEmpty()}"
            else -> plan
        }

        val finalMessage = if (routeNote.isBlank()) {
            "✓ Subscribed to $planName"
        } else {
            "✓ Subscribed to $planName · $routeNote"
        }

        Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show()
        updateStatusDisplay()
        setResult(RESULT_OK)
        openProSettingsAfterSubscribe()
    }

    private fun openProSettingsAfterSubscribe() {
        startActivity(Intent(this, ProSubscriptionSettingsActivity::class.java))
        finish()
    }

    private fun maybeRedirectToSettingsIfActive(showToast: Boolean): Boolean {
        if (changePlanRequested) return false
        if (!ProSubscriptionPrefs.isActiveLocally(this)) return false
        if (showToast) {
            Toast.makeText(this, "You already have an active ${ProSubscriptionPrefs.getPlan(this)} plan. Opening settings.", Toast.LENGTH_SHORT).show()
        }
        // Post the navigation to avoid finishing during onCreate before the view is fully attached
        window.decorView.post {
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, ProSubscriptionSettingsActivity::class.java))
                finish()
            }
        }
        return true
    }

    private fun updateStatusDisplay() {
        val status = ProSubscriptionVerifier.localStatus(this)
        val plan = status.plan.ifBlank { "none" }
        val provider = ProSubscriptionPrefs.getProvider(this)

        tvStatus.text = if (status.active) {
            "✓ Active on $plan plan · source=$provider"
        } else {
            "Not subscribed · ${status.message}"
        }

        cardUnsubscribe.visibility = if (status.active) View.VISIBLE else View.GONE
        composeState = composeState.copy(
            status = tvStatus.text?.toString().orEmpty(),
            selectedPlan = selectedPlan(),
            webCheckoutAvailable = SubscriptionCheckoutPolicy.isWebCheckoutEnabled(this),
            isSubscribed = status.active,
        )
    }

    private fun showAsaasDonationDialog() {
        val amounts = arrayOf("$3", "$5", "$10")
        AlertDialog.Builder(this)
            .setTitle("Select Donation Amount")
            .setItems(amounts) { _, which ->
                val amountClean = amounts[which].removePrefix("$")
                promptForDonationEmail(amountClean)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptForDonationEmail(amount: String) {
        val input = EditText(this).apply {
            hint = "you@example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(ProSubscriptionServerPrefs.getAccountEmail(this@ProSubscriptionActivity))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Donate \$$amount via Asaas")
            .setMessage("Enter your email to receive the receipt. We use Asaas (secure Brazilian processor) for one-time credit card payments.")
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
                startAsaasDonation(amount, email)
            }
        }

        dialog.show()
    }

    private fun startAsaasDonation(amount: String, email: String) {
        val baseUrl = AiProviderPrefs.getRelayBaseUrl(this).trimEnd('/')
        if (baseUrl.isBlank()) {
            Toast.makeText(this, "Server not configured.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Preparing donation checkout...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                var apiToken = ProSubscriptionServerPrefs.getApiToken(this)
                if (apiToken.isBlank()) {
                    apiToken = ProSubscriptionRelayClient.fetchAccountInfo(this)
                        .getOrThrow()
                        .apiToken
                        .trim()
                }

                val callbackResult = ProSubscriptionServerPrefs.createWebCallbackResult(
                    context = this,
                    purpose = ProSubscriptionServerPrefs.WebCallbackPurpose.DONATION,
                )
                val callbackUrl = SubscriptionCheckoutPolicy.createVerifiedCallbackUrl(callbackResult)

                val conn = URL("$baseUrl/api/donate").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                val requestBody = JSONObject()
                    .put("email", email)
                    .put("amount", amount.toIntOrNull() ?: 5)
                    .put("return_url", callbackUrl)
                    .put("api_token", apiToken)
                    .toString()
                conn.outputStream.write(requestBody.toByteArray())

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()

                if (code !in 200..299) {
                    val msg = runCatching { JSONObject(body).optString("message") }.getOrDefault("HTTP $code")
                    throw IllegalStateException(msg)
                }

                val json = JSONObject(body)
                val invoiceUrl = json.optString("invoice_url").trim()
                val statusUrl = json.optString("status_url").trim()

                if (invoiceUrl.isBlank() || statusUrl.isBlank()) {
                    throw IllegalStateException("Donation setup did not return checkout links.")
                }

                val pending = PendingDonationPrefs.PendingDonation(
                    invoiceUrl = invoiceUrl,
                    statusUrl = statusUrl,
                    amount = amount,
                )
                PendingDonationPrefs.save(this, pending)
                runOnUiThread {
                    showDonationIntroDialog(pending)
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Donation setup failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun maybePromptPendingDonation() {
        if (!PendingDonationPrefs.isAwaitingReturn(this)) return
        val pending = PendingDonationPrefs.get(this) ?: run {
            PendingDonationPrefs.clear(this)
            return
        }

        PendingDonationPrefs.setAwaitingReturn(this, false)
        AlertDialog.Builder(this)
            .setTitle("Donation Return")
            .setMessage("Did you complete the donation of \$${pending.amount} in the Asaas card form?")
            .setPositiveButton("Yes, Check Status") { _, _ ->
                verifyPendingDonation(pending)
            }
            .setNeutralButton("Open Card Form Again") { _, _ ->
                PendingDonationPrefs.setAwaitingReturn(this, true)
                openExternalUrl(pending.invoiceUrl)
            }
            .setNegativeButton("Cancel") { _, _ ->
                PendingDonationPrefs.clear(this)
            }
            .show()
    }

    private fun verifyPendingDonation(pending: PendingDonationPrefs.PendingDonation) {
        Toast.makeText(this, "Checking donation confirmation...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val conn = URL(pending.statusUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" }
                val json = JSONObject(body)

                if (code !in 200..299) {
                    throw IllegalStateException(json.optString("message", "Unable to verify donation yet."))
                }

                val confirmed = json.optBoolean("confirmed", false)
                val message = json.optString("message", if (confirmed) "Donation confirmed. Thank you!" else "Donation pending.")

                if (confirmed) {
                    PendingDonationPrefs.clear(this)
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Thank You!")
                            .setMessage("Your donation of \$${pending.amount} has been confirmed. Thank you for supporting CyanBridge!")
                            .setPositiveButton("Great!", null)
                            .show()
                    }
                    return@thread
                }

                PendingDonationPrefs.setAwaitingReturn(this, true)
                runOnUiThread {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            } catch (error: Throwable) {
                PendingDonationPrefs.setAwaitingReturn(this, true)
                runOnUiThread {
                    Toast.makeText(this, "Unable to verify donation: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showUnsubscribeConfirmation() {
        if (ProSubscriptionPrefs.getProvider(this) == "play_billing") {
            openPlaySubscriptionManagement()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Cancel Subscription?")
            .setMessage("Are you sure you want to cancel your subscription? You'll keep access until the end of your current billing period.")
            .setPositiveButton("Yes, Cancel") { _, _ ->
                performUnsubscribe()
            }
            .setNegativeButton("No, Keep It", null)
            .show()
    }

    private fun performUnsubscribe() {
        Toast.makeText(this, "Cancelling subscription...", Toast.LENGTH_SHORT).show()
        thread {
            val result = ProSubscriptionRelayClient.cancelSubscription(this)
            runOnUiThread {
                result.onSuccess { cancel ->
                    ProSubscriptionPrefs.setSubscribed(this, cancel.active)
                    ProSubscriptionPrefs.setPlan(this, cancel.plan)
                    ProSubscriptionPrefs.setExpiresAt(this, cancel.expiresAtMs)
                    ProSubscriptionPrefs.setProvider(this, "server_verified")
                    ProSubscriptionPrefs.setLastVerifiedAt(this, System.currentTimeMillis())
                    if (!cancel.active) {
                        ProSubscriptionPrefs.setPurchaseToken(this, "")
                    }
                    updateStatusDisplay()
                    Toast.makeText(this, cancel.message, Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this, "Unable to cancel subscription: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        const val EXTRA_CALLBACK_MESSAGE = "extra_callback_message"
        const val EXTRA_FROM_WEB_CALLBACK = "extra_from_web_callback"
        const val EXTRA_INITIAL_PLAN = "extra_initial_plan"
        const val EXTRA_CHANGE_PLAN = "extra_change_plan"
    }
}
