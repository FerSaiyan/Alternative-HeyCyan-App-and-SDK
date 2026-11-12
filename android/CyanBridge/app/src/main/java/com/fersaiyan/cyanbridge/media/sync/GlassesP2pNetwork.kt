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
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Media-sync HTTP over the glasses link: the official (system-routed), custom (P2P network,
 * then VPN-safe sockets) and adaptive route variants; custom and adaptive transfers force the
 * VPN-safe route while a VPN is up and resume with Range. Network lookup, process binding and VPN-safe sockets come from
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
        // VPN-safe route + Range-resume (add.210) cover both non-official flows; the HeyCyan
        // app flow below stays system-routed like the stock app.
        when (downloadFlowMode()) {
            GlassesSyncFlow.ADAPTIVE -> adaptiveActiveRoute()?.let { requestedRoute ->
                val route = vpnHardenedRoute(requestedRoute, connectTimeoutMs, readTimeoutMs)
                return httpGetViaAdaptiveRoute(url, listOf(route), connectTimeoutMs, readTimeoutMs, onStream)
            }
            GlassesSyncFlow.CUSTOM -> if (onStream != null) {
                // Transfers only; the v2.3.0 resolver's probes (no stream) keep the default path.
                val primary = vpnHardenedRoute(AdaptiveHttpRoute.P2P_NETWORK, connectTimeoutMs, readTimeoutMs)
                return httpGetViaAdaptiveRoute(url, customDownloadRoutes(primary), connectTimeoutMs, readTimeoutMs, onStream)
            }
            GlassesSyncFlow.OFFICIAL_HEYCYAN -> Unit
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

    /**
     * An active VPN can capture SYSTEM routing and reset the P2P network mid-transfer; only a
     * local-address-bound socket is VPN-proof, so it is forced while a VPN is up (add.210).
     */
    private fun vpnHardenedRoute(
        requestedRoute: AdaptiveHttpRoute,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): AdaptiveHttpRoute {
        val vpnActive = isVpnActive()
        val route = vpnSafeCustomRoute(
            requested = requestedRoute,
            vpnActive = vpnActive,
            vpnSafeClientAvailable = vpnActive && vpnSafeHttpClient(connectTimeoutMs, readTimeoutMs) != null,
        )
        if (route != requestedRoute) {
            Log.i(
                "DataDownload",
                "VPN active — forcing VPN-safe $route for ${downloadFlowMode()} download (requested $requestedRoute)",
            )
        }
        return route
    }

    /**
     * Opens the transfer on the first of [routes] that answers, then streams it with
     * Range-resume on that route.
     */
    private fun httpGetViaAdaptiveRoute(
        url: URL,
        routes: List<AdaptiveHttpRoute>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        onStream: ((InputStream, Long) -> Unit)?,
    ): Boolean {
        var route = routes.first()
        return try {
            var initial: RangedResponse? = null
            for (candidate in routes) {
                route = candidate
                initial = try {
                    openRangedHttp(url, candidate, 0L, connectTimeoutMs, readTimeoutMs)
                } catch (e: IOException) {
                    Log.w("DataDownload", "$candidate GET could not open $url: ${e.message}")
                    null
                }
                if (initial != null) break
            }
            if (initial == null) return false
            if (onStream == null) {
                initial.chunk.close()
                return true
            }
            // A fresh (non-ranged) request answers 200 with the full length; the firmware
            // may still truncate or reset it, so hand the consumer a stream that transparently
            // resumes with Range: bytes=<delivered>- (206 Partial Content) up to the budget.
            val totalExpected = if (initial.chunk.partial) -1L else initial.chunkLength
            val resuming = ResumingHttpStream(
                totalExpected = totalExpected,
                initial = initial.chunk,
                onResume = { attempt, offset ->
                    adaptiveSyncSession()?.mark(
                        AdaptiveSyncCheckpoint.MEDIA_FILE_RETRY,
                        "resume=$attempt,offset=$offset,total=$totalExpected,route=${route.name}",
                    )
                },
                openFrom = { rangeStart ->
                    openRangedHttp(url, route, rangeStart, connectTimeoutMs, readTimeoutMs)?.chunk
                },
            )
            resuming.use { stream ->
                onStream.invoke(stream, totalExpected)
                stream.completedFully()
            }
        } catch (e: Exception) {
            Log.w("DataDownload", "${downloadFlowMode()} $route GET failed for $url: ${e.message}")
            false
        }
    }

    /** TCP reachability over the P2P network, retried from its local address (VPN-safe). */
    fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean =
        routes.isPortOpen(ip, port, timeoutMs, downloadP2pNetwork)

    /** The phone's IPv4 address on the Wi-Fi Direct interface, for the Custom flow's subnet hint. */
    fun p2pInterfaceIpv4(): String? {
        val network = downloadP2pNetwork ?: findLikelyP2pNetwork() ?: return null
        return runCatching { routes.linkAddresses(network) }.getOrNull()
            ?.firstOrNull(GlassesNetworkAddressing::isIpv4)
    }

    private class RangedResponse(val chunk: RangedChunk, val chunkLength: Long)

    /**
     * Opens one HTTP response for [route], optionally resuming from [rangeStart]. A positive
     * [rangeStart] sends `Range: bytes=<rangeStart>-` and accepts 206 Partial Content. Returns
     * null when the route is unavailable or the server answers an unusable status.
     */
    private fun openRangedHttp(
        url: URL,
        route: AdaptiveHttpRoute,
        rangeStart: Long,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): RangedResponse? {
        return when (route) {
            AdaptiveHttpRoute.P2P_NETWORK -> {
                val network = downloadP2pNetwork ?: findLikelyP2pNetwork() ?: return null
                httpUrlRanged(network.openConnection(url) as HttpURLConnection, rangeStart, connectTimeoutMs, readTimeoutMs)
            }
            AdaptiveHttpRoute.SYSTEM -> {
                httpUrlRanged(url.openConnection() as HttpURLConnection, rangeStart, connectTimeoutMs, readTimeoutMs)
            }
            AdaptiveHttpRoute.LOCAL_ADDRESS -> {
                val client = vpnSafeHttpClient(connectTimeoutMs, readTimeoutMs) ?: return null
                val builder = okhttp3.Request.Builder()
                    .url(url)
                    .header("Connection", "close")
                if (rangeStart > 0L) builder.header("Range", "bytes=$rangeStart-")
                val response = client.newCall(builder.get().build()).execute()
                val body = response.body
                if (body == null || (!response.isSuccessful && response.code != HttpURLConnection.HTTP_PARTIAL)) {
                    response.close()
                    return null
                }
                RangedResponse(
                    chunk = RangedChunk(body.byteStream(), response.code == HttpURLConnection.HTTP_PARTIAL) {
                        response.close()
                    },
                    chunkLength = body.contentLength(),
                )
            }
        }
    }

    private fun httpUrlRanged(
        connection: HttpURLConnection,
        rangeStart: Long,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): RangedResponse? {
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Connection", "close")
            if (rangeStart > 0L) connection.setRequestProperty("Range", "bytes=$rangeStart-")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect()
                null
            } else {
                RangedResponse(
                    chunk = RangedChunk(connection.inputStream, status == HttpURLConnection.HTTP_PARTIAL) {
                        connection.disconnect()
                    },
                    chunkLength = connection.contentLengthLong,
                )
            }
        } catch (e: Exception) {
            connection.disconnect()
            null
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

/** One HTTP response chunk for a (possibly ranged) Custom/Adaptive-flow download. */
internal class RangedChunk(
    val stream: InputStream,
    /** The server answered 206 Partial Content (its body starts at the requested offset). */
    val partial: Boolean,
    private val onClose: () -> Unit,
) {
    fun close() {
        runCatching { onClose() }
    }
}

/**
 * Presents a single continuous stream over a Custom/Adaptive-flow download that the glasses firmware
 * may truncate or reset mid-transfer. When the underlying chunk ends (or throws) before
 * [totalExpected] bytes have been delivered, it reopens the transfer from the current offset
 * via [openFrom] — which issues `Range: bytes=<delivered>-` and accepts 206 — and keeps going,
 * up to [maxResumeAttempts] times. A server that ignores Range and restarts from zero (non-206)
 * is tolerated by skipping the bytes already delivered, so the consumer never sees a duplicate
 * or a short read where a resume was still possible. [totalExpected] < 0 disables resuming
 * (unknown length), matching the pre-fix behaviour.
 */
internal class ResumingHttpStream(
    private val totalExpected: Long,
    initial: RangedChunk,
    private val maxResumeAttempts: Int = DEFAULT_MAX_RESUME_ATTEMPTS,
    private val onResume: ((attempt: Int, offset: Long) -> Unit)? = null,
    private val openFrom: (rangeStart: Long) -> RangedChunk?,
) : InputStream() {
    private var current: RangedChunk? = initial
    private var delivered: Long = 0L
    var resumeAttempts: Int = 0
        private set

    fun completedFully(): Boolean = totalExpected < 0L || delivered >= totalExpected

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            val cur = current ?: return -1
            val n = try {
                cur.stream.read(b, off, len)
            } catch (io: IOException) {
                if (resume()) continue else return -1
            }
            if (n > 0) {
                delivered += n
                return n
            }
            // Underlying chunk ended: genuine EOF only once the whole body is delivered.
            if (completedFully()) return -1
            if (!resume()) return -1
        }
    }

    private fun resume(): Boolean {
        if (completedFully() || resumeAttempts >= maxResumeAttempts) return false
        resumeAttempts++
        current?.close()
        current = null
        onResume?.invoke(resumeAttempts, delivered)
        val next = openFrom(delivered) ?: return false
        // Server ignored Range and restarted from 0 → drop the bytes we already delivered.
        if (!next.partial && delivered > 0L) {
            try {
                skipFully(next.stream, delivered)
            } catch (e: IOException) {
                next.close()
                return false
            }
        }
        current = next
        return true
    }

    override fun close() {
        current?.close()
        current = null
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(32 * 1024)
        while (remaining > 0L) {
            val toRead = minOf(remaining, scratch.size.toLong()).toInt()
            val read = stream.read(scratch, 0, toRead)
            if (read < 0) throw EOFException("could not skip $count bytes to resume")
            remaining -= read
        }
    }

    companion object {
        const val DEFAULT_MAX_RESUME_ATTEMPTS = 8
    }
}

