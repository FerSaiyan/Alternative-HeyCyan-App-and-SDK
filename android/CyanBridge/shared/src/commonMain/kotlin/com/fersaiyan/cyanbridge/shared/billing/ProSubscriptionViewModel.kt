package com.fersaiyan.cyanbridge.shared.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.shared.platform.platformCurrentTimeMillis
import com.fersaiyan.cyanbridge.shared.viewmodel.DialogButton
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenDialog
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffect
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffects
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProSubscriptionScreenState(
    val ui: ProSubscriptionUiState = ProSubscriptionUiState(),
    val restoreNotFoundEmail: String? = null,
    val restoreLogsSending: Boolean = false,
    val emailCode: EmailCodeDialogState? = null,
)

data class EmailCodeDialogState(
    val email: String,
    val initialMessage: String,
    val verificationUrl: String?,
    val verifying: Boolean = false,
    val error: String? = null,
)

/** What the screen was opened with. */
data class ProSubscriptionLaunch(
    val callbackMessage: String? = null,
    val initialPlan: String? = null,
    val changePlan: Boolean = false,
    val emailVerificationReturn: Boolean = false,
)

/** Asks for the account email; [accept] validates and saves it, then the host closes the prompt and calls [onAccepted]. */
data class PromptAccountEmail(
    val title: String,
    val message: String,
    val initialEmail: String,
    val accept: (String) -> String?,
    val onAccepted: (String) -> Unit,
) : ScreenEffect

data object PlayClickSound : ScreenEffect

/** Opens Pro settings: right away, or posted after the current frame when [deferred]. */
data class OpenProSettings(val deferred: Boolean) : ScreenEffect
data object ReportActivated : ScreenEffect
data class OpenCheckoutUrl(val url: String) : ScreenEffect
data class OpenEmailVerificationLink(val url: String) : ScreenEffect
data object OpenStoreSubscriptions : ScreenEffect
data class LaunchStorePurchase(val plan: String) : ScreenEffect
data class ShowDonationIntro(val onOpen: () -> Unit, val onCancel: () -> Unit) : ScreenEffect

/**
 * Pro checkout: plan choice, account email verification, free trial, store and website
 * checkout, restoring an existing subscription, donations and cancellation.
 */
