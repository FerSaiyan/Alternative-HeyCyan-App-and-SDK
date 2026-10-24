package com.fersaiyan.cyanbridge.shared.billing

enum class BillingProvider(val wireName: String) {
    ASAAS("asaas"),
    PADDLE("paddle"),
    GOOGLE_PLAY("google_play"),
}

data class ProviderOffer(
    val provider: BillingProvider,
    val referencePriceUsd: Double,
    val currency: String,
    val adjustmentUsd: Double = 0.0,
    /** Currency required by the provider's checkout API. */
    val chargeCurrency: String = currency,
)

data class BillingPlan(
    val id: String,
    val name: String,
    val asaasOffer: ProviderOffer,
    val paddleOffer: ProviderOffer,
)

object BillingCatalog {
    val plans = listOf(
        paidPlan("cheap", "Cheap", 1.0, 1.55),
        paidPlan("standard", "Standard", 5.0, 5.75),
        paidPlan("max", "Max", 20.0, 21.50),
    )

    fun plan(id: String): BillingPlan = plans.firstOrNull { it.id == id } ?: plans[1]

    // Google Play prices are intentionally omitted here. They come from ProductDetails at
    // runtime so the app can show the selected offer's localized price and recurrence.
    private fun paidPlan(id: String, name: String, basePriceUsd: Double, paddlePriceUsd: Double) = BillingPlan(
        id = id,
        name = name,
        asaasOffer = ProviderOffer(
            provider = BillingProvider.ASAAS,
            referencePriceUsd = basePriceUsd + (basePriceUsd * 0.03) + 0.10,
            currency = "USD",
            adjustmentUsd = (basePriceUsd * 0.03) + 0.10,
            chargeCurrency = "BRL",
        ),
        paddleOffer = ProviderOffer(
            provider = BillingProvider.PADDLE,
            referencePriceUsd = paddlePriceUsd,
            currency = "USD",
            adjustmentUsd = paddlePriceUsd - basePriceUsd,
            chargeCurrency = "USD",
        ),
    )
}
