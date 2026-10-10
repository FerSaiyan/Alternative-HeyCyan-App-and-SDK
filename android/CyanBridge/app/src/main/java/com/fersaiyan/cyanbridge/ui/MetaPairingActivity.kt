package com.fersaiyan.cyanbridge.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.bluetooth.BluetoothManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.devices.metarayban.MetaAccessState
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.devices.metarayban.MetaDatPermissions
import com.fersaiyan.cyanbridge.shared.glasses.MetaPairingIssueAction
import com.fersaiyan.cyanbridge.shared.glasses.resolveMetaPairingIssue
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.launch

data class MetaPairingScreenState(
    val androidCameraGranted: Boolean = false,
    val nearbyDevicesGranted: Boolean = false,
    val initialized: Boolean = false,
    val registrationState: MetaRaybanManager.RegistrationState = MetaRaybanManager.RegistrationState.UNAVAILABLE,
    val availableDeviceCount: Int = 0,
    val selectedDeviceName: String? = null,
    val glassesCameraGranted: Boolean = false,
    val guidance: String? = null,
    val lastError: String? = null,
    val metaAiInstalled: Boolean = true,
    val metaAccessState: MetaAccessState = MetaAccessState.UNKNOWN,
    val debugMockEnabled: Boolean = false,
    val bluetoothEnabled: Boolean = true,
    val deviceConnected: Boolean = true,
    val androidPermissionPermanentlyDenied: Boolean = false,
    val checkingCameraPermission: Boolean = false,
) {
    val androidPermissionsGranted: Boolean
        get() = androidCameraGranted && nearbyDevicesGranted

    val isRegistered: Boolean
        get() = registrationState == MetaRaybanManager.RegistrationState.REGISTERED

    val isReadyForImageQuestion: Boolean
        get() = androidPermissionsGranted && initialized && isRegistered &&
            availableDeviceCount > 0 && deviceConnected && bluetoothEnabled && glassesCameraGranted

    val primaryLabel: String
        get() = when {
            debugMockEnabled -> "Test AI image question (mock)"
            androidPermissionPermanentlyDenied -> "Open Android app settings"
            !androidPermissionsGranted -> "Grant required permissions"
            !bluetoothEnabled -> "Turn on Bluetooth"
            !initialized -> "Initialize Meta connection"
            !metaAiInstalled -> "Install Meta AI"
            metaAccessState == MetaAccessState.NEEDS_META_INVITE -> "Request Meta access"
            !isRegistered -> "Register CyanBridge in Meta AI"
            availableDeviceCount == 0 -> "Refresh glasses connection"
            !deviceConnected -> "Reconnect glasses in Meta AI"
            checkingCameraPermission -> "Checking glasses camera access…"
            !glassesCameraGranted -> "Grant glasses camera access"
            else -> "Test AI image question"
        }
}

internal data class MetaSetupPrompt(val title: String, val message: String)

internal fun nextMetaSetupPrompt(state: MetaPairingScreenState): MetaSetupPrompt? = when {
    state.debugMockEnabled -> null
    state.androidPermissionPermanentlyDenied -> MetaSetupPrompt(
        "Allow permissions in Android settings",
        "Android can no longer show the permission request. Open CyanBridge's app settings, select Permissions, and allow Camera and Nearby devices (Location on Android 10–11). Return here to continue.",
    )
    !state.androidPermissionsGranted -> MetaSetupPrompt(
        "Allow Camera and Nearby devices",
        "Tap Grant required permissions, then allow Android's requests. On Android 10–11, allow Location for glasses discovery. These permissions are separate from Meta's glasses camera authorization.",
    )
    !state.bluetoothEnabled -> MetaSetupPrompt("Turn on Bluetooth", "Enable Bluetooth in Android settings, then return here with your glasses powered, unfolded, and nearby.")
    state.initialized && state.registrationState == MetaRaybanManager.RegistrationState.AVAILABLE -> MetaSetupPrompt(
        "Authorize CyanBridge in Meta AI", "Approve CyanBridge in the Meta AI registration screen, then return here. Keep the glasses connected in Meta AI.",
    )
    state.isRegistered && state.availableDeviceCount > 0 && !state.deviceConnected -> MetaSetupPrompt(
        "Reconnect your Meta glasses", "Registration is complete, but the glasses are disconnected or still connecting. Open Meta AI, power on and unfold the glasses, and confirm they are connected. Return here to check camera access.",
    )
    state.isRegistered && state.availableDeviceCount > 0 && !state.glassesCameraGranted && !state.checkingCameraPermission -> MetaSetupPrompt(
        "Allow glasses camera access", "Meta needs a separate camera authorization. Tap Grant glasses camera access, approve the request in Meta AI, and return here. You can then test an AI image question.",
    )
    else -> null
}

