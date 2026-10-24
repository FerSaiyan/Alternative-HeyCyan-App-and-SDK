package com.fersaiyan.cyanbridge.agent

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubscriptionCheckoutPolicyTest {
    @Test
    fun `allows a free trial to upgrade through Google Play`() {
        assertTrue(
            SubscriptionCheckoutPolicy.isGooglePlayCheckoutAllowed(
                changePlanRequested = true,
                currentPlan = "free_trial",
                isSubscribed = true,
            ),
        )
    }

    @Test
    fun `blocks an active paid web subscription from overlapping Google Play`() {
        assertFalse(
            SubscriptionCheckoutPolicy.isGooglePlayCheckoutAllowed(
                changePlanRequested = true,
                currentPlan = "standard",
                isSubscribed = true,
            ),
        )
    }

    @Test
    fun `creates callback on the verified app link`() {
        val url = SubscriptionCheckoutPolicy.createVerifiedCallbackUrl("one-time-result")

        assertEquals(
            "https://cyanbridge.vercel.app/web-subscribe/callback?result=one-time-result",
            url,
        )
        assertEquals("one-time-result", SubscriptionCheckoutPolicy.callbackResultFrom(Uri.parse(url)))
    }

    @Test
    fun `rejects custom scheme and untrusted callback hosts`() {
        assertNull(
            SubscriptionCheckoutPolicy.callbackResultFrom(
                Uri.parse("fersaiyan://pro-sub/callback?result=one-time-result"),
            ),
        )
        assertNull(
            SubscriptionCheckoutPolicy.callbackResultFrom(
                Uri.parse("https://example.com/web-subscribe/callback?result=one-time-result"),
            ),
        )
    }

    @Test
    fun `accepts only the expected email verification return`() {
        assertTrue(
            SubscriptionCheckoutPolicy.isEmailVerificationReturn(
                Uri.parse("fersaiyan://pro-sub/restore"),
            ),
        )
        assertFalse(
            SubscriptionCheckoutPolicy.isEmailVerificationReturn(
                Uri.parse("fersaiyan://pro-sub/other"),
            ),
        )
        assertFalse(
            SubscriptionCheckoutPolicy.isEmailVerificationReturn(
                Uri.parse("https://cyanbridge.vercel.app/email-verified"),
            ),
        )
    }

    @Test
    fun `builds and validates an opaque checkout session URL`() {
        val checkoutPage = "https://cyanbridge.vercel.app/web-subscribe?legacy=true"

        assertEquals(
            "https://cyanbridge.vercel.app/api/billing/checkout-sessions",
            SubscriptionCheckoutPolicy.checkoutSessionEndpoint(checkoutPage),
        )
        assertTrue(
            SubscriptionCheckoutPolicy.isExpectedCheckoutSessionUrl(
                checkoutPage,
                "https://cyanbridge.vercel.app/web-subscribe?checkout_session=opaque-session",
            ),
        )
    }

    @Test
    fun `rejects checkout sessions from a different origin or path`() {
        val checkoutPage = "https://cyanbridge.vercel.app/web-subscribe"

        assertFalse(
            SubscriptionCheckoutPolicy.isExpectedCheckoutSessionUrl(
                checkoutPage,
                "https://example.com/web-subscribe?checkout_session=opaque-session",
            ),
        )
        assertFalse(
            SubscriptionCheckoutPolicy.isExpectedCheckoutSessionUrl(
                checkoutPage,
                "https://cyanbridge.vercel.app/other?checkout_session=opaque-session",
            ),
        )
        assertFalse(
            SubscriptionCheckoutPolicy.isExpectedCheckoutSessionUrl(
                checkoutPage,
                "https://cyanbridge.vercel.app/web-subscribe?checkout_session=opaque-session&api_token=secret",
            ),
        )
    }
}
