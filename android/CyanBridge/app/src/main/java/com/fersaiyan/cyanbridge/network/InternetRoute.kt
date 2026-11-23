package com.fersaiyan.cyanbridge.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import javax.net.SocketFactory
import okhttp3.Dns

/**
 * Internet traffic that must keep working while a glasses link binds the whole process to its
 * Wi-Fi Direct / hotspot network ([GlassesNetworkSessionCoordinator.bindProcessToNetwork], e.g. the
 * HeyCyan live video Gemini Live uses). Such a network has no internet, so an unbound socket fails
 * its DNS lookup. These helpers open sockets on the system's internet network instead; with no
 * binding in place that is the same network the process uses anyway.
 */
object InternetRoute {
    /** The validated default network with internet, or any network with internet; null when offline. */
    fun network(context: Context): Network? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        fun usable(network: Network?): Boolean = network != null &&
            connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        connectivity.activeNetwork.takeIf(::usable)?.let { return it }
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.firstOrNull { network ->
            usable(network) && connectivity.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
    }

    /** [URL.openConnection] on the internet network (falls back to the process default). */
    fun openConnection(context: Context, url: URL): URLConnection =
        network(context)?.openConnection(url) ?: url.openConnection()

    /** For OkHttp: sockets on the internet network, chosen per connection. */
    fun socketFactory(context: Context): SocketFactory = object : SocketFactory() {
        private val appContext = context.applicationContext
        private fun base(): SocketFactory = network(appContext)?.socketFactory ?: getDefault()
        override fun createSocket(): Socket = base().createSocket()
        override fun createSocket(host: String, port: Int): Socket = base().createSocket(host, port)
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            base().createSocket(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress, port: Int): Socket = base().createSocket(host, port)
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            base().createSocket(address, port, localAddress, localPort)
    }

    /** For OkHttp: DNS on the internet network (the bound glasses network has no resolver for it). */
    fun dns(context: Context): Dns {
        val appContext = context.applicationContext
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                network(appContext)?.getAllByName(hostname)?.toList() ?: Dns.SYSTEM.lookup(hostname)
        }
    }
}
