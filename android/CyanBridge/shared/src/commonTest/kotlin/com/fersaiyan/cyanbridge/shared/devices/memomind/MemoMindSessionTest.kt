package com.fersaiyan.cyanbridge.shared.devices.memomind

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoMindSessionTest {
    /** Answers like the glasses: flow control for partial messages, then canned responses. */
    private class FakeGlasses(
        override val maxFrameBytes: Int = GmCodec.MAX_FRAME_BYTES,
        override val awaitsFragmentAcks: Boolean = false,
    ) : MemoMindTransport, MemoMindRecordingLink {
        lateinit var session: MemoMindSession
        val requests = mutableListOf<GmMessage>()
        val recordingWrites = mutableListOf<ByteArray>()
        var flowControlReplies = 0
        var recordingOpen = false
        var recordingServiceMissing = false
        var onRequest: (GmMessage) -> Unit = {}
        private val decoder = GmFrameDecoder(maxFrameBytes)

        override val isConnected = true
        override val recordingLink: MemoMindRecordingLink = this

        override suspend fun writeCommand(frame: ByteArray) {
            assertTrue(frame.size <= maxFrameBytes)
            val complete = decoder.append(frame)
            if (complete.isEmpty()) {
                flowControlReplies++
                reply(GmMessage(frame[4].toInt() and 0xFF, frame[5].toInt() and 0xFF, frame[6].toInt() and 0xFF))
                return
            }
            complete.forEach { request ->
                requests += request
                reply(request.copy(tlvs = responseTo(request)))
                onRequest(request)
            }
        }

        private fun responseTo(request: GmMessage): List<GmTlv> = when (request.service to request.command) {
            MemoMindService.SYSTEM to MemoMindCommand.DEVICE_INFO -> listOf(
                GmTlv.string(
                    """{"pver":2,"model":"H001Y","sn":"SN1","name":"MemoMind One 209","ver":"1.0.0.267","cap":5}""",
                ),
            )
            MemoMindService.SYSTEM to MemoMindCommand.DEVICE_STATUS ->
                listOf(GmTlv.string("""{"bat":21,"charging":1,"brt":5,"autobrt":1,"pos":2,"c_bat":0}"""))
            MemoMindService.PLUGIN to MemoMindCommand.APP_MESSAGE ->
                listOf(GmTlv.int8(MemoMindPluginStatus.OK), GmTlv.int32(0))
            else -> listOf(GmTlv.status(MemoMindStatus.SUCCESS))
        }

        fun reply(message: GmMessage) = GmCodec.encode(message).forEach(session::onCommandBytes)

        fun pluginEvent(channel: Int, data: ByteArray) =
            reply(GmMessage(0x42, MemoMindService.PLUGIN, MemoMindCommand.APP_MESSAGE_FROM_GLASSES, listOf(GmTlv.int16(channel), GmTlv.bytes(data))))

        override suspend fun open() {
            if (recordingServiceMissing) throw IllegalStateException("Service discovery failed")
            recordingOpen = true
        }

        override suspend fun write(bytes: ByteArray) {
            recordingWrites += bytes
            when {
                bytes.contentEquals(MemoMindRecordingControl.selectFormat(compact = true)) ->
                    session.onRecordingBytes(byteArrayOf(0x52, 0x13, 0x01, 0x31, 0x28, 0x00))
                bytes.contentEquals(MemoMindRecordingControl.start()) -> {
                    session.onRecordingBytes(byteArrayOf(0x52, 0x11, 0x00, 0x00, 0x00, 0x01))
                    val header = byteArrayOf(0x52, 0x92.toByte(), 0, 0, 0x09, 0xFF.toByte(), 2, 0, 0, 0, 0, 0xFF.toByte())
                    session.onRecordingBytes(header + ByteArray(40) { 1 } + ByteArray(40) { 2 })
                }
            }
        }

        override suspend fun close() {
            recordingOpen = false
        }
    }

    private fun sessionWith(glasses: FakeGlasses) = MemoMindSession(glasses).also { glasses.session = it }

    @Test
    fun initializeRunsTheBringUpAndReadsBatteryAndDeviceInfo() = runTest {
        val glasses = FakeGlasses()
        val session = sessionWith(glasses)

        session.initialize()

        assertEquals(
            listOf(MemoMindCommand.HEARTBEAT, MemoMindCommand.DEVICE_INFO, MemoMindCommand.DEVICE_STATUS),
            glasses.requests.map { it.command },
        )
        assertEquals(listOf(1, 2, 3), glasses.requests.map { it.eventId })
        val state = session.state.value
        assertEquals("MemoMind One 209", state.deviceInfo?.name)
        assertEquals("1.0.0.267", state.deviceInfo?.firmwareVersion)
        assertEquals(21, state.batteryPercent)
        assertTrue(state.isCharging)
    }

    @Test
    fun fragmentedRequestsWaitForFlowControlAndPluginEventsArriveInBetween() = runTest {
        val glasses = FakeGlasses(maxFrameBytes = 20, awaitsFragmentAcks = true)
        val session = sessionWith(glasses)
        val button = async(start = CoroutineStart.UNDISPATCHED) {
            session.events.first { it is MemoMindEvent.Button }
        }
        glasses.onRequest = {
            // Primary long press from the Web Bridge plugin: version 1, type 1, sequence 7.
            glasses.pluginEvent(MemoMindWebBridge.CHANNEL_BUTTON, byteArrayOf(1, 1, 0, 0, 0, 7, 0, 0, 0, 9, 0, 1, 0, 3))
        }
        val payload = ByteArray(100) { it.toByte() }

        val status = session.sendAppMessage(0x4647, payload)

        assertEquals(MemoMindPluginStatus.OK, status)
        assertContentEquals(payload, glasses.requests.single().first(GmTlvType.BYTES)!!.value)
        assertEquals(9, glasses.flowControlReplies) // 110 body bytes in ten 11-byte fragments.
        val event = button.await()
        assertEquals(MemoMindEvent.Button(MemoMindButton.PRIMARY, MemoMindButtonAction.LONG, sequence = 7), event)
        assertTrue(MemoMindAssistantTrigger.isVoiceQuestion(event))
        assertEquals(true, session.state.value.webBridgeRunning)
    }

    @Test
    fun microphoneNegotiatesTheFormatAndDeliversOpusUntilStopped() = runTest {
        val glasses = FakeGlasses()
        val session = sessionWith(glasses)
        val frames = mutableListOf<ByteArray>()
        session.audioListener = MemoMindAudioListener { frames += it }

        session.startMicrophone()

        assertEquals(MemoMindCommand.RECORDING_PROCESSING, glasses.requests.single().command)
        assertEquals(MemoMindMicrophoneState.STREAMING, session.state.value.microphone)
        assertEquals(true, session.state.value.compactRecording)
        assertEquals(listOf(1, 2), frames.map { it.first().toInt() })

        session.stopMicrophone()

        assertContentEquals(MemoMindRecordingControl.stop(), glasses.recordingWrites.last())
        assertEquals(false, glasses.recordingOpen)
        assertEquals(MemoMindMicrophoneState.IDLE, session.state.value.microphone)
    }

    @Test
    fun aUserThatLeavesAfterAnotherTookOverDoesNotStopTheCapture() = runTest {
        val glasses = FakeGlasses()
        val session = sessionWith(glasses)
        val plugin = Any()
        val question = Any()
        val questionFrames = mutableListOf<ByteArray>()
        session.joinMicrophone(plugin, {})

        session.joinMicrophone(question, { questionFrames += it })
        session.leaveMicrophone(plugin) // the suspended plugin's late pause
        session.onRecordingBytes(byteArrayOf(0x52, 0x92.toByte(), 0, 0, 0x09, 0xFF.toByte(), 1, 0, 0, 0, 0, 0xFF.toByte()) + ByteArray(40) { 3 })

        assertEquals(1, glasses.recordingWrites.count { it.contentEquals(MemoMindRecordingControl.start()) })
        assertEquals(MemoMindMicrophoneState.STREAMING, session.state.value.microphone)
        assertEquals(listOf(3), questionFrames.map { it.first().toInt() })
        session.leaveMicrophone(question)
        assertEquals(MemoMindMicrophoneState.IDLE, session.state.value.microphone)
    }

    @Test
    fun aFailedStartMakesTheRecordingLinkUnavailableUntilTheNextConnection() = runTest {
        val glasses = FakeGlasses().apply { recordingServiceMissing = true }
        val session = sessionWith(glasses)
        assertTrue(session.recordingAvailable)

        assertFailsWith<IllegalStateException> { session.joinMicrophone(Any(), {}) }

        assertFalse(session.recordingAvailable)
        assertEquals(MemoMindMicrophoneState.IDLE, session.state.value.microphone)
        session.onDisconnected()
        assertTrue(session.recordingAvailable)
    }
}
