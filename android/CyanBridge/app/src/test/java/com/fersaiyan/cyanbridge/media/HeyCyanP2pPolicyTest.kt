package com.fersaiyan.cyanbridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeyCyanP2pPolicyTest {
    @Test
    fun `command timeout requires both callback and transfer evidence to be absent`() {
        assertTrue(HeyCyanP2pPolicy.transferCommandTimedOut(false, false))
        assertFalse(HeyCyanP2pPolicy.transferCommandTimedOut(true, false))
        assertFalse(HeyCyanP2pPolicy.transferCommandTimedOut(false, true))
    }

    @Test
    fun `reset callback arrival allows retry despite vendor default error field`() {
        assertTrue(HeyCyanP2pPolicy.resetCallbackAllowsRetry(true, parsedErrorCode = 1))
        assertFalse(HeyCyanP2pPolicy.resetCallbackAllowsRetry(false, parsedErrorCode = 0))
    }

    @Test
    fun `builds the official wifi direct name`() {
        assertEquals(
            "M01_C4E3BFC3A402",
            HeyCyanP2pPolicy.officialWifiDirectName("M01", "C4:E3:BF:C3:A4:02"),
        )
    }

    @Test
    fun `normalizes underscored names like the official receiver`() {
        assertEquals(
            "M01_C4E3BFC3A402",
            HeyCyanP2pPolicy.officialWifiDirectName("Hey_Cyan_M01", "C4:E3:BF:C3:A4:02"),
        )
        assertNull(HeyCyanP2pPolicy.officialWifiDirectName("M01", null))
    }

    @Test
    fun `matches official exact name and mac suffix fallback`() {
        assertTrue(
            HeyCyanP2pPolicy.matchesOfficialPeer(
                "M01_C4E3BFC3A402",
                "M01",
                "C4:E3:BF:C3:A4:02",
            ),
        )
        assertTrue(
            HeyCyanP2pPolicy.matchesOfficialPeer(
                "M01_C4E3BFC3A402",
                "A03",
                "C4:E3:BF:C3:A4:02",
            ),
        )
        assertFalse(
            HeyCyanP2pPolicy.matchesOfficialPeer(
                "[TV] Samsung 4 Series",
                "M01",
                "C4:E3:BF:C3:A4:02",
            ),
        )
    }

    @Test
    fun `rejects ordinary wifi and accepts verified p2p networks`() {
        assertFalse(
            HeyCyanP2pPolicy.isVerifiedP2pNetwork(
                interfaceName = "wlan0",
                addresses = listOf("192.168.1.210"),
                subnetPrefixes = listOf("192.168.49."),
            ),
        )
        assertTrue(
            HeyCyanP2pPolicy.isVerifiedP2pNetwork(
                interfaceName = "p2p-wlan0-0",
                addresses = listOf("192.168.49.1"),
                subnetPrefixes = emptyList(),
            ),
        )
        assertTrue(
            HeyCyanP2pPolicy.isVerifiedP2pNetwork(
                interfaceName = "wlan1",
                addresses = listOf("172.16.20.1"),
                subnetPrefixes = listOf("172.16.20."),
            ),
        )
    }
}
