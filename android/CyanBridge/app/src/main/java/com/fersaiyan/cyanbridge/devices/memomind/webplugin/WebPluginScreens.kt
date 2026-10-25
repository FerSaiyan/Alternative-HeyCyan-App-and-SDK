package com.fersaiyan.cyanbridge.devices.memomind.webplugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.MmpkgManifest
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.MmpkgPackage
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginApprovals
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginContract.Permission
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginPermission
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginPolicy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebPluginListScreen(
    plugins: List<InstalledWebPlugin>,
    connected: Boolean,
    webBridgeRunning: Boolean,
    message: String?,
    onMessageShown: () -> Unit,
    onInstall: () -> Unit,
    onOpen: (InstalledWebPlugin) -> Unit,
    onPermissions: (InstalledWebPlugin) -> Unit,
    onRemove: (InstalledWebPlugin) -> Unit,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onMessageShown()
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.memomind_webplugins_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { GlassesStatus(connected, webBridgeRunning) }
            item {
                Text(
                    stringResource(R.string.memomind_webplugins_trust_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                FilledTonalButton(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.memomind_webplugins_install))
                }
            }
            if (plugins.isEmpty()) {
                item { Text(stringResource(R.string.memomind_webplugins_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(plugins, key = { it.key }) { plugin -> PluginCard(plugin, onOpen, onPermissions, onRemove) }
        }
    }
}

@Composable
private fun GlassesStatus(connected: Boolean, webBridgeRunning: Boolean) {
    val (text, warning) = when {
        !connected -> stringResource(R.string.memomind_webplugins_not_connected) to true
        !webBridgeRunning -> stringResource(R.string.memomind_webplugins_bridge_missing) to true
        else -> stringResource(R.string.memomind_webplugins_bridge_running) to false
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun PluginCard(
    plugin: InstalledWebPlugin,
    onOpen: (InstalledWebPlugin) -> Unit,
    onPermissions: (InstalledWebPlugin) -> Unit,
    onRemove: (InstalledWebPlugin) -> Unit,
) {
    val manifest = plugin.manifest
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(manifest.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.memomind_webplugins_version, manifest.version, manifest.id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            glassesPluginNeeded(manifest)?.let { pluginId ->
                Text(stringResource(R.string.memomind_webplugins_needs_glasses_plugin, pluginId), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onOpen(plugin) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.memomind_webplugins_open))
                }
                OutlinedButton(onClick = { onPermissions(plugin) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.memomind_webplugins_permissions))
                }
                OutlinedButton(onClick = { onRemove(plugin) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.memomind_webplugins_remove))
                }
            }
        }
    }
}

/** The glasses plugin a package pairs with when the stock Web Bridge does not cover it. */
private fun glassesPluginNeeded(manifest: MmpkgManifest): String? {
    val requirements = manifest.deviceRequirements ?: return null
    if (requirements.webBridgeCompatible) return null
    return requirements.requiredPluginId ?: requirements.preferredPluginId ?: requirements.protocols.joinToString { it.id }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebPluginRuntimeScreen(
    runtime: WebPluginRuntime,
    name: String,
    connected: Boolean,
    webBridgeRunning: Boolean,
    onClose: () -> Unit,
) {
    val microphone by runtime.host.microphoneActive.collectAsState()
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.memomind_webplugins_close))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (microphone) {
                Text(
                    stringResource(R.string.memomind_webplugins_microphone_active, name),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!connected || !webBridgeRunning) {
                Text(
                    stringResource(if (!connected) R.string.memomind_webplugins_not_connected else R.string.memomind_webplugins_bridge_missing),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 96.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            AndroidView(factory = { runtime.webView }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
internal fun InstallDialog(pkg: MmpkgPackage, replacesVersion: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val manifest = pkg.manifest
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memomind_webplugins_install_title, manifest.name)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.memomind_webplugins_install_details, manifest.id, manifest.version, formatBytes(pkg.payloadBytes)))
                replacesVersion?.let { Text(stringResource(R.string.memomind_webplugins_install_replaces, it)) }
                glassesPluginNeeded(manifest)?.let { Text(stringResource(R.string.memomind_webplugins_needs_glasses_plugin, it)) }
                if (manifest.permissions.isNotEmpty()) {
                    Text(stringResource(R.string.memomind_webplugins_install_permissions), style = MaterialTheme.typography.titleSmall)
                    manifest.permissions.forEach { PermissionText(it) }
                }
                Text(
                    stringResource(R.string.memomind_webplugins_trust_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.memomind_webplugins_install_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memomind_webplugins_cancel)) } },
    )
}

