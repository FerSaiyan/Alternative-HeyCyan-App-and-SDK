package com.fersaiyan.cyanbridge.devices.tunebuds

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TuneBudsProtocolTest {
    @Test
    fun requestEncodingFragmentsPayloadAndWrapsSequence() {
        val payload = ByteArray(31) { it.toByte() }

        val encoded = TuneBudsProtocol.encode(
            command = TuneBudsProtocol.CMD_DEVICE_INFO,
            payload = payload,
            initialSequence = 14,
        )

        assertEquals(3, encoded.frames.size)
        assertEquals(1, encoded.nextSequence)
        assertArrayEquals(
            byteArrayOf(14, 0x27, 1, 0x20, 15) + payload.copyOfRange(0, 15),
            encoded.frames[0],
        )
        assertArrayEquals(
            byteArrayOf(15, 0x27, 1, 0x21, 15) + payload.copyOfRange(15, 30),
            encoded.frames[1],
        )
        assertArrayEquals(
            byteArrayOf(0, 0x27, 1, 0x22, 1) + payload.copyOfRange(30, 31),
            encoded.frames[2],
        )
    }

    @Test
    fun decoderReassemblesFragmentedRfcommReads() {
        val payload = ByteArray(12) { (it + 1).toByte() }
        val encoded = TuneBudsProtocol.encode(
            command = TuneBudsProtocol.CMD_STORAGE,
            payload = payload,
            type = TuneBudsFrameType.NOTIFICATION,
            maxPacketSize = 10,
            initialSequence = 15,
        )
        val stream = encoded.frames.reduce(ByteArray::plus)
        val decoder = TuneBudsFrameDecoder()

        assertTrue(decoder.append(stream.copyOfRange(0, 4)).isEmpty())
        assertTrue(decoder.append(stream.copyOfRange(4, 13)).isEmpty())
        val result = decoder.append(stream.copyOfRange(13, stream.size))

        assertEquals(1, result.size)
        assertEquals(TuneBudsProtocol.CMD_STORAGE, result.single().command)
        assertEquals(TuneBudsFrameType.NOTIFICATION, result.single().type)
        assertArrayEquals(payload, result.single().payload)
        assertEquals(0, decoder.malformedFrameCount)
    }

    @Test
    fun decoderRejectsChangedFragmentMetadata() {
        val decoder = TuneBudsFrameDecoder()
        val first = byteArrayOf(0, 0x27, 2, 0x10, 1, 0x01)
        val changedCommand = byteArrayOf(1, 0x28, 2, 0x11, 1, 0x02)

        val result = decoder.append(first + changedCommand)

        assertTrue(result.isEmpty())
        assertEquals(1, decoder.malformedFrameCount)
    }

    @Test
    fun decoderSkipsAiEnvelopeWithoutTreatingItAsCommandFrame() {
        val decoder = TuneBudsFrameDecoder()
        val aiEnvelope = byteArrayOf(0x55, 0xAA.toByte(), 1, 2, 3, 0xA5.toByte(), 0x5A)
        val response = TuneBudsProtocol.encode(
            command = TuneBudsProtocol.CMD_WORK_STATE,
            payload = byteArrayOf(3),
            type = TuneBudsFrameType.RESPONSE,
        ).frames.single()

        val frames = decoder.append(aiEnvelope + response)

        assertEquals(1, frames.size)
        assertEquals(3, TuneBudsProtocol.parseStatus(frames.single().payload))
        assertEquals(0, decoder.malformedFrameCount)
    }

    @Test
    fun payloadHelpersMatchVendorLittleEndianFormats() {
        val info = TuneBudsProtocol.parseDeviceInfo(
            byteArrayOf(
                0x01, 0x01, 0xB3.toByte(),
                0x02, 0x04, 0x06, 0x00, 0x01, 0x00,
            ),
        )
        assertEquals(TuneBudsBattery(51, true), TuneBudsProtocol.parseBattery(info.getValue(1)))
        assertEquals("0.1.0.6", TuneBudsProtocol.parseFirmwareVersion(info.getValue(2)))

        assertEquals(
            TuneBudsStorageInfo(60, 418),
            TuneBudsProtocol.parseStorage(
                byteArrayOf(60, 0, 0, 0, 0xA2.toByte(), 1, 0, 0),
            ),
        )
        assertEquals(
            TuneBudsMediaCounts(1, 2, 3),
            TuneBudsProtocol.parseMediaCounts(
                byteArrayOf(1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0),
            ),
        )
    }

    @Test
    fun capabilityPayloadsUseVendorLittleEndianFormats() {
        assertEquals(0x1234, TuneBudsProtocol.parseUnsignedValue(byteArrayOf(0x34, 0x12)))
        assertEquals(0x1234, TuneBudsProtocol.parseDeviceCapabilities(byteArrayOf(0x34, 0x12)))
        assertEquals(
            TuneBudsScreenConfig(isSupported = true, isLandscape = true),
            TuneBudsProtocol.parseScreenConfig(byteArrayOf(1, 0)),
        )
        assertEquals(
            TuneBudsScreenConfig(isSupported = false, isLandscape = false),
            TuneBudsProtocol.parseScreenConfig(byteArrayOf(0, 1)),
        )
        assertEquals("QZ V821", TuneBudsProtocol.cameraCoprocessorLabel(1))
        assertEquals("unknown (9)", TuneBudsProtocol.cameraCoprocessorLabel(9))
    }

    @Test
    fun derivesClassicAddressFromManufacturerData() {
        val encoded = byteArrayOf(
            0x01, 0x02, 0x03,
            (0xFA xor 0xAD).toByte(),
            (0x00 xor 0xAD).toByte(),
            (0x11 xor 0xAD).toByte(),
            (0x15 xor 0xAD).toByte(),
            (0xA1 xor 0xAD).toByte(),
            (0x7B xor 0xAD).toByte(),
        )

        assertEquals("FA:00:11:15:A1:7B", TuneBudsProtocol.deriveClassicAddress(encoded))
    }

    @Test
    fun commandPayloadBuildersMatchReverseEngineeredLayout() {
        assertArrayEquals(
            byteArrayOf(0x00, 0xF1.toByte(), 0x53, 0x65, 0x4A, 0x01),
            TuneBudsProtocol.buildSetTimePayload(
                epochMillis = 1_700_000_000_000L,
                utcOffsetMinutes = 330,
            ),
        )
        assertArrayEquals(
            byteArrayOf(
                1, 1, 0,
                2, 4, 'T'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
                3, 2, 'p'.code.toByte(), 'w'.code.toByte(),
                4, 1, 6,
            ),
            TuneBudsProtocol.buildWifiPayload(0, "Test", "pw", 6),
        )
        assertArrayEquals(byteArrayOf(4, 10, 15), TuneBudsProtocol.rtspQualityPayload(50))
        assertArrayEquals(byteArrayOf(0, 0, 0), TuneBudsProtocol.rtspQualityPayload(-1))
    }
}
