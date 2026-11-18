package com.fersaiyan.cyanbridge.devices.tunebuds

import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsFrame
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsFrameType
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsProtocol
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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
    /** Work state 0x0D (download) followed by 0x03 (connected): the firmware left the download it just accepted. */
    private suspend fun awaitDroppedDownload() {
        var entered = false
        frames.first {
            if (it.type != TuneBudsFrameType.NOTIFICATION || it.command != TuneBudsProtocol.CMD_WORK_STATE) return@first false
            when (TuneBudsProtocol.parseStatus(it.payload)) {
                WORK_STATE_DOWNLOAD -> { entered = true; false }
                WORK_STATE_CONNECTED -> entered
                else -> false
            }
        }
    }

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
                    // Subscribe before E7: the work-state notifications follow its reply within milliseconds.
                    val dropped = async(start = CoroutineStart.UNDISPATCHED) { awaitDroppedDownload() }
                    when (val status = TuneBudsProtocol.parseStatus(request(TuneBudsProtocol.CMD_FILE_MANAGER, byteArrayOf()).payload)) {
                        0 -> {
                            // Firmware 5621 can accept E7 and then leave the download state at once (a queued
                            // media-count session won the V821 power-up): work state 0x0D then 0x03, no endpoint.
                            val ready = async(start = CoroutineStart.UNDISPATCHED) {
                                if (peer != null) { opened?.await(); peer.await() } else endpoint.await()
                                Unit
                            }
                            val race: suspend () -> Boolean = {
                                select {
                                    ready.onAwait { false }
                                    dropped.onAwait { true }
                                }
                            }
                            val wasDropped = if (peer != null) withTimeout(30_000L) { race() } else race()
                            dropped.cancel()
                            ready.cancel()
                            if (wasDropped) {
                                if (attempt == 30) throw IOException("TuneBuds dropped every file-manager session")
                                delay(1_000L)
                                continue
                            }
                            if (peer != null) connectP2p(peer.await())
                            return@coroutineScope endpoint.await()
                        }
                        // ABMateManager.startPullFileInternal maps all three to ERROR_BUSY.
                        1, 3, 5 -> {
                            dropped.cancel()
                            if (attempt == 30) throw IOException("TuneBuds file manager remained busy")
                            delay(1_000L)
                        }
                        else -> {
                            dropped.cancel()
                            throw TuneBudsFileModeException(status)
                        }
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

    private companion object {
        const val WORK_STATE_CONNECTED = 0x03
        const val WORK_STATE_DOWNLOAD = 0x0D
    }
}
