package com.fersaiyan.cyanbridge.ai.questions

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionRelayClient
import com.fersaiyan.cyanbridge.ai.AiQuestionForegroundService
import com.fersaiyan.cyanbridge.ai.AiWakeWordPreferences
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionSource
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionSourcePolicy
import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.ai.router.AssistantTestKind
import com.fersaiyan.cyanbridge.ai.router.QuestionRoutingPrefs
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.bridge.core.DisplayCommand
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridge
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.session.LocalModelLoadAnnouncer
import com.fersaiyan.cyanbridge.shared.ai.GlassesAssistantRoute
import com.fersaiyan.cyanbridge.shared.ai.GlassesAssistantRoutingPolicy
import com.fersaiyan.cyanbridge.shared.assistant.AssistantQuestionRouting
import com.fersaiyan.cyanbridge.shared.assistant.WakeQuestion
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.ui.main.GlassesSessionGuard
import com.fersaiyan.cyanbridge.ui.main.assistantSetupBlockingMessage
import com.oudmon.ble.base.communication.LargeDataHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Foreground and lock-screen steps around a question. */
internal interface QuestionForegroundWork {
    fun prepareForLockScreen()

    /** Shows over the lock screen and asks to dismiss the keyguard (voice questions). */
    fun wakeScreen()

    fun begin(status: String)

    fun finish()

    /** Once the question is recorded: "Initializing local model" if a cold load follows. */
    fun armLocalModelLoadCue()
}

/**
 * The glasses questions on Android: image questions (glasses AI-photo button, dashboard test,
 * wake words), spoken questions (Classic transcript route or the shared decision-model flow) and
 * the external routes (phone assistant, Tasker, Gemini Live). Process-wide: the main screen
 * attaches as [GlassesQuestionHost] for permissions, dialogs and its devices, and glasses buttons
 * can start questions without it ([askByVoiceInBackground], [askAboutPhotoInBackground]).
 */
internal class GlassesQuestions private constructor(context: Context) : QuestionEnvironment, QuestionForegroundWork {
    override val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile override var host: GlassesQuestionHost? = null
        private set

    override val context: Context get() = host?.activity ?: appContext

    @Volatile private var hostScope = newScope()
    override val scope: CoroutineScope get() = hostScope

    /** Outlives the main screen: background starts, display updates and command-timeout warnings. */
    private val processScope = newScope()

    val speaker: AssistantSpeaker = AssistantSpeaker(appContext)
    private val guard: GlassesSessionGuard = GlassesSessionGuard(appContext, ::selectedProfile, processScope)

    /** The dashboard's assistant mode, read from preferences when the screen is created. */
    @Volatile var assistantMode: GlassesAssistantMode = AutomationPrefs.getGlassesAssistantMode(appContext)

    private val microphone: QuestionMicrophone = QuestionMicrophone(
        context = appContext,
        speaker = speaker,
        scope = { scope },
        languageTag = ::languageTag,
        deviceAudio = { selectedProfile().audio },
        onQuestionRecorded = ::armLocalModelLoadCue,
        audioGoesToModel = { voice.questionAudioGoesToModel() },
    )
    private val providers: AssistantProviderQueries = AssistantProviderQueries({ context }, ::languageTag)
    private val live: GeminiLiveQuestions = GeminiLiveQuestions(this, speaker, ::currentRoute, ::languageTag)
    val external: ExternalAssistantHandoff = ExternalAssistantHandoff(
        env = this,
        speaker = speaker,
        route = ::currentRoute,
        stopGlassesAiAudio = ::stopGlassesAiAudio,
        wakeScreen = ::wakeScreen,
        prepareForLockScreen = ::prepareForLockScreen,
    )
    val imageCapture: ImageQuestionCapture = ImageQuestionCapture(this, guard, { imageFlow }, this)
    val imageFlow: ImageQuestionFlow = ImageQuestionFlow(
        env = this,
        capture = { imageCapture },
        speaker = speaker,
        microphone = microphone,
        providers = providers,
        live = live,
        external = external,
        foreground = this,
        route = ::currentRoute,
        spokenQuestionProvider = { voice.spokenQuestionProvider() },
        stopGlassesAiAudio = ::stopGlassesAiAudio,
        cancelLocalStreamingSpeech = ::cancelLocalStreamingSpeech,
        languageTag = ::languageTag,
    )
    private val voice: VoiceQuestionFlow = VoiceQuestionFlow(
        env = this,
        speaker = speaker,
        microphone = microphone,
        providers = providers,
        live = live,
        external = external,
        imageFlow = imageFlow,
        imageCapture = imageCapture,
        foreground = this,
        route = ::currentRoute,
        isGlassesCommandBlocked = guard::isGlassesCommandBlocked,
        isImageQuestionWorkActive = ::isImageQuestionWorkActive,
        stopGlassesAiAudio = ::stopGlassesAiAudio,
        cancelLocalStreamingSpeech = ::cancelLocalStreamingSpeech,
        languageTag = ::languageTag,
    )

