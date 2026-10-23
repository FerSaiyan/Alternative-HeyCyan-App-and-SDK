package com.fersaiyan.cyanbridge.devices.metarayban

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Explicitly unavailable adapter for builds without the optional Meta DAT SDK. */
@Suppress("UNUSED_PARAMETER")
class MetaRaybanManager private constructor() {
    companion object {
        const val UNAVAILABLE_REASON = "Meta support is unavailable in this build: the optional Meta DAT SDK was not included."
        private val instance = MetaRaybanManager()
        fun getInstance(context: Context): MetaRaybanManager = instance
    }

    enum class RegistrationState { UNAVAILABLE, AVAILABLE, REGISTERED, REGISTERING, UNREGISTERING }
    enum class DeviceSessionState { IDLE, STARTING, STARTED, PAUSED, STOPPING, STOPPED }
    enum class StreamState { STOPPED, STARTING, STARTED, STREAMING, STOPPING, PAUSED, CLOSED }
    data class CapturedPhoto(val bytes: ByteArray, val mimeType: String, val uri: Uri?)

    val isInitialized: StateFlow<Boolean> = MutableStateFlow(false)
    val registrationState: StateFlow<RegistrationState> = MutableStateFlow(RegistrationState.UNAVAILABLE)
    val metaAccessState: StateFlow<MetaAccessState> = MutableStateFlow(MetaAccessState.FAILED)
    val availableDeviceCount: StateFlow<Int> = MutableStateFlow(0)
    val selectedDeviceName: StateFlow<String?> = MutableStateFlow(null)
    val selectedDeviceIsDisplayCapable: StateFlow<Boolean> = MutableStateFlow(false)
    val deviceSessionState: StateFlow<DeviceSessionState> = MutableStateFlow(DeviceSessionState.IDLE)
    val streamState: StateFlow<StreamState> = MutableStateFlow(StreamState.STOPPED)
    val isStreaming: StateFlow<Boolean> = MutableStateFlow(false)
    val lastCapturedPhoto: StateFlow<CapturedPhoto?> = MutableStateFlow(null)
    val isDisplayActive: StateFlow<Boolean> = MutableStateFlow(false)
    val lastError: StateFlow<String?> = MutableStateFlow(UNAVAILABLE_REASON)
    val debugMockEnabled: StateFlow<Boolean> = MutableStateFlow(false)

    fun initialize() = Unit
    fun isDebugMockEnabled() = false
    fun setDebugMockEnabled(enabled: Boolean) = Unit
    fun startRegistration(activity: Activity) = Unit
    fun startUnregistration(activity: Activity) = Unit
    fun isRegistered() = false
    fun isCameraReady() = false
    suspend fun awaitCameraReady(timeoutMs: Long = 10_000L) = false
    fun refreshRegistrationState() = Unit
    fun handleRegistrationCallback(intent: Intent) = false
    fun checkCameraPermission(onGranted: () -> Unit, onRequestNeeded: () -> Unit, onError: (String) -> Unit) = onError(UNAVAILABLE_REASON)
    fun startSession(onSuccess: () -> Unit, onError: (String) -> Unit) = onError(UNAVAILABLE_REASON)
    fun stopSession() = Unit
    fun startStreaming(onFrame: (Bitmap) -> Unit, onSuccess: () -> Unit, onError: (String) -> Unit) = onError(UNAVAILABLE_REASON)
    fun stopStreaming() = Unit
    fun capturePhoto(onSuccess: (CapturedPhoto) -> Unit, onError: (String) -> Unit) = onError(UNAVAILABLE_REASON)
    suspend fun capturePhotoOnce(timeoutMs: Long = 20_000L): CapturedPhoto = throw UnsupportedOperationException(UNAVAILABLE_REASON)
    suspend fun savePhotoForProcessing(photo: CapturedPhoto, namePrefix: String): File = throw UnsupportedOperationException(UNAVAILABLE_REASON)
    fun startDisplay(onSuccess: () -> Unit, onError: (String) -> Unit) = onError(UNAVAILABLE_REASON)
    fun stopDisplay() = Unit
    fun reportExternalError(operation: String, message: String) = "$operation: $message; $UNAVAILABLE_REASON"
    fun diagnosticsSnapshot() = UNAVAILABLE_REASON
    fun registrationGuidance(): String = UNAVAILABLE_REASON
    fun installedMetaAiPackageName(): String? = null
    fun isMetaAiInstalled() = false
    fun destroy() = Unit
}
