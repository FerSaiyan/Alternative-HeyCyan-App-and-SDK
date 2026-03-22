package com.fersaiyan.cyanbridge.ui.localagent

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryGenerator
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryPrefs
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailySummaryScreen(
    date: String = "",
    onNavigateBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val resolvedDate = remember(date) {
        date.trim().ifBlank {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis()))
        }
    }

    val file = remember(resolvedDate) {
        LocalAgentMemoryStore.ensureSeedFiles(context)
        LocalAgentMemoryStore.dailySummaryFileForDate(context, resolvedDate)
    }

    var summaryText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }

    fun refreshFromDisk() {
        val loaded = LocalAgentMemoryStore.readText(file).trimEnd()
        summaryText = if (loaded.isNotBlank()) loaded else "(No daily summary generated yet. Tap Regenerate.)"

        val last = DailySummaryPrefs.getLastGeneratedAtMs(context, resolvedDate)
        statusText = if (last > 0L) {
            val t = SimpleDateFormat("HH:mm", Locale.US).format(Date(last))
            "Last generated: $t"
        } else {
            "Not generated yet"
        }
    }

    LaunchedEffect(resolvedDate) {
        refreshFromDisk()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Daily summary ($resolvedDate)",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Close",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { refreshFromDisk() },
                        enabled = !isBusy,
                    ) {
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isBusy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Generating\u2026",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Text(
                text = summaryText,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val content = summaryText.trim()
                        if (content.isBlank()) {
                            Toast.makeText(context, "Nothing to share", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val payload = buildString {
                            append("Daily summary ($resolvedDate)\n")
                            append("File: ${file.absolutePath}\n\n")
                            append(content)
                        }
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Daily summary ($resolvedDate)")
                            putExtra(Intent.EXTRA_TEXT, payload)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share daily summary"))
                    },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share")
                }

                Spacer(modifier = Modifier.width(12.dp))

                OutlinedButton(
                    onClick = {
                        val cooldown = DailySummaryPrefs.remainingCooldownMs(context, resolvedDate)
                        if (cooldown > 0L) {
                            val seconds = (cooldown / 1000L).coerceAtLeast(1L)
                            Toast.makeText(context, "Please wait ${seconds}s before regenerating.", Toast.LENGTH_SHORT).show()
                            return@OutlinedButton
                        }

                        isBusy = true
                        statusText = "Generating\u2026"
                        Toast.makeText(context, "Generating daily summary\u2026", Toast.LENGTH_SHORT).show()

                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                DailySummaryGenerator.generateAndStore(context, resolvedDate)
                            }

                            if (result.isSuccess) {
                                refreshFromDisk()
                                Toast.makeText(context, "Daily summary saved", Toast.LENGTH_SHORT).show()
                            } else {
                                statusText = "Generation failed"
                                Toast.makeText(
                                    context,
                                    "Failed: ${result.exceptionOrNull()?.message}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }

                            isBusy = false
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Regenerate")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = file.absolutePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
