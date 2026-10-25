package com.fersaiyan.cyanbridge.shared.devices.memomind

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** GM service IDs (command IDs are relative to their service). */
object MemoMindService {
    const val SYSTEM = 0x01
    const val TRANSLATION = 0x03
    const val TELEPROMPTER = 0x04
    const val NOTIFICATION = 0x05
    const val NAVIGATION = 0x06
    const val PLUGIN = 0x0F
    const val ACCESSORY = 0x10
}

/** Published commands of each service (GlassSDK DEVICE_BUSINESS_PROTOCOL / AUDIO / HUD docs). */
object MemoMindCommand {
    // System, service 0x01.
    const val HEARTBEAT = 0x01
    const val DEVICE_INFO = 0x02
    const val DEVICE_STATUS = 0x06
    const val MEDIA_FULLSCREEN = 0x09
    const val SCREEN_HEIGHT = 0x0A
    const val OPTICAL_DISTANCE = 0x0B
    const val HEAD_UP_ANGLE = 0x0C
    const val BRIGHTNESS = 0x0E
    const val AUTO_BRIGHTNESS = 0x0F
    const val RECORDING_PROCESSING = 0x2F
    const val SCREENSHOT = 0x65
    const val AI_VOICE_REPLY = 0x71
    const val MEDIA_CONTROL = 0x76

    // Translation, service 0x03.
    const val TRANSLATION_START = 0x01
    const val TRANSLATION_STOP = 0x02
    const val TRANSLATION_PROVISIONAL = 0x03
    const val TRANSLATION_FINAL = 0x04
    const val TRANSLATION_PAUSE = 0x09
    const val TRANSLATION_RESUME = 0x0A
    const val TRANSLATION_LOCAL_PAUSE_RESUME = 0x16

    // Teleprompter, service 0x04.
    const val TELEPROMPTER_START = 0x01
    const val TELEPROMPTER_STOP = 0x02
    const val TELEPROMPTER_PAGE_REQUEST = 0x03
    const val TELEPROMPTER_DISPLAY_UPDATE = 0x04
    const val TELEPROMPTER_TEXT = 0x05
    const val TELEPROMPTER_HIGHLIGHT = 0x06
    const val TELEPROMPTER_MODE = 0x07
    const val TELEPROMPTER_FONT_SIZE = 0x08
    const val TELEPROMPTER_LINE_WIDTH = 0x09
    const val TELEPROMPTER_PAUSE = 0x0A
    const val TELEPROMPTER_RESUME = 0x0B

    // Notifications, service 0x05 (0x02 is retired).
    const val NOTIFICATION_PUSH = 0x01
    const val NOTIFICATION_SWITCH = 0x03
    const val NOTIFICATION_SCROLL_SPEED = 0x04

    // Plugin application messages, service 0x0F.
    const val APP_MESSAGE = 0x28
    const val APP_MESSAGE_FROM_GLASSES = 0x29
}

/** System STATUS values (service 0x01 and most business acknowledgements). */
object MemoMindStatus {
    const val SUCCESS = 0
    const val ERROR = 1
    const val INVALID_COMMAND = 2
    const val INVALID_PARAMETER = 3
    const val OUT_OF_MEMORY = 4
    const val TIMEOUT = 5
    const val NOT_SUPPORTED = 6
    const val BUSY = 7
    const val RETRY = 8

    fun describe(status: Int): String = when (status) {
        SUCCESS -> "success"
        ERROR -> "error"
        INVALID_COMMAND -> "invalid command"
        INVALID_PARAMETER -> "invalid parameter"
        OUT_OF_MEMORY -> "out of memory"
        TIMEOUT -> "timeout"
        NOT_SUPPORTED -> "not supported"
        BUSY -> "busy"
        RETRY -> "retry"
        else -> "status $status"
    }
}

/** Plugin service INT8 results (GM_PLUGIN_*), a separate namespace from [MemoMindStatus]. */
object MemoMindPluginStatus {
    const val OK = 0
    const val INVALID = 1
    const val NOT_SUPPORTED = 2
    const val BUSY = 3
    const val NO_MEMORY = 4
    const val IO = 5
    const val NOT_PERMITTED = 6
    const val WRONG_STATE = 7
    const val VERSION = 8
    const val FULL = 9
}

