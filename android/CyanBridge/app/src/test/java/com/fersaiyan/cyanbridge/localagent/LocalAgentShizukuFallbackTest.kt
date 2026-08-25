package com.fersaiyan.cyanbridge.localagent

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAgentShizukuFallbackTest {

    @Test
    fun `removed fallback rejects all fixed input actions`() {
        assertFalse(LocalAgentShizukuFallback.supportsFixedInputOperation(LocalAgentAction.PressEnter, 1080, 2400))
        assertFalse(LocalAgentShizukuFallback.supportsFixedInputOperation(LocalAgentAction.GlobalBack, 1080, 2400))
        assertFalse(LocalAgentShizukuFallback.supportsFixedInputOperation(LocalAgentAction.GlobalHome, 1080, 2400))
        assertFalse(
            LocalAgentShizukuFallback.supportsFixedInputOperation(
                LocalAgentAction.ClickText("Send"),
                1080,
                2400,
            ),
        )
    }

    @Test
    fun `fallback rejects out of bounds or unbounded swipes`() {
        assertFalse(
            LocalAgentShizukuFallback.supportsFixedInputOperation(
                LocalAgentAction.Swipe(0, 1200, 0, -1, 300),
                1080,
                2400,
            ),
        )
        assertFalse(
            LocalAgentShizukuFallback.supportsFixedInputOperation(
                LocalAgentAction.Swipe(0, 1200, 0, 300, 10_000),
                1080,
                2400,
            ),
        )
    }
}
