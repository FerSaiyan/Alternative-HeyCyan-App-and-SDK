package com.fersaiyan.cyanbridge.plugins.walkingaid

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.ProSubscriptionDestination
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.metarayban.MetaDatPermissions
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidModelDownload
import com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidModelOption
import com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidSettingsViewModel
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs
import com.fersaiyan.cyanbridge.shared.prefs.store
import com.fersaiyan.cyanbridge.ui.MetaPairingActivity
import com.fersaiyan.cyanbridge.ui.NativePluginShortcutPreference
import com.fersaiyan.cyanbridge.ui.ensureNotificationPermission
import com.fersaiyan.cyanbridge.ui.hasBluetooth
import com.fersaiyan.cyanbridge.ui.hasNotificationPermission
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import com.fersaiyan.cyanbridge.ui.requestBluetoothPermission
import com.fersaiyan.cyanbridge.ui.requestWifiP2pPermission
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions

class WalkingAidSettingsDestination : NavScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WalkingAidSettingsScreen(
                onBack = ::finish,
                onStartService = ::startWithPermissions,
                onStopService = { WalkingAidService.stop(this) },
            )
        }
    }

    private fun startWithPermissions() {
        if (!hasBluetooth(this)) {
            requestTransportPermission(wifi = false)
            return
        }
        if (WalkingAidPreferences.getVideoMode(this) in setOf(
                WalkingAidVideoMode.EYEVUE_VIDEO,
                WalkingAidVideoMode.HEYCYAN_VIDEO,
                WalkingAidVideoMode.MENTRA_VIDEO,
            ) && !hasWifiP2pPermission(this)
        ) {
            requestTransportPermission(wifi = true)
            return
        }
        if (!hasNotificationPermission(this)) {
            WalkingAidPreferences.setEnabled(this, false)
            ensureNotificationPermission(activity, "Walking Aid") { startWithPermissions() }
            return
        }
        if (DeviceProfileStore.isMetaSelected(this)) {
            val manager = MetaRaybanManager.getInstance(this)
            if (MetaDatPermissions.missing(this).isNotEmpty() || !manager.isCameraReady() || !manager.cameraPermissionGranted.value) {
                WalkingAidPreferences.setEnabled(this, false)
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.walking_aid_meta_setup_title)
                    .setMessage(R.string.walking_aid_meta_setup_message)
                    .setPositiveButton(R.string.walking_aid_open_meta_setup) { _, _ -> startActivity(Intent(this, MetaPairingActivity::class.java)) }
                    .setNegativeButton(android.R.string.cancel, null).show()
                return
            }
        }
        WalkingAidService.start(this)
    }

    private fun requestTransportPermission(wifi: Boolean) {
        WalkingAidPreferences.setEnabled(this, false)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (wifi) R.string.walking_aid_wifi_permission_title else R.string.walking_aid_bluetooth_permission_title)
            .setMessage(if (wifi) R.string.walking_aid_wifi_permission_message else R.string.walking_aid_bluetooth_permission_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.walking_aid_grant_permission) { _, _ ->
                val callback = object : OnPermissionCallback {
                    override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                        if (all) startWithPermissions() else onDenied(permissions, false)
                    }
                    override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                        androidx.appcompat.app.AlertDialog.Builder(this@WalkingAidSettingsDestination)
                            .setTitle(R.string.walking_aid_permission_required)
                            .setMessage(R.string.walking_aid_permission_required_message)
                            .setPositiveButton(if (never) R.string.walking_aid_open_app_settings else R.string.walking_aid_try_again) { _, _ ->
                                if (never) XXPermissions.startPermissionActivity(this@WalkingAidSettingsDestination, permissions)
                                else requestTransportPermission(wifi)
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                }
                if (wifi) requestWifiP2pPermission(activity, callback) else requestBluetoothPermission(activity, callback)
            }.show()
    }
}

