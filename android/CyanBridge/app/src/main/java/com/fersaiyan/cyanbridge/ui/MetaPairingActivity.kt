package com.fersaiyan.cyanbridge.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.devices.metarayban.MetaAccessState
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
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
) {
    val androidPermissionsGranted: Boolean
        get() = androidCameraGranted && nearbyDevicesGranted

    val isRegistered: Boolean
        get() = registrationState == MetaRaybanManager.RegistrationState.REGISTERED

    val isReadyForImageQuestion: Boolean
        get() = androidPermissionsGranted && initialized && isRegistered &&
            availableDeviceCount > 0 && glassesCameraGranted

    val primaryLabel: String
        get() = when {
            !androidPermissionsGranted -> "Grant required permissions"
            !initialized -> "Initialize Meta connection"
            !metaAiInstalled -> "Install Meta AI"
            metaAccessState == MetaAccessState.NEEDS_META_INVITE -> "Request Meta access"
            !isRegistered -> "Register CyanBridge in Meta AI"
            availableDeviceCount == 0 -> "Refresh glasses connection"
            !glassesCameraGranted -> "Grant glasses camera access"
            else -> "Test AI image question"
        }
}

internal fun inferredMetaPairingError(state: MetaPairingScreenState): String? {
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

    private val androidPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            refreshState()
            val denied = requiredAndroidPermissions().filter { permission ->
                grants[permission] != true && !hasPermission(permission)
            }
            if (denied.isNotEmpty()) {
                screenState = screenState.copy(
                    lastError = "Android permission denied: ${denied.joinToString { it.substringAfterLast('.') }}",
                )
            } else if (hasRequiredAndroidPermissions()) {
                initializeDat()
            }
        }

    private val glassesCameraPermissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val granted = result.getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted
            refreshState(checkGlassesCamera = false)
            screenState = screenState.copy(
                glassesCameraGranted = granted,
                lastError = if (granted) manager.lastError.value else "Meta glasses camera permission was denied",
            )
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
                launch { manager.lastError.collect { refreshState(checkGlassesCamera = false) } }
            }
        }
    }

    private fun refreshState(checkGlassesCamera: Boolean = true) {
        screenState = screenState.copy(
            androidCameraGranted = hasPermission(Manifest.permission.CAMERA),
            nearbyDevicesGranted = hasNearbyDevicesPermission(),
            initialized = manager.isInitialized.value,
            registrationState = manager.registrationState.value,
            availableDeviceCount = manager.availableDeviceCount.value,
            selectedDeviceName = manager.selectedDeviceName.value,
            guidance = manager.registrationGuidance(),
            lastError = manager.lastError.value,
            metaAiInstalled = manager.isMetaAiInstalled(),
            metaAccessState = manager.metaAccessState.value,
        )
        if (checkGlassesCamera && screenState.initialized && screenState.isRegistered) {
            checkGlassesCameraPermission()
        }
    }

    private fun initializeDat() {
        manager.initialize()
        manager.refreshRegistrationState()
        refreshState()
    }

    private fun checkGlassesCameraPermission() {
        if (checkingGlassesCameraPermission) return
        checkingGlassesCameraPermission = true
        manager.checkCameraPermission(
            onGranted = {
                checkingGlassesCameraPermission = false
                screenState = screenState.copy(glassesCameraGranted = true)
            },
            onRequestNeeded = {
                checkingGlassesCameraPermission = false
                screenState = screenState.copy(glassesCameraGranted = false)
            },
            onError = {
                checkingGlassesCameraPermission = false
                refreshState(checkGlassesCamera = false)
            },
        )
    }

    private fun performPrimaryAction() {
        when {
            !screenState.androidPermissionsGranted ->
                androidPermissionLauncher.launch(requiredAndroidPermissions())
            !screenState.initialized -> initializeDat()
            !screenState.metaAiInstalled -> openMetaAi()
            screenState.metaAccessState == MetaAccessState.NEEDS_META_INVITE -> openBetaAccess()
            !screenState.isRegistered -> manager.startRegistration(this)
            screenState.availableDeviceCount == 0 -> {
                manager.refreshRegistrationState()
                refreshState()
                Toast.makeText(
                    this,
                    "Keep Meta AI open and the glasses powered, unfolded, and nearby.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            !screenState.glassesCameraGranted ->
                glassesCameraPermissionLauncher.launch(Permission.CAMERA)
            else -> startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_START_META_IMAGE_QUESTION, true)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
        }
    }

    private fun retryPairing() {
        when {
            !screenState.androidPermissionsGranted ->
                androidPermissionLauncher.launch(requiredAndroidPermissions())
            !screenState.initialized -> initializeDat()
            !screenState.metaAiInstalled -> openMetaAi()
            screenState.metaAccessState == MetaAccessState.NEEDS_META_INVITE -> openBetaAccess()
            !screenState.isRegistered -> manager.startRegistration(this)
            screenState.availableDeviceCount == 0 -> {
                manager.refreshRegistrationState()
                refreshState()
            }
            !screenState.glassesCameraGranted ->
                glassesCameraPermissionLauncher.launch(Permission.CAMERA)
            else -> refreshState()
        }
    }

    private fun handleRegistrationCallback(callbackIntent: Intent) {
        if (manager.handleRegistrationCallback(callbackIntent)) {
            manager.refreshRegistrationState()
            refreshState()
        }
    }

    private fun requiredAndroidPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.toTypedArray()

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
) {
    var showInitialPairingNotice by androidx.compose.runtime.remember {
        mutableStateOf(true)
    }
    val inferredError = inferredMetaPairingError(state)
    val pairingIssue = resolveMetaPairingIssue(
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
                    complete = state.availableDeviceCount > 0,
                    status = state.selectedDeviceName
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
                    modifier = Modifier.fillMaxWidth().testTag("meta_pairing_primary_action"),
                ) {
                    Text(state.primaryLabel)
                }
                OutlinedButton(
                    onClick = onSendDiagnostics,
                    modifier = Modifier.fillMaxWidth().testTag("meta_pairing_diagnostics"),
                ) {
                    Text("Send Meta diagnostics")
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
