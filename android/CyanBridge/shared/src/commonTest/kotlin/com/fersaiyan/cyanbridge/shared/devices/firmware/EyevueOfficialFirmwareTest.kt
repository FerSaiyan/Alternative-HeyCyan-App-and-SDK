package com.fersaiyan.cyanbridge.shared.devices.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The EyeVue update server's answers (captured 2026-11-17) and the download verification. */
class EyevueOfficialFirmwareTest {
    private val ble = "https://aws-sg.oss-ap-southeast-1.aliyuncs.com/other/202603/UYgDwaBCdoQ3ejOe.ufw"
    private val wifi = "https://aws-sg.oss-ap-southeast-1.aliyuncs.com/other/202603/z3hBfuCokgh9liUY.bin"

    private fun answer(bleUrl: String = ble, code: String = "S3CY02") = """
        {"code":200,"message":"ok","data":{
          "wifi":{"OtaPackage":{"code":"$code","bluetoothUrl":"$bleUrl","wifiUrl":"$wifi","otaArray":"$wifi","packageName":"BLE + WiFi 1.3.6","deviceVersion":"1.3.6"}},
          "ble":{"OtaPackage":{"code":"$code","bluetoothUrl":"$bleUrl","wifiUrl":"$wifi","otaArray":"$bleUrl","packageName":"BLE + WiFi 1.3.6","deviceVersion":"1.3.6"}}},
         "success":true}
    """.trimIndent()

    @Test
    fun offersOnlyNewerFirmwareFromTheVendorBucket() {
        assertEquals(
            EyevueOtaOffer("S3CY02", "1.3.6", ble, "BLE + WiFi 1.3.6", includesWifiPart = true),
            EyevueOfficialFirmware.parseOffer(answer(), "S3CY02", "1.3.5"),
        )
        assertNull(EyevueOfficialFirmware.parseOffer(answer(), "S3CY02", "1.3.6"))
        assertNull(EyevueOfficialFirmware.parseOffer(answer(), "S3CY02", "1.4.3"))
        assertNull(EyevueOfficialFirmware.parseOffer("""{"code":200,"data":{"wifi":null,"ble":null},"success":true}""", "S3CY02", "1.4.3"))
        assertFailsWith<IllegalArgumentException> { EyevueOfficialFirmware.parseOffer(answer("http://example.com/x.ufw"), "S3CY02", "1.0") }
        assertFailsWith<IllegalArgumentException> { EyevueOfficialFirmware.parseOffer(answer(code = "S3S100"), "S3CY02", "1.0") }
        assertFailsWith<IllegalArgumentException> { EyevueOfficialFirmware.parseOffer("<html>", "S3CY02", "1.0") }
        assertEquals(
            "https://platform.eyevue-glass.com/api/app/ota/getNewOtaPackage?deviceVersion=1.4.3&code=S3CY02",
            EyevueOfficialFirmware.checkUrl(EyevueOfficialFirmware.HOSTS[0], "S3CY02", "1.4.3"),
        )
        assertNull(EyevueOfficialFirmware.checkUrl(EyevueOfficialFirmware.HOSTS[0], "S3CY02&x=1", "1.4.3"))
    }

    @Test
    fun downloadMustMatchSizeMd5AndPinnedSha() {
        val offer = EyevueOfficialFirmware.parseOffer(answer(), "S3CY02", "1.3.5")!!
        val expected = EyevueOfficialFirmware.expectation(offer, 1_813_600, "DCXhnm89fwdOa8LvgH0jAg==", "\"0C25E19E6F3D7F074E6BC2EF807D2302\"")
        assertEquals("0c25e19e6f3d7f074e6bc2ef807d2302", expected.md5)
        assertEquals("08ae9518bf7f18ddf2d4dcc04c2aec1b78a89735f78f90592722d967c464e050", expected.sha256)
        val md5 = "0c25e19e6f3d7f074e6bc2ef807d2302"
        val sha = expected.sha256!!
        assertNull(EyevueOfficialFirmware.verifyDownload(expected, 1_813_600, md5, sha))
        listOf(
            Triple(1_813_599L, md5, sha),
            Triple(1_813_600L, "f".repeat(32), sha),
            Triple(1_813_600L, md5, "0".repeat(64)),
        ).forEach { (size, m, s) -> assertEquals(true, EyevueOfficialFirmware.verifyDownload(expected, size, m, s) != null) }
        assertEquals(true, EyevueOfficialFirmware.verifyDownload(expected.copy(size = null), 1_813_600, md5, sha) != null)
        assertEquals(true, EyevueOfficialFirmware.verifyDownload(DownloadExpectation(10, null, null), 10, md5, sha) != null)
    }
}
