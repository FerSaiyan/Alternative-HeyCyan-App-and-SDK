package com.fersaiyan.cyanbridge.ai.questions

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationInspector
import com.fersaiyan.cyanbridge.ai.image.GlassesQuestionTestHooks
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionSource
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionSourcePolicy
import com.fersaiyan.cyanbridge.ai.router.AssistantIntent
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.ai.router.MediaInferenceRoutingPolicy
import com.fersaiyan.cyanbridge.ai.router.QuestionDecisionShadow
import com.fersaiyan.cyanbridge.ai.router.QuestionRoutingPrefs
import com.fersaiyan.cyanbridge.ai.router.RelayErrorLocalizer
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPromptResolver
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionRoute
import com.fersaiyan.cyanbridge.ai.vision.ResolvedImageQuestionPrompt
import com.fersaiyan.cyanbridge.localagent.memory.RagProfile
import com.fersaiyan.cyanbridge.media.sync.TransferProgressPresenter
import com.fersaiyan.cyanbridge.shared.ai.GlassesAssistantRoute
import com.fersaiyan.cyanbridge.shared.assistant.AssistantQuestionRouting
import com.fersaiyan.cyanbridge.shared.assistant.CapturedQuestion
import com.fersaiyan.cyanbridge.shared.assistant.VisionReply
import com.fersaiyan.cyanbridge.shared.assistant.VisionReplyPolicy
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.shared.settings.QuestionRoutingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The answer half of an image question: the spoken question asked while the photo transfers (or
 * right after it), the photo checks, the chosen provider's answer spoken aloud, and follow-up
 * questions about the same photo while the user keeps asking. Gemini Live and the phone
 * assistant take over before the in-app answer when they are the route.
 */
