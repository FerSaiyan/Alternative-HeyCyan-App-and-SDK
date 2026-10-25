package com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin

/**
 * The App side of the MemoMind Web plugin Bridge (PhoneSDK `packages/bridge-contract`,
 * revision p7-handoff-r1): method and permission registry, error codes and transport limits.
 * The published contract calls itself Bridge 2.0 (`bridgeVersion: "2.0"`, package
 * `schemaVersion: 2`, `permissionPolicyVersion: 1`).
 */
object WebPluginContract {
    const val BRIDGE_VERSION = "2.0"
    const val SCHEMA_VERSION = 2
    const val PERMISSION_POLICY_VERSION = 1

    /** The reference glasses profile the SDK reports and validates against. */
    const val DEVICE_PROFILE_ID = "jhv3-reference-v1"
    const val DISPLAY_WIDTH = 600
    const val DISPLAY_HEIGHT = 350

    /** Largest Scene Bridge / plugin message payload (one GM application message). */
    const val MAX_PAYLOAD_BYTES = 81_901
    const val MAX_IMAGE_BASE64_CHARACTERS = 128 * 1024

    /** Largest Bridge request the host parses (UTF-8 bytes of the JSON envelope). */
    const val MAX_REQUEST_BYTES = 128 * 1024
    const val MAX_FRAME_TILES = 256
    const val MAX_REBUILD_OPERATIONS = 128
    const val MAX_SUBSCRIPTIONS = 16

    const val STREAM_PORT_MESSAGE_TYPE = "gm-plugin:stream-port"
    const val AUDIO_STREAM_KIND = "audio.capture"

    object Permission {
        const val STORAGE = "storage"
        const val FILES = "files.user-selected"
        const val DISPLAY = "display"
        const val DEVICE_INFO = "device.info"
        const val DEVICE_EVENTS = "device.events"
        const val DEVICE_MESSAGING = "device.messaging"
        const val AUDIO_CAPTURE = "audio.capture"
        const val AUDIO_PLAYBACK = "audio.playback"
        const val NETWORK = "network"
        const val LOCATION = "location.foreground"
    }

    val PERMISSIONS: List<String> = listOf(
        Permission.STORAGE,
        Permission.FILES,
        Permission.DISPLAY,
        Permission.DEVICE_INFO,
        Permission.DEVICE_EVENTS,
        Permission.DEVICE_MESSAGING,
        Permission.AUDIO_CAPTURE,
        Permission.AUDIO_PLAYBACK,
        Permission.NETWORK,
        Permission.LOCATION,
    )

    /** `device.events` scope values, in the order of their Web Bridge channels 0x0100..0x0103. */
    val EVENT_TYPES: List<String> = listOf("button", "imuGesture", "rawImu", "connection")

    /** Every registered method and the manifest permission it needs (null: none). */
    val METHOD_PERMISSIONS: Map<String, String?> = linkedMapOf(
        "runtime.ready" to null,
        "runtime.ping" to null,
        "runtime.getBridgeVersion" to null,
        "runtime.getCapabilities" to null,
        "runtime.getLifecycleState" to null,
        "storage.get" to Permission.STORAGE,
        "storage.set" to Permission.STORAGE,
        "storage.remove" to Permission.STORAGE,
        "storage.clear" to Permission.STORAGE,
        "files.pick" to Permission.FILES,
        "files.list" to Permission.FILES,
        "files.stat" to Permission.FILES,
        "files.openRead" to Permission.FILES,
        "files.getUsage" to Permission.FILES,
        "files.delete" to Permission.FILES,
        "display.createPage" to Permission.DISPLAY,
        "display.closePage" to Permission.DISPLAY,
        "display.updateText" to Permission.DISPLAY,
        "display.updateImage" to Permission.DISPLAY,
        "display.updateImageLz4" to Permission.DISPLAY,
        "display.rebuildPage" to Permission.DISPLAY,
        "display.beginFrame" to Permission.DISPLAY,
        "display.updateFrameImageLz4" to Permission.DISPLAY,
        "device.getInfo" to Permission.DEVICE_INFO,
        "device.subscribeEvents" to Permission.DEVICE_EVENTS,
        "device.unsubscribeEvents" to Permission.DEVICE_EVENTS,
        "plugin.sendMessage" to Permission.DEVICE_MESSAGING,
        "audio.openCapture" to Permission.AUDIO_CAPTURE,
        "audio.stopCapture" to Permission.AUDIO_CAPTURE,
        "location.getCurrentPosition" to Permission.LOCATION,
        "location.watchPosition" to Permission.LOCATION,
        "location.clearWatch" to Permission.LOCATION,
    )

    /** Registered methods this host does not provide; they answer CAPABILITY_UNAVAILABLE after authorization. */
    val UNSUPPORTED_METHODS: Set<String> = setOf(
        "files.pick",
        "files.list",
        "files.stat",
        "files.openRead",
        "files.getUsage",
        "files.delete",
        "location.getCurrentPosition",
        "location.watchPosition",
        "location.clearWatch",
    )

    /** Standard Web Bridge channels a plugin message may only use with `display` as well. */
    val DISPLAY_CHANNELS: Set<Int> = setOf(1, 2, 6, 7, 8, 9, 0x0104)

    val PICKUP_MODES: List<String> = listOf(
        "unchanged",
        "frontFixed",
        "meetingAuto",
        "nonWearerFocus",
        "frontBalanced",
        "frontFocus",
    )
}

/** Bridge error codes (`ERROR_CODES` of the contract). */
enum class WebPluginErrorCode {
    INVALID_REQUEST,
    PAYLOAD_TOO_LARGE,
    UNAUTHORIZED,
    PERMISSION_DENIED,
    FILE_NOT_FOUND,
    STALE_RUNTIME,
    METHOD_NOT_FOUND,
    RATE_LIMITED,
    BUSY,
    AUDIO_BUSY,
    NO_AUDIO,
    BUFFER_OVERFLOW,
    QUOTA_EXCEEDED,
    TIMEOUT,
    DEVICE_DISCONNECTED,
    CAPABILITY_UNAVAILABLE,
    RUNTIME_CLOSED,
    RUNTIME_REPLACED,
    INTERNAL_ERROR,
}

/** A Bridge failure returned to the plugin as `{ ok: false, error: { code, message } }`. */
class WebPluginBridgeException(val code: WebPluginErrorCode, message: String) : Exception(message)

internal fun bridgeError(code: WebPluginErrorCode, message: String): Nothing = throw WebPluginBridgeException(code, message)

internal fun invalidRequest(message: String): Nothing = bridgeError(WebPluginErrorCode.INVALID_REQUEST, message)
