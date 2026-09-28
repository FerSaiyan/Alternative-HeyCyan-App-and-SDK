package com.fersaiyan.cyanbridge.hil

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.localagent.LocalAgentIntents
import com.fersaiyan.cyanbridge.localagent.LocalAgentObservation
import com.fersaiyan.cyanbridge.localagent.LocalAgentShadowTrace
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs as RuntimePrefs
import com.fersaiyan.cyanbridge.localagent.TaskerExecutionBackend
import com.fersaiyan.cyanbridge.localagent.TaskerLocalAgentService
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Gemma-gated variant of the real YouTube Tasker HIL.
 *
 * Enables the opt-in embedding cosine gate (HIL-provisioned GGUF) ahead of the
 * single-token LLM inside [TaskerLocalAgentService] and runs the Linus Tech
 * Tips playback flow. Per-decision gate evidence lands in logcat
 * (`JEV_EMBED [local-agent-ui...]` top/margin/accepted lines and
 * `Jev-like UI choice=` lines).
 */
@RunWith(AndroidJUnit4::class)
class LocalAiTaskerEmbeddingYouTubeHilTest {
    @Test
    fun embeddingGateUsesTaskerToPlayLinusTechTipsVideo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        HilTestSupport.requireTaskerStack(context)
        HilTestSupport.requireOrSkip(
            HilTestSupport.localAiRequired,
            "Set -e hil_local_ai true to run the local-model Tasker HIL",
        )
        HilTestSupport.requireOrSkip(
            HilTestSupport.youtubeRequired,
            "Set -e hil_youtube true to run the nondeterministic YouTube HIL",
        )
        HilTestSupport.requireOrSkip(
            HilTestSupport.packageInstalled(context, YOUTUBE_PACKAGE),
            "YouTube is not installed on the HIL target",
        )
        val proPlanner = HilTestSupport.proPlannerRequired
        if (proPlanner) {
            HilTestSupport.requireVerifiedProPlanner(context)
        } else {
            HilTestSupport.requireOrSkip(
                !RemoteOpenAiPrefs.isActive(context),
                "YouTube HIL requires the on-device model path; remote planning is active",
            )
            HilTestSupport.requireOrSkip(
                LocalModelStorageRepository.resolveSelectedModel(context) != null,
                "No CyanBridge local model is installed/selected on the HIL target",
            )
        }
        HilTestSupport.requireOrSkip(
            File(EMBEDDING_GGUF_PATH).isFile,
            "Missing HIL-provisioned embedding GGUF: $EMBEDDING_GGUF_PATH",
        )

        val previousProvider = AutomationPrefs.getProviderType(context)
        val previousAutomationEnabled = AutomationPrefs.isLocalAgentAutomationEnabled(context)
        val previousMaxSteps = AutomationPrefs.getMaxSteps(context)
        val previousRequireConfirmation = RuntimePrefs.isRequireActionConfirmationEnabled(context)
        val previousScreenshotPlanning = RuntimePrefs.isScreenshotPlanningEnabled(context)
        val previousRemoteScreenshotUpload = RuntimePrefs.isRemoteScreenshotUploadEnabled(context)
        val previousEmbeddingEnabled = AutomationPrefs.isEmbeddingDecisionEnabled(context)
        val previousEmbeddingPath = AutomationPrefs.getEmbeddingModelPath(context)
        val previousEmbeddingMargin = AutomationPrefs.getEmbeddingMarginThreshold(context)
        val shadowPrefs = context.getSharedPreferences(LocalAgentShadowTrace.PREFS_NAME, Context.MODE_PRIVATE)
        val previousShadow = shadowPrefs.getBoolean(LocalAgentShadowTrace.PREF_ENABLED, false)
        val reached = linkedSetOf<String>()
        fun stage(name: String) {
            if (reached.add(name)) {
                val event = "JEV_HIL_STAGE youtube=$name reached=$reached"
                println(event)
                android.util.Log.i("JevHilStage", event)
            }
        }

