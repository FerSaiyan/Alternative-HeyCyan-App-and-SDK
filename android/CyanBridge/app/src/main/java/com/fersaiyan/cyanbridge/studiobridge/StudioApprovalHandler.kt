package com.fersaiyan.cyanbridge.studiobridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.audio.TtsSpeaker
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiClient
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles approval requests from the Studio bridge:
 * 1. Speaks a TTS prompt on the Bluetooth speaker
 * 2. Captures the user's voice response via STT
 * 3. Classifies the response using an LLM (supports any language/slang)
 * 4. Sends the allow/deny decision back to the desktop
 */
class StudioApprovalHandler(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tts: TtsSpeaker? = null
    private val processing = AtomicBoolean(false)

    fun initialize() {
        tts = TtsSpeaker(appContext, TAG, onInit = { speaker, ok -> if (ok) speaker.setLanguage(Locale.US) })
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        scope.cancel()
    }

    /**
     * Called by [StudioBridgeClient] when an approval request arrives.
     * Runs the full TTS -> STT -> LLM classify -> respond pipeline.
     */
    suspend fun handleApprovalRequest(
        context: Context,
        approvalId: String,
        sessionId: String,
        toolName: String,
        toolArgsSummary: String,
        dangerLevel: String,
        ttsText: String,
    ) {
        if (processing.getAndSet(true)) {
            Log.w(TAG, "Already processing an approval; auto-denying new one")
            StudioBridgeClient.sendApprovalResponse(approvalId, "deny", sessionId)
            return
        }

        try {
            if (!canCaptureVoice(context)) {
                Log.w(TAG, "Voice approval unavailable: microphone permission or recognizer missing")
                speakBestEffort("Voice approval is unavailable. Open CyanBridge and enable microphone access.")
                StudioBridgeClient.sendApprovalResponse(approvalId, "deny", sessionId)
                return
            }
            val prompt = ttsText.ifBlank {
                buildString {
                    append("Agent wants to run ")
                    append(toolName)
                    if (toolArgsSummary.isNotBlank()) {
                        append(". ")
                        append(toolArgsSummary)
                    }
                    append(". Approve?")
                }
            }

            var lastDecision = "deny"

            for (attempt in 0..MAX_CLARIFY_RETRIES) {
                // Step 1: TTS prompt (only full prompt on first attempt)
                if (attempt == 0) {
                    speakAndWait(prompt)
                } else {
                    speakAndWait("I didn't catch that. Please say yes or no.")
                }

                // Step 2: STT capture (with timeout)
                val userSpeech = withTimeoutOrNull(STT_TIMEOUT_MS) {
                    captureVoiceResponse(context)
                }

                if (userSpeech.isNullOrBlank()) {
                    Log.i(TAG, "No speech captured on attempt $attempt")
                    if (attempt >= MAX_CLARIFY_RETRIES) {
                        StudioBridgeClient.sendApprovalResponse(approvalId, "deny", sessionId)
                        speakBestEffort("No response received. Denied.")
                        return
                    }
                    continue
                }

                Log.i(TAG, "User said (attempt $attempt): $userSpeech")

                // Step 3: LLM classification
                lastDecision = classifyResponse(userSpeech, toolName, toolArgsSummary)
                Log.i(TAG, "LLM classified as: $lastDecision (attempt $attempt)")

                if (lastDecision != "clarify") break

                // If clarify and we have retries left, loop again.
                if (attempt < MAX_CLARIFY_RETRIES) {
                    speakBestEffort("I'm not sure if that was a yes or no.")
                }
            }

            // Step 4: Send response
            StudioBridgeClient.sendApprovalResponse(approvalId, lastDecision, sessionId)

            val feedback = when (lastDecision) {
                "allow" -> "Approved."
                "deny" -> "Denied."
                else -> "No clear response. Denied to be safe."
            }
            speakBestEffort(feedback)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling approval: ${e.message}", e)
            StudioBridgeClient.sendApprovalResponse(approvalId, "deny", sessionId)
        } finally {
            processing.set(false)
        }
    }

    /**
     * Handle generic session events (completed, failed, etc.) with TTS.
     */
    fun handleSessionEvent(context: Context, event: String, message: String) {
        val text = when (event) {
            "completed" -> message.ifBlank { "Agent session completed." }
            "failed" -> message.ifBlank { "Agent session failed." }
            "stuck" -> message.ifBlank { "Agent session is stuck." }
            else -> message.ifBlank { return }
        }
        scope.launch { speakBestEffort(text) }
    }

    // -----------------------------------------------------------------------
    // TTS
    // -----------------------------------------------------------------------

    private suspend fun speakAndWait(text: String) {
        val speaker = tts
        if (speaker == null || !speaker.awaitReady(3_000)) {
            Log.w(TAG, "TTS not ready")
            return
        }
        speaker.speakAndAwait(
            text = text,
            utteranceId = "studio_approval_${System.currentTimeMillis()}",
            readyTimeoutMs = 0L,
            timeoutMs = 15_000,
        )
    }

    private suspend fun speakBestEffort(text: String) {
        val speaker = tts ?: return
        if (!speaker.awaitReady(3_000)) return
        speaker.speak(text, "studio_event")
    }

    // -----------------------------------------------------------------------
    // STT
    // -----------------------------------------------------------------------

    private suspend fun captureVoiceResponse(context: Context): String? {
        if (!canCaptureVoice(context)) return null
        return withContext(Dispatchers.Main) {
            val session = AudioSessionCoordinator.tryOpen(context, AudioSessionRequest.VoiceCapture(TAG))
                ?: return@withContext null
            try {
                session.connectRoute()
                recognizeOnce(context)
            } finally {
                session.close()
            }
        }
    }

    private suspend fun recognizeOnce(context: Context): String? =
        suspendCancellableCoroutine { cont ->
            var recognizer: SpeechRecognizer? = null
            var finished = false

            fun finish(result: String?) {
                if (finished) return
                finished = true
                runCatching { recognizer?.destroy() }
                recognizer = null
                if (cont.isActive) {
                    cont.resume(result?.trim()?.takeIf { it.isNotBlank() }) {}
                }
            }
            cont.invokeOnCancellation { finish(null) }

            // Beep to indicate listening
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                scope.launch {
                    delay(250)
                    runCatching { tone.release() }
                }
            }

            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                }

                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        Log.w(TAG, "STT error: $error")
                        finish(null)
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        finish(matches?.firstOrNull())
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                recognizer?.startListening(intent)

                // Timeout: if STT doesn't finish within the window, auto-deny
                scope.launch {
                    delay(STT_TIMEOUT_MS)
                    finish(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "STT init failed: ${e.message}")
                finish(null)
            }
        }

    // -----------------------------------------------------------------------
    // LLM Classification
    // -----------------------------------------------------------------------

    /**
     * Use the remote LLM to classify the user's voice response.
     * Returns "allow", "deny", or "clarify" (if ambiguous).
     * Handles any language, slang, dialect, or informal expression.
     * Robust to reasoning models that emit <think> tags or extended thinking.
     */
    private suspend fun classifyResponse(
        userSpeech: String,
        toolName: String,
        toolArgsSummary: String,
    ): String {
        val actionContext = buildString {
            append("The AI agent wants to run: $toolName")
            if (toolArgsSummary.isNotBlank()) {
                append(". Details: $toolArgsSummary")
            }
        }

        val messages = listOf(
            mapOf(
                "role" to "system",
                "content" to CLASSIFY_SYSTEM_PROMPT,
            ),
            mapOf(
                "role" to "user",
                "content" to "Action context: $actionContext\n\nUser said: \"$userSpeech\"\n\nClassify the user's intent.",
            ),
        )

        return try {
            val response = RemoteOpenAiClient.chatCompletion(
                context = appContext,
                messages = messages,
                maxTokens = 256,
                temperature = 0.0,
            )
            parseClassification(response)
        } catch (e: Exception) {
            Log.w(TAG, "LLM classification failed: ${e.message}; defaulting to deny")
            "deny"
        }
    }

    /**
     * Robustly parse the classification from the LLM response.
     * Handles:
     * - Clean JSON: {"decision":"allow"}
     * - Reasoning models with <think>...</think> tags
     * - Models that wrap JSON in markdown code blocks
     * - Models with <answer>...</answer> tags
     * - Fallback keyword scan over the entire response
     */
    internal fun parseClassification(raw: String): String {
        // Step 1: Try to extract a JSON object from the response.
        val jsonDecision = extractJsonDecision(raw)
        if (jsonDecision != null) return jsonDecision

        // Step 2: Fallback - scan the raw text for decision keywords.
        // Strip known reasoning wrappers first.
        val stripped = stripReasoningWrappers(raw).lowercase()

        // Look for explicit "decision" field patterns even without valid JSON.
        val decisionMatch = Regex("decision[\"':\\s]+(allow|deny|clarify)")
            .find(stripped)
            ?.groupValues?.getOrNull(1)
        if (decisionMatch != null) return decisionMatch

        // Last resort: keyword heuristics on the full text.
        return keywordClassify(stripped)
    }

    /**
     * Try to find and parse a JSON object containing a "decision" field.
     * Scans for { ... } blocks of increasing size.
     */
    private fun extractJsonDecision(raw: String): String? {
        // Try markdown code blocks first: ```json ... ``` or ``` ... ```
        val codeBlock = Regex("```(?:json)?\\s*\\n?(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.getOrNull(1)
        if (codeBlock != null) {
            val parsed = tryParseJsonObject(codeBlock)
            if (parsed != null) {
                val decision = parsed.optString("decision", "").lowercase().trim()
                if (decision in VALID_DECISIONS) return decision
            }
        }

        // Try <answer> tags
        val answerTag = Regex("<answer>\\s*(\\{.*?})\\s*</answer>", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.getOrNull(1)
        if (answerTag != null) {
            val parsed = tryParseJsonObject(answerTag)
            if (parsed != null) {
                val decision = parsed.optString("decision", "").lowercase().trim()
                if (decision in VALID_DECISIONS) return decision
            }
        }

        // Try to find any JSON object with a "decision" key by scanning for { ... }
        var depth = 0
        var start = -1
        for (i in raw.indices) {
            when (raw[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val candidate = raw.substring(start, i + 1)
                        val parsed = tryParseJsonObject(candidate)
                        if (parsed != null) {
                            val decision = parsed.optString("decision", "").lowercase().trim()
                            if (decision in VALID_DECISIONS) return decision
                        }
                        start = -1
                    }
                }
            }
        }

        return null
    }

    private fun tryParseJsonObject(text: String): org.json.JSONObject? {
        return try {
            org.json.JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Strip common reasoning-model wrappers from the response.
     */
    private fun stripReasoningWrappers(raw: String): String {
        var text = raw
        // Strip <think>...</think>
        text = text.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        // Strip <reasoning>...</reasoning>
        text = text.replace(Regex("<reasoning>.*?</reasoning>", RegexOption.DOT_MATCHES_ALL), "")
        // Strip <scratch>...</scratch>
        text = text.replace(Regex("<scratch>.*?</scratch>", RegexOption.DOT_MATCHES_ALL), "")
        return text.trim()
    }

    /**
     * Last-resort keyword classification on stripped text.
     */
    private fun keywordClassify(text: String): String {
        // Check for explicit negation first.
        val denyPatterns = listOf(
            "\\bdeny\\b", "\\bdenied\\b", "\\bdenying\\b",
            "\\brefuse\\b", "\\brefused\\b", "\\breject\\b", "\\brejected\\b",
            "\\bno\\b", "\\bnope\\b", "\\bstop\\b", "\\bcancel\\b",
            "\\bnot\\s+now\\b", "\\bnot\\s+sure\\b", "\\bhold\\s+on\\b", "\\bwait\\b",
        )
        val allowPatterns = listOf(
            "\\ballow\\b", "\\ballowed\\b", "\\bapprove\\b", "\\bapproved\\b",
            "\\byes\\b", "\\byeah\\b", "\\byep\\b", "\\byeah\\b",
            "\\bsure\\b", "\\bgo\\s+ahead\\b", "\\bdo\\s+it\\b",
            "\\bof\\s+course\\b", "\\bgo\\b", "\\bconfirm\\b",
        )

        val hasDeny = denyPatterns.any { Regex(it).containsMatchIn(text) }
        val hasAllow = allowPatterns.any { Regex(it).containsMatchIn(text) }

        return when {
            hasDeny && !hasAllow -> "deny"
            hasAllow && !hasDeny -> "allow"
            hasDeny && hasAllow -> "clarify"
            else -> "clarify"
        }
    }

    companion object {
        private const val TAG = "StudioApprovalHandler"
        private const val STT_TIMEOUT_MS = 12_000L
        private const val MAX_CLARIFY_RETRIES = 2
        private val VALID_DECISIONS = setOf("allow", "deny", "clarify")

        fun canCaptureVoice(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED &&
                SpeechRecognizer.isRecognitionAvailable(context)
        }

        private val CLASSIFY_SYSTEM_PROMPT = """
You are an intent classifier. A user was asked to approve or deny an AI agent action.
The user may reply in ANY language, slang, dialect, or informal expression.

Your job: determine if the user APPROVED, DENIED, or gave an AMBIGUOUS response.

You MUST respond with ONLY a JSON object (no markdown, no explanation):
{"decision":"allow","confidence":0.95,"reasoning":"brief reason"}

The "decision" field MUST be one of: "allow", "deny", "clarify"
The "confidence" field is a float from 0.0 to 1.0.
The "reasoning" field is a short explanation (max 30 words).

Use "clarify" when:
- The user said something unrelated to the question
- The user asked a question back instead of answering
- The response is genuinely ambiguous (not just informal)
- The speech recognition captured noise or unintelligible text

APPROVAL examples (set decision to "allow"):
- English: yes, yeah, yeap, yep, sure, go ahead, do it, approve, allow, of course, go for it, yessir, yup, absolutely, definitely, why not
- Portuguese: sim, pode, bora, bora bora, pode falar, vai, claro, manda bala, vai em frente, com certeza, bora la
- Spanish: si, dale, claro, adelante, senor, va, andale, pues si, dale pues, como no
- French: oui, allez, vas-y, bien sur, d'accord, allez-y, ouais
- German: ja, klar, los, mach, einverstanden, aber sicher, na logo
- Japanese: hai, OK, ii yo, zenzen, shouchi
- Chinese: hao, keyi, xing, mei wenti, dang ran, xing a
- Korean: ne, joha, geurae, mullon, arayo
- Arabic: naam, etmaan, ahlilan, tabaan, ma mushkila

DENIAL examples (set decision to "deny"):
- English: no, nope, deny, don't, stop, cancel, wait, hold on, not now, never, absolutely not
- Portuguese: nao, para, cancela, espera, de jeito nenhum, nem pensar, de forma alguma
- Spanish: no, para, cancela, espera, ni loco, ni de broma, para nada
- French: non, arrete, attends, pas maintenant, jamais, en aucun cas
- German: nein, stopp, warte, lass das, auf keinen fall, niemals
- Japanese: iya, matte, dame, chigau, zenzen dame
- Chinese: buyao, bie, bu xing, bu yao, jue bu
- Korean: ani, jamkkan, andwae, an doe, jeoldae
- Arabic: la, istanna, mumtane, abadan, la aqrar
        """.trimIndent()
    }
}
