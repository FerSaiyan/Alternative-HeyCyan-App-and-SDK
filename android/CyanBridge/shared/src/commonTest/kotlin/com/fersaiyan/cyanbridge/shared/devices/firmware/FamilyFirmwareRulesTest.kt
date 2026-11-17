package com.fersaiyan.cyanbridge.shared.devices.firmware

import com.fersaiyan.cyanbridge.shared.customization.CustomizationFamily
import com.fersaiyan.cyanbridge.shared.devices.firmware.FirmwareChecksums.crc16Ccitt
import com.fersaiyan.cyanbridge.shared.devices.firmware.FirmwareChecksums.crc32NoFinalXor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The EyeVue `.ufw` / TuneBuds `.fot` checks and the model rules, on small containers built
 * here with the same fields and checksums as the vendor files (no vendor firmware in the repo).
 */
class FamilyFirmwareRulesTest {
    private val eyevue = FamilyGlassesFirmware(CustomizationFamily.EYEVUE, "S3CY02", "1.3.6", batteryPercent = 80)
    private val tuneBuds = FamilyGlassesFirmware(CustomizationFamily.TUNEBUDS, "XK One Pro", "0.1.0.6", batteryPercent = 80)

    @Test
    fun ufwContainerChecksAndModelCode() {
        assertEquals(UfwInspection(EyevueUfw.CHIP, "S3CY02"), EyevueUfw.inspect(ufw("CY02")))
        assertNull(EyevueUfw.inspect(ufw(null)).modelCode)
        val damaged = listOf(
            "header" to ufw("CY02").also { it[0x05] = (it[0x05] + 1).toByte() },
            "flash" to ufw("CY02").also { it[0x600] = (it[0x600] + 1).toByte() },
            "trailer" to ufw("CY02").also { it[it.size - 16] = 'X'.code.toByte() },
            "truncated" to ufw("CY02").copyOf(0x2500),
        )
        damaged.forEach { (what, bytes) ->
            assertIs<FirmwareCheck.Refused>(FamilyFirmwareRules.check(eyevueFile(bytes), eyevue), what)
        }
    }

    @Test
    fun eyevueModelAndBaseVersionRules() {
        val cases = listOf(
            Triple("same model", eyevueFile(ufw("CY02")), true),
            Triple("other model", eyevueFile(ufw("S100")), false),
            Triple("unreadable model, bare file", eyevueFile(ufw(null)), false),
            Triple("unreadable model, bundle", eyevueFile(ufw(null), FirmwareOrigin.BUNDLE, base = "1.3.6"), true),
            Triple("bundle for other base", eyevueFile(ufw("CY02"), FirmwareOrigin.BUNDLE, base = "1.4.3"), false),
            Triple("wrong extension", eyevueFile(ufw("CY02"), name = "x.bin"), false),
        )
        cases.forEach { (what, candidate, accepted) ->
            assertEquals(accepted, FamilyFirmwareRules.check(candidate, eyevue) is FirmwareCheck.Accepted, what)
        }
        assertIs<FirmwareCheck.Refused>(FamilyFirmwareRules.check(eyevueFile(ufw("CY02")), eyevue.copy(modelCode = null)))
        assertIs<FirmwareCheck.Refused>(FamilyFirmwareRules.check(eyevueFile(ufw("CY02")), tuneBuds))
        val rollback = FamilyFirmwareRules.check(eyevueFile(ufw("CY02"), targetVersion = "1.3.5"), eyevue)
        assertEquals(FirmwareCheck.Accepted("1.3.5", notNewer = true), rollback)
    }

    @Test
    fun fotContainerChecksAndBundleRule() {
        val bundle = { bytes: ByteArray -> tuneBudsFile(bytes, FirmwareOrigin.BUNDLE, base = "0.1.0.6") }
        assertIs<FirmwareCheck.Accepted>(FamilyFirmwareRules.check(bundle(fot()), tuneBuds))
        listOf(
            "header crc" to fot().also { it[0x30] = 1 },
            "payload crc" to fot().also { it[0x210] = (it[0x210] + 1).toByte() },
            "trailer crc" to fot().also { it[it.size - 1] = 9 },
            "length" to fot().copyOf(0x300),
        ).forEach { (what, bytes) ->
            assertIs<FirmwareCheck.Refused>(FamilyFirmwareRules.check(bundle(bytes), tuneBuds), what)
        }
        // A bare .fot names no model; only the bundle (built from the glasses' firmware) is installed.
        assertIs<FirmwareCheck.Refused>(FamilyFirmwareRules.check(tuneBudsFile(fot(), FirmwareOrigin.FILE), tuneBuds))
        assertIs<FirmwareCheck.Refused>(
            FamilyFirmwareRules.check(tuneBudsFile(fot(), FirmwareOrigin.BUNDLE, base = "0.1.0.5"), tuneBuds),
        )
    }

