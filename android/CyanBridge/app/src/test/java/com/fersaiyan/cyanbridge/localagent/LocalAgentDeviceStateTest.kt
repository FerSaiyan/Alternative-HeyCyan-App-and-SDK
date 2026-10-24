package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
class LocalAgentDeviceStateTest {

    @Test
    fun `ready requires an interactive unlocked phone`() {
        assertEquals(
            LocalAgentDeviceState.Availability.READY,
            LocalAgentDeviceState.fromSignals(interactive = true, keyguardLocked = false),
        )
        assertEquals(
            LocalAgentDeviceState.Availability.NON_INTERACTIVE,
            LocalAgentDeviceState.fromSignals(interactive = false, keyguardLocked = false),
        )
        assertEquals(
            LocalAgentDeviceState.Availability.LOCKED,
            LocalAgentDeviceState.fromSignals(interactive = true, keyguardLocked = true),
        )
    }

    @Test
    fun `missing platform state fails closed`() {
        assertEquals(
            LocalAgentDeviceState.Availability.UNAVAILABLE,
            LocalAgentDeviceState.fromSignals(interactive = null, keyguardLocked = false),
        )
        assertEquals(
            LocalAgentDeviceState.Availability.UNAVAILABLE,
            LocalAgentDeviceState.fromSignals(interactive = true, keyguardLocked = null),
        )
    }

    @Test
    fun `controller refuses new task when device state cannot be verified`() {
        val result = LocalAgentController.start(unavailableDeviceContext(), "Open Settings")

        assertFalse(result.ok)
        assertEquals("device_state_unavailable", result.error)
    }

    @Test
    fun `screenshot planning fails before accessibility when device state is unavailable`() = runBlocking {
        val capture = LocalAgentScreenshotCapture.captureForPlanning(
            context = unavailableDeviceContext(),
            observation = LocalAgentObservation(
                createdAtMs = 1L,
                packageName = "com.android.settings",
                screenText = "Settings",
                screenSnapshot = null,
            ),
        )

        assertTrue(capture is LocalAgentScreenshotCapture.Capture.Unavailable)
        assertTrue((capture as LocalAgentScreenshotCapture.Capture.Unavailable).reason.contains("Unable to verify"))
    }

    private fun unavailableDeviceContext(): Context = object : ContextWrapper(
        ApplicationProvider.getApplicationContext<Context>(),
    ) {
        override fun getSystemService(name: String): Any? {
            return if (name == Context.POWER_SERVICE || name == Context.KEYGUARD_SERVICE) {
                null
            } else {
                super.getSystemService(name)
            }
        }
    }
}
