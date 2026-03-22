package com.fersaiyan.cyanbridge.ui.localagent

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MODE_DRAFT = "draft"
private const val MODE_CONFIRMED = "confirmed"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyFactsScreen(
    mode: String = MODE_DRAFT,
    date: String = "",
    onNavigateBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val resolvedDate = remember(date) {
        date.trim().ifBlank {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis()))
        }
    }
    val resolvedMode = remember(mode) { mode.trim().ifBlank { MODE_DRAFT } }

    val file: File = remember(resolvedMode, resolvedDate) {
        LocalAgentMemoryStore.ensureSeedFiles(context)
        when (resolvedMode) {
            MODE_CONFIRMED -> LocalAgentMemoryStore.confirmedDailyFactsFileForDate(context, resolvedDate)
            else -> LocalAgentMemoryStore.dailyFactsFileForDate(context, resolvedDate)
        }
    }

    val title = when (resolvedMode) {
        MODE_CONFIRMED -> "Confirmed daily facts ($resolvedDate)"
        else -> "Daily facts ($resolvedDate)"
    }

    val hint = when (resolvedMode) {
        MODE_CONFIRMED -> "Confirmed facts (used by the agent as true for this day)"
        else -> "Write facts you want to remember / verify"
    }

    var text by remember { mutableStateOf("") }
    var savedText by remember { mutableStateOf("") }

    LaunchedEffect(file.absolutePath) {
        val loaded = LocalAgentMemoryStore.readText(file)
        text = loaded
        savedText = loaded
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
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
                    IconButton(onClick = {
                        LocalAgentMemoryStore.writeText(file, text)
                        savedText = text
                        Toast.makeText(
                            context,
                            if (resolvedMode == MODE_CONFIRMED) "Saved confirmed facts" else "Saved daily facts",
                            Toast.LENGTH_SHORT,
                        ).show()
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(top = 12.dp),
                minLines = 8,
            )

            Text(
                text = file.absolutePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
