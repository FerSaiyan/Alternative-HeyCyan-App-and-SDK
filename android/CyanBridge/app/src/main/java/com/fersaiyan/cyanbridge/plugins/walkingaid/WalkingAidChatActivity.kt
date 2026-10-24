package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPromptResolver
import com.fersaiyan.cyanbridge.audio.TtsSpeaker
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelRequestPriority
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.ui.setThemedComposeContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class WalkingAidChatActivity : AppCompatActivity() {

    private lateinit var composeView: ComposeView
    private var tts: TtsSpeaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        composeView = ComposeView(this).also(::setContentView)

        tts = TtsSpeaker(this, "WalkingAidChat")

        WalkingAidImageStore.load(this)

        setThemedComposeContent(composeView) {
            WalkingAidChatScreen(
                onBack = ::finish,
                onSpeak = { speakTts(it) },
            )
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun speakTts(text: String) {
        val speaker = tts?.takeIf { it.isReady } ?: return
        speaker.setLanguage(Locale.forLanguageTag(ImageQuestionPreferences.get(this).appLanguageTag))
        speaker.speak(text, "walking_chat_${System.currentTimeMillis()}")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkingAidChatScreen(
    onBack: () -> Unit,
    onSpeak: (String) -> Unit,
) {
    val context = LocalContext.current
    var question by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val localModelsProvider = remember { LocalModelsProvider() }
    val history = remember { WalkingAidImageStore.getImageHistory(10) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_walking_ask_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.compose_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
        ) {
            // Image thumbnails from history
            if (history.isNotEmpty()) {
                Text(
                    stringResource(R.string.compose_walking_recent_images),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    history.takeLast(5).forEach { entry ->
                        val file = File(entry.imagePath)
                        if (file.exists()) {
                            Card(
                                modifier = Modifier.size(80.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            ) {
                                android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.let { bitmap ->
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = stringResource(R.string.compose_walking_thumbnail),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.compose_walking_no_images),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Response area
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (response.isNotBlank()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Text(
                                text = response,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (isLoading) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Input
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    placeholder = { Text(stringResource(R.string.compose_walking_ask_placeholder)) },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading,
                    singleLine = true,
                )
                IconButton(
                    onClick = {
                        if (question.isBlank() || isLoading) return@IconButton
                        isLoading = true
                        val userQuestion = question.trim()
                        question = ""

                        // Get the latest image
                        val latestImage = history.lastOrNull()?.imagePath
                        val settings = ImageQuestionPreferences.get(context)

                        CoroutineScope(Dispatchers.IO).launch {
                            val basePrompt = ImageQuestionPromptResolver.resolve(
                                settings = settings,
                                userQuestion = userQuestion,
                            ).text
                            val telemetryJson = WalkingAidWarningEngine.getRecentSceneTelemetryJson()
                            val prompt = if (telemetryJson != "[]") {
                                "$basePrompt\n\nReal-time Vision Sensor Tracking Telemetry Log:\n$telemetryJson"
                            } else {
                                basePrompt
                            }

                            val source = WalkingAidPreferences.getImageDescriptionSource(context)
                            val reply = if (source == "cloud") {
                                val modelOverride = WalkingAidPreferences.getImageDescriptionModelOverride(context)
                                val result = if (latestImage != null && File(latestImage).exists()) {
                                    CliRelayClient.imageQuery(
                                        context = context,
                                        imagePath = latestImage,
                                        prompt = prompt,
                                        modelOverride = modelOverride,
                                    )
                                } else {
                                    CliRelayClient.voiceQuery(
                                        context = context,
                                        prompt = prompt,
                                        modelOverride = modelOverride,
                                    )
                                }
                                result.getOrDefault("I couldn't get an answer. Please try again.")
                            } else {
                                try {
                                    val imagePaths = if (latestImage != null && File(latestImage).exists())
                                        listOf(latestImage) else emptyList()
                                    localModelsProvider.streamChat(
                                        context = context,
                                        messages = listOf(
                                            mapOf("role" to "User", "content" to prompt)
                                        ),
                                        imagePaths = imagePaths,
                                        requestPriority = LocalModelRequestPriority.HIGH,
                                    )
                                } catch (e: Exception) {
                                    "Error: ${e.message}"
                                }
                            }

                            response = reply.trim()
                            if (WalkingAidPreferences.isTtsEnabled(context) && reply.isNotBlank()) {
                                onSpeak(reply)
                            }
                            isLoading = false
                        }
                    },
                    enabled = question.isNotBlank() && !isLoading,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.compose_walking_send),
                        tint = if (question.isNotBlank() && !isLoading)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
            }
        }
    }
}
