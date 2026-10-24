package com.fersaiyan.cyanbridge.shared.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlassesNetworkAddressingTest {
    @Test
    fun derivesSlash24PrefixFromFourPartAddresses() {
        assertEquals("192.168.49.", GlassesNetworkAddressing.prefix24("192.168.49.79"))
        assertNull(GlassesNetworkAddressing.prefix24(null))
        assertNull(GlassesNetworkAddressing.prefix24(" "))
        assertNull(GlassesNetworkAddressing.prefix24("fe80::1"))
        assertNull(GlassesNetworkAddressing.prefix24("10.0.0"))
        assertEquals(
            listOf("192.168.49.", "10.0.0."),
            GlassesNetworkAddressing.subnetHints("192.168.49.2", null, "192.168.49.7", "10.0.0.4"),
        )
    }

    @Test
    fun parsesOnlyValidIpv4Literals() {
        assertContentEquals(intArrayOf(192, 168, 49, 1), GlassesNetworkAddressing.parseIpv4("192.168.49.1"))
        assertNull(GlassesNetworkAddressing.parseIpv4("192.168.49.256"))
        assertNull(GlassesNetworkAddressing.parseIpv4("192.168.49"))
        assertNull(GlassesNetworkAddressing.parseIpv4("a.b.c.d"))
        assertFalse(GlassesNetworkAddressing.isIpv4(""))
    }

    @Test
    fun rejectsOrdinaryWifiAndAcceptsVerifiedP2pNetworks() {
        assertFalse(
            GlassesNetworkAddressing.isVerifiedP2pNetwork(
                interfaceName = "wlan0",
                addresses = listOf("192.168.1.210"),
                subnetPrefixes = listOf("192.168.49."),
            ),
        )
        assertTrue(
            GlassesNetworkAddressing.isVerifiedP2pNetwork(
                interfaceName = "p2p-wlan0-0",
                addresses = listOf("192.168.49.1"),
                subnetPrefixes = emptyList(),
            ),
        )
        assertTrue(
            GlassesNetworkAddressing.isVerifiedP2pNetwork(
                interfaceName = "wlan1",
                addresses = listOf("172.16.20.1"),
                subnetPrefixes = listOf("172.16.20."),
            ),
        )
        assertTrue(GlassesNetworkAddressing.isP2pInterfaceName("WFD0"))
        assertFalse(GlassesNetworkAddressing.isP2pInterfaceName(null))
    }

    @Test
    fun selectsThePhoneAddressOnlyFromTheGlassesSubnet() {
        val candidates = listOf(
            "wlan0" to "192.168.1.20",
            "p2p-wlan0-0" to "192.168.50.1",
            "wlan1" to "192.168.49.1",
        )

        assertEquals("192.168.49.1", GlassesNetworkAddressing.selectLocalP2pIpv4("192.168.49.2", candidates))
        assertEquals("192.168.50.1", GlassesNetworkAddressing.selectLocalP2pIpv4("192.168.50.2", candidates))
        assertNull(GlassesNetworkAddressing.selectLocalP2pIpv4("192.168.77.2", candidates))
        assertNull(GlassesNetworkAddressing.selectLocalP2pIpv4("not-an-ip", candidates))
        // Wi-Fi Direct interfaces win over other interfaces on the same subnet.
        assertEquals(
            "192.168.49.9",
            GlassesNetworkAddressing.selectLocalP2pIpv4(
                "192.168.49.2",
                listOf("wlan0" to "192.168.49.5", "p2p0" to "192.168.49.9"),
            ),
        )
    }

    @Test
    fun neverTargetsThePhoneWhenItOwnsTheGroup() {
        assertTrue(GlassesNetworkAddressing.isPhoneGroupOwnerAddress("192.168.49.1", phoneIsGroupOwner = true))
        assertFalse(GlassesNetworkAddressing.isPhoneGroupOwnerAddress("192.168.49.1", phoneIsGroupOwner = false))
        assertFalse(GlassesNetworkAddressing.isPhoneGroupOwnerAddress("192.168.49.1", phoneIsGroupOwner = null))
        assertEquals(
            listOf("192.168.49.79"),
            GlassesNetworkAddressing.glassesHttpTargets(
                listOf("192.168.49.1", null, " ", "192.168.49.79", "192.168.49.79"),
                phoneIsGroupOwner = true,
            ),
        )
    }
}
