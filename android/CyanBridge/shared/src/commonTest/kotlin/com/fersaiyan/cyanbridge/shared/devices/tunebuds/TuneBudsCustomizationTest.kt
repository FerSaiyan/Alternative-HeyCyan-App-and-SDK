package com.fersaiyan.cyanbridge.shared.devices.tunebuds

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AB Mate FOTA, the 0xB0 wake event and the 0xB2 glasses-mic path. */
class TuneBudsCustomizationTest {
    /** Glasses that answer 0xA0 and verify the streamed image like the add.176 device model. */
    private class FotaGlasses(private val block: Int, private val failWith: Int? = null) : TuneBudsTransport {
        lateinit var session: TuneBudsSession
        val image = mutableListOf<Byte>()
        var announcedSize = 0L
        private val decoder = TuneBudsFrameDecoder()
        private var nextBoundary = block.toLong()
        private var sequence = 0
        override val isConnected = true
        override val maxWriteLength = 64

        override suspend fun write(packet: ByteArray) {
            decoder.append(packet).forEach { frame ->
                when (frame.command) {
                    TuneBudsProtocol.CMD_FOTA_GET_INFO -> {
                        announcedSize = TuneBudsFota.u32(frame.payload, 8)
                        reply(frame.command, TuneBudsFrameType.RESPONSE, TuneBudsFota.le32(0) + TuneBudsFota.le32(block.toLong()) + byteArrayOf(1))
                    }
                    TuneBudsProtocol.CMD_FOTA_START, TuneBudsProtocol.CMD_FOTA_SEND_DATA -> {
                        val data = if (frame.command == TuneBudsProtocol.CMD_FOTA_START) frame.payload.copyOfRange(8, frame.payload.size) else frame.payload
                        image += data.toList()
                        when {
                            image.size.toLong() == announcedSize -> notifyState(failWith ?: TuneBudsProtocol.FOTA_STATE_FINISH)
                            image.size >= nextBoundary -> {
                                nextBoundary += block
                                notifyState(TuneBudsProtocol.FOTA_STATE_NEXT_BLOCK)
                            }
                        }
                    }
                    else -> reply(frame.command, TuneBudsFrameType.RESPONSE, byteArrayOf(0))
                }
            }
        }

        private fun notifyState(state: Int) = reply(TuneBudsProtocol.CMD_FOTA_STATE, TuneBudsFrameType.NOTIFICATION, byteArrayOf(state.toByte()))

        fun reply(command: Int, type: TuneBudsFrameType, payload: ByteArray) {
            val encoded = TuneBudsProtocol.encode(command, payload, type, maxPacketSize = 64, initialSequence = sequence)
            sequence = encoded.nextSequence
            encoded.frames.forEach(session::onBytesReceived)
        }
    }

    @Test
    fun fotaStreamsTheImageInBlocksUntilTheGlassesFinish() = runTest {
        val glasses = FotaGlasses(block = 100)
        val session = TuneBudsSession(glasses).also { glasses.session = it }
        val image = ByteArray(1_234) { (it * 13).toByte() }

        val outcome = TuneBudsFota(session).push(image, byteArrayOf(1, 2, 3, 4), TuneBudsFota.versionCode("0.1.0.7"))

        assertEquals(TuneBudsFota.Outcome.Finished, outcome)
        assertContentEquals(image, glasses.image.toByteArray())
        assertEquals(0x00010007L, TuneBudsFota.versionCode("0.1.0.7"))
    }

    @Test
    fun fotaReportsTheGlassesVerificationError() = runTest {
        val glasses = FotaGlasses(block = 4096, failWith = 0x0b)
        val session = TuneBudsSession(glasses).also { glasses.session = it }

        val outcome = TuneBudsFota(session).push(ByteArray(300), ByteArray(4), 0)

        assertEquals(TuneBudsFota.Outcome.Failed("The glasses rejected the update (code 0xb)"), outcome)
    }

    @Test
    fun aiChatNotifyIsAWakeEventExceptDuringOurOwnMicCapture() = runTest {
        val glasses = FotaGlasses(block = 100)
        val session = TuneBudsSession(glasses).also { glasses.session = it }
        val wake = async(start = CoroutineStart.UNDISPATCHED) { session.wakeEvents.first() }

        glasses.reply(TuneBudsProtocol.CMD_AI_CHAT_STATE, TuneBudsFrameType.NOTIFICATION, byteArrayOf(2))
        assertEquals(2, wake.await())

        val mic = mutableListOf<Byte>()
        var wakes = 0
        val counter = async(start = CoroutineStart.UNDISPATCHED) { session.wakeEvents.collect { wakes++ } }
        session.micAudioListener = { mic += it.toList() }
        glasses.reply(TuneBudsProtocol.CMD_AI_CHAT_STATE, TuneBudsFrameType.NOTIFICATION, byteArrayOf(2))
        glasses.reply(TuneBudsProtocol.CMD_AI_RECORD_DATA, TuneBudsFrameType.NOTIFICATION, ByteArray(50) { 7 })
        counter.cancel()

        assertEquals(0, wakes)
        assertEquals(50, mic.size)
        assertTrue(mic.all { it == 7.toByte() })
        assertFalse(session.state.value.lastError != null)
    }
}
