package com.fersaiyan.cyanbridge.shared.ui.glasses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaBlocker
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaComponent
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaErrorKind
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaFailure
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaPhase
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaProgress
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaRules
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraOtaStage
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraWifiError
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraWifiNetwork
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.action_back
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_back_blocked
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_available_title
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_battery_low
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_battery_ok
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_battery_unknown
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_before_title
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_busy
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_cancel
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_charging
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_charging_recommended
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_check_again
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_check_failed
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_checking
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_complete
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_component_app
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_component_bes
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_component_system
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_confirm_install
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_confirm_message
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_confirm_title
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_continue_needed
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_continue_update
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_cooldown
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_done
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_downgrade
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_error_code
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_failed_battery
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_failed_glasses
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_failed_install
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_failed_network
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_failed_no_progress
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_failed_restart_timeout
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_failed_stalled
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_failed_start
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_failed_storage
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_failed_timeout
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_failed_title
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_finishing
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_installed_title
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_keep_open
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_microphone_in_use
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_newer_than_release
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_not_connected
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_percent
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_phase_download_app
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_phase_download_bes
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_phase_download_system
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_phase_install_app
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_phase_install_bes
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_phase_install_system
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_phase_working
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_restart_required
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_restarting
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_starting
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_step_progress
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_target_release
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_title
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_unofficial_client
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_up_to_date
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_update_now
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_verifying
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_version_app
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_version_bes
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_version_system
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_version_unknown
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_wifi_connect
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_wifi_connect_failed
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_wifi_connected
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_wifi_connecting
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_wifi_no_networks
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_wifi_password
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_wifi_password_title
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_wifi_required
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_wifi_scan
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_wifi_scan_failed
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_wifi_scanning
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_wifi_secured
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_wifi_setup
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_wifi_title
import com.fersaiyan.cyanbridge.shared.generated.resources.mentra_ota_wifi_wrong_password
import com.fersaiyan.cyanbridge.shared.icons.AppIcon
import com.fersaiyan.cyanbridge.shared.icons.imageVector
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Mentra Live firmware update: current versions, the update check, preconditions, a
 * confirmation, per-step progress and the result. [onClose] runs only when the view model
 * allows leaving (never while the update runs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MentraFirmwareUpdateScreen(
    viewModel: MentraFirmwareUpdateViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val leaveNotice = stringResource(Res.string.mentra_ota_back_blocked)
    LaunchedEffect(viewModel.leaveRefusals) {
        if (viewModel.leaveRefusals > 0) snackbar.showSnackbar(leaveNotice)
    }
    LaunchedEffect(Unit) { viewModel.onOpened() }
    val ota = state.ota

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.mentra_ota_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (viewModel.onLeaveRequested()) onClose() }) {
                        Icon(
                            imageVector = AppIcon.Back.imageVector(),
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (ota.isActive) Notice(stringResource(Res.string.mentra_ota_keep_open), warning = true)
            InstalledCard(state)
            StatusCard(state, viewModel, onClose)
            if (ota.stage == MentraOtaStage.UPDATE_AVAILABLE) PreconditionsCard(state, viewModel)
            if (viewModel.showWifiSetup && !ota.isActive) WifiCard(state, viewModel)
        }
    }

    if (viewModel.confirming) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text(stringResource(Res.string.mentra_ota_confirm_title)) },
            text = { Text(stringResource(Res.string.mentra_ota_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmStart,
                    enabled = state.blockers.isEmpty(),
                    modifier = Modifier.testTag("mentra_ota_confirm"),
                ) { Text(stringResource(Res.string.mentra_ota_confirm_install)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConfirmation) { Text(stringResource(Res.string.mentra_ota_cancel)) }
            },
        )
    }
    viewModel.passwordFor?.let { network -> PasswordDialog(network, viewModel) }
}

@Composable
private fun InstalledCard(state: MentraFirmwareUpdateUiState) {
    val versions = state.glasses.versions
    val unknown = stringResource(Res.string.mentra_ota_version_unknown)
    SectionCard(stringResource(Res.string.mentra_ota_installed_title)) {
        val app = versions.appVersion?.takeIf { it.isNotBlank() } ?: unknown
        val build = versions.buildNumber?.takeIf { it.isNotBlank() } ?: unknown
        BodyText(stringResource(Res.string.mentra_ota_version_app, app, build))
        BodyText(stringResource(Res.string.mentra_ota_version_system, versions.systemVersion?.takeIf { it.isNotBlank() } ?: unknown))
        BodyText(stringResource(Res.string.mentra_ota_version_bes, versions.besVersion?.takeIf { it.isNotBlank() } ?: unknown))
        state.ota.manifest?.let { BodyText(stringResource(Res.string.mentra_ota_target_release, it.releaseVersion), muted = true) }
    }
}

