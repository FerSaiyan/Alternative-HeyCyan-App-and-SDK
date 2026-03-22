package com.fersaiyan.cyanbridge.ui.localagent

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.data.local.entity.PendingAction
import com.fersaiyan.cyanbridge.localagent.LocalAgentAccessibilityBridge
import com.fersaiyan.cyanbridge.localagent.LocalAgentActionParser
import com.fersaiyan.cyanbridge.localagent.actions.LocalAgentActionManager
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingActionsScreen(
    onNavigateBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingActions by remember { mutableStateOf<List<PendingAction>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var actionResult by remember { mutableStateOf<String?>(null) }

    fun loadPending() {
        isLoading = true
        scope.launch {
            val dao = MyApplication.database.pendingActionDao()
            val actions = withContext(Dispatchers.IO) {
                dao.getActionsByStatus("pending")
            }
            pendingActions = actions
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadPending()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pending Actions") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Close",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { loadPending() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh",
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
            Text(
                text = "Pending: ${pendingActions.size}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (actionResult != null) {
                Text(
                    text = actionResult!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (pendingActions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "(no pending actions)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    pendingActions.forEach { action ->
                        PendingActionCard(
                            action = action,
                            onApprove = {
                                scope.launch {
                                    val dao = MyApplication.database.pendingActionDao()
                                    withContext(Dispatchers.IO) {
                                        action.status = "approved"
                                        action.result = null
                                        dao.update(action)
                                    }

                                    val actions = LocalAgentActionParser.parseList(action.actionJson)
                                    if (actions.isEmpty()) {
                                        withContext(Dispatchers.IO) {
                                            action.status = "executed"
                                            action.result = "parse_failed"
                                            dao.update(action)
                                        }
                                        actionResult = "Could not parse action JSON"
                                    } else {
                                        val results = mutableListOf<String>()
                                        for (a in actions) {
                                            val ok = runCatching {
                                                val intentOk = LocalAgentActionManager.executeNow(context, a)
                                                if (intentOk) true else LocalAgentAccessibilityBridge.perform(a)
                                            }.getOrDefault(false)

                                            results += "${a.javaClass.simpleName}: ${if (ok) "ok" else "failed"}"
                                        }

                                        withContext(Dispatchers.IO) {
                                            action.status = "executed"
                                            action.result = results.joinToString("; ")
                                            dao.update(action)
                                        }
                                        actionResult = "Executed action #${action.id}"
                                    }

                                    Toast.makeText(context, actionResult, Toast.LENGTH_SHORT).show()
                                    loadPending()
                                }
                            },
                            onReject = {
                                scope.launch {
                                    val dao = MyApplication.database.pendingActionDao()
                                    withContext(Dispatchers.IO) {
                                        action.status = "rejected"
                                        action.result = "rejected_by_user"
                                        dao.update(action)
                                    }
                                    actionResult = "Rejected action #${action.id}"
                                    Toast.makeText(context, actionResult, Toast.LENGTH_SHORT).show()
                                    loadPending()
                                }
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun PendingActionCard(
    action: PendingAction,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val tsText = remember(action.ts) {
        if (action.ts > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(action.ts))
        } else "(no-ts)"
    }

    val prettyJson = remember(action.actionJson) {
        runCatching {
            val trimmed = action.actionJson.trim()
            if (trimmed.startsWith("{")) {
                JSONObject(trimmed).toString(2)
            } else {
                trimmed
            }
        }.getOrDefault(action.actionJson)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "id=${action.id}  ts=$tsText",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "source=${action.source}  status=${action.status}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!action.result.isNullOrBlank()) {
                Text(
                    text = "result=${action.result}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = prettyJson,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reject")
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Approve")
                }
            }
        }
    }
}
