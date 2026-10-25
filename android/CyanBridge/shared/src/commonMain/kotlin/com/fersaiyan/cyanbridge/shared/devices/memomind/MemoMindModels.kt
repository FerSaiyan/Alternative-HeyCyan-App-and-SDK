package com.fersaiyan.cyanbridge.shared.devices.memomind

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** System 0x02 device information (sent as a STRING TLV holding JSON). Unknown fields are kept in [fields]. */
data class MemoMindDeviceInfo(
    val protocolVersion: Int?,
    /** Capability bitmask; its bit definitions are not published, so only report it. */
    val capabilities: Long?,
    val model: String?,
    val serialNumber: String?,
    val macAddress: String?,
    val name: String?,
    val firmwareVersion: String?,
    val caseVersion: String?,
    val hardwareVersion: String?,
    val fontVersion: String?,
    val fields: JsonObject,
) {
    companion object {
        fun parse(json: JsonObject) = MemoMindDeviceInfo(
            protocolVersion = json.int("pver"),
            capabilities = (json["cap"] as? JsonPrimitive)?.longOrNull,
            model = json.string("model"),
            serialNumber = json.string("sn"),
            macAddress = json.string("mac"),
            name = json.string("name"),
            firmwareVersion = json.string("ver"),
            caseVersion = json.string("cver"),
            hardwareVersion = json.string("hver"),
            fontVersion = json.string("fver"),
            fields = json,
        )
    }
}

/** System 0x06 device status. Placeholder zeros (case fields on the no-case product) are not sensors. */
data class MemoMindDeviceStatus(
    val batteryPercent: Int?,
    val isCharging: Boolean,
    val brightness: Int?,
    val autoBrightness: Boolean?,
    val screenHeight: Int?,
    val opticalDistance: Int?,
    val headUpAngle: Int?,
    val wearDetection: Boolean?,
    val doNotDisturb: Boolean?,
    val fields: JsonObject,
) {
    companion object {
        fun parse(json: JsonObject) = MemoMindDeviceStatus(
            batteryPercent = json.int("bat")?.coerceIn(0, 100),
            isCharging = (json.int("charging") ?: 0) != 0,
            brightness = json.int("brt"),
            autoBrightness = json.int("autobrt")?.let { it != 0 },
            screenHeight = json.int("pos"),
            opticalDistance = json.int("dist"),
            headUpAngle = json.int("angle"),
            wearDetection = json.int("wear_det")?.let { it != 0 },
            doNotDisturb = json.int("dnd")?.let { it != 0 },
            fields = json,
        )
    }
}

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

/** Buttons (GM_PLUGIN_BUTTON_*) reported by the Web Bridge plugin. */
object MemoMindButton {
    const val UNKNOWN = 0
    const val PRIMARY = 1
    const val UP = 2
    const val DOWN = 3
    const val LEFT = 4
    const val RIGHT = 5
    const val PAGE_UP = 6
    const val PAGE_DOWN = 7
    const val SCROLL_UP = 8
    const val SCROLL_DOWN = 9
    const val BACK = 10
    const val HOME = 11
}

/** Button actions (GM_PLUGIN_BUTTON_ACTION_*); PRIMARY reports SINGLE..RELEASE, the rest TRIGGER. */
object MemoMindButtonAction {
    const val UNKNOWN = 0
    const val SINGLE = 1
    const val DOUBLE = 2
    const val LONG = 3
    const val VERY_LONG = 4
    const val RELEASE = 5
    const val TRIGGER = 6
}

/** Head gestures (GM_PLUGIN_IMU_GESTURE_*). */
object MemoMindGesture {
    const val NONE = 0
    const val NOD = 1
    const val HEAD_RAISE = 2
    const val HEAD_LOWER = 3
    const val SHAKE = 4
    const val HEAD_RAISE_TIMEOUT = 5
    const val HEAD_LOWER_TIMEOUT = 6
    const val LEFT = 7
    const val RIGHT = 8
}

/**
 * Something the glasses sent that is not the response to a request. Every plugin message
 * (service 0x0F command 0x29) arrives as [AppMessage]; the Web Bridge event channels are also
 * decoded into the typed events that follow it.
 */
sealed interface MemoMindEvent {
    /** A running glasses plugin called `bt_send()`; [data] is the application payload. */
    class AppMessage(val channel: Int, val data: ByteArray) : MemoMindEvent {
        override fun toString() = "AppMessage(channel=0x${channel.toString(16)}, ${data.size} bytes)"
    }

    data class Button(val button: Int, val action: Int, val sequence: Long) : MemoMindEvent
    data class HeadGesture(val gesture: Int, val active: Boolean, val sequence: Long) : MemoMindEvent

    data class RawImu(
        val accel: List<Int>,
        val gyro: List<Int>,
        val temperature: Int,
        val pitchDegrees: Int,
    ) : MemoMindEvent

