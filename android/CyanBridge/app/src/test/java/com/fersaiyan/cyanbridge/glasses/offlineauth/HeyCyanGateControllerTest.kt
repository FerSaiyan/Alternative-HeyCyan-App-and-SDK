package com.fersaiyan.cyanbridge.glasses.offlineauth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Trigger logic for the HeyCyan gate: default-on, run-once-per-connection, NAK -> unlock -> retry once,
 * and diagnostics state recording. Runs under Robolectric because the coordinator logs via android.util.Log.
 */
@RunWith(RobolectricTestRunner::class)
class HeyCyanGateControllerTest {

    @Before
    fun clearState() = HeyCyanGateState.clear()

    private fun controllerFor(glasses: SimulatedHeyCyanGlasses) = HeyCyanGateController(
        coordinatorFactory = {
            HeyCyanOfflineAuthCoordinator(isEnabled = { true }, transportFactory = { prompt -> Prompting(glasses, prompt) })
        },
    )

    /** The simulated glasses, plus the app's prompt callback when the front-button window opens. */
    private class Prompting(
        private val glasses: SimulatedHeyCyanGlasses,
        private val prompt: () -> Unit,
    ) : GlassesAuthTransport by glasses {
        override fun onPromptFrontButton() {
            prompt()
            glasses.onPromptFrontButton()
        }
    }

    @Test
    fun enabledByDefault() {
        assertTrue(HeyCyanGateController.DEFAULT_ENABLED)
        assertTrue(HeyCyanGateController().enabled)
    }

    @Test
    fun connectRunsUnlockOnceAndRecordsGateState() = runBlocking {
        val glasses = SimulatedHeyCyanGlasses(userPressesFrontButton = true)
        val controller = controllerFor(glasses)
        controller.onConnectionReset()

        val first = controller.onHeyCyanConnected()
        assertEquals(OfflineUnlockResult.SUCCESS, first)
        assertTrue(glasses.requestCameraCapture())

        // Diagnostics captured the run.
        assertEquals(HeyCyanAuthProtocol.CAP_M02S_BOTH_REQUIRED, HeyCyanGateState.capabilityByte)
        assertEquals(true, HeyCyanGateState.deviceAuthOk)
        assertEquals(true, HeyCyanGateState.bindOk)
        assertEquals(OfflineUnlockResult.SUCCESS, HeyCyanGateState.lastUnlockResult)

        // Second connect-time trigger does not re-run (per-connection latch).
        assertEquals(OfflineUnlockResult.ALREADY_UNLOCKED, controller.onHeyCyanConnected())
    }

    @Test
    fun preAuthUnitIsLeftUntouched() = runBlocking {
        val glasses = SimulatedHeyCyanGlasses(capabilityByte = 0x00)
        val result = controllerFor(glasses).onHeyCyanConnected()
        assertEquals(OfflineUnlockResult.NOT_REQUIRED, result)
    }

    @Test
    fun disabledDoesNothing() = runBlocking {
        val glasses = SimulatedHeyCyanGlasses(userPressesFrontButton = true)
        val controller = controllerFor(glasses).also { it.enabled = false }
        assertEquals(OfflineUnlockResult.DISABLED, controller.onHeyCyanConnected())
    }

    @Test
    fun gatedNakRunsUnlockAndRetriesCommandOnce() = runBlocking {
        val glasses = SimulatedHeyCyanGlasses(userPressesFrontButton = true)
        val controller = controllerFor(glasses)
        var retries = 0

        val result = controller.onGatedNak(cmdId = 0x41, retry = { retries++ })
        assertEquals(OfflineUnlockResult.SUCCESS, result)
        assertEquals(1, retries)
        assertEquals(0x41, HeyCyanGateState.lastNakCommand)
    }

    @Test
    fun gatedNakDoesNotRetryOnFailure() = runBlocking {
        // bound-to-other can't be unlocked; the command must NOT be retried.
        val glasses = SimulatedHeyCyanGlasses(bindStatus = HeyCyanAuthProtocol.BIND_STATUS_BOUND_OTHER)
        val controller = controllerFor(glasses)
        var retries = 0

        val result = controller.onGatedNak(cmdId = 0x45, retry = { retries++ })
        assertEquals(OfflineUnlockResult.BOUND_TO_OTHER, result)
        assertEquals(0, retries)
        assertEquals(0x45, HeyCyanGateState.lastNakCommand)
    }

    /**
     * add.242 rig: two gated commands NAK 150 ms apart. The second trigger used to get ALREADY_UNLOCKED while the
     * first was still waiting for the press, showed "Glasses activated" over the real prompt and retried the command.
     */
    @Test
    fun concurrentTriggerIsSilent() = runBlocking {
        val controller = controllerFor(SimulatedHeyCyanGlasses(userPressesFrontButton = true))
        val reported = mutableListOf<OfflineUnlockResult>()
        var nested: OfflineUnlockResult? = null
        var nestedRetries = 0

        val result = controller.onGatedNak(
            cmdId = 0x41,
            prompt = {
                nested = runBlocking {
                    controller.onGatedNak(cmdId = 0x41, onResult = { reported += it }, retry = { nestedRetries++ })
                }
            },
            onResult = { reported += it },
        )
        assertEquals(OfflineUnlockResult.SUCCESS, result)
        assertEquals(OfflineUnlockResult.IN_PROGRESS, nested)
        assertEquals(listOf(OfflineUnlockResult.SUCCESS), reported)
        assertEquals(0, nestedRetries)
    }

    /** add.242 rig: after a missed press, background NAKs every ~20 s re-prompted forever. */
    @Test
    fun missedPressStopsBackgroundRepromptsUntilUserActionOrReconnect() = runBlocking {
        val controller = controllerFor(SimulatedHeyCyanGlasses(userPressesFrontButton = false))
        var prompts = 0

        assertEquals(OfflineUnlockResult.BIND_REJECTED, controller.onGatedNak(cmdId = 0x41, prompt = { prompts++ }))
        assertEquals(OfflineUnlockResult.BIND_REJECTED, controller.onGatedNak(cmdId = 0x41, prompt = { prompts++ }))
        assertEquals(1, prompts)

        controller.allowRetryAfterMissedPress() // e.g. the user starts a sync
        controller.onGatedNak(cmdId = 0x41, prompt = { prompts++ })
        assertEquals(2, prompts)

        controller.onConnectionReset() // reconnect
        controller.onGatedNak(cmdId = 0x41, prompt = { prompts++ })
        assertEquals(3, prompts)
    }
}
