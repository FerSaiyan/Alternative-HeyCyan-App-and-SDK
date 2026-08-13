package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
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
