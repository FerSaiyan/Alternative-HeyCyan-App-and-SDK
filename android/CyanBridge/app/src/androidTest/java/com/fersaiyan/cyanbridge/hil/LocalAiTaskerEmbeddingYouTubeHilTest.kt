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
        HilTestSupport.requireOrSkip(
            !RemoteOpenAiPrefs.isActive(context),
            "YouTube HIL requires the on-device model path; remote planning is active",
        )
        HilTestSupport.requireOrSkip(
            LocalModelStorageRepository.resolveSelectedModel(context) != null,
            "No CyanBridge local model is installed/selected on the HIL target",
        )
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

        ActivityScenario.launch(HilFixtureActivity::class.java).use {
            try {
                AutomationPrefs.setProviderType(context, AgentProviderType.LOCAL_AGENT)
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
                RuntimePrefs.clearLastError(context)

                val start = Intent(context, TaskerLocalAgentService::class.java).apply {
                    action = LocalAgentIntents.ACTION_START
                    putExtra(LocalAgentIntents.EXTRA_GOAL, GOAL)
                }
                ContextCompat.startForegroundService(context, start)

                val result = awaitPlayingVideo(context)
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
                    PLAYBACK_MARKERS.any { visible.contains(it) },
                )
            } finally {
                context.startService(
                    Intent(context, TaskerLocalAgentService::class.java).apply {
                        action = LocalAgentIntents.ACTION_STOP
                    },
                )
                AutomationPrefs.setProviderType(context, previousProvider)
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

    private fun awaitPlayingVideo(context: Context): LocalAgentObservation {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var lastObservation: LocalAgentObservation? = null
        var lastObserveMs = 0L
        while (System.currentTimeMillis() < deadline) {
            // Status polls are cheap and contention-free. Tasker observations
            // are serialized inside Tasker, so poll them rarely: the service
            // loop already observes every step, and concurrent test polls were
            // observed to starve the service request past its 8 s timeout.
            val now = System.currentTimeMillis()
            if (now - lastObserveMs >= OBSERVE_POLL_MS) {
                lastObserveMs = now
                lastObservation = runBlocking { TaskerExecutionBackend.observe(context) }
                val visible = visibleText(lastObservation)
                if (
                    lastObservation?.packageName == YOUTUBE_PACKAGE &&
                    visible.contains("linus") &&
                    visible.contains("tech") &&
                    PLAYBACK_MARKERS.any { visible.contains(it) }
                ) {
                    return lastObservation
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

    companion object {
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val TIMEOUT_MS = 8 * 60_000L
        private const val POLL_MS = 1_000L
        private const val OBSERVE_POLL_MS = 20_000L
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
        private val PLAYBACK_MARKERS = setOf("pause", "playing", "paused", "progress")
    }
}
