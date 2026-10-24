package com.fersaiyan.cyanbridge.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeyCyanP2pPolicyTest {
    @Test
    fun `command timeout requires both callback and transfer evidence to be absent`() {
        assertTrue(HeyCyanP2pPolicy.transferCommandTimedOut(false, false))
        assertFalse(HeyCyanP2pPolicy.transferCommandTimedOut(true, false))
        assertFalse(HeyCyanP2pPolicy.transferCommandTimedOut(false, true))
    }

    @Test
    fun `reset callback arrival allows retry despite vendor default error field`() {
        assertTrue(HeyCyanP2pPolicy.resetCallbackAllowsRetry(true, parsedErrorCode = 1))
        assertFalse(HeyCyanP2pPolicy.resetCallbackAllowsRetry(false, parsedErrorCode = 0))
    }
}
