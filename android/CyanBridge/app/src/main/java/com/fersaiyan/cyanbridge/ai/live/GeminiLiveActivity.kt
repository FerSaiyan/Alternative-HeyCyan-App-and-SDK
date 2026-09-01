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
    private lateinit var relayClient: GeminiLiveRelayClient
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
        relayClient = GeminiLiveRelayClient(this)
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
        // Pro → ephemeral token + GCP Vertex paid direct WS (no key to phone).
        // Free → server relay (phone → Vercel holds GEMINI_API_KEY → Google, no token to phone).
        val isPro = hasPaidPlan()
        useRelayForFreeTier = !isPro
        val message = if (isPro) {
            "Gemini Live (Pro) uses an ephemeral token + GCP Vertex paid route. Your microphone streams " +
                "directly to Google with a short-lived token (no API key on device). Glasses vision is streamed live."
        } else {
            "Gemini Live (Free) routes through CyanBridge server — your audio goes to Vercel first, " +
                "Vercel (holding the API key) talks to Google and returns the answer. No token or API key leaves the server. " +
                "Quality is HTTP relay; upgrade to Pro for direct low-latency ephemeral token + Vertex."
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
        val defaultImageQuestion = ImageQuestionPromptResolver.resolve(
            settings = ImageQuestionPreferences.get(this),
            userQuestion = null,
        ).forRoute(ImageQuestionRoute.PRO_RELAY)
        visionStatus = if (useRelayForFreeTier) "Glasses vision: relay (server holds key)" else "Glasses vision: preparing"
        renderIndicators()

        if (useRelayForFreeTier) {
            // Free tier: no ephemeral token. Server relay holds GEMINI_API_KEY/GCP Vertex.
            // Direct WS is Pro-only. Here we keep Live WS idle and rely on push-to-talk relay.
            status.text = "Free relay ready — tap glasses button to send audio via server"
            // Do not start direct WS; relay uses HTTP POST per utterance.
            return
        }

        // Pro: ephemeral token + GCP Vertex paid direct WS (bidiGenerateContentSetup).
        // Streaming-capable glasses can negotiate/start their camera while the relay token and
        // Gemini WebSocket setup happen. HeyCyan's opportunistic mode performs no capture here;
        // its audible still is triggered only after a real user-speech window starts.
        visionController.start()
        client.start(language, defaultImageQuestion)
    }

    private fun captureHardwareImageQuestion() {
        // Free tier uses relay (no WS listening), so allow capture even without liveListening
        if (useRelayForFreeTier) {
            if (!hardwareImageCaptureInProgress.compareAndSet(false, true)) return
            status.text = "Receiving glasses AI photo (relay)"
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { GeminiLiveGlassesImageCapture().captureFromHardwareButton() }
                }
                result
                    .onSuccess { image ->
                        // Free relay: send image via HTTP relay (server holds GEMINI_API_KEY)
                        val base64 = android.util.Base64.encodeToString(image, android.util.Base64.NO_WRAP)
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching {
                                relayClient.sendAudioAndGetText(
                                    pcm16 = shortArrayOf(), // no audio, image only
                                    prompt = "Describe this image for the free relay user.",
                                    imageJpegBase64 = base64,
                                )
                            }.onSuccess { text ->
                                runOnUiThread {
                                    status.text = text.take(200)
                                    visionStatus = "Glasses vision: relay image answered"
                                    renderIndicators()
                                }
                            }.onFailure { e ->
                                runOnUiThread { Toast.makeText(this@GeminiLiveActivity, e.message ?: "Relay failed", Toast.LENGTH_LONG).show() }
                            }
                        }
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
            return
        }
        if (!liveListening) return
        if (!hardwareImageCaptureInProgress.compareAndSet(false, true)) return
        status.text = "Receiving glasses AI photo"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { GeminiLiveGlassesImageCapture().captureFromHardwareButton() }
            }
            result
                .onSuccess { image ->
                    client.sendImage(image)
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
        if (!input && text.isNotBlank()) {
            android.util.Log.d("GeminiLiveActivity", "Gemini transcription: $text")
        }
    }

    override fun onSetupComplete() {
        // Audio capture is gated by setupComplete; the glasses camera may already be warm.
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
}
