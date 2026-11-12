package com.fersaiyan.cyanbridge.hil.audio

import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.agent.ProSubscriptionRelayClient
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionVerifier
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly invoked lab setup. Uses real email verification, never fabricated entitlements. */
class ProAccountSetupHilTest {
    @Test
    fun requestVerification() {
        val args = InstrumentationRegistry.getArguments()
        val email = args.getString("proEmail").orEmpty()
        assumeTrue("Pass proEmail to request a real verification email", email.isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProSubscriptionRelayClient.requestAccountEmailVerification(context, email).getOrThrow()
    }

    @Test
    fun verifyAccount() {
        val code = InstrumentationRegistry.getArguments().getString("proCode").orEmpty()
        assumeTrue("Pass proCode from the verification email", code.isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = ProSubscriptionRelayClient.verifyAccountEmailCode(context, code).getOrThrow()
        assertTrue("Email was not verified", account.emailVerified)
        val verified = ProSubscriptionVerifier.verifyNow(context, applyActivationRouting = true)
        assertTrue("Verified account has no active subscription", verified.active)
    }

    /** CI link without email: stores an existing account token (never logged) and requires a server-active entitlement. */
    @Test
    fun linkApiToken() {
        val token = InstrumentationRegistry.getArguments().getString("proApiToken").orEmpty().trim()
        assumeTrue("Pass proApiToken (CYANBRIDGE_HIL_PRO_API_TOKEN) to link an existing account", token.isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProSubscriptionServerPrefs.setApiToken(context, token)
        val account = ProSubscriptionRelayClient.fetchAccountInfo(context).getOrThrow()
        assertTrue("Linked account email is not verified", account.emailVerified)
        val verified = ProSubscriptionVerifier.verifyNow(context, applyActivationRouting = true)
        assertTrue("Linked account has no active server subscription", verified.active && verified.source == "server")
    }
}
