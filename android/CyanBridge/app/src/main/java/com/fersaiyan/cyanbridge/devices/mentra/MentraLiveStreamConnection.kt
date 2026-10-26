package com.fersaiyan.cyanbridge.devices.mentra

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.fersaiyan.cyanbridge.media.rtsp.GlassesLiveConnection
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraStreamRoute
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraStreamUrls
import com.fersaiyan.cyanbridge.shared.network.session.GlassesLinkClient
import com.mentra.bluetoothsdk.HotspotStatus
import com.mentra.bluetoothsdk.StreamRequest
import com.mentra.bluetoothsdk.StreamState
import com.mentra.bluetoothsdk.StreamVideoConfig
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Mentra Live preview transport for [com.fersaiyan.cyanbridge.media.rtsp.LibVlcLivePreviewManager]:
 * the glasses publish RTMP to an ingest on the phone ([MentraRtmpIngest]) and LibVLC plays the
 * remuxed MPEG-TS from loopback.
 *
 * The glasses must reach the phone. When the glasses are on a Wi-Fi network and the phone's Wi-Fi
 * is in the same subnet, the ingest listens there. Otherwise the glasses turn on their hotspot,
 * the phone joins it app-scoped ([MentraHotspotNetwork], link client
 * [GlassesLinkClient.MENTRA_LIVE_STREAM]) and the ingest listens on the phone's hotspot address,
 * as the official app's SoftAP stream does. Video only: the glasses microphone stays free.
 */
internal class MentraLiveStreamConnection(context: Context) : GlassesLiveConnection {
    private val context = context.applicationContext
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val manager: MentraLiveManager get() = MentraLiveManager.getInstance(context)

    private var hotspotLink: MentraHotspotNetwork? = null
    private var openedHotspot = false
    private var ingest: MentraRtmpIngest? = null
    private var player: LoopbackTsServer? = null
    private var streamRequested = false
    private var streamId: String? = null

