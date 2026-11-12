package com.fersaiyan.cyanbridge.media.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The restored v2.3.0 Custom flow resolver: what it probes, in which order, and when it scans. */
class CustomHttpResolverTest {
    private fun inputs(
        bleIp: String? = null,
        bridgeIp: String? = null,
        groupOwnerIp: String? = null,
        phoneIsGroupOwner: Boolean? = null,
        p2pConnected: Boolean = true,
    ) = CustomResolverInputs(bleIp, bridgeIp, groupOwnerIp, phoneIsGroupOwner, p2pConnected)

    @Test
    fun `probes reported addresses first, then subnet guesses, never the phone's own group-owner address`() {
        val cases = listOf(
            // Glasses own the group: .1 is the glasses and stays a target.
            inputs(bleIp = "192.168.49.79", groupOwnerIp = "192.168.49.1", phoneIsGroupOwner = false) to
                listOf("192.168.49.79", "192.168.49.1", "192.168.49.2", "192.168.49.3"),
            // The phone owns the group: 192.168.49.1 is the phone and is skipped.
            inputs(bleIp = "192.168.49.2", bridgeIp = "192.168.49.2", groupOwnerIp = "192.168.49.1", phoneIsGroupOwner = true) to
                listOf("192.168.49.2", "192.168.49.79", "192.168.49.3"),
            // The bridge IP picks the subnet when BLE has not reported yet.
            inputs(bridgeIp = "10.0.7.20", groupOwnerIp = "192.168.49.1", phoneIsGroupOwner = false) to
                listOf("10.0.7.20", "192.168.49.1", "10.0.7.1", "10.0.7.79", "10.0.7.2", "10.0.7.3"),
        )
        cases.forEach { (state, expected) ->
            val prefix = CustomHttpResolver.subnetPrefix(state) { "172.16.0.5" }
            assertEquals(expected, CustomHttpResolver.probeTargets(state, prefix))
        }
        // The Wi-Fi Direct interface address is the last subnet hint.
        assertEquals("172.16.0.", CustomHttpResolver.subnetPrefix(inputs()) { "172.16.0.5" })
        assertEquals(253, CustomHttpResolver.scanHosts("192.168.49.", phoneIsGroupOwner = true).size)
        assertFalse("192.168.49.1" in CustomHttpResolver.scanHosts("192.168.49.", phoneIsGroupOwner = true))
        assertEquals(254, CustomHttpResolver.scanHosts("192.168.49.", phoneIsGroupOwner = false).size)
    }

    @Test
    fun `scans the subnet once, on a formed group, only while no glasses IP is known`() {
        assertTrue(CustomHttpResolver.shouldScanSubnet(alreadyScanned = false, inputs(groupOwnerIp = "192.168.49.1")))
        assertFalse(CustomHttpResolver.shouldScanSubnet(alreadyScanned = true, inputs()))
        assertFalse(CustomHttpResolver.shouldScanSubnet(alreadyScanned = false, inputs(p2pConnected = false)))
        assertFalse(CustomHttpResolver.shouldScanSubnet(alreadyScanned = false, inputs(bleIp = "192.168.49.79")))
        assertFalse(CustomHttpResolver.shouldScanSubnet(alreadyScanned = false, inputs(bridgeIp = "192.168.49.79")))
        assertEquals(45_000L, CustomHttpResolver.OVERALL_TIMEOUT_MS)
    }
}
