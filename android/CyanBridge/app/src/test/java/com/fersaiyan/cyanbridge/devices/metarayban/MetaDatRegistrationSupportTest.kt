package com.fersaiyan.cyanbridge.devices.metarayban

import org.junit.Assert.assertTrue
import org.junit.Test

class MetaDatRegistrationSupportTest {
    @Test
    fun unavailableDeveloperBuildWithoutBondedMetaDeviceExplainsDeveloperModeFirst() {
        val guidance = metaDatSetupGuidance(
            registrationState = MetaRaybanManager.RegistrationState.UNAVAILABLE,
            availableDeviceCount = 0,
            readiness = readiness(bondedMetaDeviceCount = 0, developerConfiguration = true),
        )

        assertTrue(guidance.orEmpty().contains("Developer Mode"))
    }

    @Test
    fun unavailableDeveloperBuildExplainsDeveloperMode() {
        val guidance = metaDatSetupGuidance(
            registrationState = MetaRaybanManager.RegistrationState.UNAVAILABLE,
            availableDeviceCount = 0,
            readiness = readiness(bondedMetaDeviceCount = 1, developerConfiguration = true),
        )

        assertTrue(guidance.orEmpty().contains("Developer Mode"))
    }

    @Test
    fun unavailableProductionBuildExplainsReleaseChannelEvenWithoutBondedMetaDevice() {
        val guidance = metaDatSetupGuidance(
            registrationState = MetaRaybanManager.RegistrationState.UNAVAILABLE,
            availableDeviceCount = 0,
            readiness = readiness(bondedMetaDeviceCount = 0, developerConfiguration = false),
        )

        assertTrue(guidance.orEmpty().contains("production registration"))
        assertTrue(guidance.orEmpty().contains("release channel"))
    }

    @Test
    fun registeredWithoutDiscoveredDeviceExplainsPowerAndMetaAiRequirements() {
        val guidance = metaDatSetupGuidance(
            registrationState = MetaRaybanManager.RegistrationState.REGISTERED,
            availableDeviceCount = 0,
            readiness = readiness(bondedMetaDeviceCount = 1),
        )

        assertTrue(guidance.orEmpty().contains("powered"))
        assertTrue(guidance.orEmpty().contains("unfolded"))
        assertTrue(guidance.orEmpty().contains("Meta AI"))
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
