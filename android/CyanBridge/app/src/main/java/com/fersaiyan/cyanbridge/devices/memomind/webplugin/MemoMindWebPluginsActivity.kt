package com.fersaiyan.cyanbridge.devices.memomind.webplugin

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.devices.memomind.MemoMindManager
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.MmpkgException
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.MmpkgPackage
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginApprovals
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginPolicy
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MemoMind Web plugins: install `.mmpkg` packages from a file, list, remove, choose permissions,
 * and run one plugin at a time in a sandboxed [WebPluginRuntime] on this screen.
 */
class MemoMindWebPluginsActivity : AppCompatActivity() {
    private lateinit var store: WebPluginStore
    private lateinit var manager: MemoMindManager
    private var plugins by mutableStateOf<List<InstalledWebPlugin>>(emptyList())
    private var pendingInstall by mutableStateOf<MmpkgPackage?>(null)
    private var pendingRemoval by mutableStateOf<InstalledWebPlugin?>(null)
    private var permissionRequest by mutableStateOf<PermissionRequest?>(null)
    private var runtime by mutableStateOf<WebPluginRuntime?>(null)
    private var message by mutableStateOf<String?>(null)

    /** The permission dialog for [plugin]; [launch] starts it after confirming. */
    class PermissionRequest(val plugin: InstalledWebPlugin, val current: WebPluginApprovals, val launch: Boolean)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::readPackage) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WebPluginStore(this)
        manager = MemoMindManager.getInstance(this)
        refresh()
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            val glasses by manager.state.collectAsState()
            CyanBridgeTheme(appearance) {
                val running = runtime
                BackHandler(enabled = running != null) { closeRuntime() }
                if (running != null) {
                    WebPluginRuntimeScreen(
                        runtime = running,
                        name = running.pluginName,
                        connected = glasses.isConnected,
                        webBridgeRunning = glasses.device.webBridgeRunning == true,
                        onClose = ::closeRuntime,
                    )
                } else {
                    WebPluginListScreen(
                        plugins = plugins,
                        connected = glasses.isConnected,
                        webBridgeRunning = glasses.device.webBridgeRunning == true,
                        message = message,
                        onMessageShown = { message = null },
                        onInstall = { picker.launch(arrayOf("*/*")) },
                        onOpen = ::open,
                        onPermissions = { plugin -> permissionRequest = PermissionRequest(plugin, store.approvals(plugin).first, launch = false) },
                        onRemove = { pendingRemoval = it },
                        onBack = ::finish,
                    )
                }
                pendingInstall?.let { pkg ->
                    InstallDialog(
                        pkg = pkg,
                        replacesVersion = store.installedVersionOf(pkg)?.manifest?.version,
                        onConfirm = { install(pkg) },
                        onDismiss = { pendingInstall = null },
                    )
                }
                pendingRemoval?.let { plugin ->
                    RemoveDialog(plugin = plugin, onConfirm = { remove(plugin) }, onDismiss = { pendingRemoval = null })
                }
                permissionRequest?.let { request ->
                    PermissionDialog(
                        plugin = request.plugin,
                        initial = request.current,
                        launch = request.launch,
                        onConfirm = { approvals ->
                            permissionRequest = null
                            store.saveApprovals(request.plugin, approvals)
                            if (request.launch) start(request.plugin, approvals)
                        },
                        onDismiss = { permissionRequest = null },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runtime?.let {
            manager.webPluginOwnsInput = true
            it.setForeground(true)
        }
    }

    override fun onPause() {
        runtime?.setForeground(false)
        manager.webPluginOwnsInput = false
        super.onPause()
    }

    override fun onDestroy() {
        runtime?.destroy()
        runtime = null
        manager.webPluginOwnsInput = false
        super.onDestroy()
    }

    private fun refresh() {
        plugins = store.list()
    }

    private fun readPackage(uri: Uri) {
        lifecycleScope.launch {
            try {
                pendingInstall = withContext(Dispatchers.IO) { store.read(this@MemoMindWebPluginsActivity, uri) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: MmpkgException) {
                message = getString(R.string.memomind_webplugins_invalid, error.message)
            } catch (error: Exception) {
                Log.w(TAG, "Cannot read the package", error)
                message = getString(R.string.memomind_webplugins_read_failed)
            }
        }
    }

    private fun install(pkg: MmpkgPackage) {
        pendingInstall = null
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.install(pkg) } }
                .onSuccess { message = getString(R.string.memomind_webplugins_installed, pkg.manifest.name) }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "Install failed", it)
                    message = getString(R.string.memomind_webplugins_invalid, it.message)
                }
            refresh()
        }
    }

    private fun remove(plugin: InstalledWebPlugin) {
        pendingRemoval = null
        store.remove(plugin)
        refresh()
    }

    /** Opens [plugin], asking for permissions first when a choice is missing or a required one is denied. */
    private fun open(plugin: InstalledWebPlugin) {
        if (!WebPluginRuntime.isSupported) {
            message = getString(R.string.memomind_webplugins_webview_unsupported)
            return
        }
        val (approvals, decided) = store.approvals(plugin)
        val undecided = plugin.manifest.permissions.any { it.name !in decided }
        if (undecided || !WebPluginPolicy.canStart(plugin.manifest.permissions, approvals)) {
            permissionRequest = PermissionRequest(plugin, approvals, launch = true)
        } else {
            start(plugin, approvals)
        }
    }

    private fun start(plugin: InstalledWebPlugin, approvals: WebPluginApprovals) {
        if (!WebPluginPolicy.canStart(plugin.manifest.permissions, approvals)) return
        runtime?.destroy()
        val created = WebPluginRuntime(this, plugin, approvals, store, manager, lifecycleScope) {
            runtime = null
            manager.webPluginOwnsInput = false
            message = getString(R.string.memomind_webplugins_crashed)
        }
        runtime = created
        manager.webPluginOwnsInput = true
        created.load()
    }

    private fun closeRuntime() {
        runtime?.destroy()
        runtime = null
        manager.webPluginOwnsInput = false
    }

    private companion object {
        const val TAG = "MemoMindWebPlugins"
    }
}
