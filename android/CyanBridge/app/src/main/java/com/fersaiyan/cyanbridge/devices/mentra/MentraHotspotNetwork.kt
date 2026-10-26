package com.fersaiyan.cyanbridge.devices.mentra

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.fersaiyan.cyanbridge.network.GlassesNetworkSessionCoordinator
import com.fersaiyan.cyanbridge.shared.network.session.GlassesLinkClaim
import com.fersaiyan.cyanbridge.shared.network.session.GlassesLinkClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * The phone's join to the Mentra Live hotspot, scoped to this app the way the MentraOS app does
 * it: a `WifiNetworkSpecifier` request without `NET_CAPABILITY_INTERNET`, so Android never makes
 * the hotspot the default network, and the process is never bound to it. Only sockets created
 * from [join]'s [Network] (see `AndroidGlassesNetworkRoutes.networkBoundHttpClient`) use it.
 *
 * The glasses link is claimed from [GlassesNetworkSessionCoordinator] first, so the join never
 * overlaps a HeyCyan Wi-Fi Direct group, an Eyevue or TuneBuds transport or Wi-Fi ADB. The
 * hotspot is an access point (no Wi-Fi Direct group), so [close] releases the claim at once.
 */
class MentraHotspotNetwork(context: Context) {
    private val context = context.applicationContext
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)

    @Volatile private var claim: GlassesLinkClaim? = null
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var joined: Network? = null
    private val _lost = MutableStateFlow(false)

    /** True once the joined hotspot dropped (glasses turned it off, out of range, Wi-Fi off). */
    val lost: StateFlow<Boolean> = _lost.asStateFlow()

    /**
     * Claims the glasses link and joins [ssid]. Android may show its "connect to device" dialog
     * the first time; the user has [JOIN_TIMEOUT_MS] to accept it.
     */
    suspend fun join(ssid: String, password: String): Network {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("Joining the Mentra Live hotspot needs Android 10 or newer")
        }
        close()
        val claimed = GlassesNetworkSessionCoordinator.claimVendorLink(context, GlassesLinkClient.MENTRA_GALLERY)
            ?: throw IOException("The glasses Wi-Fi link is still used by another session")
        claim = claimed
        GlassesNetworkSessionCoordinator.core.markConnectRequested(claimed)
        _lost.value = false
        return try {
            val network = withTimeout(JOIN_TIMEOUT_MS) { request(ssid, password) }
            GlassesNetworkSessionCoordinator.core.markGroupFormed(claimed)
            Log.i(TAG, "Joined the glasses hotspot $ssid (app-scoped, default network unchanged)")
            network
        } catch (timeout: TimeoutCancellationException) {
            close()
            throw IOException("Timed out joining the glasses hotspot $ssid", timeout)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun request(ssid: String, password: String): Network =
        suspendCancellableCoroutine { continuation ->
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .apply { if (password.isNotEmpty()) setWpa2Passphrase(password) }
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
            val resumed = AtomicBoolean(false)
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    joined = network
                    if (resumed.compareAndSet(false, true)) continuation.resume(network)
                }

                override fun onUnavailable() {
                    if (resumed.compareAndSet(false, true)) {
                        continuation.resumeWithException(
                            IOException("The glasses hotspot was not approved or could not be found"),
                        )
                    }
                }

                override fun onLost(network: Network) {
                    if (network != joined) return
                    Log.w(TAG, "The glasses hotspot connection was lost")
                    _lost.value = true
                }
            }
            callback = networkCallback
            try {
                connectivity.requestNetwork(request, networkCallback)
            } catch (error: Exception) {
                callback = null
                if (resumed.compareAndSet(false, true)) continuation.resumeWithException(error)
            }
        }

    /** Drops the hotspot request and releases the glasses link. Safe to call more than once. */
    fun close() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        joined = null
        val released = claim ?: return
        claim = null
        GlassesNetworkSessionCoordinator.releaseVendorLink(context, released, driver = null)
        Log.i(TAG, "Left the glasses hotspot and released the glasses link")
    }

    private companion object {
        const val TAG = MentraGallerySync.TAG

        /** Covers the system approval dialog on first use (the MentraOS example allows 35 s). */
        const val JOIN_TIMEOUT_MS = 60_000L
    }
}
