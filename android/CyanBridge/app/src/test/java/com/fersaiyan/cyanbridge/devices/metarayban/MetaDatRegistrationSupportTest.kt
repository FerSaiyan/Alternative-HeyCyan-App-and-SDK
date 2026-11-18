package com.fersaiyan.cyanbridge.devices.metarayban

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MetaDatRegistrationSupportTest {
    /** 2.3.0 reports: Meta AI installed, no bonded Meta glasses, DAT UNAVAILABLE, told to request an invite. */
    @Test
    fun unavailableBeforeRegistrationIsSetupNotAnInvite() {
        for (bonded in listOf(0, 1)) {
            val accessState = resolveMetaAccessState(
                initialized = true,
                registrationState = MetaRaybanManager.RegistrationState.UNAVAILABLE,
                readiness = readiness(bondedMetaDeviceCount = bonded, developerConfiguration = false),
                lastError = null,
            )
            assertEquals(MetaAccessState.NEEDS_SETUP, accessState)
        }
    }

    @Test
    fun rejectedRegistrationOutsideDeveloperModeNeedsMetaInvite() {
        val accessState = resolveMetaAccessState(
            initialized = true,
            registrationState = MetaRaybanManager.RegistrationState.AVAILABLE,
            readiness = readiness(bondedMetaDeviceCount = 1, developerConfiguration = false),
            lastError = "registration: Registration process failed for an unspecified reason",
            registrationRejected = true,
        )

        assertEquals(MetaAccessState.NEEDS_META_INVITE, accessState)
    }

    @Test
    fun developerModeChoiceIsNotSentToTheInviteForm() {
        val accessState = resolveMetaAccessState(
            initialized = true,
            registrationState = MetaRaybanManager.RegistrationState.AVAILABLE,
            readiness = readiness(bondedMetaDeviceCount = 1, developerConfiguration = false).copy(developerModeChosen = true),
            lastError = "registration: Registration process failed for an unspecified reason",
            registrationRejected = true,
        )

        assertEquals(MetaAccessState.FAILED, accessState)
    }

    @Test
    fun explicitSdkErrorIsNotMisclassifiedAsReleaseChannelGating() {
        val accessState = resolveMetaAccessState(
            initialized = true,
            registrationState = MetaRaybanManager.RegistrationState.UNAVAILABLE,
            readiness = readiness(bondedMetaDeviceCount = 1, developerConfiguration = false),
            lastError = "registration: SDK failure 42",
        )

        assertEquals(MetaAccessState.FAILED, accessState)
    }

    @Test
    fun bluetoothOffIsSetupNotReleaseChannelGating() {
        val accessState = resolveMetaAccessState(
            initialized = true,
            registrationState = MetaRaybanManager.RegistrationState.UNAVAILABLE,
            readiness = readiness(
                bondedMetaDeviceCount = 1,
                developerConfiguration = false,
                bluetoothEnabled = false,
            ),
            lastError = null,
        )

        assertEquals(MetaAccessState.NEEDS_SETUP, accessState)
    }

    /** 2.3.0 reports: registered glasses whose stream failed were logged as meta_access_failed. */
    @Test
    fun cameraOrStreamErrorWhileRegisteredKeepsAccessReady() {
        val accessState = resolveMetaAccessState(
            initialized = true,
            registrationState = MetaRaybanManager.RegistrationState.REGISTERED,
            readiness = readiness(bondedMetaDeviceCount = 1),
            lastError = "cameraPermission: All discovered devices are powered off or disconnected",
        )

        assertEquals(MetaAccessState.READY, accessState)
    }

    @Test
    fun unavailableWithoutBondedGlassesAsksToPairInMetaAiOnBothPaths() {
        for (developer in listOf(true, false)) {
            val guidance = metaDatSetupGuidance(
                registrationState = MetaRaybanManager.RegistrationState.UNAVAILABLE,
                availableDeviceCount = 0,
                readiness = readiness(bondedMetaDeviceCount = 0, developerConfiguration = developer),
            ).orEmpty()

            assertTrue(guidance.contains("No Meta glasses are paired with this phone"))
            assertFalse(guidance.contains("not currently enabled"))
        }
    }

    @Test
    fun rejectedRegistrationGuidanceFollowsTheSetupChoice() {
        val invite = metaDatSetupGuidance(
            registrationState = MetaRaybanManager.RegistrationState.AVAILABLE,
            availableDeviceCount = 0,
            readiness = readiness(bondedMetaDeviceCount = 1, developerConfiguration = false),
            registrationRejected = true,
        ).orEmpty()
        val developer = metaDatSetupGuidance(
            registrationState = MetaRaybanManager.RegistrationState.AVAILABLE,
            availableDeviceCount = 0,
            readiness = readiness(bondedMetaDeviceCount = 1, developerConfiguration = false).copy(developerModeChosen = true),
            registrationRejected = true,
        ).orEmpty()

        assertTrue(invite.contains("https://cyanbridgelabs.com/beta"))
        assertTrue(developer.contains("Developer Mode is on"))
        assertFalse(developer.contains("https://cyanbridgelabs.com/beta"))
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
