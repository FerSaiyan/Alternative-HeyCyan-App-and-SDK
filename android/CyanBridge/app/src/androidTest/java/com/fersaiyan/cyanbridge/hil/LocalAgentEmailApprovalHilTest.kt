package com.fersaiyan.cyanbridge.hil

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.data.local.entity.PendingAction
import com.fersaiyan.cyanbridge.localagent.LocalAgentController
import com.fersaiyan.cyanbridge.localagent.LocalAgentIntents
import com.fersaiyan.cyanbridge.localagent.LocalAgentObservation
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs as RuntimePrefs
import com.fersaiyan.cyanbridge.localagent.TaskerExecutionBackend
import com.fersaiyan.cyanbridge.localagent.TaskerLocalAgentService
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Destructive/side-effect HIL: sends one real email to the repository owner's own test address.
 * It is gated by CYANBRIDGE_HIL_ENABLE_EMAIL_SEND and should only run on the dedicated lab target.
 */
@RunWith(AndroidJUnit4::class)
class LocalAgentEmailApprovalHilTest {
    @Test
    fun cyanBridgeResearchesSummarizesRequestsYesAndTaskerSendsSelfEmail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        HilTestSupport.requireTaskerStack(context)
        HilTestSupport.requireOrSkip(
            HilTestSupport.emailSendRequired,
            "Real-email HIL was not enabled for this instrumentation run",
        )

        val dao = MyApplication.database.pendingActionDao()
        val preexistingPending = runBlocking { dao.getActionsByStatus("pending") }
        assertTrue(
            "Dedicated HIL target has pre-existing pending actions; resolve them before running a real-send test: $preexistingPending",
            preexistingPending.isEmpty(),
        )

        val previousProvider = AutomationPrefs.getProviderType(context)
        val previousAutomationEnabled = AutomationPrefs.isLocalAgentAutomationEnabled(context)
        val previousMaxSteps = AutomationPrefs.getMaxSteps(context)
        val previousRequireConfirmation = RuntimePrefs.isRequireActionConfirmationEnabled(context)
        val previousScreenshotPlanning = RuntimePrefs.isScreenshotPlanningEnabled(context)
        val previousRemoteScreenshotUpload = RuntimePrefs.isRemoteScreenshotUploadEnabled(context)

        // Respect an explicitly selected Pro Subscription planner. Otherwise force the local-model
        // CyanBridge planner, never Tasker as the intelligence source for this architecture test.
        val providerForTest = if (previousProvider == AgentProviderType.PRO_SUBSCRIPTION) {
            AgentProviderType.PRO_SUBSCRIPTION
        } else {
            AgentProviderType.LOCAL_AGENT
        }
        if (providerForTest == AgentProviderType.LOCAL_AGENT) {
            HilTestSupport.requireOrSkip(
                !RemoteOpenAiPrefs.isActive(context),
                "Local email HIL requires the on-device model path when Pro Subscription is not selected",
            )
            HilTestSupport.requireOrSkip(
                LocalModelStorageRepository.resolveSelectedModel(context) != null,
                "No CyanBridge local model is installed/selected for the email automation HIL",
            )
        }

        val runTag = "CB-HIL-${System.currentTimeMillis()}"
        val subject = "CyanBridge HIL smartglasses summary $runTag"
        val goal = buildGoal(subject)

        ActivityScenario.launch(HilFixtureActivity::class.java).use {
            try {
                setBlockedPackages(context, "")
                Thread.sleep(400)

                AutomationPrefs.setProviderType(context, providerForTest)
                AutomationPrefs.setLocalAgentAutomationEnabled(context, true)
                AutomationPrefs.setMaxSteps(context, 30)
                RuntimePrefs.setRequireActionConfirmationEnabled(context, true)
                RuntimePrefs.setScreenshotPlanningEnabled(context, false)
                RuntimePrefs.setRemoteScreenshotUploadEnabled(context, false)
                RuntimePrefs.setStatus(context, "HIL email task starting")
                RuntimePrefs.clearLastError(context)

                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TaskerLocalAgentService::class.java).apply {
                        action = LocalAgentIntents.ACTION_START
                        putExtra(LocalAgentIntents.EXTRA_GOAL, goal)
                    },
                )