@Composable
private fun StatusCard(state: MentraFirmwareUpdateUiState, viewModel: MentraFirmwareUpdateViewModel, onClose: () -> Unit) {
    val ota = state.ota
    val connected = state.glasses.ready
    Card(
        modifier = Modifier.fillMaxWidth().testTag("mentra_ota_status"),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (ota.stage) {
                MentraOtaStage.IDLE,
                MentraOtaStage.CHECKING -> if (connected || ota.stage == MentraOtaStage.CHECKING) {
                    Busy(stringResource(Res.string.mentra_ota_checking))
                } else {
                    BodyText(stringResource(Res.string.mentra_ota_not_connected))
                }
                MentraOtaStage.CHECK_FAILED -> {
                    BodyText(
                        stringResource(
                            if (ota.failure == MentraOtaFailure.UNOFFICIAL_CLIENT) {
                                Res.string.mentra_ota_unofficial_client
                            } else {
                                Res.string.mentra_ota_check_failed
                            },
                        ),
                    )
                    CheckAgain(viewModel, enabled = connected)
                }
                MentraOtaStage.UP_TO_DATE -> {
                    TitleText(stringResource(Res.string.mentra_ota_up_to_date))
                    val manifest = ota.manifest
                    if (manifest != null && MentraOtaRules.offer(manifest, state.glasses.versions).downgrade) {
                        BodyText(stringResource(Res.string.mentra_ota_newer_than_release), muted = true)
                    }
                    CheckAgain(viewModel, enabled = connected)
                }
                MentraOtaStage.UPDATE_AVAILABLE -> UpdateAvailable(state, viewModel)
                MentraOtaStage.STARTING -> Busy(stringResource(Res.string.mentra_ota_starting))
                MentraOtaStage.UPDATING -> Progress(ota.progress)
                MentraOtaStage.FINISHING -> {
                    ota.progress?.let { Progress(it) }
                    Busy(stringResource(Res.string.mentra_ota_finishing))
                }
                MentraOtaStage.RESTARTING -> Busy(stringResource(Res.string.mentra_ota_restarting))
                MentraOtaStage.VERIFYING -> Busy(stringResource(Res.string.mentra_ota_verifying))
                MentraOtaStage.COMPLETE -> {
                    TitleText(stringResource(Res.string.mentra_ota_complete))
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(Res.string.mentra_ota_done))
                    }
                }
                MentraOtaStage.FAILED -> Failed(state, viewModel)
            }
        }
    }
}

