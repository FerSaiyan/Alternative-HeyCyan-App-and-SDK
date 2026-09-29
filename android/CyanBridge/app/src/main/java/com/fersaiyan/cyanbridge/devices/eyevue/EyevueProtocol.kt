package com.fersaiyan.cyanbridge.devices.eyevue

import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.UUID

data class EyevueFrame(
    val commandId: Int,
    val payload: ByteArray,
)

data class EyevueBattery(
    val percent: Int,
    val isCharging: Boolean,
)

data class EyevueCustomer(
    val project: String,
    val customer: String,
)

data class EyevueVoiceAssistantStatus(
    val localOfflineSpeechEnabled: Boolean,
    val aiWakeWordEnabled: Boolean,
)

/**
 * Encapsulates the Eyevue smart glasses protocol based on reverse-engineered sources.
 *
 * Eyevue BLE datagram format:
 * [0xAB, 0x55, len_hi, len_lo, commandId, payload..., crc]
 * Where:
 * - len = payload.size + 2 (command plus CRC, 2 bytes big-endian)
 * - crc = (commandId + sum(payload)) & 0xFF
 */
object EyevueProtocol {
    // Outbound (phone -> glasses): 0xAB55. Vendor Datagram calls this SOF_APP_BLE
    // and hardcodes it in convertLeHeaderToBytes.
    const val SOF_HI = 0xAB.toByte()
    const val SOF_LO = 0x55.toByte()
    // Inbound (glasses -> phone): 0xAC55. Vendor Datagram calls this SOF_BLE_APP
    // and only parses inbound frames with this header (recoverBytesToDatagram).
    const val INBOUND_SOF_HI = 0xAC.toByte()

