package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.shared.glasses.MetaPairingIssueAction
import com.fersaiyan.cyanbridge.shared.glasses.resolveMetaPairingIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaPairingScreenStateTest {
    @Test
    fun requiresAndroidPermissionsFirst() {
        val state = MetaPairingScreenState()

        assertEquals("Grant required permissions", state.primaryLabel)
        assertFalse(state.isReadyForImageQuestion)
    }

    @Test
    fun requestsRegistrationAfterInitialization() {
        val state = MetaPairingScreenState(
            androidCameraGranted = true,
            nearbyDevicesGranted = true,
            initialized = true,
            registrationState = MetaRaybanManager.RegistrationState.AVAILABLE,
        )

        assertEquals("Register CyanBridge in Meta AI", state.primaryLabel)
    }

    @Test
    fun requestsMetaAiInstallBeforeRegistration() {
        val state = MetaPairingScreenState(
            androidCameraGranted = true,
            nearbyDevicesGranted = true,
            initialized = true,
            metaAiInstalled = false,
        )

        assertEquals("Install Meta AI", state.primaryLabel)
    }

    @Test
    fun missingMetaAiErrorHasInstallAction() {
        val issue = resolveMetaPairingIssue(
            metaAiInstalled = false,
            lastError = "registration: The Meta AI app is not installed on the device",
            setupGuidance = "Install or update Meta AI, pair the glasses there, then return to CyanBridge.",
        )

        assertEquals("Meta AI is required", issue?.title)
        assertEquals("Install Meta AI", issue?.primaryLabel)
        assertEquals(MetaPairingIssueAction.INSTALL_META_AI, issue?.action)
    }

    @Test
    fun noEligibleDatDeviceHasFriendlyRetryAction() {
        val issue = resolveMetaPairingIssue(
            metaAiInstalled = true,
            lastError = "createSession: No eligible device found",
            setupGuidance = "Registration is complete, but DAT has not exposed a device yet.",
        )

        assertEquals("Meta glasses are not ready", issue?.title)
        assertEquals("Try again", issue?.primaryLabel)
        assertEquals(MetaPairingIssueAction.OPEN_PAIRING, issue?.action)
    }

    @Test
    fun unknownMetaErrorHasFriendlyRetryAction() {
        val issue = resolveMetaPairingIssue(
            metaAiInstalled = true,
            lastError = "registration: opaque sdk failure 42",
            setupGuidance = null,
        )

        assertEquals("We could not finish Meta pairing", issue?.title)
        assertEquals("Try again", issue?.primaryLabel)
        assertEquals(MetaPairingIssueAction.OPEN_PAIRING, issue?.action)
    }

    @Test
    fun registeredWithoutDatDeviceIsSurfacedAsPairingFailure() {
        val state = MetaPairingScreenState(
            androidCameraGranted = true,
            nearbyDevicesGranted = true,
            initialized = true,
            registrationState = MetaRaybanManager.RegistrationState.REGISTERED,
            availableDeviceCount = 0,
        )

        assertEquals(
            "No DAT device was discovered after Meta registration",
            inferredMetaPairingError(state),
        )
    }

    @Test
    fun unavailableDatWithDiscoveryGuidanceIsSurfacedAsPairingFailure() {
        val state = MetaPairingScreenState(
            androidCameraGranted = true,
            nearbyDevicesGranted = true,
            initialized = true,
            registrationState = MetaRaybanManager.RegistrationState.UNAVAILABLE,
            guidance = "DAT cannot see a linked Meta wearable yet.",
        )

        assertEquals("DAT cannot see a linked Meta wearable", inferredMetaPairingError(state))
    }

    @Test
    fun availableRegistrationDoesNotCreateSyntheticError() {
        val state = MetaPairingScreenState(
            androidCameraGranted = true,
            nearbyDevicesGranted = true,
            initialized = true,
            registrationState = MetaRaybanManager.RegistrationState.AVAILABLE,
            availableDeviceCount = 1,
        )

        assertEquals(null, inferredMetaPairingError(state))
    }

    @Test
    fun requestsGlassesCameraAfterDeviceDiscovery() {
        val state = MetaPairingScreenState(
            androidCameraGranted = true,
            nearbyDevicesGranted = true,
            initialized = true,
            registrationState = MetaRaybanManager.RegistrationState.REGISTERED,
            availableDeviceCount = 1,
        )

        assertEquals("Grant glasses camera access", state.primaryLabel)
    }

    @Test
    fun readyStateOffersAiImageQuestion() {
        val state = MetaPairingScreenState(
            androidCameraGranted = true,
            nearbyDevicesGranted = true,
            initialized = true,
            registrationState = MetaRaybanManager.RegistrationState.REGISTERED,
            availableDeviceCount = 1,
            glassesCameraGranted = true,
        )

        assertTrue(state.isReadyForImageQuestion)
        assertEquals("Test AI image question", state.primaryLabel)
    }
}
