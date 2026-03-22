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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenCapturesScreen(
    onNavigateBack: () -> Unit = {},
) {
    val context = LocalContext.current

    val date = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis()))
    }

    var captureLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var filePath by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }

    fun loadCaptures() {
        val file = LocalAgentMemoryStore.screenCaptureFileForDate(context, date)
        filePath = file.absolutePath
        captureLines = LocalAgentMemoryStore.readScreenCaptureLines(context, date, maxLines = 25)
    }

    LaunchedEffect(Unit) {
        loadCaptures()
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear all screen captures?") },
            text = { Text("This will delete all screen OCR capture data. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    LocalAgentMemoryStore.deleteAllPassiveCapture(context)
                    loadCaptures()
                    Toast.makeText(context, "Screen captures cleared", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screen Captures") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Close",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { loadCaptures() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                        )
                    }
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Clear all",
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
                text = "Screen captures ($date)",
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = filePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (captureLines.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No screen captures yet for today",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val rendered = remember(captureLines) {
                    renderScreenCaptures(captureLines)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    rendered.forEach { entry ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = entry.time,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = entry.pkg,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                Text(
                                    text = entry.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

private data class ScreenCaptureEntry(
    val time: String,
    val pkg: String,
    val text: String,
)

private fun renderScreenCaptures(lines: List<String>): List<ScreenCaptureEntry> {
    val tsFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    return lines.mapNotNull { line ->
        val obj = runCatching { JSONObject(line) }.getOrNull()
        if (obj == null) return@mapNotNull ScreenCaptureEntry(
            time = "",
            pkg = "",
            text = line.take(2500),
        )

        val ts = obj.optLong("ts_ms", 0L)
        val pkg = obj.optString("package", "")
        val text = obj.optString("text", "").take(2500)
        val tsText = if (ts > 0L) tsFmt.format(Date(ts)) else "(no-ts)"

        ScreenCaptureEntry(
            time = tsText,
            pkg = pkg,
            text = if (text.length >= 2500) "$text\n\u2026(truncated)" else text,
        )
    }
}
