package com.fersaiyan.cyanbridge.shared.devices.eyevue

import com.fersaiyan.cyanbridge.shared.devices.GlassesPhotoLimits
import com.fersaiyan.cyanbridge.shared.platform.platformCurrentTimeMillis
import com.fersaiyan.cyanbridge.shared.platform.platformUtcOffsetMinutes
import kotlin.jvm.Synchronized

data class EyevueFrame(val commandId: Int, val payload: ByteArray)

data class EyevueBattery(val percent: Int, val isCharging: Boolean)

data class EyevueCustomer(val project: String, val customer: String)

data class EyevueVoiceAssistantStatus(
    val localOfflineSpeechEnabled: Boolean,
    val aiWakeWordEnabled: Boolean,
)

/**
 * Eyevue smart glasses BLE protocol, reverse-engineered from the vendor app.
 *
 * Datagram format: `[SOF_HI, 0x55, len_hi, len_lo, commandId, payload..., crc]` where
 * len = payload.size + 2 (command plus CRC, big-endian) and crc = (commandId + sum(payload)) & 0xFF.
 * GATT UUIDs are lowercase 128-bit strings.
 */
object EyevueProtocol {
    // Outbound (phone -> glasses): 0xAB55. Vendor Datagram calls this SOF_APP_BLE
    // and hardcodes it in convertLeHeaderToBytes.
    const val SOF_HI = 0xAB.toByte()
    const val SOF_LO = 0x55.toByte()
    // Inbound (glasses -> phone): 0xAC55. Vendor Datagram calls this SOF_BLE_APP
    // and only parses inbound frames with this header (recoverBytesToDatagram).
    const val INBOUND_SOF_HI = 0xAC.toByte()

    const val SERVICE_UUID = "0000aa12-0000-1000-8000-00805f9b34fb"
    const val COMMAND_WRITE_UUID = "0000aa13-0000-1000-8000-00805f9b34fb"
    const val COMMAND_NOTIFY_UUID = "0000aa14-0000-1000-8000-00805f9b34fb"
    const val PHOTO_NOTIFY_UUID = "0000aa15-0000-1000-8000-00805f9b34fb"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

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
    // Vendor fileDownloadFinish variants (qfc.stopP2pWifiConnect):
    // (48, 1) = done, keep glasses files; (48, 0) = done, CLEAR glasses files;
    // (49, downloadedCount) = partial transfer.
    const val PARAM_WIFI_FILE_DOWNLOAD_CLEAR = 0x00.toByte()
    const val PARAM_DOWNLOAD_FINISH_NUN = 0x31.toByte()
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

    fun buildDatagram(commandId: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(commandId in 0..0xFF) { "Eyevue command must fit in one byte" }
        require(payload.size <= 0xFFFD) { "Eyevue payload is too large" }
        val length = payload.size + 2
        val packet = ByteArray(5 + payload.size + 1)
        packet[0] = SOF_HI
        packet[1] = SOF_LO
        packet[2] = ((length shr 8) and 0xFF).toByte()
        packet[3] = (length and 0xFF).toByte()
        packet[4] = (commandId and 0xFF).toByte()
        payload.copyInto(packet, 5)
        packet[packet.size - 1] = crc(commandId, payload).toByte()
        return packet
    }

    /** Accepts both the outbound AB55 and the inbound AC55 header. */
    fun parseDatagram(packet: ByteArray): EyevueFrame {
        require(packet.size >= 6) { "Eyevue packet is too short" }
        require(isFrameStart(packet[0], packet[1])) { "Invalid Eyevue packet header" }
        val declaredLength = u16(packet[2], packet[3])
        require(declaredLength >= 2) { "Invalid Eyevue packet length: $declaredLength" }
        require(packet.size == declaredLength + 4) {
            "Eyevue packet length mismatch: declared=$declaredLength actual=${packet.size - 4}"
        }
        val commandId = packet[4].toInt() and 0xFF
        val payload = packet.copyOfRange(5, packet.size - 1)
        val expectedCrc = crc(commandId, payload)
        val actualCrc = packet.last().toInt() and 0xFF
        require(actualCrc == expectedCrc) { "Eyevue CRC mismatch: expected=$expectedCrc actual=$actualCrc" }
        return EyevueFrame(commandId, payload)
    }

