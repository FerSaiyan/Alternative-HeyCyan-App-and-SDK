package com.fersaiyan.cyanbridge.ui.localagent

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BlacklistAppUiItem(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystemApp: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBlacklistScreen(
    onNavigateBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var allApps by remember { mutableStateOf<List<BlacklistAppUiItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var hideSystemApps by remember { mutableStateOf(true) }
    val selectedPackages = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        isLoading = true
        val initialSelection = LocalAgentPrefs.getCaptureBlacklistPackages(context)
        selectedPackages.clear()
        selectedPackages.addAll(initialSelection)
        hideSystemApps = LocalAgentPrefs.isHideSystemAppsEnabled(context)

        val apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(0)
                .asSequence()
                .mapNotNull { ai ->
                    val pkg = ai.packageName?.trim().orEmpty()
                    if (pkg.isBlank()) return@mapNotNull null

                    val label = runCatching { pm.getApplicationLabel(ai).toString().trim() }
                        .getOrNull()
                        .orEmpty()
                        .ifBlank { pkg }

                    val icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull()
                    val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                    BlacklistAppUiItem(
                        packageName = pkg,
                        label = label,
                        icon = icon,
                        isSystemApp = isSystem,
                    )
                }
                .distinctBy { it.packageName }
                .sortedWith(compareBy<BlacklistAppUiItem> { it.label.lowercase() }.thenBy { it.packageName })
                .toList()
        }
        allApps = apps
        isLoading = false
    }

    val filteredApps = remember(allApps, searchQuery, hideSystemApps) {
        val q = searchQuery.trim().lowercase()
        allApps.filter {
            val okSystem = !(hideSystemApps && it.isSystemApp)
            val okQuery = q.isBlank() ||
                it.label.lowercase().contains(q) ||
                it.packageName.lowercase().contains(q)
            okSystem && okQuery
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blacklist Apps") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Close",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val selected = selectedPackages.toSet()
                        LocalAgentPrefs.setCaptureBlacklistPackages(context, selected)
                        Toast.makeText(context, "Saved blacklist (${selected.size} apps)", Toast.LENGTH_SHORT).show()
                        onNavigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Save",
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
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search apps") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                    )
                },
                singleLine = true,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = "Hide system apps",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = hideSystemApps,
                    onCheckedChange = {
                        hideSystemApps = it
                        LocalAgentPrefs.setHideSystemAppsEnabled(context, it)
                    },
                )
            }

            Text(
                text = "${filteredApps.size} / ${allApps.size} apps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        BlacklistAppRow(
                            app = app,
                            isChecked = selectedPackages.contains(app.packageName),
                            onCheckedChange = { checked ->
                                if (checked) selectedPackages.add(app.packageName)
                                else selectedPackages.remove(app.packageName)
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun BlacklistAppRow(
    app: BlacklistAppUiItem,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isChecked) }
            .padding(vertical = 8.dp),
    ) {
        val bitmap = remember(app.packageName) { app.icon?.toBitmap()?.asImageBitmap() }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        } else {
            Spacer(modifier = Modifier.size(36.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Checkbox(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
        )
    }
}