@Composable
internal fun RemoveDialog(plugin: InstalledWebPlugin, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memomind_webplugins_remove_title, plugin.manifest.name)) },
        text = { Text(stringResource(R.string.memomind_webplugins_remove_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.memomind_webplugins_remove)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memomind_webplugins_cancel)) } },
    )
}

/** Lets the user grant each declared permission; required ones gate [onConfirm] when [launch]ing. */
@Composable
internal fun PermissionDialog(
    plugin: InstalledWebPlugin,
    initial: WebPluginApprovals,
    launch: Boolean,
    onConfirm: (WebPluginApprovals) -> Unit,
    onDismiss: () -> Unit,
) {
    val declarations = plugin.manifest.permissions
    val checked = remember(plugin.key) {
        mutableStateMapOf<String, Boolean>().apply { declarations.forEach { put(it.name, initial.has(it.name)) } }
    }
    val approvals = WebPluginApprovals(declarations.filter { checked[it.name] == true }.associate { it.name to it.scope.orEmpty() })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memomind_webplugins_permission_title, plugin.manifest.name)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.memomind_webplugins_permission_hint), style = MaterialTheme.typography.bodySmall)
                declarations.forEach { permission ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked[permission.name] == true, onCheckedChange = { checked[permission.name] = it })
                        Column(modifier = Modifier.weight(1f)) { PermissionText(permission) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(approvals) }, enabled = !launch || WebPluginPolicy.canStart(declarations, approvals)) {
                Text(stringResource(if (launch) R.string.memomind_webplugins_start else R.string.memomind_webplugins_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memomind_webplugins_cancel)) } },
    )
}

@Composable
private fun PermissionText(permission: WebPluginPermission) {
    val requirement = stringResource(if (permission.required) R.string.memomind_webplugins_required else R.string.memomind_webplugins_optional)
    Text("${stringResource(permissionLabel(permission.name))} · $requirement", style = MaterialTheme.typography.bodyMedium)
    permission.scope?.let { scope ->
        val label = if (permission.name == Permission.DEVICE_EVENTS) {
            R.string.memomind_webplugins_scope_events
        } else {
            R.string.memomind_webplugins_scope_channels
        }
        Text(stringResource(label, scope.joinToString()), style = MaterialTheme.typography.bodySmall)
    }
    permission.reason?.let {
        Text("“${it.take(200)}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun permissionLabel(name: String): Int = when (name) {
    Permission.STORAGE -> R.string.memomind_webplugins_perm_storage
    Permission.FILES -> R.string.memomind_webplugins_perm_files
    Permission.DISPLAY -> R.string.memomind_webplugins_perm_display
    Permission.DEVICE_INFO -> R.string.memomind_webplugins_perm_device_info
    Permission.DEVICE_EVENTS -> R.string.memomind_webplugins_perm_device_events
    Permission.DEVICE_MESSAGING -> R.string.memomind_webplugins_perm_device_messaging
    Permission.AUDIO_CAPTURE -> R.string.memomind_webplugins_perm_audio_capture
    Permission.AUDIO_PLAYBACK -> R.string.memomind_webplugins_perm_audio_playback
    Permission.NETWORK -> R.string.memomind_webplugins_perm_network
    else -> R.string.memomind_webplugins_perm_location
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
