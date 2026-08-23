package com.fersaiyan.cyanbridge.hil

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.localagent.LocalAgentIntents
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs as RuntimePrefs
import com.fersaiyan.cyanbridge.localagent.TaskerLocalAgentService
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAiTaskerChromeHilTest {
    @Test
    fun localAiUsesTaskerToNavigateChromeAndReturnsGroundedSummary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        HilTestSupport.requireTaskerStack(context)
        HilTestSupport.requireOrSkip(
            HilTestSupport.localAiRequired,
            "Local-AI HIL was not enabled for this instrumentation run",
        )
        HilTestSupport.requireOrSkip(
            !RemoteOpenAiPrefs.isActive(context),
            "Local-AI HIL requires the on-device model path; disable the remote OpenAI-compatible local-model server",
        )
        val selectedModel = LocalModelStorageRepository.resolveSelectedModel(context)
        HilTestSupport.requireOrSkip(
            selectedModel != null,
            "No CyanBridge local model is installed/selected on the HIL phone",
        )

        val previousProvider = AutomationPrefs.getProviderType(context)
        val previousAutomationEnabled = AutomationPrefs.isLocalAgentAutomationEnabled(context)
        val previousMaxSteps = AutomationPrefs.getMaxSteps(context)
        val previousRequireConfirmation = RuntimePrefs.isRequireActionConfirmationEnabled(context)
        val previousScreenshotPlanning = RuntimePrefs.isScreenshotPlanningEnabled(context)
        val previousRemoteScreenshotUpload = RuntimePrefs.isRemoteScreenshotUploadEnabled(context)

        ActivityScenario.launch(HilFixtureActivity::class.java).use {
            try {
                setBlockedPackages(context, "")
                Thread.sleep(400)

                AutomationPrefs.setProviderType(context, AgentProviderType.LOCAL_AGENT)
                AutomationPrefs.setLocalAgentAutomationEnabled(context, true)
                AutomationPrefs.setMaxSteps(context, 16)
                RuntimePrefs.setRequireActionConfirmationEnabled(context, false)
                RuntimePrefs.setScreenshotPlanningEnabled(context, false)
                RuntimePrefs.setRemoteScreenshotUploadEnabled(context, false)
                RuntimePrefs.setStatus(context, "HIL local AI starting")
                RuntimePrefs.clearLastError(context)

                val start = Intent(context, TaskerLocalAgentService::class.java).apply {
                    action = LocalAgentIntents.ACTION_START
                    putExtra(LocalAgentIntents.EXTRA_GOAL, GOAL)
                }
                ContextCompat.startForegroundService(context, start)

                val finalStatus = awaitGroundedAnswer(context)
                val normalized = finalStatus.lowercase()
                assertTrue(
                    "Local AI answer did not summarize the observed Borealis page: $finalStatus",
                    normalized.contains("borealis"),
                )
                assertTrue(
                    "Local AI answer omitted the unique observed quantity: $finalStatus",
                    normalized.contains("37") && normalized.contains("amber"),
                )
                assertTrue(
                    "Local AI answer did not preserve the architecture distinction: $finalStatus",
                    normalized.contains("cyanbridge") && normalized.contains("tasker"),
                )
                assertFalse(
                    "Local AI HIL unexpectedly switched to a remote model endpoint",
                    RemoteOpenAiPrefs.isActive(context),
                )
            } finally {
                context.startService(
                    Intent(context, TaskerLocalAgentService::class.java).apply {
                        action = LocalAgentIntents.ACTION_STOP
                    },
                )
                setBlockedPackages(context, "")
                AutomationPrefs.setProviderType(context, previousProvider)
                AutomationPrefs.setLocalAgentAutomationEnabled(context, previousAutomationEnabled)
                AutomationPrefs.setMaxSteps(context, previousMaxSteps)
                RuntimePrefs.setRequireActionConfirmationEnabled(context, previousRequireConfirmation)
                RuntimePrefs.setScreenshotPlanningEnabled(context, previousScreenshotPlanning)
                RuntimePrefs.setRemoteScreenshotUploadEnabled(context, previousRemoteScreenshotUpload)
            }
        }
    }

    private fun awaitGroundedAnswer(context: Context): String {
        val deadline = System.currentTimeMillis() + LOCAL_AI_TIMEOUT_MS
        var status = RuntimePrefs.getStatus(context)
        while (System.currentTimeMillis() < deadline) {
            status = RuntimePrefs.getStatus(context)
            val normalized = status.lowercase()
            if (normalized.contains("37") && normalized.contains("amber")) {
                return status
            }

            val error = RuntimePrefs.getLastError(context)
            if (error != "(none)" && error.isNotBlank()) {
                throw AssertionError("Local Agent failed before producing its answer: status=$status error=$error")
            }
            Thread.sleep(1_000L)
        }
        throw AssertionError(
            "Timed out waiting for a grounded CyanBridge local-AI answer. " +
                "Last status=$status error=${RuntimePrefs.getLastError(context)}",
        )
    }

    private fun setBlockedPackages(context: Context, packages: String) {
        context.sendBroadcast(
            Intent(ACTION_HIL_SET_LOCALAGENT_BLOCKED)
                .setPackage(HilTestSupport.TASKER_PACKAGE)
                .putExtra("packages", packages),
        )
    }

    companion object {
        private const val ACTION_HIL_SET_LOCALAGENT_BLOCKED =
            "com.fersaiyan.cyanbridge.HIL_SET_LOCALAGENT_BLOCKED"
        private const val LOCAL_AI_TIMEOUT_MS = 6 * 60_000L
        private const val GOAL =
            "Open Chrome. On the CyanBridge HIL Search page, type the query 'local agent architecture' " +
                "into Search query, click the visible Search button, open the first result, read only " +
                "the first visible result page without scrolling, then finish with a concise summary " +
                "for the user of what the page says."
    }
}
