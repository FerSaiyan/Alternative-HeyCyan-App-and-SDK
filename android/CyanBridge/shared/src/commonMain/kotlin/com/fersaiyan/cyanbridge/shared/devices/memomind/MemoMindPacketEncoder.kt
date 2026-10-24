package com.fersaiyan.cyanbridge.shared.devices.memomind

import com.fersaiyan.cyanbridge.shared.platform.platformCurrentTimeMillis
import kotlin.jvm.Synchronized

/**
 * Encodes RFCOMM control frames for MemoMind glasses.
 *
 * The confirmed wire format is:
 * `fa 00 <len:uint16 BE> <seq> <group> <opcode> <type> ...`
 *
 * JSON payloads are written by hand to match Android's org.json byte for byte
 * (insertion order, no spaces, '/' escaped as "\/").
 */
object MemoMindPacketEncoder {

    private const val FRAME_PREFIX_0: Int = 0xFA
    private const val FRAME_PREFIX_1: Int = 0x00

    private const val TYPE_REQUEST: Int = 0x01
    private const val TYPE_RESPONSE: Int = 0x02
    private const val TYPE_PUSH: Int = 0x08

    private const val GROUP_DEVICE: Int = 0x01
    private const val GROUP_CARDS: Int = 0x02
    private const val GROUP_NOTIFICATIONS: Int = 0x05

    private const val OP_DEVICE_INFO: Int = 0x02
    private const val OP_BATTERY: Int = 0x06
    private const val OP_CARD_PUSH: Int = 0x08
    private const val OP_NOTIFICATION_PUSH: Int = 0x01
    private const val OP_SET_BRIGHTNESS: Int = 0x0B
    private const val OP_SET_WAKE_ANGLE: Int = 0x0C
    private const val OP_SET_FONT_SIZE: Int = 0x0E
    private const val OP_SET_AUTO_BRIGHTNESS: Int = 0x0F

    private const val COMPONENT_STOCK: Int = 0x04
    private const val COMPONENT_NEWS: Int = 0x06
    private const val COMPONENT_SCHEDULE: Int = 0x09
    private const val COMPONENT_CALENDAR: Int = 0x0A
    private const val COMPONENT_NOTIFICATION: Int = 0x01

    private const val COMPONENT_ACTION_PUSH: Int = 0x05

    private var sequenceCounter = 1
    private var notificationIdCounter = 10_000

    @Synchronized
    private fun nextNotificationId(): Int = notificationIdCounter++

    data class ScheduleItem(
        val title: String,
        val body: String = "",
        val timestamp: String = "",
        val completed: Int = 0,
        val id: Int = nextNotificationId(),
    )

    data class CalendarItem(
        val title: String,
        val timestamp: String,
        val body: String = "",
        val id: Int = nextNotificationId(),
    )

    data class NewsItem(
        val content: String,
        val id: Int = nextNotificationId(),
        val source: String = "CyanBridge",
        val title: String = "",
    )

    fun encodeDeviceInfoRequest(): ByteArray = encodeSimpleRequest(GROUP_DEVICE, OP_DEVICE_INFO)

    fun encodeBatteryRequest(): ByteArray = encodeSimpleRequest(GROUP_DEVICE, OP_BATTERY)

    fun encodeShowText(text: String): ByteArray =
        encodeScheduleItems(listOf(ScheduleItem(title = text.trim().ifEmpty { "CyanBridge" })))

    fun encodeLines(lines: List<String>): ByteArray {
        if (lines.isEmpty()) return encodeEmptySchedule()
        val items = lines.filter { it.isNotBlank() }.map { ScheduleItem(title = it) }
        return encodeScheduleItems(items.ifEmpty { listOf(ScheduleItem(title = "CyanBridge")) })
    }

    fun encodeScheduleCard(title: String, body: String): ByteArray =
        encodeScheduleItems(listOf(ScheduleItem(title = title.ifBlank { "CyanBridge" }, body = body)))

    fun encodeEmptySchedule(): ByteArray = encodeScheduleItems(emptyList())

    fun encodeScheduleItems(items: List<ScheduleItem>): ByteArray = encodeComponentPush(
        COMPONENT_SCHEDULE,
        jsonArray(items) { jsonObject("id" to it.id, "ti" to it.title, "ts" to it.timestamp, "c" to it.body, "do" to it.completed) },
    )

    fun encodeCalendarItems(items: List<CalendarItem>): ByteArray = encodeComponentPush(
        COMPONENT_CALENDAR,
        jsonArray(items) { jsonObject("id" to it.id, "ti" to it.title, "ts" to it.timestamp, "c" to it.body) },
    )

    fun encodeNewsItems(items: List<NewsItem>): ByteArray = encodeComponentPush(
        COMPONENT_NEWS,
        jsonArray(items) { jsonObject("c" to it.content, "id" to it.id, "src" to it.source, "t" to it.title) },
    )

    fun encodeNotification(
        title: String,
        body: String,
        appName: String = "CyanBridge",
        packageName: String = "com.fersaiyan.cyanbridge",
    ): ByteArray {
        val payload = jsonObject(
            "id" to nextNotificationId(),
            "a" to appName,
            "type" to 0,
            "ts" to platformCurrentTimeMillis() / 1000L,
            "c" to body,
            "ti" to title,
            "pkg_name" to packageName,
        )
        return encodePushFrame(
            group = GROUP_NOTIFICATIONS,
            opcode = OP_NOTIFICATION_PUSH,
            componentId = COMPONENT_NOTIFICATION,
            actionId = COMPONENT_ACTION_PUSH,
            payload = payload,
        )
    }

