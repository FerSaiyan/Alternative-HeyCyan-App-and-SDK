package com.fersaiyan.cyanbridge.agent

import android.content.Context

object PendingDonationPrefs {
    private const val PREFS_NAME = "pending_donation_prefs"
    private const val KEY_INVOICE_URL = "invoice_url"
    private const val KEY_STATUS_URL = "status_url"
    private const val KEY_SUBSCRIPTION_ID = "subscription_id"
    private const val KEY_AMOUNT = "amount"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_EMAIL = "email"
    private const val KEY_AWAITING_RETURN = "awaiting_return"

    data class PendingDonation(
        val invoiceUrl: String,
        val statusUrl: String,
        val subscriptionId: String,
        val amount: String,
        val apiToken: String,
        val email: String,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, pending: PendingDonation) {
        prefs(context).edit()
            .putString(KEY_INVOICE_URL, pending.invoiceUrl)
            .putString(KEY_STATUS_URL, pending.statusUrl)
            .putString(KEY_SUBSCRIPTION_ID, pending.subscriptionId)
            .putString(KEY_AMOUNT, pending.amount)
            .putString(KEY_API_TOKEN, pending.apiToken)
            .putString(KEY_EMAIL, pending.email)
            .apply()
    }

    fun get(context: Context): PendingDonation? {
        val p = prefs(context)
        val invoiceUrl = p.getString(KEY_INVOICE_URL, "").orEmpty().trim()
        val statusUrl = p.getString(KEY_STATUS_URL, "").orEmpty().trim()
        val subscriptionId = p.getString(KEY_SUBSCRIPTION_ID, "").orEmpty().trim()
        val amount = p.getString(KEY_AMOUNT, "").orEmpty().trim()
        val apiToken = p.getString(KEY_API_TOKEN, "").orEmpty().trim()
        val email = p.getString(KEY_EMAIL, "").orEmpty().trim()
        if (invoiceUrl.isBlank() || statusUrl.isBlank() || subscriptionId.isBlank()) return null
        return PendingDonation(
            invoiceUrl = invoiceUrl,
            statusUrl = statusUrl,
            subscriptionId = subscriptionId,
            amount = amount,
            apiToken = apiToken,
            email = email,
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun setAwaitingReturn(context: Context, awaiting: Boolean) {
        prefs(context).edit().putBoolean(KEY_AWAITING_RETURN, awaiting).apply()
    }

    fun isAwaitingReturn(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AWAITING_RETURN, false)
    }
}
