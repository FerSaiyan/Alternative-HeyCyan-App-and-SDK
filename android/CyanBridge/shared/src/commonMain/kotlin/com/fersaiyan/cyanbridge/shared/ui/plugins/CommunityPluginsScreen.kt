package com.fersaiyan.cyanbridge.shared.ui.plugins

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import com.fersaiyan.cyanbridge.shared.icons.imageVector
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.navigation.icon
import com.fersaiyan.cyanbridge.shared.plugins.CommunityPluginCardData
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginCardData
import com.fersaiyan.cyanbridge.shared.plugins.PluginTimeWindow
import com.fersaiyan.cyanbridge.shared.ui.localizedDestinationLabel
import kotlin.math.floor
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun CommunityPluginsScreen(
    plugins: List<CommunityPluginCardData>,
    selectedWindow: PluginTimeWindow,
    isRefreshing: Boolean,
    onWindowSelected: (PluginTimeWindow) -> Unit,
    onRefresh: () -> Unit,
    onOpenCommunityPlugin: (CommunityPluginCardData) -> Unit = {},
    onPublishPlugin: () -> Unit,
    onDestinationSelected: (AppDestination) -> Unit,
    nativePlugins: List<NativePluginCardData> = emptyList(),
    onOpenNativePluginSettings: (String) -> Unit = {},
    onToggleNativePlugin: (String, Boolean) -> Unit = { _, _ -> },
    taskerIntegrations: List<CommunityPluginCardData> = emptyList(),
    onDownloadTaskerIntegration: (CommunityPluginCardData) -> Unit = {},
    onOpenTaskerIntegrationSettings: (String) -> Unit = {},
) {
    var selectedPluginTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsPluginTitle by rememberSaveable { mutableStateOf<String?>(null) }
    val trending = plugins.sortedByDescending { it.trend(selectedWindow) }.take(4)
    val topVoted = plugins.sortedByDescending { it.votes(selectedWindow) }.take(4)
    val topDownloaded = plugins.sortedByDescending { it.downloads(selectedWindow) }.take(4)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.plugins_title)) },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(Res.string.plugins_refresh))
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == AppDestination.PLUGINS,
                        onClick = { onDestinationSelected(destination) },
                        icon = {
                            Icon(
                                imageVector = destination.icon.imageVector(),
                                contentDescription = null,
                            )
                        },
                        label = { Text(localizedDestinationLabel(destination)) },
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onPublishPlugin) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(Res.string.plugins_publish))
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .testTag("community_plugins_list"),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PluginHero() }
            item {
                Text(
                    text = stringResource(Res.string.plugins_future_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (taskerIntegrations.isNotEmpty()) {
                item { PluginSectionLabel("Tasker integrations") }
                item {
                    Text(
                        text = "These features keep their CyanBridge settings and AI/memory logic, but require the matching Tasker profile for Android automation or scheduling.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                itemsIndexed(taskerIntegrations, key = { _, p -> "tasker-${p.id}" }) { _, plugin ->
                    TaskerIntegrationCard(
                        plugin = plugin,
                        onDownload = { onDownloadTaskerIntegration(plugin) },
                        onOpenSettings = { onOpenTaskerIntegrationSettings(plugin.id) },
                    )
                }
            }

            if (nativePlugins.isNotEmpty()) {
                item { PluginSectionLabel(stringResource(Res.string.native_plugins_section)) }
                itemsIndexed(nativePlugins, key = { _, p -> "native-${p.id}" }) { _, plugin ->
                    NativePluginCard(
                        plugin = plugin,
                        onOpenSettings = { onOpenNativePluginSettings(plugin.id) },
                        onToggle = { enabled -> onToggleNativePlugin(plugin.id, enabled) },
                    )
                }
            }

            item {
                PeriodFilter(selectedWindow = selectedWindow, onWindowSelected = onWindowSelected)
            }
            item { PluginSectionLabel(stringResource(Res.string.plugins_trending)) }
            itemsIndexed(trending, key = { _, plugin -> "trending-${plugin.title}" }) { index, plugin ->
                PluginCard(
                    plugin = plugin,
                    rank = index + 1,
                    window = selectedWindow,
                    selected = selectedPluginTitle == plugin.title,
                    onSelect = {
                        selectedPluginTitle = plugin.title
                        detailsPluginTitle = plugin.title
                    },
                    onOpenPlugin = { onOpenCommunityPlugin(plugin) },
                )
            }
            item { PluginSectionLabel(stringResource(Res.string.plugins_top_voted)) }
            itemsIndexed(topVoted, key = { _, plugin -> "voted-${plugin.title}" }) { index, plugin ->
                PluginCard(
                    plugin = plugin,
                    rank = index + 1,
                    window = selectedWindow,
                    selected = selectedPluginTitle == plugin.title,
                    onSelect = {
                        selectedPluginTitle = plugin.title
                        detailsPluginTitle = plugin.title
                    },
                    onOpenPlugin = { onOpenCommunityPlugin(plugin) },
                )
            }
            item { PluginSectionLabel(stringResource(Res.string.plugins_top_downloaded)) }
            itemsIndexed(topDownloaded, key = { _, plugin -> "downloaded-${plugin.title}" }) { index, plugin ->
                PluginCard(
                    plugin = plugin,
                    rank = index + 1,
                    window = selectedWindow,
                    selected = selectedPluginTitle == plugin.title,
                    onSelect = {
                        selectedPluginTitle = plugin.title
                        detailsPluginTitle = plugin.title
                    },
                    onOpenPlugin = { onOpenCommunityPlugin(plugin) },
                )
            }
        }
    }

    plugins.firstOrNull { it.title == detailsPluginTitle }?.let { plugin ->
        AlertDialog(
            onDismissRequest = { detailsPluginTitle = null },
            title = { Text(plugin.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(plugin.description)
                    Text(
                        text = stringResource(Res.string.plugin_selected),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { detailsPluginTitle = null }) {
                    Text(stringResource(Res.string.action_close))
                }
            },
        )
    }
}

@Composable
private fun PluginHero() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(Res.string.plugins_hero_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(Res.string.plugins_hero_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun TaskerIntegrationCard(
    plugin: CommunityPluginCardData,
    onDownload: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tasker_plugin_card_${plugin.id}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = plugin.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "Tasker",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                text = plugin.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDownload,
                    enabled = !plugin.downloadUrl.isNullOrBlank() || !plugin.taskerNetLink.isNullOrBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Download profile")
                }
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Settings")
                }
            }
        }
    }
}

