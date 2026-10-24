package com.fersaiyan.cyanbridge.shared.billing

import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenEffect
import com.fersaiyan.cyanbridge.shared.viewmodel.ScreenToast
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ProSubscriptionViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val platform = FakeProAccountPlatform()
    private val billing = FakeStoreBilling()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(effects: MutableList<ScreenEffect>) =
        ProSubscriptionViewModel(platform, billing, dispatcher).also { vm ->
            backgroundScope.launch(dispatcher) { vm.effectFlow.toList(effects) }
        }

    @Test
    fun requestedPlanIsSelectedAndUnknownChoicesFallBackToTheTrial() = runTest(dispatcher) {
        val vm = viewModel(mutableListOf())

        assertTrue(vm.onCreate(ProSubscriptionLaunch(initialPlan = " MAX ")))
        assertEquals("max", vm.state.value.ui.selectedPlan)
        assertEquals("Not subscribed · No active subscription", vm.state.value.ui.status)

        vm.selectPlan("enterprise")
        assertEquals("free_trial", vm.state.value.ui.selectedPlan)
    }

    @Test
    fun storePurchaseActivatesThePlanAndOpensSettings() = runTest(dispatcher) {
        val effects = mutableListOf<ScreenEffect>()
        val vm = viewModel(effects)
        vm.onCreate(ProSubscriptionLaunch())
        billing.listener.onReady()
        billing.listener.onPurchasesUpdated(listOf(ProStorePurchase(purchased = true, plan = "standard", purchaseToken = "tok")))

        assertEquals(listOf("standard", "play_billing", "tok"), listOf(platform.entitlement.plan, platform.entitlement.provider, platform.entitlement.purchaseToken))
        assertTrue(platform.entitlement.isSubscribed)
        assertEquals(1, billing.acknowledged)
        assertTrue(ScreenToast("✓ Subscribed to Standard ($5.00 per month) · Switched provider to Pro Subscription", long = true) in effects)
        assertEquals(listOf(ReportActivated, OpenProSettings(deferred = false)), effects.takeLast(2))
    }

    @Test
    fun missingStorePurchaseClearsOnlyAPlayEntitlement() = runTest(dispatcher) {
        val effects = mutableListOf<ScreenEffect>()
        platform.entitlement.apply { isSubscribed = true; provider = "play_billing"; expiresAt = 1L }
        val vm = viewModel(effects)
        vm.onCreate(ProSubscriptionLaunch())
        billing.listener.onReady()

        assertFalse(platform.entitlement.isSubscribed)
        assertEquals("play_purchase_missing", platform.entitlement.provider)
        assertTrue(ScreenToast("No active Play subscription found for this account.", long = true) in effects)
    }

    @Test
    fun restoreWithoutSubscriptionOffersToSendLogs() = runTest(dispatcher) {
        val effects = mutableListOf<ScreenEffect>()
        val vm = viewModel(effects)
        vm.onCreate(ProSubscriptionLaunch())
        platform.verifiedEmail = "me@example.com"

        vm.restoreExistingSubscription()
        val prompt = effects.filterIsInstance<PromptAccountEmail>().single()
        assertEquals("Restore Pro access", prompt.title)
        prompt.onAccepted(prompt.accept(" ME@example.com ")!!)

        assertEquals("me@example.com", vm.state.value.restoreNotFoundEmail)
        assertEquals("Email verified, but no active Pro subscription was found for this account.", vm.state.value.ui.status)
        vm.sendRestoreFailureLogs()
        assertEquals(null, vm.state.value.restoreNotFoundEmail)
        assertTrue(ScreenToast("Logs sent to the developer. Reference: log-1", long = true) in effects)
    }
}

private class FakeEntitlement : ProEntitlementStore {
    override var isSubscribed = false
    override var plan = "none"
    override var expiresAt = 0L
    override var purchaseToken = ""
    override var provider = "mock"
    override var lastVerifiedAt = 0L

