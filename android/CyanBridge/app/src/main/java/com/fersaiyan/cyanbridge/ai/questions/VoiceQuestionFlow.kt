package com.fersaiyan.cyanbridge.ai.questions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.ai.image.GlassesQuestionTestHooks
import com.fersaiyan.cyanbridge.ai.router.AssistantIntent
import com.fersaiyan.cyanbridge.ai.router.AssistantRequest
import com.fersaiyan.cyanbridge.ai.router.AssistantRequestSource
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.ai.router.QuestionDecisionShadow
import com.fersaiyan.cyanbridge.ai.router.QuestionRoutingPrefs
import com.fersaiyan.cyanbridge.ai.router.RelayErrorLocalizer
import com.fersaiyan.cyanbridge.localagent.LocalAgentController
import com.fersaiyan.cyanbridge.localagent.memory.RagProfile
import com.fersaiyan.cyanbridge.shared.ai.AssistantSpeechPolicy
import com.fersaiyan.cyanbridge.shared.ai.GlassesAssistantRoute
import com.fersaiyan.cyanbridge.shared.assistant.AssistantQuestionRouting
import com.fersaiyan.cyanbridge.shared.assistant.SpokenQuestionFlow
import com.fersaiyan.cyanbridge.shared.assistant.SpokenQuestionSettings
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.shared.settings.QuestionRoutingMode
import com.fersaiyan.cyanbridge.tasker.TaskerIntegrationManager
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission as HjqPermission
import com.hjq.permissions.XXPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Spoken glasses questions: the AI button's voice route (Classic: on-device transcript and the
 * transcript router, or the recording for a model that hears it), the phone agent, and the
 * decision-model questions, which run the shared [SpokenQuestionFlow] over Android ports.
 */
