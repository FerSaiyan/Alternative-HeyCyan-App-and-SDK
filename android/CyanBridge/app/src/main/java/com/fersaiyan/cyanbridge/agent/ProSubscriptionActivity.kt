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
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.google.android.material.button.MaterialButton
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import kotlin.concurrent.thread
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
    private var legacyReturnDialogVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pro_subscription)

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

        if (ProSubscriptionPrefs.getProvider(this) == "debug_mock") {
            ProSubscriptionPrefs.clearEntitlement(
                context = this,
                provider = "debug_mock_cleared",
                clearPurchaseToken = true,
            )
            Toast.makeText(this, "Cleared old debug mock entitlement", Toast.LENGTH_SHORT).show()
            updateStatusDisplay()
        }

        btnDonate.setOnClickListener {
            showStripeDonationDialog()
        }

        btnUnsubscribe.setOnClickListener {
            showUnsubscribeConfirmation()
        }

        btnSubscribe.setOnClickListener {
            val plan = selectedPlan()
            promptForSubscriptionEmail(plan) { email ->
                startSubscriptionFlow(plan, email)
            }
        }

        btnSubscribeWeb.setOnClickListener {
            launchWebCheckout(selectedPlan())
        }

        btnCancel.setOnClickListener {
            finish()
        }

        setupBilling()

        window.decorView.post {
            maybeAutoStartWebCheckoutFromIntent()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusDisplay()
        refreshPurchaseStatusFromStore()
        maybePromptPendingLegacyCheckout()

        if (ProSubscriptionPrefs.isSubscribed(this)) {
            thread {
                ProSubscriptionVerifier.verifyNow(this, strictForTesting = shouldForceStrictVerification())
                runOnUiThread {
                    updateStatusDisplay()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeShowCallbackToast(intent)
    }

    override fun onDestroy() {
        billing?.destroy()
        super.onDestroy()
    }

    private fun setupBilling() {
        billing = PlayBillingManager(
            context = this,
            onPurchasesUpdated = { purchases ->
                handlePurchases(purchases)
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
        if (productIds.isEmpty()) return

        billing?.querySubscriptionProducts(productIds) { details ->
            runOnUiThread {
                playProducts = details
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
    }

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
                            "cheap" -> "Cheap ($1/mo)"
                            "standard" -> "Standard ($5/mo)"
                            "max" -> "Max ($20/mo)"
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

    private fun maybeAutoStartWebCheckoutFromIntent() {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START_WEB_CHECKOUT, false) != true) return

        val requestedPlan = intent?.getStringExtra(EXTRA_INITIAL_PLAN)?.trim().orEmpty().ifBlank { selectedPlan() }
        val requestedEmail = intent?.getStringExtra(EXTRA_AUTO_WEB_CHECKOUT_EMAIL)?.trim().orEmpty()
        val changePlan = intent?.getBooleanExtra(EXTRA_AUTO_WEB_CHECKOUT_CHANGE_PLAN, false) == true

        intent?.removeExtra(EXTRA_AUTO_START_WEB_CHECKOUT)
        intent?.removeExtra(EXTRA_AUTO_WEB_CHECKOUT_EMAIL)
        intent?.removeExtra(EXTRA_AUTO_WEB_CHECKOUT_CHANGE_PLAN)

        if (requestedEmail.isNotBlank()) {
            launchWebCheckoutWithEmail(requestedPlan, requestedEmail, changePlan)
        } else {
            launchWebCheckout(requestedPlan, changePlan)
        }
    }

    private fun launchWebCheckout(plan: String, changePlan: Boolean = false) {
        promptForCheckoutEmail(plan, changePlan)
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

    private fun promptForCheckoutEmail(plan: String, changePlan: Boolean = false) {
        promptForSubscriptionEmail(plan) { email ->
            launchWebCheckoutWithEmail(plan, email, changePlan)
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
                onConfirmed(email)
            }
        }

        dialog.show()
    }

    private fun startSubscriptionFlow(plan: String, emailHint: String = "") {
        if (plan == "free_trial") {
            activateFreeTrial(emailHint)
            return
        }

        val playProduct = playProductForPlan(plan)
        if (playProduct != null) {
            billing?.launchSubscriptionPurchase(this, playProduct)
            return
        }

        if (SubscriptionCheckoutPolicy.isWebCheckoutEnabled(this)) {
            val fallbackMessage = if (plan == "cheap") {
                "Cheap plan is available on the website checkout only."
            } else {
                "Google Play checkout is unavailable for this plan. Opening website checkout..."
            }
            Toast.makeText(this, fallbackMessage, Toast.LENGTH_SHORT).show()
            launchWebCheckoutWithEmail(plan, emailHint)
            return
        }

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

    private fun playProductForPlan(plan: String): ProductDetails? {
        val productId = PlaySubscriptionCatalog.productIdForPlan(plan)
        if (productId.isBlank()) return null
        return playProducts[productId]
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

    private fun launchWebCheckoutWithEmail(plan: String, emailHint: String, changePlan: Boolean = false) {
        val baseUrl = SubscriptionCheckoutPolicy.resolveWebCheckoutUrl(this)
        if (baseUrl.isBlank()) {
            Toast.makeText(this, "Web checkout is not configured yet.", Toast.LENGTH_SHORT).show()
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
            .appendQueryParameter("platform", "android")
            .appendQueryParameter("package_name", packageName)
            .appendQueryParameter("return_url", callback.toString())
            .appendQueryParameter("change_plan", if (changePlan) "1" else "0")
            .appendQueryParameter("legacy_checkout", "1")
            .appendQueryParameter("native_legacy", "1")
            .apply {
                val accountEmail = ProSubscriptionServerPrefs.getAccountEmail(this@ProSubscriptionActivity)
                val finalEmail = emailHint.ifBlank { accountEmail }
                if (finalEmail.isNotBlank()) {
                    appendQueryParameter("email", finalEmail)
                }
            }
            .build()

        Toast.makeText(this, "Preparing secure checkout...", Toast.LENGTH_SHORT).show()
        thread {
            val apiToken = runCatching {
                ProSubscriptionServerPrefs.getApiToken(this).trim().ifBlank {
                    ProSubscriptionRelayClient.fetchAccountInfo(this).getOrThrow().apiToken.trim()
                }
            }.getOrDefault("")

            val accountEmail = ProSubscriptionServerPrefs.getAccountEmail(this)
            val finalEmail = emailHint.ifBlank { accountEmail }

            val setupTarget = target.buildUpon().apply {
                if (apiToken.isNotBlank()) {
                    appendQueryParameter("api_token", apiToken)
                }
                if (finalEmail.isNotBlank()) {
                    appendQueryParameter("email", finalEmail)
                }
            }.build().toString()

            val browserFallbackTarget = setupTarget

            try {
                val conn = URL(setupTarget).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                val responseCode = conn.responseCode
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (responseCode !in 200..299) {
                    throw IllegalStateException(body.ifBlank { "Legacy checkout setup failed." })
                }

                val session = parseLegacyCheckoutSession(
                    body = body,
                    baseUrl = baseUrl,
                    fallbackPlan = plan,
                    fallbackApiToken = apiToken,
                    fallbackEmail = finalEmail,
                ) ?: throw IllegalStateException("Could not find the Asaas card form link in the checkout page.")

                if (session.invoiceUrl.isBlank() || session.statusUrl.isBlank() || session.apiToken.isBlank()) {
                    throw IllegalStateException("Legacy checkout did not return the expected Asaas links.")
                }

                PendingLegacyCheckoutPrefs.save(this, session)
                PendingLegacyCheckoutPrefs.setAwaitingReturn(this, false)
                runOnUiThread {
                    showLegacyCheckoutIntroDialog(session)
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Falling back to browser checkout: ${error.message}", Toast.LENGTH_LONG).show()
                    openExternalUrl(browserFallbackTarget)
                }
            }
        }
    }

    private fun buildLegacyStatusUrl(baseUrl: String, apiToken: String, plan: String): String {
        return Uri.parse(baseUrl).buildUpon()
            .appendPath("status")
            .appendQueryParameter("api_token", apiToken)
            .appendQueryParameter("plan", plan)
            .build()
            .toString()
    }

    private fun parseLegacyCheckoutSession(
        body: String,
        baseUrl: String,
        fallbackPlan: String,
        fallbackApiToken: String,
        fallbackEmail: String,
    ): PendingLegacyCheckoutPrefs.PendingCheckout? {
        val trimmedBody = body.trim()
        if (trimmedBody.startsWith("{")) {
            val json = JSONObject(trimmedBody)
            val invoiceUrl = json.optString("invoice_url").trim()
            if (invoiceUrl.isNotBlank()) {
                return PendingLegacyCheckoutPrefs.PendingCheckout(
                    invoiceUrl = invoiceUrl,
                    statusUrl = json.optString("status_url").trim().ifBlank {
                        buildLegacyStatusUrl(baseUrl, fallbackApiToken, fallbackPlan)
                    },
                    subscriptionId = json.optString("subscription_id").trim().ifBlank {
                        "legacy_checkout_pending"
                    },
                    plan = json.optString("plan").trim().ifBlank { fallbackPlan },
                    apiToken = json.optString("api_token").trim().ifBlank { fallbackApiToken },
                    email = json.optString("email").trim().ifBlank { fallbackEmail },
                )
            }
        }

        val invoiceUrl = extractLegacyInvoiceUrl(body) ?: return null
        return PendingLegacyCheckoutPrefs.PendingCheckout(
            invoiceUrl = invoiceUrl,
            statusUrl = buildLegacyStatusUrl(baseUrl, fallbackApiToken, fallbackPlan),
            subscriptionId = "legacy_checkout_pending",
            plan = fallbackPlan,
            apiToken = fallbackApiToken,
            email = fallbackEmail,
        )
    }

    private fun extractLegacyInvoiceUrl(html: String): String? {
        val anchorTag = Regex("""<a\b[^>]*\bid=["']open-checkout["'][^>]*>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.value
            ?: return null
        return Regex("""\bhref=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(anchorTag)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun showLegacyCheckoutIntroDialog(session: PendingLegacyCheckoutPrefs.PendingCheckout) {
        AlertDialog.Builder(this)
            .setTitle(R.string.legacy_checkout_dialog_title)
            .setMessage(getString(R.string.legacy_checkout_dialog_message))
            .setPositiveButton(R.string.legacy_checkout_open_button) { _, _ ->
                PendingLegacyCheckoutPrefs.setAwaitingReturn(this, true)
                openExternalUrl(session.invoiceUrl)
            }
            .setNegativeButton("Cancel") { _, _ ->
                PendingLegacyCheckoutPrefs.clear(this)
            }
            .show()
    }

    private fun maybePromptPendingLegacyCheckout() {
        if (legacyReturnDialogVisible) return
        if (!PendingLegacyCheckoutPrefs.isAwaitingReturn(this)) return
        val session = PendingLegacyCheckoutPrefs.get(this) ?: run {
            PendingLegacyCheckoutPrefs.clear(this)
            return
        }

        legacyReturnDialogVisible = true
        PendingLegacyCheckoutPrefs.setAwaitingReturn(this, false)
        AlertDialog.Builder(this)
            .setTitle(R.string.legacy_checkout_return_title)
            .setMessage(R.string.legacy_checkout_return_message)
            .setPositiveButton(R.string.legacy_checkout_confirm_button) { _, _ ->
                legacyReturnDialogVisible = false
                verifyPendingLegacyCheckout(session)
            }
            .setNeutralButton(R.string.legacy_checkout_reopen_button) { _, _ ->
                legacyReturnDialogVisible = false
                PendingLegacyCheckoutPrefs.setAwaitingReturn(this, true)
                openExternalUrl(session.invoiceUrl)
            }
            .setNegativeButton("Cancel") { _, _ ->
                legacyReturnDialogVisible = false
                PendingLegacyCheckoutPrefs.clear(this)
            }
            .setOnDismissListener {
                legacyReturnDialogVisible = false
            }
            .show()
    }

    private fun verifyPendingLegacyCheckout(session: PendingLegacyCheckoutPrefs.PendingCheckout) {
        Toast.makeText(this, "Checking payment confirmation...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val conn = URL(session.statusUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Accept", "application/json")
                val responseCode = conn.responseCode
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" }
                val json = JSONObject(body)
                if (responseCode !in 200..299) {
                    throw IllegalStateException(json.optString("message", "Unable to verify the subscription yet."))
                }

                val active = json.optBoolean("active", false)
                val state = json.optString("state", if (active) "active" else "pending")
                val message = json.optString("message", if (active) "Subscription confirmed." else "Payment pending.")
                val expiresAtMs = json.optLong("expires_at_ms", 0L)

                if (active) {
                    PendingLegacyCheckoutPrefs.clear(this)
                    val callbackUri = Uri.Builder()
                        .scheme("fersaiyan")
                        .authority("pro-sub")
                        .appendPath("callback")
                        .appendQueryParameter("status", "success")
                        .appendQueryParameter("plan", session.plan)
                        .appendQueryParameter("token", session.subscriptionId)
                        .appendQueryParameter("expires_at_ms", expiresAtMs.toString())
                        .appendQueryParameter("api_token", session.apiToken)
                        .appendQueryParameter("email", session.email)
                        .appendQueryParameter("message", message)
                        .build()
                    runOnUiThread {
                        startActivity(Intent(this, WebSubscriptionCallbackActivity::class.java).apply {
                            data = callbackUri
                        })
                    }
                    return@thread
                }

                if (state == "pending") {
                    PendingLegacyCheckoutPrefs.setAwaitingReturn(this, true)
                } else {
                    PendingLegacyCheckoutPrefs.clear(this)
                }

                runOnUiThread {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            } catch (error: Throwable) {
                PendingLegacyCheckoutPrefs.setAwaitingReturn(this, true)
                runOnUiThread {
                    Toast.makeText(this, "Unable to verify the subscription yet: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (intent.resolveActivityInfo(packageManager, 0) == null) {
                Toast.makeText(this, "No browser found to open checkout.", Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(intent)
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
        // Plan labels are set in XML; web checkout will override with real pricing
        // when Stripe is fully configured. For now, show the base labels.
    }

    private fun refreshPurchaseStatusFromStore() {
        billing?.queryActivePurchases { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases)
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
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
                applyLocalSubscription(plan, active.purchaseToken, source = "play_billing")
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
        val shouldClear = provider == "play_billing" || provider == "debug_mock" || provider == "server_verified" || provider == "verification_required"
        if (!shouldClear) return
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
            "play_billing", "debug_mock", "server_verified", "verification_required" -> true
            else -> false
        }
    }

    private fun applyLocalSubscription(plan: String, purchaseToken: String, source: String) {
        val now = System.currentTimeMillis()
        val expiresAt = when (plan) {
            "max" -> now + 365L * 24L * 60L * 60L * 1000L
            "free_trial" -> now + 30L * 24L * 60L * 60L * 1000L
            else -> now + 31L * 24L * 60L * 60L * 1000L
        }

        ProSubscriptionPrefs.setPlan(this, plan)
        ProSubscriptionPrefs.setSubscribed(this, true)
        ProSubscriptionPrefs.setExpiresAt(this, expiresAt)
        ProSubscriptionPrefs.setPurchaseToken(this, purchaseToken)
        ProSubscriptionPrefs.setProvider(this, source)
        ProSubscriptionPrefs.setLastVerifiedAt(this, now)
        val routeAction = ProSubscriptionRoutingPolicy.applyAfterActivation(this)
        val routeNote = ProSubscriptionRoutingPolicy.actionNote(routeAction)

        val planName = when (plan) {
            "free_trial" -> "Free Trial (30 days)"
            "cheap" -> "Cheap ($1/mo)"
            "standard" -> "Standard ($5/mo)"
            "max" -> "Max ($20/mo)"
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
    }

    private fun showStripeDonationDialog() {
        val amounts = arrayOf("$2", "$5", "$10", "$20")
        AlertDialog.Builder(this)
            .setTitle("Select Donation Amount")
            .setItems(amounts) { _, which ->
                val amount = amounts[which]
                openStripeDonation(amount)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openStripeDonation(amount: String) {
        try {
            // Stripe payment link - user needs to configure their own Stripe account
            // Format: https://buy.stripe.com/your_payment_link_id
            // Replace this with your actual Stripe payment link after creating it in Stripe dashboard
            val stripePaymentLink = "https://buy.stripe.com/test_donation_link"
            val amountCents = when (amount) {
                "$2" -> "200"
                "$5" -> "500"
                "$10" -> "1000"
                "$20" -> "2000"
                else -> "500"
            }
            val stripeUrl = "$stripePaymentLink?amount=$amountCents"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(stripeUrl)))
            Toast.makeText(this, "Opening Stripe donation...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open donation page. Stripe payment link not configured.", Toast.LENGTH_LONG).show()
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
        Toast.makeText(this, "Opening subscription management...", Toast.LENGTH_SHORT).show()
        thread {
            val apiToken = runCatching {
                ProSubscriptionServerPrefs.getApiToken(this).trim().ifBlank {
                    ProSubscriptionRelayClient.fetchAccountInfo(this).getOrThrow().apiToken.trim()
                }
            }.getOrDefault("")

            val relayBase = AiProviderPrefs.getRelayBaseUrl(this).trim().trimEnd('/')
            val manageUrl = Uri.parse("$relayBase/web-subscribe/cancel").buildUpon().apply {
                if (apiToken.isNotBlank()) {
                    appendQueryParameter("api_token", apiToken)
                }
            }.build().toString()

            runOnUiThread {
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

    companion object {
        const val EXTRA_CALLBACK_MESSAGE = "extra_callback_message"
        const val EXTRA_FROM_WEB_CALLBACK = "extra_from_web_callback"
        const val EXTRA_INITIAL_PLAN = "extra_initial_plan"
        const val EXTRA_AUTO_START_WEB_CHECKOUT = "extra_auto_start_web_checkout"
        const val EXTRA_AUTO_WEB_CHECKOUT_CHANGE_PLAN = "extra_auto_web_checkout_change_plan"
        const val EXTRA_AUTO_WEB_CHECKOUT_EMAIL = "extra_auto_web_checkout_email"
    }
}
