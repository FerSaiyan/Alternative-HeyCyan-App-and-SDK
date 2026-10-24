package com.fersaiyan.cyanbridge.devices.tunebuds

import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsFrame
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsFrameType
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsProtocol
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.URI

/** Vendor E6/E8 handshake; subscribe before requests so immediate URLs aren't lost. */
internal class TuneBudsRtspStartup(
    private val frames: Flow<TuneBudsFrame>,
    private val request: suspend (Int, ByteArray) -> TuneBudsFrame,
) {
    suspend fun open(
        ssid: String,
        password: String,
        channel: Int,
        quality: Int = -1,
        timeoutMs: Long = 60_000L,
    ): String = withTimeout(timeoutMs) {
        coroutineScope {
            val endpoint = async(start = CoroutineStart.UNDISPATCHED) {
                val frame = frames.filter {
                    it.type == TuneBudsFrameType.NOTIFICATION && it.command == TuneBudsProtocol.CMD_START_RTSP
                }.first()
                val url = TuneBudsProtocol.parseString(frame.payload)
                    ?: throw IOException("TuneBuds returned an empty RTSP URL")
                TuneBudsRtspEndpoint.parse(url)
                url
            }
            val wifiFailure = launch(start = CoroutineStart.UNDISPATCHED) {
                val frame = frames.first {
                    it.type == TuneBudsFrameType.NOTIFICATION &&
                        it.command == TuneBudsProtocol.CMD_WIFI_STATE &&
                        TuneBudsProtocol.parseStatus(it.payload) in setOf(0, 4, 5)
                }
                throw IOException("TuneBuds Wi-Fi failed (state ${TuneBudsProtocol.parseStatus(frame.payload)})")
            }
            try {
                val wifi = request(
                    TuneBudsProtocol.CMD_CONFIGURE_WIFI,
                    TuneBudsProtocol.buildWifiPayload(0, ssid, password, channel),
                )
                if (TuneBudsProtocol.parseStatus(wifi.payload) != 0) {
                    throw IOException("TuneBuds Wi-Fi configuration failed")
                }
                // Official AB Mate maps 1/3/5 to busy; 6 means unsupported.
                for (attempt in 1..30) {
                    val response = request(TuneBudsProtocol.CMD_START_RTSP, TuneBudsProtocol.rtspQualityPayload(quality))
                    when (val status = TuneBudsProtocol.parseStatus(response.payload)) {
                        0 -> return@coroutineScope endpoint.await()
                        1, 3, 5 -> {
                            if (attempt == 30) throw IOException("TuneBuds livestream remained busy")
                            delay(1_000L)
                        }
                        6 -> throw IOException("This TuneBuds firmware does not support livestream")
                        else -> throw IOException("TuneBuds livestream failed (status $status)")
                    }
                }
                error("Unreachable")
            } finally {
                endpoint.cancel()
                wifiFailure.cancel()
            }
        }
    }
}

internal data class TuneBudsRtspEndpoint(val host: String, val port: Int, val path: String) {
    companion object {
        fun parse(url: String): TuneBudsRtspEndpoint {
            val uri = runCatching { URI(url) }.getOrElse { throw IOException("Invalid TuneBuds RTSP URL", it) }
            if (!uri.scheme.equals("rtsp", ignoreCase = true) || uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0
            ) throw IOException("Invalid TuneBuds RTSP URL")
            val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            return TuneBudsRtspEndpoint(uri.host, if (uri.port == -1) 554 else uri.port,
                path + (uri.rawQuery?.let { "?$it" } ?: ""))
        }
    }
}