    init {
        speaker.answerShown = ::showAnswerOnDisplay
    }

    // ---------------------------------------------------------------------------------------------
    // The main screen
    // ---------------------------------------------------------------------------------------------

    /** The main screen was created: it hosts the questions and owns the speech engine. */
    fun attachHost(host: GlassesQuestionHost) {
        this.host = host
        assistantMode = AutomationPrefs.getGlassesAssistantMode(host.activity)
    }

    /** Creates the speech engine on the screen (it used to be the screen's own engine). */
    fun startSpeech(host: GlassesQuestionHost) {
        speaker.start(host.activity)
    }

    /** The screen is being destroyed: its questions end, as they did with its lifecycle scope. */
    fun onHostDestroying(host: GlassesQuestionHost) {
        if (this.host !== host) return
        hostScope.cancel()
        hostScope = newScope()
        cancelLocalStreamingSpeech("activity destroyed")
        // Cancelling the screen's scope closes a listening question's audio session, which
        // restores the Bluetooth route unless another session (a meeting, Live) still uses it.
        val voiceQueryWasActive = voice.voiceQueryInProgress.getAndSet(null) != null
        if (voiceQueryWasActive) finish()
    }

    /** After the screen's own teardown: its speech engine stops. */
    fun detachHost(host: GlassesQuestionHost) {
        if (this.host !== host) return
        speaker.shutdown()
        this.host = null
    }

    override fun runOnUiThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // ---------------------------------------------------------------------------------------------
    // Routing state
    // ---------------------------------------------------------------------------------------------

    fun currentRoute(): GlassesAssistantRoute =
        GlassesAssistantRoutingPolicy.resolve(
            mode = assistantMode,
            customProvider = AutomationPrefs.getProviderType(context),
        )

    fun selectedProfile(): GlassesFamilyProfile = GlassesFamilyProfiles.forClass(DeviceProfileStore.selectedClass(appContext))

    fun languageTag(): String {
        val context = context
        return ImageQuestionPreferences.get(context).appLanguageTag.ifBlank {
            context.resources.configuration.locales[0]?.toLanguageTag().orEmpty()
                .ifBlank { Locale.getDefault().toLanguageTag() }
        }
    }

    fun isImageQuestionWorkActive(): Boolean {
        val screen = host
        return imageFlow.imageQueryInProgress.get() ||
            imageCapture.imageCaptureAwaitingNotification.get() ||
            imageCapture.thumbnailRequestInProgress.get() ||
            screen?.questionPhotoBlocksVoice() == true ||
            screen?.highQualityImageRequestActive == true ||
            imageFlow.parallelQuestionActive
    }

    // ---------------------------------------------------------------------------------------------
    // Entry points
    // ---------------------------------------------------------------------------------------------

    /** The dashboard's Test voice button (also the MemoMind and Mentra buttons while the screen exists). */
    fun startVoiceQuestionFromUi() {
        if (host?.showAssistantSetupIfNeeded(AssistantTestKind.VOICE) == true) return
        voice.triggerAssistantVoiceQuery()
    }

    /** The dashboard's Test image button. */
    fun startImageQuestionFromUi() {
        if (host?.showAssistantSetupIfNeeded(AssistantTestKind.IMAGE) == true) return
        if (external.showImageRequirementsWarningIfNeeded()) return
        imageCapture.handleGlassesImageButtonPressed(
            triggerCapture = true,
            sourceTag = "test_button",
            thumbnailQuality = imageCapture.pendingThumbnailQuality,
        )
    }

