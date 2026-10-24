package com.fersaiyan.cyanbridge.shared.devices.tunebuds

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuneBudsSessionTest {
    /** Answers every request like the glasses: status 0, or the canned device-info values. */
    private class FakeGlasses(var deviceInfo: Map<Int, ByteArray> = emptyMap()) : TuneBudsTransport {
        lateinit var session: TuneBudsSession
        val requests = mutableListOf<Int>()
        val packets = mutableListOf<ByteArray>()
        var onRequest: (Int) -> Unit = {}
        private var sequence = 0

        override val isConnected = true
        override val maxWriteLength = 20

        override suspend fun write(packet: ByteArray) {
            packets += packet
            val command = packet[1].toInt() and 0xFF
            val fragment = packet[3].toInt() and 0xFF
            if ((fragment and 0x0F) != (fragment ushr 4)) return // wait for the last fragment
            requests += command
            val payload = if (command == TuneBudsProtocol.CMD_DEVICE_INFO) {
                deviceInfo.flatMap { (type, value) -> listOf(type.toByte(), value.size.toByte()) + value.toList() }.toByteArray()
            } else {
                byteArrayOf(0)
            }
            reply(command, TuneBudsFrameType.RESPONSE, payload)
            onRequest(command)
        }

        fun reply(command: Int, type: TuneBudsFrameType, payload: ByteArray) {
            val encoded = TuneBudsProtocol.encode(command, payload, type, maxPacketSize = 20, initialSequence = sequence)
            sequence = encoded.nextSequence
            encoded.frames.forEach(session::onBytesReceived)
        }
    }

    private fun sessionWith(glasses: FakeGlasses) = TuneBudsSession(glasses).also { glasses.session = it }

    @Test
    fun batteryComesFromTheDeviceInfoResponse() = runTest {
        val glasses = FakeGlasses(mapOf(TuneBudsProtocol.INFO_BATTERY to byteArrayOf((0x80 or 57).toByte())))
        val session = sessionWith(glasses)

        session.requestBattery()

        assertEquals(57, session.state.value.batteryPercent)
        assertTrue(session.state.value.isCharging)
        assertEquals(listOf(TuneBudsProtocol.CMD_DEVICE_INFO), glasses.requests)
    }

    @Test
    fun aiPhotoChunksAreJoinedUntilTheFinalStatus() = runTest {
        val glasses = FakeGlasses()
        val session = sessionWith(glasses)
        glasses.onRequest = { command ->
            if (command == TuneBudsProtocol.CMD_CAMERA_ON) {
                glasses.reply(TuneBudsProtocol.CMD_AI_PICTURE, TuneBudsFrameType.NOTIFICATION, byteArrayOf(1, 0xFF.toByte(), 0xD8.toByte()))
                glasses.reply(TuneBudsProtocol.CMD_AI_PICTURE, TuneBudsFrameType.NOTIFICATION, byteArrayOf(0, 0xFF.toByte(), 0xD9.toByte()))
            }
        }

        val photo = session.capturePhotoForAi(timeoutMs = 1_000L)

        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()), photo)
    }

    @Test
    fun recordingTogglesTrackTheGlassesState() = runTest {
        val glasses = FakeGlasses()
        val session = sessionWith(glasses)

        session.toggleVideo()
        assertTrue(session.state.value.isVideoRecording)

        session.toggleVideo()
        assertEquals(false, session.state.value.isVideoRecording)
        assertEquals(listOf(TuneBudsProtocol.CMD_START_VIDEO, TuneBudsProtocol.CMD_CAMERA_CLOSE), glasses.requests)
    }

    @Test
    fun requestsAreSplitIntoLinkSizedPacketsWithRunningSequence() = runTest {
        val glasses = FakeGlasses()
        val session = sessionWith(glasses)

        session.syncTime()
        session.syncTime()

        assertTrue(glasses.packets.all { it.size <= 20 })
        assertEquals(listOf(0, 1), glasses.packets.map { it[0].toInt() })
    }
}