internal class ImageQuestionFlow(
    private val env: QuestionEnvironment,
    private val capture: () -> ImageQuestionCapture,
    private val speaker: AssistantSpeaker,
    private val microphone: QuestionMicrophone,
    private val providers: AssistantProviderQueries,
    private val live: GeminiLiveQuestions,
    private val external: ExternalAssistantHandoff,
    private val foreground: QuestionForegroundWork,
    private val route: () -> GlassesAssistantRoute,
    private val spokenQuestionProvider: () -> AgentProviderType,
    private val stopGlassesAiAudio: (source: String) -> Unit,
    private val cancelLocalStreamingSpeech: (reason: String) -> Unit,
    private val languageTag: () -> String,
) {
    /** A voice question routed to the camera (Classic ANALYZE_IMAGE): asked again with the photo. */
    @Volatile var pendingVoiceImageQuestion: String? = null

    /** Receives the next captured glasses photo for a decide-once question instead of the image flow. */
    @Volatile var pendingPhotoSink: CompletableDeferred<String?>? = null

    val imageQueryInProgress = AtomicBoolean(false)
    private var lastImageQueryAtMs: Long = 0L
    private var activeParallelAudioQuestionDeferred: CompletableDeferred<CapturedQuestion?>? = null
    private var activeParallelAudioQuestionJob: Job? = null

    val parallelQuestionActive: Boolean get() = activeParallelAudioQuestionJob?.isActive == true

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(env.context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancelParallelAudioQuestion() {
        val job = activeParallelAudioQuestionJob
        activeParallelAudioQuestionJob = null
        job?.cancel()
        val deferred = activeParallelAudioQuestionDeferred
        activeParallelAudioQuestionDeferred = null
        if (deferred?.isCompleted == true) runCatching { deferred.getCompleted()?.audioPath?.let { File(it).delete() } }
        if (deferred?.isCompleted == false) {
            deferred.complete(null)
        }
    }

    fun startParallelAudioQuestionIfEligible(offerSpokenQuestion: Boolean) {
        // Gemini Live handles voice continuously after the session is listening.
        // Skipping the recorded Ask window here keeps local/tasker/
        // non-Live Pro (e.g. gemini 3.7 flash) parallel behavior unchanged while
        // Live takes a photo and shows Initializing Live instead.
        if (live.isRoutedForImageQuestion()) {
            cancelParallelAudioQuestion()
            capture().pendingOfferSpokenQuestion = false
            Log.i("ImageQuestionAudio", "Skipping parallel Ask window for Gemini Live route")
            return
        }
        cancelParallelAudioQuestion()
        if (
            offerSpokenQuestion &&
            pendingVoiceImageQuestion.isNullOrBlank() &&
            hasMicrophonePermission()
        ) {
            val deferred = CompletableDeferred<CapturedQuestion?>()
            activeParallelAudioQuestionDeferred = deferred
            activeParallelAudioQuestionJob = env.scope.launch(Dispatchers.Main) {
                // 500 ms settling delay for photo capture command & hardware shutter sound
                delay(500L)
                if (!deferred.isCompleted && !deferred.isCancelled) {
                    askNowToast()
                    val spokenQuestion = microphone.capture(timeoutMs = INITIAL_LISTENING_TIMEOUT_MS)
                    if (!deferred.complete(spokenQuestion)) spokenQuestion.audioPath?.let { File(it).delete() }
                }
            }
        }
    }

    private fun askNowToast() {
        Toast.makeText(
            env.context,
            "Ask about the image now, or wait for the default description.",
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun clearPendingVoiceImageQuestion(sourceTag: String) {
        if (sourceTag == VOICE_REQUEST_TAG) {
            pendingVoiceImageQuestion = null
        }
        if (sourceTag == DECIDE_ONCE_PHOTO_TAG) {
            pendingPhotoSink?.complete(null)
            pendingPhotoSink = null
        }
        capture().pendingOfferSpokenQuestion = false
        cancelParallelAudioQuestion()
    }

    private data class ImageMetrics(
        val width: Int,
        val height: Int,
        val bytes: Long,
    )

    private fun readImageMetrics(file: File): ImageMetrics? {
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                null
            } else {
                ImageMetrics(options.outWidth, options.outHeight, file.length())
            }
        }.getOrNull()
    }

    fun isDecodableImageFile(file: File): Boolean = readImageMetrics(file) != null

    fun onImageReadyForQuestion(
        imagePath: String,
        source: ImageQuestionSource,
        transferDurationMs: Long,
    ) {
        pendingPhotoSink?.let { sink ->
            // A decide-once question asked for this photo; it answers in its own conversation.
            pendingPhotoSink = null
            val usable = readImageMetrics(File(imagePath)) != null
            Log.i("AIHijack", "Photo delivered to decide-once question usable=$usable")
            if (!sink.complete(if (usable) imagePath else null)) File(imagePath).delete()
            return
        }
        val context = env.context
        val imageFile = File(imagePath)
        val metrics = readImageMetrics(imageFile)
        if (metrics == null) {
            pendingVoiceImageQuestion = null
            Log.e("AIHijack", "Image file is missing or invalid: $imagePath (${imageFile.length()} bytes)")
            env.runOnUiThread {
                Toast.makeText(context, "Image transfer was incomplete. Please try again.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val ageMs = System.currentTimeMillis() - imageFile.lastModified()
        if (ageMs > MAX_IMAGE_AGE_MS || ageMs < 0) {
            pendingVoiceImageQuestion = null
            Log.w("AIHijack", "Image too old: age=${ageMs / 1000}s, path=$imagePath")
            env.runOnUiThread {
                Toast.makeText(
                    context,
                    "Image is ${ageMs / 60000} min old — too old to use.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }

        val thumbnailQuality = capture().pendingThumbnailQuality
        val sourceLabel = if (source == ImageQuestionSource.FAST_PREVIEW) {
            "${thumbnailQuality.label} BLE preview"
        } else {
            source.label
        }
        val transferSummary = "$sourceLabel: ${metrics.width}x${metrics.height}, " +
            "${TransferProgressPresenter.formatTransferBytes(metrics.bytes)}, ${transferDurationMs.coerceAtLeast(0L)} ms"
        Log.i(
            "ImageQuestion",
            "Ready for AI provider: source=${source.wireName}, dimensions=${metrics.width}x${metrics.height}, " +
                "thumbnailQuality=${thumbnailQuality.label}, bytes=${metrics.bytes}, " +
                "transferDurationMs=${transferDurationMs.coerceAtLeast(0L)}, path=$imagePath",
        )
        env.runOnUiThread {
            Toast.makeText(context, transferSummary, Toast.LENGTH_LONG).show()
        }

        env.scope.launch(Dispatchers.IO) {
            var initialQuestion = pendingVoiceImageQuestion
            var captured: CapturedQuestion? = null
            pendingVoiceImageQuestion = null
            // Live routes own their continuous microphone session.
            // Non-Live (local, tasker, gemini 3.7 flash) keeps the parallel + sequential windows.
            val isLiveRoute = withContext(Dispatchers.Main) { live.isRoutedForImageQuestion() }
            if (isLiveRoute) {
                withContext(Dispatchers.Main) { cancelParallelAudioQuestion() }
                Log.i("ImageQuestion", "Live route: skipping legacy Ask windows, using system image prompt")
            } else if (initialQuestion.isNullOrBlank()) {
                val parallelDeferred = activeParallelAudioQuestionDeferred
                activeParallelAudioQuestionDeferred = null
                activeParallelAudioQuestionJob = null
                if (parallelDeferred != null) {
                    Log.i("ImageQuestion", "Awaiting parallel audio question recording...")
                    captured = parallelDeferred.await()
                    initialQuestion = captured?.text
                } else if (hasMicrophonePermission()) {
                    withContext(Dispatchers.Main) { askNowToast() }
                    captured = microphone.capture(timeoutMs = INITIAL_LISTENING_TIMEOUT_MS)
                    initialQuestion = captured.text
                }
            } else {
                cancelParallelAudioQuestion()
            }
            if (captured?.error != null) {
                withContext(Dispatchers.Main) { speaker.speak(captured.error!!); foreground.finish() }
                return@launch
            }
            if (!initialQuestion.isNullOrBlank() && QuestionRoutingPrefs.mode(env.context) == QuestionRoutingMode.SHADOW) {
                val shadowText = initialQuestion
                env.scope.launch(Dispatchers.Default) {
                    QuestionDecisionShadow.log(shadowText, flow = "image", classic = AssistantIntent.ANALYZE_IMAGE)
                }
            }
            val externalAutomation = external.usesExternalAssistantUi()
            fun offerFollowUp() {
                env.scope.launch {
                    delay(500L)
                    val followUp = microphone.capture(timeoutMs = INITIAL_LISTENING_TIMEOUT_MS)
                    val error = followUp.error
                    if (error != null) {
                        speaker.speak(error)
                    } else if (followUp.heardSpeech) {
                        triggerAssistantImageQuery(
                            imagePath = imagePath,
                            userQuestion = followUp.text,
                            audioPath = followUp.audioPath,
                            source = source,
                            onReplySpoken = ::offerFollowUp,
                        )
                    }
                }
            }
            // External assistant apps own response playback and their own conversation UI.
            triggerAssistantImageQuery(
                imagePath = imagePath,
                userQuestion = initialQuestion,
                audioPath = captured?.audioPath,
                source = source,
                onReplySpoken = if (externalAutomation) null else ::offerFollowUp,
            )
        }
    }

    private fun resolvePrompt(userQuestion: String?): ResolvedImageQuestionPrompt =
        ImageQuestionPromptResolver.resolve(
            settings = ImageQuestionPreferences.get(env.context),
            userQuestion = userQuestion,
        )

    fun triggerAssistantImageQuery(
        imagePath: String,
        userQuestion: String? = null,
        audioPath: String? = null,
        source: ImageQuestionSource = ImageQuestionSourcePolicy.defaultSource(),
        onReplySpoken: (() -> Unit)? = null,
    ) {
        val context = env.context
        // Debounce: prevent duplicate requests within 5 seconds
        val now = System.currentTimeMillis()
        if (now - lastImageQueryAtMs < 5000) {
            audioPath?.let { File(it).delete() }
            Log.w("AIHijack", "Image query debounced (last was ${now - lastImageQueryAtMs}ms ago)")
            return
        }

        // Guard against concurrent requests
        if (!imageQueryInProgress.compareAndSet(false, true)) {
            audioPath?.let { File(it).delete() }
            Log.w("AIHijack", "Image query already in progress; treating duplicate action as barge-in")
            cancelLocalStreamingSpeech("duplicate image-query action")
            imageQueryInProgress.set(false)
            return
        }
        foreground.begin("Analyzing glasses image")
        foreground.armLocalModelLoadCue()

        lastImageQueryAtMs = now
        GlassesQuestionTestHooks.emit(
            "image_query",
            "question=${!userQuestion.isNullOrBlank()} audio=${audioPath != null} source=${source.wireName}",
        )
        val resolvedPrompt = resolvePrompt(userQuestion)

        val externalReason = if (audioPath == null) external.imageAutomationUnsupportedReason() else null
        if (externalReason != null) {
            audioPath?.let { File(it).delete() }
            Toast.makeText(context, externalReason, Toast.LENGTH_LONG).show()
            if (external.usesExternalAssistantUi() && ExternalAssistantAutomationInspector.inspect(context).phoneLocked) {
                speaker.speak(externalReason)
            }
            imageQueryInProgress.set(false)
            foreground.finish()
            return
        }

        val internalProvider = AssistantQuestionRouting.imageQuestionProvider(
            route = route(),
            recordedQuestion = audioPath != null,
            spokenProvider = spokenQuestionProvider,
            mediaProvider = { MediaInferenceRoutingPolicy.resolve(context) },
        )
        if (internalProvider != null) {
            triggerChosenProviderImageQuery(
                imagePath = imagePath,
                providerType = internalProvider,
                resolvedPrompt = resolvedPrompt,
                audioPath = audioPath,
                onReplySpoken = onReplySpoken,
            )
            return
        }

        Log.i("AIHijack", "Handing image query to external assistant: $imagePath")
        try {
            stopGlassesAiAudio("image-query command")
            external.handOffImage(
                imagePath = imagePath,
                userQuestion = userQuestion,
                source = source,
            )
        } catch (error: Exception) {
            Log.e("AIHijack", "Failed to hand image to external assistant: ${error.message}", error)
            Toast.makeText(context, "Could not start the phone-assistant image question.", Toast.LENGTH_LONG).show()
        } finally {
            imageQueryInProgress.set(false)
            foreground.finish()
        }
    }

    private fun triggerChosenProviderImageQuery(
        imagePath: String,
        providerType: AgentProviderType,
        resolvedPrompt: ResolvedImageQuestionPrompt,
        audioPath: String? = null,
        onReplySpoken: (() -> Unit)? = null,
    ) {
        Log.i("AIHijack", "Running image query with RAG disabled for chosen provider $providerType: $imagePath")

        if (live.shouldUseForQuestions(providerType)) {
            // Live systemInstruction is permissive (97 languages, switch on request) - use base question
            // without strict "Answer only in X". Single-shot PRO_RELAY keeps strict via forRoute.
            val liveBasePrompt = ImageQuestionPromptResolver.baseQuestion(
                settings = ImageQuestionPreferences.get(env.context),
                userQuestion = null,
            ).ifBlank { resolvedPrompt.text.substringBefore("\n\n") }
            live.launch(
                prompt = liveBasePrompt,
                imagePath = imagePath,
                onLaunched = {
                    imageQueryInProgress.set(false)
                    foreground.finish()
                },
            )
            return
        }

        val streaming = speaker.streaming
        val onSpeechCompleted: () -> Unit = {
            foreground.finish()
            onReplySpoken?.invoke()
            Unit
        }
        val localSpeechSessionId = if (providerType == AgentProviderType.LOCAL_AGENT) {
            speaker.answerPlayback(true)
            streaming.startNewSession(
                languageTag = ImageQuestionPreferences.get(env.context).appLanguageTag,
                onSpeechCompleted = {
                    speaker.answerPlayback(false)
                    onSpeechCompleted()
                },
            )
        } else {
            null
        }

        val queryJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val finalReply = when (providerType) {
                    AgentProviderType.PRO_SUBSCRIPTION -> {
                        val context = env.context
                        // Continuous Gemini Live selections return before this one-shot query is
                        // created. Match main for an active Pro user's selected non-Live model.
                        val questionsModel = ProSubscriptionAiPrefs.getQuestionsModel(context)
                        // A spoken question recorded with the photo goes along as audio (photo + bare audio).
                        val visionResult = CliRelayClient.imageQuery(
                            context = context,
                            imagePath = imagePath,
                            prompt = if (audioPath == null) resolvedPrompt.forRoute(ImageQuestionRoute.PRO_RELAY) else null,
                            modelOverride = questionsModel,
                            audioPath = audioPath,
                            spokenQuestion = audioPath != null,
                            systemContext = audioPath?.let {
                                ImageQuestionDefaults.spokenQuestionInstruction(languageTag()) + " " +
                                    ImageQuestionDefaults.currentContextInstruction()
                            },
                        )
                        relayReply(visionResult, detectVisionFailure = true)
                    }

                    AgentProviderType.LOCAL_AGENT -> {
                        var receivedModelText = false
                        providers.query(
                            // A spoken question goes as photo + bare audio (spokenQuestionInstruction).
                            userPrompt = if (audioPath == null) resolvedPrompt.forRoute(ImageQuestionRoute.LOCAL_GEMMA) else "",
                            providerType = AgentProviderType.LOCAL_AGENT,
                            ragProfile = RagProfile.NONE,
                            imagePaths = listOf(imagePath),
                            audioPath = audioPath,
                            onToken = { fragment ->
                                receivedModelText = true
                                localSpeechSessionId?.let { sessionId ->
                                    streaming.onModelTokenDelta(fragment, sessionId)
                                }
                            },
                        )
                            .also { reply ->
                                if (!receivedModelText) {
                                    localSpeechSessionId?.let { sessionId ->
                                        streaming.onModelTokenDelta(reply, sessionId)
                                    }
                                }
                            }
                    }

                    AgentProviderType.TASKER -> {
                        val visionResult = CliRelayClient.imageQuery(
                            context = env.context,
                            imagePath = imagePath,
                            prompt = resolvedPrompt.forRoute(ImageQuestionRoute.TASKER_GEMINI),
                        )
                        relayReply(visionResult, detectVisionFailure = false)
                    }
                }

                val replyToSpeak = VisionReplyPolicy.spoken(finalReply)
                if (providerType == AgentProviderType.LOCAL_AGENT && finalReply.isBlank()) {
                    localSpeechSessionId?.let { sessionId ->
                        streaming.onModelTokenDelta(replyToSpeak, sessionId)
                    }
                }
                Log.i(
                    "AIHijack",
                    "Image query completed provider=$providerType replyLength=${replyToSpeak.length}",
                )
                env.runOnUiThread {
                    if (providerType == AgentProviderType.LOCAL_AGENT) {
                        speaker.answerShown(replyToSpeak)
                        localSpeechSessionId?.let(streaming::onModelGenerationCompleted)
                    } else {
                        speaker.speakVision(replyToSpeak, onDone = onSpeechCompleted)
                    }
                }
            } finally {
                audioPath?.let { File(it).delete() }
                imageQueryInProgress.set(false)
            }
        }
        localSpeechSessionId?.let { sessionId ->
            streaming.attachGenerationJob(sessionId, queryJob)
        }
    }

    /** A relay photo answer: failures and unusable replies become a short sentence and a toast. */
    private fun relayReply(result: Result<String>, detectVisionFailure: Boolean): String {
        val context = env.context
        val reply: VisionReply = if (result.isFailure) {
            val throwable = result.exceptionOrNull() ?: Exception("unknown error")
            Log.e("AIHijack", "Image query failed: ${throwable.message}")
            VisionReplyPolicy.relayFailure(
                isQuota = RelayErrorLocalizer.isQuotaError(throwable),
                localizedMessage = RelayErrorLocalizer.localizedMessage(context, throwable),
                errorMessage = throwable.message,
            )
        } else {
            VisionReplyPolicy.relayReply(result.getOrNull(), detectVisionFailure).also {
                if (it.toast != null) {
                    Log.w("AIHijack", "Vision relay couldn't process image. Reply: ${result.getOrNull()?.trim()?.take(100)}")
                }
            }
        }
        reply.toast?.let { message ->
            env.runOnUiThread { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        }
        return reply.spoken
    }

    companion object {
        const val VOICE_REQUEST_TAG = "voice_request"
        const val DECIDE_ONCE_PHOTO_TAG = "decide_once"
        private const val MAX_IMAGE_AGE_MS = 3L * 60L * 1000L
        const val INITIAL_LISTENING_TIMEOUT_MS = 3_300L
    }
}