    @Test
    fun blockersAndVersions() {
        val ready = FamilyFirmwareRules.blockers(true, 80, CustomizationFamily.EYEVUE, glassesBusy = false, audioBusy = false)
        assertTrue(ready.isEmpty())
        assertEquals(
            setOf(FamilyOtaBlocker.NOT_CONNECTED, FamilyOtaBlocker.BATTERY_LOW, FamilyOtaBlocker.GLASSES_BUSY, FamilyOtaBlocker.AUDIO_BUSY),
            FamilyFirmwareRules.blockers(false, 49, CustomizationFamily.TUNEBUDS, glassesBusy = true, audioBusy = true),
        )
        assertTrue(FamilyFirmwareRules.blockers(true, null, CustomizationFamily.EYEVUE, false, false).isEmpty())
        assertEquals(0, FamilyFirmwareRules.compareVersions("1.3.6", "1.3.6.0"))
        assertTrue(FamilyFirmwareRules.compareVersions("1.4.3", "1.3.10") > 0)
        assertTrue(FamilyFirmwareRules.compareVersions("1.3.9", "1.3.10") < 0)
    }

    private fun eyevueFile(
        bytes: ByteArray,
        origin: FirmwareOrigin = FirmwareOrigin.FILE,
        base: String? = null,
        name: String = "fw.ufw",
        targetVersion: String? = null,
    ) = FirmwareCandidate(CustomizationFamily.EYEVUE, name, bytes, "0".repeat(64), origin, base, targetVersion)

    private fun tuneBudsFile(bytes: ByteArray, origin: FirmwareOrigin, base: String? = null) =
        FirmwareCandidate(CustomizationFamily.TUNEBUDS, "fw.fot", bytes, "0".repeat(64), origin, base)

    companion object {
        private const val APP_DIR = 0x2400

        /** A `.ufw` with one `flash.bin` record and an application that reports `S3` + [customer] (none when null). */
        fun ufw(customer: String?): ByteArray {
            val appSize = 0x300
            val flashOff = 0x400
            val flashLen = APP_DIR - flashOff + appSize + 0x20
            val raw = ByteArray(flashOff + flashLen + 16)
            val app = ByteArray(appSize) { (it * 7).toByte() }
            "app_area_head".encodeToByteArray().copyOf(16).copyInto(app, 16)
            put32(app, 8, appSize.toLong())
            customer?.let { (byteArrayOf(0x40, 0xE0.toByte(), 'S'.code.toByte(), '3'.code.toByte(), 0xC1.toByte(), 0xFF.toByte()) + it.encodeToByteArray()).copyInto(app, 0x80) }
            put16(app, 2, crc16Ccitt(app, 0x20, appSize))
            put16(app, 0, crc16Ccitt(app, 2, 32))
            app.copyInto(raw, APP_DIR)
            var block = APP_DIR
            while (block < APP_DIR + appSize) {
                xorKeystream(raw, block, minOf(32, APP_DIR + appSize - block), (0x7DF0 xor ((0xF701 + (block shr 2)) and 0xFFFF)) and 0xFFFF)
                block += 32
            }
            "JLUFW".encodeToByteArray().copyInto(raw, raw.size - 16)
            val record = ByteArray(0x50)
            put32(record, 4, crc16Ccitt(raw, flashOff, flashOff + flashLen).toLong())
            put32(record, 8, flashOff.toLong())
            put32(record, 12, flashLen.toLong())
            "flash.bin".encodeToByteArray().copyInto(record, 0x40)
            xorKeystream(record, 0, 0x50, 0xFFFF)
            record.copyInto(raw, 0x40)
            val header = ByteArray(0x40)
            put16(header, 2, crc16Ccitt(raw, 0x40, 0x90))
            put32(header, 4, raw.size.toLong())
            put16(header, 8, 1)
            EyevueUfw.CHIP.encodeToByteArray().copyInto(header, 0x10)
            put16(header, 0, crc16Ccitt(header, 2, 0x40))
            xorKeystream(header, 0, 0x40, 0xFFFF)
            header.copyInto(raw, 0)
            return raw
        }

        /** A `.fot` with a 512-byte payload and a 64-byte trailer. */
        fun fot(): ByteArray {
            val raw = ByteArray(0x200 + 512 + 64) { if (it >= 0x200) (it * 13).toByte() else 0 }
            "POT".encodeToByteArray().copyInto(raw, 0)
            put32(raw, 4, (raw.size - 8).toLong())
            put32(raw, 8, 0xCFC6CEC9)
            TuneBudsFot.CHIP.encodeToByteArray().copyInto(raw, 0x10)
            put32(raw, 0x54, 0xC1D4C1C4)
            put32(raw, 0x5C, 0x200)
            put32(raw, 0x60, 512)
            put32(raw, 0x74, 0x400)
            put32(raw, 0x78, 64)
            put32(raw, 0x7C, crc32NoFinalXor(raw, 0x400, raw.size))
            put32(raw, 0x88, crc32NoFinalXor(raw, 0x200, 0x400))
            put16(raw, 0xFE, crc16Ccitt(raw, 0, 0xFE, init = 0xFFFF))
            return raw
        }

        private fun xorKeystream(b: ByteArray, off: Int, len: Int, key: Int) {
            var k = key
            for (i in off until off + len) {
                b[i] = (b[i].toInt() xor (k and 0xFF)).toByte()
                k = ((k shl 1) and 0xFFFF) xor (if (k and 0x8000 != 0) 0x1021 else 0)
            }
        }

        private fun put16(b: ByteArray, o: Int, v: Int) {
            b[o] = v.toByte()
            b[o + 1] = (v shr 8).toByte()
        }

        private fun put32(b: ByteArray, o: Int, v: Long) {
            for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte()
        }
    }
}
