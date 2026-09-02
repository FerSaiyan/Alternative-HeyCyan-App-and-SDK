package com.fersaiyan.cyanbridge.devices.tunebuds

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.TimeZone

enum class TuneBudsFrameType(val id: Int) {
    REQUEST(1),
    RESPONSE(2),
    NOTIFICATION(3);

    companion object {
        fun fromId(id: Int): TuneBudsFrameType? = entries.firstOrNull { it.id == id }
    }
}

data class TuneBudsFrame(
    val sequence: Int,
    val command: Int,
    val type: TuneBudsFrameType,
    val payload: ByteArray,
)

data class TuneBudsEncodedCommand(
    val frames: List<ByteArray>,
    val nextSequence: Int,
)

data class TuneBudsStorageInfo(
    val usedMiB: Long,
    val freeMiB: Long,
)

data class TuneBudsMediaCounts(
    val images: Long,
    val videos: Long,
    val audio: Long,
) {
    val total: Long get() = images + videos + audio
}

data class TuneBudsBattery(
    val percent: Int,
    val isCharging: Boolean,
)

data class TuneBudsScreenConfig(
    val isSupported: Boolean,
    val isLandscape: Boolean,
)

/** Pure codec and payload helpers for the Bluetrum AB Mate command protocol. */
object TuneBudsProtocol {
    const val DEFAULT_MAX_PACKET_SIZE = 20
    const val HEADER_SIZE = 5

    const val CMD_DEVICE_INFO = 0x27
    const val CMD_SET_DETECTION = 0x26
    const val CMD_CAMERA_COPROCESSOR_INFO = 0xE0
    const val CMD_CAMERA_ON = 0xE1
    const val CMD_CAMERA_CLOSE = 0xE2
    const val CMD_AI_PICTURE = 0xE3
    const val CMD_START_VIDEO = 0xE4
    const val CMD_START_AUDIO = 0xE5
    const val CMD_CONFIGURE_WIFI = 0xE6
    const val CMD_FILE_MANAGER = 0xE7
    const val CMD_START_RTSP = 0xE8
    const val CMD_COPROCESSOR_VERSION = 0xE9
    const val CMD_STORAGE = 0xEA
    const val CMD_MEDIA_COUNTS = 0xEF
    const val CMD_SET_TIME = 0xF0
    const val CMD_WORK_STATE = 0xF6
    const val CMD_GLASS_CONFIG = 0xF7
    const val CMD_TELEPROMPTER = 0xF8
    const val CMD_TELEPROMPTER_CONTROL = 0xFB

    const val INFO_BATTERY = 0x01
    const val INFO_FIRMWARE_VERSION = 0x02
    const val INFO_IN_EAR_STATE = 0x09
    const val INFO_MAX_PACKET_SIZE = 0xFF
    const val INFO_AI_KIT_SUPPORT = 0xA0
    const val INFO_SERIAL_NUMBER = 0x85
    const val INFO_MODEL = 0x86
    const val INFO_DEVICE_ABILITY = 0xFE
    const val INFO_DEVICE_CAPABILITIES = INFO_DEVICE_ABILITY
    const val INFO_VIDEO_LIMIT = 0x82
    const val INFO_AUDIO_LIMIT = 0x83
    const val INFO_SUPPORT_VOLUME_CONTROL = 0x8D
    const val INFO_CURRENT_VOLUME = 0x8E
    const val INFO_SUPPORT_AUDIO = 0x98
    const val INFO_SUPPORT_APP_LIST = 0x9B
    const val INFO_SUPPORT_DETECTION = 0x96
    const val INFO_SCREEN_CONFIG = 0x9A
    const val INFO_RESOLUTION = 0x93
    const val INFO_SUPPORT_OPUS = 0x9F
    const val INFO_AI_CHAT_SUPPORT = 0x8A
    const val INFO_WIFI_SUPPORT = 0xA3