/** The screen's ViewModel, kept by the hosting Activity (or navigation entry) across recreation. */
@Composable
internal fun walkingAidSettingsViewModel(): WalkingAidSettingsViewModel {
    val context = LocalContext.current.applicationContext
    return viewModel {
        WalkingAidSettingsViewModel(
            store = PluginPrefs.WalkingAid.store(context),
            catalog = PluginPrefs.Community.store(context),
            platform = AndroidWalkingAidSettingsPlatform(context),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkingAidSettingsScreen(
    onBack: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    viewModel: WalkingAidSettingsViewModel = walkingAidSettingsViewModel(),
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val vm = viewModel
    val hasCamera = vm.hasCamera
    val cameraUnavailableReason = vm.cameraUnavailableReason
    val enabled = vm.enabled
    val running = vm.running
    val videoMode = vm.videoMode
    val aidMode = vm.aidMode
    val selectedDevice = DeviceProfileStore.selectedClass(context)
    val serviceFailure by WalkingAidService.lastFailure.collectAsState()
    serviceFailure?.let { message ->
        AlertDialog(
            onDismissRequest = WalkingAidService::dismissFailure,
            title = { Text(stringResource(R.string.walking_aid_stopped)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = WalkingAidService::dismissFailure) { Text(stringResource(android.R.string.ok)) } },
        )
    }
    LaunchedEffect(Unit) { vm.followService() }
    val focusDescriptionText = vm.focusDescriptionText
    val savedFocusOriginal = vm.savedFocusOriginal
    val focusTranslationSource = vm.focusTranslationSource

    val intervalOptions = listOf(2, 3, 5, 10, 15, 30)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.walking_aid_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.compose_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasCamera && cameraUnavailableReason != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = stringResource(R.string.compose_camera_warning),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.compose_camera_hardware_required),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = stringResource(R.string.compose_walking_camera_description, cameraUnavailableReason),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            // Section: General
            SectionTitle(stringResource(R.string.walking_aid_general))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Enable switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.compose_walking_enabled), modifier = Modifier.weight(1f))
                        Switch(
                            enabled = hasCamera,
                            checked = enabled && hasCamera,
                            onCheckedChange = { vm.onEnabledChange(it, onStartService, onStopService) },
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(stringResource(R.string.walking_aid_mode_title), fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WalkingAidMode.entries.forEach { mode ->
                            FilterChip(
                                selected = aidMode == mode,
                                enabled = !enabled && !running,
                                onClick = { vm.onAidModeSelected(mode) },
                                label = { Text(stringResource(if (mode == WalkingAidMode.EXPLORE)
                                    R.string.walking_aid_mode_explore else R.string.walking_aid_mode_walking)) },
                            )
                        }
                    }
                    Text(
                        stringResource(if (aidMode == WalkingAidMode.EXPLORE)
                            R.string.walking_aid_mode_explore_description else R.string.walking_aid_mode_walking_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    WalkingAidVideoSourceButtons(
                        selected = videoMode,
                        deviceClass = selectedDevice,
                        sdk = Build.VERSION.SDK_INT,
                        enabled = !enabled && !running,
                        onSelected = vm::onVideoModeSelected,
                    )
                    Text(
                        stringResource(R.string.walking_aid_video_source_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Capture interval
                    Text(
                        stringResource(R.string.walking_aid_capture_interval),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        intervalOptions.forEach { option ->
                            FilterChip(
                                selected = vm.captureInterval == option,
                                enabled = videoMode == WalkingAidVideoMode.PERIODIC_PHOTOS,
                                onClick = { vm.onCaptureIntervalSelected(option) },
                                label = { Text(stringResource(R.string.compose_walking_seconds, option)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Latency Test Button
                    Button(
                        onClick = vm::onRunLatencyBenchmark,
                        enabled = !vm.isTestingLatency && !running && videoMode == WalkingAidVideoMode.PERIODIC_PHOTOS,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (vm.isTestingLatency) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp).width(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.compose_walking_benchmark_running))
                        } else {
                            Text(stringResource(R.string.compose_walking_latency_benchmark))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Acquisition Benchmark Button
                    Button(
                        onClick = vm::onRunAcquisitionBenchmark,
                        enabled = !vm.isTestingAcquisition && hasCamera && !running && videoMode == WalkingAidVideoMode.PERIODIC_PHOTOS,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (vm.isTestingAcquisition) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp).width(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.compose_walking_acquisition_running))
                        } else {
                            Text(stringResource(R.string.compose_walking_benchmark_quality))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        stringResource(R.string.compose_walking_thumbnail_quality),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.compose_walking_quality_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        (0..5).forEach { level ->
                            FilterChip(
                                selected = vm.thumbnailQuality == level,
                                onClick = { vm.onThumbnailQualitySelected(level) },
                                label = { Text("$level") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        stringResource(R.string.compose_walking_photo_settle_delay, vm.settleDelayMs),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.compose_walking_photo_settle_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Slider(
                        value = vm.settleDelayMs.toFloat(),
                        onValueChange = { vm.onSettleDelayChange(it.toInt()) },
                        valueRange = 0f..500f,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SectionTitle(stringResource(R.string.compose_glasses_tab))
            NativePluginShortcutPreference(
                pluginId = NativePluginIds.WALKING_AID,
                pluginTitle = stringResource(R.string.walking_aid_title),
            )

            // Section: Image Recognition
            SectionTitle(stringResource(R.string.walking_aid_image_recognition))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (aidMode == WalkingAidMode.WALKING) {
                        Text(
                            stringResource(R.string.compose_walking_safety_context),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = vm.imageDescriptionSource == "local",
                                onClick = { vm.onImageDescriptionSourceSelected("local") },
                                label = { Text(stringResource(R.string.compose_walking_local_safety)) },
                            )
                            FilterChip(
                                selected = vm.imageDescriptionSource == "cloud",
                                onClick = { vm.onImageDescriptionSourceSelected("cloud") },
                                label = { Text(stringResource(R.string.compose_walking_cloud_context)) },
                            )
                        }

                        Text(
                            stringResource(R.string.compose_walking_cloud_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (vm.imageDescriptionSource == "cloud") {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = vm::openImageModelPicker) {
                                Text(
                                    text = if (vm.imageDescriptionCloudModelId.isNotBlank())
                                        "Model: ${vm.imageDescriptionCloudModelId}"
                                    else
                                        stringResource(R.string.compose_walking_select_model),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.compose_walking_detection_model),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = vm.yoloModelType == WalkingAidPreferences.MODEL_TYPE_YOLO11,
                            onClick = { vm.onYoloModelTypeSelected(WalkingAidPreferences.MODEL_TYPE_YOLO11) },
                            label = { Text(stringResource(R.string.compose_walking_yolo_fast)) },
                        )
                        FilterChip(
                            selected = vm.yoloModelType == WalkingAidPreferences.MODEL_TYPE_YOLO_WORLD,
                            onClick = { vm.onYoloModelTypeSelected(WalkingAidPreferences.MODEL_TYPE_YOLO_WORLD) },
                            label = { Text(stringResource(R.string.compose_walking_yolo_larger)) },
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    WalkingAidModelDownloadCard(vm, WalkingAidModelCatalog.detectorFor(vm.yoloModelType))

                    if (aidMode == WalkingAidMode.EXPLORE) return@Column

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.compose_walking_attention),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TextField(
                        value = focusDescriptionText,
                        onValueChange = vm::onFocusTextChange,
                        placeholder = { Text(stringResource(R.string.compose_walking_attention_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        supportingText = {
                            val understood = WalkingAidFocusMapper.resolve(vm.understoodFocusText)
                            Text(
                                if (vm.focusUnsaved) {
                                    stringResource(R.string.compose_walking_attention_unsaved)
                                } else if (understood.isEmpty()) {
                                    stringResource(R.string.compose_walking_attention_support)
                                } else {
                                    stringResource(
                                        R.string.compose_walking_attention_summary,
                                        WalkingAidFocusMapper.friendlySummary(understood),
                                    )
                                }
                            )
                        },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.compose_walking_translation_source),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = focusTranslationSource == "local",
                            onClick = { vm.onFocusTranslationSourceSelected("local") },
                            label = { Text(stringResource(R.string.compose_walking_translation_local)) },
                        )
                        FilterChip(
                            selected = focusTranslationSource == "cloud",
                            onClick = { vm.onFocusTranslationSourceSelected("cloud") },
                            label = { Text(stringResource(R.string.compose_walking_translation_cloud)) },
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        enabled = !vm.isTranslatingFocus,
                        onClick = vm::onTranslateAndSaveFocus,
                    ) {
                        if (vm.isTranslatingFocus) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(18.dp).height(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.compose_walking_translate_save))
                    }
                    vm.focusTranslationError?.let { error ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.compose_walking_translation_failed, error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (savedFocusOriginal.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.compose_walking_original_input, savedFocusOriginal),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(R.string.compose_walking_model_input, vm.savedModelFocusText),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // Section: Depth Estimation
            if (aidMode == WalkingAidMode.WALKING) SectionTitle(stringResource(R.string.walking_aid_depth_estimation))
            if (aidMode == WalkingAidMode.WALKING) Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.compose_walking_depth_enabled), modifier = Modifier.weight(1f))
                        Switch(
                            checked = vm.depthEnabled,
                            onCheckedChange = vm::onDepthEnabledChange,
                        )
                    }

                    if (vm.depthEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.compose_walking_source),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = vm.depthSource == "local",
                                onClick = { vm.onDepthSourceSelected("local") },
                                label = { Text(stringResource(R.string.walking_aid_source_local)) },
                            )
                            FilterChip(
                                selected = vm.depthSource == "cloud",
                                onClick = { vm.onDepthSourceSelected("cloud") },
                                label = { Text(stringResource(R.string.walking_aid_source_cloud)) },
                            )
                        }

                        if (vm.depthSource == "cloud") {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = vm::openDepthModelPicker) {
                                Text(
                                    text = if (vm.depthCloudModelId.isNotBlank())
                                        "Model: ${vm.depthCloudModelId}"
                                    else
                                        stringResource(R.string.compose_walking_select_model),
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.height(12.dp))
                            WalkingAidModelDownloadCard(vm, WalkingAidModelCatalog.depth)
                        }
                    }
                }
            }

            // Section: Output
            SectionTitle(stringResource(R.string.walking_aid_output))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.walking_aid_tts_enabled), modifier = Modifier.weight(1f))
                        Switch(
                            checked = vm.ttsEnabled,
                            onCheckedChange = vm::onTtsEnabledChange,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val speechRateLabel = stringResource(R.string.walking_aid_speech_rate)
                    val speechRateValue = stringResource(R.string.walking_aid_speech_rate_value, vm.speechRate)
                    Text(speechRateLabel)
                    Text(speechRateValue)
                    Slider(
                        modifier = Modifier.semantics {
                            contentDescription = speechRateLabel
                            stateDescription = speechRateValue
                        },
                        value = vm.speechRate,
                        onValueChange = vm::onSpeechRateChange,
                        valueRange = 0.5f..2f,
                        steps = 14,
                    )
                    if (aidMode == WalkingAidMode.EXPLORE) {
                        val pauseLabel = stringResource(R.string.walking_aid_announcement_pause)
                        val pauseValue = stringResource(R.string.walking_aid_announcement_pause_value, vm.announcementPause)
                        Text(pauseLabel)
                        Text(pauseValue)
                        Slider(
                            modifier = Modifier.semantics {
                                contentDescription = pauseLabel
                                stateDescription = pauseValue
                            },
                            value = vm.announcementPause,
                            onValueChange = vm::onAnnouncementPauseChange,
                            valueRange = 0f..5f,
                            steps = 9,
                        )
                    }

                    if (aidMode == WalkingAidMode.WALKING) Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.walking_aid_safety_disclaimer), modifier = Modifier.weight(1f))
                        Switch(
                            checked = vm.safetyDisclaimerEnabled,
                            onCheckedChange = vm::onSafetyDisclaimerChange,
                        )
                    }
                }
            }

            // Section: Custom Instructions
            if (aidMode == WalkingAidMode.WALKING) SectionTitle(stringResource(R.string.compose_custom_instructions))
            if (aidMode == WalkingAidMode.WALKING) Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_walking_additional_instructions_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = vm.customPrompt,
                        onValueChange = vm::onCustomPromptChange,
                        modifier = Modifier.fillMaxWidth(),
                         label = { Text(stringResource(R.string.compose_walking_instructions)) },
                         placeholder = { Text(stringResource(R.string.compose_walking_instructions_hint)) },
                        minLines = 3,
                        maxLines = 6,
                        supportingText = { Text("${vm.customPrompt.length}/${WalkingAidSettingsViewModel.MAX_CUSTOM_PROMPT_LENGTH}") },
                    )
                }
            }

            // Section: History
            SectionTitle(stringResource(R.string.walking_aid_history))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.compose_walking_max_descriptions, vm.historyMaxCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = vm.historyMaxCount.toFloat(),
                        onValueChange = { vm.onHistoryMaxCountChange(it.toInt()) },
                        valueRange = 10f..100f,
                        steps = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = vm::onClearHistory,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.walking_aid_clear_history))
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Model picker dialogs
    if (vm.showImageModelPicker) {
        ModelPickerDialog(
            availableModels = vm.availableModels,
            isLoading = vm.modelsLoading,
            selectedModelId = vm.imageDescriptionCloudModelId,
            onSelect = vm::onImageModelSelected,
            onDismiss = vm::dismissImageModelPicker,
            onRefresh = vm::refreshCloudModels,
        )
    }

    if (vm.showDepthModelPicker) {
        ModelPickerDialog(
            availableModels = vm.availableModels,
            isLoading = vm.modelsLoading,
            selectedModelId = vm.depthCloudModelId,
            onSelect = vm::onDepthModelSelected,
            onDismiss = vm::dismissDepthModelPicker,
            onRefresh = vm::refreshCloudModels,
        )
    }

    vm.latencyReport?.let { report ->
        AlertDialog(
            onDismissRequest = vm::dismissLatencyReport,
            title = { Text(stringResource(R.string.compose_walking_latency_title)) },
            text = {
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = vm::dismissLatencyReport) {
                    Text(stringResource(R.string.compose_walking_cancel))
                }
            }
        )
    }

    vm.acquisitionReport?.let { report ->
        AlertDialog(
            onDismissRequest = vm::dismissAcquisitionReport,
            title = { Text(stringResource(R.string.compose_walking_acquisition_title)) },
            text = {
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = vm::dismissAcquisitionReport) {
                    Text(stringResource(R.string.compose_walking_cancel))
                }
            }
        )
    }

    vm.readiness?.let { readiness ->
        AlertDialog(
            onDismissRequest = vm::dismissReadiness,
            title = { Text(stringResource(R.string.compose_walking_setup_required)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.compose_walking_setup_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    readiness.missingDetails.forEach { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = vm::dismissReadiness) {
                    Text(stringResource(R.string.compose_walking_review_settings))
                }
            },
            dismissButton = {
                if (readiness.requiresProForCloud) {
                    TextButton(
                        onClick = {
                            vm.dismissReadiness()
                            val intent = AppRoute.PRO_SUBSCRIPTION.intent(context)
                            context.startActivity(intent)
                        }
                    ) {
                        Text(stringResource(R.string.compose_walking_upgrade_pro))
                    }
                } else {
                    TextButton(onClick = vm::dismissReadiness) {
                        Text(stringResource(R.string.compose_walking_cancel))
                    }
                }
            }
        )
    }
}

