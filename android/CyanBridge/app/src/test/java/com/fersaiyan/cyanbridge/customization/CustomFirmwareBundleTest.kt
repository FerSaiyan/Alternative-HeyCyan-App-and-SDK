package com.fersaiyan.cyanbridge.customization

import com.fersaiyan.cyanbridge.shared.customization.CustomizationFamily
import com.fersaiyan.cyanbridge.shared.customization.GlassesFirmwareInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CustomFirmwareBundleTest {
    private val swu = ByteArray(300) { 1 }
    private val bin = ByteArray(200) { 2 }
    private val heyCyan = GlassesFirmwareInfo(CustomizationFamily.HEYCYAN, "W610", "AC707N", "2.0.1", "V821", "1.2.3")

    private fun zip(manifest: String, files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            (files + (CustomFirmwareBundleReader.MANIFEST to manifest.toByteArray())).forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifest(binSha: String = CustomFirmwareBundleReader.sha256Hex(bin), baseBle: String = "2.0.1") = """
        {"format":"cyanbridge-custom-firmware","format_version":1,"device_family":"heycyan",
         "base_ble_firmware_version":"$baseBle","base_wifi_firmware_version":"1.2.3",
         "files":[{"role":"wifi","name":"app.swu","sha256":"${CustomFirmwareBundleReader.sha256Hex(swu)}"},
                  {"role":"ble","name":"fw.bin","sha256":"$binSha"}]}
    """.trimIndent()

    @Test
    fun heyCyanZipYieldsTheVerifiedPairForTheMatchingGlasses() {
        val bundle = CustomFirmwareBundleReader.read("custom.zip", zip(manifest(), mapOf("app.swu" to swu, "fw.bin" to bin)))

        assertEquals(CustomizationFamily.HEYCYAN, bundle.family)
        assertArrayEquals(swu, bundle.wifi!!.bytes)
        assertArrayEquals(bin, bundle.ble.bytes)
        assertNull(CustomFirmwareBundleReader.mismatch(bundle, heyCyan))
    }

    @Test
    fun damagedFilesWrongBaseAndMissingPartsAreRefused() {
        val cases = mapOf(
            "damaged" to { CustomFirmwareBundleReader.read("c.zip", zip(manifest(binSha = "0".repeat(64)), mapOf("app.swu" to swu, "fw.bin" to bin))) },
            "Wi-Fi .swu" to { CustomFirmwareBundleReader.read("c.zip", zip(manifest().replace("app.swu\",\"sha", "gone.swu\",\"sha"), mapOf("fw.bin" to bin))) },
            "not a CyanBridge" to { CustomFirmwareBundleReader.read("c.zip", zip("""{"format":"other"}""", emptyMap())) },
        )
        cases.forEach { (expected, read) ->
            val error = runCatching { read() }.exceptionOrNull()
            assertTrue("$expected: ${error?.message}", error?.message.orEmpty().contains(expected) || error?.message.orEmpty().contains("missing"))
        }
        val otherBase = CustomFirmwareBundleReader.read("c.zip", zip(manifest(baseBle = "2.0.0"), mapOf("app.swu" to swu, "fw.bin" to bin)))
        assertTrue(CustomFirmwareBundleReader.mismatch(otherBase, heyCyan)!!.contains("built for Bluetooth firmware 2.0.0"))
        val eyevue = CustomFirmwareBundleReader.read("EYEVUE_custom.ufw", ByteArray(10))
        assertTrue(CustomFirmwareBundleReader.mismatch(eyevue, heyCyan)!!.contains("for EyeVue glasses"))
    }
}
