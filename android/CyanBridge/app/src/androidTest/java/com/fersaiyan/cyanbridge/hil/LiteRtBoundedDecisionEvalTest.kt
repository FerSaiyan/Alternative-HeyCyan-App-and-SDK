package com.fersaiyan.cyanbridge.hil

import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.ai.decision.DecisionCandidate
import com.fersaiyan.cyanbridge.ai.decision.SharedGemmaDecisionEngine
import com.fersaiyan.cyanbridge.localmodels.engine.EngineLoadConfig
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtLocalInferenceEngine
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the shipped shared-decision path over bounded-specialist canonical turns
 * (tools/benchmarks/bounded_specialist_contract.py) and records raw per-option
 * probabilities. Host-side scoring maps them back to option keys; this test
 * neither knows nor reads the expected choice.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtBoundedDecisionEvalTest {
    @Test fun scoresBoundedTurnsWithLoadedLiteRtModel() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("liteRtDecisionModelPath").orEmpty()
        val casesPath = args.getString("boundedDecisionCasesPath").orEmpty()
        assumeTrue(
            "Requires opt-in flag, an existing .litertlm and canonical cases",
            BuildConfig.LITERT_DECISION_PROTOTYPE && File(modelPath).isFile && File(casesPath).isFile,
        )
        val backend = LocalComputeBackend.valueOf(args.getString("liteRtDecisionBackend") ?: "CPU")
        val maxCases = args.getString("boundedDecisionMaxCases")?.toInt() ?: Int.MAX_VALUE
        val output = File(args.getString("boundedDecisionOutputPath") ?: "$casesPath.results.jsonl")
        val engine = LiteRtLocalInferenceEngine(instrumentation.targetContext)
        try {
            val loadStart = SystemClock.elapsedRealtime()
            val loaded = engine.loadModel(modelPath, EngineLoadConfig(4096, 4, backend, 0, speculativeDecoding = false))
            val header = JSONObject()
                .put("kind", "header").put("model", modelPath).put("activeBackend", loaded.activeBackend.name)
                .put("fallback", loaded.fallbackReason ?: JSONObject.NULL)
                .put("loadMs", SystemClock.elapsedRealtime() - loadStart).put("loadedPssKiB", Debug.getPss())
                .put("renderedProbe", engine.renderedDecisionPrompt("PROBE_USER_TEXT"))
            val scorer = SharedGemmaDecisionEngine(engine)
            var scored = 0
            output.bufferedWriter().use { writer ->
                writer.appendLine(header.toString())
                File(casesPath).useLines { lines ->
                    for (line in lines.filter { it.isNotBlank() }.take(maxCases)) {
                        val case = JSONObject(line)
                        val turn = case.getJSONObject("turn")
                        val options = turn.getJSONObject("options")
                        val keys = options.keys().asSequence().toList()
                        val candidates = keys.mapIndexed { index, key ->
                            DecisionCandidate(('A' + index).toString(), options.getJSONObject(key).getString("label"))
                        }
                        val start = SystemClock.elapsedRealtime()
                        // decisionState carries the contract instructions Laya also receives.
                        val state = case.optString("decisionState").ifEmpty { turn.getString("query") }
                        val decision = scorer.choose(state, candidates, "bounded-eval")
                        writer.appendLine(
                            JSONObject()
                                .put("kind", "case").put("caseId", case.getString("caseId"))
                                .put("optionIds", JSONArray(keys))
                                .put("selected", keys[decision.index])
                                .put("probabilities", JSONArray(decision.probabilities.map(Float::toDouble)))
                                .put("margin", decision.margin.toDouble())
                                .put("raw", decision.rawOutput)
                                .put("decisionMs", SystemClock.elapsedRealtime() - start)
                                .toString(),
                        )
                        writer.flush()
                        scored++
                    }
                }
                writer.appendLine(JSONObject().put("kind", "footer").put("cases", scored).put("finalPssKiB", Debug.getPss()).toString())
            }
            println("LITERT_BOUNDED_EVAL cases=$scored backend=${loaded.activeBackend} output=$output")
            assertTrue("No cases scored", scored > 0)
        } finally {
            engine.unloadModel()
        }
        Unit
    }
}
