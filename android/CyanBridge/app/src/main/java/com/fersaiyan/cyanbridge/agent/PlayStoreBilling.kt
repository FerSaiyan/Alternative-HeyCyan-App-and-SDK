package com.fersaiyan.cyanbridge.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.fersaiyan.cyanbridge.shared.billing.ProStoreBilling
import com.fersaiyan.cyanbridge.shared.billing.ProStorePurchase
import java.security.MessageDigest

/** Google Play subscriptions for the shared Pro checkout. */
class PlayStoreBilling(context: Context) : ProStoreBilling {
    private val context = context.applicationContext
    private var manager: PlayBillingManager? = null
    @Volatile private var playProducts: Map<String, ProductDetails> = emptyMap()

    override fun start(listener: ProStoreBilling.Listener) {
        manager = PlayBillingManager(
            context = context,
            onPurchasesUpdated = { listener.onPurchasesUpdated(it.map(::toStorePurchase)) },
            onError = listener::onError,
        ).also { it.start(listener::onReady) }
    }

    override fun queryOffers(onResult: (Map<String, String>, Set<String>) -> Unit) {
        val productIds = PlaySubscriptionCatalog.allProductIds()
        if (productIds.isEmpty()) {
            playProducts = emptyMap()
            onResult(emptyMap(), emptySet())
            return
        }
        manager?.querySubscriptionProducts(productIds) { details ->
            playProducts = details
            val plans = listOf("cheap", "standard", "max")
            val prices = plans.mapNotNull { plan ->
                val offer = PlaySubscriptionCatalog.offerForPlan(plan) ?: return@mapNotNull null
                val product = details[offer.productId] ?: return@mapNotNull null
                PlayBillingManager.localizedOfferDescription(product, offer)?.let { plan to it }
            }.toMap()
            val purchasable = plans.filter { plan ->
                val offer = PlaySubscriptionCatalog.offerForPlan(plan)
                val product = offer?.let { details[it.productId] }
                offer != null && product != null && PlaySubscriptionCatalog.configuredOffer(product, offer) != null
            }.toSet()
            onResult(prices, purchasable)
        }
    }

    override fun queryActivePurchases(onResult: (List<ProStorePurchase>) -> Unit) {
        manager?.queryActivePurchases { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) onResult(purchases.map(::toStorePurchase))
        }
    }

    override fun acknowledge(purchase: ProStorePurchase) {
        (purchase.handle as? Purchase)?.let { manager?.acknowledgeIfNeeded(it) }
    }

    override fun canPurchase(plan: String): Boolean {
        val offer = PlaySubscriptionCatalog.offerForPlan(plan) ?: return false
        return playProducts[offer.productId] != null
    }

    override fun launchPurchase(host: Any, plan: String) {
        val offer = PlaySubscriptionCatalog.offerForPlan(plan) ?: return
        val product = playProducts[offer.productId] ?: return
        manager?.launchSubscriptionPurchase(
            activity = host as Activity,
            productDetails = product,
            offer = offer,
            obfuscatedAccountId = playBillingAccountId(),
        )
    }

    override fun destroy() {
        manager?.destroy()
    }

    private fun playBillingAccountId(): String? {
        val apiToken = ProSubscriptionServerPrefs.getApiToken(context).trim()
        if (apiToken.isBlank()) return null
        return MessageDigest.getInstance("SHA-256")
            .digest(apiToken.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun toStorePurchase(purchase: Purchase) = ProStorePurchase(
        purchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED,
        plan = purchase.products.firstNotNullOfOrNull { PlaySubscriptionCatalog.planForProductId(it).ifBlank { null } }.orEmpty(),
        purchaseToken = purchase.purchaseToken,
        handle = purchase,
    )
}

/** Opens Google Play's subscription page for the current plan. */
internal fun Activity.openPlaySubscriptionManagement() {
    val productId = PlaySubscriptionCatalog.productIdForPlan(ProSubscriptionPrefs.getPlan(this))
    val uri = Uri.parse("https://play.google.com/store/account/subscriptions").buildUpon().apply {
        appendQueryParameter("package", packageName)
        if (productId.isNotBlank()) appendQueryParameter("sku", productId)
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
