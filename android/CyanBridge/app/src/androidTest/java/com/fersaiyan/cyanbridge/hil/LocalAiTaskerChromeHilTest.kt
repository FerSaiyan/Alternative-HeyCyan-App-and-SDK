package com.fersaiyan.cyanbridge.hil

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.localagent.LocalAgentIntents
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs as RuntimePrefs
import com.fersaiyan.cyanbridge.localagent.TaskerExecutionBackend
import com.fersaiyan.cyanbridge.localagent.TaskerLocalAgentService
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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

        HilWebFixtureServer().use { webFixture ->
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

                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(webFixture.searchUrl))
                        .setPackage(CHROME_PACKAGE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                val fixtureObservation = awaitFixtureSearchPage(context)
                assertTrue(
                    "Tasker did not observe the deterministic Chrome fixture: ${fixtureObservation?.screenText}",
                    fixtureObservation?.screenText?.contains(SEARCH_MARKER) == true,
                )

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

                // Prove the navigation itself ended on the real Chrome article through the same
                // production Tasker/AutoInput observation boundary used by the planner.
                val finalObservation = runBlocking { TaskerExecutionBackend.observe(context) }
                assertTrue(
                    "Tasker did not observe Chrome after local-AI navigation: ${finalObservation?.packageName}",
                    finalObservation?.packageName == CHROME_PACKAGE,
                )
                assertTrue(
                    "Tasker did not reach the first-result article marker: ${finalObservation?.screenText}",
                    finalObservation?.screenText?.contains(ARTICLE_MARKER) == true,
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
    }

    private fun awaitFixtureSearchPage(context: Context): com.fersaiyan.cyanbridge.localagent.LocalAgentObservation? {
        val deadline = System.currentTimeMillis() + FIXTURE_TIMEOUT_MS
        var observation: com.fersaiyan.cyanbridge.localagent.LocalAgentObservation? = null
        while (System.currentTimeMillis() < deadline) {
            observation = runBlocking { TaskerExecutionBackend.observe(context) }
            if (
                observation?.packageName == CHROME_PACKAGE &&
                observation.screenText?.contains(SEARCH_MARKER) == true
            ) {
                return observation
            }
            Thread.sleep(500L)
        }
        return observation
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
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val SEARCH_MARKER = "CYANBRIDGE_HIL_WEB_SEARCH_72941"
        private const val ARTICLE_MARKER = "CYANBRIDGE_HIL_WEB_ARTICLE_72941"
        private const val FIXTURE_TIMEOUT_MS = 45_000L
        private const val LOCAL_AI_TIMEOUT_MS = 6 * 60_000L
        private const val GOAL =
            "Open Chrome. On the CyanBridge HIL Search page, type the query 'local agent architecture' " +
                "into Search query, click the visible Search button, open the first result, read only " +
                "the first visible result page without scrolling, then finish with a concise summary " +
                "for the user of what the page says."
    }

    private class HilWebFixtureServer : Closeable {
        private val running = AtomicBoolean(true)
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val worker = thread(name = "cyanbridge-hil-web", isDaemon = true) {
            while (running.get()) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { respond(socket) }
                runCatching { socket.close() }
            }
        }

        val searchUrl: String = "http://127.0.0.1:${server.localPort}/"

        private fun respond(socket: Socket) {
            socket.soTimeout = 5_000
            val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
            val requestLine = reader.readLine().orEmpty()
            while (reader.readLine()?.isNotEmpty() == true) Unit
            val path = requestLine.split(' ').getOrNull(1).orEmpty()
            val body = when {
                path.startsWith("/article") -> ARTICLE_HTML
                path.startsWith("/search") -> RESULTS_HTML
                else -> SEARCH_HTML
            }.toByteArray(StandardCharsets.UTF_8)
            socket.getOutputStream().buffered().use { out ->
                out.write(
                    ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII),
                )
                out.write(body)
            }
        }

        override fun close() {
            running.set(false)
            runCatching { server.close() }
            worker.join(2_000L)
        }

        companion object {
            private const val SEARCH_HTML = """
                <!doctype html><html><head><meta name="viewport" content="width=device-width"></head>
                <body><h1>CYANBRIDGE_HIL_WEB_SEARCH_72941</h1>
                <form action="/search" method="get">
                  <label for="query">Search query</label>
                  <input id="query" name="q" aria-label="Search query">
                  <button type="submit">Search</button>
                </form></body></html>
            """
            private const val RESULTS_HTML = """
                <!doctype html><html><head><meta name="viewport" content="width=device-width"></head>
                <body><h1>Search results</h1>
                <a href="/article">Borealis local-agent architecture — first result</a>
                <a href="/unrelated">Unrelated second result</a></body></html>
            """
            private const val ARTICLE_HTML = """
                <!doctype html><html><head><meta name="viewport" content="width=device-width"></head>
                <body><h1>CYANBRIDGE_HIL_WEB_ARTICLE_72941 — Borealis local-agent architecture</h1>
                <p>Borealis uses exactly 37 amber modules. CyanBridge owns planning and safety,
                while Tasker only observes the screen and executes approved UI actions.</p></body></html>
            """
        }
    }
}
