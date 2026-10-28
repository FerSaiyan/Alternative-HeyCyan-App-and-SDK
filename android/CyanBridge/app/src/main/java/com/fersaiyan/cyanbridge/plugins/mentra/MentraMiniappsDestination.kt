package com.fersaiyan.cyanbridge.plugins.mentra

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.plugins.PluginVoicePermissions
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappApprovals
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappException
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappPermissionPolicy
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappPermissionType
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappSource
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappStoreCatalog
import com.fersaiyan.cyanbridge.shared.plugins.mentra.MiniappStoreListing
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mentra miniapps (experimental): install from a miniapp URL or the Mentra Miniapp Store, ask
 * for permissions, start and stop background layers, and show a miniapp's UI. A route of
 * MainActivity; the background keeps running in [MentraMiniappHost] when this screen closes.
 */
class MentraMiniappsDestination : NavScreen() {
    private lateinit var store: MiniappStore
    private lateinit var installer: MiniappInstaller
    private var installed by mutableStateOf<List<InstalledMiniapp>>(emptyList())
    private var busy by mutableStateOf(false)
    private var showStore by mutableStateOf(false)
    private var offer by mutableStateOf<MiniappOffer?>(null)
    private var consent by mutableStateOf<ConsentRequest?>(null)
    private var removal by mutableStateOf<InstalledMiniapp?>(null)
    private var openUi by mutableStateOf<String?>(null)
    private var storeApps by mutableStateOf<List<MiniappStoreListing>>(emptyList())
    private var storePage by mutableStateOf(0)
    private var storeHasMore by mutableStateOf(false)
    private var storeError by mutableStateOf<String?>(null)
    private val icons = mutableStateMapOf<String, ImageBitmap>()

    /** The permission dialog for [app]; [then] runs with the saved approvals when the user allows. */
    private class ConsentRequest(val app: InstalledMiniapp, val initial: MiniappApprovals, val then: ((MiniappApprovals) -> Unit)?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = MiniappStore(this)
        installer = MiniappInstaller(store)
        refresh()
        setContent { Root() }
    }