/** Recording noise processing selected with [MemoMindCommands.recordingProcessing]. */
enum class MemoMindNoiseMode(val wire: Int) {
    /** ENC noise reduction (default). */
    NOISE_REDUCTION(0),
    RAW(1),
}

/** Microphone pickup; omit it (null) for firmware that does not support the selection. */
enum class MemoMindPickupMode(val wire: Int) {
    FRONT_FIXED(0),
    MEETING_AUTO(1),
    NON_WEARER_FOCUS(2),
    FRONT_BALANCED(3),
    FRONT_FOCUS(4),
}

/** Phone media operations of system command 0x76. */
enum class MemoMindMediaOperation(val wire: Int) {
    PLAY(0),
    PAUSE(1),
    VOLUME_UP(2),
    VOLUME_DOWN(3),
    MUTE(4),
    SET_VOLUME(5),
    NEXT(6),
    PREVIOUS(7),
}

/** Notification card style (service 0x05 command 0x01). */
enum class MemoMindNotificationStyle(val wire: Int) {
    DETAILED(1),
    COMPACT(2),
}

/** Teleprompter start parameters, sent as the positional JSON array of command 0x01. */
data class MemoMindTeleprompterLayout(
    val width: Int = 600,
    val height: Int = 350,
    val cachePages: Int = 3,
    /** 1 manual, 2 auto, 3 AI follow. */
    val mode: Int = 1,
    /** 1 small, 2 large. */
    val fontSize: Int = 1,
    /** 1 narrow, 2 medium, 3 wide. */
    val lineWidth: Int = 2,
    /** 0 preview, 1 actual. */
    val scene: Int = 1,
)

/** The published GM business requests. Event IDs are assigned by [MemoMindSession]. */
object MemoMindCommands {
    private fun system(command: Int, vararg tlvs: GmTlv) = GmRequest(MemoMindService.SYSTEM, command, tlvs.toList())

    fun heartbeat() = system(MemoMindCommand.HEARTBEAT)
    fun deviceInfo() = system(MemoMindCommand.DEVICE_INFO)
    fun deviceStatus() = system(MemoMindCommand.DEVICE_STATUS)

    /** Brightness level 0..10. */
    fun brightness(level: Int) = system(MemoMindCommand.BRIGHTNESS, GmTlv.int8(level.coerceIn(0, 10)))
    fun autoBrightness(enabled: Boolean) = system(MemoMindCommand.AUTO_BRIGHTNESS, GmTlv.int8(if (enabled) 1 else 0))

    /** HUD vertical position level 0..8 (not a pixel coordinate). */
    fun screenHeight(level: Int) = system(MemoMindCommand.SCREEN_HEIGHT, GmTlv.int8(level.coerceIn(0, 8)))
    fun opticalDistance(level: Int) = system(MemoMindCommand.OPTICAL_DISTANCE, GmTlv.int8(level.coerceIn(0, 8)))
    fun headUpAngle(angle: Int) = system(MemoMindCommand.HEAD_UP_ANGLE, GmTlv.int8(angle.coerceIn(0, 255)))
    fun mediaFullscreen(enabled: Boolean) = system(MemoMindCommand.MEDIA_FULLSCREEN, GmTlv.int8(if (enabled) 1 else 0))

    /** Microphone processing for the next recording; send before opening the recording link. */
    fun recordingProcessing(noise: MemoMindNoiseMode, pickup: MemoMindPickupMode? = null): GmRequest =
        GmRequest(
            MemoMindService.SYSTEM,
            MemoMindCommand.RECORDING_PROCESSING,
            listOfNotNull(GmTlv.int8(noise.wire), pickup?.let { GmTlv.int8(it.wire) }),
        )

    fun mediaControl(operation: MemoMindMediaOperation, volumePercent: Int? = null): GmRequest {
        require((operation == MemoMindMediaOperation.SET_VOLUME) == (volumePercent != null)) {
            "Only SET_VOLUME carries a volume"
        }
        return GmRequest(
            MemoMindService.SYSTEM,
            MemoMindCommand.MEDIA_CONTROL,
            listOfNotNull(GmTlv.int8(operation.wire), volumePercent?.let { GmTlv.int8(it.coerceIn(0, 100)) }),
        )
    }

