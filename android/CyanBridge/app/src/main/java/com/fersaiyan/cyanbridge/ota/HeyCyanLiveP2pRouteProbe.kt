package com.fersaiyan.cyanbridge.ota

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Verify Android's local P2P routing when it rejects whole-process binding. */
internal object HeyCyanLiveP2pRouteProbe {
    data class Route(val port: Int, val localAddress: String)

    fun findRoute(
        glassesIp: String,
        ports: IntArray,
        p2pLocalAddresses: Set<String>,
        timeoutMs: Int = 1_000,
        socketFactory: () -> Socket = { Socket() },
    ): Route? {
        if (p2pLocalAddresses.isEmpty()) return null
        for (port in ports) {
            try {
                socketFactory().use { socket ->
                    socket.connect(InetSocketAddress(glassesIp, port), timeoutMs)
                    val local = socket.localAddress?.hostAddress
                    // An open port alone is insufficient: the socket must use
                    // an address on the verified Wi-Fi Direct interface.
                    if (local != null && local in p2pLocalAddresses) return Route(port, local)
                }
            } catch (_: IOException) {
                // The camera server may still be starting. Caller owns retries.
            }
        }
        return null
    }
}