internal fun inferredMetaPairingError(state: MetaPairingScreenState): String? {
    if (state.debugMockEnabled) return null // Mock bypasses Meta AI requirement for testing
    state.lastError?.takeIf { it.isNotBlank() }?.let { return it }
    if (!state.metaAiInstalled) return "Meta AI app is not installed"
    if (!state.initialized) return null

    if (state.metaAccessState == MetaAccessState.NEEDS_META_INVITE) {
        return "Meta DAT registration is unavailable for this account or app release channel"
    }

    if (state.isRegistered && state.availableDeviceCount == 0) {
        return "No DAT device was discovered after Meta registration"
    }

    if (state.registrationState == MetaRaybanManager.RegistrationState.UNAVAILABLE) {
        val guidance = state.guidance.orEmpty()
        if (guidance.contains("DAT cannot see", ignoreCase = true)) {
            return "DAT cannot see a linked Meta wearable"
        }
        if (guidance.contains("Developer Mode", ignoreCase = true)) {
            return "Meta DAT registration is unavailable because Developer Mode or device authorization is incomplete"
        }
        if (guidance.contains("release channel", ignoreCase = true)) {
            return "Meta DAT registration is unavailable for this account or app release channel"
        }
    }

    return null
}

class MetaPairingActivity : AppCompatActivity() {
    private val manager by lazy { MetaRaybanManager.getInstance(this) }
    private var screenState by mutableStateOf(MetaPairingScreenState())
    private var checkingGlassesCameraPermission = false
    private var cameraCheckKey: String? = null
    private var androidPermissionError: String? = null
    private var androidPermissionPermanentlyDenied = false
    private var requestingGlassesCameraPermission = false

