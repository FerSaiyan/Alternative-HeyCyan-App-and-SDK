package com.fersaiyan.cyanbridge.shared.network.session

import kotlin.test.Test
import kotlin.test.assertEquals

class P2pTeardownPolicyTest {
    private val policy = P2pTeardownPolicy()

    @Test
    fun keepsTheHardwareTestedTimings() {
        assertEquals(5_000L, policy.removeActionTimeoutMs)
        assertEquals(5_000L, policy.confirmTimeoutMs)
        assertEquals(20, policy.confirmPolls)
        assertEquals(1_000L, policy.retryDelayMs)
        assertEquals(3, policy.maxAttempts)
        assertEquals(5_000L, policy.quarantineRetryMs)
    }

    @Test
    fun releaseQuarantinesAndPreflightGivesUpWhenExhausted() {
        assertEquals(TeardownStep.Retry(2, 1_000L), policy.afterUnconfirmedAttempt(1, true, TeardownKind.RELEASE))
        assertEquals(TeardownStep.Quarantine(5_000L), policy.afterUnconfirmedAttempt(3, true, TeardownKind.RELEASE))
        assertEquals(TeardownStep.Quarantine(5_000L), policy.afterUnconfirmedAttempt(1, false, TeardownKind.RELEASE))
        assertEquals(TeardownStep.GiveUp, policy.afterUnconfirmedAttempt(3, true, TeardownKind.PREFLIGHT))
        assertEquals(TeardownStep.GiveUp, policy.afterUnconfirmedAttempt(1, false, TeardownKind.PREFLIGHT))
    }
}
