package com.fersaiyan.cyanbridge.glasses.offlineauth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The coordinator uses `android.util.Log`, so it runs under Robolectric (JVM, no AVD). It verifies
 * the opt-in gate: disabled by default (unchanged shared-branch behavior) vs enabled unlock.
 */
@RunWith(RobolectricTestRunner::class)
class HeyCyanOfflineAuthCoordinatorTest {

    @Test
    fun disabledByDefaultDoesNothing() = runBlocking {
        val glasses = SimulatedHeyCyanGlasses()
        val coordinator = HeyCyanOfflineAuthCoordinator(
            isEnabled = { false },
            transportFactory = { glasses },
        )
        assertEquals(OfflineUnlockResult.DISABLED, coordinator.attemptOfflineUnlock())
        assertFalse("nothing sent while disabled", glasses.requestCameraCapture())
    }

    @Test
    fun enabledRunsUnlockAndFlipsGate() = runBlocking {
        val glasses = SimulatedHeyCyanGlasses(userPressesFrontButton = true)
        val coordinator = HeyCyanOfflineAuthCoordinator(
            isEnabled = { true },
            transportFactory = { glasses },
        )
        assertFalse(glasses.requestCameraCapture())
        val result = coordinator.attemptOfflineUnlock(onPromptFrontButton = { })
        assertEquals(OfflineUnlockResult.SUCCESS, result)
        assertTrue(glasses.requestCameraCapture())
    }
}