        ActivityScenario.launch(HilFixtureActivity::class.java).use {
            try {
                AutomationPrefs.setProviderType(context,
                    if (proPlanner) AgentProviderType.PRO_SUBSCRIPTION else AgentProviderType.LOCAL_AGENT)
                shadowPrefs.edit().putBoolean(LocalAgentShadowTrace.PREF_ENABLED,
                    InstrumentationRegistry.getArguments().getString("hil_shadow") == "true").apply()
                AutomationPrefs.setLocalAgentAutomationEnabled(context, true)
                AutomationPrefs.setMaxSteps(context, 20)
                AutomationPrefs.setEmbeddingDecisionEnabled(context, true)
                AutomationPrefs.setEmbeddingModelPath(context, EMBEDDING_GGUF_PATH)
                AutomationPrefs.setEmbeddingMarginThreshold(context, EMBEDDING_MARGIN)
                RuntimePrefs.setRequireActionConfirmationEnabled(context, false)
                RuntimePrefs.setScreenshotPlanningEnabled(context, false)
                RuntimePrefs.setRemoteScreenshotUploadEnabled(context, false)
                RuntimePrefs.setStatus(context, "HIL embedding-gated YouTube starting")
                RuntimePrefs.clearLastError(context)

                // Single warmup observation before the service starts: proves the
                // Tasker pipeline is hot while nothing else contends with it.
                val warmup = runBlocking { TaskerExecutionBackend.observe(context) }
                assertTrue(
                    "Tasker warmup observation failed before the agent started: " +
                        "status=${RuntimePrefs.getStatus(context)} error=${RuntimePrefs.getLastError(context)}",
                    warmup != null,
                )
                stage("tasker_observation_ready")
                RuntimePrefs.clearLastError(context)

                val start = Intent(context, TaskerLocalAgentService::class.java).apply {
                    action = LocalAgentIntents.ACTION_START
                    putExtra(LocalAgentIntents.EXTRA_GOAL, GOAL)
                }
                ContextCompat.startForegroundService(context, start)

                val evidence = awaitPlayingVideo(context) { observation ->
                    if (observation?.packageName == YOUTUBE_PACKAGE) {
                        stage("youtube_foreground")
                        val visible = visibleText(observation)
                        if (visible.contains("linus") && visible.contains("tech")) {
                            stage("requested_content_visible")
                        }
                    }
                }
                val result = evidence.observation
                assertTrue(
                    "Tasker did not observe the YouTube package: ${result.packageName}",
                    result.packageName == YOUTUBE_PACKAGE,
                )
                val visible = visibleText(result)
                assertTrue(
                    "Tasker did not observe the requested Linus Tech Tips content: $visible",
                    visible.contains("linus") && visible.contains("tech"),
                )
                assertTrue(
                    "Tasker did not observe a playback control/state: $visible",
                    playbackObserved(visible) || evidence.advancing,
                )
                stage("playback_observed")
            } catch (failure: Throwable) {
                throw AssertionError(
                    "YouTube HIL reached=$reached status=${RuntimePrefs.getStatus(context)} " +
                        "error=${RuntimePrefs.getLastError(context)}: ${failure.message}",
                    failure,
                )
            } finally {
                context.startService(
                    Intent(context, TaskerLocalAgentService::class.java).apply {
                        action = LocalAgentIntents.ACTION_STOP
                    },
                )
                AutomationPrefs.setProviderType(context, previousProvider)
                shadowPrefs.edit().putBoolean(LocalAgentShadowTrace.PREF_ENABLED, previousShadow).apply()
                AutomationPrefs.setLocalAgentAutomationEnabled(context, previousAutomationEnabled)
                AutomationPrefs.setMaxSteps(context, previousMaxSteps)
                AutomationPrefs.setEmbeddingDecisionEnabled(context, previousEmbeddingEnabled)
                AutomationPrefs.setEmbeddingModelPath(context, previousEmbeddingPath)
                AutomationPrefs.setEmbeddingMarginThreshold(context, previousEmbeddingMargin)
                RuntimePrefs.setRequireActionConfirmationEnabled(context, previousRequireConfirmation)
                RuntimePrefs.setScreenshotPlanningEnabled(context, previousScreenshotPlanning)
                RuntimePrefs.setRemoteScreenshotUploadEnabled(context, previousRemoteScreenshotUpload)
            }
        }
    }

    private data class PlaybackEvidence(val observation: LocalAgentObservation, val advancing: Boolean)

    private fun awaitPlayingVideo(
        context: Context,
        onObservation: (LocalAgentObservation?) -> Unit,
    ): PlaybackEvidence {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var lastObservation: LocalAgentObservation? = null
        var lastObserveMs = 0L
        var lastVideoProgress: Pair<Int, Int>? = null
        while (System.currentTimeMillis() < deadline) {
            // Status polls are cheap and contention-free. Tasker observations
            // are serialized inside Tasker, so poll them rarely: the service
            // loop already observes every step, and concurrent test polls were
            // observed to starve the service request past its 8 s timeout.
            val now = System.currentTimeMillis()
            if (now - lastObserveMs >= OBSERVE_POLL_MS) {
                lastObserveMs = now
                lastObservation = runBlocking { TaskerExecutionBackend.observe(context) }
                onObservation(lastObservation)
                val visible = visibleText(lastObservation)
                val matchingPlayer = lastObservation?.packageName == YOUTUBE_PACKAGE &&
                    visible.contains("linus") && visible.contains("tech") &&
                    visible.contains("video player") && !visible.contains("video paused")
                val progress = if (matchingPlayer) videoProgress(visible) else null
                val previousProgress = lastVideoProgress
                val advancing = progress != null && previousProgress != null &&
                    progress.second == previousProgress.second &&
                    progress.first > previousProgress.first &&
                    progress.first - previousProgress.first <= 30
                if (progress != null) lastVideoProgress = progress
                if (
                    matchingPlayer && (playbackObserved(visible) || advancing)
                ) {
                    return PlaybackEvidence(lastObservation, advancing)
                }
            }

            val error = RuntimePrefs.getLastError(context)
            if (error != "(none)" && error.isNotBlank() && error in FATAL_ERRORS) {
                throw AssertionError(
                    "Local Agent failed before YouTube playback was observed: " +
                        "status=${RuntimePrefs.getStatus(context)} error=$error " +
                        "visible=${visibleText(lastObservation)}",
                )
            }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(
            "Timed out waiting for YouTube playback. status=${RuntimePrefs.getStatus(context)} " +
                "error=${RuntimePrefs.getLastError(context)} " +
                "lastPackage=${lastObservation?.packageName} visible=${visibleText(lastObservation)}",
        )
    }

    private fun visibleText(observation: LocalAgentObservation?): String {
        return listOf(
            observation?.screenText.orEmpty(),
            observation?.screenSnapshot?.textSummary.orEmpty(),
            observation?.screenSnapshot?.nodes.orEmpty().joinToString(" ") { node ->
                listOf(node.text, node.contentDescription, node.hintText).joinToString(" ")
            },
        ).joinToString(" ").lowercase()
    }

    private fun playbackObserved(visible: String): Boolean = PLAYBACK_MARKERS.any { it.containsMatchIn(visible) }

    /** A positive playback signal even when YouTube's Pause overlay hides between polls. */
    private fun videoProgress(visible: String): Pair<Int, Int>? {
        val match = VIDEO_PROGRESS.find(visible) ?: return null
        val elapsed = match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
        val duration = match.groupValues[3].toInt() * 60 + match.groupValues[4].toInt()
        return (elapsed to duration).takeIf { elapsed in 0 until duration }
    }

    companion object {
        private val VIDEO_PROGRESS = Regex("(\\d+) minutes? (\\d+) seconds? of (\\d+) minutes? (\\d+) seconds?")
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val TIMEOUT_MS = 8 * 60_000L
        private const val POLL_MS = 1_000L
        // Cloud inference can advance from a visible Pause control to the next
        // action within seconds. Sample independently before that evidence fades.
        private const val OBSERVE_POLL_MS = 5_000L
        /** Fail fast only on errors the service cannot retry through. */
        private val FATAL_ERRORS = setOf(
            "local_agent_automation_disabled",
            "missing_goal",
            "max_steps_reached",
        )
        private const val EMBEDDING_GGUF_PATH = "/data/local/tmp/jev-embedding/embeddinggemma-300M-Q8_0.gguf"
        private const val EMBEDDING_MARGIN = 0.10f
        private const val GOAL =
            "Open YouTube, search for Linus Tech Tips, open a result from Linus Tech Tips, " +
                "start playback, and finish after confirming the video is playing."
        // "paused" and a static progress bar do not demonstrate active playback.
        // A visible Pause control (or explicit Playing state) is stronger evidence.
        private val PLAYBACK_MARKERS = listOf(Regex("\\bpause\\b"), Regex("\\bplaying\\b"))
    }
}