    override fun clear(provider: String, clearPurchaseToken: Boolean) {
        isSubscribed = false
        plan = "none"
        expiresAt = 0L
        this.provider = provider
        if (clearPurchaseToken) purchaseToken = ""
    }
}

private class FakeStoreBilling : ProStoreBilling {
    lateinit var listener: ProStoreBilling.Listener
    var purchases = emptyList<ProStorePurchase>()
    var acknowledged = 0

    override fun start(listener: ProStoreBilling.Listener) {
        this.listener = listener
    }

    override fun queryOffers(onResult: (Map<String, String>, Set<String>) -> Unit) =
        onResult(mapOf("standard" to "$5.00 per month"), setOf("standard"))

    override fun queryActivePurchases(onResult: (List<ProStorePurchase>) -> Unit) = onResult(purchases)
    override fun acknowledge(purchase: ProStorePurchase) {
        acknowledged++
    }

    override fun canPurchase(plan: String) = plan == "standard"
    override fun launchPurchase(host: Any, plan: String) = Unit
    override fun destroy() = Unit
}

private class FakeProAccountPlatform : ProAccountPlatform {
    override val entitlement = FakeEntitlement()
    var verifiedEmail = ""
    private var email = ""

    override fun localStatus() = ProEntitlementStatus(
        active = entitlement.isActiveLocally(),
        plan = entitlement.plan,
        expiresAtMs = entitlement.expiresAt,
        message = if (entitlement.isSubscribed) "Subscription active (local status)" else "No active subscription",
    )

    override fun verifyNow(strict: Boolean, applyActivationRouting: Boolean) = localStatus()
    override fun forceStrictVerification() = false
    override fun applyActivationRouting() = "Switched provider to Pro Subscription"
    override fun hasVerifyUrl() = false
    override fun isWebCheckoutEnabled() = true
    override fun relayConfigured() = true
    override fun relayUnavailableHint(error: Throwable): String? = null
    override fun accountEmail() = email
    override fun setAccountEmail(email: String) {
        this.email = email
    }

    override fun normalizeAccountEmail(raw: String) = raw.trim().lowercase()
    override fun isValidAccountEmail(email: String) = "@" in email
    override fun isAccountEmailVerified(email: String) = email == verifiedEmail
    override fun setVerifiedAccountEmail(email: String) {
        verifiedEmail = email
    }

    override fun apiToken() = ""
    override fun fetchAccount() = Result.success(ProAccount("token", verifiedEmail, true, "none", "inactive"))
    override fun requestEmailVerification(email: String) = Result.success(ProEmailVerification(false, "Code sent", null))
    override fun verifyEmailCode(code: String) = Result.success(Unit)
    override fun cancelSubscription() = Result.success(ProCancelResult(false, "none", 0L, "Cancelled"))
    override fun createWebCheckout(plan: String, provider: BillingProvider, changePlan: Boolean) = Result.success("https://pay")
    override fun activateTrial(email: String) = ProTrialActivation(false, "No trial", 0L, "free_trial")
    override suspend fun sendRestoreFailureLogs(email: String) = Result.success("log-1")
    override fun createDonation(amount: String, email: String) = ProDonation("https://invoice", "https://status", amount)
    override fun checkDonation(donation: ProDonation) = ProDonationStatus(false, "Donation pending.")
    override fun pendingDonation(): ProDonation? = null
    override fun isAwaitingDonationReturn() = false
    override fun setAwaitingDonationReturn(awaiting: Boolean) = Unit
    override fun clearDonation() = Unit
    override fun fetchQuota(model: String) = Result.success(ProQuota(0, 0, 0, 0L, model))
    override fun fetchModelCatalog() = Result.success(ProModelCatalog(emptyList(), emptyList()))
    override fun registerBetaCloudInterest() = Result.success(ProBetaSignup("Thanks", null))
}
