package com.fersaiyan.cyanbridge.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.fersaiyan.cyanbridge.shared.network.GlassesNetworkAddressing
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.Dns
import okhttp3.OkHttpClient

/** A non-VPN Wi-Fi network that carries the glasses link. */
data class GlassesP2pNetworkMatch(
    val network: Network,
    val interfaceName: String,
    val addresses: List<String>,
) {
    val summary: String get() = "if=$interfaceName addrs=$addresses"
}

/**
 * Stateless Android route lookups and socket factories for the glasses link. Process binding is
 * stateful and lives in [GlassesNetworkSessionCoordinator]; everything here may be called from
 * any thread.
 */
class AndroidGlassesNetworkRoutes(context: Context) {
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * The first non-VPN Wi-Fi network that [GlassesNetworkAddressing.isVerifiedP2pNetwork]
     * accepts for [subnetHints]. Ordinary Wi-Fi is never returned. Throws when the platform
     * refuses to enumerate networks.
     */
    @Suppress("DEPRECATION")
    fun findVerifiedP2pNetwork(subnetHints: Collection<String>): GlassesP2pNetworkMatch? {
        for (network in connectivity.allNetworks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            // Wi-Fi Direct networks show up as TRANSPORT_WIFI; a VPN shows up as TRANSPORT_VPN.
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val properties = connectivity.getLinkProperties(network)
            val interfaceName = properties?.interfaceName.orEmpty()
            val addresses = properties?.linkAddresses?.mapNotNull { it.address.hostAddress }.orEmpty()
            if (GlassesNetworkAddressing.isVerifiedP2pNetwork(interfaceName, addresses, subnetHints)) {
                return GlassesP2pNetworkMatch(network, interfaceName, addresses)
            }
        }
        return null
    }

    /** The network whose interface is exactly [interfaceName] (a Wi-Fi Direct group interface). */
    @Suppress("DEPRECATION")
    fun findNetworkByInterface(interfaceName: String): Network? {
        return connectivity.allNetworks.firstOrNull {
            connectivity.getLinkProperties(it)?.interfaceName == interfaceName
        }
    }

    @Suppress("DEPRECATION")
    fun isVpnActive(): Boolean {
        return try {
            connectivity.allNetworks.any { network ->
                connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun linkAddresses(network: Network): List<String> =
        connectivity.getLinkProperties(network)?.linkAddresses?.mapNotNull { it.address.hostAddress }.orEmpty()

    fun interfaceName(network: Network): String? = connectivity.getLinkProperties(network)?.interfaceName

    /** The network's first IPv4 local address (for example `192.168.49.1`). */
    fun firstIpv4LocalAddress(network: Network?): InetAddress? {
        network ?: return null
        return try {
            connectivity.getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address }
                ?.firstOrNull { it is Inet4Address }
        } catch (_: Exception) {
            null
        }
    }

    /** Sockets bound to the Wi-Fi Direct local address, which bypasses VPN routing. */
    fun localAddressBoundSocketFactory(localAddress: InetAddress): SocketFactory =
        object : SocketFactory() {
            override fun createSocket(): Socket {
                val socket = Socket()
                socket.bind(InetSocketAddress(localAddress, 0))
                return socket
            }

            override fun createSocket(host: String, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) =
                throw UnsupportedOperationException()
            override fun createSocket(host: InetAddress, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int) =
                throw UnsupportedOperationException()
        }

    /** An OkHttp client whose sockets bind to [network]'s IPv4 local address (VPN-safe). */
    fun localAddressBoundHttpClient(
        network: Network?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): OkHttpClient? {
        val localAddress = firstIpv4LocalAddress(network) ?: return null
        return try {
            OkHttpClient.Builder()
                .socketFactory(localAddressBoundSocketFactory(localAddress))
                .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        } catch (_: Exception) {
            null
        }
    }

    /** [base] with sockets and DNS routed through [network] without binding the process. */
    fun networkBoundHttpClient(base: OkHttpClient, network: Network): OkHttpClient =
        base.newBuilder()
            .connectionPool(okhttp3.ConnectionPool())
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
            })
            .build()

    /**
     * TCP reachability of [ip]:[port]. Uses [network]'s socket factory (or the default route),
     * then retries from [network]'s local address so an active VPN cannot swallow the probe.
     */
    fun isPortOpen(ip: String, port: Int, timeoutMs: Int, network: Network? = null): Boolean {
        try {
            val factory = network?.socketFactory ?: SocketFactory.getDefault()
            factory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                return true
            }
        } catch (_: Exception) {
        }
        val localAddress = firstIpv4LocalAddress(network) ?: return false
        return try {
            Socket().use { socket ->
                socket.bind(InetSocketAddress(localAddress, 0))
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
