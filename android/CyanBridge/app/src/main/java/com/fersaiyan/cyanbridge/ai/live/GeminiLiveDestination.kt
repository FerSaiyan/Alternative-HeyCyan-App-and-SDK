package com.fersaiyan.cyanbridge.ai.live

import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.fersaiyan.cyanbridge.ui.nav.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.AndroidProAccountPlatform
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPromptResolver
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.shared.billing.hasPaidPlan
import com.fersaiyan.cyanbridge.shared.live.GeminiLiveSession
import com.fersaiyan.cyanbridge.shared.live.GeminiLiveViewModel
import com.fersaiyan.cyanbridge.shared.live.LiveImageRequest
import com.fersaiyan.cyanbridge.shared.live.RequestMicrophone
import com.fersaiyan.cyanbridge.ui.collectScreenEffects
import com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Visible Gemini Live session; [GeminiLiveViewModel] owns its state and timers.
 *
 * Opening/using other Pro models never starts this Activity or its audio/vision controllers.
 */
class GeminiLiveDestination : NavScreen() {
    private val viewModel: GeminiLiveViewModel by viewModels {
        viewModelFactory {
            initializer {
                val session = AndroidGeminiLiveSession(
                    context = applicationContext,
                    initialImagePath = intent.getStringExtra(EXTRA_INITIAL_IMAGE_PATH)?.takeIf { it.isNotBlank() },
                    initialPrompt = intent.getStringExtra(EXTRA_INITIAL_PROMPT)?.takeIf { it.isNotBlank() },
                )
                val platform = AndroidProAccountPlatform(applicationContext)
                GeminiLiveViewModel(session) { platform.entitlement.hasPaidPlan() }.also(session::attach)
            }
        }
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onMicrophoneResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gemini_live)
        val status = findViewById<TextView>(R.id.gemini_live_status)
        val elapsed = findViewById<TextView>(R.id.gemini_live_elapsed)
        val network = findViewById<TextView>(R.id.gemini_live_network)
        val indicators = findViewById<TextView>(R.id.gemini_live_indicators)
        val startButton = findViewById<MaterialButton>(R.id.gemini_live_start)
        val stopButton = findViewById<MaterialButton>(R.id.gemini_live_stop)

        startButton.setOnClickListener { viewModel.requestStart() }
        stopButton.setOnClickListener { viewModel.stop() }
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                state.status?.let { status.text = it }
                state.elapsed?.let { elapsed.text = it }
                state.network?.let { network.text = it }
                indicators.text = state.indicators
                startButton.visibility = if (state.active) View.GONE else View.VISIBLE
                stopButton.visibility = if (state.active) View.VISIBLE else View.GONE
            }
        }
        collectScreenEffects(viewModel.effectFlow) { effect ->
            if (effect == RequestMicrophone) microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        viewModel.onCreate(autoStart = intent.getBooleanExtra(EXTRA_AUTO_START, false))
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onPostResume() {
        super.onPostResume()
        viewModel.onPostResume()
    }

    override fun onPause() {
        viewModel.onPause()
        super.onPause()
    }

    companion object {
        const val EXTRA_AUTO_START = "gemini_live_auto_start"
        const val EXTRA_INITIAL_IMAGE_PATH = "gemini_live_initial_image_path"
        const val EXTRA_INITIAL_PROMPT = "gemini_live_initial_prompt"
    }
}

