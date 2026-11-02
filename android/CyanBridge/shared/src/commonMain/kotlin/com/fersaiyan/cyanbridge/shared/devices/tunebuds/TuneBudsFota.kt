package com.fersaiyan.cyanbridge.shared.devices.tunebuds

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AB Mate firmware update over a [TuneBudsSession] (the vendor `com.bluetrum.fota.OtaManager`
 * flow, ported from the owner's add.176 reference):
 * - 0xA0 GET_INFO `u32 version | md5(file)[0..4) | u32 size` -> `u32 start | u32 block | u8 allow`
 * - 0xA1 START `u32 start | u32 total | data...`, then 0xA2 SEND_DATA `data...` (no responses)
 * - 0xA3 STATE notify after every block: 00 next block, FE continue, FD pause, FF finished
 *   (the glasses verified and applied the whole image), anything else is an error code.
 * All integers are little-endian.
 */
class TuneBudsFota(private val session: TuneBudsSession) {
    sealed class Outcome {
        data object Finished : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    suspend fun push(
        image: ByteArray,
        md5Prefix: ByteArray,
        version: Long,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Outcome = coroutineScope {
        require(md5Prefix.size == 4) { "md5Prefix must be the first 4 bytes of the image's MD5" }
        val states = Channel<Int>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            session.frames
                .filter { it.command == TuneBudsProtocol.CMD_FOTA_STATE && it.type == TuneBudsFrameType.NOTIFICATION }
                .collect { frame -> states.trySend(TuneBudsProtocol.parseStatus(frame.payload) ?: -1) }
        }
        try {
            val info = session.request(
                TuneBudsProtocol.CMD_FOTA_GET_INFO,
                le32(version) + md5Prefix + le32(image.size.toLong()),
            ).payload
            if (info.size < 9) return@coroutineScope Outcome.Failed("The glasses sent no update info")
            val start = u32(info, 0)
            val block = u32(info, 4).takeIf { it > 0 } ?: return@coroutineScope Outcome.Failed("The glasses sent block size 0")
            if (info[8].toInt() == 0) return@coroutineScope Outcome.Failed("The glasses refused the update")
            if (start > image.size) return@coroutineScope Outcome.Failed("The glasses asked to resume past the end")

            val maxPayload = session.maxFramePayload
            var pos = start
            var nextBoundary = (start / block + 1) * block
            var first = true
            while (true) {
                if (pos < image.size) {
                    val room = if (first) maxPayload - 8 else maxPayload
                    val n = minOf(room.toLong(), image.size - pos, nextBoundary - pos).toInt()
                    val chunk = image.copyOfRange(pos.toInt(), pos.toInt() + n)
                    if (first) {
                        session.sendWithoutResponse(TuneBudsProtocol.CMD_FOTA_START, le32(pos) + le32(image.size.toLong()) + chunk)
                        first = false
                    } else {
                        session.sendWithoutResponse(TuneBudsProtocol.CMD_FOTA_SEND_DATA, chunk)
                    }
                    pos += n
                    onProgress(pos, image.size.toLong())
                    if (pos < image.size && pos < nextBoundary) continue
                }
                val timeout = if (pos >= image.size) FINAL_STATE_TIMEOUT_MS else BLOCK_STATE_TIMEOUT_MS
                when (val state = withTimeoutOrNull(timeout) { states.receive() }) {
                    null -> return@coroutineScope Outcome.Failed("The glasses stopped answering during the update")
                    TuneBudsProtocol.FOTA_STATE_FINISH -> return@coroutineScope if (pos >= image.size) {
                        Outcome.Finished
                    } else {
                        Outcome.Failed("The glasses finished early")
                    }
                    TuneBudsProtocol.FOTA_STATE_NEXT_BLOCK, TuneBudsProtocol.FOTA_STATE_CONTINUE -> {
                        if (pos < image.size) nextBoundary += block
                    }
                    TuneBudsProtocol.FOTA_STATE_PAUSE -> Unit
                    else -> return@coroutineScope Outcome.Failed("The glasses rejected the update (code 0x${state.toString(16)})")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Outcome.Failed("unreachable")
        } finally {
            collector.cancel()
        }
    }

    companion object {
        private const val BLOCK_STATE_TIMEOUT_MS = 30_000L
        private const val FINAL_STATE_TIMEOUT_MS = 120_000L

        /** "a.b.c.d" -> the u32 the glasses report in device-info 0x02 (a is the top byte). */
        fun versionCode(version: String): Long {
            val parts = version.trim().split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0, 0)
            return (parts[0].toLong() and 0xFF shl 24) or (parts[1].toLong() and 0xFF shl 16) or
                (parts[2].toLong() and 0xFF shl 8) or (parts[3].toLong() and 0xFF)
        }

        internal fun le32(value: Long) = ByteArray(4) { (value ushr (8 * it)).toByte() }

        internal fun u32(b: ByteArray, o: Int): Long =
            (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
                ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
    }
}
