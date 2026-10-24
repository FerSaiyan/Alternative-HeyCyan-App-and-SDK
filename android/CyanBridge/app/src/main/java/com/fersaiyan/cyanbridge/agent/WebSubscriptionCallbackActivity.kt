package com.fersaiyan.cyanbridge.agent

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.shared.billing.CheckoutReturn
import com.fersaiyan.cyanbridge.shared.billing.acceptDonationReturn
import com.fersaiyan.cyanbridge.shared.billing.verifySubscriptionReturn
import kotlin.concurrent.thread

/**
 * Handles the verified HTTPS browser return. URL parameters never carry account credentials or
 * entitlement data; a short-lived opaque result only wakes the app to verify server state.
 */
class WebSubscriptionCallbackActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platform = AndroidProAccountPlatform(this)
        val result = SubscriptionCheckoutPolicy.callbackResultFrom(intent?.data)
        when (ProSubscriptionServerPrefs.consumeWebCallbackResult(this, result)) {
            ProSubscriptionServerPrefs.WebCallbackPurpose.SUBSCRIPTION -> thread {
                val outcome = platform.verifySubscriptionReturn()
                runOnUiThread { finishCallback(outcome) }
            }
            ProSubscriptionServerPrefs.WebCallbackPurpose.DONATION -> finishCallback(platform.acceptDonationReturn())
            null -> finish()
        }
    }

    private fun finishCallback(result: CheckoutReturn) {
        val destination = if (result.success) ProSubscriptionSettingsActivity::class.java else ProSubscriptionActivity::class.java
        startActivity(Intent(this, destination).apply {
            putExtra(ProSubscriptionActivity.EXTRA_CALLBACK_MESSAGE, result.message)
            putExtra(ProSubscriptionActivity.EXTRA_FROM_WEB_CALLBACK, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }
}