    /** The Web Bridge plugin connected; a disconnect is only seen as a transport loss. */
    data class PluginConnection(val connected: Boolean) : MemoMindEvent

    data class FrameStatus(
        val frameId: Long,
        val tileIndex: Int,
        val nextIndex: Int,
        val status: Int,
        val complete: Boolean,
    ) : MemoMindEvent

    /** The native teleprompter asks for the previous ([next] false) or next page. */
    data class TeleprompterPage(val next: Boolean) : MemoMindEvent

    /** The wearer paused or resumed a native translation session on the glasses. */
    data class TranslationPaused(val paused: Boolean) : MemoMindEvent

    /** Any other unsolicited message, for diagnostics and hardware mapping. */
    data class Unsolicited(val message: GmMessage) : MemoMindEvent
}

/** Decodes unsolicited messages into [MemoMindEvent]s. */
object MemoMindEvents {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(message: GmMessage): List<MemoMindEvent> = when {
        message.service == MemoMindService.PLUGIN && message.command == MemoMindCommand.APP_MESSAGE_FROM_GLASSES -> {
            val channel = message.first(GmTlvType.INT16)?.intValue?.toInt()
            val data = message.first(GmTlvType.BYTES)?.value
            if (channel == null || data == null) {
                listOf(MemoMindEvent.Unsolicited(message))
            } else {
                listOfNotNull(MemoMindEvent.AppMessage(channel, data), decodeWebBridge(channel, data))
            }
        }
        message.service == MemoMindService.TELEPROMPTER && message.command == MemoMindCommand.TELEPROMPTER_PAGE_REQUEST ->
            listOf(
                message.tlvs.firstNotNullOfOrNull { it.intValue }
                    ?.let { MemoMindEvent.TeleprompterPage(next = it == 2L) }
                    ?: MemoMindEvent.Unsolicited(message),
            )
        message.service == MemoMindService.TRANSLATION && message.command == MemoMindCommand.TRANSLATION_LOCAL_PAUSE_RESUME ->
            listOf(
                message.first(GmTlvType.INT8)?.intValue
                    ?.let { MemoMindEvent.TranslationPaused(paused = it == 0L) }
                    ?: MemoMindEvent.Unsolicited(message),
            )
        else -> listOf(MemoMindEvent.Unsolicited(message))
    }

    /** The Web Bridge uplink: a 10-byte header (version, type, sequence, timestamp) and its data. */
    fun decodeWebBridge(channel: Int, data: ByteArray): MemoMindEvent? {
        if (data.size < 10 || data[0].toInt() != 1) return null
        val sequence = u32(data, 2)
        return when (channel) {
            MemoMindWebBridge.CHANNEL_BUTTON -> if (data.size >= 14) {
                MemoMindEvent.Button(button = u16(data, 10), action = u16(data, 12), sequence = sequence)
            } else {
                null
            }
            MemoMindWebBridge.CHANNEL_IMU_GESTURE -> if (data.size >= 13) {
                MemoMindEvent.HeadGesture(gesture = u16(data, 10), active = data[12].toInt() != 0, sequence = sequence)
            } else {
                null
            }
            MemoMindWebBridge.CHANNEL_RAW_IMU -> if (data.size >= 26) {
                MemoMindEvent.RawImu(
                    accel = listOf(s16(data, 10), s16(data, 12), s16(data, 14)),
                    gyro = listOf(s16(data, 16), s16(data, 18), s16(data, 20)),
                    temperature = s16(data, 22),
                    pitchDegrees = s16(data, 24),
                )
            } else {
                null
            }
            MemoMindWebBridge.CHANNEL_CONNECTION -> if (data.size >= 11) {
                MemoMindEvent.PluginConnection(data[10].toInt() != 0)
            } else {
                null
            }
            MemoMindWebBridge.CHANNEL_FRAME_STATUS -> if (data.size >= 20) {
                MemoMindEvent.FrameStatus(
                    frameId = u32(data, 10),
                    tileIndex = u16(data, 14),
                    nextIndex = u16(data, 16),
                    status = data[18].toInt() and 0xFF,
                    complete = data[19].toInt() == 1,
                )
            } else {
                null
            }
            else -> null
        }
    }

    /** JSON carried by a STRING (device info/status) or JSON TLV; null when absent or invalid. */
    fun jsonObject(message: GmMessage): JsonObject? {
        val text = (message.first(GmTlvType.STRING) ?: message.first(GmTlvType.JSON))?.text ?: return null
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    private fun u16(data: ByteArray, offset: Int) =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun s16(data: ByteArray, offset: Int) = u16(data, offset).toShort().toInt()

    private fun u32(data: ByteArray, offset: Int): Long =
        (u16(data, offset).toLong() shl 16) or u16(data, offset + 2).toLong()
}
