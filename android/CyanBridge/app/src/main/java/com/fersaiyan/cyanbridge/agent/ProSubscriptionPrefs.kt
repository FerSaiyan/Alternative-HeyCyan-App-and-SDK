package com.fersaiyan.cyanbridge.agent

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.ProPrefs.Subscription
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

object ProSubscriptionPrefs {
    fun isSubscribed(context: Context): Boolean = Subscription.isSubscribed[context]

    fun setSubscribed(context: Context, subscribed: Boolean) {
        Subscription.isSubscribed[context] = subscribed
    }

    fun getPlan(context: Context): String = Subscription.plan[context]

    fun setPlan(context: Context, plan: String) {
        Subscription.plan[context] = plan
    }

    fun getExpiresAt(context: Context): Long = Subscription.expiresAt[context]

    fun setExpiresAt(context: Context, timestamp: Long) {
        Subscription.expiresAt[context] = timestamp
    }

    fun getPurchaseToken(context: Context): String = Subscription.purchaseToken[context].orEmpty()

    fun setPurchaseToken(context: Context, token: String) {
        Subscription.purchaseToken[context] = token
    }

    fun getProvider(context: Context): String = Subscription.provider[context]

    fun setProvider(context: Context, provider: String) {
        Subscription.provider[context] = provider
    }

    fun getLastVerifiedAt(context: Context): Long = Subscription.lastVerifiedAt[context]

    fun setLastVerifiedAt(context: Context, timestamp: Long) {
        Subscription.lastVerifiedAt[context] = timestamp
    }

    fun isActiveLocally(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isSubscribed(context)) return false
        val expires = getExpiresAt(context)
        return expires <= 0L || expires > nowMs
    }

    fun clearEntitlement(
        context: Context,
        provider: String = "none",
        clearPurchaseToken: Boolean = true,
    ) {
        Subscription.edit(context) {
            this[Subscription.isSubscribed] = false
            this[Subscription.plan] = "none"
            this[Subscription.expiresAt] = 0L
            this[Subscription.provider] = provider
            this[Subscription.lastVerifiedAt] = System.currentTimeMillis()
            if (clearPurchaseToken) this[Subscription.purchaseToken] = null
        }
    }
}