    /**
     * A glasses button asked a spoken question without the main screen (MemoMind, Mentra Live):
     * the same voice question as Test voice, with setup problems shown as a toast.
     */
    fun askByVoiceInBackground(source: String) {
        processScope.launch {
            Log.i("AIHijack", "Background voice question source=$source screen=${host != null}")
            if (!blockedInBackground(AssistantTestKind.VOICE)) voice.triggerAssistantVoiceQuery()
        }
    }

    /** A glasses button asked about what the glasses see, without the main screen ([photo] takes it). */
    fun askAboutPhotoInBackground(source: String, photo: QuestionPhotoSource) {
        processScope.launch {
            Log.i("AIHijack", "Background image question source=$source screen=${host != null}")
            if (blockedInBackground(AssistantTestKind.IMAGE) || external.showImageRequirementsWarningIfNeeded()) return@launch
            imageCapture.handleGlassesImageButtonPressed(
                triggerCapture = true,
                sourceTag = "${source}_button",
                thumbnailQuality = imageCapture.pendingThumbnailQuality,
                photo = photo,
            )
        }
    }

    /** The speech engine is ready, and the route needs no setup the user must do on the screen. */
    private suspend fun blockedInBackground(kind: AssistantTestKind): Boolean {
        if (!speaker.awaitStarted()) Log.w("AIHijack", "Speech engine not ready for the background question")
        val screen = host
        if (screen != null) return screen.showAssistantSetupIfNeeded(kind)
        val message = assistantSetupBlockingMessage(appContext, currentRoute(), kind) ?: return false
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        return true
    }

    /** Wake word or AI button: a decision-model question, the voice route or a photo question. */
    fun handleAiWakeWordActivation(source: String) {
        if (host?.isAiHijackEnabled == false) return
        val route = AiWakeWordPreferences.route(appContext)
        Log.i("AIHijack", "AI wake activation source=$source route=$route")
        runOnUiThread {
            if (source == "eyevue") {
                EyevueManager.getInstance(appContext).stopVoiceRecognition()
            } else {
                stopGlassesAiAudio("$source wake-word route")
            }
            // A voice question goes to the decision model whenever the answering model hears audio
            // (local Gemma 4, or an audio-capable Pro model); Settings > Question routing = Classic forces
            // the transcript router. A photo-mapped wake keeps the photo flow: photo + recording, no decision.
            when (AssistantQuestionRouting.wakeQuestion(route, QuestionRoutingPrefs.mode(appContext), voice::questionAudioGoesToModel)) {
                WakeQuestion.SPOKEN_DECISION -> {
                    Log.i("AIHijack", "Spoken decision question (route=$route provider=${voice.spokenQuestionProvider()})")
                    voice.triggerSpokenDecisionQuestion()
                }
                WakeQuestion.VOICE_QUESTION -> voice.triggerAssistantVoiceQuery()
                WakeQuestion.IMAGE_QUESTION -> imageCapture.handleGlassesImageButtonPressed(
                    triggerCapture = true,
                    sourceTag = "${source}_wake_word",
                    source = ImageQuestionSourcePolicy.defaultSource(),
                    thumbnailQuality = ImageQuestionSourcePolicy.defaultThumbnailQuality(),
                    offerSpokenQuestion = true,
                )
            }
        }
    }

    /** Debug builds: the glasses' AI photo button as instrumentation fires it. */
    fun debugPhotoButton() {
        imageCapture.handleGlassesImageButtonPressed(
            triggerCapture = false,
            sourceTag = "debug_photo_button",
            offerSpokenQuestion = true,
        )
    }

    /** HeyCyan's 0x02 notification: the AI photo is ready on the glasses. */
    fun onHeyCyanPhotoReady(payload: ByteArray) {
        imageCapture.onHeyCyanPhotoReady(
            payload = payload,
            isAiHijackEnabled = host?.isAiHijackEnabled ?: true,
            showImageRequirementsWarning = external::showImageRequirementsWarningIfNeeded,
        )
    }

    // Media sync callbacks (HeyCyan's Wi-Fi high-quality image).
    val pendingCaptureStartedAtMs: Long get() = imageCapture.pendingCaptureStartedAtMs
    var pendingImageQuestionSource: ImageQuestionSource
        get() = imageCapture.pendingImageQuestionSource
        set(value) {
            imageCapture.pendingImageQuestionSource = value
        }
    var thumbnailQuality: ImageThumbnailQuality
        get() = imageCapture.pendingThumbnailQuality
        set(value) {
            imageCapture.pendingThumbnailQuality = value
        }