    private fun refresh() {
        lifecycleScope.launch { installed = withContext(Dispatchers.IO) { store.list() } }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun failed(error: Throwable) {
        if (error is CancellationException) throw error
        Log.w(TAG, "Miniapp action failed", error)
        toast(if (error is MiniappException) error.message.orEmpty() else error.message ?: error.javaClass.simpleName)
    }

    /** Runs [block] with the busy indicator, reporting failures. */
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                block()
            } catch (error: Throwable) {
                failed(error)
            } finally {
                busy = false
            }
        }
    }

    // region actions

    private fun fetchOffer(url: String) = work { offer = installer.fetchOffer(url) }

    private fun install(target: MiniappOffer) {
        offer = null
        work {
            val app = installer.install(target)
            toast(getString(R.string.mentra_miniapps_installed_toast, app.manifest.name))
            // A running copy keeps the old code until it restarts.
            if (MentraMiniappHost.running.value.containsKey(app.manifest.packageName)) MentraMiniappHost.stop(this, app.manifest.packageName)
            installed = withContext(Dispatchers.IO) { store.list() }
        }
    }

    private fun installListing(listing: MiniappStoreListing) = work {
        val folder = installer.folderForListing(listing)
        offer = installer.fetchOffer(folder)
    }

    private fun checkUpdate(app: InstalledMiniapp) = work {
        val update = installer.checkUpdate(app)
        if (update == null) toast(getString(R.string.mentra_miniapps_up_to_date, app.manifest.name)) else offer = update
    }

    private fun loadStore(page: Int, query: String) = work {
        storeError = null
        try {
            val result = installer.storePage(page, query)
            storeApps = if (page == 1) result.apps else storeApps + result.apps
            storePage = result.page
            storeHasMore = result.hasMore
            result.apps.forEach { listing ->
                val url = listing.iconUrl ?: return@forEach
                if (listing.packageName in icons) return@forEach
                lifecycleScope.launch {
                    val bytes = installer.icon(url) ?: return@launch
                    withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                        ?.let { icons[listing.packageName] = it }
                }
            }
        } catch (error: MiniappException) {
            storeError = error.message
        }
    }

    /** Starts [app] (after asking for permissions when a choice is missing) and opens its UI. */
    private fun open(app: InstalledMiniapp) {
        if (!PluginPrefs.Community.mentraMiniappsEnabled[this]) {
            toast(getString(R.string.mentra_miniapps_disabled))
            return
        }
        if (MentraMiniappHost.running.value.containsKey(app.manifest.packageName)) {
            openUi = app.manifest.packageName
            return
        }
        val approvals = store.approvals(app)
        if (MiniappPermissionPolicy.needsConsent(app.manifest, approvals)) {
            consent = ConsentRequest(app, approvals) { granted -> start(app, granted) }
        } else {
            start(app, approvals)
        }
    }

    private fun start(app: InstalledMiniapp, approvals: MiniappApprovals) {
        val startNow = {
            work {
                MentraMiniappHost.start(this, app, approvals, store)
                openUi = app.manifest.packageName
            }
        }
        // Speech recognition needs the OS microphone (and, for its service, notification) permission.
        if (approvals.has(MiniappPermissionType.MICROPHONE) && !PluginVoicePermissions.hasRequiredPermissions(this)) {
            PluginVoicePermissions.request(activity) { startNow() }
        } else {
            startNow()
        }
    }

    private fun remove(app: InstalledMiniapp) {
        removal = null
        MentraMiniappHost.stop(this, app.manifest.packageName)
        work {
            withContext(Dispatchers.IO) { store.remove(app) }
            installed = withContext(Dispatchers.IO) { store.list() }
        }
    }

    // endregion

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Root() {
        val running by MentraMiniappHost.running.collectAsState()
        val uiApp = openUi?.let { running[it] }
        BackHandler(enabled = uiApp != null || showStore) {
            if (uiApp != null) openUi = null else showStore = false
        }
        when {
            uiApp != null -> MiniappScreen(uiApp, onClose = { openUi = null })
            showStore -> StoreScreen()
            else -> ListScreen(running.keys)
        }
        offer?.let { OfferDialog(it) }
        consent?.let { ConsentDialog(it) }
        removal?.let { app ->
            AlertDialog(
                onDismissRequest = { removal = null },
                title = { Text(stringResource(R.string.mentra_miniapps_remove_title, app.manifest.name)) },
                text = { Text(stringResource(R.string.mentra_miniapps_remove_message)) },
                confirmButton = { TextButton(onClick = { remove(app) }) { Text(stringResource(R.string.mentra_miniapps_remove)) } },
                dismissButton = { TextButton(onClick = { removal = null }) { Text(stringResource(R.string.mentra_miniapps_cancel)) } },
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Bar(title: String, onBack: () -> Unit) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.mentra_miniapps_back))
                }
            },
            actions = { if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 3.dp) },
        )
    }

    @Composable
    private fun ListScreen(runningPackages: Set<String>) {
        var url by remember { mutableStateOf("") }
        Scaffold(topBar = { Bar(stringResource(R.string.mentra_miniapps_title), ::finish) }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("mentra_miniapps_list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(stringResource(R.string.mentra_miniapps_intro), style = MaterialTheme.typography.bodyMedium) }
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(stringResource(R.string.mentra_miniapps_trust_note), modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it.trim() },
                            label = { Text(stringResource(R.string.mentra_miniapps_url_label)) },
                            placeholder = { Text(stringResource(R.string.mentra_miniapps_url_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("mentra_miniapps_url"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { fetchOffer(url) }, enabled = url.startsWith("https://") && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.mentra_miniapps_install_url))
                            }
                            OutlinedButton(
                                onClick = {
                                    showStore = true
                                    if (storeApps.isEmpty()) loadStore(1, "")
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.mentra_miniapps_browse_store)) }
                        }
                    }
                }
                item { Text(stringResource(R.string.mentra_miniapps_installed), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                if (installed.isEmpty()) item { Text(stringResource(R.string.mentra_miniapps_empty), style = MaterialTheme.typography.bodyMedium) }
                items(installed, key = { it.key }) { app -> InstalledCard(app, app.manifest.packageName in runningPackages) }
            }
        }
    }

    @Composable
    private fun InstalledCard(app: InstalledMiniapp, isRunning: Boolean) {
        val icon = remember(app.key) { app.iconFile?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
        Card(
            modifier = Modifier.fillMaxWidth().testTag("mentra_miniapp_${app.manifest.packageName}"),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(icon)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.manifest.name, style = MaterialTheme.typography.titleMedium)
                        val host = app.source?.folderUrl?.let { Uri.parse(it).host }.orEmpty()
                        Text(stringResource(R.string.mentra_miniapps_version, app.manifest.version, host), style = MaterialTheme.typography.bodySmall)
                    }
                    if (isRunning) Text(stringResource(R.string.mentra_miniapps_running), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                app.manifest.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { open(app) }, enabled = !busy) { Text(stringResource(R.string.mentra_miniapps_open)) }
                    if (isRunning) {
                        OutlinedButton(onClick = { MentraMiniappHost.stop(this@MentraMiniappsDestination, app.manifest.packageName) }) {
                            Text(stringResource(R.string.mentra_miniapps_stop))
                        }
                    }
                }
                Row {
                    TextButton(onClick = { checkUpdate(app) }, enabled = !busy && app.source != null) { Text(stringResource(R.string.mentra_miniapps_update)) }
                    if (app.manifest.permissions.isNotEmpty()) {
                        TextButton(onClick = { consent = ConsentRequest(app, store.approvals(app), then = null) }) {
                            Text(stringResource(R.string.mentra_miniapps_permissions))
                        }
                    }
                    TextButton(onClick = { removal = app }) { Text(stringResource(R.string.mentra_miniapps_remove)) }
                }
            }
        }
    }

    @Composable
    private fun AppIcon(icon: ImageBitmap?) {
        Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))) {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxSize()) {}
            }
        }
    }

    @Composable
    private fun StoreScreen() {
        var query by remember { mutableStateOf("") }
        Scaffold(topBar = { Bar(stringResource(R.string.mentra_miniapps_store_title)) { showStore = false } }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("mentra_miniapps_store"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(stringResource(R.string.mentra_miniapps_store_note), style = MaterialTheme.typography.bodySmall) }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.mentra_miniapps_store_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { TextButton(onClick = { loadStore(1, query) }, enabled = !busy) { Text("OK") } },
                    )
                }
                storeError?.let { item { Text(stringResource(R.string.mentra_miniapps_store_failed, it), color = MaterialTheme.colorScheme.error) } }
                if (storeApps.isEmpty() && !busy && storeError == null) item { Text(stringResource(R.string.mentra_miniapps_store_empty)) }
                items(storeApps, key = { it.packageName }) { listing -> StoreCard(listing) }
                if (storeHasMore) item {
                    OutlinedButton(onClick = { loadStore(storePage + 1, query) }, enabled = !busy) { Text(stringResource(R.string.mentra_miniapps_store_more)) }
                }
            }
        }
    }

    @Composable
    private fun StoreCard(listing: MiniappStoreListing) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(icons[listing.packageName])
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(listing.name, style = MaterialTheme.typography.titleMedium)
                        listing.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
                Text(
                    (listOfNotNull(listing.version?.let { "v$it" }, listing.reviewTier) + listing.categories).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { installListing(listing) }, enabled = !busy) { Text(stringResource(R.string.mentra_miniapps_install)) }
                    OutlinedButton(onClick = {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MiniappStoreCatalog.listingWebUrl(listing.packageName)))) }
                    }) { Text(stringResource(R.string.mentra_miniapps_store_open_web)) }
                }
            }
        }
    }

    @Composable
    private fun OfferDialog(target: MiniappOffer) {
        val manifest = target.served.manifest
        val hardware = remember { MentraMiniappHost.currentHardware(this) }
        val missing = hardware.missing(manifest)
        AlertDialog(
            onDismissRequest = { offer = null },
            title = { Text(stringResource(R.string.mentra_miniapps_install_title, manifest.name)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.mentra_miniapps_install_from, target.folder.host, manifest.version), style = MaterialTheme.typography.bodySmall)
                    manifest.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (manifest.permissions.isEmpty()) {
                        Text(stringResource(R.string.mentra_miniapps_install_no_permissions))
                    } else {
                        Text(stringResource(R.string.mentra_miniapps_install_needs, manifest.permissions.joinToString { permissionLabel(it.type) }))
                    }
                    if (missing.isNotEmpty()) {
                        Text(
                            stringResource(R.string.mentra_miniapps_install_missing, missing.joinToString { it.name.lowercase() }),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    when (target.decision) {
                        MiniappSource.UpdateDecision.INSTALL -> target.installed?.let {
                            Text(stringResource(R.string.mentra_miniapps_install_replaces, it.manifest.version))
                        }
                        MiniappSource.UpdateDecision.SAME_VERSION -> Text(stringResource(R.string.mentra_miniapps_install_same, manifest.version))
                        MiniappSource.UpdateDecision.DOWNGRADE_REFUSED -> Unit
                    }
                    Text(stringResource(R.string.mentra_miniapps_trust_note), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { install(target) },
                    enabled = target.decision != MiniappSource.UpdateDecision.DOWNGRADE_REFUSED,
                ) { Text(stringResource(R.string.mentra_miniapps_install)) }
            },
            dismissButton = { TextButton(onClick = { offer = null }) { Text(stringResource(R.string.mentra_miniapps_cancel)) } },
        )
    }

    @Composable
    private fun ConsentDialog(request: ConsentRequest) {
        val manifest = request.app.manifest
        val choices = remember(request) {
            mutableStateMapOf<MiniappPermissionType, Boolean>().apply {
                manifest.permissions.forEach { put(it.type, request.initial.has(it.type) || it.type !in request.initial.decided) }
            }
        }
        val approvals = MiniappApprovals(granted = choices.filterValues { it }.keys, decided = manifest.permissions.map { it.type }.toSet())
        AlertDialog(
            onDismissRequest = { consent = null },
            title = { Text(stringResource(R.string.mentra_miniapps_permission_title, manifest.name)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    manifest.permissions.forEach { permission ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = choices[permission.type] == true, onCheckedChange = { choices[permission.type] = it })
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${permissionLabel(permission.type)} · " + stringResource(
                                        if (permission.required) R.string.mentra_miniapps_permission_required else R.string.mentra_miniapps_permission_optional,
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                permission.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                if (permission.type != MiniappPermissionType.MICROPHONE) {
                                    Text(stringResource(R.string.mentra_miniapps_permission_unsupported), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        consent = null
                        store.saveApprovals(request.app, approvals)
                        request.then?.let { then -> if (MiniappPermissionPolicy.canStart(manifest, approvals)) then(approvals) }
                    },
                ) { Text(stringResource(R.string.mentra_miniapps_allow)) }
            },
            dismissButton = { TextButton(onClick = { consent = null }) { Text(stringResource(R.string.mentra_miniapps_cancel)) } },
        )
    }

    @Composable
    private fun MiniappScreen(running: RunningMiniapp, onClose: () -> Unit) {
        val ready by running.ready.collectAsState()
        val logs by running.logs.collectAsState()
        var showLogs by remember { mutableStateOf(false) }
        val dark = isSystemInDarkTheme()
        Scaffold(
            topBar = {
                Bar(running.app.manifest.name, onClose)
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showLogs = !showLogs }) { Text(stringResource(R.string.mentra_miniapps_logs)) }
                    TextButton(onClick = {
                        onClose()
                        MentraMiniappHost.stop(this@MentraMiniappsDestination, running.app.manifest.packageName)
                    }) { Text(stringResource(R.string.mentra_miniapps_stop)) }
                }
                if (showLogs) {
                    Text(
                        logs.takeLast(40).joinToString("\n"),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                when {
                    !running.app.manifest.hasUi -> Text(
                        stringResource(R.string.mentra_miniapps_no_ui, running.app.manifest.name),
                        modifier = Modifier.padding(16.dp),
                    )
                    !MiniappUiWebView.isSupported -> Text(stringResource(R.string.mentra_miniapps_webview_unsupported), modifier = Modifier.padding(16.dp))
                    !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.mentra_miniapps_starting, running.app.manifest.name))
                    }
                    else -> MiniappWebView(running, dark, onClose)
                }
            }
        }
    }

    @Composable
    private fun MiniappWebView(running: RunningMiniapp, dark: Boolean, onClose: () -> Unit) {
        val ui = remember(running) {
            MiniappUiWebView(activity, running, store, dark) {
                toast(getString(R.string.mentra_miniapps_crashed))
                onClose()
            }.also { it.load() }
        }
        DisposableEffect(ui) { onDispose { ui.destroy() } }
        LaunchedEffect(ui) { ui.setForeground(true) }
        AndroidView(factory = { ui.webView }, modifier = Modifier.fillMaxSize())
    }

    private fun permissionLabel(type: MiniappPermissionType): String = type.name.lowercase().replace('_', ' ')

    private companion object {
        const val TAG = "MentraMiniapps"
    }
}