/** The Gemini Live client, glasses vision and AI-photo button behind the preview screen. */
private class AndroidGeminiLiveSession(
    context: Context,
    private val initialImagePath: String?,
    private val initialPrompt: String?,
) : GeminiLiveSession, GeminiLiveClient.Listener {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope: CoroutineScope = MainScope()
    private lateinit var viewModel: GeminiLiveViewModel
    private val client = GeminiLiveClient(this.context, this)
    private val visionController = GeminiLiveVisionController(this.context, client) { message ->
        onMain { viewModel.onVisionStatus(message) }
    }
    private var imageButtonRegistered = false
    private val imageButtonHandler: (ManualGlassesPhoto) -> Unit = { photo ->
        scope.launch(Dispatchers.Main) { viewModel.onImageButton(ImageRequest(photo)) }
    }

    fun attach(viewModel: GeminiLiveViewModel) {
        this.viewModel = viewModel
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun microphoneGranted() = granted(Manifest.permission.RECORD_AUDIO)

    override fun notificationsGranted() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)

    override fun start() {
        val language = AppLanguagePreferences.selected(context).languageTag.ifBlank { Locale.getDefault().toLanguageTag() }
        // Base question without strict language lock: Live's system handles 97-language switching permissively.
        val defaultImageQuestion = ImageQuestionPromptResolver.baseQuestion(
            settings = ImageQuestionPreferences.get(context),
            userQuestion = null,
        )
        visionController.start()
        client.start(language, defaultImageQuestion)
    }

    override fun stop() = client.stop()
    override fun startVision() = visionController.start()
    override fun stopVision() = visionController.stop()
    override fun pause() = client.pauseForBackground()
    override fun resume() = client.resumeAfterForeground()

    override fun setImageButtonEnabled(enabled: Boolean) {
        if (enabled && !imageButtonRegistered) {
            GeminiLiveImageButtonRouter.register(DeviceProfileStore.selectedClass(context), imageButtonHandler)
            imageButtonRegistered = true
        } else if (!enabled && imageButtonRegistered) {
            GeminiLiveImageButtonRouter.unregister(imageButtonHandler)
            imageButtonRegistered = false
        }
    }

    override fun sendInitialTurn() {
        android.util.Log.i(TAG, "Sending initial Live turn image=${!initialImagePath.isNullOrBlank()} prompt=${!initialPrompt.isNullOrBlank()}")
        initialImagePath?.let { path ->
            scope.launch(Dispatchers.IO) {
                val image = runCatching { File(path).readBytes() }.getOrNull()
                if (image != null && image.isNotEmpty()) {
                    if (client.sendImage(image)) visionController.onVisualContextSent()
                }
                initialPrompt?.let(client::sendTextTurn)
            }
        } ?: initialPrompt?.let(client::sendTextTurn)
    }

    override fun close() {
        visionController.close()
        client.close()
        scope.cancel()
    }

    override fun onStateChanged(state: GeminiLiveState, detail: String) = onMain { viewModel.onStateChanged(state, detail) }
    override fun onInterrupted() = onMain { viewModel.onInterrupted() }
    override fun onNetworkChanged(available: Boolean) = onMain { viewModel.onNetworkChanged(available) }
    override fun onModelOutputStarted() = onMain { viewModel.onActivity() }
    override fun onModelTurnComplete() = onMain { viewModel.onModelTurnComplete() }
    override fun onSetupComplete() = viewModel.onSetupComplete()

    override fun onUserSpeechActivity(active: Boolean) {
        visionController.onSpeechActivity(active)
        if (active) onMain { viewModel.onActivity() }
    }

    override fun onTranscription(input: Boolean, text: String) {
        // Transcription is parallel metadata, never on the audio -> Gemini -> native-audio path.
        if (input && text.isNotBlank()) onMain { viewModel.onActivity() }
        if (text.isNotBlank()) android.util.Log.d(TAG, "${if (input) "User" else "Gemini"} transcription: $text")
    }

    private inner class ImageRequest(private val photo: ManualGlassesPhoto) : LiveImageRequest {
        private var captured = false
        private var error: Throwable? = null

        override suspend fun captureAndSend(): Result<Boolean> {
            client.recordVisionCaptureStarted()
            val result = withContext(Dispatchers.IO) {
                runCatching { GeminiLiveGlassesImageCapture(context).captureFromHardwareButton(photo) }
            }
            return result.map { image ->
                captured = true
                client.sendImage(image).also { queued -> if (queued) visionController.onVisualContextSent() }
            }.onFailure { error = it }
        }

        override fun finish() {
            client.recordVisionCaptureFinished(captured, error)
            photo.acquisition?.cancel()
        }

        override fun cancel() {
            photo.acquisition?.cancel()
        }
    }

    private companion object {
        const val TAG = "GeminiLiveActivity"
    }
}