    override suspend fun open(onStatus: (String, String) -> Unit): String {
        val glasses = manager
        if (!glasses.ready) throw IOException("Mentra Live is not connected")
        if (glasses.isRecordingVideo) throw IOException("Stop the Mentra Live video recording first")
        if (glasses.firmwareUpdateActive) throw IOException("Mentra Live is updating its firmware")

        val phoneAddress = withContext(Dispatchers.IO) { sameWifiAddress(glasses) } ?: joinHotspot(glasses, onStatus)
        val key = UUID.randomUUID().toString().replace("-", "")
        val localPlayer = LoopbackTsServer(UUID.randomUUID().toString().replace("-", "")).also { player = it }
        val playbackUrl = localPlayer.start()
        val receiver = MentraRtmpIngest(phoneAddress, key, localPlayer).also { ingest = it }
        val port = withContext(Dispatchers.IO) { receiver.start() }
        val publishUrl = MentraStreamUrls.previewPublishUrl(phoneAddress.hostAddress.orEmpty(), port, key)

        onStatus("Starting stream", "Asking Mentra Live to stream to this phone")
        val id = "cyanbridge-preview-" + UUID.randomUUID()
        streamId = id
        streamRequested = true
        Log.i(TAG, "startStream ${MentraStreamUrls.redacted(publishUrl)}")
        val started = withContext(Dispatchers.IO) {
            glasses.startStream(
                StreamRequest(
                    publishUrl,
                    id,
                    true, // the glasses' start sound, a privacy cue next to the capture light
                    StreamVideoConfig(PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_BITRATE, PREVIEW_FPS),
                    null,
                    null,
                    false, // no microphone: preview is video only
                ),
            )
        }
        val resolved = started.resolvedConfig?.video
        Log.i(TAG, "Glasses streaming: ${resolved?.width}x${resolved?.height} ${resolved?.fps} fps ${resolved?.bitrate} bps (${started.resolvedConfig?.transport})")

        onStatus("Waiting for video", "Mentra Live is streaming; waiting for the first keyframe")
        val firstFrame = withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MS) {
            while (receiver.lastVideoAtMs == 0L) delay(100)
            true
        }
        if (firstFrame == null) {
            val reason = receiver.unsupportedCodec?.let { "the glasses sent $it" }
                ?: if (receiver.publishing.value) "no H.264 keyframe arrived" else "the glasses never connected to this phone"
            throw IOException("No video from Mentra Live: $reason")
        }
        return playbackUrl
    }

    /** The phone's Wi-Fi address when the glasses' Wi-Fi network is the phone's own subnet. */
    private fun sameWifiAddress(glasses: MentraLiveManager): InetAddress? {
        val glassesIp = glasses.wifi()?.localIp?.takeIf { it.isNotBlank() } ?: return null
        for (network in connectivity.allNetworks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val link = connectivity.getLinkProperties(network)?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address } ?: continue
            val phoneIp = link.address.hostAddress ?: continue
            if (MentraStreamRoute.sameSubnet(phoneIp, glassesIp, link.prefixLength)) {
                Log.i(TAG, "Glasses ($glassesIp) and phone ($phoneIp) share a Wi-Fi network; no hotspot needed")
                return link.address
            }
        }
        return null
    }

    private suspend fun joinHotspot(glasses: MentraLiveManager, onStatus: (String, String) -> Unit): InetAddress {
        onStatus("Starting hotspot", "Turning on the Mentra Live hotspot")
        val hotspot = glasses.hotspot() ?: run {
            openedHotspot = true
            val status = withTimeout(HOTSPOT_TIMEOUT_MS) { glasses.setHotspot(true) }
            (status as? HotspotStatus.Enabled)?.also { delay(HOTSPOT_BROADCAST_DELAY_MS) }
                ?: throw IOException("The glasses hotspot did not turn on (${status.state})")
        }
        if (hotspot.ssid.isBlank()) throw IOException("The glasses did not report their hotspot name")
        Log.i(TAG, "Glasses hotspot ${hotspot.ssid} at ${hotspot.localIp} (openedByPreview=$openedHotspot)")
        onStatus("Joining Wi-Fi", "Joining ${hotspot.ssid}")
        val link = MentraHotspotNetwork(context, GlassesLinkClient.MENTRA_LIVE_STREAM, TAG).also { hotspotLink = it }
        val network = link.join(hotspot.ssid, hotspot.password)
        return awaitIpv4(network) ?: throw IOException("The phone got no address on the glasses hotspot")
    }

    /** DHCP can finish after `onAvailable`; poll the link properties for the phone's IPv4. */
    private suspend fun awaitIpv4(network: Network): InetAddress? = withTimeoutOrNull(ADDRESS_TIMEOUT_MS) {
        var address = connectivity.getLinkProperties(network)?.let(::firstIpv4)
        while (address == null) {
            delay(200)
            address = connectivity.getLinkProperties(network)?.let(::firstIpv4)
        }
        address
    }

    private fun firstIpv4(properties: LinkProperties): InetAddress? =
        properties.linkAddresses.firstOrNull { it.address is Inet4Address }?.address

    override suspend fun awaitFailure(): Throwable = coroutineScope {
        val glasses = manager
        val disconnected = async(start = CoroutineStart.UNDISPATCHED) {
            glasses.glasses.first { !it.connected }
            IOException("Mentra Live disconnected")
        }
        val stopped = async(start = CoroutineStart.UNDISPATCHED) {
            val event = glasses.streamStatus.first { event ->
                (event.streamId == null || event.streamId == streamId) && when (event.state) {
                    StreamState.STOPPED, StreamState.RECONNECT_FAILED -> true
                    StreamState.ERROR -> event.willRetry != true
                    else -> false
                }
            }
            IOException("Mentra Live stopped the stream (${event.state?.value}${event.errorDetails?.let { ": $it" }.orEmpty()})")
        }
        val stalled = async(start = CoroutineStart.UNDISPATCHED) {
            var failure: IOException? = null
            while (failure == null) {
                delay(1_000)
                val last = ingest?.lastVideoAtMs ?: 0L
                failure = when {
                    hotspotLink?.lost?.value == true -> IOException("The glasses hotspot connection was lost")
                    last > 0 && SystemClock.elapsedRealtime() - last > VIDEO_STALL_MS ->
                        IOException("Mentra Live video stopped arriving")
                    else -> null
                }
            }
            failure
        }
        try {
            select {
                disconnected.onAwait { it }
                stopped.onAwait { it }
                stalled.onAwait { it }
            }
        } finally {
            disconnected.cancel()
            stopped.cancel()
            stalled.cancel()
        }
    }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        val glasses = MentraLiveManager.instanceOrNull()
        if (streamRequested && glasses?.connected == true) {
            runCatching { withTimeout(STOP_TIMEOUT_MS) { glasses.stopStream() } }
                .onSuccess { Log.i(TAG, "Glasses stream stopped (${it.state?.value})") }
                .onFailure { Log.w(TAG, "stopStream failed: ${it.message}") }
        }
        streamRequested = false
        streamId = null
        ingest?.stop()
        ingest = null
        player?.stop()
        player = null
        // Leave the phone side first, then stop the glasses AP (the gallery sync's order).
        hotspotLink?.close()
        hotspotLink = null
        if (openedHotspot && glasses?.connected == true) {
            runCatching { withTimeout(HOTSPOT_TIMEOUT_MS) { glasses.setHotspot(false) } }
                .onFailure { Log.w(TAG, "Could not turn the glasses hotspot off: ${it.message}") }
        }
        openedHotspot = false
    }

    companion object {
        const val TAG = MentraRtmpIngest.TAG

        // 720p at 15 fps keeps software decoding light and the hotspot hop well under its budget.
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
        private const val PREVIEW_FPS = 15
        private const val PREVIEW_BITRATE = 2_000_000
        private const val HOTSPOT_TIMEOUT_MS = 30_000L
        private const val HOTSPOT_BROADCAST_DELAY_MS = 3_000L
        private const val ADDRESS_TIMEOUT_MS = 10_000L
        private const val FIRST_FRAME_TIMEOUT_MS = 20_000L
        private const val VIDEO_STALL_MS = 10_000L
        private const val STOP_TIMEOUT_MS = 15_000L
    }
}
