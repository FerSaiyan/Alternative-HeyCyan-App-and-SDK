package com.fersaiyan.cyanbridge.shared.devices.mentra

import com.fersaiyan.cyanbridge.shared.devices.BleDeviceClassifier
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MentraSdkGlassesTest {
    @Test
    fun namesFollowTheSdkScanners() {
        mapOf(
            "Even G1_42_L_57863C" to DeviceClass.EVEN_G1,
            "Even G1_42_R_57863D" to DeviceClass.EVEN_G1,
            "Even G2_32_L_3FFA6C" to DeviceClass.EVEN_G2,
            "Nex1-0042" to DeviceClass.MENTRA_DISPLAY,
            "MENTRA_DISPLAY_7" to DeviceClass.MENTRA_DISPLAY,
            "NIMO_A1B2" to DeviceClass.NIMO,
            "MENTRA_LIVE_BLE_1" to DeviceClass.MENTRA_LIVE,
            // AR99 is never recognized by name; nor are lookalikes.
            "AR99_123456" to DeviceClass.UNKNOWN,
            "Galaxy G1 phone" to DeviceClass.UNKNOWN,
        ).forEach { (name, expected) -> assertEquals(expected, BleDeviceClassifier.guessDeviceClass(name), name) }

        assertTrue(MentraSdkModel.isSecondaryArm("Even G1_42_R_57863D"))
        assertFalse(MentraSdkModel.isSecondaryArm("Even G1_42_L_57863C"))
        assertEquals("42", MentraSdkModel.sdkNameFor(MentraSdkModel.G1, "Even G1_42_L_57863C"))
        assertEquals(MentraSdkModel.G2, MentraSdkModel.forSdkDeviceType("Even Realities G2"))
        assertEquals(listOf(MentraSdkModel.DISPLAY), MentraSdkModel.entries.filterNot { it.onIos })
    }

    /** One manufacturer-specific structure (length, 0xFF, data) after a flags structure. */
    private fun advertisement(project: String, size: Int = 26): ByteArray {
        val data = ByteArray(size - 1)
        project.encodeToByteArray().copyInto(data)
        // XOR 0xAD of 11:22:33:44:55:66 at data offset 8.
        byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66).forEachIndexed { i, b -> data[8 + i] = (b.toInt() xor 0xAD).toByte() }
        return byteArrayOf(2, 0x01, 0x06, size.toByte(), 0xFF.toByte()) + data + ByteArray(4)
    }

    @Test
    fun ar99NeedsItsExactProjectId() {
        assertEquals(Ar99Advertisement("AR99", "11:22:33:44:55:66"), Ar99Advertisement.parse(advertisement("AR99")))
        listOf("AF98", "AF99", "HVXM", "HVXF", "ar99", "").forEach { project ->
            assertNull(Ar99Advertisement.parse(advertisement(project)), project)
        }
        assertNull(Ar99Advertisement.parse(advertisement("AR99", size = 20)), "short manufacturer data")
        assertNull(Ar99Advertisement.parse(null))
        assertNull(Ar99Advertisement.parse(byteArrayOf(2, 0x01, 0x06)), "no manufacturer data")
    }

    @Test
    fun pairingPicksTheChosenGlassesOrNone() {
        val ar99 = MentraSdkCandidate("SN: 123456", "11:22:33:44:55:66", projectName = "AR99")
        assertEquals(ar99, MentraSdkPairing.choose(MentraSdkModel.AR99, listOf(ar99), null, "11:22:33:44:55:66"))
        assertNull(MentraSdkPairing.choose(MentraSdkModel.AR99, listOf(ar99.copy(projectName = "AF99")), null, "11:22:33:44:55:66"))
        assertNull(MentraSdkPairing.choose(MentraSdkModel.AR99, listOf(ar99), null, null), "AR99 never falls back")

        val g1 = listOf(MentraSdkCandidate("41", null), MentraSdkCandidate("42", null))
        assertEquals(g1[1], MentraSdkPairing.choose(MentraSdkModel.G1, g1, "Even G1_42_L_57863C", "AA:BB"))

        val g2 = MentraSdkCandidate("S110LABC000123", null)
        assertEquals(g2, MentraSdkPairing.choose(MentraSdkModel.G2, listOf(g2), "Even G2_32_L_3FFA6C", "AA:BB"))
        assertNull(MentraSdkPairing.choose(MentraSdkModel.G2, listOf(g2, g2.copy(name = "S110LABC000124")), "Even G2_32_L_3FFA6C", "AA:BB"))

        val nimo = MentraSdkCandidate("NIMO_A1B2", "CC:DD")
        assertEquals(nimo, MentraSdkPairing.choose(MentraSdkModel.NIMO, listOf(nimo.copy(address = "EE:FF"), nimo), "NIMO_A1B2_ble", "cc:dd"))
    }
}