class ProSubscriptionViewModel(
    private val platform: ProAccountPlatform,
    val billing: ProStoreBilling?,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    private val _state = MutableStateFlow(ProSubscriptionScreenState())
    val state: StateFlow<ProSubscriptionScreenState> = _state.asStateFlow()
    private val effects = ScreenEffects()
    val effectFlow get() = effects.flow

    private val entitlement get() = platform.entitlement

    /** Status line; starts as the text the screen showed before any refresh. */
    private var statusText = "Not subscribed"

    /** Plan picked on the screen (`free_trial`, `cheap`, `standard` or `max`); null until [onCreate]. */
    var checkedPlan: String? = null
        private set
    private var lastBillingError = ""
    private var changePlanRequested = false
    private var pendingEmailVerification = ""
    private var emailVerificationRefreshInFlight = false
    private var restoreExistingSubscriptionPending = false
    private var billingStarted = false
    private var onEmailCodeVerified: () -> Unit = {}

    private var ui: ProSubscriptionUiState
        get() = _state.value.ui
        set(value) = _state.update { it.copy(ui = value) }

    private fun toast(text: String, long: Boolean = false) = effects.toast(text, long)

    /** Runs on every screen creation; returns false when the screen redirects to Pro settings instead. */
    fun onCreate(launch: ProSubscriptionLaunch): Boolean {
        updateStatusDisplay()
        ui = ui.copy(webCheckoutAvailable = platform.isWebCheckoutEnabled())
        showCallbackMessage(launch.callbackMessage)

        if (checkedPlan == null) {
            checkedPlan = "free_trial"
            val requested = launch.initialPlan?.trim()?.lowercase()
            if (requested in setOf("free_trial", "cheap", "max", "standard")) checkedPlan = requested
        }
        changePlanRequested = launch.changePlan
        ui = ui.copy(googlePlayCheckoutAllowed = isGooglePlayCheckoutAllowed())

        if (entitlement.provider == "debug_mock") {
            entitlement.clear(provider = "debug_mock_cleared", clearPurchaseToken = true)
            toast("Cleared old debug mock entitlement")
            updateStatusDisplay()
        }

        // If the user already has an active plan, guide them to settings instead of repurchasing.
        if (redirectToSettingsIfActive(showToast = false)) return false

        startBilling()
        refreshComposeState()
        if (launch.emailVerificationReturn) prepareEmailVerificationReturn()
        return true
    }

    fun onResume() {
        updateStatusDisplay()
        refreshPurchaseStatusFromStore()
        maybePromptPendingDonation()
        refreshPendingEmailVerification()

        // If verification just completed and the user now has an active plan, send to settings.
        if (redirectToSettingsIfActive(showToast = false)) return

        val shouldVerifyServerState = entitlement.isSubscribed ||
            (entitlement.provider != "play_billing" && platform.apiToken().isNotBlank())
        if (shouldVerifyServerState) {
            viewModelScope.launch {
                val verified = withContext(io) { platform.verifyNow(strict = platform.forceStrictVerification()) }
                updateStatusDisplay()
                // After server sync, if now active and not in change-plan mode, open settings.
                if (verified.active) redirectToSettingsIfActive(showToast = true)
            }
        }
    }

    fun onNewIntent(callbackMessage: String?, emailVerificationReturn: Boolean) {
        showCallbackMessage(callbackMessage)
        if (emailVerificationReturn) prepareEmailVerificationReturn()
        refreshPendingEmailVerification()
    }

    /** The plan the screen kept across recreation. */
    fun restoreCheckedPlan(plan: String) {
        checkedPlan = plan
    }

    override fun onCleared() {
        billing?.destroy()
    }

    // ── Screen actions ──

    fun selectPlan(plan: String) {
        checkedPlan = when (plan) {
            "cheap", "standard", "max" -> plan
            else -> "free_trial"
        }
        refreshComposeState()
    }

    /** "Start free trial" (also the subscribe action for paid plans). */
    fun subscribeToSelectedPlan() {
        if (redirectToSettingsIfActive(showToast = true)) return
        val plan = selectedPlan()
        promptForSubscriptionEmail(plan) { email ->
            if (plan == "free_trial") activateFreeTrial(email) else startStoreSubscriptionFlow(plan)
        }
    }

    fun subscribeWithStore() {
        val plan = selectedPlan()
        promptForSubscriptionEmail(plan) { startStoreSubscriptionFlow(plan) }
    }

    fun subscribeOnWebsite(provider: BillingProvider) {
        val plan = selectedPlan()
        if (plan == "free_trial") {
            effects.send(PlayClickSound)
            subscribeToSelectedPlan()
            return
        }
        val changePlan = changePlanRequested
        promptForSubscriptionEmail(plan) { launchWebCheckoutWithEmail(plan, provider, changePlan) }
    }

    fun showCheckoutUnavailable() {
        toast(withBillingError("No checkout is available for this plan right now."), long = true)
    }

    fun restoreExistingSubscription() {
        if (redirectToSettingsIfActive(showToast = true)) return
        restoreExistingSubscriptionPending = true
        promptForAccountEmail(
            title = "Restore Pro access",
            message = "Enter the email used for your existing Pro subscription. We will verify it and link this app to that account without starting a checkout.",
        ) { email -> verifyRestoredProAccount(email) }
    }

    fun dismissRestoreNotFound() = _state.update { it.copy(restoreNotFoundEmail = null) }

    fun sendRestoreFailureLogs() {
        val email = _state.value.restoreNotFoundEmail ?: return
        if (_state.value.restoreLogsSending) return
        _state.update { it.copy(restoreLogsSending = true) }
        viewModelScope.launch {
            val result = withContext(io) { platform.sendRestoreFailureLogs(email) }
            _state.update { it.copy(restoreLogsSending = false) }
            result.onSuccess { logId ->
                _state.update { it.copy(restoreNotFoundEmail = null) }
                toast("Logs sent to the developer. Reference: $logId", long = true)
            }.onFailure { error ->
                toast(platform.relayUnavailableHint(error) ?: error.message ?: "Unable to send logs.", long = true)
            }
        }
    }

    fun donate() {
        val amounts = listOf("$3", "$5", "$10")
        effects.send(
            ScreenDialog(
                title = "Select Donation Amount",
                items = amounts,
                onItem = { which -> promptForDonationEmail(amounts[which].removePrefix("$")) },
                negative = DialogButton("Cancel"),
            ),
        )
    }

    fun cancelSubscription() {
        if (entitlement.provider == "play_billing") {
            effects.send(OpenStoreSubscriptions)
            return
        }
        effects.send(
            ScreenDialog(
                title = "Cancel Subscription?",
                message = "Are you sure you want to cancel your subscription? You'll keep access until the end of your current billing period.",
                positive = DialogButton("Yes, Cancel") { performUnsubscribe() },
                negative = DialogButton("No, Keep It"),
            ),
        )
    }

    // ── Email code dialog ──

    fun onEmailCodeChanged() = updateEmailCode { it.copy(error = null) }

    fun verifyEmailCode(code: String) {
        val request = _state.value.emailCode ?: return
        if (request.verifying) return
        updateEmailCode { it.copy(verifying = true, error = null) }
        toast("Verifying code...")
        val onVerified = onEmailCodeVerified
        viewModelScope.launch {
            val result = withContext(io) { platform.verifyEmailCode(code) }
            result.onSuccess {
                _state.update { it.copy(emailCode = null) }
                pendingEmailVerification = ""
                platform.setVerifiedAccountEmail(request.email)
                // If verification also synced an active subscription, send to settings.
                val serverState = withContext(io) { platform.verifyNow(strict = platform.forceStrictVerification()) }
                if (serverState.active || entitlement.isActiveLocally()) {
                    toast("Email verified. Your ${serverState.plan} plan is already active.", long = true)
                    redirectToSettingsIfActive(showToast = false)
                    if (!entitlement.isActiveLocally() || changePlanRequested) onVerified()
                } else {
                    toast("Email verified. Choose your plan to continue.", long = true)
                    onVerified()
                }
            }.onFailure { error ->
                val message = platform.relayUnavailableHint(error) ?: error.message ?: "Invalid code"
                updateEmailCode { it.copy(verifying = false, error = message) }
                toast(message, long = true)
            }
        }
    }

    fun resendEmailCode() {
        val request = _state.value.emailCode ?: return
        _state.update { it.copy(emailCode = null) }
        requestAccountEmailVerification(request.email, onEmailCodeVerified)
    }

    fun openEmailVerificationLink() {
        val url = _state.value.emailCode?.verificationUrl ?: return
        _state.update { it.copy(emailCode = null) }
        effects.send(OpenEmailVerificationLink(url))
    }

    fun dismissEmailCode() {
        if (_state.value.emailCode?.verifying != true) _state.update { it.copy(emailCode = null) }
    }

    private fun updateEmailCode(change: (EmailCodeDialogState) -> EmailCodeDialogState) =
        _state.update { state -> state.copy(emailCode = state.emailCode?.let(change)) }

    // ── Status ──

    private fun selectedPlan(): String = checkedPlan ?: "standard"

    private fun showCallbackMessage(message: String?) {
        val msg = message.orEmpty().trim()
        if (msg.isNotBlank()) toast(msg)
    }

    private fun refreshComposeState() {
        val status = platform.localStatus()
        ui = ui.copy(
            status = statusText.ifBlank { status.message },
            selectedPlan = selectedPlan(),
            webCheckoutAvailable = platform.isWebCheckoutEnabled(),
            isSubscribed = status.active,
            googlePlayCheckoutAllowed = isGooglePlayCheckoutAllowed(),
        )
    }

    private fun updateStatusDisplay() {
        val status = platform.localStatus()
        val plan = status.plan.ifBlank { "none" }
        statusText = if (status.active) {
            "✓ Active on $plan plan · source=${entitlement.provider}"
        } else {
            "Not subscribed · ${status.message}"
        }
        ui = ui.copy(
            status = statusText,
            selectedPlan = selectedPlan(),
            webCheckoutAvailable = platform.isWebCheckoutEnabled(),
            isSubscribed = status.active,
        )
    }

    private fun isGooglePlayCheckoutAllowed(): Boolean =
        !changePlanRequested || !entitlement.isSubscribed || entitlement.plan == "free_trial"

    private fun redirectToSettingsIfActive(showToast: Boolean): Boolean {
        if (changePlanRequested) return false
        if (!entitlement.isActiveLocally()) return false
        if (showToast) toast("You already have an active ${entitlement.plan} plan. Opening settings.")
        effects.send(OpenProSettings(deferred = true))
        return true
    }

    private fun openSettingsAfterSubscribe() = effects.send(OpenProSettings(deferred = false))

    private fun withBillingError(message: String) = buildString {
        append(message)
        if (lastBillingError.isNotBlank()) append(" (billing: $lastBillingError)")
    }

    // ── Account email ──

    private fun promptForSubscriptionEmail(plan: String, onConfirmed: (String) -> Unit) {
        val message = if (plan == "free_trial") {
            "Use a valid email so your free trial can be restored later on another device."
        } else {
            "Use the same email as your previous purchase so we can restore an active subscription instead of charging again."
        }
        promptForAccountEmail(title = "Account email", message = message, onConfirmed = onConfirmed)
    }

    private fun acceptAccountEmail(raw: String): String? {
        val email = platform.normalizeAccountEmail(raw)
        if (!platform.isValidAccountEmail(email)) return null
        platform.setAccountEmail(email)
        return email
    }

    private fun promptForAccountEmail(title: String, message: String, onConfirmed: (String) -> Unit) {
        effects.send(
            PromptAccountEmail(title, message, platform.accountEmail(), ::acceptAccountEmail) { email ->
                confirmAccountEmail(email) { onConfirmed(email) }
            },
        )
    }

    private fun confirmAccountEmail(email: String, onConfirmed: () -> Unit) {
        if (entitlement.isActiveLocally() && !changePlanRequested) {
            if (redirectToSettingsIfActive(showToast = true)) return
        }
        if (platform.isAccountEmailVerified(email)) {
            onConfirmed()
            return
        }
        toast("Checking account verification...")
        viewModelScope.launch {
            val accountResult = withContext(io) { platform.fetchAccount() }
            if (platform.isAccountEmailVerified(email)) {
                pendingEmailVerification = ""
                onConfirmed()
            } else if (accountResult.isFailure) {
                toast("Unable to check verification. Check your connection and try again.", long = true)
            } else if (pendingEmailVerification.equals(email, ignoreCase = true)) {
                toast("Verification is still being confirmed. Wait a moment, then try again.", long = true)
            } else {
                requestAccountEmailVerification(email, onConfirmed)
            }
        }
    }

    private fun requestAccountEmailVerification(email: String, onEmailMatchConfirmed: () -> Unit) {
        if (entitlement.isActiveLocally() && !changePlanRequested) {
            if (redirectToSettingsIfActive(showToast = true)) return
        }
        toast("Sending verification email...")
        viewModelScope.launch {
            val result = withContext(io) { platform.requestEmailVerification(email) }
            result.onSuccess { verification ->
                if (verification.emailMatched) {
                    pendingEmailVerification = ""
                    platform.setVerifiedAccountEmail(email)
                    toast(verification.message, long = true)
                    // If already active, don't re-trigger purchase; go to settings.
                    if (entitlement.isActiveLocally() && !changePlanRequested) {
                        if (redirectToSettingsIfActive(showToast = true)) return@onSuccess
                    }
                    onEmailMatchConfirmed()
                } else {
                    pendingEmailVerification = email
                    onEmailCodeVerified = onEmailMatchConfirmed
                    _state.update {
                        it.copy(emailCode = EmailCodeDialogState(email, verification.message, verification.verificationUrl))
                    }
                }
            }.onFailure { error ->
                toast("Unable to verify email: ${error.message}", long = true)
            }
        }
    }

    private fun prepareEmailVerificationReturn() {
        pendingEmailVerification = platform.accountEmail()
    }

    private fun refreshPendingEmailVerification() {
        val email = pendingEmailVerification
        if (email.isBlank() || emailVerificationRefreshInFlight || platform.isAccountEmailVerified(email)) return

        emailVerificationRefreshInFlight = true
        viewModelScope.launch {
            val lastResult = withContext(io) {
                var last: Result<ProAccount>? = null
                for (attempt in 0 until 3) {
                    last = platform.fetchAccount()
                    if (platform.isAccountEmailVerified(email)) break
                    if (attempt < 2) delay(750L)
                }
                last
            }
            emailVerificationRefreshInFlight = false
            if (platform.isAccountEmailVerified(email)) {
                pendingEmailVerification = ""
                if (restoreExistingSubscriptionPending) {
                    verifyRestoredProAccount(email)
                } else {
                    toast("Email verified. Choose your plan to continue.", long = true)
                }
            } else if (lastResult?.isFailure == true) {
                toast("Unable to confirm verification. Check your connection and try again.", long = true)
            } else {
                toast("Verification is not confirmed yet. Wait a moment, then try again.", long = true)
            }
        }
    }

    // ── Free trial and restore ──

    private fun activateFreeTrial(emailHint: String = "") {
        val finalEmail = platform.normalizeAccountEmail(emailHint.ifBlank { platform.accountEmail() })
        if (!platform.isValidAccountEmail(finalEmail)) {
            promptForAccountEmail(
                title = "Account email",
                message = "Use a valid email so your free trial can be restored later on another device.",
            ) { confirmedEmail -> activateFreeTrial(confirmedEmail) }
            return
        }

        platform.setAccountEmail(finalEmail)
        if (!platform.isAccountEmailVerified(finalEmail)) {
            confirmAccountEmail(finalEmail) { activateFreeTrial(finalEmail) }
            return
        }
        if (!platform.relayConfigured()) {
            toast("Server not configured.")
            return
        }
        toast("Activating free trial...")

        viewModelScope.launch {
            val result = withContext(io) { runCatching { platform.activateTrial(finalEmail) } }
            result.onSuccess { trial ->
                if (trial.ok && trial.expiresAtMs > platformCurrentTimeMillis()) {
                    entitlement.plan = trial.plan
                    entitlement.isSubscribed = true
                    entitlement.expiresAt = trial.expiresAtMs
                    entitlement.purchaseToken = "free_trial_${platformCurrentTimeMillis()}"
                    entitlement.provider = "server_verified"
                    entitlement.lastVerifiedAt = platformCurrentTimeMillis()
                    val routeNote = platform.applyActivationRouting()
                    val displayPlan = when (trial.plan) {
                        "free_trial" -> "Free Trial (30 days)"
                        "cheap" -> "Cheap"
                        "standard" -> "Standard"
                        "max" -> "Max"
                        else -> trial.plan
                    }
                    val note = if (routeNote.isBlank()) "" else " · $routeNote"
                    toast("✓ ${trial.message} — $displayPlan$note", long = true)
                    updateStatusDisplay()
                    effects.send(ReportActivated)
                    openSettingsAfterSubscribe()
                } else {
                    toast(trial.message, long = true)
                }
            }.onFailure { error ->
                toast("Free trial failed: ${error.message}", long = true)
            }
        }
    }

    private fun verifyRestoredProAccount(email: String) {
        toast("Checking for an active Pro subscription...")
        viewModelScope.launch {
            val result = withContext(io) {
                runCatching {
                    val account = platform.fetchAccount().getOrThrow()
                    check(account.emailVerified && account.email.equals(email, ignoreCase = true)) {
                        "The verified relay account does not match that email."
                    }
                    platform.verifyNow(strict = platform.forceStrictVerification(), applyActivationRouting = true)
                }
            }
            restoreExistingSubscriptionPending = false
            result.onSuccess { verified ->
                updateStatusDisplay()
                if (verified.active) {
                    effects.send(ReportActivated)
                    toast("Pro restored. Your ${verified.plan} plan is active.", long = true)
                    openSettingsAfterSubscribe()
                } else {
                    statusText = "Email verified, but no active Pro subscription was found for this account."
                    refreshComposeState()
                    _state.update { it.copy(restoreNotFoundEmail = email) }
                }
            }.onFailure { error ->
                val message = platform.relayUnavailableHint(error) ?: error.message ?: "Unable to restore Pro access."
                statusText = message
                refreshComposeState()
                toast(message, long = true)
            }
        }
    }

    // ── Checkout ──

    private fun startStoreSubscriptionFlow(plan: String) {
        if (!isGooglePlayCheckoutAllowed()) {
            toast("Change this web subscription through website checkout to avoid overlapping subscriptions.", long = true)
            return
        }
        if (billing?.canPurchase(plan) == true) {
            effects.send(LaunchStorePurchase(plan))
            return
        }
        toast(withBillingError("Google Play checkout is unavailable for this plan. Choose website checkout instead."), long = true)
    }

    private fun launchWebCheckoutWithEmail(plan: String, provider: BillingProvider, changePlan: Boolean) {
        val providerName = when (provider) {
            BillingProvider.ASAAS -> "Asaas"
            BillingProvider.PADDLE -> "Paddle"
            BillingProvider.GOOGLE_PLAY -> {
                toast("Google Play is not a website checkout provider.", long = true)
                return
            }
        }
        toast("Preparing $providerName checkout...")
        viewModelScope.launch {
            withContext(io) { platform.createWebCheckout(plan, provider, changePlan) }
                .onSuccess { checkoutUrl -> effects.send(OpenCheckoutUrl(checkoutUrl)) }
                .onFailure { error -> toast("Unable to prepare checkout: ${error.message}", long = true) }
        }
    }

    private fun performUnsubscribe() {
        toast("Cancelling subscription...")
        viewModelScope.launch {
            withContext(io) { platform.cancelSubscription() }
                .onSuccess { cancel ->
                    entitlement.applyCancellation(cancel)
                    updateStatusDisplay()
                    toast(cancel.message, long = true)
                }
                .onFailure { toast("Unable to cancel subscription: ${it.message}", long = true) }
        }
    }

    // ── Store billing ──

    private fun startBilling() {
        val store = billing ?: return
        if (billingStarted) return
        billingStarted = true
        store.start(object : ProStoreBilling.Listener {
            override fun onReady() {
                viewModelScope.launch {
                    refreshPurchaseStatusFromStore()
                    refreshStoreOffers()
                }
            }

            override fun onPurchasesUpdated(purchases: List<ProStorePurchase>) {
                viewModelScope.launch { handlePurchases(purchases, applyActivationRouting = true) }
            }

            override fun onError(message: String) {
                viewModelScope.launch {
                    lastBillingError = message
                    toast("Billing: $message")
                }
            }
        })
    }

    private fun refreshStoreOffers() {
        billing?.queryOffers { prices, purchasable ->
            viewModelScope.launch { ui = ui.copy(playPriceLabels = prices, playCheckoutAvailablePlans = purchasable) }
        }
    }

    private fun refreshPurchaseStatusFromStore() {
        if (!billingStarted) return
        billing?.queryActivePurchases { purchases ->
            viewModelScope.launch { handlePurchases(purchases, applyActivationRouting = false) }
        }
    }

    private fun handlePurchases(purchases: List<ProStorePurchase>, applyActivationRouting: Boolean) {
        val active = purchases.firstOrNull { it.purchased && it.plan.isNotBlank() }
        if (active == null) {
            clearStaleStoreEntitlement()
            return
        }
        billing?.acknowledge(active)
        applyStoreSubscription(active.plan, active.purchaseToken, applyActivationRouting)
        // Optional server verification path (if an endpoint is configured).
        viewModelScope.launch {
            withContext(io) { platform.verifyNow(strict = platform.forceStrictVerification()) }
            updateStatusDisplay()
        }
    }

    private fun clearStaleStoreEntitlement() {
        // Only Play entitlements: web and server-verified subscribers stay when Play returns nothing.
        if (entitlement.provider != "play_billing") return
        if (!entitlement.isSubscribed) return
        entitlement.clear(provider = "play_purchase_missing", clearPurchaseToken = false)
        toast("No active Play subscription found for this account.", long = true)
        updateStatusDisplay()
    }

    private fun applyStoreSubscription(plan: String, purchaseToken: String, applyActivationRouting: Boolean) {
        val now = platformCurrentTimeMillis()
        entitlement.plan = plan
        entitlement.isSubscribed = true
        entitlement.purchaseToken = purchaseToken
        entitlement.provider = "play_billing"

        // With a verifier configured, ask the backend for the real expiry right away.
        if (platform.hasVerifyUrl()) {
            viewModelScope.launch {
                withContext(io) { platform.verifyNow(strict = false) }
                updateStatusDisplay()
            }
        } else {
            entitlement.expiresAt = when (plan) {
                "max" -> now + 365L * DAY_MS
                "free_trial" -> now + 30L * DAY_MS
                else -> now + 31L * DAY_MS
            }
            entitlement.lastVerifiedAt = now
        }

        val routeNote = if (applyActivationRouting) platform.applyActivationRouting() else ""
        val price = ui.playPriceLabels[plan]?.let { " ($it)" }.orEmpty()
        val planName = when (plan) {
            "free_trial" -> "Free Trial (30 days)"
            "cheap" -> "Cheap$price"
            "standard" -> "Standard$price"
            "max" -> "Max$price"
            else -> plan
        }
        toast(if (routeNote.isBlank()) "✓ Subscribed to $planName" else "✓ Subscribed to $planName · $routeNote", long = true)
        updateStatusDisplay()
        effects.send(ReportActivated)
        openSettingsAfterSubscribe()
    }

    // ── Donations ──

    private fun promptForDonationEmail(amount: String) {
        effects.send(
            PromptAccountEmail(
                title = "Donate \$$amount via Asaas",
                message = "Enter your email to receive the receipt. We use Asaas (secure Brazilian processor) for one-time credit card payments.",
                initialEmail = platform.accountEmail(),
                accept = ::acceptAccountEmail,
                onAccepted = { email -> startDonation(amount, email) },
            ),
        )
    }

    private fun startDonation(amount: String, email: String) {
        if (!platform.relayConfigured()) {
            toast("Server not configured.")
            return
        }
        toast("Preparing donation checkout...")
        viewModelScope.launch {
            withContext(io) { runCatching { platform.createDonation(amount, email) } }
                .onSuccess { pending ->
                    effects.send(
                        ShowDonationIntro(
                            onOpen = {
                                platform.setAwaitingDonationReturn(true)
                                effects.send(OpenCheckoutUrl(pending.invoiceUrl))
                            },
                            onCancel = platform::clearDonation,
                        ),
                    )
                }
                .onFailure { error -> toast("Donation setup failed: ${error.message}", long = true) }
        }
    }

    private fun maybePromptPendingDonation() {
        if (!platform.isAwaitingDonationReturn()) return
        val pending = platform.pendingDonation() ?: run {
            platform.clearDonation()
            return
        }
        platform.setAwaitingDonationReturn(false)
        effects.send(
            ScreenDialog(
                title = "Donation Return",
                message = "Did you complete the donation of \$${pending.amount} in the Asaas card form?",
                positive = DialogButton("Yes, Check Status") { verifyPendingDonation(pending) },
                neutral = DialogButton("Open Card Form Again") {
                    platform.setAwaitingDonationReturn(true)
                    effects.send(OpenCheckoutUrl(pending.invoiceUrl))
                },
                negative = DialogButton("Cancel") { platform.clearDonation() },
            ),
        )
    }

    private fun verifyPendingDonation(pending: ProDonation) {
        toast("Checking donation confirmation...")
        viewModelScope.launch {
            val result = withContext(io) {
                runCatching { platform.checkDonation(pending) }.also { checked ->
                    if (checked.getOrNull()?.confirmed == true) platform.clearDonation()
                    else platform.setAwaitingDonationReturn(true)
                }
            }
            result.onSuccess { status ->
                if (status.confirmed) {
                    effects.send(
                        ScreenDialog(
                            title = "Thank You!",
                            message = "Your donation of \$${pending.amount} has been confirmed. Thank you for supporting CyanBridge!",
                            positive = DialogButton("Great!"),
                        ),
                    )
                } else {
                    toast(status.message, long = true)
                }
            }.onFailure { error ->
                toast("Unable to verify donation: ${error.message}", long = true)
            }
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
