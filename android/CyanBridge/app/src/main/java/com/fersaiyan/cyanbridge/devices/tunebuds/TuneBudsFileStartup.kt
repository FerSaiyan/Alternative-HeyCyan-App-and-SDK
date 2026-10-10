package com.fersaiyan.cyanbridge.devices.tunebuds

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

internal class TuneBudsFileModeException(val status: Int?) : IOException(
    if (status == 4) "TuneBuds file manager cannot start: low battery (status 4)"
    else "TuneBuds file manager failed with status $status",
)

/** Subscribe before E6/E7: endpoint, peer and open notifications may precede replies. */
internal class TuneBudsFileStartup(
    private val frames: Flow<TuneBudsFrame>,
    private val request: suspend (Int, ByteArray) -> TuneBudsFrame,
) {
    suspend fun open(
        mode: Int,
        ssid: String,
        password: String,
        channel: Int = 0,
        timeoutMs: Long = 60_000L,
        connectP2p: suspend (String) -> Unit = {},
    ): String = withTimeout(timeoutMs) {
        coroutineScope {
            val endpoint = async(start = CoroutineStart.UNDISPATCHED) {
                TuneBudsProtocol.parseString(frames.first {
                    it.type == TuneBudsFrameType.NOTIFICATION && it.command == TuneBudsProtocol.CMD_FILE_MANAGER
                }.payload) ?: throw IOException("TuneBuds returned an empty media server address")
            }
            val peer = if (mode == 2) async(start = CoroutineStart.UNDISPATCHED) {
                TuneBudsProtocol.parseP2pAddress(frames.first {
                    it.type == TuneBudsFrameType.NOTIFICATION && it.command == TuneBudsProtocol.CMD_P2P_ADDRESS
                }.payload) ?: throw IOException("TuneBuds returned an invalid P2P peer address")
            } else null
            val opened = if (mode == 2) async(start = CoroutineStart.UNDISPATCHED) {
                frames.first {
                    it.type == TuneBudsFrameType.NOTIFICATION && it.command == TuneBudsProtocol.CMD_WIFI_STATE &&
                        TuneBudsProtocol.parseStatus(it.payload) == 2
                }
            } else null
            val failure = launch(start = CoroutineStart.UNDISPATCHED) {
                val frame = frames.first {
                    it.type == TuneBudsFrameType.NOTIFICATION && it.command == TuneBudsProtocol.CMD_WIFI_STATE &&
                        TuneBudsProtocol.parseStatus(it.payload) in setOf(0, 4, 5)
                }
                throw IOException("TuneBuds Wi-Fi failed (state ${TuneBudsProtocol.parseStatus(frame.payload)})")
            }
            try {
                val configured = request(TuneBudsProtocol.CMD_CONFIGURE_WIFI,
                    TuneBudsProtocol.buildWifiPayload(mode, ssid, password, channel))
                if (TuneBudsProtocol.parseStatus(configured.payload) != 0) throw IOException("TuneBuds Wi-Fi configuration failed")
                for (attempt in 1..30) {
                    when (val status = TuneBudsProtocol.parseStatus(request(TuneBudsProtocol.CMD_FILE_MANAGER, byteArrayOf()).payload)) {
                        0 -> {
                            if (peer != null) {
                                val address = withTimeout(30_000L) {
                                    opened?.await()
                                    peer.await()
                                }
                                connectP2p(address)
                            }
                            return@coroutineScope endpoint.await()
                        }
                        // ABMateManager.startPullFileInternal maps all three to ERROR_BUSY.
                        1, 3, 5 -> {
                            if (attempt == 30) throw IOException("TuneBuds file manager remained busy")
                            delay(1_000L)
                        }
                        else -> throw TuneBudsFileModeException(status)
                    }
                }
                error("Unreachable")
            } finally {
                failure.cancel()
                endpoint.cancel()
                peer?.cancel()
                opened?.cancel()
            }
        }
    }
}
