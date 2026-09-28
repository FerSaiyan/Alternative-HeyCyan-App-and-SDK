package com.fersaiyan.cyanbridge.localagent

import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.ai.decision.DecisionCandidate
import com.fersaiyan.cyanbridge.ai.decision.LocalDecision
import com.fersaiyan.cyanbridge.ai.decision.LocalDecisionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroundedNavigationTest {
    private val noModel = object : LocalDecisionEngine {
        override suspend fun choose(state: String, candidates: List<DecisionCandidate>, debugTag: String): LocalDecision {
            error("A fresh, unambiguous current-screen action must not invoke the model")
        }
    }

    private fun observation(pkg: String, vararg labels: String): LocalAgentObservation = LocalAgentObservation(
        createdAtMs = 1L, packageName = pkg, screenText = labels.joinToString(" "),
        screenSnapshot = LocalAgentScreenSnapshot(
            packageName = pkg, textSummary = labels.joinToString(" "),
            nodes = labels.mapIndexed { index, label ->
                LocalAgentScreenNode(index, 0, label, "", "", "TextView", "", true, false, false,
                    LocalAgentNodeBounds(index * 100, 100, index * 100 + 80, 150))
            },
        ),
    )

    private fun next(goal: String, obs: LocalAgentObservation) = runBlocking {
        RemoteUiControlLocalAgentBrain { noModel }.next(
            ApplicationProvider.getApplicationContext(),
            LocalAgentTaskState(goal = goal, maxSteps = 15, startedAtMs = 1L), obs,
        )
    }

    @Test fun `search suggestion chooses exact requested query rather than navigate up`() {
        val output = next("Open YouTube and search for Linus Tech Tips, then open a result",
            observation("com.google.android.youtube", "Navigate up", "Edit suggestion linus tech tips", "linus tech tips"))
        assertEquals(listOf(LocalAgentAction.ClickCoord(240, 125)), output.actions)
    }

    @Test fun `populated Chrome fixture form submits its Search button without retapping label`() {
        val original = observation("com.android.chrome", "Search news", "latest smartglasses news", "Search")
        val form = original.copy(screenSnapshot = original.screenSnapshot!!.copy(nodes =
            original.screenSnapshot.nodes.map { node ->
                if (node.index == 1) node.copy(viewId = "query", isEditable = true) else node
            }))
        assertEquals(listOf(LocalAgentAction.ClickCoord(240, 125)),
            next("Open Chrome and search for 'latest smartglasses news', then open the first result", form).actions)
    }

    @Test fun `first result uses observed full label even if candidate description truncates it`() {
        val output = next("Open Chrome and search for 'latest smartglasses news', then open the first result",
            observation("com.android.chrome", "News results", "Cobalt Horizon 88417 smartglasses — first result", "Your connection to this site is not secure"))
        assertEquals(listOf(LocalAgentAction.ClickCoord(140, 125)), output.actions)
    }

    @Test fun `Chrome security sheet offers only dismissal and reobservation`() {
        val output = next("Open Chrome and search for 'latest smartglasses news', then open the first result",
            observation("com.android.chrome", "Connection is not secure", "Cookies and site data", "Your connection to 127.0.0.1:34021 is not secure"))
        assertEquals(listOf(LocalAgentAction.GlobalBack), output.actions)
    }

    @Test fun `requested video playing stops the agent before it taps Pause`() {
        val goal = "Open YouTube, search for Linus Tech Tips, open a result and start playback"
        val player = observation("com.google.android.youtube", "Linus Tech Tips 16.9M subscribers",
            "Video player", "Pause video", "Next video")
        assertEquals(listOf(LocalAgentAction.Finish("Observed the requested video playing.")),
            next(goal, player).actions)
        assertEquals(false, YouTubePlaybackEvidence.playingRequestedVideo(goal,
            observation("com.google.android.youtube", "Linus Tech Tips 16.9M subscribers",
                "Video player", "Play video", "Paused")))
        assertEquals(false, YouTubePlaybackEvidence.playingRequestedVideo(goal,
            observation("com.google.android.youtube", "Unrelated channel", "Video player", "Pause video")))
    }
}