    /** The glasses' AI voice-reply preference (no speech or audio is carried). */
    fun aiVoiceReply(enabled: Boolean) = system(MemoMindCommand.AI_VOICE_REPLY, GmTlv.int8(if (enabled) 1 else 0))

    fun screenshot() = system(MemoMindCommand.SCREENSHOT)

    fun translationStart(title: String) =
        GmRequest(MemoMindService.TRANSLATION, MemoMindCommand.TRANSLATION_START, listOf(GmTlv.string(title)))

    fun translationStop() = GmRequest(MemoMindService.TRANSLATION, MemoMindCommand.TRANSLATION_STOP)

    /** A result inside an active translation session; the array is translation first. */
    fun translationResult(translated: String, source: String, final: Boolean) = GmRequest(
        MemoMindService.TRANSLATION,
        if (final) MemoMindCommand.TRANSLATION_FINAL else MemoMindCommand.TRANSLATION_PROVISIONAL,
        listOf(GmTlv.json(JsonArray(listOf(JsonPrimitive(translated), JsonPrimitive(source))).toString())),
    )

    fun teleprompterStart(layout: MemoMindTeleprompterLayout = MemoMindTeleprompterLayout()) = GmRequest(
        MemoMindService.TELEPROMPTER,
        MemoMindCommand.TELEPROMPTER_START,
        listOf(
            GmTlv.json(
                intArray(
                    layout.width, layout.height, layout.cachePages, layout.mode,
                    layout.fontSize, layout.lineWidth, layout.scene,
                ),
            ),
        ),
    )

    fun teleprompterStop() = GmRequest(MemoMindService.TELEPROMPTER, MemoMindCommand.TELEPROMPTER_STOP)

    /** Text content; [contentMode] 1 initial, 2 prepend, 3 append. */
    fun teleprompterText(lines: List<String>, contentMode: Int = 1) = GmRequest(
        MemoMindService.TELEPROMPTER,
        MemoMindCommand.TELEPROMPTER_TEXT,
        listOf(GmTlv.int8(contentMode), GmTlv.json(JsonArray(lines.map(::JsonPrimitive)).toString())),
    )

    fun teleprompterHighlight(start: Int, end: Int) = GmRequest(
        MemoMindService.TELEPROMPTER,
        MemoMindCommand.TELEPROMPTER_HIGHLIGHT,
        listOf(GmTlv.json(intArray(start, end))),
    )

    fun teleprompterPause(paused: Boolean) = GmRequest(
        MemoMindService.TELEPROMPTER,
        if (paused) MemoMindCommand.TELEPROMPTER_PAUSE else MemoMindCommand.TELEPROMPTER_RESUME,
    )

    /**
     * A notification card. [timestampSeconds] follows the App convention: wall-clock seconds
     * adjusted by the phone's zone offset, not UTC milliseconds.
     */
    fun notification(
        id: Int,
        appName: String,
        title: String,
        content: String,
        packageName: String,
        timestampSeconds: Long,
        style: MemoMindNotificationStyle = MemoMindNotificationStyle.DETAILED,
        type: Int = NOTIFICATION_TYPE_APP,
    ): GmRequest {
        val json = buildJsonObject {
            put("id", id)
            put("a", appName)
            put("type", type)
            put("ts", timestampSeconds)
            put("c", content)
            put("ti", title)
            put("pkg_name", packageName)
        }
        return GmRequest(
            MemoMindService.NOTIFICATION,
            MemoMindCommand.NOTIFICATION_PUSH,
            listOf(GmTlv.int8(style.wire), GmTlv.json(json.toString())),
        )
    }

    fun notificationsEnabled(enabled: Boolean) = GmRequest(
        MemoMindService.NOTIFICATION,
        MemoMindCommand.NOTIFICATION_SWITCH,
        listOf(GmTlv.int8(if (enabled) 1 else 0)),
    )

    /**
     * An application message to the running glasses plugin. [data] is opaque and must be
     * non-empty (the runtime rejects empty messages).
     */
    fun appMessage(channel: Int, data: ByteArray): GmRequest {
        require(channel in 0..0xFFFF) { "channel must be 0..65535" }
        require(data.isNotEmpty()) { "application messages must not be empty" }
        return GmRequest(
            MemoMindService.PLUGIN,
            MemoMindCommand.APP_MESSAGE,
            listOf(GmTlv.int16(channel), GmTlv.bytes(data)),
        )
    }