    fun encode(
        command: Int,
        payload: ByteArray = byteArrayOf(),
        type: TuneBudsFrameType = TuneBudsFrameType.REQUEST,
        maxPacketSize: Int = DEFAULT_MAX_PACKET_SIZE,
        initialSequence: Int = 0,
    ): TuneBudsEncodedCommand {
        require(command in 0..0xFF) { "TuneBuds command must fit in one byte" }
        require(maxPacketSize in (HEADER_SIZE + 1)..260) { "Invalid TuneBuds packet size: $maxPacketSize" }
        require(initialSequence in 0..15) { "TuneBuds sequence must be in 0..15" }
        val maxPayloadSize = maxPacketSize - HEADER_SIZE
        val fragmentCount = if (payload.isEmpty()) 1 else (payload.size + maxPayloadSize - 1) / maxPayloadSize
        require(fragmentCount <= 16) { "TuneBuds command requires more than 16 fragments" }

        var sequence = initialSequence
        val frames = ArrayList<ByteArray>(fragmentCount)
        repeat(fragmentCount) { index ->
            val start = index * maxPayloadSize
            val fragment = if (payload.isEmpty()) {
                byteArrayOf()
            } else {
                payload.copyOfRange(start, minOf(start + maxPayloadSize, payload.size))
            }
            frames += byteArrayOf(
                sequence.toByte(),
                command.toByte(),
                type.id.toByte(),
                ((((fragmentCount - 1) and 0x0F) shl 4) or (index and 0x0F)).toByte(),
                fragment.size.toByte(),
            ) + fragment
            sequence = (sequence + 1) and 0x0F
        }
        return TuneBudsEncodedCommand(frames, sequence)
    }

    fun deviceInfoQuery(vararg infoTypes: Int): ByteArray {
        require(infoTypes.isNotEmpty()) { "At least one TuneBuds device-info type is required" }
        return ByteArray(infoTypes.size * 2).also { payload ->
            infoTypes.forEachIndexed { index, type ->
                require(type in 0..0xFF) { "Device-info type must fit in one byte" }
                payload[index * 2] = type.toByte()
                payload[index * 2 + 1] = 0
            }
        }
    }

    fun parseDeviceInfo(payload: ByteArray): Map<Int, ByteArray> {
        val values = linkedMapOf<Int, ByteArray>()
        var cursor = 0
        while (cursor < payload.size) {
            require(payload.size - cursor >= 2) { "Truncated TuneBuds device-info header" }
            val type = payload[cursor].toInt() and 0xFF
            val length = payload[cursor + 1].toInt() and 0xFF
            cursor += 2
            require(payload.size - cursor >= length) { "Truncated TuneBuds device-info value for 0x${type.toString(16)}" }
            values[type] = payload.copyOfRange(cursor, cursor + length)
            cursor += length
        }
        return values
    }

    fun parseBattery(payload: ByteArray): TuneBudsBattery? {
        val value = payload.firstOrNull()?.toInt()?.and(0xFF) ?: return null
        return TuneBudsBattery(percent = value and 0x7F, isCharging = value and 0x80 != 0)
    }

