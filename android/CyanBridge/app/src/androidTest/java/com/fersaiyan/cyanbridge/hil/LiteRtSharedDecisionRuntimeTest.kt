package com.fersaiyan.cyanbridge.hil

import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.ai.decision.DecisionCandidate
import com.fersaiyan.cyanbridge.ai.decision.SharedGemmaDecisionEngine
import com.fersaiyan.cyanbridge.localmodels.engine.EngineLoadConfig
import com.fersaiyan.cyanbridge.localmodels.engine.GenerationConfig
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtLocalInferenceEngine
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Provisioned .litertlm only; captures smoke measurements, not a device benchmark. */
@RunWith(AndroidJUnit4::class)
class LiteRtSharedDecisionRuntimeTest {
    @Test fun chatAndDecisionScoresReuseLoadedLiteRtEngine() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("liteRtDecisionModelPath").orEmpty()
        assumeTrue("Requires opt-in flag and an existing .litertlm", BuildConfig.LITERT_DECISION_PROTOTYPE && File(path).isFile)
        val backend = LocalComputeBackend.valueOf(args.getString("liteRtDecisionBackend") ?: "CPU")
        val engine = LiteRtLocalInferenceEngine(instrumentation.targetContext)
        val candidates = listOf(DecisionCandidate("A", "Tap visible Search node 17"), DecisionCandidate("B", "Defer to planner"))
        try {
            val start = SystemClock.elapsedRealtime()
            val loaded = engine.loadModel(path, EngineLoadConfig(2048, 4, backend, 0, speculativeDecoding = false))
            val loadMs = SystemClock.elapsedRealtime() - start
            val loadedPssKiB = Debug.getPss()
            val scorer = SharedGemmaDecisionEngine(engine)
            val state = "Goal: Search YouTube for Linus Tech Tips. YouTube home: Search node 17 is visible and clickable."
            val decisionStart = SystemClock.elapsedRealtime()
            val before = scorer.choose(state, candidates, "litert-hil")
            val decisionMs = SystemClock.elapsedRealtime() - decisionStart
            val text = engine.generate(GenerationConfig("Say hello briefly.", 0.0, 0.9, 40, 16, 1.0, 1, false)) {}
            assertTrue("Empty chat", text.text.isNotBlank())
            val after = scorer.choose(state, candidates, "litert-hil")
            assertEquals(path, engine.loadedModelPath())
            assertArrayEquals(before.probabilities, after.probabilities, 1e-4f)
            assertTrue(before.abstained && after.abstained)
            try {
                engine.score("state", listOf("A", "A"))
                fail("Duplicate labels accepted")
            } catch (_: IllegalArgumentException) {}
            try {
                engine.score("very long state ".repeat(10000), listOf("A", "B"))
                fail("Overflow accepted")
            } catch (_: Exception) {}
            println("LITERT_DECISION_HIL backend=${loaded.activeBackend} loadMs=$loadMs decisionMs=$decisionMs loadedPssKiB=$loadedPssKiB finalPssKiB=${Debug.getPss()} probs=${after.probabilities.joinToString()} ${after.rawOutput}")
        } finally { engine.unloadModel() }
        assertFalse(engine.isModelLoaded())
        try {
            engine.score("state", listOf("A", "B"))
            fail("Unloaded engine accepted")
        } catch (_: IllegalStateException) {}
        Unit
    }
}