    private val androidPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            MetaDatPermissions.recordRequestResult(this, grants)
            refreshState()
            val denied = requiredAndroidPermissions().filter { permission ->
                grants[permission] != true && !hasPermission(permission)
            }
            if (denied.isNotEmpty()) {
                androidPermissionPermanentlyDenied = denied.any { !shouldShowRequestPermissionRationale(it) }
                androidPermissionError = "Android permission denied: ${denied.joinToString { it.substringAfterLast('.') }}"
                refreshState(checkGlassesCamera = false)
            } else if (hasRequiredAndroidPermissions()) {
                androidPermissionPermanentlyDenied = false
                androidPermissionError = null
                initializeDat()
            }
        }

    private val glassesCameraPermissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            requestingGlassesCameraPermission = false
            result.fold(
                onSuccess = { status ->
                    val granted = status == PermissionStatus.Granted
                    manager.recordCameraPermissionResult(granted)
                    if (!granted) manager.reportExternalError("cameraPermission", "Meta glasses camera permission was denied. Approve camera access in Meta AI and try again.")
                },
                onFailure = { error, _ ->
                    manager.recordCameraPermissionResult(false)
                    manager.reportExternalError("cameraPermission", error.description)
                },
            )
            refreshState(checkGlassesCamera = false)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                MetaPairingScreen(
                    state = screenState,
                    onBack = ::finish,
                    onOpenMetaAi = ::openMetaAi,
                    onPrimaryAction = ::performPrimaryAction,
                    onRetryPairing = ::retryPairing,
                    onSendDiagnostics = ::showDiagnostics,
                    onRequestAccess = ::openBetaAccess,
                    onRefreshInvite = ::refreshInviteStatus,
                    onToggleMock = { enabled -> manager.setDebugMockEnabled(enabled); refreshState() },
                )
            }
        }
        observeManager()
        if (hasRequiredAndroidPermissions()) initializeDat()
        handleRegistrationCallback(intent)
        refreshState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRegistrationCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        cameraCheckKey = null
        refreshState()
        if (hasRequiredAndroidPermissions()) initializeDat()
    }

    private fun observeManager() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { manager.isInitialized.collect { refreshState() } }
                launch { manager.registrationState.collect { refreshState() } }
                launch { manager.metaAccessState.collect { refreshState() } }
                launch { manager.availableDeviceCount.collect { refreshState() } }
                launch { manager.selectedDeviceName.collect { refreshState() } }
                launch { manager.selectedDeviceLinkState.collect { refreshState() } }
                launch { manager.cameraPermissionGranted.collect { refreshState(checkGlassesCamera = false) } }
                launch { manager.lastError.collect { refreshState(checkGlassesCamera = false) } }
                launch { manager.debugMockEnabled.collect { refreshState(checkGlassesCamera = false) } }
            }
        }
    }

    private fun refreshState(checkGlassesCamera: Boolean = true) {
        androidPermissionPermanentlyDenied = MetaDatPermissions.permanentlyDenied(this)
        if (hasRequiredAndroidPermissions()) {
            androidPermissionPermanentlyDenied = false
            androidPermissionError = null
        }
        screenState = screenState.copy(
            androidCameraGranted = hasPermission(Manifest.permission.CAMERA),
            nearbyDevicesGranted = hasNearbyDevicesPermission(),
            initialized = manager.isInitialized.value,
            registrationState = manager.registrationState.value,
            availableDeviceCount = manager.availableDeviceCount.value,
            selectedDeviceName = manager.selectedDeviceName.value,
            guidance = manager.registrationGuidance(),
            lastError = androidPermissionError ?: manager.lastError.value,
            metaAiInstalled = manager.isMetaAiInstalled(),
            metaAccessState = manager.metaAccessState.value,
            debugMockEnabled = manager.debugMockEnabled.value,
            deviceConnected = manager.isCameraReady(),
            glassesCameraGranted = manager.cameraPermissionGranted.value,
            bluetoothEnabled = hasNearbyDevicesPermission() && runCatching {
                getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
            }.getOrDefault(false),
            androidPermissionPermanentlyDenied = androidPermissionPermanentlyDenied,
            checkingCameraPermission = checkingGlassesCameraPermission,
        )
        if (checkGlassesCamera && screenState.androidPermissionsGranted && screenState.initialized && screenState.deviceConnected &&
            !requestingGlassesCameraPermission) {
            val key = "${screenState.registrationState}:${screenState.selectedDeviceName}:${screenState.availableDeviceCount}:${screenState.deviceConnected}"
            if (cameraCheckKey == key) return
            cameraCheckKey = key
            checkGlassesCameraPermission()
        } else if (!screenState.deviceConnected) cameraCheckKey = null
    }

    private fun initializeDat() {
        manager.initialize()
        manager.refreshRegistrationState()
        refreshState()
    }

    private fun checkGlassesCameraPermission() {
        if (manager.debugMockEnabled.value) {
            screenState = screenState.copy(glassesCameraGranted = true)
            return
        }
        if (checkingGlassesCameraPermission) return
        checkingGlassesCameraPermission = true
        screenState = screenState.copy(checkingCameraPermission = true)
        manager.checkCameraPermission(
            onGranted = {
                checkingGlassesCameraPermission = false
                refreshState(checkGlassesCamera = false)
            },
            onRequestNeeded = {
                checkingGlassesCameraPermission = false
                refreshState(checkGlassesCamera = false)
            },
            onError = {
                checkingGlassesCameraPermission = false
                refreshState(checkGlassesCamera = false)
            },
        )
    }

    private fun performPrimaryAction() {
        if (requestingGlassesCameraPermission || checkingGlassesCameraPermission) return
        when {
            screenState.debugMockEnabled -> {
                // Mock bypasses Meta AI install/registration for testing
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_START_META_IMAGE_QUESTION, true)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                )
                return
            }
            screenState.androidPermissionPermanentlyDenied -> openAppSettings()
            !screenState.androidPermissionsGranted ->
                androidPermissionLauncher.launch(requiredAndroidPermissions())
            !screenState.bluetoothEnabled -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            !screenState.initialized -> initializeDat()
            !screenState.metaAiInstalled -> openMetaAi()
            screenState.metaAccessState == MetaAccessState.NEEDS_META_INVITE -> openBetaAccess()
            !screenState.isRegistered -> manager.startRegistration(this)
            !screenState.deviceConnected && screenState.availableDeviceCount > 0 -> openMetaAi()
            screenState.availableDeviceCount == 0 -> {
                // Invited + registered but no glasses — refresh rather than restart
                manager.refreshRegistrationState()
                refreshState()
                Toast.makeText(
                    this,
                    if (screenState.isRegistered) "You're registered — pair glasses in Meta AI, then Refresh again."
                    else "Keep Meta AI open and the glasses powered, unfolded, and nearby.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            !screenState.glassesCameraGranted -> {
                requestingGlassesCameraPermission = true
                glassesCameraPermissionLauncher.launch(Permission.CAMERA)
            }
            else -> startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_START_META_IMAGE_QUESTION, true)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
        }
    }

    private fun retryPairing() {
        cameraCheckKey = null
        if (!screenState.isReadyForImageQuestion) performPrimaryAction() else refreshState()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun refreshInviteStatus() {
        manager.refreshRegistrationState()
        refreshState()
        Toast.makeText(this, "Refreshed Meta registration — ${screenState.registrationState.name}", Toast.LENGTH_SHORT).show()
    }

    private fun handleRegistrationCallback(callbackIntent: Intent) {
        if (manager.handleRegistrationCallback(callbackIntent)) {
            manager.refreshRegistrationState()
            refreshState()
        }
    }

    private fun requiredAndroidPermissions(): Array<String> = MetaDatPermissions.required()

    private fun hasRequiredAndroidPermissions(): Boolean =
        requiredAndroidPermissions().all(::hasPermission)

    private fun hasNearbyDevicesPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openMetaAi() {
        val launchIntent = manager.installedMetaAiPackageName()
            ?.let(packageManager::getLaunchIntentForPackage)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$META_AI_PACKAGE"))
                        .setPackage("com.android.vending"),
                )
            }.recoverCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(META_AI_PLAY_STORE_URL)))
            }.onFailure {
                screenState = screenState.copy(lastError = "Could not open the Meta AI download page")
            }
        }
    }

    private fun openBetaAccess() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(META_BETA_ACCESS_URL)))
        }.onFailure {
            screenState = screenState.copy(lastError = "Could not open the Meta access request page")
        }
    }

    private fun showDiagnostics() {
        DebugLogSupport.showSupportOptionsDialog(
            activity = this,
            title = getString(R.string.meta_diagnostics_title),
            issueType = "Meta Ray-Ban / DAT",
            description = getString(R.string.meta_diagnostics_description),
            extraInfo = linkedMapOf("Meta DAT snapshot" to manager.diagnosticsSnapshot()),
            dismissButtonLabel = getString(R.string.action_cancel),
        )
    }

    private companion object {
        const val META_AI_PACKAGE = "com.facebook.stella"
        const val META_AI_PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.facebook.stella"
        const val META_BETA_ACCESS_URL = "https://cyanbridge.vercel.app/beta"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetaPairingScreen(
    state: MetaPairingScreenState,
    onBack: () -> Unit,
    onOpenMetaAi: () -> Unit,
    onPrimaryAction: () -> Unit,
    onRetryPairing: () -> Unit,
    onSendDiagnostics: () -> Unit,
    onRequestAccess: () -> Unit = {},
    onRefreshInvite: () -> Unit = {},
    onToggleMock: (Boolean) -> Unit = {},
) {
    var showInitialPairingNotice by androidx.compose.runtime.remember {
        mutableStateOf(true)
    }
    val inferredError = inferredMetaPairingError(state)
    val pairingIssue = if (state.androidPermissionPermanentlyDenied) null else resolveMetaPairingIssue(
        metaAiInstalled = state.metaAiInstalled,
        lastError = inferredError,
        setupGuidance = state.guidance,
        metaAccessRequired = state.metaAccessState == MetaAccessState.NEEDS_META_INVITE,
    )
    var showPairingIssue by androidx.compose.runtime.remember(inferredError, state.guidance, state.metaAiInstalled) {
        mutableStateOf(pairingIssue != null)
    }

    if (showInitialPairingNotice) {
        AlertDialog(
            onDismissRequest = { showInitialPairingNotice = false },
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
            title = { Text("Meta pairing reliability notice") },
            text = {
                Text(
                    "Some users are experiencing issues with reliable Meta Glasses pairing. If you encounter an issue and get stuck, please send the logs with an available email for the developer to better understand and fix the issue, since I am having difficulties reproducing the error on my device.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showInitialPairingNotice = false }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showInitialPairingNotice = false
                        onSendDiagnostics()
                    },
                ) {
                    Text("Send logs")
                }
            },
        )
    } else if (pairingIssue != null && showPairingIssue) {
        AlertDialog(
            onDismissRequest = { showPairingIssue = false },
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
            title = { Text(pairingIssue.title) },
            text = { Text(pairingIssue.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPairingIssue = false
                        when (pairingIssue.action) {
                            MetaPairingIssueAction.INSTALL_META_AI -> onOpenMetaAi()
                            MetaPairingIssueAction.OPEN_PAIRING -> onRetryPairing()
                            MetaPairingIssueAction.OPEN_META_AI -> onOpenMetaAi()
                            MetaPairingIssueAction.REQUEST_ACCESS -> onRequestAccess()
                        }
                    },
                ) {
                    Text(pairingIssue.primaryLabel)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPairingIssue = false
                        onSendDiagnostics()
                    },
                ) {
                    Text("Send logs")
                }
            },
        )
    } else if (pairingIssue == null) {
        val prompt = nextMetaSetupPrompt(state)
        var showStep by androidx.compose.runtime.remember(prompt) { mutableStateOf(prompt != null) }
        if (prompt != null && showStep) AlertDialog(
            onDismissRequest = { showStep = false },
            modifier = Modifier.testTag("meta_setup_next_step"),
            title = { Text(prompt.title) },
            text = { Text(prompt.message) },
            confirmButton = {
                TextButton(onClick = { showStep = false; onPrimaryAction() }) { Text(state.primaryLabel) }
            },
            dismissButton = { TextButton(onClick = { showStep = false }) { Text("Later") } },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Pair Meta glasses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .testTag("meta_pairing_screen"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Connect through Meta AI", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Pair your glasses in Meta AI first. CyanBridge then asks Meta AI to authorize camera access; it does not pair the glasses through the Bluetooth scan list.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onOpenMetaAi, modifier = Modifier.fillMaxWidth()) {
                            Text("Open Meta AI")
                        }
                    }
                }
            }
            item {
                SetupStep(
                    title = "1. Required Android permissions",
                    detail = "Camera is required for AI image questions. Nearby Devices lets DAT discover glasses already paired in Meta AI.",
                    complete = state.androidPermissionsGranted,
                    status = when {
                        state.androidPermissionsGranted -> "Camera and Nearby Devices granted"
                        else -> "Permission required"
                    },
                )
            }
            item {
                SetupStep(
                    title = "2. Authorize CyanBridge",
                    detail = "Registration opens Meta AI. Approve CyanBridge and return here.",
                    complete = state.isRegistered,
                    status = state.registrationState.name.replace('_', ' ').lowercase()
                        .replaceFirstChar { it.uppercase() },
                )
            }
            item {
                SetupStep(
                    title = "3. Discover your glasses",
                    detail = "Keep the glasses powered, unfolded, nearby, and connected in Meta AI.",
                    complete = state.availableDeviceCount > 0 && state.deviceConnected,
                    status = state.selectedDeviceName
                        ?.let { if (state.deviceConnected) it else "$it — connecting or disconnected" }
                        ?: if (state.availableDeviceCount > 0) {
                            "${state.availableDeviceCount} Meta device(s) available"
                        } else {
                            "Waiting for a DAT device"
                        },
                )
            }
            item {
                SetupStep(
                    title = "4. Allow glasses camera",
                    detail = "This is Meta's separate authorization for receiving camera frames from the glasses.",
                    complete = state.glassesCameraGranted,
                    status = if (state.glassesCameraGranted) "Glasses camera allowed" else "Authorization required",
                )
            }
            state.guidance?.takeIf { it.isNotBlank() }?.let { guidance ->
                item {
                    Text(
                        guidance,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("meta_pairing_guidance"),
                    )
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onPrimaryAction,
                    enabled = !state.checkingCameraPermission && state.registrationState !in setOf(
                        MetaRaybanManager.RegistrationState.REGISTERING, MetaRaybanManager.RegistrationState.UNREGISTERING,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("meta_pairing_primary_action"),
                ) {
                    Text(state.primaryLabel)
                }
                if (state.isRegistered && state.availableDeviceCount == 0 && !state.debugMockEnabled) {
                    OutlinedButton(
                        onClick = onRefreshInvite,
                        modifier = Modifier.fillMaxWidth().testTag("meta_pairing_refresh"),
                    ) {
                        Text("Check again (Refresh)")
                    }
                }
                OutlinedButton(
                    onClick = onSendDiagnostics,
                    modifier = Modifier.fillMaxWidth().testTag("meta_pairing_diagnostics"),
                ) {
                    Text("Send Meta diagnostics")
                }
            }
            if (BuildConfig.DEBUG) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Debug: Mock Ray-Ban", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Test pairing flow without physical glasses. Simulates registered + device + camera.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            androidx.compose.material3.Switch(
                                checked = state.debugMockEnabled,
                                onCheckedChange = onToggleMock,
                                modifier = Modifier.testTag("meta_mock_switch"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupStep(
    title: String,
    detail: String,
    complete: Boolean,
    status: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (complete) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    status,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
