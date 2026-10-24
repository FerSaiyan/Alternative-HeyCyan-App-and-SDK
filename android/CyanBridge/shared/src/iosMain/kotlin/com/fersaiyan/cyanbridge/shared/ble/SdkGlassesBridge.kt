package com.fersaiyan.cyanbridge.shared.ble

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData
import kotlin.coroutines.resume

/**
 * A glasses vendor SDK that only exists in Swift (MoYoung CRPSmartGlasses, Meta Wearables DAT,
 * Mentra Bluetooth SDK). Unlike [VendorGlassesBridge] (QCSDK), these SDKs scan and connect on
 * their own, so the Kotlin side passes the identifier picked in the shared pairing screen.
 *
 * Device-specific operations go through [perform] with an action key (see [SdkGlassesActions]),
 * which keeps the Objective-C surface small for all three SDKs.
 */
interface SdkGlassesBridge {
    /** [DeviceClass] name this bridge drives. */
    val deviceClassName: String

    fun setListener(listener: SdkGlassesListener?)

    /**
     * Connects to the glasses with this identifier (empty: the first glasses the SDK finds).
     * On success the message is the identifier to reconnect with next time.
     */
    fun connect(identifier: String, name: String, completion: SdkResultCallback)
    fun disconnect()
    fun requestBattery()
    fun requestMediaCounts()

    /** Takes a photo for the assistant and returns its JPEG bytes (null when it failed). */
    fun captureAiPhoto(completion: SdkPhotoCallback)

    /** Runs a device-specific action; [SdkTextCallback] carries a status or error text. */
    fun perform(action: String, argument: String, completion: SdkTextCallback)
}

/** Events the SDKs push on their own; called on the main thread. */
interface SdkGlassesListener {
    /** [state]: one of the [SdkConnectionState] values. */
    fun onConnectionChanged(state: Int, label: String)
    fun onBattery(level: Int, charging: Boolean)
    fun onMediaCounts(photos: Int, videos: Int, audio: Int)

    /** A photo the glasses took on their own (AI button, capture gesture). */
    fun onAiImage(data: NSData)

    /** A hardware button or gesture, e.g. "press", "long_press". */
    fun onButton(gesture: String)

    /** A status value for the dashboard, keyed by [SdkGlassesStatus]. */
    fun onStatus(key: String, value: String)
}

fun interface SdkResultCallback {
    fun onResult(success: Boolean, message: String)
}

fun interface SdkPhotoCallback {
    fun onResult(data: NSData?, message: String)
}

fun interface SdkTextCallback {
    fun onResult(success: Boolean, value: String)
}

object SdkConnectionState {
    const val DISCONNECTED = 0
    const val CONNECTING = 1
    const val CONNECTED = 2
}

/** Keys for [SdkGlassesBridge.perform]. */
object SdkGlassesActions {
    const val CAPTURE_PHOTO = "capture_photo"
    const val SYNC_TIME = "sync_time"
    const val VERSION = "version"

    /** MoYoung: enter file-sync mode; the value is the glasses' media base URL. */
    const val START_FILE_SYNC = "start_file_sync"
    const val STOP_FILE_SYNC = "stop_file_sync"

    /** Meta: registration with the Meta AI app (opens it). */
    const val META_REGISTER = "meta_register"
    const val META_UNREGISTER = "meta_unregister"
    const val META_OPEN_META_AI = "meta_open_meta_ai"
    const val META_DIAGNOSTICS = "meta_diagnostics"
}

/** Keys for [SdkGlassesListener.onStatus]. */
object SdkGlassesStatus {
    const val META_REGISTRATION = "meta_registration"
    const val META_DEVICE_NAME = "meta_device_name"
    const val META_DEVICE_COUNT = "meta_device_count"
    const val META_AI_INSTALLED = "meta_ai_installed"
    const val META_DISPLAY_CAPABLE = "meta_display_capable"
    const val ERROR = "error"
}

/** The Swift host registers the SDK bridges it was built with before the Compose controller starts. */
object SdkGlassesRegistry {
    private val bridges = mutableMapOf<String, SdkGlassesBridge>()

    fun register(bridge: SdkGlassesBridge) {
        bridges[bridge.deviceClassName] = bridge
    }

    fun bridgeFor(deviceClass: DeviceClass): SdkGlassesBridge? = bridges[deviceClass.name]

    fun isAvailable(deviceClass: DeviceClass): Boolean = deviceClass.name in bridges
}

private const val SDK_CONNECT_TIMEOUT_MS = 30_000L
private const val SDK_PHOTO_TIMEOUT_MS = 90_000L
private const val SDK_ACTION_TIMEOUT_MS = 60_000L

/** Succeeds with the SDK's identifier for the connected glasses (saved to reconnect later). */
suspend fun SdkGlassesBridge.awaitConnect(identifier: String, name: String): Result<String> =
    withTimeoutOrNull(SDK_CONNECT_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            connect(identifier, name) { success, message ->
                if (continuation.isActive) {
                    continuation.resume(if (success) Result.success(message) else Result.failure(IllegalStateException(message)))
                }
            }
        }
    } ?: Result.failure(IllegalStateException("The glasses did not connect within ${SDK_CONNECT_TIMEOUT_MS / 1000}s"))

suspend fun SdkGlassesBridge.awaitAiPhoto(): Result<NSData> =
    withTimeoutOrNull(SDK_PHOTO_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            captureAiPhoto { data, message ->
                if (continuation.isActive) {
                    continuation.resume(data?.let { Result.success(it) } ?: Result.failure(IllegalStateException(message)))
                }
            }
        }
    } ?: Result.failure(IllegalStateException("The glasses photo did not arrive"))

suspend fun SdkGlassesBridge.awaitPerform(action: String, argument: String = ""): Result<String> =
    withTimeoutOrNull(SDK_ACTION_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            perform(action, argument) { success, value ->
                if (continuation.isActive) {
                    continuation.resume(if (success) Result.success(value) else Result.failure(IllegalStateException(value)))
                }
            }
        }
    } ?: Result.failure(IllegalStateException("The glasses did not answer ($action)"))
