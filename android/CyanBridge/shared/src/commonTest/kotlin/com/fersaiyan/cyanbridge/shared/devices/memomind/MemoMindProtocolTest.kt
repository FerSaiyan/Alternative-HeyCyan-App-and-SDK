package com.fersaiyan.cyanbridge.shared.devices.memomind

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Byte-exact vectors from the MemoMind open platform (GlassSDK/docs/WIRE_EXAMPLES.md, PROTOCOL.md), event ID 1. */
class MemoMindProtocolTest {
    private fun hex(text: String): ByteArray =
        text.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun encode(request: GmRequest): ByteArray = GmCodec.encode(request.withEvent(1)).single()

    private val wireExamples: List<Pair<String, GmRequest>> = listOf(
        "FA 00 00 09 01 01 01 01 06" to MemoMindCommands.heartbeat(),
        "FA 00 00 09 01 01 02 01 07" to MemoMindCommands.deviceInfo(),
        "FA 00 00 09 01 01 06 01 0B" to MemoMindCommands.deviceStatus(),
        "FA 00 00 0E 01 01 0E 08 00 00 01 05 01 26" to MemoMindCommands.brightness(5),
        "FA 00 00 0E 01 01 0F 08 00 00 01 01 01 23" to MemoMindCommands.autoBrightness(true),
        "FA 00 00 0E 01 01 0A 08 00 00 01 04 01 21" to MemoMindCommands.screenHeight(4),
        "FA 00 00 0E 01 01 0B 08 00 00 01 04 01 22" to MemoMindCommands.opticalDistance(4),
        "FA 00 00 13 01 01 2F 08 00 00 01 00 08 00 00 01 00 01 50" to
            MemoMindCommands.recordingProcessing(MemoMindNoiseMode.NOISE_REDUCTION, MemoMindPickupMode.FRONT_FIXED),
        "FA 00 00 0E 01 01 2F 08 00 00 01 01 01 43" to MemoMindCommands.recordingProcessing(MemoMindNoiseMode.RAW),
        "FA 00 00 0E 01 01 76 08 00 00 01 00 01 89" to MemoMindCommands.mediaControl(MemoMindMediaOperation.PLAY),
        "FA 00 00 0E 01 01 76 08 00 00 01 01 01 8A" to MemoMindCommands.mediaControl(MemoMindMediaOperation.PAUSE),
        "FA 00 00 13 01 01 76 08 00 00 01 05 08 00 00 01 32 01 CE" to
            MemoMindCommands.mediaControl(MemoMindMediaOperation.SET_VOLUME, 50),
        "FA 00 00 11 01 03 01 02 00 00 04 44 65 6D 6F 02 9B" to MemoMindCommands.translationStart("Demo"),
        "FA 00 00 1B 01 03 04 05 00 00 0E 5B 22 48 65 6C 6C 6F 22 2C 22 48 69 22 5D 05 41" to
            MemoMindCommands.translationResult(translated = "Hello", source = "Hi", final = true),
        "FA 00 00 09 01 03 02 01 09" to MemoMindCommands.translationStop(),
        "FA 00 00 20 01 04 01 05 00 00 13 5B 36 30 30 2C 33 35 30 2C 33 2C 31 2C 31 2C 32 2C 30 5D 05 1D" to
            MemoMindCommands.teleprompterStart(MemoMindTeleprompterLayout(scene = 0)),
        "FA 00 00 1B 01 04 05 08 00 00 01 01 05 00 00 09 5B 22 48 65 6C 6C 6F 22 5D 04 27" to
            MemoMindCommands.teleprompterText(listOf("Hello")),
        "FA 00 00 12 01 04 06 05 00 00 05 5B 30 2C 35 5D 02 6A" to MemoMindCommands.teleprompterHighlight(0, 5),
        "FA 00 00 09 01 04 02 01 0A" to MemoMindCommands.teleprompterStop(),
        "FA 00 00 65 01 05 01 08 00 00 01 01 05 00 00 53 7B 22 69 64 22 3A 31 2C 22 61 22 3A 22 44 65 6D 6F 22 2C " +
            "22 74 79 70 65 22 3A 30 2C 22 74 73 22 3A 30 2C 22 63 22 3A 22 48 65 6C 6C 6F 22 2C 22 74 69 22 3A 22 53 " +
            "44 4B 22 2C 22 70 6B 67 5F 6E 61 6D 65 22 3A 22 65 78 61 6D 70 6C 65 2E 73 64 6B 22 7D 19 EB" to
            MemoMindCommands.notification(
                id = 1, appName = "Demo", title = "SDK", content = "Hello",
                packageName = "example.sdk", timestampSeconds = 0,
            ),
        "FA 00 00 09 01 01 65 01 6A" to MemoMindCommands.screenshot(),
        "FA 00 00 16 01 0F 28 07 00 00 02 00 01 01 00 00 03 41 42 43 02 1C" to
            MemoMindCommands.appMessage(1, "ABC".encodeToByteArray()),
        "FA 00 00 14 01 0F 28 07 00 00 02 00 01 01 00 00 01 00 01 52" to
            MemoMindCommands.appMessage(MemoMindWebBridge.CHANNEL_CLEAR, MemoMindWebBridge.clear()),
        "FA 00 00 20 01 0F 28 07 00 00 02 00 02 01 00 00 0D 01 00 0A 00 14 00 C8 00 28 00 00 48 69 03 2B" to
            MemoMindCommands.appMessage(MemoMindWebBridge.CHANNEL_TEXT, MemoMindWebBridge.text(1, 10, 20, 200, 40, "Hi")),
        "FA 00 00 1E 01 0F 28 07 00 00 02 00 03 01 00 00 0B 02 00 0A 00 14 00 C8 00 28 01 00 02 79" to
            MemoMindCommands.appMessage(MemoMindWebBridge.CHANNEL_RECT, MemoMindWebBridge.rectangle(2, 10, 20, 200, 40)),
        "FA 00 00 1D 01 0F 28 07 00 00 02 00 05 01 00 00 0A 03 00 00 00 00 00 64 00 32 01 02 02" to
            MemoMindCommands.appMessage(MemoMindWebBridge.CHANNEL_LINE, MemoMindWebBridge.line(3, 0, 0, 100, 50)),
        "FA 00 00 1F 01 0F 28 07 00 00 02 00 06 01 00 00 0C 00 00 00 00 00 02 00 02 00 01 F0 0F 02 71" to
            MemoMindCommands.appMessage(
                MemoMindWebBridge.CHANNEL_BITMAP,
                MemoMindWebBridge.bitmap(0, 0, 2, 2, 1, byteArrayOf(0xF0.toByte(), 0x0F)),
            ),
        "FA 00 00 14 01 0F 28 07 00 00 02 00 04 01 00 00 01 01 01 56" to
            MemoMindCommands.appMessage(MemoMindWebBridge.CHANNEL_DELETE, MemoMindWebBridge.delete(1)),
    )