    val SERVICE_UUID: UUID = UUID.fromString("0000aa12-0000-1000-8000-00805f9b34fb")
    val COMMAND_WRITE_UUID: UUID = UUID.fromString("0000aa13-0000-1000-8000-00805f9b34fb")
    val COMMAND_NOTIFY_UUID: UUID = UUID.fromString("0000aa14-0000-1000-8000-00805f9b34fb")
    val PHOTO_NOTIFY_UUID: UUID = UUID.fromString("0000aa15-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_BRIGHTNESS = 1
    const val CMD_RECORD_TIME = 2
    const val CMD_WEAR_DETECT = 4
    const val CMD_GET_CAPACITY = 22
    const val CMD_GET_BATTERY = 23
    const val CMD_TAKE_PHOTO = 34
    const val CMD_RECORD_VIDEO = 35
    const val CMD_STOP_RECORD = 36
    const val CMD_CONTROL_MUSIC = 49
    const val CMD_CONTROL_VOLUME = 50
    const val CMD_RECORD_AUDIO = 52
    const val CMD_GET_WIFI_INFO = 57
    const val CMD_GET_CUSTOMER = 100
    const val CMD_APP_LIVE = 103
    const val CMD_GET_DEVICE_STATUS = 72
    const val CMD_GET_DEVICE_INFO = 85
    const val CMD_GET_VOLUME = 105
    const val CMD_GET_MEDIA_COUNT = 64
    const val CMD_SET_TIME = 89
    const val CMD_FILE_DOWNLOAD_FINISH = 68
    const val CMD_GET_SUPPORT_FUNCTION = 149
    const val CMD_STOP_VOICE_RECOGNITION = 86
    const val CMD_GET_VOICE_ASSISTANT_STATUS = 113
    const val CMD_SET_VOICE_ASSISTANT_STATUS = 114

    const val CMD_RECEIVE_BATTERY = 83
    const val CMD_RECEIVE_WIFI_INFO = 37
    const val CMD_RECEIVE_THUMBNAIL_COUNT = 66
    const val CMD_RECEIVE_CUSTOMER = 100
    const val CMD_RECEIVE_VOICE_DATA = 70
    const val CMD_RECEIVE_VOICE_DATA_START = 151
    const val CMD_RECEIVE_VOICE_DATA_END = 153
    const val CMD_RECEIVE_PHOTO_DATA_START = 151
    const val CMD_RECEIVE_PHOTO_DATA = 152
    const val CMD_RECEIVE_PHOTO_DATA_END = 153

    const val CMD_START_LIVE = CMD_APP_LIVE

    const val PARAM_LIVE_AP = 0x30.toByte()    // '0'
    const val PARAM_LIVE_P2P = 0x31.toByte()   // '1'
    const val PARAM_DOWNLOAD_FINISH = 0x30.toByte()
    // Vendor manual shutter = 48 ('0'): saves to glasses storage, flashes LED.
    // Vendor AI shutter = 49 ('1'): streams image back over Bluetooth.
    const val PARAM_PHOTO_THUMBNAIL = 0x30
    const val PARAM_PHOTO_HIGH_QUALITY = 0x31
    // Vendor startRecord/stopRecord both send value 0 (RECORD_VIDEO); the command
    // id (35 vs 36) distinguishes start from stop. Do not use 1 here.
    const val PARAM_RECORD_START = 0x00
    const val PARAM_RECORD_STOP = 0x00
    const val PARAM_AUDIO_START = 0x01
    const val PARAM_AUDIO_STOP = 0x00
    const val PARAM_WIFI_AP = 0x30
    const val PARAM_WIFI_P2P = 0x31

    /**
     * Builds a full Eyevue BLE datagram packet.
     */
    fun buildDatagram(commandId: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(commandId in 0..0xFF) { "Eyevue command must fit in one byte" }
        require(payload.size <= 0xFFFD) { "Eyevue payload is too large" }
        val len = payload.size + 2
        val packet = ByteArray(5 + payload.size + 1)
        packet[0] = SOF_HI
        packet[1] = SOF_LO
        packet[2] = ((len shr 8) and 0xFF).toByte()
        packet[3] = (len and 0xFF).toByte()
        packet[4] = (commandId and 0xFF).toByte()

        var crc = commandId and 0xFF
        for (i in payload.indices) {
            packet[5 + i] = payload[i]
            crc += (payload[i].toInt() and 0xFF)
        }
        packet[packet.size - 1] = (crc and 0xFF).toByte()
        return packet
    }

    fun parseDatagram(packet: ByteArray): EyevueFrame {
        require(packet.size >= 6) { "Eyevue packet is too short" }
        require(
            (packet[0] == SOF_HI || packet[0] == INBOUND_SOF_HI) && packet[1] == SOF_LO,
        ) { "Invalid Eyevue packet header" }
        val declaredLength = u16(packet[2], packet[3])
        require(declaredLength >= 2) { "Invalid Eyevue packet length: $declaredLength" }
        require(packet.size == declaredLength + 4) {
            "Eyevue packet length mismatch: declared=$declaredLength actual=${packet.size - 4}"
        }
        val commandId = packet[4].toInt() and 0xFF
        val payload = packet.copyOfRange(5, packet.size - 1)
        val expectedCrc = crc(commandId, payload)
        val actualCrc = packet.last().toInt() and 0xFF
        require(actualCrc == expectedCrc) {
            "Eyevue CRC mismatch: expected=$expectedCrc actual=$actualCrc"
        }
        return EyevueFrame(commandId, payload)
    }

    fun crc(commandId: Int, payload: ByteArray): Int {
        var result = commandId and 0xFF
        payload.forEach { result += it.toInt() and 0xFF }
        return result and 0xFF
    }

    fun valuePacket(commandId: Int, value: Int): ByteArray =
        buildDatagram(commandId, byteArrayOf((value and 0xFF).toByte()))

    fun twoValuePacket(commandId: Int, first: Int, second: Int): ByteArray =
        buildDatagram(
            commandId,
            byteArrayOf((first and 0xFF).toByte(), (second and 0xFF).toByte()),
        )

    fun buildGetCustomerPacket(): ByteArray = buildDatagram(CMD_GET_CUSTOMER)

    fun buildGetBatteryPacket(): ByteArray = valuePacket(CMD_GET_BATTERY, 0)

    fun buildGetCapacityPacket(): ByteArray = valuePacket(CMD_GET_CAPACITY, 0)

    fun buildGetDeviceStatusPacket(): ByteArray = valuePacket(CMD_GET_DEVICE_STATUS, 0)

    fun buildGetDeviceInfoPacket(): ByteArray = valuePacket(CMD_GET_DEVICE_INFO, 0)

    fun buildGetWifiInfoPacket(p2p: Boolean): ByteArray =
        valuePacket(CMD_GET_WIFI_INFO, if (p2p) PARAM_WIFI_P2P else PARAM_WIFI_AP)

    fun buildPhotoPacket(highQuality: Boolean): ByteArray =
        valuePacket(CMD_TAKE_PHOTO, if (highQuality) PARAM_PHOTO_HIGH_QUALITY else PARAM_PHOTO_THUMBNAIL)

    fun buildStartVideoPacket(): ByteArray = valuePacket(CMD_RECORD_VIDEO, PARAM_RECORD_START)

    fun buildStopVideoPacket(): ByteArray = valuePacket(CMD_STOP_RECORD, PARAM_RECORD_STOP)

    fun buildAudioPacket(start: Boolean): ByteArray =
        valuePacket(CMD_RECORD_AUDIO, if (start) PARAM_AUDIO_START else PARAM_AUDIO_STOP)

    fun buildFinishTransferPacket(): ByteArray =
        buildDatagram(CMD_FILE_DOWNLOAD_FINISH, byteArrayOf(PARAM_DOWNLOAD_FINISH, 0x01))

    fun buildWearDetectionPacket(enabled: Boolean): ByteArray =
        valuePacket(CMD_WEAR_DETECT, if (enabled) 0x31 else 0x30)

    fun buildStopVoiceRecognitionPacket(): ByteArray =
        valuePacket(CMD_STOP_VOICE_RECOGNITION, 0)

    fun buildGetVoiceAssistantStatusPacket(): ByteArray =
        valuePacket(CMD_GET_VOICE_ASSISTANT_STATUS, 0)

    fun buildSetVoiceAssistantStatusPacket(
        localOfflineSpeechEnabled: Boolean,
        aiWakeWordEnabled: Boolean,
    ): ByteArray {
        var value = if (localOfflineSpeechEnabled) 0 else 1
        if (!aiWakeWordEnabled) value = value or 2
        return valuePacket(CMD_SET_VOICE_ASSISTANT_STATUS, value)
    }

    fun buildRecordDurationPacket(seconds: Int): ByteArray =
        twoValuePacket(CMD_RECORD_TIME, seconds shr 8, seconds)

    fun buildSetTimePacket(epochMillis: Long = System.currentTimeMillis()): ByteArray {
        val calendar = Calendar.getInstance().apply { timeInMillis = epochMillis }
        return buildDatagram(
            CMD_SET_TIME,
            byteArrayOf(
                (calendar.get(Calendar.YEAR) - 2000).toByte(),
                (calendar.get(Calendar.MONTH) + 1).toByte(),
                calendar.get(Calendar.DAY_OF_MONTH).toByte(),
                calendar.get(Calendar.HOUR_OF_DAY).toByte(),
                calendar.get(Calendar.MINUTE).toByte(),
                calendar.get(Calendar.SECOND).toByte(),
            ),
        )
    }

    fun parseBattery(frame: EyevueFrame): EyevueBattery? {
        return when (frame.commandId) {
            CMD_GET_BATTERY -> {
                if (frame.payload.size < 2) null else EyevueBattery(
                    percent = ((frame.payload[0].toInt() and 0x0F) * 10) +
                        (frame.payload[1].toInt() and 0x0F),
                    isCharging = frame.payload.getOrNull(2)?.toInt() == 1,
                )
            }
            CMD_RECEIVE_BATTERY -> {
                if (frame.payload.size < 2) null else EyevueBattery(
                    percent = frame.payload[1].toInt() and 0xFF,
                    isCharging = frame.payload[0].toInt() == 1,
                )
            }
            else -> null
        }
    }

    fun parseWifiSsid(frame: EyevueFrame): String? {
        if (frame.commandId != CMD_RECEIVE_WIFI_INFO || frame.payload.isEmpty()) return null
        return frame.payload.toString(StandardCharsets.UTF_8)
            .trim { it <= ' ' || it == '\u0000' }
            .takeIf { it.isNotBlank() }
    }

    fun parseCustomer(frame: EyevueFrame): EyevueCustomer? {
        if (frame.commandId != CMD_RECEIVE_CUSTOMER || frame.payload.size < 8) return null
        fun readPart(offset: Int): String = frame.payload
            .copyOfRange(offset, offset + 4)
            .filter { it.toInt() != 0 }
            .toByteArray()
            .toString(StandardCharsets.UTF_8)
        return EyevueCustomer(
            project = readPart(0),
            customer = readPart(4),
        )
    }

    fun parseVoiceAssistantStatus(frame: EyevueFrame): EyevueVoiceAssistantStatus? {
        if (frame.commandId != CMD_GET_VOICE_ASSISTANT_STATUS || frame.payload.isEmpty()) return null
        val value = frame.payload[0].toInt() and 0xFF
        return EyevueVoiceAssistantStatus(
            localOfflineSpeechEnabled = value and 1 == 0,
            aiWakeWordEnabled = value and 2 == 0,
        )
    }

    private fun u16(high: Byte, low: Byte): Int =
        ((high.toInt() and 0xFF) shl 8) or (low.toInt() and 0xFF)

    /** Start Live streaming over AP mode. */
    fun buildStartLiveApPacket(): ByteArray = buildDatagram(CMD_APP_LIVE, byteArrayOf(PARAM_LIVE_AP))

    /** Start Live streaming over P2P mode. */
    fun buildStartLiveP2pPacket(): ByteArray = buildDatagram(CMD_APP_LIVE, byteArrayOf(PARAM_LIVE_P2P))

    /** Stop live stream / complete file transfer. */
    fun buildExitLivePacket(): ByteArray = buildFinishTransferPacket()

    /**
     * Homescreen/manual shutter: `AB 55 00 03 22 30 52`.
     * Saves to glasses storage and flashes the picture LED.
     */
    fun buildManualPhotoPacket(): ByteArray = buildPhotoPacket(highQuality = false)

    /**
     * AI shutter: `AB 55 00 03 22 31 53`.
     * Streams the image back over Bluetooth instead of saving.
     */
    fun buildAiPhotoPacket(): ByteArray = buildPhotoPacket(highQuality = true)

    /** Homescreen photo snapshot (manual shutter). */
    fun buildTakePhotoPacket(): ByteArray = buildManualPhotoPacket()

    /** Start video recording. */
    fun buildRecordVideoPacket(): ByteArray = buildStartVideoPacket()

    /** Stop video recording. */
    fun buildStopRecordPacket(): ByteArray = buildStopVideoPacket()

    /** Returns the primary HTTP index URL for Eyevue glasses (SK series P2P XML index). */
    fun eyevueMediaIndexUrl(deviceIp: String): String = "http://$deviceIp/?custom=1&cmd=3001&par=1"

    /**
     * Parses filenames (JPG, MP4, OPUS) from Eyevue XML responses or plain text fallback.
     */
    fun parseMediaListFromXml(content: String): List<String> {
        val fileNames = mutableListOf<String>()
        try {
            val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(java.io.StringReader(content))
            var eventType = parser.eventType
            var currentTag = ""
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> currentTag = parser.name
                    org.xmlpull.v1.XmlPullParser.TEXT -> {
                        if (currentTag == "NAME" || currentTag == "FPATH") {
                            val text = parser.text.trim()
                            if (text.isNotBlank() && (
                                    text.endsWith(".jpg", ignoreCase = true) ||
                                        text.endsWith(".jpeg", ignoreCase = true) ||
                                        text.endsWith(".mp4", ignoreCase = true) ||
                                        text.endsWith(".opus", ignoreCase = true)
                                )) {
                                val fileName = text.substringAfterLast('\\').substringAfterLast('/')
                                if (fileName !in fileNames) fileNames.add(fileName)
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            // Fallback line parsing for filenames in plaintext/config format
            content.lineSequence()
                .map { it.trim() }
                .filter { it.endsWith(".jpg", ignoreCase = true) || it.endsWith(".mp4", ignoreCase = true) || it.endsWith(".opus", ignoreCase = true) }
                .forEach { if (it !in fileNames) fileNames.add(it) }
        }
        return fileNames
    }
}

/** Buffers BLE notifications so frames split across ATT packets are decoded safely. */
class EyevueFrameDecoder {
    private val buffer = ByteArrayOutputStream()

    fun append(chunk: ByteArray): List<EyevueFrame> {
        if (chunk.isEmpty()) return emptyList()
        buffer.write(chunk)
        val bytes = buffer.toByteArray()
        val frames = mutableListOf<EyevueFrame>()
        var cursor = 0

        while (true) {
            // Accept both outbound (AB55, used in tests) and inbound (AC55,
            // what the glasses actually send) headers.
            while (cursor + 1 < bytes.size &&
                ((bytes[cursor] != EyevueProtocol.SOF_HI &&
                    bytes[cursor] != EyevueProtocol.INBOUND_SOF_HI) ||
                    bytes[cursor + 1] != EyevueProtocol.SOF_LO)
            ) {
                cursor++
            }
            if (bytes.size - cursor < 4) break

            val length = ((bytes[cursor + 2].toInt() and 0xFF) shl 8) or
                (bytes[cursor + 3].toInt() and 0xFF)
            if (length < 2) {
                cursor++
                continue
            }
            val frameSize = length + 4
            if (bytes.size - cursor < frameSize) break

            val candidate = bytes.copyOfRange(cursor, cursor + frameSize)
            runCatching { EyevueProtocol.parseDatagram(candidate) }
                .onSuccess(frames::add)
            cursor += frameSize
        }

        buffer.reset()
        if (cursor < bytes.size) buffer.write(bytes, cursor, bytes.size - cursor)
        return frames
    }

    fun reset() = buffer.reset()
}

/** Reassembles the AA15 photo stream used by the vendor's AI-photo path. */
class EyevuePhotoAssembler {
    private val buffer = ByteArrayOutputStream()
    private var receiving = false

    fun append(packet: ByteArray): ByteArray? {
        if (packet.size < 8) return null
        val commandId = packet[4].toInt() and 0xFF
        val payloadEnd = packet.size - 3
        if (payloadEnd < 5) return null

        return when (commandId) {
            EyevueProtocol.CMD_RECEIVE_PHOTO_DATA_START -> {
                buffer.reset()
                receiving = true
                null
            }

            EyevueProtocol.CMD_RECEIVE_PHOTO_DATA -> {
                // Each photo-data payload starts with a four-byte chunk address.
                val imageStart = 9
                if (receiving && payloadEnd > imageStart) {
                    buffer.write(packet, imageStart, payloadEnd - imageStart)
                }
                null
            }

            EyevueProtocol.CMD_RECEIVE_PHOTO_DATA_END -> {
                if (!receiving) return null
                receiving = false
                buffer.toByteArray().takeIf { it.isNotEmpty() }.also { buffer.reset() }
            }

            else -> null
        }
    }

    fun reset() {
        receiving = false
        buffer.reset()
    }
}