    internal fun isFrameStart(first: Byte, second: Byte): Boolean =
        (first == SOF_HI || first == INBOUND_SOF_HI) && second == SOF_LO

    fun crc(commandId: Int, payload: ByteArray): Int {
        var result = commandId and 0xFF
        payload.forEach { result += it.toInt() and 0xFF }
        return result and 0xFF
    }

    fun valuePacket(commandId: Int, value: Int): ByteArray =
        buildDatagram(commandId, byteArrayOf((value and 0xFF).toByte()))

    fun twoValuePacket(commandId: Int, first: Int, second: Int): ByteArray =
        buildDatagram(commandId, byteArrayOf((first and 0xFF).toByte(), (second and 0xFF).toByte()))

    fun buildGetCustomerPacket(): ByteArray = buildDatagram(CMD_GET_CUSTOMER)
    fun buildGetBatteryPacket(): ByteArray = valuePacket(CMD_GET_BATTERY, 0)
    fun buildGetCapacityPacket(): ByteArray = valuePacket(CMD_GET_CAPACITY, 0)
    fun buildGetDeviceStatusPacket(): ByteArray = valuePacket(CMD_GET_DEVICE_STATUS, 0)
    fun buildGetDeviceInfoPacket(): ByteArray = valuePacket(CMD_GET_DEVICE_INFO, 0)
    fun buildGetMediaCountPacket(): ByteArray = valuePacket(CMD_GET_MEDIA_COUNT, 0)
    fun buildGetVolumePacket(): ByteArray = valuePacket(CMD_GET_VOLUME, 0)

    fun buildGetWifiInfoPacket(p2p: Boolean = false): ByteArray =
        valuePacket(CMD_GET_WIFI_INFO, if (p2p) PARAM_WIFI_P2P else PARAM_WIFI_AP)

    fun buildPhotoPacket(highQuality: Boolean): ByteArray =
        valuePacket(CMD_TAKE_PHOTO, if (highQuality) PARAM_PHOTO_HIGH_QUALITY else PARAM_PHOTO_THUMBNAIL)

    fun buildStartVideoPacket(): ByteArray = valuePacket(CMD_RECORD_VIDEO, PARAM_RECORD_START)
    fun buildStopVideoPacket(): ByteArray = valuePacket(CMD_STOP_RECORD, PARAM_RECORD_STOP)

    fun buildAudioPacket(start: Boolean): ByteArray =
        valuePacket(CMD_RECORD_AUDIO, if (start) PARAM_AUDIO_START else PARAM_AUDIO_STOP)

    fun buildFinishTransferPacket(): ByteArray =
        buildDatagram(CMD_FILE_DOWNLOAD_FINISH, byteArrayOf(PARAM_DOWNLOAD_FINISH, 0x01))

    /**
     * Successful sync: tell the glasses to delete the transferred files,
     * mirroring vendor stopP2pWifiConnect when downloaded == total.
     * Without this the glasses keep everything and the count never drops.
     */
    fun buildFinishTransferAndClearPacket(): ByteArray =
        buildDatagram(CMD_FILE_DOWNLOAD_FINISH, byteArrayOf(PARAM_DOWNLOAD_FINISH, PARAM_WIFI_FILE_DOWNLOAD_CLEAR))

    /** Partial sync: report how many files arrived, glasses keep the rest. */
    fun buildFinishTransferPartialPacket(downloaded: Int): ByteArray =
        buildDatagram(CMD_FILE_DOWNLOAD_FINISH, byteArrayOf(PARAM_DOWNLOAD_FINISH_NUN, (downloaded and 0xFF).toByte()))

    fun buildWearDetectionPacket(enabled: Boolean): ByteArray =
        valuePacket(CMD_WEAR_DETECT, if (enabled) 0x31 else 0x30)

    fun buildStopVoiceRecognitionPacket(): ByteArray = valuePacket(CMD_STOP_VOICE_RECOGNITION, 0)

    fun buildGetVoiceAssistantStatusPacket(): ByteArray = valuePacket(CMD_GET_VOICE_ASSISTANT_STATUS, 0)

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

