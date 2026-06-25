package com.fersaiyan.cyanbridge.agent

import com.fersaiyan.cyanbridge.BuildConfig

object PlaySubscriptionCatalog {

    private data class Entry(
        val plan: String,
        val productId: String,
    )

    private val entries = listOf(
        Entry(plan = "cheap", productId = BuildConfig.PRO_CHEAP_SKU.trim()),
        Entry(plan = "standard", productId = BuildConfig.PRO_STANDARD_SKU.trim()),
        Entry(plan = "max", productId = BuildConfig.PRO_MAX_SKU.trim()),
    ).filter { it.productId.isNotBlank() }

    fun allProductIds(): List<String> =
        entries.map { it.productId }.distinct()

    fun productIdForPlan(plan: String): String =
        entries.firstOrNull { it.plan == plan }?.productId.orEmpty()

    fun planForProductId(productId: String): String =
        entries.firstOrNull { it.productId == productId.trim() }?.plan.orEmpty()
}
