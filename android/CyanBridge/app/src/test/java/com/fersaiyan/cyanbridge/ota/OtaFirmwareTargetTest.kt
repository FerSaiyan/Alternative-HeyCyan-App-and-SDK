package com.fersaiyan.cyanbridge.ota

import com.fersaiyan.cyanbridge.shared.glasses.OtaFirmwareSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaFirmwareTargetTest {

    @Test
    fun firmwareArtifactsAreNotSharedAcrossTargets() {
        assertTrue(OtaTarget.V821_WIFI.isExpectedFirmwareFilename("WIFIAM01G1.swu"))
        assertFalse(OtaTarget.V821_WIFI.isExpectedFirmwareFilename("JIELI.bin"))

        assertTrue(OtaTarget.JIELI_BLE.isExpectedFirmwareFilename("JIELI.bin"))
        assertFalse(OtaTarget.JIELI_BLE.isExpectedFirmwareFilename("WIFIAM01G1.swu"))
    }

    @Test
    fun combinedWorkflowRequiresBothTransportArtifacts() {
        assertTrue(isCombinedOtaFilenamePair("WIFIAM01G1.swu", "JIELI.bin"))
        assertFalse(isCombinedOtaFilenamePair("WIFIAM01G1.swu", "JIELI.swu"))
        assertFalse(isCombinedOtaFilenamePair("JIELI.bin", "WIFIAM01G1.swu"))
    }

    @Test
    fun `only approved server sources map to catalog channels`() {
        assertNull(OtaFirmwareSource.PERSONAL_FILE.serverChannel())
        assertEquals("stealth", OtaFirmwareSource.STEALTH_CATALOG.serverChannel())
        assertEquals("debug", OtaFirmwareSource.DEBUG_CATALOG.serverChannel())
    }

    @Test
    fun `server artifacts must declare the exact current base firmware`() {
        assertTrue(
            isExactFirmwareBaseMatch(
                "WIFIAM01G1_1.00.28_2603031800",
                "wifiam01g1_1.00.28_2603031800",
            ),
        )
        assertFalse(
            isExactFirmwareBaseMatch(
                "WIFIAM01G1_1.00.28_2603031800",
                "WIFIAM01G1_1.00.23_2510111600",
            ),
        )
    }

    @Test
    fun `one device info response supplies exact versions for both OTA targets`() {
        val versions = InstalledFirmwareVersions(
            wifiHardwareVersion = "WIFIAM01G1_V9.2",
            wifiFirmwareVersion = "WIFIAM01G1_1.00.28_2603031800",
            bleHardwareVersion = "JL7018F_V1",
            bleFirmwareVersion = "JL7018F_1.02.03",
        )

        assertTrue(versions.isComplete())
        assertEquals("WIFIAM01G1_V9.2", versions.hardwareVersionFor(OtaTarget.V821_WIFI))
        assertEquals("WIFIAM01G1_1.00.28_2603031800", versions.firmwareVersionFor(OtaTarget.V821_WIFI))
        assertEquals("JL7018F_V1", versions.hardwareVersionFor(OtaTarget.JIELI_BLE))
        assertEquals("JL7018F_1.02.03", versions.firmwareVersionFor(OtaTarget.JIELI_BLE))
    }

    @Test
    fun `server SHA-256 metadata must be a complete hex digest`() {
        assertTrue(isSha256Hex("a".repeat(64)))
        assertFalse(isSha256Hex("a".repeat(63)))
        assertFalse(isSha256Hex("z".repeat(64)))
    }

    @Test
    fun `only the explicit patch-unavailable response opens a patch request`() {
        assertTrue(
            isFirmwarePatchUnavailableResponse(
                FIRMWARE_PATCH_UNAVAILABLE_STATUS,
                FIRMWARE_PATCH_UNAVAILABLE_ERROR,
            ),
        )
        assertFalse(isFirmwarePatchUnavailableResponse(404, FIRMWARE_PATCH_UNAVAILABLE_ERROR))
        assertFalse(isFirmwarePatchUnavailableResponse(FIRMWARE_PATCH_UNAVAILABLE_STATUS, "server_error"))
    }

    @Test
    fun `patch requests require the complete relay response contract`() {
        assertEquals(
            "No exact patch is approved.",
            firmwarePatchUnavailableMessage(
                FIRMWARE_PATCH_UNAVAILABLE_STATUS,
                FIRMWARE_PATCH_UNAVAILABLE_ERROR,
                "No exact patch is approved.",
            ),
        )
        assertNull(
            firmwarePatchUnavailableMessage(
                404,
                FIRMWARE_PATCH_UNAVAILABLE_ERROR,
                "No exact patch is approved.",
            ),
        )
        assertNull(
            firmwarePatchUnavailableMessage(
                FIRMWARE_PATCH_UNAVAILABLE_STATUS,
                "firmware_not_available",
                "No exact patch is approved.",
            ),
        )
        assertEquals(
            "No approved firmware patch exists for this installed version.",
            firmwarePatchUnavailableMessage(
                FIRMWARE_PATCH_UNAVAILABLE_STATUS,
                FIRMWARE_PATCH_UNAVAILABLE_ERROR,
                "",
            ),
        )
    }

    @Test
    fun `free trial firmware gate names the required paid plans`() {
        val copy = firmwareSubscriptionGateCopy("free_trial")

        assertEquals("Patched LED OTA: Standard or Max Required", copy.title)
        assertTrue(copy.message.contains("Patched LED OTA firmware"))
        assertTrue(copy.message.contains("paid Standard or Max subscription"))
        assertTrue(copy.message.contains("current plan is Free Trial"))
        assertEquals("View Standard & Max", copy.actionLabel)
    }

    @Test
    fun `cheap firmware gate explicitly says cheap does not qualify`() {
        val copy = firmwareSubscriptionGateCopy("cheap")

        assertTrue(copy.message.contains("current plan is Cheap"))
        assertTrue(copy.message.contains("paid Cheap plan does not include this firmware"))
        assertTrue(copy.message.contains("Upgrade to Standard or Max"))
    }

    @Test
    fun `qualifying plan gate suggests subscription refresh instead of another upgrade`() {
        val copy = firmwareSubscriptionGateCopy("standard")

        assertTrue(copy.message.contains("server reports your current plan as Standard"))
        assertTrue(copy.message.contains("Review or refresh your subscription"))
        assertFalse(copy.message.contains("Upgrade to"))
        assertEquals("Manage subscription", copy.actionLabel)
    }
}
