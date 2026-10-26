package com.fersaiyan.cyanbridge.plugins.walkingaid

import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import android.content.Context
import android.os.Bundle
import com.fersaiyan.cyanbridge.ui.nav.viewModels
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPromptResolver
import com.fersaiyan.cyanbridge.audio.TtsSpeaker
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelRequestPriority
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidChatPlatform
import com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidChatViewModel
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs
import com.fersaiyan.cyanbridge.shared.prefs.store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class WalkingAidChatDestination : NavScreen() {

    private val viewModel by viewModels<WalkingAidChatViewModel> {
        viewModelFactory {
            initializer {
                val context = applicationContext
                WalkingAidChatViewModel(PluginPrefs.WalkingAid.store(context), AndroidWalkingAidChatPlatform(context))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WalkingAidImageStore.load(this)

        setContent {
            WalkingAidChatScreen(viewModel, onBack = ::finish)
        }
    }
}

/** Answers from the relay (cloud source) or the on-device model, spoken with the app language. */
private class AndroidWalkingAidChatPlatform(private val context: Context) : WalkingAidChatPlatform {
    private val localModelsProvider = LocalModelsProvider()
    private var tts: TtsSpeaker? = TtsSpeaker(context, "WalkingAidChat")

    override suspend fun answer(question: String, imagePath: String?): String = withContext(Dispatchers.IO) {
        val latestImage = imagePath
        val settings = ImageQuestionPreferences.get(context)
        val basePrompt = ImageQuestionPromptResolver.resolve(
            settings = settings,
            userQuestion = question,
        ).text
        val telemetryJson = WalkingAidWarningEngine.getRecentSceneTelemetryJson()
        val prompt = if (telemetryJson != "[]") {
            "$basePrompt\n\nReal-time Vision Sensor Tracking Telemetry Log:\n$telemetryJson"
        } else {
            basePrompt
        }

        val source = WalkingAidPreferences.getImageDescriptionSource(context)
        if (source == "cloud") {
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
    }

    override fun speak(text: String) {
        val speaker = tts?.takeIf { it.isReady } ?: return
        speaker.setLanguage(Locale.forLanguageTag(ImageQuestionPreferences.get(context).appLanguageTag))
        speaker.speak(text, "walking_chat_${System.currentTimeMillis()}")
    }

    override fun close() {
        tts?.shutdown()
        tts = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkingAidChatScreen(
    viewModel: WalkingAidChatViewModel,
    onBack: () -> Unit,
) {
    val question = viewModel.question
    val response = viewModel.response
    val isLoading = viewModel.isLoading
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
                    onValueChange = viewModel::onQuestionChange,
                    placeholder = { Text(stringResource(R.string.compose_walking_ask_placeholder)) },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading,
                    singleLine = true,
                )
                IconButton(
                    onClick = { viewModel.ask(history.lastOrNull()?.imagePath) },
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