internal class VoiceQuestionFlow(
    private val env: QuestionEnvironment,
    private val speaker: AssistantSpeaker,
    private val microphone: QuestionMicrophone,
    private val providers: AssistantProviderQueries,
    private val live: GeminiLiveQuestions,
    private val external: ExternalAssistantHandoff,
    private val imageFlow: ImageQuestionFlow,
    private val imageCapture: ImageQuestionCapture,
    private val foreground: QuestionForegroundWork,
    private val route: () -> GlassesAssistantRoute,
    private val isGlassesCommandBlocked: (source: String) -> Boolean,
    private val isImageQuestionWorkActive: () -> Boolean,
    private val stopGlassesAiAudio: (source: String) -> Unit,
    private val cancelLocalStreamingSpeech: (reason: String) -> Unit,
    private val languageTag: () -> String,
) {
    private val context: Context get() = env.context
    val voiceQueryInProgress = AtomicReference<Any?>(null)
    private val decideOnceInProgress = AtomicBoolean(false)

    /** The model that hears a recorded question (PRO only when its questions model hears audio). */
    fun spokenQuestionProvider(): AgentProviderType =
        AssistantQuestionRouting.spokenQuestionProvider(route()) { proQuestionsModelHearsAudio() }

    fun questionAudioGoesToModel(): Boolean = AssistantQuestionRouting.questionAudioGoesToModel(
        route(),
        hasLocalAudioModel = { com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider.hasLocalAudioModel(context) },
        proQuestionsModelHearsAudio = ::proQuestionsModelHearsAudio,
    )

    private fun proQuestionsModelHearsAudio(): Boolean =
        !live.shouldUseForQuestions(AgentProviderType.PRO_SUBSCRIPTION) && ProSubscriptionAiPrefs.questionsModelHearsAudio(context)

    fun triggerAssistantVoiceQuery() {
        if (isGlassesCommandBlocked("voice-query command")) return
        val effectiveMode = external.effectiveAssistantMode()
        Log.i("AIHijack", "Triggering Voice Query for $effectiveMode")

        val currentRoute = route()
        val provider = AssistantQuestionRouting.voiceQuestionProvider(currentRoute)
        when {
            provider != null -> triggerCliRelayVoiceQuery(memoryAwareChosenProvider = true, chosenProviderType = provider)
            currentRoute == GlassesAssistantRoute.TASKER_EXTERNAL_UI -> external.triggerTaskerVoiceAutomation()
            else -> external.launchPhoneAssistant()
        }
    }

    private fun triggerCliRelayVoiceQuery(
        memoryAwareChosenProvider: Boolean = false,
        chosenProviderType: AgentProviderType? = null,
    ) {
        if (isGlassesCommandBlocked("voice-query command")) return
        if (isImageQuestionWorkActive()) {
            Log.i("AIHijack", "Ignoring voice query while an image question is active")
            Toast.makeText(context, "An image question is already active", Toast.LENGTH_SHORT).show()
            return
        }
        cancelLocalStreamingSpeech("new voice query")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val activity = env.host?.activity
            if (activity == null) {
                // No screen to ask for the microphone from.
                microphoneRequiredToast(context)
                return
            }
            XXPermissions.with(activity)
                .permission(HjqPermission.RECORD_AUDIO)
                .request(object : OnPermissionCallback {
                    override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                        if (all) {
                            triggerCliRelayVoiceQuery(memoryAwareChosenProvider, chosenProviderType)
                        } else {
                            microphoneRequiredToast(activity)
                        }
                    }

                    override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                        super.onDenied(permissions, never)
                        microphoneRequiredToast(activity)
                        if (never) {
                            XXPermissions.startPermissionActivity(activity, permissions)
                        }
                    }
                })
            return
        }
        foreground.prepareForLockScreen()
        val voiceQueryToken = Any()
        if (!voiceQueryInProgress.compareAndSet(null, voiceQueryToken)) {
            Log.i("AIHijack", "Ignoring duplicate voice query while another query is active")
            Toast.makeText(context, "A voice question is already active", Toast.LENGTH_SHORT).show()
            return
        }
        foreground.begin("Listening for glasses voice question")
        // Wake up screen if locked
        foreground.wakeScreen()

        // Tell glasses to stop proprietary AI audio stream
        stopGlassesAiAudio("voice-query command")

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val audioRouteOwner = QuestionMicrophone.RouteOwner(voiceQueryToken, audioManager)
        // Glasses with an SDK microphone get no Bluetooth call route, so there is none to clear.
        if (!microphone.usesDeviceSdkRoute()) microphone.activeRoute.set(audioRouteOwner)

        fun stopSco() {
            // A delayed callback from an older query must not clear the route selected by a
            // newer query. AudioManager is a process singleton, so the per-query owner object
            // is what makes this check race-safe.
            if (!microphone.activeRoute.compareAndSet(audioRouteOwner, null)) return
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.mode = android.media.AudioManager.MODE_NORMAL
            }
        }

        fun finishVoiceQueryWork() {
            if (voiceQueryInProgress.compareAndSet(voiceQueryToken, null)) {
                foreground.finish()
            }
        }

        Toast.makeText(context, "Listening for voice query…", Toast.LENGTH_SHORT).show()

        fun processVoicePrompt(prompt: String) {
            if (prompt.isBlank()) {
                stopSco()
                finishVoiceQueryWork()
                return
            }

            Toast.makeText(context, "Asking: $prompt", Toast.LENGTH_SHORT).show()

            env.scope.launch(Dispatchers.IO) {
                try {
                    val selectedProvider = chosenProviderType
                        ?: AutomationPrefs.getProviderType(context)
                    val routingProvider = if (memoryAwareChosenProvider) {
                        selectedProvider
                    } else {
                        // This legacy branch is retained only for direct callers outside the
                        // two-mode Glasses dashboard.
                        AgentProviderType.TASKER
                    }
                    val routing = providers.requestRouter.route(
                        context = context,
                        request = AssistantRequest(
                            text = prompt,
                            source = AssistantRequestSource.GLASSES_VOICE,
                        ),
                        providerType = routingProvider,
                    )
                    GlassesQuestionTestHooks.emit("route", routing.intent.name)
                    if (QuestionRoutingPrefs.mode(context) == QuestionRoutingMode.SHADOW) {
                        env.scope.launch(Dispatchers.Default) {
                            QuestionDecisionShadow.log(prompt, flow = "voice", classic = routing.intent)
                        }
                    }

                    when (routing.intent) {
                        AssistantIntent.ANSWER_QUESTION -> {
                            if (
                                memoryAwareChosenProvider &&
                                live.shouldUseForQuestions(selectedProvider)
                            ) {
                                withContext(Dispatchers.Main) {
                                    stopSco()
                                    finishVoiceQueryWork()
                                    live.launch(prompt = prompt)
                                }
                                return@launch
                            }
                            val reply = if (memoryAwareChosenProvider) {
                                providers.query(
                                    userPrompt = prompt,
                                    providerType = selectedProvider,
                                    ragProfile = RagProfile.LIGHT,
                                )
                            } else {
                                val modelOverride = if (selectedProvider == AgentProviderType.PRO_SUBSCRIPTION) {
                                    ProSubscriptionAiPrefs.getQuestionsModel(context)
                                } else {
                                    null
                                }

                                CliRelayClient.voiceQuery(
                                    context = context,
                                    prompt = prompt,
                                    modelOverride = modelOverride,
                                ).getOrElse { RelayErrorLocalizer.localizedMessage(context, it) }
                            }

                            env.runOnUiThread {
                                speaker.speakVision(reply) {
                                    stopSco()
                                    finishVoiceQueryWork()
                                }
                            }
                        }

                        AssistantIntent.ANALYZE_IMAGE -> env.runOnUiThread {
                            stopSco()
                            // The image flow now owns the shared foreground service. Release
                            // only this voice-query guard without stopping that service.
                            voiceQueryInProgress.compareAndSet(voiceQueryToken, null)
                            imageFlow.pendingVoiceImageQuestion = routing.normalizedGoal ?: prompt
                            speaker.speak("Okay. I'll check what you see.")
                            imageCapture.handleGlassesImageButtonPressed(
                                triggerCapture = true,
                                sourceTag = ImageQuestionFlow.VOICE_REQUEST_TAG,
                            )
                        }

                        AssistantIntent.EXECUTE_UI_TASK -> env.runOnUiThread {
                            stopSco()
                            finishVoiceQueryWork()
                            startLocalAgentFromVoice(routing.normalizedGoal ?: prompt)
                        }

                        AssistantIntent.CLARIFY -> env.runOnUiThread {
                            stopSco()
                            finishVoiceQueryWork()
                            speaker.speak(
                                AssistantSpeechPolicy.clarification(routing.clarification),
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    stopSco()
                    finishVoiceQueryWork()
                    throw cancelled
                } catch (error: Exception) {
                    Log.e("AIHijack", "Voice query processing failed", error)
                    env.runOnUiThread {
                        stopSco()
                        finishVoiceQueryWork()
                        speaker.speak("I couldn't process that voice question. Please try again.")
                    }
                }
            }
        }

        val provider = chosenProviderType ?: AutomationPrefs.getProviderType(context)
        if (memoryAwareChosenProvider && live.shouldUseForQuestions(provider)) {
            stopSco()
            finishVoiceQueryWork()
            live.launch(prompt = "Listen to the user's spoken question and answer it.")
            return
        }
        env.scope.launch {
            var originalAudio: String? = null
            try {
                val captured = microphone.capture(SpokenQuestionSettings().questionOnsetMs, voiceOnly = true)
                originalAudio = captured.audioPath
                stopSco()
                val error = captured.error
                val transcript = captured.text
                when {
                    error != null -> { finishVoiceQueryWork(); speaker.speak(error) }
                    !captured.heardSpeech -> { finishVoiceQueryWork(); speaker.speak(SpokenQuestionFlow.NOT_HEARD) }
                    transcript != null -> processVoicePrompt(transcript)
                    captured.audioPath != null -> {
                        // Transcription failure keeps the original question; raw audio is never
                        // sent for cloud transcription or replaced with a generic question.
                        val reply = withContext(Dispatchers.IO) {
                            providers.query(
                                userPrompt = "",
                                providerType = spokenQuestionProvider(),
                                ragProfile = RagProfile.NONE,
                                audioPath = captured.audioPath,
                            )
                        }
                        speaker.speakVision(reply) { finishVoiceQueryWork() }
                    }
                }
            } catch (cancelled: CancellationException) {
                finishVoiceQueryWork()
                throw cancelled
            } catch (error: Exception) {
                Log.e("AIHijack", "Voice audio query failed", error)
                finishVoiceQueryWork()
                speaker.speak("I couldn't process that voice question. Please try again.")
            } finally {
                originalAudio?.let { File(it).delete() }
                stopSco()
            }
        }
    }

    private fun microphoneRequiredToast(context: Context) {
        Toast.makeText(context, "Microphone permission is required for voice questions", Toast.LENGTH_SHORT).show()
    }

    /**
     * Hands a spoken phone task to the local agent (Tasker), after the same checks as before. The agent
     * asks for spoken confirmation before high-risk actions (calls, messages, email, alarms).
     */
    fun startLocalAgentFromVoice(goal: String) {
        val context = context
        when {
            goal.isBlank() -> speaker.speak("Sorry, what should I do on the phone?")
            !AutomationPrefs.isLocalAgentAutomationEnabled(context) -> speaker.speak("Enable Local Agent phone control in CyanBridge settings first.")
            external.isDeviceLocked() -> speaker.speak("Unlock your phone before I control it.")
            !TaskerIntegrationManager.inspect(context).automationEnvironmentReady ->
                speaker.speak("Complete Tasker and AutoInput setup in CyanBridge Plugins first.")
            LocalAgentController.start(context, goal).ok -> speaker.speak("Okay. I'll do that.")
            else -> speaker.speak("I couldn't start phone control.")
        }
    }

    /**
     * A spoken question with the decision model (default whenever the answering model hears audio: a
     * local Gemma 4 or an audio-capable Pro model), run by the shared [SpokenQuestionFlow]. Models that
     * cannot hear audio keep on-device STT and the transcript router (Classic).
     */
    fun triggerSpokenDecisionQuestion() {
        if (isGlassesCommandBlocked("voice-query command")) return
        if (isImageQuestionWorkActive() || !decideOnceInProgress.compareAndSet(false, true)) {
            Log.i("AIHijack", "Spoken decision question ignored: another question is active")
            return
        }
        foreground.begin("Listening for question")
        val provider = spokenQuestionProvider()
        val ports = AndroidSpokenQuestionPorts(
            env = env,
            provider = provider,
            speaker = speaker,
            providers = providers,
            live = live,
            languageTag = languageTag,
            requestPhoto = ::requestDecideOncePhoto,
            startLocalAgent = ::startLocalAgentFromVoice,
        )
        val flow = SpokenQuestionFlow(
            generationScope = env.scope,
            generationContext = Dispatchers.IO,
            microphone = microphone,
            brain = ports,
            actions = ports,
            speech = ports,
            events = GlassesQuestionTestHooks::emit,
        )
        env.scope.launch {
            try {
                flow.run()
            } finally {
                if (decideOnceInProgress.compareAndSet(true, false)) foreground.finish()
            }
        }
    }

    /** Starts a glasses photo whose file goes to the returned deferred (null if capture fails). */
    private fun requestDecideOncePhoto(): kotlinx.coroutines.CompletableDeferred<String?> {
        val sink = kotlinx.coroutines.CompletableDeferred<String?>()
        imageFlow.pendingPhotoSink?.complete(null)
        imageFlow.pendingPhotoSink = sink
        imageCapture.handleGlassesImageButtonPressed(
            triggerCapture = true,
            sourceTag = ImageQuestionFlow.DECIDE_ONCE_PHOTO_TAG,
            offerSpokenQuestion = false,
        )
        return sink
    }
}
