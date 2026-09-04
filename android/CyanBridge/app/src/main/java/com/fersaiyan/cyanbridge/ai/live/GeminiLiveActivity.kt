package com.fersaiyan.cyanbridge.ai.live

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPromptResolver
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionRoute
import com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Visible, activity-scoped Gemini Live session.
 *
 * Opening/using other Pro models never starts this Activity or its audio/vision controllers.
 */
class GeminiLiveActivity : AppCompatActivity(), GeminiLiveClient.Listener {
    private lateinit var client: GeminiLiveClient
    private var useRelayForFreeTier = false
    private lateinit var visionController: GeminiLiveVisionController
    private lateinit var status: TextView
    private lateinit var elapsed: TextView
    private lateinit var network: TextView
    private lateinit var indicators: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private var startedAtMs = 0L
    private var liveListening = false
    private var hardwareImageButtonRegistered = false
    private var visionStatus = "Glasses vision: waiting"
    private var initialImagePath: String? = null
    private var initialPrompt: String? = null
    private var initialTurnSent = false
    private val hardwareImageCaptureInProgress = AtomicBoolean(false)
    private val hardwareImageButtonHandler: () -> Unit = { captureHardwareImageQuestion() }

    private val ticker = object : Runnable {
        override fun run() {
            if (startedAtMs > 0L) {
                val seconds = (System.currentTimeMillis() - startedAtMs) / 1_000L
                elapsed.text = "Session ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                elapsed.postDelayed(this, 1_000L)
            }
        }
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startLive()
        else Toast.makeText(this, "Microphone permission is required for Gemini Live", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gemini_live)
        status = findViewById(R.id.gemini_live_status)
        elapsed = findViewById(R.id.gemini_live_elapsed)
        network = findViewById(R.id.gemini_live_network)
        indicators = findViewById(R.id.gemini_live_indicators)
        startButton = findViewById(R.id.gemini_live_start)
        stopButton = findViewById(R.id.gemini_live_stop)
        client = GeminiLiveClient(this, this)
        initialImagePath = intent.getStringExtra(EXTRA_INITIAL_IMAGE_PATH)?.takeIf { it.isNotBlank() }
        initialPrompt = intent.getStringExtra(EXTRA_INITIAL_PROMPT)?.takeIf { it.isNotBlank() }
        visionController = GeminiLiveVisionController(this, client) { message ->
            runOnUiThread {
                visionStatus = message
                renderIndicators()
            }
        }

        startButton.setOnClickListener { explainAndRequestMicrophone() }
        stopButton.setOnClickListener {
            visionController.stop()
            client.stop()
        }
        setControls(false)
        renderIndicators()
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) startLive()
            else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        client.resumeAfterForeground()
        if (liveListening) visionController.start()
    }

    override fun onPostResume() {
        super.onPostResume()
        updateHardwareImageButtonRouting()
    }

    override fun onPause() {
        unregisterHardwareImageButton()
        visionController.stop()
        client.pauseForBackground()
        super.onPause()
    }

    override fun onDestroy() {
        elapsed.removeCallbacks(ticker)
        unregisterHardwareImageButton()
        visionController.close()
        client.close()
        super.onDestroy()
    }

    private fun explainAndRequestMicrophone() {
        // Pro connects directly with a constrained token. Free connects to the authenticated
        // CyanBridge WebSocket proxy, which retains both Google credentials on Vercel.
        val isPro = hasPaidPlan()
        useRelayForFreeTier = !isPro
        val message = if (isPro) {
            "Gemini Live (Pro) uses an ephemeral token + GCP Vertex paid route. Your microphone streams " +
                "directly to Google with a short-lived token (no API key on device). Glasses vision is streamed live."
        } else {
            "Gemini Live (Free) routes continuously through the CyanBridge server. Vercel holds the " +
                "Google API key and ephemeral token while proxying selected speech and images. " +
                "No Google credential is stored on this device."
        }
        AlertDialog.Builder(this)
            .setTitle(if (isPro) "Gemini Live — Pro (direct)" else "Gemini Live — Free (relay)")
            .setMessage(
                message + " Compatible streaming glasses contribute visual context. Other Pro models are unaffected.",
            )
            .setPositiveButton("Continue") { _, _ ->
                if (hasPermission(Manifest.permission.RECORD_AUDIO)) startLive()
                else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startLive() {
        val language = AppLanguagePreferences.selected(this).languageTag
            .ifBlank { Locale.getDefault().toLanguageTag() }
        // Base question without strict language lock - Live's system handles 97-language switching permissively
        val defaultImageQuestion = ImageQuestionPromptResolver.baseQuestion(
            settings = ImageQuestionPreferences.get(this),
            userQuestion = null,
        )
        visionStatus = if (useRelayForFreeTier) "Glasses vision: relay (server holds key)" else "Glasses vision: preparing"
        renderIndicators()
        status.text = if (useRelayForFreeTier) "Connecting to CyanBridge Live relay" else "Connecting to Gemini Live"
        visionController.start()
        client.start(language, defaultImageQuestion)
    }

    private fun captureHardwareImageQuestion() {
        if (!liveListening) return
        if (!hardwareImageCaptureInProgress.compareAndSet(false, true)) return
        status.text = "Receiving glasses AI photo"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { GeminiLiveGlassesImageCapture(this@GeminiLiveActivity).captureFromHardwareButton() }
            }
            result
                .onSuccess { image ->
                    client.sendImage(image)
                    visionController.onVisualContextSent()
                    visionStatus = "Glasses vision: manual AI-photo sent"
                    status.text = "Image sent to Gemini Live"
                    renderIndicators()
                }
                .onFailure { error ->
                    Toast.makeText(
                        this@GeminiLiveActivity,
                        error.message ?: "Glasses image capture failed",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            hardwareImageCaptureInProgress.set(false)
        }
    }

    private fun hasPaidPlan(): Boolean =
        ProSubscriptionPrefs.isActiveLocally(this) &&
            ProSubscriptionPrefs.getPlan(this).lowercase() in setOf("cheap", "standard", "max")

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onStateChanged(state: GeminiLiveState, detail: String) {
        runOnUiThread {
            status.text = detail.ifBlank { state.name.lowercase().replaceFirstChar(Char::uppercase) }
            val listening = state == GeminiLiveState.LISTENING
            liveListening = listening
            updateHardwareImageButtonRouting()

            if (listening) {
                visionController.start()
                if (startedAtMs == 0L) {
                    startedAtMs = System.currentTimeMillis()
                    elapsed.post(ticker)
                }
            }

            if (state == GeminiLiveState.STOPPED || state == GeminiLiveState.ERROR) {
                visionController.stop()
                startedAtMs = 0L
                elapsed.removeCallbacks(ticker)
                elapsed.text = "Session not running"
            }
            renderIndicators()
            setControls(
                listening ||
                    state == GeminiLiveState.CONNECTING ||
                    state == GeminiLiveState.RECONNECTING ||
                    state == GeminiLiveState.REQUESTING_TOKEN,
            )
        }
    }

    override fun onInterrupted() {
        runOnUiThread { status.text = "Gemini was interrupted. Listening for you." }
    }

    override fun onNetworkChanged(available: Boolean) {
        runOnUiThread {
            network.text = if (available) {
                "Network: connected"
            } else {
                "Network: lost, reconnecting when available"
            }
        }
    }

    override fun onUserSpeechActivity(active: Boolean) {
        visionController.onSpeechActivity(active)
    }

    override fun onTranscription(input: Boolean, text: String) {
        // Transcription is intentionally parallel metadata. It is not inserted into the
        // audio -> Gemini -> native-audio critical path.
        if (text.isNotBlank()) {
            val direction = if (input) "User" else "Gemini"
            android.util.Log.d("GeminiLiveActivity", "$direction transcription: $text")
        }
    }

    override fun onSetupComplete() {
        if (initialTurnSent) return
        initialTurnSent = true
        android.util.Log.i(
            "GeminiLiveActivity",
            "Sending initial Live turn image=${!initialImagePath.isNullOrBlank()} prompt=${!initialPrompt.isNullOrBlank()}",
        )
        initialImagePath?.let { path ->
            lifecycleScope.launch(Dispatchers.IO) {
                val image = runCatching { File(path).readBytes() }.getOrNull()
                if (image != null && image.isNotEmpty()) {
                    client.sendImage(image)
                    visionController.onVisualContextSent()
                }
                initialPrompt?.let(client::sendTextTurn)
            }
        } ?: initialPrompt?.let(client::sendTextTurn)
    }

    private fun renderIndicators() {
        indicators.text = if (liveListening) {
            "Microphone: on   $visionStatus"
        } else {
            "Microphone: off   Glasses vision: off"
        }
    }

    private fun setControls(active: Boolean) {
        startButton.visibility = if (active) View.GONE else View.VISIBLE
        stopButton.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun updateHardwareImageButtonRouting() {
        val shouldRegister = liveListening &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        if (shouldRegister && !hardwareImageButtonRegistered) {
            GeminiLiveImageButtonRouter.register(hardwareImageButtonHandler)
            hardwareImageButtonRegistered = true
        } else if (!shouldRegister) {
            unregisterHardwareImageButton()
        }
    }

    private fun unregisterHardwareImageButton() {
        if (!hardwareImageButtonRegistered) return
        GeminiLiveImageButtonRouter.unregister(hardwareImageButtonHandler)
        hardwareImageButtonRegistered = false
    }

    companion object {
        const val EXTRA_AUTO_START = "gemini_live_auto_start"
        const val EXTRA_INITIAL_IMAGE_PATH = "gemini_live_initial_image_path"
        const val EXTRA_INITIAL_PROMPT = "gemini_live_initial_prompt"
    }
}