    fun encodeSetBrightness(level: Int): ByteArray = encodeSettingsWrite(OP_SET_BRIGHTNESS, level.coerceIn(0, 10))

    fun encodeSetAutoBrightness(enabled: Boolean): ByteArray =
        encodeSettingsWrite(OP_SET_AUTO_BRIGHTNESS, if (enabled) 0x00 else 0x01)

    fun encodeSetWakeAngle(degrees: Int): ByteArray = encodeSettingsWrite(OP_SET_WAKE_ANGLE, degrees.coerceIn(0, 60))

    fun encodeSetFontSize(size: Int): ByteArray = encodeSettingsWrite(OP_SET_FONT_SIZE, size.coerceIn(1, 6))

    fun extractJsonPayload(frame: ByteArray, expectedGroup: Int, expectedOpcode: Int): String? {
        if (frame.size < 13) return null
        if ((frame[0].toInt() and 0xFF) != FRAME_PREFIX_0 || (frame[1].toInt() and 0xFF) != FRAME_PREFIX_1) return null

        val group = frame[5].toInt() and 0xFF
        val opcode = frame[6].toInt() and 0xFF
        val type = frame[7].toInt() and 0xFF
        if (group != expectedGroup || opcode != expectedOpcode || type != TYPE_RESPONSE) return null

        val payloadLength = ((frame[8].toInt() and 0xFF) shl 16) or
            ((frame[9].toInt() and 0xFF) shl 8) or
            (frame[10].toInt() and 0xFF)
        val payloadStart = 11
        val payloadEnd = payloadStart + payloadLength
        if (payloadEnd + 2 > frame.size) return null

        return frame.copyOfRange(payloadStart, payloadEnd).decodeToString()
    }

    private fun encodeComponentPush(componentId: Int, payload: String): ByteArray = encodePushFrame(
        group = GROUP_CARDS,
        opcode = OP_CARD_PUSH,
        componentId = componentId,
        actionId = COMPONENT_ACTION_PUSH,
        payload = payload,
    )

    /**
     * Encode a single-value settings write.
     *
     * Observed wire format:
     * `fa 00 00 0e <seq> 01 <opcode> 08 00 00 01 <value> 01 <sum>`
     */
    private fun encodeSettingsWrite(opcode: Int, value: Int): ByteArray {
        val frame = byteArrayOf(
            FRAME_PREFIX_0.toByte(),
            FRAME_PREFIX_1.toByte(),
            0x00, 0x0E,
            nextSequence().toByte(),
            GROUP_DEVICE.toByte(),
            opcode.toByte(),
            TYPE_PUSH.toByte(),
            0x00, 0x00, 0x01,
            value.toByte(),
            0x01,
            0x00, // placeholder for checksum
        )
        frame[13] = checksum(frame, 13).toByte()
        return frame
    }

    private fun encodeSimpleRequest(group: Int, opcode: Int): ByteArray {
        val seq = nextSequence()
        val checksum = (seq + group + opcode + TYPE_REQUEST + 0x02) and 0xFF
        return byteArrayOf(
            FRAME_PREFIX_0.toByte(),
            FRAME_PREFIX_1.toByte(),
            0x00,
            0x09,
            seq.toByte(),
            group.toByte(),
            opcode.toByte(),
            TYPE_REQUEST.toByte(),
            checksum.toByte(),
        )
    }

    private fun encodePushFrame(group: Int, opcode: Int, componentId: Int, actionId: Int, payload: String): ByteArray {
        val payloadBytes = payload.encodeToByteArray()
        val header = byteArrayOf(
            FRAME_PREFIX_0.toByte(), FRAME_PREFIX_1.toByte(), 0x00, 0x00,
            nextSequence().toByte(), group.toByte(), opcode.toByte(), TYPE_PUSH.toByte(),
            0x00, 0x00, 0x01, componentId.toByte(), actionId.toByte(), 0x00,
            (payloadBytes.size shr 8).toByte(), payloadBytes.size.toByte(),
        )
        val frame = header + payloadBytes + byteArrayOf(0x00, 0x00, 0x00)
        val sum = checksum(frame, frame.size - 2)
        frame[frame.size - 2] = (sum shr 8).toByte()
        frame[frame.size - 1] = sum.toByte()
        frame[2] = (frame.size shr 8).toByte()
        frame[3] = frame.size.toByte()
        return frame
    }

    private fun checksum(bytes: ByteArray, count: Int): Int {
        var sum = 0
        for (i in 0 until count) sum = (sum + (bytes[i].toInt() and 0xFF)) and 0xFFFF
        return sum
    }

    @Synchronized
    private fun nextSequence(): Int {
        val seq = sequenceCounter++ and 0xFF
        return if (seq == 0) 1 else seq
    }

    private fun <T> jsonArray(items: List<T>, encode: (T) -> String): String =
        items.joinToString(",", "[", "]", transform = encode)

    private fun jsonObject(vararg fields: Pair<String, Any>): String =
        fields.joinToString(",", "{", "}") { (key, value) ->
            jsonString(key) + ":" + if (value is String) jsonString(value) else value.toString()
        }

    /** Android org.json JSONStringer escaping. */
    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"', '\\', '/' -> append('\\').append(c)
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u000C' -> append("\\f")
                else -> if (c <= '\u001F') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
            }
        }
        append('"')
    }
}
