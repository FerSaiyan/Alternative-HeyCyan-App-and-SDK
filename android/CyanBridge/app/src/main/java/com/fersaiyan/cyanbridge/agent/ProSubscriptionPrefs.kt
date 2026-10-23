package com.fersaiyan.cyanbridge.agent

import android.content.Context

object ProSubscriptionPrefs {
    private const val PREFS_NAME = "pro_subscription_prefs"
    private const val FREE_PRO_ACCESS_ENABLED = true
    private const val KEY_IS_SUBSCRIBED = "is_subscribed"
    private const val KEY_PLAN = "plan"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_PURCHASE_TOKEN = "purchase_token"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_LAST_VERIFIED_AT = "last_verified_at"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSubscribed(context: Context): Boolean = prefs(context).getBoolean(KEY_IS_SUBSCRIBED, false)

    fun setSubscribed(context: Context, subscribed: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_SUBSCRIBED, subscribed).apply()
    }

    fun getPlan(context: Context): String = prefs(context).getString(KEY_PLAN, null) ?: "none"

    fun setPlan(context: Context, plan: String) {
        prefs(context).edit().putString(KEY_PLAN, plan).apply()
    }

    fun getExpiresAt(context: Context): Long = prefs(context).getLong(KEY_EXPIRES_AT, 0L)

    fun setExpiresAt(context: Context, timestamp: Long) {
        prefs(context).edit().putLong(KEY_EXPIRES_AT, timestamp).apply()
    }

    fun getPurchaseToken(context: Context): String = prefs(context).getString(KEY_PURCHASE_TOKEN, null) ?: ""

    fun setPurchaseToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_PURCHASE_TOKEN, token).apply()
    }

    fun getProvider(context: Context): String = prefs(context).getString(KEY_PROVIDER, "mock") ?: "mock"

    fun setProvider(context: Context, provider: String) {
        prefs(context).edit().putString(KEY_PROVIDER, provider).apply()
    }

    fun getLastVerifiedAt(context: Context): Long = prefs(context).getLong(KEY_LAST_VERIFIED_AT, 0L)

    fun setLastVerifiedAt(context: Context, timestamp: Long) {
        prefs(context).edit().putLong(KEY_LAST_VERIFIED_AT, timestamp).apply()
    }

    fun isActiveLocally(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (FREE_PRO_ACCESS_ENABLED) return true
        if (!isSubscribed(context)) return false
        val expires = getExpiresAt(context)
        return expires <= 0L || expires > nowMs
    }

    fun clearEntitlement(
        context: Context,
        provider: String = "none",
        clearPurchaseToken: Boolean = true,
    ) {
        val e = prefs(context).edit()
            .putBoolean(KEY_IS_SUBSCRIBED, false)
            .putString(KEY_PLAN, "none")
            .putLong(KEY_EXPIRES_AT, 0L)
            .putString(KEY_PROVIDER, provider)
            .putLong(KEY_LAST_VERIFIED_AT, System.currentTimeMillis())

        if (clearPurchaseToken) {
            e.remove(KEY_PURCHASE_TOKEN)
        }

        e.apply()
    }
}
