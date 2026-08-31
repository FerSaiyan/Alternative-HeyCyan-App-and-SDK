package com.fersaiyan.cyanbridge.shared.ui.localagent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

data class BlacklistAppItem(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun LocalAgentDocumentScreen(
    title: String,
    path: String,
    text: String,
    hint: String,
    editable: Boolean,
    primaryLabel: String,
    onTextChange: (String) -> Unit,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                         Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(Res.string.action_back))
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeroPanel(text = title, supportingText = path)
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = { Text(hint) },
                readOnly = !editable,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                shape = MaterialTheme.shapes.large,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                secondaryLabel?.let { label ->
                    OutlinedButton(onClick = onSecondary ?: {}) { Text(label) }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = onPrimary) { Text(primaryLabel) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun PendingActionsScreen(
    pendingCount: Int,
    renderedAction: String,
    hasPendingAction: Boolean,
    onRefresh: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.local_agent_pending_actions)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                         Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(Res.string.action_back))
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeroPanel(text = stringResource(Res.string.local_agent_pending_count, pendingCount))
            Card(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                SelectionContainer(modifier = Modifier.padding(16.dp)) {
                    Text(
                        renderedAction,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReject,
                    enabled = hasPendingAction,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                     Text(stringResource(Res.string.local_agent_reject))
                }
                Button(onClick = onApprove, enabled = hasPendingAction, modifier = Modifier.weight(1f)) {
                     Text(stringResource(Res.string.local_agent_approve))
                }
                 TextButton(onClick = onRefresh, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.local_agent_refresh)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun DailySummaryScreen(
    title: String,
    path: String,
    status: String,
    summary: String,
    isBusy: Boolean,
    progress: Int,
    progressTitle: String,
    progressDetail: String,
    onRefresh: () -> Unit,
    onRegenerate: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                         Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(Res.string.action_back))
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HeroPanel(text = status, supportingText = path)
            AnimatedVisibility(
                visible = isBusy,
                enter = fadeIn(animationSpec = spring()) + expandVertically(animationSpec = spring()),
                exit = fadeOut(animationSpec = spring()) + shrinkVertically(animationSpec = spring()),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = spring()),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(progressTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Text(progressDetail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Card(modifier = Modifier.weight(1f).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                SelectionContainer(modifier = Modifier.padding(16.dp)) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                 OutlinedButton(onClick = onRefresh, enabled = !isBusy, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.local_agent_refresh)) }
                 Button(onClick = onRegenerate, enabled = !isBusy, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.local_agent_regenerate)) }
                 TextButton(onClick = onShare, enabled = !isBusy, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.local_agent_share)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun AppBlacklistScreen(
    apps: List<BlacklistAppItem>,
    totalCount: Int,
    query: String,
    hideSystemApps: Boolean,
    selectedPackages: Set<String>,
    isLoading: Boolean,
    onQueryChange: (String) -> Unit,
    onHideSystemAppsChange: (Boolean) -> Unit,
    onTogglePackage: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.local_agent_blacklist_apps)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                         Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(Res.string.action_back))
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
                .padding(horizontal = 16.dp),
        ) {
            HeroPanel(
                text = if (isLoading) {
                    stringResource(Res.string.local_agent_loading_apps)
                } else {
                    stringResource(Res.string.local_agent_app_count, apps.size, totalCount)
                },
                modifier = Modifier.padding(top = 12.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        label = { Text(stringResource(Res.string.local_agent_search)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(Res.string.local_agent_hide_system), modifier = Modifier.weight(1f))
                        Switch(checked = hideSystemApps, onCheckedChange = onHideSystemAppsChange)
                    }
                }
            }
            Card(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = app.packageName in selectedPackages,
                                onCheckedChange = { onTogglePackage(app.packageName) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = buildString {
                                        append(app.packageName)
                                         if (app.isSystemApp) append(" · ").append(stringResource(Res.string.local_agent_system))
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                 Text(stringResource(Res.string.local_agent_save_blacklist))
            }
        }
    }
}

@Composable
private fun HeroPanel(
    text: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.padding(12.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                supportingText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