    const val NOTIFICATION_TYPE_APP = 0
    const val NOTIFICATION_TYPE_SIMULATED = 1
    const val NOTIFICATION_TYPE_SCHEDULE = 2

    private fun intArray(vararg values: Int): String = JsonArray(values.map { JsonPrimitive(it) }).toString()
}

/** Raw recording-link control bytes (no GM envelope; byte 2 is a parameter, not a length). */
object MemoMindRecordingControl {
    const val MAGIC = 0x52
    const val FORMAT_LEGACY = 0
    const val FORMAT_COMPACT = 1

    fun selectFormat(compact: Boolean) = byteArrayOf(0x52, 0x12, if (compact) 1 else 0)

    /** Starts the default recording scene. */
    fun start() = byteArrayOf(0x52, 0x01, 0x00)

    /** Stops capture; the glasses send no acknowledgement. */
    fun stop() = byteArrayOf(0x52, 0x00, 0x00)
}

/**
 * Application payloads of the public GM Web Bridge glasses plugin (GlassSDK
 * examples/web_bridge). They are delivered with [MemoMindCommands.appMessage] and only draw
 * while a Web Bridge compatible plugin runs on the glasses.
 */
object MemoMindWebBridge {
    const val CHANNEL_CLEAR = 1
    const val CHANNEL_TEXT = 2
    const val CHANNEL_RECT = 3
    const val CHANNEL_DELETE = 4
    const val CHANNEL_LINE = 5
    const val CHANNEL_BITMAP = 6
    const val CHANNEL_BITMAP_LZ4 = 7
    const val CHANNEL_FRAME_BEGIN = 8
    const val CHANNEL_FRAME_TILE_LZ4 = 9
    const val CHANNEL_BUTTON = 0x0100
    const val CHANNEL_IMU_GESTURE = 0x0101
    const val CHANNEL_RAW_IMU = 0x0102
    const val CHANNEL_CONNECTION = 0x0103
    const val CHANNEL_FRAME_STATUS = 0x0104
    const val CHANNEL_PING = 0x7FFE

    /** The reference display (jhv3-reference-v1): 600 x 350 GRAY_4. */
    const val DISPLAY_WIDTH = 600
    const val DISPLAY_HEIGHT = 350

    /** The renderer keeps at most this many live text/rectangle/line objects. */
    const val MAX_OBJECTS = 16

    /** Channel 1 carries one compatibility byte; the runtime rejects empty messages. */
    fun clear() = byteArrayOf(0)

    fun text(id: Int, x: Int, y: Int, width: Int, height: Int, text: String, border: Int = 0, radius: Int = 0): ByteArray =
        box(id, x, y, width, height, border, radius) + text.replace("\u0000", "").encodeToByteArray()

    fun rectangle(id: Int, x: Int, y: Int, width: Int, height: Int, border: Int = 1, radius: Int = 0): ByteArray =
        box(id, x, y, width, height, border, radius)

    fun delete(id: Int) = byteArrayOf(id.toByte())

    fun line(id: Int, x1: Int, y1: Int, x2: Int, y2: Int, stroke: Int = 1): ByteArray =
        byteArrayOf(id.toByte()) + u16(x1) + u16(y1) + u16(x2) + u16(y2) + byteArrayOf(stroke.toByte())

    /** Raw GRAY_4 pixels, two per byte (high nibble left); [pixels] holds `stride * height` bytes. */
    fun bitmap(x: Int, y: Int, width: Int, height: Int, stride: Int, pixels: ByteArray): ByteArray {
        require(width > 0 && height > 0 && stride >= (width + 1) / 2) { "invalid bitmap geometry" }
        require(pixels.size == stride * height) { "bitmap needs stride * height bytes" }
        return u16(x) + u16(y) + u16(width) + u16(height) + u16(stride) + pixels
    }

    private fun box(id: Int, x: Int, y: Int, width: Int, height: Int, border: Int, radius: Int): ByteArray =
        byteArrayOf(id.toByte()) + u16(x) + u16(y) + u16(width) + u16(height) +
            byteArrayOf(border.toByte(), radius.toByte())

    private fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
}