    fun parseUnsignedValue(payload: ByteArray): Int? = when (payload.size) {
        1 -> payload[0].toInt() and 0xFF
        2 -> (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        else -> null
    }

    fun parseSupportFlag(payload: ByteArray): Boolean? = when (parseUnsignedValue(payload)) {
        0 -> false
        1 -> true
        else -> null
    }

    fun parseDeviceCapabilities(payload: ByteArray): Int? =
        if (payload.size == 2) {
            (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        } else {
            null
        }

    fun cameraCoprocessorLabel(type: Int): String = when (type) {
        0 -> "none"
        1 -> "QZ V821"
        2 -> "TX W81x"
        3 -> "Realtek"
        else -> "unknown ($type)"
    }

    fun parseScreenConfig(payload: ByteArray): TuneBudsScreenConfig? =
        if (payload.size == 2) {
            TuneBudsScreenConfig(
                isSupported = payload[0].toInt() and 0xFF == 1,
                isLandscape = payload[1].toInt() and 0xFF == 0,
            )
        } else {
            null
        }

    fun parseFirmwareVersion(payload: ByteArray): String? {
        if (payload.size != 4) return null
        return payload.reversed().joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    fun parseString(payload: ByteArray): String? = payload
        .toString(StandardCharsets.UTF_8)
        .trim { it <= ' ' || it == '\u0000' }
        .takeIf { it.isNotBlank() }

    fun deriveClassicAddress(manufacturerData: ByteArray): String {
        require(manufacturerData.size >= 9) { "TuneBuds manufacturer data is too short" }
        return (3 until 9).joinToString(":") { index ->
            "%02X".format((manufacturerData[index].toInt() xor 0xAD) and 0xFF)
        }
    }

    fun parseStorage(payload: ByteArray): TuneBudsStorageInfo? {
        if (payload.size != 8) return null
        return TuneBudsStorageInfo(u32le(payload, 0), u32le(payload, 4))
    }

    fun parseMediaCounts(payload: ByteArray): TuneBudsMediaCounts? {
        if (payload.size != 12) return null
        return TuneBudsMediaCounts(
            images = u32le(payload, 0),
            videos = u32le(payload, 4),
            audio = u32le(payload, 8),
        )
    }

    fun parseStatus(payload: ByteArray): Int? = payload.firstOrNull()?.toInt()?.and(0xFF)

    fun buildSetTimePayload(
        epochMillis: Long = System.currentTimeMillis(),
        utcOffsetMinutes: Int = TimeZone.getDefault().getOffset(epochMillis) / 60_000,
    ): ByteArray {
        require(utcOffsetMinutes in Short.MIN_VALUE..Short.MAX_VALUE)
        val seconds = epochMillis / 1_000L
        require(seconds in 0..0xFFFF_FFFFL) { "Epoch seconds do not fit the TuneBuds u32 field" }
        return byteArrayOf(
            seconds.toByte(),
            (seconds shr 8).toByte(),
            (seconds shr 16).toByte(),
            (seconds shr 24).toByte(),
            utcOffsetMinutes.toByte(),
            (utcOffsetMinutes shr 8).toByte(),
        )
    }

    fun buildWifiPayload(
        mode: Int,
        ssid: String,
        password: String,
        channel: Int = 0,
    ): ByteArray {
        require(mode in 0..2)
        require(channel in 0..0xFF)
        val ssidBytes = ssid.toByteArray(StandardCharsets.UTF_8)
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        require(ssidBytes.size <= 0xFF && passwordBytes.size <= 0xFF)
        return byteArrayOf(0x01, 0x01, mode.toByte()) +
            byteArrayOf(0x02, ssidBytes.size.toByte()) + ssidBytes +
            byteArrayOf(0x03, passwordBytes.size.toByte()) + passwordBytes +
            byteArrayOf(0x04, 0x01, channel.toByte())
    }

    fun buildConfigPayload(key: Int, value: ByteArray): ByteArray {
        require(key in 0..0xFF && value.size <= 0xFF)
        return byteArrayOf(key.toByte(), value.size.toByte()) + value
    }

    fun rtspQualityPayload(quality: Int): ByteArray = when (quality) {
        in 0..24 -> byteArrayOf(0x01, 0x1E, 0x3C)
        in 25..49 -> byteArrayOf(0x02, 0x14, 0x1E)
        in 50..74 -> byteArrayOf(0x04, 0x0A, 0x0F)
        in 75..99 -> byteArrayOf(0x06, 0x05, 0x0A)
        100 -> byteArrayOf(0x08, 0x00, 0x05)
        else -> byteArrayOf(0x00, 0x00, 0x00)
    }

    private fun u32le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}

/** Splits an RFCOMM byte stream and strictly reassembles AB Mate fragments. */
class TuneBudsFrameDecoder {
    private data class Assembly(
        val command: Int,
        val type: TuneBudsFrameType,
        val total: Int,
        var nextIndex: Int,
        val payload: ByteArrayOutputStream,
        val firstSequence: Int,
    )

    private var buffered = byteArrayOf()
    private var expectedSequence: Int? = null
    private var assembly: Assembly? = null

    var malformedFrameCount: Int = 0
        private set

    fun append(chunk: ByteArray): List<TuneBudsFrame> {
        if (chunk.isEmpty()) return emptyList()
        buffered += chunk
        val completed = mutableListOf<TuneBudsFrame>()
        var cursor = 0

        while (buffered.size - cursor >= TuneBudsProtocol.HEADER_SIZE) {
            if (isAiEnvelopeStart(buffered, cursor)) {
                val end = findAiEnvelopeEnd(buffered, cursor + 2)
                if (end < 0) break
                cursor = end + 2
                continue
            }

            val type = TuneBudsFrameType.fromId(buffered[cursor + 2].toInt() and 0xFF)
            if (type == null) {
                malformedFrameCount++
                cursor++
                continue
            }
            val payloadLength = buffered[cursor + 4].toInt() and 0xFF
            val frameSize = TuneBudsProtocol.HEADER_SIZE + payloadLength
            if (buffered.size - cursor < frameSize) break
            val frame = buffered.copyOfRange(cursor, cursor + frameSize)
            cursor += frameSize
            runCatching { acceptPhysicalFrame(frame, type) }
                .onSuccess { logical -> logical?.let(completed::add) }
                .onFailure {
                    malformedFrameCount++
                    assembly = null
                }
        }

        if (cursor > 0) buffered = buffered.copyOfRange(cursor, buffered.size)
        return completed
    }

    fun reset() {
        buffered = byteArrayOf()
        expectedSequence = null
        assembly = null
        malformedFrameCount = 0
    }

    private fun acceptPhysicalFrame(frame: ByteArray, type: TuneBudsFrameType): TuneBudsFrame? {
        val sequence = frame[0].toInt() and 0x0F
        expectedSequence?.let { expected ->
            // The official AB Mate decoder reports transport-sequence gaps but still consumes
            // the frame. AI envelopes share the RFCOMM stream but bypass this decoder, so
            // rejecting the next command frame would drop valid photo chunks or responses.
            if (sequence != expected) malformedFrameCount++
        }
        expectedSequence = (sequence + 1) and 0x0F

        val command = frame[1].toInt() and 0xFF
        val fragment = frame[3].toInt() and 0xFF
        val total = ((fragment ushr 4) and 0x0F) + 1
        val index = fragment and 0x0F
        require(index < total) { "TuneBuds fragment index $index is outside total $total" }
        val payload = frame.copyOfRange(TuneBudsProtocol.HEADER_SIZE, frame.size)

        var current = assembly
        if (current == null) {
            require(index == 0) { "TuneBuds fragmented response did not start at index zero" }
            current = Assembly(command, type, total, 0, ByteArrayOutputStream(), sequence)
            assembly = current
        } else {
            require(current.command == command) { "TuneBuds command changed during reassembly" }
            require(current.type == type) { "TuneBuds frame type changed during reassembly" }
            require(current.total == total) { "TuneBuds fragment total changed during reassembly" }
        }
        require(index == current.nextIndex) {
            "TuneBuds fragment index mismatch: expected=${current.nextIndex} actual=$index"
        }
        current.payload.write(payload)
        current.nextIndex++

        if (index != total - 1) return null
        assembly = null
        return TuneBudsFrame(
            sequence = current.firstSequence,
            command = command,
            type = type,
            payload = current.payload.toByteArray(),
        )
    }

    private fun isAiEnvelopeStart(bytes: ByteArray, offset: Int): Boolean =
        bytes[offset] == 0x55.toByte() && bytes[offset + 1] == 0xAA.toByte()

    private fun findAiEnvelopeEnd(bytes: ByteArray, start: Int): Int {
        for (index in start until bytes.lastIndex) {
            if (bytes[index] == 0xA5.toByte() && bytes[index + 1] == 0x5A.toByte()) return index
        }
        return -1
    }
}
