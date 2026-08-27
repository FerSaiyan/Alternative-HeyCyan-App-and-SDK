package com.fersaiyan.cyanbridge.devices.metarayban

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class MetaDatRegistrationSupportTest {
    @Test
    fun silentUnavailableProductionRegistrationNeedsMetaInvite() {
        val accessState = resolveMetaAccessState(
            initialized = true,
            registrationState = MetaRaybanManager.RegistrationState.UNAVAILABLE,
            availableDeviceCount = 0,
            readiness = readiness(bondedMetaDeviceCount = 1, developerConfiguration = false),
            lastError = null,
        )

        assertEquals(MetaAccessState.NEEDS_META_INVITE, accessState)
    }

    @Test
    fun explicitSdkErrorIsNotMisclassifiedAsReleaseChannelGating() {
        val accessState = resolveMetaAccessState(
            initialized = true,
            registrationState = MetaRaybanManager.RegistrationState.UNAVAILABLE,
            availableDeviceCount = 0,
            readiness = readiness(bondedMetaDeviceCount = 1, developerConfiguration = false),
            lastError = "registration: SDK failure 42",
        )

        assertEquals(MetaAccessState.FAILED, accessState)
    }

    @Test
    fun bluetoothFailureIsNotMisclassifiedAsReleaseChannelGating() {
        val accessState = resolveMetaAccessState(
            initialized = true,
            registrationState = MetaRaybanManager.RegistrationState.UNAVAILABLE,
            availableDeviceCount = 0,
            readiness = readiness(
                bondedMetaDeviceCount = 1,
                developerConfiguration = false,
                bluetoothEnabled = false,
            ),
            lastError = null,
        )

        assertEquals(MetaAccessState.FAILED, accessState)
    }

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

        assertTrue(guidance.orEmpty().contains("not currently enabled"))
        assertTrue(guidance.orEmpty().contains("https://cyanbridge.vercel.app/beta"))
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
        bluetoothEnabled: Boolean = true,
    ) = MetaDatReadiness(
        metaAiInstalled = true,
        bluetoothPermissionGranted = true,
        bluetoothEnabled = bluetoothEnabled,
        bondedDeviceCount = bondedMetaDeviceCount,
        bondedMetaDeviceCount = bondedMetaDeviceCount,
        developerConfiguration = developerConfiguration,
    )
}