    /** Sets the glasses clock to the device's local wall time at [epochMillis]. */
    fun buildSetTimePacket(
        epochMillis: Long = platformCurrentTimeMillis(),
        utcOffsetMinutes: Int = platformUtcOffsetMinutes(epochMillis),
    ): ByteArray {
        val local = epochMillis + utcOffsetMinutes * 60_000L
        val days = local.floorDiv(86_400_000L)
        val secondOfDay = (local.mod(86_400_000L) / 1000L).toInt()
        // Proleptic Gregorian date from days since 1970-01-01 (H. Hinnant, civil_from_days).
        val z = days + 719_468L
        val era = z.floorDiv(146_097L)
        val dayOfEra = z - era * 146_097L
        val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
        val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
        val shiftedMonth = (5L * dayOfYear + 2L) / 153L
        val day = (dayOfYear - (153L * shiftedMonth + 2L) / 5L + 1L).toInt()
        val month = (if (shiftedMonth < 10L) shiftedMonth + 3L else shiftedMonth - 9L).toInt()
        val year = (yearOfEra + era * 400L + if (month <= 2) 1L else 0L).toInt()
        return buildSetTimePacket(year, month, day, secondOfDay / 3600, secondOfDay / 60 % 60, secondOfDay % 60)
    }

    /** Local calendar fields (month 1-12). */
    fun buildSetTimePacket(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): ByteArray =
        buildDatagram(
            CMD_SET_TIME,
            byteArrayOf((year - 2000).toByte(), month.toByte(), day.toByte(), hour.toByte(), minute.toByte(), second.toByte()),
        )

    fun parseBattery(frame: EyevueFrame): EyevueBattery? = when (frame.commandId) {
        CMD_GET_BATTERY -> if (frame.payload.size < 2) null else EyevueBattery(
            percent = (frame.payload[0].toInt() and 0x0F) * 10 + (frame.payload[1].toInt() and 0x0F),
            isCharging = frame.payload.getOrNull(2)?.toInt() == 1,
        )
        CMD_RECEIVE_BATTERY -> if (frame.payload.size < 2) null else EyevueBattery(
            percent = frame.payload[1].toInt() and 0xFF,
            isCharging = frame.payload[0].toInt() == 1,
        )
        else -> null
    }

    fun parseWifiSsid(frame: EyevueFrame): String? {
        if (frame.commandId != CMD_RECEIVE_WIFI_INFO || frame.payload.isEmpty()) return null
        return frame.payload.decodeToString().trim { it <= ' ' || it == '\u0000' }.takeIf { it.isNotBlank() }
    }

    fun parseCustomer(frame: EyevueFrame): EyevueCustomer? {
        if (frame.commandId != CMD_RECEIVE_CUSTOMER || frame.payload.size < 8) return null
        fun readPart(offset: Int): String =
            frame.payload.copyOfRange(offset, offset + 4).filter { it.toInt() != 0 }.toByteArray().decodeToString()
        return EyevueCustomer(project = readPart(0), customer = readPart(4))
    }

    fun parseVoiceAssistantStatus(frame: EyevueFrame): EyevueVoiceAssistantStatus? {
        if (frame.commandId != CMD_GET_VOICE_ASSISTANT_STATUS || frame.payload.isEmpty()) return null
        val value = frame.payload[0].toInt() and 0xFF
        return EyevueVoiceAssistantStatus(
            localOfflineSpeechEnabled = value and 1 == 0,
            aiWakeWordEnabled = value and 2 == 0,
        )
    }

    /** Start Live streaming over AP mode. */
    fun buildStartLiveApPacket(): ByteArray = buildDatagram(CMD_APP_LIVE, byteArrayOf(PARAM_LIVE_AP))

    /** Start Live streaming over P2P mode. */
    fun buildStartLiveP2pPacket(): ByteArray = buildDatagram(CMD_APP_LIVE, byteArrayOf(PARAM_LIVE_P2P))

    /** Stop live stream / complete file transfer. */
    fun buildExitLivePacket(): ByteArray = buildFinishTransferPacket()

    /** Homescreen/manual shutter `AB 55 00 03 22 30 52`: saves to glasses storage and flashes the LED. */
    fun buildManualPhotoPacket(): ByteArray = buildPhotoPacket(highQuality = false)

    /** AI shutter `AB 55 00 03 22 31 53`: streams the image back over Bluetooth instead of saving. */
    fun buildAiPhotoPacket(): ByteArray = buildPhotoPacket(highQuality = true)

