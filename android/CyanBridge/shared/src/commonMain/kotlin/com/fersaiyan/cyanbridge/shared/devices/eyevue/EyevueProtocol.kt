package com.fersaiyan.cyanbridge.shared.devices.eyevue

/**
 * Platform-neutral port of the Android app's Eyevue BLE protocol
 * (app/.../devices/eyevue/EyevueProtocol.kt): datagram framing, commands and
 * notification parsing. UUIDs are lowercase 128-bit strings.
 */
data class EyevueFrame(val commandId: Int, val payload: ByteArray)

data class EyevueBattery(val percent: Int, val isCharging: Boolean)

object EyevueProtocol {
    const val SOF_HI = 0xAB.toByte()
    const val SOF_LO = 0x55.toByte()

    const val SERVICE_UUID = "0000aa12-0000-1000-8000-00805f9b34fb"
    const val COMMAND_WRITE_UUID = "0000aa13-0000-1000-8000-00805f9b34fb"
    const val COMMAND_NOTIFY_UUID = "0000aa14-0000-1000-8000-00805f9b34fb"
    const val PHOTO_NOTIFY_UUID = "0000aa15-0000-1000-8000-00805f9b34fb"

    const val CMD_RECORD_TIME = 2
    const val CMD_WEAR_DETECT = 4
    const val CMD_GET_BATTERY = 23
    const val CMD_TAKE_PHOTO = 34
    const val CMD_RECORD_VIDEO = 35
    const val CMD_STOP_RECORD = 36
    const val CMD_RECORD_AUDIO = 52
    const val CMD_GET_WIFI_INFO = 57
    const val CMD_GET_MEDIA_COUNT = 64
    const val CMD_GET_VOLUME = 105
    const val CMD_SET_TIME = 89
    const val CMD_RECEIVE_BATTERY = 83
    const val CMD_RECEIVE_WIFI_INFO = 37
    const val CMD_RECEIVE_THUMBNAIL_COUNT = 66
    const val CMD_RECEIVE_PHOTO_DATA_START = 151
    const val CMD_RECEIVE_PHOTO_DATA = 152
    const val CMD_RECEIVE_PHOTO_DATA_END = 153

    private const val PARAM_PHOTO_THUMBNAIL = 0x30
    private const val PARAM_PHOTO_HIGH_QUALITY = 0x31
    private const val PARAM_WIFI_AP = 0x30

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

    fun parseDatagram(packet: ByteArray): EyevueFrame {
        require(packet.size >= 6) { "Eyevue packet is too short" }
        require(packet[0] == SOF_HI && packet[1] == SOF_LO) { "Invalid Eyevue packet header" }
        val declaredLength = u16(packet[2], packet[3])
        require(declaredLength >= 2 && packet.size == declaredLength + 4) { "Eyevue packet length mismatch" }
        val commandId = packet[4].toInt() and 0xFF
        val payload = packet.copyOfRange(5, packet.size - 1)
        require((packet.last().toInt() and 0xFF) == crc(commandId, payload)) { "Eyevue CRC mismatch" }
        return EyevueFrame(commandId, payload)
    }

    fun crc(commandId: Int, payload: ByteArray): Int {
        var result = commandId and 0xFF
        payload.forEach { result += it.toInt() and 0xFF }
        return result and 0xFF
    }

    private fun valuePacket(commandId: Int, value: Int) = buildDatagram(commandId, byteArrayOf((value and 0xFF).toByte()))

    fun buildGetBatteryPacket() = valuePacket(CMD_GET_BATTERY, 0)
    fun buildGetMediaCountPacket() = valuePacket(CMD_GET_MEDIA_COUNT, 0)
    fun buildGetVolumePacket() = valuePacket(CMD_GET_VOLUME, 0)
    fun buildGetWifiInfoPacket() = valuePacket(CMD_GET_WIFI_INFO, PARAM_WIFI_AP)
    fun buildPhotoPacket(highQuality: Boolean) =
        valuePacket(CMD_TAKE_PHOTO, if (highQuality) PARAM_PHOTO_HIGH_QUALITY else PARAM_PHOTO_THUMBNAIL)
    fun buildStartVideoPacket() = valuePacket(CMD_RECORD_VIDEO, 0x01)
    fun buildStopVideoPacket() = valuePacket(CMD_STOP_RECORD, 0x00)
    fun buildAudioPacket(start: Boolean) = valuePacket(CMD_RECORD_AUDIO, if (start) 0x01 else 0x00)
    fun buildWearDetectionPacket(enabled: Boolean) = valuePacket(CMD_WEAR_DETECT, if (enabled) 0x31 else 0x30)
    fun buildRecordDurationPacket(seconds: Int) =
        buildDatagram(CMD_RECORD_TIME, byteArrayOf(((seconds shr 8) and 0xFF).toByte(), (seconds and 0xFF).toByte()))

    /** Local calendar fields; the caller converts "now" with the platform calendar. */
    fun buildSetTimePacket(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int) = buildDatagram(
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
            while (cursor + 1 < bytes.size &&
                (bytes[cursor] != EyevueProtocol.SOF_HI || bytes[cursor + 1] != EyevueProtocol.SOF_LO)
            ) {
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
class EyevuePhotoAssembler {
    private val chunks = mutableListOf<ByteArray>()
    private var receiving = false

    fun append(packet: ByteArray): ByteArray? {
        if (packet.size < 8) return null
        val payloadEnd = packet.size - 3
        return when (packet[4].toInt() and 0xFF) {
            EyevueProtocol.CMD_RECEIVE_PHOTO_DATA_START -> {
                chunks.clear()
                receiving = true
                null
            }
            EyevueProtocol.CMD_RECEIVE_PHOTO_DATA -> {
                // Each photo-data payload starts with a four-byte chunk address.
                val imageStart = 9
                if (receiving && payloadEnd > imageStart) chunks += packet.copyOfRange(imageStart, payloadEnd)
                null
            }
            EyevueProtocol.CMD_RECEIVE_PHOTO_DATA_END -> {
                if (!receiving) return null
                receiving = false
                val image = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
                chunks.clear()
                image.takeIf { it.isNotEmpty() }
            }
            else -> null
        }
    }
}
