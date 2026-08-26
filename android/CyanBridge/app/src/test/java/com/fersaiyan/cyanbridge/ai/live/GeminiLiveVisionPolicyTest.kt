package com.fersaiyan.cyanbridge.ai.live

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveVisionPolicyTest {
    @Test
    fun `meta rayban uses live frames capped at one fps`() {
        val capabilities = GeminiLiveVisionPolicy.forDevice(DeviceClass.META_RAYBAN)

        assertEquals(GeminiLiveVisionCapabilities.Mode.LIVE_FRAMES, capabilities.mode)
        assertEquals(1.0, capabilities.maxVideoFps, 0.0)
        assertTrue(
            GeminiLiveVisionPolicy.shouldSendVideoFrame(
                capabilities = capabilities,
                userSpeaking = true,
                nowMs = 2_000L,
                lastFrameSentMs = 1_000L,
                encodingInProgress = false,
            ),
        )
        assertFalse(
            GeminiLiveVisionPolicy.shouldSendVideoFrame(
                capabilities = capabilities,
                userSpeaking = false,
                nowMs = 3_000L,
                lastFrameSentMs = 1_000L,
                encodingInProgress = false,
            ),
        )
    }

    @Test
    fun `hey cyan still capture is opportunistic and rate limited`() {
        val capabilities = GeminiLiveVisionPolicy.forDevice(DeviceClass.HEY_CYAN)

        assertEquals(GeminiLiveVisionCapabilities.Mode.OPPORTUNISTIC_STILL, capabilities.mode)
        assertTrue(capabilities.audibleStillCapture)
        assertTrue(
            GeminiLiveVisionPolicy.shouldCaptureAutomaticStill(
                capabilities = capabilities,
                nowMs = 1_000L,
                lastAutomaticStillMs = 0L,
                captureInProgress = false,
            ),
        )
        assertFalse(
            GeminiLiveVisionPolicy.shouldCaptureAutomaticStill(
                capabilities = capabilities,
                nowMs = 10_000L,
                lastAutomaticStillMs = 1_000L,
                captureInProgress = false,
            ),
        )
        assertTrue(
            GeminiLiveVisionPolicy.shouldCaptureAutomaticStill(
                capabilities = capabilities,
                nowMs = 21_000L,
                lastAutomaticStillMs = 1_000L,
                captureInProgress = false,
            ),
        )
    }

    @Test
    fun `unsupported device does not enable automatic vision`() {
        val capabilities = GeminiLiveVisionPolicy.forDevice(DeviceClass.UNKNOWN)
        assertEquals(GeminiLiveVisionCapabilities.Mode.NONE, capabilities.mode)
        assertFalse(capabilities.supportsAutomaticVision)
    }
}
