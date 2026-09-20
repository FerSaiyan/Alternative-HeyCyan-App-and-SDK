package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.ai.decision.DecisionCandidate
import com.fersaiyan.cyanbridge.ai.decision.DecisionOutputParser
import com.fersaiyan.cyanbridge.ai.router.AgentInferenceRouter
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in real-model probe for the bounded Jev-like generation fallback.
 *
 * Model weights and CPU/GPU settings are provisioned outside the APK. This test intentionally
 * performs a cold call followed by a warm call so emulator acceleration can be compared without
 * treating model load time as inference latency.
 */
@RunWith(AndroidJUnit4::class)
class RealLocalDecisionModelEmulatorTest {
    @Test
    fun selectedModelChoosesExpectedBoundedActionColdAndWarm() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        HilTestSupport.requireOrSkip(
            HilTestSupport.localAiRequired,
            "Set -e hil_local_ai true to run the real local-decision probe",
        )
        HilTestSupport.requireOrSkip(
            !RemoteOpenAiPrefs.isActive(context),
            "Real local-decision probe requires the on-device model path",
        )
        val model = LocalModelStorageRepository.resolveSelectedModel(context)
        HilTestSupport.requireOrSkip(model != null, "No selected local model")

        val candidates = listOf(
            DecisionCandidate("A", "Open YouTube, the app explicitly requested by the goal"),
            DecisionCandidate("B", "Tap an unrelated test control"),
            DecisionCandidate("C", "Finish before YouTube is open"),
        )
        suspend fun invoke(): Pair<String, Long> {
            val started = System.nanoTime()
            val raw = AgentInferenceRouter.completeDecisionToken(
                context = context,
                sessionId = "real-local-decision-probe",
                systemPrompt = "Reply with exactly one capital letter: A, B, or C. No explanation.",
                userPrompt = """
                    Goal: Open YouTube and search for Linus Tech Tips.
                    Current app: CyanBridge test fixture.
                    A = Open YouTube, the app explicitly requested by the goal.
                    B = Tap an unrelated test control.
                    C = Finish before YouTube is open.
                    Answer:
                """.trimIndent(),
                maxTokens = 8,
                providerType = AgentProviderType.LOCAL_AGENT,
            )
            return raw to ((System.nanoTime() - started) / 1_000_000L)
        }

        val (coldRaw, coldMs) = invoke()
        val cold = DecisionOutputParser.parse(coldRaw, candidates)
        println("JEV_REAL_LOCAL model=${model?.displayName} phase=cold elapsedMs=$coldMs raw='${coldRaw.take(120)}'")
        assertEquals("A", cold.label)

        val (warmRaw, warmMs) = invoke()
        val warm = DecisionOutputParser.parse(warmRaw, candidates)
        println("JEV_REAL_LOCAL model=${model?.displayName} phase=warm elapsedMs=$warmMs raw='${warmRaw.take(120)}'")
        assertEquals("A", warm.label)
    }
}
