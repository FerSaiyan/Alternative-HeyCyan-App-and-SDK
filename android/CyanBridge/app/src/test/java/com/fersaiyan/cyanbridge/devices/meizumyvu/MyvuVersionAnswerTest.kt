package com.fersaiyan.cyanbridge.devices.meizumyvu

import com.myvu.client.core.Hex
import com.myvu.client.transport.ble.BlePairing
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The glasses' answer to the BLE version negotiation (real org.json needs Robolectric). */
@RunWith(RobolectricTestRunner::class)
class MyvuVersionAnswerTest {
    @Test
    fun `reads the encryption mode from the version answer`() {
        // The captured answer (CapturedFrames.F483 without its 4-byte header).
        val captured = """{"i":"2C6F4E00DC47","v":4,"e":1,"m":512,"b":2}"""
        val cases = listOf(
            "captured answer" to captured.toByteArray() to 1,
            // 41 bytes: the cut that gives the 104-character JSONException in the 2.4.3 report.
            "answer cut off after 41 bytes" to captured.take(41).toByteArray() to 1,
            "answer without e defaults to GCM" to """{"v":4}""".toByteArray() to 3,
            "LinkProtocol frame is not the answer" to Hex.decode("0a060e6b2f8a5c831049") to -1,
        )
        for ((input, expected) in cases) {
            val (name, payload) = input
            assertEquals(name, expected, BlePairing.negotiatedEncryptMode(payload))
        }
    }
}