    /** Homescreen photo snapshot (manual shutter). */
    fun buildTakePhotoPacket(): ByteArray = buildManualPhotoPacket()

    /** Start video recording. */
    fun buildRecordVideoPacket(): ByteArray = buildStartVideoPacket()

    /** Stop video recording. */
    fun buildStopRecordPacket(): ByteArray = buildStopVideoPacket()

    /** Primary HTTP index URL (SK series P2P XML index). */
    fun mediaIndexUrl(deviceIp: String) = "http://$deviceIp/?custom=1&cmd=3001&par=1"

    /** Filenames from the glasses' XML index (NAME/FPATH tags) or a plain list. */
    fun parseMediaList(content: String): List<String> {
        val tagged = Regex("<(?:NAME|FPATH)>([^<]+)</(?:NAME|FPATH)>", RegexOption.IGNORE_CASE)
            .findAll(content)
            .map { it.groupValues[1].trim() }
            .toList()
        val candidates = tagged.ifEmpty { content.lines().map(String::trim) }
        return candidates
            .filter { name -> MEDIA_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } }
            .map { it.substringAfterLast('\\').substringAfterLast('/') }
            .distinct()
    }

    private val MEDIA_EXTENSIONS = listOf(".jpg", ".jpeg", ".mp4", ".opus")

    private fun u16(high: Byte, low: Byte) = ((high.toInt() and 0xFF) shl 8) or (low.toInt() and 0xFF)
}

/** Buffers BLE notifications so frames split across ATT packets decode safely. */
class EyevueFrameDecoder {
    private var buffer = ByteArray(0)

    fun append(chunk: ByteArray): List<EyevueFrame> {
        if (chunk.isEmpty()) return emptyList()
        val bytes = buffer + chunk
        val frames = mutableListOf<EyevueFrame>()
        var cursor = 0
        while (true) {
            while (cursor + 1 < bytes.size && !EyevueProtocol.isFrameStart(bytes[cursor], bytes[cursor + 1])) {
                cursor++
            }
            if (bytes.size - cursor < 4) break
            val length = ((bytes[cursor + 2].toInt() and 0xFF) shl 8) or (bytes[cursor + 3].toInt() and 0xFF)
            if (length < 2) {
                cursor++
                continue
            }
            val frameSize = length + 4
            if (bytes.size - cursor < frameSize) break
            runCatching { EyevueProtocol.parseDatagram(bytes.copyOfRange(cursor, cursor + frameSize)) }
                .onSuccess(frames::add)
            cursor += frameSize
        }
        buffer = bytes.copyOfRange(cursor, bytes.size)
        return frames
    }

    fun reset() {
        buffer = ByteArray(0)
    }
}

/** Reassembles the AA15 photo stream used by the vendor's AI-photo path. */
class EyevuePhotoAssembler(private val maxImageBytes: Int = GlassesPhotoLimits.MAX_IMAGE_BYTES) {
    private val chunks = mutableListOf<ByteArray>()
    private var size = 0
    private var receiving = false

    @Synchronized
    fun append(packet: ByteArray): ByteArray? {
        if (packet.size < 8) return null
        val payloadEnd = packet.size - 3
        return when (packet[4].toInt() and 0xFF) {
            EyevueProtocol.CMD_RECEIVE_PHOTO_DATA_START -> {
                clear()
                receiving = true
                null
            }
            EyevueProtocol.CMD_RECEIVE_PHOTO_DATA -> {
                // Each photo-data payload starts with a four-byte chunk address.
                val imageStart = 9
                if (receiving && payloadEnd > imageStart) {
                    if (size + payloadEnd - imageStart > maxImageBytes) {
                        reset()
                        return null
                    }
                    chunks += packet.copyOfRange(imageStart, payloadEnd)
                    size += payloadEnd - imageStart
                }
                null
            }
            EyevueProtocol.CMD_RECEIVE_PHOTO_DATA_END -> {
                if (!receiving) return null
                receiving = false
                val image = ByteArray(size)
                var offset = 0
                chunks.forEach { chunk ->
                    chunk.copyInto(image, offset)
                    offset += chunk.size
                }
                clear()
                image.takeIf { it.isNotEmpty() }
            }
            else -> null
        }
    }

    @Synchronized
    fun reset() {
        receiving = false
        clear()
    }

    private fun clear() {
        chunks.clear()
        size = 0
    }
}
