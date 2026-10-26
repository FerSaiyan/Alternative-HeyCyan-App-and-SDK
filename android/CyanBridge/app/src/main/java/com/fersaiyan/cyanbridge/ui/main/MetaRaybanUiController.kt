package com.fersaiyan.cyanbridge.ui.main

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.devices.metarayban.MetaDatPermissions
import com.fersaiyan.cyanbridge.shared.glasses.MetaRaybanUiState
import com.fersaiyan.cyanbridge.ui.MetaPairingDestination
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport
import com.meta.wearable.dat.core.types.DatResult
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionError
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Meta Ray-Ban (DAT) controls: Android and DAT camera permission readiness, the legacy Meta
 * buttons, and the Meta section of the dashboard. The permission launchers are registered by the
 * activity at construction time and their results are passed back through this controller.
 */
internal class MetaRaybanUiController(
    host: MainScreenHost,
    private val devices: DeviceManagerRegistry,
    private val metaAndroidPermissionLauncher: ActivityResultLauncher<Array<String>>,
    private val metaWearablePermissionLauncher: ActivityResultLauncher<Permission>,
) : MainScreenController(host) {
    private var pendingMetaDatAction: (() -> Unit)? = null
    private var pendingMetaCameraAction: (() -> Unit)? = null
    private var metaCameraReadinessInProgress = false
    var enabledMetaCameraCheckActive = false

    fun onAndroidPermissionsResult(result: Map<String, Boolean>) {
        MetaDatPermissions.recordRequestResult(activity, result)
        val action = pendingMetaDatAction
        pendingMetaDatAction = null
        enabledMetaCameraCheckActive = false
        if (metaAndroidPermissionsMissing().isEmpty()) {
            val manager = devices.getOrCreateMetaRaybanManager()
            manager.initialize()
            if (manager.isInitialized.value) {
                action?.invoke()
            } else {
                showMetaError(
                    "Android/DAT initialization",
                    manager.lastError.value ?: "Unable to initialize Meta Wearables DAT",
                )
            }
        } else {
            val denied = result.filterValues { !it }.keys.joinToString().ifBlank { "unknown" }
            showMetaError(
                "Android permissions",
                "Meta needs Bluetooth and camera permissions; denied=$denied",
            )
        }
    }

    fun onWearablePermissionResult(result: DatResult<PermissionStatus, PermissionError>) {
        val action = pendingMetaCameraAction
        pendingMetaCameraAction = null
        enabledMetaCameraCheckActive = false
        metaCameraReadinessInProgress = false
        result.fold(
            onSuccess = { status ->
                val granted = status == PermissionStatus.Granted
                devices.getOrCreateMetaRaybanManager().recordCameraPermissionResult(granted)
                if (granted) action?.invoke() else showMetaError("DAT camera permission", "Meta camera permission was denied")
            },
            onFailure = { error, _ ->
                devices.getOrCreateMetaRaybanManager().recordCameraPermissionResult(false)
                showMetaError("DAT camera permission", error.description)
            },
        )
    }

    fun metaAndroidPermissionsMissing(): Array<String> {
        return MetaDatPermissions.missing(activity)
    }

    fun ensureMetaDatReady(action: () -> Unit) {
        val missing = metaAndroidPermissionsMissing()
        if (missing.isNotEmpty()) {
            if (MetaDatPermissions.permanentlyDenied(activity)) {
                showMetaError("Android permissions", "Allow Camera and Nearby devices (Location on Android 10–11) in CyanBridge's Android app settings, then try again.")
                return
            }
            pendingMetaDatAction = action
            metaAndroidPermissionLauncher.launch(missing)
            return
        }

        val manager = devices.getOrCreateMetaRaybanManager()
        manager.initialize()
        if (manager.isInitialized.value) {
            action()
        } else {
            showMetaError(
                "Android/DAT initialization",
                manager.lastError.value ?: "Unable to initialize Meta Wearables DAT",
            )
        }
    }

    fun ensureMetaCameraReady(action: () -> Unit) {
        if (metaCameraReadinessInProgress) return
        metaCameraReadinessInProgress = true
        ensureMetaDatReady {
            val manager = devices.getOrCreateMetaRaybanManager()
            if (manager.isDebugMockEnabled()) {
                metaCameraReadinessInProgress = false
                action()
                return@ensureMetaDatReady
            }
            activity.lifecycleScope.launch {
                if (!manager.awaitCameraReady()) {
                    showMetaError("Glasses connection", "Reconnect your glasses in Meta AI, then return to CyanBridge.")
                    return@launch
                }
                manager.checkCameraPermission(
                    onGranted = { metaCameraReadinessInProgress = false; action() },
                    onRequestNeeded = {
                        pendingMetaCameraAction = action
                        metaWearablePermissionLauncher.launch(Permission.CAMERA)
                    },
                    onError = { error -> showMetaError("DAT camera permission", error) },
                )
            }
        }
    }

    fun setupMetaRaybanUi() {
        // Registration buttons
        binding.btnMetaRegister.setOnClickListener {
            ensureMetaDatReady {
                devices.getOrCreateMetaRaybanManager().startRegistration(activity)
            }
        }

        binding.btnMetaUnregister.setOnClickListener {
            ensureMetaDatReady {
                devices.getOrCreateMetaRaybanManager().startUnregistration(activity)
            }
        }

        // Session buttons
        binding.btnMetaSessionStart.setOnClickListener {
            ensureMetaDatReady {
                devices.getOrCreateMetaRaybanManager().startSession(
                    onSuccess = {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Meta session started", Toast.LENGTH_SHORT).show()
                            updateMetaRaybanUiState()
                        }
                    },
                    onError = { error ->
                        activity.runOnUiThread {
                            showMetaError("DAT session", error)
                        }
                    },
                )
            }
        }

        binding.btnMetaSessionStop.setOnClickListener {
            devices.metaRaybanManager?.stopSession()
            updateMetaRaybanUiState()
        }

        // Streaming buttons
        binding.btnMetaStreamStart.setOnClickListener {
            ensureMetaCameraReady {
                devices.getOrCreateMetaRaybanManager().startStreaming(
                    onFrame = { bitmap ->
                        Log.d("MainActivity", "Received Meta video frame: ${bitmap.width}x${bitmap.height}")
                    },
                    onSuccess = {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Streaming started", Toast.LENGTH_SHORT).show()
                            updateMetaRaybanUiState()
                        }
                    },
                    onError = { error ->
                        activity.runOnUiThread {
                            showMetaError("DAT stream", error)
                        }
                    },
                )
            }
        }

        binding.btnMetaStreamStop.setOnClickListener {
            devices.metaRaybanManager?.stopStreaming()
            updateMetaRaybanUiState()
        }

        // Photo capture button
        binding.btnMetaCapturePhoto.setOnClickListener {
            devices.metaRaybanManager?.capturePhoto(
                onSuccess = { photoData ->
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Photo captured!", Toast.LENGTH_SHORT).show()
                        binding.btnMetaViewPhoto.isEnabled = true
                        updateDashboardState { state ->
                            state.copy(metaRayban = state.metaRayban.copy(hasCapturedPhoto = true))
                        }
                    }
                },
                onError = { error ->
                    activity.runOnUiThread {
                        showMetaError("DAT photo capture", error)
                    }
                }
            )
        }

        // View last photo button
        binding.btnMetaViewPhoto.setOnClickListener {
            val photo = devices.metaRaybanManager?.lastCapturedPhoto?.value
            val uri = photo?.uri
            if (uri != null) {
                runCatching {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, photo.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                    )
                }.onFailure {
                    Toast.makeText(activity, "No photo viewer is installed", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(activity, "No captured Meta photo is available", Toast.LENGTH_SHORT).show()
            }
        }

        // Display buttons
        binding.btnMetaDisplayStart.setOnClickListener {
            ensureMetaDatReady {
                devices.getOrCreateMetaRaybanManager().startDisplay(
                    onSuccess = {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Display started", Toast.LENGTH_SHORT).show()
                            updateMetaRaybanUiState()
                        }
                    },
                    onError = { error ->
                        activity.runOnUiThread {
                            showMetaError("DAT display", error)
                        }
                    }
                )
            }
        }

        binding.btnMetaDisplayStop.setOnClickListener {
            devices.metaRaybanManager?.stopDisplay()
            updateMetaRaybanUiState()
        }
    }

    fun captureMetaPhotoForGallery() {
        ensureMetaCameraReady {
            val manager = devices.getOrCreateMetaRaybanManager()
            activity.lifecycleScope.launch(Dispatchers.IO) {
                runCatching { manager.capturePhotoOnce() }
                    .onSuccess {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Meta photo captured", Toast.LENGTH_SHORT).show()
                            updateMetaRaybanUiState()
                        }
                    }
                    .onFailure { error ->
                        withContext(Dispatchers.Main) {
                            showMetaError(
                                "DAT background photo",
                                error.message ?: "camera unavailable",
                            )
                        }
                    }
            }
        }
    }

    fun showMetaDiagnostics() {
        val manager = devices.getOrCreateMetaRaybanManager()
        DebugLogSupport.showSupportOptionsDialog(
            activity = activity,
            title = activity.getString(R.string.meta_diagnostics_title),
            issueType = "Meta Ray-Ban / DAT",
            description = activity.getString(R.string.meta_diagnostics_description),
            extraInfo = linkedMapOf(
                "Meta DAT snapshot" to manager.diagnosticsSnapshot(),
            ),
            dismissButtonLabel = activity.getString(R.string.action_cancel),
        )
    }

    fun openMetaAiAppOrStore() {
        val manager = devices.getOrCreateMetaRaybanManager()
        val launchIntent = manager.installedMetaAiPackageName()
            ?.let(activity.packageManager::getLaunchIntentForPackage)
        if (launchIntent != null) {
            activity.startActivity(launchIntent)
            return
        }
        runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.facebook.stella"))
                    .setPackage("com.android.vending"),
            )
        }.recoverCatching {
            activity.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=com.facebook.stella"),
                ),
            )
        }.onFailure {
            Toast.makeText(activity, "Could not open the Meta AI download page", Toast.LENGTH_LONG).show()
        }
    }

    fun showMetaError(operation: String, message: String) {
        enabledMetaCameraCheckActive = false
        metaCameraReadinessInProgress = false
        val manager = devices.getOrCreateMetaRaybanManager()
        val detail = message
        Log.e(
            "MainActivity",
            "$operation failed: $detail\n${manager.diagnosticsSnapshot()}",
        )
        updateMetaRaybanUiState()
        if (!activity.isFinishing && !activity.isDestroyed) {
            AlertDialog.Builder(activity)
                .setTitle("Complete Meta glasses setup")
                .setMessage("$operation: $detail\n\nOpen Meta setup for the next step: Android permissions, Meta AI connection, or glasses camera authorization.")
                .setPositiveButton("Open Meta setup") { _, _ -> activity.startActivity(AppRoute.META_PAIRING.intent(activity)) }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    fun updateMetaRaybanUiState() {
        val manager = devices.metaRaybanManager ?: return
        manager.refreshRegistrationState()

        // Update registration status
        val regState = manager.registrationState.value
        binding.tvMetaRegistrationStatus.text = "Registration: ${regState.name}"

        // Update session state
        val sessionState = manager.deviceSessionState.value
        binding.tvMetaSessionState.text = "Session: ${sessionState.name}"

        // Update stream state
        val streamState = manager.streamState.value
        binding.tvMetaStreamState.text = "Stream: ${streamState.name}"

        // Update display state
        val isDisplayActive = manager.isDisplayActive.value
        val displayCapable = manager.selectedDeviceIsDisplayCapable.value
        binding.layoutMetaDisplay.visibility = if (displayCapable) View.VISIBLE else View.GONE
        binding.tvMetaDisplayState.text = "Display: ${if (isDisplayActive) "Active" else "Inactive"}"

        // Enable/disable buttons based on state
        binding.btnMetaRegister.isEnabled = regState != com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERED &&
            regState != com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERING
        binding.btnMetaUnregister.isEnabled = regState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERED

        binding.btnMetaSessionStart.isEnabled = regState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERED &&
            manager.availableDeviceCount.value > 0 &&
            sessionState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.DeviceSessionState.IDLE
        binding.btnMetaSessionStop.isEnabled = sessionState != com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.DeviceSessionState.IDLE

        binding.btnMetaStreamStart.isEnabled = sessionState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.DeviceSessionState.STARTED &&
            streamState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.StreamState.STOPPED
        binding.btnMetaStreamStop.isEnabled = streamState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.StreamState.STREAMING

        binding.btnMetaCapturePhoto.isEnabled = streamState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.StreamState.STREAMING

        binding.btnMetaViewPhoto.isEnabled = manager.lastCapturedPhoto.value != null
        binding.btnMetaDisplayStart.isEnabled = displayCapable && sessionState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.DeviceSessionState.STARTED && !isDisplayActive
        binding.btnMetaDisplayStop.isEnabled = displayCapable && isDisplayActive
        updateDashboardState { state ->
            state.copy(
                metaRayban = MetaRaybanUiState(
                    registrationLabel = regState.name,
                    sessionLabel = sessionState.name,
                    streamLabel = streamState.name,
                    selectedDeviceName = manager.selectedDeviceName.value,
                    availableDeviceCount = manager.availableDeviceCount.value,
                    setupGuidance = manager.registrationGuidance(),
                    lastError = manager.lastError.value,
                    metaAiInstalled = manager.isMetaAiInstalled(),
                    displayCapable = displayCapable,
                    displayActive = isDisplayActive,
                    canRegister = regState != com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERED &&
                        regState != com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERING,
                    canUnregister = regState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERED,
                    canStartSession = regState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.RegistrationState.REGISTERED &&
                        manager.availableDeviceCount.value > 0 &&
                        sessionState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.DeviceSessionState.IDLE,
                    canStopSession = sessionState != com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.DeviceSessionState.IDLE,
                    canStartStream = sessionState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.DeviceSessionState.STARTED &&
                        streamState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.StreamState.STOPPED,
                    canStopStream = streamState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.StreamState.STREAMING,
                    canCapturePhoto = streamState == com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager.StreamState.STREAMING,
                    hasCapturedPhoto = manager.lastCapturedPhoto.value != null,
                    registrationPath = manager.registrationPath.value,
                ),
            )
        }
    }
}
