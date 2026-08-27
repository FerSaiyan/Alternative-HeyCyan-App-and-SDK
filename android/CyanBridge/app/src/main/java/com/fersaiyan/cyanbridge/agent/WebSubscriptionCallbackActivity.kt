package com.fersaiyan.cyanbridge.agent

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * Handles the verified HTTPS browser return. URL parameters never carry account credentials or
 * entitlement data; a short-lived opaque result only wakes the app to verify server state.
 */
class WebSubscriptionCallbackActivity : AppCompatActivity() {

    private data class CallbackResult(
        val success: Boolean,
        val message: String,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val result = SubscriptionCheckoutPolicy.callbackResultFrom(intent?.data)
        when (ProSubscriptionServerPrefs.consumeWebCallbackResult(this, result)) {
            ProSubscriptionServerPrefs.WebCallbackPurpose.SUBSCRIPTION -> verifySubscription()
            ProSubscriptionServerPrefs.WebCallbackPurpose.DONATION -> resumeDonation()
            null -> finish()
        }
    }

    private fun verifySubscription() {
        thread {
            // A browser redirect is never proof of payment. Require a fresh server response.
            val verified = ProSubscriptionVerifier.verifyNow(
                this,
                strictForTesting = true,
                applyActivationRouting = true,
            )
            val result = CallbackResult(
                success = verified.active,
                message = if (verified.active) {
                    "Subscription verified"
                } else {
                    "Payment returned, but no active subscription was confirmed."
                },
            )
            runOnUiThread { finishCallback(result) }
        }
    }

    private fun resumeDonation() {
        PendingDonationPrefs.setAwaitingReturn(this, true)
        finishCallback(CallbackResult(false, "Return received. Check the donation status to confirm payment."))
    }

    private fun finishCallback(result: CallbackResult) {
        val destination = if (result.success) {
            ProSubscriptionSettingsActivity::class.java
        } else {
            ProSubscriptionActivity::class.java
        }

        startActivity(Intent(this, destination).apply {
            putExtra(ProSubscriptionActivity.EXTRA_CALLBACK_MESSAGE, result.message)
            putExtra(ProSubscriptionActivity.EXTRA_FROM_WEB_CALLBACK, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }
}