@Composable
private fun UpdateAvailable(state: MentraFirmwareUpdateUiState, viewModel: MentraFirmwareUpdateViewModel) {
    val offer = state.ota.offer ?: return
    val versions = state.glasses.versions
    val unknown = stringResource(Res.string.mentra_ota_version_unknown)
    TitleText(stringResource(Res.string.mentra_ota_available_title, offer.manifest.releaseVersion))
    if (state.ota.installedDuringSession) BodyText(stringResource(Res.string.mentra_ota_continue_needed))
    offer.components.forEach { component ->
        val line = when (component) {
            MentraOtaComponent.APP -> stringResource(
                Res.string.mentra_ota_component_app,
                versions.buildNumber?.takeIf { it.isNotBlank() } ?: unknown,
                offer.manifest.appVersionCode?.toString() ?: unknown,
            )
            MentraOtaComponent.SYSTEM -> stringResource(Res.string.mentra_ota_component_system)
            MentraOtaComponent.BES -> stringResource(
                Res.string.mentra_ota_component_bes,
                versions.besVersion?.takeIf { it.isNotBlank() } ?: unknown,
                offer.manifest.besVersion ?: unknown,
            )
        }
        BodyText("• $line")
    }
    Button(
        onClick = viewModel::requestStart,
        enabled = state.blockers.isEmpty(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mentra_ota_update_now"),
    ) {
        Text(
            stringResource(
                if (state.ota.installedDuringSession) Res.string.mentra_ota_continue_update else Res.string.mentra_ota_update_now,
            ),
        )
    }
}

@Composable
private fun PreconditionsCard(state: MentraFirmwareUpdateUiState, viewModel: MentraFirmwareUpdateViewModel) {
    val glasses = state.glasses
    val blockers = state.blockers
    SectionCard(stringResource(Res.string.mentra_ota_before_title)) {
        if (MentraOtaBlocker.NOT_CONNECTED in blockers) Notice(stringResource(Res.string.mentra_ota_not_connected), warning = true)
        val battery = glasses.batteryPercent
        when {
            battery == null -> BodyText(stringResource(Res.string.mentra_ota_battery_unknown))
            MentraOtaBlocker.BATTERY_LOW in blockers ->
                Notice(stringResource(Res.string.mentra_ota_battery_low, battery, MentraOtaRules.MIN_BATTERY_PERCENT), warning = true)
            else -> BodyText(stringResource(Res.string.mentra_ota_battery_ok, battery))
        }
        if (glasses.charging) {
            BodyText(stringResource(Res.string.mentra_ota_charging))
        } else if (state.chargingRecommended) {
            BodyText(stringResource(Res.string.mentra_ota_charging_recommended), muted = true)
        }
        val ssid = glasses.wifiSsid
        if (ssid.isNullOrBlank()) {
            Notice(stringResource(Res.string.mentra_ota_wifi_required), warning = true)
        } else {
            BodyText(stringResource(Res.string.mentra_ota_wifi_connected, ssid))
        }
        if (!viewModel.showWifiSetup) {
            OutlinedButton(
                onClick = viewModel::openWifiSetup,
                enabled = glasses.ready,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(Res.string.mentra_ota_wifi_setup)) }
        }
        if (MentraOtaBlocker.MICROPHONE_IN_USE in blockers) Notice(stringResource(Res.string.mentra_ota_microphone_in_use), warning = true)
        if (MentraOtaBlocker.GLASSES_BUSY in blockers) Notice(stringResource(Res.string.mentra_ota_busy), warning = true)
        if (MentraOtaBlocker.DOWNGRADE in blockers) Notice(stringResource(Res.string.mentra_ota_downgrade), warning = true)
        if (MentraOtaBlocker.COOLDOWN in blockers) Notice(stringResource(Res.string.mentra_ota_cooldown), warning = true)
    }
}

@Composable
private fun Failed(state: MentraFirmwareUpdateUiState, viewModel: MentraFirmwareUpdateViewModel) {
    val ota = state.ota
    TitleText(stringResource(Res.string.mentra_ota_failed_title))
    BodyText(stringResource(failureMessage(ota.failure, ota.glassesErrorCode)))
    ota.glassesErrorCode?.let { BodyText(stringResource(Res.string.mentra_ota_error_code, it), muted = true) }
    if (ota.restartRequired) {
        Notice(stringResource(Res.string.mentra_ota_restart_required), warning = true)
    } else {
        CheckAgain(viewModel, enabled = state.glasses.ready)
    }
}

private fun failureMessage(failure: MentraOtaFailure?, code: String?): StringResource = when (failure) {
    MentraOtaFailure.START_FAILED -> Res.string.mentra_ota_failed_start
    MentraOtaFailure.BATTERY_LOW -> Res.string.mentra_ota_failed_battery
    MentraOtaFailure.NO_PROGRESS -> Res.string.mentra_ota_failed_no_progress
    MentraOtaFailure.STALLED -> Res.string.mentra_ota_failed_stalled
    MentraOtaFailure.RESTART_TIMEOUT -> Res.string.mentra_ota_failed_restart_timeout
    MentraOtaFailure.SESSION_TIMEOUT -> Res.string.mentra_ota_failed_timeout
    else -> when (MentraOtaRules.errorKind(code)) {
        MentraOtaErrorKind.NETWORK -> Res.string.mentra_ota_failed_network
        MentraOtaErrorKind.STORAGE -> Res.string.mentra_ota_failed_storage
        MentraOtaErrorKind.INSTALL -> Res.string.mentra_ota_failed_install
        MentraOtaErrorKind.BATTERY -> Res.string.mentra_ota_failed_battery
        MentraOtaErrorKind.UNKNOWN -> Res.string.mentra_ota_failed_glasses
    }
}

