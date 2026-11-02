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

    private val swuSha = CustomFirmwareBundleReader.sha256Hex(swu)

    private fun manifest(binSha: String = CustomFirmwareBundleReader.sha256Hex(bin), baseBle: String = "2.0.1", wifiFw: String = "1.2.3") = """
        {"format":"cyanbridge-custom-firmware","format_version":1,"device_family":"heycyan",
         "base_ble_firmware_version":"$baseBle","base_wifi_firmware_version":"1.2.3",
         "files":[{"role":"ble","name":"fw.bin","sha256":"$binSha"}],
         "wifi":{"channel":"led","wifi_hardware_version":"V821","wifi_firmware_version":"$wifiFw","sha256":"$swuSha","size":300}}
    """.trimIndent()

    @Test
    fun heyCyanZipCarriesTheBinAndNamesTheCatalogSwu() {
        val bundle = CustomFirmwareBundleReader.read("custom.zip", zip(manifest(), mapOf("fw.bin" to bin)))

        assertEquals(CustomizationFamily.HEYCYAN, bundle.family)
        assertArrayEquals(bin, bundle.ble.bytes)
        assertNull(bundle.wifi)
        assertEquals(WifiCatalogRef("led", "V821", "1.2.3", swuSha, 300), bundle.wifiCatalog)
        assertNull(CustomFirmwareBundleReader.mismatch(bundle, heyCyan))
        val otherWifi = CustomFirmwareBundleReader.read("c.zip", zip(manifest(wifiFw = "1.2.4"), mapOf("fw.bin" to bin)))
        assertTrue(CustomFirmwareBundleReader.mismatch(otherWifi, heyCyan)!!.contains("Wi-Fi part is for V821 1.2.4"))
    }

    @Test
    fun damagedFilesWrongBaseAndMissingPartsAreRefused() {
        val cases = mapOf(
            "damaged" to { CustomFirmwareBundleReader.read("c.zip", zip(manifest(binSha = "0".repeat(64)), mapOf("fw.bin" to bin))) },
            "Wi-Fi .swu reference" to { CustomFirmwareBundleReader.read("c.zip", zip(manifest().substringBefore(",\n \"wifi\"") + "}", mapOf("fw.bin" to bin))) },
            "not a CyanBridge" to { CustomFirmwareBundleReader.read("c.zip", zip("""{"format":"other"}""", emptyMap())) },
        )
        cases.forEach { (expected, read) ->
            val error = runCatching { read() }.exceptionOrNull()
            assertTrue("$expected: ${error?.message}", error?.message.orEmpty().contains(expected))
        }
        val otherBase = CustomFirmwareBundleReader.read("c.zip", zip(manifest(baseBle = "2.0.0"), mapOf("fw.bin" to bin)))
        assertTrue(CustomFirmwareBundleReader.mismatch(otherBase, heyCyan)!!.contains("built for Bluetooth firmware 2.0.0"))
        val eyevue = CustomFirmwareBundleReader.read("EYEVUE_custom.ufw", ByteArray(10))
        assertTrue(CustomFirmwareBundleReader.mismatch(eyevue, heyCyan)!!.contains("for EyeVue glasses"))
    }
}
