package com.fersaiyan.cyanbridge.media.sync

import android.content.Context
import android.net.Network
import android.util.Log
import com.fersaiyan.cyanbridge.media.AdaptiveHttpRoute
import com.fersaiyan.cyanbridge.media.AdaptiveP2pSyncSession
import com.fersaiyan.cyanbridge.media.AdaptiveSyncCheckpoint
import com.fersaiyan.cyanbridge.network.GlassesNetworkSessionCoordinator
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import com.fersaiyan.cyanbridge.shared.network.GlassesNetworkAddressing
import com.fersaiyan.cyanbridge.shared.network.session.GlassesLinkClaim
import com.fersaiyan.cyanbridge.ui.bleIpBridge
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Media-sync HTTP over the glasses link: the official (system-routed) and adaptive route
 * variants. Network lookup, process binding and VPN-safe sockets come from
 * [GlassesNetworkSessionCoordinator], which owns them for every glasses workflow.
 */
internal class GlassesP2pNetwork(
    private val context: Context,
    private val linkClaim: () -> GlassesLinkClaim?,
    private val downloadFlowMode: () -> GlassesSyncFlow,
    private val adaptiveActiveRoute: () -> AdaptiveHttpRoute?,
    private val adaptiveSyncSession: () -> AdaptiveP2pSyncSession?,
    private val downloadBleIp: () -> String?,
    private val downloadWifiIp: () -> String?,
) {
    var downloadP2pNetwork: Network? = null
    var selectedDownloadNetworkSummary = "none"
    private val routes = GlassesNetworkSessionCoordinator.routes(context)

    data class AdaptiveMediaConfigFetch(
        val content: String? = null,
        val serverReached: Boolean = false,
        val statusCode: Int? = null,
        val failureDetail: String = "unknown failure",
    )

    fun fetchAdaptiveMediaConfig(ip: String, route: AdaptiveHttpRoute): AdaptiveMediaConfigFetch {
        val url = URL("http://$ip/files/media.config")
        return try {
            when (route) {
                AdaptiveHttpRoute.P2P_NETWORK -> {
                    val network = findLikelyP2pNetwork() ?: downloadP2pNetwork
                        ?: return AdaptiveMediaConfigFetch(failureDetail = "verified P2P network unavailable")
                    downloadP2pNetwork = network
                    adaptiveSyncSession()?.mark(
                        AdaptiveSyncCheckpoint.P2P_NETWORK_IDENTIFIED,
                        selectedDownloadNetworkSummary,
                    )
                    val connection = network.openConnection(url) as HttpURLConnection
                    readAdaptiveMediaConfigConnection(connection, route)
                }
                AdaptiveHttpRoute.LOCAL_ADDRESS -> {
                    val client = vpnSafeHttpClient(connectTimeoutMs = 3_000, readTimeoutMs = 4_000)
                        ?: return AdaptiveMediaConfigFetch(failureDetail = "P2P local address unavailable")
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .header("Connection", "close")
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        val status = response.code
                        adaptiveSyncSession()?.mark(
                            AdaptiveSyncCheckpoint.TCP_80_CONNECTED,
                            "route=${route.name},status=$status",
                        )
                        if (!response.isSuccessful) {
                            AdaptiveMediaConfigFetch(
                                serverReached = true,
                                statusCode = status,
                                failureDetail = "HTTP $status",
                            )
                        } else {
                            val body = response.body
                                ?: return@use AdaptiveMediaConfigFetch(
                                    serverReached = true,
                                    statusCode = status,
                                    failureDetail = "HTTP $status without a body",
                                )
                            val expected = body.contentLength()
                            val bytes = body.bytes()
                            if (expected >= 0L && bytes.size.toLong() != expected) {
                                AdaptiveMediaConfigFetch(
                                    serverReached = true,
                                    statusCode = status,
                                    failureDetail = "truncated body ${bytes.size}/$expected bytes",
                                )
                            } else {
                                AdaptiveMediaConfigFetch(
                                    content = bytes.toString(Charsets.UTF_8),
                                    serverReached = true,
                                    statusCode = status,
                                    failureDetail = "complete",
                                )
                            }
                        }
                    }
                }
                AdaptiveHttpRoute.SYSTEM -> {
                    // System routing is the final compatibility fallback. Ensure a previously
                    // learned process binding does not make it identical to the P2P route.
                    unbindProcessFromNetwork()
                    val connection = url.openConnection() as HttpURLConnection
                    readAdaptiveMediaConfigConnection(connection, route)
                }
            }
        } catch (e: Exception) {
            val detail = e.message?.take(180) ?: e::class.java.simpleName
            Log.w("DataDownload", "Adaptive media.config failed: route=$route ip=$ip detail=$detail")
            AdaptiveMediaConfigFetch(
                serverReached = detail.contains("unexpected end of stream", ignoreCase = true),
                failureDetail = detail,
            )
        }
    }

    private fun readAdaptiveMediaConfigConnection(
        connection: HttpURLConnection,
        route: AdaptiveHttpRoute,
    ): AdaptiveMediaConfigFetch {
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 3_000
            connection.readTimeout = 4_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Connection", "close")
            val status = connection.responseCode
            adaptiveSyncSession()?.mark(
                AdaptiveSyncCheckpoint.TCP_80_CONNECTED,
                "route=${route.name},status=$status",
            )
            if (status != HttpURLConnection.HTTP_OK) {
                AdaptiveMediaConfigFetch(
                    serverReached = true,
                    statusCode = status,
                    failureDetail = "HTTP $status",
                )
            } else {
                val expected = connection.contentLengthLong
                val bytes = connection.inputStream.use(InputStream::readBytes)
                if (expected >= 0L && bytes.size.toLong() != expected) {
                    AdaptiveMediaConfigFetch(
                        serverReached = true,
                        statusCode = status,
                        failureDetail = "truncated body ${bytes.size}/$expected bytes",
                    )
                } else {
                    AdaptiveMediaConfigFetch(
                        content = bytes.toString(Charsets.UTF_8),
                        serverReached = true,
                        statusCode = status,
                        failureDetail = "complete",
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttpConnection(url: URL): HttpURLConnection? {
        val network = downloadP2pNetwork ?: findLikelyP2pNetwork()?.also { downloadP2pNetwork = it }
        if (network != null) {
            try {
                val conn = network.openConnection(url) as HttpURLConnection
                conn.instanceFollowRedirects = true
                return conn
            } catch (_: Exception) {}
        }
        return try {
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn
        } catch (_: Exception) { null }
    }

    private fun openPlainHttpConnection(url: URL): HttpURLConnection? {
        return try {
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn
        } catch (_: Exception) { null }
    }

    /** An OkHttp client whose sockets bind to the P2P local address (VPN-proof). */
    private fun vpnSafeHttpClient(connectTimeoutMs: Int, readTimeoutMs: Int): okhttp3.OkHttpClient? =
        routes.localAddressBoundHttpClient(downloadP2pNetwork, connectTimeoutMs, readTimeoutMs)

    /**
     * HTTP GET using P2P-bound sockets (VPN-safe).
     * Tries Network.openConnection() first, then OkHttp with P2P local-address binding.
     */
    fun httpGet(
        url: URL,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        onStream: ((InputStream, Long) -> Unit)? = null
    ): Boolean {
        if (downloadFlowMode() == GlassesSyncFlow.CUSTOM) {
            adaptiveActiveRoute()?.let { route ->
                return httpGetViaAdaptiveRoute(url, route, connectTimeoutMs, readTimeoutMs, onStream)
            }
        }
        if (downloadFlowMode() == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
            return try {
                val conn = openPlainHttpConnection(url) ?: return false
                conn.requestMethod = "GET"
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    onStream?.invoke(conn.inputStream, conn.contentLengthLong)
                    conn.disconnect()
                    true
                } else {
                    conn.disconnect()
                    false
                }
            } catch (e: Exception) {
                Log.w("DataDownload", "Official flow plain httpGet failed for $url: ${e.message}")
                false
            }
        }

        try {
            val conn = openHttpConnection(url) ?: return false
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                onStream?.invoke(conn.inputStream, conn.contentLengthLong)
                conn.disconnect()
                return true
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w("DataDownload", "httpGet default path failed for $url: ${e.message}")
        }

        val client = vpnSafeHttpClient(connectTimeoutMs, readTimeoutMs) ?: return false
        return try {
            val request = okhttp3.Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful && resp.body != null) {
                    onStream?.invoke(resp.body!!.byteStream(), resp.body!!.contentLength())
                    true
                } else false
            }
        } catch (e: Exception) {
            Log.w("DataDownload", "P2P-bound httpGet fallback failed for $url: ${e.message}")
            false
        }
    }

    private fun httpGetViaAdaptiveRoute(
        url: URL,
        route: AdaptiveHttpRoute,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        onStream: ((InputStream, Long) -> Unit)?,
    ): Boolean {
        return try {
            when (route) {
                AdaptiveHttpRoute.P2P_NETWORK -> {
                    val network = downloadP2pNetwork ?: findLikelyP2pNetwork() ?: return false
                    val connection = network.openConnection(url) as HttpURLConnection
                    try {
                        connection.requestMethod = "GET"
                        connection.connectTimeout = connectTimeoutMs
                        connection.readTimeout = readTimeoutMs
                        connection.setRequestProperty("Connection", "close")
                        if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
                        onStream?.invoke(connection.inputStream, connection.contentLengthLong)
                        true
                    } finally {
                        connection.disconnect()
                    }
                }
                AdaptiveHttpRoute.LOCAL_ADDRESS -> {
                    val client = vpnSafeHttpClient(connectTimeoutMs, readTimeoutMs) ?: return false
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .header("Connection", "close")
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body
                        if (!response.isSuccessful || body == null) return@use false
                        onStream?.invoke(body.byteStream(), body.contentLength())
                        true
                    }
                }
                AdaptiveHttpRoute.SYSTEM -> {
                    val connection = url.openConnection() as HttpURLConnection
                    try {
                        connection.requestMethod = "GET"
                        connection.connectTimeout = connectTimeoutMs
                        connection.readTimeout = readTimeoutMs
                        connection.setRequestProperty("Connection", "close")
                        if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
                        onStream?.invoke(connection.inputStream, connection.contentLengthLong)
                        true
                    } finally {
                        connection.disconnect()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("DataDownload", "Adaptive $route GET failed for $url: ${e.message}")
            false
        }
    }

    fun findLikelyP2pNetwork(): Network? {
        // We want a network whose sockets route to the Wi-Fi Direct group even when a VPN is active.
        return try {
            val prefixHints = GlassesNetworkAddressing.subnetHints(
                downloadBleIp(),
                bleIpBridge.ip.value,
                downloadWifiIp(),
            )
            val match = routes.findVerifiedP2pNetwork(prefixHints)
            if (match != null) {
                selectedDownloadNetworkSummary = match.summary
                Log.i("DataDownload", "Selected verified P2P/WFD network: $selectedDownloadNetworkSummary")
                return match.network
            }

            selectedDownloadNetworkSummary = "none (ordinary Wi-Fi fallback rejected)"
            Log.i("DataDownload", "No verified P2P/WFD Network exposed; leaving process routing unchanged")
            null
        } catch (e: Exception) {
            selectedDownloadNetworkSummary = "lookup failed: ${e.message.orEmpty()}"
            Log.w("DataDownload", "Failed to locate P2P network: ${e.message}")
            null
        }
    }

    fun bindProcessToNetwork(network: Network?) {
        if (network == null) return
        val claim = linkClaim() ?: return
        if (GlassesNetworkSessionCoordinator.isProcessBound(claim)) return

        // A VPN usually refuses process binding (EPERM); a bypassable one accepts it. Either way
        // the adaptive routes keep binding individual sockets to the P2P local address.
        if (isVpnActive()) {
            Log.i("DataDownload", "VPN active — attempting bindProcessToNetwork; P2P-local socket binding remains the fallback")
        }
        if (GlassesNetworkSessionCoordinator.bindProcessToNetwork(context, claim, network)) {
            Log.i("DataDownload", "Bound process to P2P network")
        } else {
            Log.w("DataDownload", "bindProcessToNetwork returned false")
        }
    }

    fun unbindProcessFromNetwork() {
        val claim = linkClaim() ?: return
        GlassesNetworkSessionCoordinator.unbindProcess(context, claim)
    }

    fun isVpnActive(): Boolean = routes.isVpnActive()
}
