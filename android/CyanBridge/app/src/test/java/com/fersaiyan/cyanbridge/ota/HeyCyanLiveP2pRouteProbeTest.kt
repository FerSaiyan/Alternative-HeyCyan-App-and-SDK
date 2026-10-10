package com.fersaiyan.cyanbridge.ota

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeyCyanLiveP2pRouteProbeTest {
    private class ProbeSocket(private val source: String, private val fail: Boolean = false) : Socket() {
        var closed = false
        var target: SocketAddress? = null
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            target = endpoint
            if (fail) throw IOException("Server still starting")
        }
        override fun getLocalAddress(): InetAddress = InetAddress.getByName(source)
        override fun close() { closed = true }
    }

    @Test
    fun `verified samsung group owner route works without process binding`() {
        val socket = ProbeSocket("192.168.49.1")
        val route = HeyCyanLiveP2pRouteProbe.findRoute(
            "192.168.49.40", intArrayOf(8554), setOf("192.168.49.1"), socketFactory = { socket },
        )
        assertEquals(HeyCyanLiveP2pRouteProbe.Route(8554, "192.168.49.1"), route)
        assertEquals(InetSocketAddress("192.168.49.40", 8554), socket.target)
        assertTrue(socket.closed)
    }

    @Test
    fun `reachable endpoint on ordinary wifi is not a verified p2p route`() {
        val socket = ProbeSocket("100.89.35.82")
        assertNull(HeyCyanLiveP2pRouteProbe.findRoute(
            "192.168.49.40", intArrayOf(8554), setOf("192.168.49.1"), socketFactory = { socket },
        ))
        assertTrue(socket.closed)
    }

    @Test
    fun `failed startup probe is closed and firmware port can be verified`() {
        val sockets = listOf(ProbeSocket("192.168.49.1", fail = true), ProbeSocket("192.168.49.1"))
        var index = 0
        val route = HeyCyanLiveP2pRouteProbe.findRoute(
            "192.168.49.40", intArrayOf(8554, 554), setOf("192.168.49.1"),
            socketFactory = { sockets[index++] },
        )
        assertEquals(554, route?.port)
        assertTrue(sockets.all { it.closed })
    }

    @Test
    fun `missing p2p addresses cannot permit probing a default route`() {
        assertNull(HeyCyanLiveP2pRouteProbe.findRoute(
            "192.168.49.40", intArrayOf(8554), emptySet(),
            socketFactory = { error("Unverified interface must not be probed") },
        ))
    }
}