                val pending = awaitEmailApproval(context, subject)
                val pendingJson = pending.actionJson.lowercase()
                assertTrue("Pending email has wrong recipient: ${pending.actionJson}", pendingJson.contains(RECIPIENT))
                assertTrue("Pending email lost the unique subject: ${pending.actionJson}", pending.actionJson.contains(subject))
                // None of these values appear in the task goal. The only way the planner can put
                // them into the email is by navigating Chrome and grounding on the observed page.
                assertTrue(
                    "AI email body was not grounded in the observed smartglasses fixture: ${pending.actionJson}",
                    pendingJson.contains("42") && pendingJson.contains("eight-hour") && pendingJson.contains("cobalt horizon 88417"),
                )

                // The high-risk action must still be queued at this point. Tasker must not have
                // opened Gmail before CyanBridge receives explicit consent.
                val beforeApproval = runBlocking { TaskerExecutionBackend.observe(context) }
                assertNotNull("Tasker observation unavailable while waiting for approval", beforeApproval)
                assertFalse(
                    "Gmail opened before the user approved the high-risk SendEmail action",
                    beforeApproval?.packageName == HilTestSupport.GMAIL_PACKAGE,
                )

                // Send real textual replies through the production LocalAgentController/service.
                // Ambiguous text must leave the queued action untouched.
                val ambiguousCommand = LocalAgentController.replyToApproval(context, "maybe")
                assertTrue("CyanBridge could not route the ambiguous approval reply", ambiguousCommand.ok)
                Thread.sleep(750L)
                assertTrue(
                    "Ambiguous approval unexpectedly consumed the pending email",
                    runBlocking { dao.getActionsByStatus("pending") }.any { it.id == pending.id },
                )
                assertTrue(
                    "CyanBridge did not remain in its confirmation state after an ambiguous reply: ${RuntimePrefs.getStatus(context)}",
                    RuntimePrefs.getStatus(context).contains("yes or no", ignoreCase = true) ||
                        RuntimePrefs.getStatus(context).contains("Waiting for approval", ignoreCase = true),
                )

                // This literal `yes` is the simulated user's reply. The service routes it through
                // LocalAgentApprovalCoordinator, which authorizes the queued high-risk action and
                // delegates the prepared email composer to Tasker.
                val approvalCommand = LocalAgentController.replyToApproval(context, "yes")
                assertTrue("CyanBridge could not route the literal yes approval", approvalCommand.ok)
                val executedApproval = awaitApprovalExecution(pending.id)
                assertEquals("executed", executedApproval.status)
                assertTrue(
                    "Approved SendEmail did not execute through Tasker: ${executedApproval.result}",
                    executedApproval.result?.contains("SendEmail") == true &&
                        !executedApproval.result.orEmpty().contains("failed", ignoreCase = true),
                )

                val sentObservation = awaitSelfDeliveredMessage(context, subject)
                val sentText = sentObservation.screenText.orEmpty().lowercase()
                assertEquals(HilTestSupport.GMAIL_PACKAGE, sentObservation.packageName)
                assertTrue(
                    "Self-delivered Gmail message subject was not visible: ${sentObservation.screenText}",
                    sentText.contains(runTag.lowercase()),
                )
                assertFalse(
                    "The test still appears to be in the Gmail compose screen rather than after send: ${sentObservation.screenText}",
                    looksLikeComposer(sentObservation.screenText.orEmpty()),
                )

                val finalStatus = awaitTerminalStatus(context)
                val finalError = RuntimePrefs.getLastError(context)
                assertTrue("Local Agent did not report completion after the email send: $finalStatus", finalStatus.isNotBlank())
                assertTrue("Local Agent finished with an error: $finalError", finalError == "(none)")
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

    private fun awaitEmailApproval(context: Context, subject: String): PendingAction {
        val dao = MyApplication.database.pendingActionDao()
        val deadline = System.currentTimeMillis() + PLANNING_TIMEOUT_MS
        var lastStatus = RuntimePrefs.getStatus(context)
        while (System.currentTimeMillis() < deadline) {
            val pending = runBlocking { dao.getActionsByStatus("pending") }
                .firstOrNull { action ->
                    action.source == "tasker_agent" &&
                        action.actionJson.contains(RECIPIENT, ignoreCase = true) &&
                        action.actionJson.contains(subject)
                }
            if (pending != null) {
                assertTrue(
                    "Service did not expose the confirmation wait state: ${RuntimePrefs.getStatus(context)}",
                    RuntimePrefs.getStatus(context).contains("Waiting for approval", ignoreCase = true),
                )
                return pending
            }

            lastStatus = RuntimePrefs.getStatus(context)
            val error = RuntimePrefs.getLastError(context)
            if (error != "(none)" && error.isNotBlank()) {
                throw AssertionError("Local Agent failed before requesting email approval: status=$lastStatus error=$error")
            }
            Thread.sleep(1_000L)
        }
        throw AssertionError("Timed out waiting for SendEmail approval. Last status=$lastStatus")
    }