@Composable
private fun WalkingAidModelDownloadCard(vm: WalkingAidSettingsViewModel, entry: WalkingAidModelCatalogEntry) {
    val context = LocalContext.current
    val installed = WalkingAidModelInstaller.isInstalled(context, entry)
    val isDownloading = vm.downloadingModelId == entry.id
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(entry.displayName, fontWeight = FontWeight.SemiBold)
            Text(entry.description, style = MaterialTheme.typography.bodySmall)
            Text(
                "${entry.sizeBytes / (1024 * 1024)} MB · ${entry.licenseNote}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isDownloading) {
                Text(modelDownloadStatus(vm.modelDownload), style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { vm.onDownloadModel(entry.id, entry.displayName) },
                enabled = !installed && !isDownloading,
            ) {
                Text(
                    when {
                        installed -> stringResource(R.string.compose_walking_model_installed)
                        isDownloading -> stringResource(R.string.compose_walking_model_downloading_button)
                        else -> stringResource(R.string.compose_walking_model_download_huggingface)
                    },
                )
            }
        }
    }
}

@Composable
private fun modelDownloadStatus(download: WalkingAidModelDownload?): String = when (download) {
    null -> ""
    is WalkingAidModelDownload.Starting -> stringResource(R.string.compose_walking_model_starting, download.modelName)
    is WalkingAidModelDownload.Downloading -> stringResource(R.string.compose_walking_model_downloading, download.percent)
    is WalkingAidModelDownload.Ready -> stringResource(R.string.compose_walking_model_ready, download.modelName)
    is WalkingAidModelDownload.Failed -> stringResource(R.string.compose_walking_model_download_failed, download.message)
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun ModelPickerDialog(
    availableModels: List<WalkingAidModelOption>,
    isLoading: Boolean,
    selectedModelId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (availableModels.isEmpty()) {
            onRefresh()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compose_walking_select_model)) },
        text = {
            Column {
                if (isLoading) {
                    Text(stringResource(R.string.compose_walking_loading_models))
                } else if (availableModels.isEmpty()) {
                    Text(stringResource(R.string.compose_walking_no_models))
                } else {
                    Text(
                        stringResource(R.string.compose_walking_current_model, selectedModelId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    LazyColumn(
                        modifier = Modifier.height(300.dp),
                    ) {
                        items(availableModels) { option ->
                            TextButton(
                                onClick = {
                                    onSelect(option.id)
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column {
                                    Text(
                                        text = option.label.ifBlank { option.id },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (option.id == selectedModelId)
                                            androidx.compose.ui.text.font.FontWeight.Bold
                                        else
                                            androidx.compose.ui.text.font.FontWeight.Normal,
                                    )
                                    Text(
                                        text = option.id,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.compose_done))
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.compose_refresh))
                }
            }
        },
    )
}
