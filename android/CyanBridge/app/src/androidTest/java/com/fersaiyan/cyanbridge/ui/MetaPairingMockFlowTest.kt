package com.fersaiyan.cyanbridge.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetaPairingMockFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun manager(): MetaRaybanManager = MetaRaybanManager.getInstance(context)

    @After
    fun tearDown() {
        // Always disable mock to avoid leaking state to other tests
        manager().setDebugMockEnabled(false)
    }

    @Test
    fun mockEnabledShowsRegisteredAndMockDevice() {
        val mgr = manager()
        mgr.setDebugMockEnabled(true)

        composeRule.setContent {
            CyanBridgeTheme {
                MetaPairingScreen(
                    state = MetaPairingScreenState(
                        androidCameraGranted = true,
                        nearbyDevicesGranted = true,
                        initialized = true,
                        registrationState = MetaRaybanManager.RegistrationState.REGISTERED,
                        availableDeviceCount = 1,
                        selectedDeviceName = "Mock Ray-Ban (Debug)",
                        glassesCameraGranted = true,
                        debugMockEnabled = true,
                    ),
                    onBack = {},
                    onOpenMetaAi = {},
                    onPrimaryAction = {},
                    onRetryPairing = {},
                    onSendDiagnostics = {},
                    onToggleMock = { mgr.setDebugMockEnabled(it) },
                )
            }
        }

        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("meta_pairing_screen").assertIsDisplayed()
        // Top section visible without scroll
        composeRule.onNodeWithText("Registered").assertExists()
        composeRule.onNodeWithText("Mock Ray-Ban (Debug)").assertExists()
        // Scroll to bottom where mock card + primary button live
        composeRule.onNodeWithTag("meta_pairing_screen").performScrollToNode(hasTestTag("meta_mock_switch"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Glasses camera allowed").assertExists()
        composeRule.onNodeWithText("Test AI image question (mock)").assertExists()
        composeRule.onNodeWithText("Debug: Mock Ray-Ban").assertExists()
        composeRule.onNodeWithTag("meta_mock_switch", useUnmergedTree = true).assertIsOn()
    }

    @Test
    fun mockToggleSwitchCanDisable() {
        val mgr = manager()
        mgr.setDebugMockEnabled(true)
        assertTrue(mgr.debugMockEnabled.value)
        assertEquals(MetaRaybanManager.RegistrationState.REGISTERED, mgr.registrationState.value)
        mgr.setDebugMockEnabled(false)
        assertEquals(false, mgr.debugMockEnabled.value)
        // Re-enable for next test isolation
        mgr.setDebugMockEnabled(true)
        assertTrue(mgr.debugMockEnabled.value)
    }

    @Test
    fun debugMockEnablesFullDatFlowWithoutHardware() {
        val mgr = manager()

        // Start clean
        mgr.setDebugMockEnabled(false)
        assertEquals(false, mgr.debugMockEnabled.value)

        // Enable mock — should synthesize REGISTERED + device + permissions
        mgr.setDebugMockEnabled(true)
        assertTrue(mgr.isInitialized.value)
        assertEquals(MetaRaybanManager.RegistrationState.REGISTERED, mgr.registrationState.value)
        assertTrue(mgr.availableDeviceCount.value > 0)
        assertEquals("Mock Ray-Ban (Debug)", mgr.selectedDeviceName.value)

        // Session/stream/photo should work without real Meta AI or glasses
        runBlocking {
            var sessionOk = false
            var streamOk = false
            // Use callbacks with runBlocking bridge
            mgr.startSession(
                onSuccess = { sessionOk = true },
                onError = { throw AssertionError("startSession failed: $it") },
            )
            // startSession mock is synchronous
            assertTrue("Mock session should start", sessionOk)

            mgr.startStreaming(
                onFrame = { /* ignore fake frame */ },
                onSuccess = { streamOk = true },
                onError = { throw AssertionError("startStreaming failed: $it") },
            )
            assertTrue("Mock stream should start", streamOk)
            assertTrue(mgr.isStreaming.value)

            // Capture mock photo — should persist to MediaStore/DCIM and return bytes
            var captured: MetaRaybanManager.CapturedPhoto? = null
            mgr.capturePhoto(
                onSuccess = { captured = it },
                onError = { throw AssertionError("capturePhoto failed: $it") },
            )
            // capturePhoto is async via scope.launch — wait briefly
            var waited = 0
            while (captured == null && waited < 2000) {
                Thread.sleep(100)
                waited += 100
            }
            assertNotNull("Mock photo should be captured", captured)
            assertTrue(captured!!.bytes.isNotEmpty())
            assertEquals("image/jpeg", captured!!.mimeType)

            // Cleanup session/stream
            mgr.stopStreaming()
            mgr.stopSession()
        }

        // Verify disable restores real DAT observation (even if still UNAVAILABLE on emulator)
        mgr.setDebugMockEnabled(false)
        assertEquals(false, mgr.debugMockEnabled.value)
        // After disable, manager should have reset mock device name
        assertTrue(mgr.selectedDeviceName.value == null || mgr.selectedDeviceName.value != "Mock Ray-Ban (Debug)")
    }

    @Test
    fun mockBypassesMetaAiRequiredError() {
        // Without mock, Meta AI not installed on emulator -> error
        val noMockState = MetaPairingScreenState(
            metaAiInstalled = false,
            initialized = true,
            debugMockEnabled = false,
        )
        assertNotNull(inferredMetaPairingError(noMockState))

        // With mock enabled, same device should not report error
        val mockState = noMockState.copy(debugMockEnabled = true)
        assertEquals(null, inferredMetaPairingError(mockState))
    }
}
