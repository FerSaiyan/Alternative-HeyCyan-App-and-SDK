package com.fersaiyan.cyanbridge.devices.metarayban

import org.junit.Assert.assertTrue
import org.junit.Test

class MetaDatRegistrationSupportTest {
    @Test
    fun unavailableWithoutBondedMetaDeviceExplainsPairingPrerequisite() {
        val guidance = metaDatSetupGuidance(
            registrationState = MetaRaybanManager.RegistrationState.UNAVAILABLE,
            availableDeviceCount = 0,
            readiness = readiness(bondedMetaDeviceCount = 0),
        )

        assertTrue(guidance.orEmpty().contains("Pair or re-pair supported glasses in Meta AI"))
    }

    @Test
    fun unavailableDeveloperBuildExplainsDeveloperMode() {
        val guidance = metaDatSetupGuidance(
            registrationState = MetaRaybanManager.RegistrationState.UNAVAILABLE,
            availableDeviceCount = 0,
            readiness = readiness(bondedMetaDeviceCount = 1, developerConfiguration = true),
        )

        assertTrue(guidance.orEmpty().contains("Enable Developer Mode"))
    }

    @Test
    fun unavailableProductionBuildExplainsReleaseChannel() {
        val guidance = metaDatSetupGuidance(
            registrationState = MetaRaybanManager.RegistrationState.UNAVAILABLE,
            availableDeviceCount = 0,
            readiness = readiness(bondedMetaDeviceCount = 1, developerConfiguration = false),
        )

        assertTrue(guidance.orEmpty().contains("release channel"))
    }

    @Test
    fun registeredWithoutDiscoveredDeviceExplainsPowerAndMetaAiRequirements() {
        val guidance = metaDatSetupGuidance(
            registrationState = MetaRaybanManager.RegistrationState.REGISTERED,
            availableDeviceCount = 0,
            readiness = readiness(bondedMetaDeviceCount = 1),
        )

        assertTrue(guidance.orEmpty().contains("Turn on and unfold"))
    }

    private fun readiness(
        bondedMetaDeviceCount: Int,
        developerConfiguration: Boolean = true,
    ) = MetaDatReadiness(
        metaAiInstalled = true,
        bluetoothPermissionGranted = true,
        bluetoothEnabled = true,
        bondedDeviceCount = bondedMetaDeviceCount,
        bondedMetaDeviceCount = bondedMetaDeviceCount,
        developerConfiguration = developerConfiguration,
    )
}
