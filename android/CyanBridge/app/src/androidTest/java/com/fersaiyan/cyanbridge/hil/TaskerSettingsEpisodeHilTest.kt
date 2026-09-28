package com.fersaiyan.cyanbridge.hil

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localagent.LocalAgentAction
import com.fersaiyan.cyanbridge.localagent.LocalAgentObservation
import com.fersaiyan.cyanbridge.localagent.TaskerExecutionBackend
import com.fersaiyan.cyanbridge.localagent.UiActionCandidateBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** A separate reversible Tasker-observed episode; never an Artemis-labeled row. */
@RunWith(AndroidJUnit4::class)
class TaskerSettingsEpisodeHilTest {
    @Test fun opensSettingsNetworkAndChecksObservedDestination() = runBlocking {
        HilTestSupport.requireOrSkip(
            InstrumentationRegistry.getArguments().getString("hil_artemis_parity") == "true",
            "Opt in to reversible Tasker-only Settings navigation",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A later failed attempt must never leave an older verified export
        // looking like the current run's success.
        context.deleteFile("tasker_settings_episode_v1.json")
        HilTestSupport.requireTaskerStack(context)
        // Android's ordinary Settings launcher resumes the last nested page.
        // Explicitly reset the reversible fixture to Settings HOME; this
        // setup is not a supervised Tasker action. The labeled navigation is
        // still observed and executed exclusively through Tasker/AutoInput.
        context.startActivity(Intent().setClassName("com.android.settings", "com.android.settings.Settings")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        delay(700L)
        val before = requireNotNull(TaskerExecutionBackend.observe(context)) { "Missing pre-action Tasker observation" }
        assertEquals("com.android.settings", before.packageName)
        val preLabels = before.screenSnapshot?.nodes.orEmpty()
            .map { it.text.ifBlank { it.contentDescription } }.toSet()
        assertTrue("Settings home was not observed before the action",
            "Search Settings" in preLabels && "Network & internet" in preLabels &&
                "Internet" !in preLabels && "SIMs" !in preLabels)
        val goal = "In Settings, open Network & internet"
        val bounded = UiActionCandidateBuilder.build(goal, before)
        val matched = bounded.candidates.indices.filter { index ->
            bounded.keys[index] == "click_node" &&
                bounded.candidates[index].description.contains("Network & internet", ignoreCase = true)
        }
        assertEquals("Tasker candidate target must be uniquely grounded", 1, matched.size)
        val targetIndex = bounded.nodeIndices[matched.single()]
        assertTrue("Target has no Tasker node binding", targetIndex != null)
        assertTrue("Tasker did not observe Settings target", before.screenSnapshot?.nodes.orEmpty().any {
            it.index == targetIndex && (it.text == "Network & internet" || it.contentDescription == "Network & internet")
        })
        val startNs = System.nanoTime()
        val action = TaskerExecutionBackend.execute(context, LocalAgentAction.ClickText("Network & internet"))
        assertTrue("Tasker did not execute the observed Settings target: ${action.detail}", action.success)
        // Tasker's execution acknowledgement is NOT evidence of navigation.
        // Wait for the new screen to settle and accept only a fresh Tasker
        // observation with both destination controls.
        var after: LocalAgentObservation? = null
        var observed = emptySet<String>()
        repeat(4) {
            if (after != null) return@repeat
            delay(900L)
            val candidate = TaskerExecutionBackend.observe(context)
            if (candidate != null && candidate.createdAtMs > before.createdAtMs) {
                val labels = candidate.screenSnapshot?.nodes.orEmpty()
                    .map { it.text.ifBlank { it.contentDescription } }.toSet()
                if (candidate.packageName == "com.android.settings" &&
                    "Internet" in labels && "SIMs" in labels && "Search Settings" !in labels) {
                    after = candidate
                    observed = labels
                } else {
                    println("TASKER_SETTINGS_EPISODE postAttemptNodes=${candidate.screenSnapshot?.nodes?.size ?: 0} " +
                        "internetObserved=${"Internet" in labels} airplaneModeObserved=${"Airplane mode" in labels} " +
                        "simsObserved=${"SIMs" in labels} vpnObserved=${"VPN" in labels} " +
                        "rootSearchObserved=${"Search Settings" in labels}")
                }
            }
        }
        val confirmed = requireNotNull(after) { "Tasker did not observe Internet and SIMs after four attempts" }
        assertTrue("Network page absent after Tasker click", "Internet" in observed && "SIMs" in observed)
        val actionToVerificationMs = (System.nanoTime() - startNs) / 1_000_000L

        // Export only a strict public-label allowlist. Never persist raw
        // screen text, credentials, coordinates, account names or email data.
        val allowlisted = setOf("Search Settings", "Network & internet", "Connected devices", "Apps",
            "Notifications", "Sound & vibration", "Modes", "Display & touch", "Wallpaper & style",
            "Internet", "SIMs", "Airplane mode", "Hotspot & tethering", "Data Saver", "VPN")
        fun safeLabels(frame: LocalAgentObservation): JSONArray = JSONArray().apply {
            frame.screenSnapshot?.nodes.orEmpty().forEach { node ->
                val name = node.text.ifBlank { node.contentDescription }
                if (name in allowlisted) put(JSONObject().put("nodeIndex", node.index).put("label", name))
            }
        }
        fun hash(frame: LocalAgentObservation): String = MessageDigest.getInstance("SHA-256")
            .digest(frame.screenSnapshot?.toPromptText().orEmpty().toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertTrue("Tasker did not observe a changed screen", hash(before) != hash(confirmed))
        val row = JSONObject()
            .put("version", 1)
            .put("episodeId", "tasker_settings_network_${before.createdAtMs}")
            .put("source", "TASKER_AUTOINPUT")
            .put("goal", goal)
            .put("device", "authenticated_Pixel_9a")
            .put("operation", "TAP")
            .put("targetLabel", "Network & internet")
            .put("targetNodeIndex", targetIndex)
            .put("pre", JSONObject().put("provider", "TASKER_AUTOINPUT")
                .put("packageName", before.packageName).put("createdAtMs", before.createdAtMs)
                .put("snapshotSha256", hash(before)).put("allowlistedNodes", safeLabels(before)))
            .put("post", JSONObject().put("provider", "TASKER_AUTOINPUT")
                .put("packageName", confirmed.packageName).put("createdAtMs", confirmed.createdAtMs)
                .put("snapshotSha256", hash(confirmed)).put("allowlistedNodes", safeLabels(confirmed)))
            .put("outcomeVerified", true)
            .put("observedDestinationMarkers", JSONArray(listOf("Internet", "SIMs")))
            .put("actionToVerificationMs", actionToVerificationMs)
            .put("observationToVerificationMs", confirmed.createdAtMs - before.createdAtMs)
            .put("candidateCount", bounded.candidates.size)
        context.openFileOutput("tasker_settings_episode_v1.json", android.content.Context.MODE_PRIVATE).use {
            it.write(row.toString().toByteArray(Charsets.UTF_8))
        }
        println("TASKER_SETTINGS_EPISODE provider=TASKER_AUTOINPUT verified=true " +
            "candidates=${bounded.candidates.size} actionToVerificationMs=$actionToVerificationMs " +
            "observationToVerificationMs=${confirmed.createdAtMs - before.createdAtMs}")
    }
}