    fun onImageReadyForQuestion(imagePath: String, source: ImageQuestionSource, transferDurationMs: Long) =
        imageFlow.onImageReadyForQuestion(imagePath, source, transferDurationMs)

    fun cancelParallelAudioQuestion() = imageFlow.cancelParallelAudioQuestion()

    fun requestImageThumbnailForQuestion(sourceTag: String) = imageCapture.requestImageThumbnailForQuestion(sourceTag)

    fun clearPendingVoiceImageQuestion(sourceTag: String) = imageFlow.clearPendingVoiceImageQuestion(sourceTag)

    fun isDecodableImageFile(file: File): Boolean = imageFlow.isDecodableImageFile(file)

    // ---------------------------------------------------------------------------------------------
    // Around a question
    // ---------------------------------------------------------------------------------------------

    /** Tells HeyCyan glasses to stop their own AI audio before the app answers. */
    fun stopGlassesAiAudio(source: String) {
        if (DeviceProfileStore.selectedClass(appContext) == DeviceClass.META_RAYBAN) {
            // Meta audio is managed by DAT/Android audio routing; never send Oudmon
            // command bytes to a Meta wearable.
            Log.d("AIHijack", "Skipping HeyCyan AI-audio stop for Meta ($source)")
            return
        }
        if (guard.isGlassesCommandBlocked(source)) return
        val permit = guard.acquireBackgroundGlassesCommand(source) ?: return
        try {
            LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x0b)) { _, _ ->
                GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            }
            guard.warnIfBackgroundGlassesCommandTimesOut(permit)
        } catch (e: Exception) {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            Log.e("AIHijack", "Failed to stop glasses AI audio for $source", e)
        }
    }

    fun cancelLocalStreamingSpeech(reason: String) {
        val streaming = speaker.streaming
        if (streaming.activeSessionId == 0L) return
        Log.i("AIHijack", "Cancelling local streaming speech: $reason")
        streaming.cancelActiveStreamingResponse()
        CoroutineScope(Dispatchers.IO).launch {
            LocalModelsProvider().cancelGeneration()
        }
    }

    override fun prepareForLockScreen() {
        val activity = host?.activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }
    }

    override fun wakeScreen() {
        val activity = host?.activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            val keyguardManager = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(activity, null)
        }
    }

    override fun begin(status: String) {
        AiQuestionForegroundService.start(context, status)
        // Which Pro models hear audio (relay /models), refreshed daily without delaying this question.
        if (currentRoute() == GlassesAssistantRoute.PRO && ProSubscriptionAiPrefs.audioCapableModelsStale(context)) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { ProSubscriptionRelayClient.fetchModelCatalog(appContext) }
            }
        }
    }

    override fun finish() {
        LocalModelLoadAnnouncer.shared.disarm()
        AiQuestionForegroundService.stop(context)
    }

    /**
     * Once the question is recorded, say "Initializing local model" if this question has to wait
     * for a cold load (already running, or started later by the decision or the answer). Warm
     * models stay silent. Queued, so it never cuts off a cue that is still playing.
     */
    override fun armLocalModelLoadCue() {
        LocalModelLoadAnnouncer.shared.arm { modelId ->
            Log.i("AIHijack", "Cold load during question model=$modelId; speaking initializing cue")
            val languageTag = languageTag()
            runOnUiThread {
                speaker.speak(
                    text = ImageQuestionDefaults.initializingLocalModelCueForLanguage(languageTag),
                    languageTag = languageTag,
                    utteranceId = "initializing_local_model_${System.nanoTime()}",
                    queueMode = android.speech.tts.TextToSpeech.QUEUE_ADD,
                )
            }
        }
    }

    /** Glasses with a display show the spoken answer too (MemoMind: the teleprompter or Web Bridge text). */
    private fun showAnswerOnDisplay(text: String) {
        if (text.isBlank() || !selectedProfile().showsAssistantAnswers) return
        processScope.launch(Dispatchers.IO) {
            GlassesBridge.showText(DisplayCommand.Text(text))
                .onFailure { Log.w("AIHijack", "Answer not shown on the glasses display", it) }
        }
    }

    companion object {
        @Volatile private var instance: GlassesQuestions? = null

        fun get(context: Context): GlassesQuestions =
            instance ?: synchronized(this) {
                instance ?: GlassesQuestions(context).also { instance = it }
            }

        private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
