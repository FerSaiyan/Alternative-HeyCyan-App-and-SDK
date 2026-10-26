package com.fersaiyan.cyanbridge.ai.questions

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveDestination
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveForegroundService
import com.fersaiyan.cyanbridge.ai.live.GeminiLivePermissionGateActivity
import com.fersaiyan.cyanbridge.ai.router.MediaInferenceRoutingPolicy
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPromptResolver
import com.fersaiyan.cyanbridge.shared.ai.GlassesAssistantRoute
import com.fersaiyan.cyanbridge.shared.assistant.AssistantQuestionRouting
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.google.android.material.snackbar.Snackbar

/** The Gemini Live detour of glasses questions: Pro users whose questions model is Live. */
internal class GeminiLiveQuestions(
    private val env: QuestionEnvironment,
    private val speaker: AssistantSpeaker,
    private val route: () -> GlassesAssistantRoute,
    private val languageTag: () -> String,
) {
    fun shouldUseForQuestions(providerType: AgentProviderType): Boolean {
        if (providerType != AgentProviderType.PRO_SUBSCRIPTION) return false
        val context = env.context
        val isProActive = ProSubscriptionPrefs.isActiveLocally(context)
        val questionsModel = ProSubscriptionAiPrefs.getQuestionsModel(context)
        val result = ProSubscriptionAiPrefs.shouldUseGeminiLiveForQuestions(
            isProActive = isProActive,
            questionsModel = questionsModel,
        )
        Log.i("GeminiLive", "shouldUseGeminiLiveQuestions isProActive=$isProActive questionsModel=$questionsModel result=$result")
        return result
    }

    /** True when the *image* question button would route to Gemini Live (voice+vision). */
    fun isRoutedForImageQuestion(): Boolean {
        val provider = AssistantQuestionRouting.liveCandidateProvider(route()) {
            MediaInferenceRoutingPolicy.resolve(env.context)
        } ?: return false
        return shouldUseForQuestions(provider)
    }

    fun launch(
        prompt: String,
        imagePath: String? = null,
        onLaunched: (() -> Unit)? = null,
    ) {
        val context = env.context
        val languageTag = languageTag()
        // Base image question without strict "Answer only in X" - Live systemInstruction handles 97-language switching permissively.
        // Single-shot paths keep strict Resolver; Live uses baseQuestion as imagePrompt, while initialPrompt is the user's actual turn.
        val baseImagePrompt = ImageQuestionPromptResolver.baseQuestion(
            settings = ImageQuestionPreferences.get(context),
            userQuestion = null,
        )
        val initialPrompt = prompt.trim().ifBlank { baseImagePrompt }
        // Foreground service keeps mic+vision alive with screen off/locked.
        // The dedicated GeminiLiveActivity is now optional debug UI; the image
        // button path prefers the notification-driven service with a Stop action.
        val launch: () -> Unit = {
            val screen = env.host
            val launchContext = screen?.activity ?: env.appContext
            try {
                val isPro = ProSubscriptionPrefs.isActiveLocally(launchContext) &&
                    ProSubscriptionPrefs.getPlan(launchContext).lowercase() in setOf("cheap", "standard", "max")
                GeminiLivePermissionGateActivity.launch(
                    context = launchContext,
                    language = languageTag,
                    imagePrompt = baseImagePrompt,
                    initialImagePath = imagePath,
                    initialPrompt = initialPrompt,
                    useRelay = !isPro,
                )
                Log.i(
                    "GeminiLive",
                    "Requested Live launch initialImage=${!imagePath.isNullOrBlank()} useRelay=${!isPro}",
                )
                // Keep a visible in-app stop action when MainActivity is open; the
                // foreground service notification remains the background Stop control.
                if (screen != null) {
                    Snackbar
                        .make(screen.rootView, "Gemini Live: microphone may be active", Snackbar.LENGTH_INDEFINITE)
                        .setAction("Stop Live") {
                            GeminiLiveForegroundService.stop(launchContext)
                        }
                        .show()
                }
            } catch (error: Exception) {
                Log.e("GeminiLive", "Could not start Live foreground service", error)
                Toast.makeText(launchContext, "Could not start Gemini Live.", Toast.LENGTH_LONG).show()
                // Fallback to legacy activity path
                try {
                    launchContext.startActivity(
                        AppRoute.GEMINI_LIVE.intent(launchContext)
                            .putExtra(GeminiLiveDestination.EXTRA_AUTO_START, true)
                            .putExtra(GeminiLiveDestination.EXTRA_INITIAL_PROMPT, prompt)
                            .apply {
                                if (screen == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                imagePath?.takeIf { it.isNotBlank() }?.let {
                                    putExtra(GeminiLiveDestination.EXTRA_INITIAL_IMAGE_PATH, it)
                                }
                            },
                    )
                } catch (_: Exception) {}
            } finally {
                onLaunched?.invoke()
            }
        }
        env.runOnUiThread {
            speaker.speak(
                text = ImageQuestionDefaults.initializingLiveCueForLanguage(languageTag),
                languageTag = languageTag,
                utteranceId = "initializing_live_${System.nanoTime()}",
                streamType = null,
                onDone = launch,
            )
        }
    }
}