    @Test
    fun requestsEncodeToThePublishedWireExamples() {
        wireExamples.forEach { (expected, request) ->
            assertContentEquals(hex(expected), encode(request), "0x${request.service.toString(16)}/0x${request.command.toString(16)}")
        }
    }

    @Test
    fun publishedExamplesDecodeBackToTheirRequests() {
        val decoder = GmFrameDecoder()
        val stream = wireExamples.map { hex(it.first) }.reduce(ByteArray::plus)
        // Arbitrary read boundaries, as on an SPP byte stream.
        val messages = stream.toList().chunked(7).flatMap { decoder.append(it.toByteArray()) }
        assertEquals(wireExamples.map { it.second.withEvent(1) }, messages)
        assertEquals(0, decoder.malformedCount)
    }

    @Test
    fun aMessageLongerThanOneFrameMatchesThePublishedTwoFrameExample() {
        val frames = GmCodec.encode(MemoMindCommands.appMessage(1, ByteArray(600) { 'A'.code.toByte() }).withEvent(1))

        assertEquals(listOf(512, 116), frames.map { it.size })
        assertContentEquals(hex("FA 00 02 6B 01 0F 28 07 00 00 02 00 01 01 00 02 58"), frames[0].copyOf(17))
        assertContentEquals(hex("7F 31"), frames[0].copyOfRange(510, 512))
        assertContentEquals(hex("01 00 02 6B 01 0F 28"), frames[1].copyOf(7))
        assertContentEquals(hex("1B D1"), frames[1].copyOfRange(114, 116))
        val decoded = GmFrameDecoder().append(frames[0] + frames[1]).single()
        assertEquals(600, decoded.first(GmTlvType.BYTES)!!.value.size)
    }

    @Test
    fun responsesUseStatusOrPluginInt8() {
        val decoder = GmFrameDecoder()
        val system = decoder.append(hex("FA 00 00 0E 01 01 0E 06 00 00 01 00 01 1F")).single()
        val plugin = decoder.append(hex("FA 00 00 16 01 0F 28 08 00 00 01 00 03 00 00 04 00 00 00 00 01 58")).single()

        assertEquals(MemoMindStatus.SUCCESS, system.status)
        assertEquals(GmTlvType.STATUS, system.tlvs.single().type)
        assertEquals(MemoMindPluginStatus.OK, plugin.status)
        assertEquals(listOf(GmTlvType.INT8, GmTlvType.INT32), plugin.tlvs.map { it.type })
    }

    @Test
    fun aDamagedFrameIsSkippedAndTheNextOneDecodes() {
        val damaged = hex("FA 00 00 09 01 01 01 01 07")
        val good = hex("FA 00 00 09 01 01 02 01 07")
        val decoder = GmFrameDecoder()

        assertEquals(listOf(GmMessage(1, 1, 2)), decoder.append(hex("00 13") + damaged + good))
        assertTrue(decoder.malformedCount > 0)
    }

    @Test
    fun recordingControlsAndBothRecordFormats() {
        assertContentEquals(hex("52 12 00"), MemoMindRecordingControl.selectFormat(compact = false))
        assertContentEquals(hex("52 12 01"), MemoMindRecordingControl.selectFormat(compact = true))
        assertContentEquals(hex("52 01 00"), MemoMindRecordingControl.start())
        assertContentEquals(hex("52 00 00"), MemoMindRecordingControl.stop())

        val opus = ByteArray(40) { it.toByte() }
        val header = hex("52 92 00 00 09 FF 01 00 00 00 00 FF")
        val legacy = header + ByteArray(4) + byteArrayOf(0x28) + ByteArray(4) + opus
        val compact = header + opus

        val legacyParser = MemoMindRecordingParser(compact = false)
        val messages = legacyParser.append(hex("52 13 01 31 28 00 52 11 00 01 02 03") + legacy.copyOf(30)) +
            legacyParser.append(legacy.copyOfRange(30, legacy.size))
        assertEquals(MemoMindRecordingMessage.FormatSelected(compact = true), messages[0])
        assertEquals(MemoMindRecordingMessage.Started(noiseMode = 0, timestamp = 0x010203), messages[1])
        val audio = assertIs<MemoMindRecordingMessage.Audio>(messages[2])
        assertEquals(0x92, audio.streamCommand)
        assertContentEquals(opus, audio.frames.single())

        val compactFrames = MemoMindRecordingParser(compact = true).append(hex("7F") + compact)
        assertContentEquals(opus, assertIs<MemoMindRecordingMessage.Audio>(compactFrames.single()).frames.single())
    }
}
