package com.fersaiyan.cyanbridge.shared.devices.tunebuds

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuneBudsSessionTest {
    /** Answers every request like the glasses: status 0, or the canned device-info values asked for. */
    private class FakeGlasses(var deviceInfo: Map<Int, ByteArray> = emptyMap()) : TuneBudsTransport {
        lateinit var session: TuneBudsSession
        val requests = mutableListOf<Int>()
        val payloads = mutableListOf<ByteArray>()
        val packets = mutableListOf<ByteArray>()
        val statuses = mutableMapOf<Int, Int>()
        var onRequest: (Int) -> Unit = {}
        private val decoder = TuneBudsFrameDecoder()
        private var sequence = 0

        override val isConnected = true
        override val maxWriteLength = 20

        override suspend fun write(packet: ByteArray) {
            packets += packet
            decoder.append(packet).forEach { request ->
                val command = request.command
                requests += command
                payloads += request.payload
                val payload = if (command == TuneBudsProtocol.CMD_DEVICE_INFO) {
                    request.payload.filterIndexed { index, _ -> index % 2 == 0 }
                        .mapNotNull { type -> deviceInfo[type.toInt() and 0xFF]?.let { type to it } }
                        .flatMap { (type, value) -> listOf(type, value.size.toByte()) + value.toList() }
                        .toByteArray()
                } else {
                    byteArrayOf((statuses[command] ?: 0).toByte())
                }
                reply(command, TuneBudsFrameType.RESPONSE, payload)
                onRequest(command)
            }
        }

        fun reply(command: Int, type: TuneBudsFrameType, payload: ByteArray) {
            val encoded = TuneBudsProtocol.encode(command, payload, type, maxPacketSize = 20, initialSequence = sequence)
            sequence = encoded.nextSequence
            encoded.frames.forEach(session::onBytesReceived)
        }

        fun notify(command: Int, payload: ByteArray) = reply(command, TuneBudsFrameType.NOTIFICATION, payload)
    }

    private fun sessionWith(glasses: FakeGlasses) = TuneBudsSession(glasses).also { glasses.session = it }

    private fun info(vararg types: Int) = TuneBudsProtocol.deviceInfoQuery(*types)

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
                glasses.notify(TuneBudsProtocol.CMD_AI_PICTURE, byteArrayOf(1, 0xFF.toByte(), 0xD8.toByte()))
                glasses.notify(TuneBudsProtocol.CMD_AI_PICTURE, byteArrayOf(0, 0xFF.toByte(), 0xD9.toByte()))
            }
        }

        val photo = session.capturePhotoForAi(timeoutMs = 1_000L)

        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()), photo)
    }

    @Test
    fun aiPhotoListenerSeesChunksPhotosAndFailures() = runTest {
        val glasses = FakeGlasses()
        val session = sessionWith(glasses)
        val events = mutableListOf<String>()
        session.aiPhotoListener = object : TuneBudsAiPhotoListener {
            override fun onChunk(startsPhoto: Boolean) { events += "chunk $startsPhoto" }
            override fun onPhoto(image: ByteArray) { events += "photo ${image.size}" }
            override fun onFailure(message: String) { events += message }
        }

        glasses.notify(TuneBudsProtocol.CMD_AI_PICTURE, byteArrayOf(1, 0xFF.toByte(), 0xD8.toByte()))
        glasses.notify(TuneBudsProtocol.CMD_AI_PICTURE, byteArrayOf(0, 0xFF.toByte(), 0xD9.toByte()))
        glasses.notify(TuneBudsProtocol.CMD_AI_PICTURE, byteArrayOf(2))

        assertEquals(
            listOf("chunk true", "chunk false", "photo 4", "chunk false", "TuneBuds AI photo failed with status 2"),
            events,
        )
        assertEquals("TuneBuds AI photo failed with status 2", session.state.value.lastError)
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

        // The firmware stops a recording on its own (limit or the physical button).
        session.toggleAudio()
        glasses.notify(TuneBudsProtocol.CMD_CAMERA_CLOSE, byteArrayOf(0))
        assertEquals(false, session.state.value.isAudioRecording)
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

    @Test
    fun initializeReadsStatusAndOptionallyTheCapabilities() = runTest {
        val glasses = FakeGlasses(
            mapOf(
                TuneBudsProtocol.INFO_MAX_PACKET_SIZE to byteArrayOf(30),
                TuneBudsProtocol.INFO_FIRMWARE_VERSION to byteArrayOf(4, 3, 2, 1),
                TuneBudsProtocol.INFO_WIFI_SUPPORT to byteArrayOf(7),
                TuneBudsProtocol.INFO_DEVICE_ABILITY to byteArrayOf(0x34, 0x12),
                TuneBudsProtocol.INFO_SCREEN_CONFIG to byteArrayOf(1, 0),
                TuneBudsProtocol.INFO_CURRENT_VOLUME to byteArrayOf(5, 7),
                TuneBudsProtocol.INFO_VIDEO_LIMIT to byteArrayOf(0x2C, 0x01),
            ),
        )
        glasses.statuses[TuneBudsProtocol.CMD_CAMERA_COPROCESSOR_INFO] = 1
        glasses.statuses[TuneBudsProtocol.CMD_WORK_STATE] = 3
        val session = sessionWith(glasses)

        session.initialize()

        val status = listOf(0xE9, 0xEA, 0xEF, 0xF6)
        assertEquals(listOf(0x27, 0x27) + status, glasses.requests)
        assertContentEquals(info(0x01, 0x02, 0x86, 0x85), glasses.payloads[1])
        assertEquals(30, session.state.value.protocolPacketSize)
        assertEquals(1, session.state.value.aiKitSupport)
        assertEquals("1.2.3.4", session.state.value.firmwareVersion)
        assertEquals(3, session.state.value.workState)
        assertNull(session.state.value.wifiSupportMask)

        glasses.requests.clear()
        glasses.payloads.clear()
        session.reset()
        session.initialize(readCapabilities = true)

        assertEquals(listOf(0x27, 0x27, 0x27, 0xE0) + status, glasses.requests)
        assertContentEquals(info(0x01, 0x02, 0x86, 0x85, 0x82, 0x83, 0x96, 0xA3), glasses.payloads[1])
        assertContentEquals(
            info(0xFE, 0x96, 0x98, 0x93, 0x9A, 0x8D, 0x8E, 0x82, 0x83, 0x9F, 0x8A, 0x9B),
            glasses.payloads[2],
        )
        val state = session.state.value
        assertEquals(7, state.wifiSupportMask)
        assertContentEquals(byteArrayOf(0x34, 0x12), state.deviceAbility)
        assertEquals(0x1234, state.deviceCapabilityMask)
        assertEquals(TuneBudsScreenConfig(isSupported = true, isLandscape = true), state.screenConfig)
        assertEquals(listOf(5, 7), state.currentVolume)
        assertEquals(300, state.videoLimitSeconds)
        assertEquals(1, state.cameraCoprocessorType)
        // The glasses asked for 30-byte packets, but the link takes 20.
        assertTrue(glasses.packets.all { it.size <= 20 })
    }

    @Test
    fun refreshStatusReadsEverythingInTheVendorOrder() = runTest {
        val glasses = FakeGlasses()
        val session = sessionWith(glasses)

        session.refreshStatus()

        assertEquals(listOf(0x27, 0x27, 0xE0, 0xEA, 0xEF, 0xE9), glasses.requests)
        assertContentEquals(info(0x01, 0x02, 0x86, 0x85, 0xA3), glasses.payloads[0])
    }

    @Test
    fun notificationsUpdateTheStateAndClearAnEarlierError() = runTest {
        val glasses = FakeGlasses()
        val session = sessionWith(glasses)
        session.reportError("Could not read storage")
        assertEquals("Could not read storage", session.state.value.lastError)

        glasses.notify(TuneBudsProtocol.CMD_STORAGE, byteArrayOf(100, 0, 0, 0, 50, 0, 0, 0))
        assertEquals(TuneBudsStorageInfo(100, 50), session.state.value.storage)
        assertNull(session.state.value.lastError)

        glasses.notify(TuneBudsProtocol.CMD_MEDIA_COUNTS, byteArrayOf(3, 0, 0, 0, 2, 0, 0, 0, 1, 0, 0, 0))
        glasses.notify(TuneBudsProtocol.CMD_COPROCESSOR_VERSION, "V821".encodeToByteArray())
        glasses.notify(TuneBudsProtocol.CMD_CAMERA_COPROCESSOR_INFO, byteArrayOf(2))
        glasses.notify(TuneBudsProtocol.CMD_WIFI_STATE, byteArrayOf(4))
        glasses.notify(TuneBudsProtocol.INFO_BATTERY, byteArrayOf(40))
        val state = session.state.value
        assertEquals(TuneBudsMediaCounts(3, 2, 1), state.mediaCounts)
        assertEquals("V821", state.coprocessorVersion)
        assertEquals(2, state.cameraCoprocessorType)
        assertEquals(4, state.wifiState)
        assertEquals(40, state.batteryPercent)

        session.clearWifiState()
        assertNull(session.state.value.wifiState)
    }
}