@Composable
private fun NativePluginCard(
    plugin: NativePluginCardData,
    onOpenSettings: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("native_plugin_card_${plugin.id}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = plugin.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = plugin.badge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                text = plugin.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Switch(
                    checked = plugin.enabled,
                    onCheckedChange = onToggle,
                    enabled = plugin.isAvailable,
                )
                if (plugin.hasSettings) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(Res.string.plugins_settings_content_description, plugin.title),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeriodFilter(
    selectedWindow: PluginTimeWindow,
    onWindowSelected: (PluginTimeWindow) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.plugins_filter_sort),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PluginTimeWindow.entries.forEach { window ->
                FilterChip(
                    selected = window == selectedWindow,
                    onClick = { onWindowSelected(window) },
                    label = { Text(localizedPluginTimeWindow(window)) },
                )
            }
        }
    }
}

@Composable
private fun PluginSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun PluginCard(
    plugin: CommunityPluginCardData,
    rank: Int,
    window: PluginTimeWindow,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpenPlugin: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$rank. ${plugin.title}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = plugin.badge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                text = stringResource(Res.string.plugins_by_author, plugin.author),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = plugin.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.plugins_downloads, formatCount(plugin.downloads(window))),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = stringResource(Res.string.plugins_votes, formatCount(plugin.votes(window))),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = stringResource(
                        Res.string.plugins_trend,
                        localizedPluginTimeWindow(window),
                        plugin.trend(window),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedButton(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (selected) {
                        stringResource(Res.string.plugin_selected)
                    } else {
                        stringResource(Res.string.plugin_select, plugin.title)
                    },
                )
            }
            when {
                !plugin.taskerNetLink.isNullOrBlank() -> {
                    TextButton(onClick = onOpenPlugin, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.plugins_open_tasker))
                    }
                }
                !plugin.downloadUrl.isNullOrBlank() -> {
                    TextButton(onClick = onOpenPlugin, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.plugins_download))
                    }
                }
            }
        }
    }
}

@Composable
private fun localizedPluginTimeWindow(window: PluginTimeWindow): String = when (window) {
    PluginTimeWindow.ALL_TIME -> stringResource(Res.string.plugins_all_time)
    PluginTimeWindow.WEEKLY -> stringResource(Res.string.plugins_weekly)
    PluginTimeWindow.MONTHLY -> stringResource(Res.string.plugins_monthly)
}

private fun formatCount(value: Int): String = when {
    value >= 1_000_000 -> {
        val m = value / 1_000_000f
        "${floor(m * 10) / 10}M"
    }
    value >= 1_000 -> {
        val k = value / 1_000f
        "${floor(k * 10) / 10}k"
    }
    else -> value.toString()
}