/**
 * Custom/Adaptive-flow route hardening. A VPN can capture SYSTEM routing (`url.openConnection()`) and
 * reset [AdaptiveHttpRoute.P2P_NETWORK] mid-transfer; only a local-address-bound socket is
 * VPN-proof. When a VPN is active, force [AdaptiveHttpRoute.LOCAL_ADDRESS] as long as a
 * VPN-safe client is available; otherwise leave the requested route unchanged.
 */
internal fun vpnSafeCustomRoute(
    requested: AdaptiveHttpRoute,
    vpnActive: Boolean,
    vpnSafeClientAvailable: Boolean,
): AdaptiveHttpRoute = when {
    requested == AdaptiveHttpRoute.LOCAL_ADDRESS -> requested
    vpnActive && vpnSafeClientAvailable -> AdaptiveHttpRoute.LOCAL_ADDRESS
    else -> requested
}

/**
 * Custom-flow transfer routes in v2.3.0 order (P2P network, then system routing, which the
 * process binding on group formation points at the group, then P2P-local sockets), with
 * [primary] (VPN-safe when a VPN is up) tried first.
 */
internal fun customDownloadRoutes(primary: AdaptiveHttpRoute): List<AdaptiveHttpRoute> =
    (listOf(primary) + listOf(
        AdaptiveHttpRoute.P2P_NETWORK,
        AdaptiveHttpRoute.SYSTEM,
        AdaptiveHttpRoute.LOCAL_ADDRESS,
    )).distinct()
