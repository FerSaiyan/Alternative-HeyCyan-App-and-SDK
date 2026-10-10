package com.fersaiyan.cyanbridge.devices.tunebuds

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.media.rtsp.GlassesLiveConnection
import com.fersaiyan.cyanbridge.media.rtsp.GlassesRtspRelay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.io.IOException

internal class TuneBudsLiveConnection(context: Context, private val manager: TuneBudsManager) : GlassesLiveConnection {
    private val hotspot = TuneBudsLocalHotspot(context)
    private var relay: GlassesRtspRelay? = null
    private var commandAttempted = false

    override suspend fun open(onStatus: (String, String) -> Unit): String {
        if (!manager.isConnected()) throw IOException("TuneBuds Bluetooth is not connected")
        onStatus("Starting hotspot", "Creating the TuneBuds livestream Wi-Fi link")
        val credentials = hotspot.start()
        onStatus("Starting stream", "Connecting TuneBuds to the phone and sending 0xE8")
        val url = coroutineScope {
            val monitor = launch { throw awaitFailure() }
            try {
                commandAttempted = true
                manager.startLivestream(credentials)
            } finally { monitor.cancel() }
        }
        Log.i("TuneBudsLive", "Device RTSP endpoint: $url")
        val endpoint = TuneBudsRtspEndpoint.parse(url)
        val proxy = GlassesRtspRelay(
            endpoint.host, endpoint.port, streamPath = endpoint.path,
            upstreamPlayRange = null,
            log = { Log.i("TuneBudsRtspRelay", it) },
        )
        relay = proxy
        proxy.start()
        return GlassesRtspRelay.URL
    }

    override suspend fun awaitFailure(): Throwable = coroutineScope {
        val disconnected = async(start = CoroutineStart.UNDISPATCHED) {
            manager.state.first { it.protocolState != TuneBudsSppState.CONNECTED.name || it.wifiState in setOf(0, 4, 5) }
            IOException("TuneBuds Bluetooth or Wi-Fi disconnected")
        }
        val hotspotStopped = async(start = CoroutineStart.UNDISPATCHED) {
            hotspot.isActive.first { !it }
            IOException("TuneBuds livestream hotspot stopped")
        }
        try {
            select {
                disconnected.onAwait { it }
                hotspotStopped.onAwait { it }
            }
        } finally {
            disconnected.cancel()
            hotspotStopped.cancel()
        }
    }

    override suspend fun close() {
        relay?.stop()
        relay = null
        try {
            if (commandAttempted && manager.isConnected()) manager.finishTransferBlocking()
        } catch (error: Exception) {
            Log.w("TuneBudsLive", "Camera shutdown failed", error)
        } finally {
            commandAttempted = false
            hotspot.stop()
        }
    }
}