    private fun awaitApprovalExecution(id: Long): PendingAction {
        val dao = MyApplication.database.pendingActionDao()
        val deadline = System.currentTimeMillis() + APPROVAL_EXECUTION_TIMEOUT_MS
        var last: PendingAction? = null
        while (System.currentTimeMillis() < deadline) {
            last = runBlocking { dao.getActionById(id) }
            if (last?.status == "executed") return last
            if (last?.status == "rejected") {
                throw AssertionError("Literal yes unexpectedly rejected the pending email: ${last.result}")
            }
            Thread.sleep(500L)
        }
        throw AssertionError("Timed out waiting for the approved SendEmail action to execute. Last record=$last")
    }

    private fun awaitSelfDeliveredMessage(context: Context, subject: String): LocalAgentObservation {
        val deadline = System.currentTimeMillis() + DELIVERY_TIMEOUT_MS
        var lastObservation: LocalAgentObservation? = null
        while (System.currentTimeMillis() < deadline) {
            lastObservation = runBlocking { TaskerExecutionBackend.observe(context) }
            val text = lastObservation?.screenText.orEmpty()
            if (
                lastObservation?.packageName == HilTestSupport.GMAIL_PACKAGE &&
                text.contains(subject, ignoreCase = true) &&
                !looksLikeComposer(text)
            ) {
                return lastObservation
            }

            val error = RuntimePrefs.getLastError(context)
            if (error != "(none)" && error.isNotBlank()) {
                throw AssertionError(
                    "Local Agent failed after email approval: status=${RuntimePrefs.getStatus(context)} error=$error observation=$text",
                )
            }
            Thread.sleep(1_500L)
        }
        throw AssertionError(
            "Timed out waiting for the self-delivered Gmail subject after approval. " +
                "Last observation=${lastObservation?.screenText}",
        )
    }

    private fun awaitTerminalStatus(context: Context): String {
        val deadline = System.currentTimeMillis() + 60_000L
        var status = RuntimePrefs.getStatus(context)
        while (System.currentTimeMillis() < deadline) {
            status = RuntimePrefs.getStatus(context)
            if (!status.startsWith("Tasker step", ignoreCase = true) &&
                !status.startsWith("Waiting for approval", ignoreCase = true) &&
                !status.startsWith("Connecting", ignoreCase = true)
            ) {
                return status
            }
            Thread.sleep(500L)
        }
        return status
    }

    private fun looksLikeComposer(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains(RECIPIENT) &&
            (normalized.contains("send") || normalized.contains("subject") || normalized.contains("compose"))
    }

    private fun setBlockedPackages(context: Context, packages: String) {
        context.sendBroadcast(
            Intent(ACTION_HIL_SET_LOCALAGENT_BLOCKED)
                .setPackage(HilTestSupport.TASKER_PACKAGE)
                .putExtra("packages", packages),
        )
    }

    private fun buildGoal(subject: String): String =
        "Open Chrome. On the CyanBridge HIL Search page, search for 'latest smartglasses news', " +
            "open the first result, read only the first visible article without scrolling, and summarize the important facts you actually observe. " +
            "Then prepare an email to $RECIPIENT with the exact subject '$subject'. The body must clearly say this " +
            "is an automated CyanBridge HIL test using deterministic fixture data, not live news, and must summarize " +
            "the article's concrete product details plus its unique verification phrase exactly as observed. Use the send_email action for the prepared message. " +
            "Before any email submission, wait for CyanBridge to ask the user for confirmation. Only after the user replies yes may Tasker proceed " +
            "with Gmail and click the visible Send control. Because the recipient is this same test account, wait for " +
            "the sent message with subject '$subject' to appear in Gmail, then finish and tell the user the test email was sent."

    companion object {
        private const val RECIPIENT = "fernandosaiyan10@gmail.com"
        private const val ACTION_HIL_SET_LOCALAGENT_BLOCKED =
            "com.fersaiyan.cyanbridge.HIL_SET_LOCALAGENT_BLOCKED"
        private const val PLANNING_TIMEOUT_MS = 10 * 60_000L
        private const val APPROVAL_EXECUTION_TIMEOUT_MS = 45_000L
        private const val DELIVERY_TIMEOUT_MS = 2 * 60_000L
    }
}
