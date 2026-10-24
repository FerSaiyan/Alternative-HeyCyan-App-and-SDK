package com.fersaiyan.cyanbridge.shared.devices.eyevue

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EyevueProtocolTest {
    @Test
    fun livePacketsMatchVendorFrames() {
        assertContentEquals(
            byteArrayOf(0xAB.toByte(), 0x55, 0x00, 0x03, 0x67, 0x30, 0x97.toByte()),
            EyevueProtocol.buildStartLiveApPacket(),
        )
        assertContentEquals(
            byteArrayOf(0xAB.toByte(), 0x55, 0x00, 0x03, 0x67, 0x31, 0x98.toByte()),
            EyevueProtocol.buildStartLiveP2pPacket(),
        )
        assertContentEquals(
            byteArrayOf(0xAB.toByte(), 0x55, 0x00, 0x04, 0x44, 0x30, 0x01, 0x75),
            EyevueProtocol.buildFinishTransferPacket(),
        )
    }

    @Test
    fun photoPacketsMatchVendorShutters() {
        // Homescreen/manual: command 34 + 48, CRC = (34 + 48) & 0xFF = 82.
        assertContentEquals(
            byteArrayOf(0xAB.toByte(), 0x55, 0x00, 0x03, 0x22, 0x30, 0x52),
            EyevueProtocol.buildManualPhotoPacket(),
        )
        // AI: command 34 + 49, CRC = (34 + 49) & 0xFF = 83.
        assertContentEquals(
            byteArrayOf(0xAB.toByte(), 0x55, 0x00, 0x03, 0x22, 0x31, 0x53.toByte()),
            EyevueProtocol.buildAiPhotoPacket(),
        )
        // Homescreen default must be the manual shutter, not the AI shutter.
        assertContentEquals(
            EyevueProtocol.buildManualPhotoPacket(),
            EyevueProtocol.buildTakePhotoPacket(),
        )
    }

    @Test
    fun videoPacketsMatchVendorStartStop() {
        // Vendor startRecord/stopRecord both send value 0; command id differs.
        // Start: command 35 + 0, CRC = 35. Stop: command 36 + 0, CRC = 36.
        assertContentEquals(
            byteArrayOf(0xAB.toByte(), 0x55, 0x00, 0x03, 0x23, 0x00, 0x23),
            EyevueProtocol.buildStartVideoPacket(),
        )
        assertContentEquals(
            byteArrayOf(0xAB.toByte(), 0x55, 0x00, 0x03, 0x24, 0x00, 0x24),
            EyevueProtocol.buildStopVideoPacket(),
        )
    }

    @Test
    fun finishPacketsMatchVendorCleanup() {
        // Vendor stopP2pWifiConnect: (48, 1) keep, (48, 0) clear, (49, n) partial.
        // CRCs: (68+48+1)=117=0x75, (68+48+0)=116=0x74, (68+49+45)=162=0xA2.
        assertContentEquals(
            byteArrayOf(0xAB.toByte(), 0x55, 0x00, 0x04, 0x44, 0x30, 0x01, 0x75),
            EyevueProtocol.buildFinishTransferPacket(),
        )
        assertContentEquals(
            byteArrayOf(0xAB.toByte(), 0x55, 0x00, 0x04, 0x44, 0x30, 0x00, 0x74),
            EyevueProtocol.buildFinishTransferAndClearPacket(),
        )
        assertContentEquals(
            byteArrayOf(0xAB.toByte(), 0x55, 0x00, 0x04, 0x44, 0x31, 0x2D, 0xA2.toByte()),
            EyevueProtocol.buildFinishTransferPartialPacket(45),
        )
    }

    @Test
    fun decoderHandlesFragmentedFrames() {
        val decoder = EyevueFrameDecoder()
        val packet = EyevueProtocol.buildStartLiveP2pPacket()

        assertTrue(decoder.append(packet.copyOfRange(0, 3)).isEmpty())
        val frames = decoder.append(packet.copyOfRange(3, packet.size))

        assertEquals(1, frames.size)
        assertEquals(EyevueProtocol.CMD_APP_LIVE, frames.single().commandId)
        assertContentEquals(byteArrayOf(0x31), frames.single().payload)
    }

    @Test
    fun parserRejectsCorruptCrc() {
        val packet = EyevueProtocol.buildStartLiveApPacket().also { it[it.lastIndex] = 0 }

        assertFailsWith<IllegalArgumentException> { EyevueProtocol.parseDatagram(packet) }
    }

    @Test
    fun parsesBatteryAndWifiResponses() {
        val battery = EyevueProtocol.parseBattery(
            EyevueFrame(EyevueProtocol.CMD_GET_BATTERY, byteArrayOf(0x07, 0x05, 0x01)),
        )
        assertEquals(75, battery?.percent)
        assertTrue(battery?.isCharging == true)

        val wifi = EyevueProtocol.parseWifiSsid(
            EyevueFrame(EyevueProtocol.CMD_RECEIVE_WIFI_INFO, "Eyevue-AP\u0000".encodeToByteArray()),
        )
        assertEquals("Eyevue-AP", wifi)
    }

    @Test
    fun parsesInboundAc55FramesFromHardware() {
        // Real bytes captured from the glasses over AA14. Inbound frames use
        // AC55 (vendor SOF_BLE_APP), not the AB55 we send outbound.
        // Battery reply: command 23, payload 38 32 00 -> 82%, not charging.
        // CRC check: (23 + 0x38 + 0x32 + 0x00) & 0xFF = 129 = 0x81.
        val batteryBytes = byteArrayOf(
            0xAC.toByte(), 0x55, 0x00, 0x05, 0x17, 0x38, 0x32, 0x00, 0x81.toByte(),
        )
        val batteryFrame = EyevueProtocol.parseDatagram(batteryBytes)
        assertEquals(EyevueProtocol.CMD_GET_BATTERY, batteryFrame.commandId)
        val battery = EyevueProtocol.parseBattery(batteryFrame)
        assertEquals(82, battery?.percent)
        assertEquals(false, battery?.isCharging)

        // Thumbnail-count reply: command 66, payload 00 2D -> 45 files.
        // CRC check: (66 + 0x00 + 0x2D) & 0xFF = 111 = 0x6F.
        val countBytes = byteArrayOf(
            0xAC.toByte(), 0x55, 0x00, 0x04, 0x42, 0x00, 0x2D, 0x6F,
        )
        val countFrame = EyevueProtocol.parseDatagram(countBytes)
        assertEquals(EyevueProtocol.CMD_RECEIVE_THUMBNAIL_COUNT, countFrame.commandId)
        assertContentEquals(byteArrayOf(0x00, 0x2D), countFrame.payload)

        // The streaming decoder must also accept the inbound header.
        val decoder = EyevueFrameDecoder()
        val frames = decoder.append(countBytes)
        assertEquals(1, frames.size)
        assertEquals(EyevueProtocol.CMD_RECEIVE_THUMBNAIL_COUNT, frames.single().commandId)
    }

    @Test
    fun voiceAssistantPacketsMatchVendorBitFlags() {
        assertContentEquals(
            EyevueProtocol.valuePacket(EyevueProtocol.CMD_SET_VOICE_ASSISTANT_STATUS, 1),
            EyevueProtocol.buildSetVoiceAssistantStatusPacket(
                localOfflineSpeechEnabled = false,
                aiWakeWordEnabled = true,
            ),
        )
        val status = EyevueProtocol.parseVoiceAssistantStatus(
            EyevueFrame(EyevueProtocol.CMD_GET_VOICE_ASSISTANT_STATUS, byteArrayOf(1)),
        )
        assertEquals(false, status?.localOfflineSpeechEnabled)
        assertEquals(true, status?.aiWakeWordEnabled)
    }

    @Test
    fun photoAssemblerRemovesChunkAddressAndJoinsImageBytes() {
        val assembler = EyevuePhotoAssembler()
        assembler.append(photoPacket(EyevueProtocol.CMD_RECEIVE_PHOTO_DATA_START))
        assembler.append(photoPacket(EyevueProtocol.CMD_RECEIVE_PHOTO_DATA, byteArrayOf(0, 0, 0, 1, 0xFF.toByte(), 0xD8.toByte())))
        assembler.append(photoPacket(EyevueProtocol.CMD_RECEIVE_PHOTO_DATA, byteArrayOf(0, 0, 0, 2, 0xFF.toByte(), 0xD9.toByte())))

        val image = assembler.append(photoPacket(EyevueProtocol.CMD_RECEIVE_PHOTO_DATA_END))

        assertContentEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
            image,
        )
    }

    @Test
    fun setTimeUsesLocalWallClockFields() {
        // 2026-03-01T01:02:03Z at UTC-03:00 is 2026-02-28 22:02:03 local.
        val epochMillis = 1_772_326_923_000L
        assertContentEquals(
            EyevueProtocol.buildSetTimePacket(2026, 2, 28, 22, 2, 3),
            EyevueProtocol.buildSetTimePacket(epochMillis, utcOffsetMinutes = -180),
        )
        // Leap day, positive offset: 2024-02-29T23:30:00Z at +01:00 is 2024-03-01 00:30:00.
        assertContentEquals(
            EyevueProtocol.buildSetTimePacket(2024, 3, 1, 0, 30, 0),
            EyevueProtocol.buildSetTimePacket(1_709_249_400_000L, utcOffsetMinutes = 60),
        )
    }

    @Test
    fun parsesCustomerAndMediaIndex() {
        val customer = EyevueProtocol.parseCustomer(
            EyevueFrame(EyevueProtocol.CMD_RECEIVE_CUSTOMER, "SK01\u0000\u0000AB".encodeToByteArray() + byteArrayOf(0, 0)),
        )
        assertEquals(EyevueCustomer(project = "SK01", customer = "AB"), customer)
        assertEquals("http://192.168.1.254/?custom=1&cmd=3001&par=1", EyevueProtocol.mediaIndexUrl("192.168.1.254"))
    }

    private fun photoPacket(commandId: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        byteArrayOf(0xAB.toByte(), 0x55, 0, 0, commandId.toByte()) + payload + byteArrayOf(0, 0, 0)

    @Test
    fun photoOverflowAndCancelledStreamsRequireFreshStart() {
        val assembler = EyevuePhotoAssembler(maxImageBytes = 4)
        val start = photoPacket(EyevueProtocol.CMD_RECEIVE_PHOTO_DATA_START)
        val data = photoPacket(EyevueProtocol.CMD_RECEIVE_PHOTO_DATA, byteArrayOf(0, 0, 0, 0, 1, 2, 3))
        val end = photoPacket(EyevueProtocol.CMD_RECEIVE_PHOTO_DATA_END)
        assertEquals(null, assembler.append(data))
        assertEquals(null, assembler.append(end))
        assembler.append(start)
        assembler.append(data)
        assembler.append(data) // Overflow drops the entire image.
        assertEquals(null, assembler.append(end))
        assembler.append(start)
        assembler.append(data)
        assembler.reset()
        assertEquals(null, assembler.append(end))
        assembler.append(start)
        assembler.append(data)
        assertContentEquals(byteArrayOf(1, 2, 3), assembler.append(end))
    }
}