@Composable
private fun Progress(progress: MentraOtaProgress?) {
    if (progress == null) {
        Busy(stringResource(Res.string.mentra_ota_starting))
        return
    }
    val step = stringResource(phaseLabel(progress))
    TitleText(
        if (progress.totalSteps > 0 && progress.currentStep > 0) {
            stringResource(Res.string.mentra_ota_step_progress, progress.currentStep, progress.totalSteps, step)
        } else {
            step
        },
    )
    LinearProgressIndicator(
        progress = { progress.percent / 100f },
        modifier = Modifier.fillMaxWidth().testTag("mentra_ota_progress"),
    )
    BodyText(stringResource(Res.string.mentra_ota_percent, progress.percent), muted = true)
}

private fun phaseLabel(progress: MentraOtaProgress): StringResource {
    val download = progress.phase == MentraOtaPhase.DOWNLOAD
    return when (progress.component) {
        MentraOtaComponent.APP -> if (download) Res.string.mentra_ota_phase_download_app else Res.string.mentra_ota_phase_install_app
        MentraOtaComponent.SYSTEM -> if (download) Res.string.mentra_ota_phase_download_system else Res.string.mentra_ota_phase_install_system
        MentraOtaComponent.BES -> if (download) Res.string.mentra_ota_phase_download_bes else Res.string.mentra_ota_phase_install_bes
        null -> Res.string.mentra_ota_phase_working
    }
}

@Composable
private fun WifiCard(state: MentraFirmwareUpdateUiState, viewModel: MentraFirmwareUpdateViewModel) {
    val wifi = state.wifi
    SectionCard(stringResource(Res.string.mentra_ota_wifi_title)) {
        wifi.connectingSsid?.let { Busy(stringResource(Res.string.mentra_ota_wifi_connecting, it)) }
        wifi.error?.let { error ->
            Notice(
                stringResource(
                    when (error) {
                        MentraWifiError.SCAN_FAILED -> Res.string.mentra_ota_wifi_scan_failed
                        MentraWifiError.WRONG_PASSWORD -> Res.string.mentra_ota_wifi_wrong_password
                        MentraWifiError.CONNECT_FAILED -> Res.string.mentra_ota_wifi_connect_failed
                    },
                ),
                warning = true,
            )
        }
        if (wifi.scanning) {
            Busy(stringResource(Res.string.mentra_ota_wifi_scanning))
        } else if (wifi.scanned && wifi.networks.isEmpty()) {
            BodyText(stringResource(Res.string.mentra_ota_wifi_no_networks))
        }
        wifi.networks.forEach { network ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = wifi.connectingSsid == null && state.glasses.ready) { viewModel.chooseNetwork(network) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = network.ssid,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (network.secured) {
                    Text(
                        text = stringResource(Res.string.mentra_ota_wifi_secured),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = viewModel::scanWifi,
                enabled = !wifi.scanning && state.glasses.ready,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(stringResource(Res.string.mentra_ota_wifi_scan)) }
            TextButton(onClick = viewModel::closeWifiSetup, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.mentra_ota_cancel))
            }
        }
    }
}

@Composable
private fun PasswordDialog(network: MentraWifiNetwork, viewModel: MentraFirmwareUpdateViewModel) {
    var password by remember(network.ssid) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = viewModel::dismissPassword,
        title = { Text(stringResource(Res.string.mentra_ota_wifi_password_title, network.ssid)) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(Res.string.mentra_ota_wifi_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { viewModel.connectWifi(password) }, enabled = password.isNotEmpty()) {
                Text(stringResource(Res.string.mentra_ota_wifi_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissPassword) { Text(stringResource(Res.string.mentra_ota_cancel)) }
        },
    )
}

@Composable
private fun CheckAgain(viewModel: MentraFirmwareUpdateViewModel, enabled: Boolean) {
    OutlinedButton(
        onClick = viewModel::check,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(stringResource(Res.string.mentra_ota_check_again)) }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        BodyText(text)
    }
}

@Composable
private fun Notice(text: String, warning: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        if (warning) {
            Icon(
                imageVector = AppIcon.Warning.imageVector(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TitleText(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun BodyText(text: String, muted: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}
