package com.fersaiyan.cyanbridge.shared.ai

import com.fersaiyan.cyanbridge.shared.ai.RelayErrorClassifier.Kind
import com.fersaiyan.cyanbridge.shared.ai.RelayErrorClassifier.RelayError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayErrorClassifierTest {
    private fun classify(message: String?) = RelayErrorClassifier.classify(message)

    @Test
    fun quotaNamesThePlanFromTheMessage() {
        assertEquals(
            RelayError(Kind.QUOTA_EXHAUSTED, "cheap"),
            classify("quota_exhausted: Monthly quota exhausted for plan cheap"),
        )
        assertEquals(RelayError(Kind.QUOTA_EXHAUSTED, "standard"), classify("HTTP 402 quota standard"))
        assertEquals(RelayError(Kind.QUOTA_EXHAUSTED, "Pro"), classify("Quota exhausted"))
        assertTrue(RelayErrorClassifier.isQuotaError(IllegalStateException("402 quota")))
        assertFalse(RelayErrorClassifier.isQuotaError(IllegalStateException("402 payment")))
    }

    @Test
    fun subscriptionAndAuthentication() {
        assertEquals(RelayError(Kind.SUBSCRIPTION_REQUIRED), classify("subscription_required (HTTP 403)"))
        assertEquals(RelayError(Kind.SUBSCRIPTION_REQUIRED), classify("403 Subscription inactive"))
        assertEquals(RelayError(Kind.AUTHENTICATION_REQUIRED), classify("authentication_required (HTTP 401)"))
        assertEquals(RelayError(Kind.AUTHENTICATION_REQUIRED), classify("401 bad token"))
        // Quota wins over the other two.
        assertEquals(RelayError(Kind.QUOTA_EXHAUSTED, "max"), classify("quota exhausted, subscription_required, max"))
    }

    @Test
    fun anythingElseIsGenericWithShortDetail() {
        assertEquals(RelayError(Kind.GENERIC, "Relay HTTP 429"), classify("Relay HTTP 429"))
        assertEquals(RelayError(Kind.GENERIC, "boom"), classify("  boom  "))
        assertEquals(RelayError(Kind.GENERIC, "x".repeat(120)), classify("x".repeat(300)))
        assertEquals(RelayError(Kind.GENERIC, "unknown error"), classify(null))
        assertEquals(RelayError(Kind.GENERIC, "unknown error"), classify(" "))
    }
}
