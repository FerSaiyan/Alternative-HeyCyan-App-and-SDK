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
            "W620_DB3E334C9DA2",
            HeyCyanP2pPolicy.officialWifiDirectName("W620", "DB:3E:33:4C:9D:A2"),
        )
    }

    @Test
    fun `normalizes underscored names like the official receiver`() {
        assertEquals(
            "W620_DB3E334C9DA2",
            HeyCyanP2pPolicy.officialWifiDirectName("Hey_Cyan_W620", "DB:3E:33:4C:9D:A2"),
        )
        assertNull(HeyCyanP2pPolicy.officialWifiDirectName("W620", null))
    }

    @Test
    fun `matches official exact name and mac suffix fallback`() {
        assertTrue(
            HeyCyanP2pPolicy.matchesOfficialPeer(
                "W620_DB3E334C9DA2",
                "W620",
                "DB:3E:33:4C:9D:A2",
            ),
        )
        assertTrue(
            HeyCyanP2pPolicy.matchesOfficialPeer(
                "M01_DB3E334C9DA2",
                "W620",
                "DB:3E:33:4C:9D:A2",
            ),
        )
        assertFalse(
            HeyCyanP2pPolicy.matchesOfficialPeer(
                "[TV] Samsung 4 Series",
                "W620",
                "DB:3E:33:4C:9D:A2",
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
