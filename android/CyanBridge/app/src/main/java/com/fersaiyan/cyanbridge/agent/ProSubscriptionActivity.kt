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
            startSubscriptionFlow(selectedPlan())
        }

        btnSubscribeWeb.setOnClickListener {
            launchWebCheckout(selectedPlan())
        }

        btnCancel.setOnClickListener {
            finish()
        }

        setupBilling()
    }

    override fun onResume() {
        super.onResume()
        updateStatusDisplay()
        refreshPurchaseStatusFromStore()

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

    private fun activateFreeTrial() {
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
                val body = "{}"
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

    private fun launchWebCheckout(plan: String) {
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
            setText(ProSubscriptionServerPrefs.getAccountEmail(this@ProSubscriptionActivity))
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

    private fun startSubscriptionFlow(plan: String) {
        if (plan == "free_trial") {
            activateFreeTrial()
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
            launchWebCheckout(plan)
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

    private fun launchWebCheckoutWithEmail(plan: String, emailHint: String) {
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
            .apply {
                val apiToken = ProSubscriptionServerPrefs.getApiToken(this@ProSubscriptionActivity)
                if (apiToken.isNotBlank()) {
                    appendQueryParameter("api_token", apiToken)
                }
                val accountEmail = ProSubscriptionServerPrefs.getAccountEmail(this@ProSubscriptionActivity)
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

        runCatching {
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
    }
}
