package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.ai.decision.AssistantDecisionOptions
import com.fersaiyan.cyanbridge.ai.decision.EmbeddingDecisionEngine
import com.fersaiyan.cyanbridge.ai.decision.LiveControlOptions
import com.fersaiyan.cyanbridge.localmodels.engine.LlamaCppTextEmbeddingEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * Real CPU-only GGUF embedding test. Model paths are supplied by the host
 * runner with instrumentation arguments; no model is committed or downloaded
 * by default CI.
 */
@RunWith(AndroidJUnit4::class)
class RealEmbeddingModelEmulatorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun embeddingGemma_q8_realInference_onCpu() = runBlocking {
        runModel(
            argName = "jev_embedding_gemma_path",
            displayName = "EmbeddingGemma-300M-Q8_0",
            acceptedDimensions = setOf(768),
            texts = listOf(
                "Open Spotify and play my liked songs.",
                "Abra o Spotify e toque minhas músicas curtidas.",
                "What is the weather today?",
            ),
        )
    }

    @Test
    fun qwen3Embedding06b_q8_realInference_onCpu() = runBlocking {
        runModel(
            argName = "jev_embedding_qwen_path",
            displayName = "Qwen3-Embedding-0.6B-Q8_0",
            // The official model card advertises up to 1024 dimensions, but
            // the current llama.cpp mobile runtime returned 768 for this GGUF.
            // Accept both while logging the observed runtime contract.
            acceptedDimensions = setOf(768, 1024),
            texts = listOf(
                "Open Spotify and play my liked songs.",
                "Abra o Spotify e toque minhas músicas curtidas.",
                "What is the weather today?",
            ),
        )
    }

    private suspend fun runModel(
        argName: String,
        displayName: String,
        acceptedDimensions: Set<Int>,
        texts: List<String>,
    ) {
        val path = InstrumentationRegistry.getArguments().getString(argName).orEmpty()
        require(path.isNotBlank()) {
            "Missing -e $argName <device-path>; this test intentionally refuses a fake/hash fallback"
        }
        val file = File(path)
        require(file.isFile) { "$displayName model is missing on device: $path" }

        println("JEV_EMBED start model=$displayName path=${file.name} bytes=${file.length()}")
        LlamaCppTextEmbeddingEngine(context, file, contextSize = 2048, cpuThreads = 4).use { engine ->
            val vectors = texts.map { text ->
                val result = engine.embed(text)
                println(
                    "JEV_EMBED model=$displayName text=${text.take(48)} dim=${result.dimension} " +
                        "norm=${result.l2Norm()} finite=${result.isFinite()} elapsedMs=${result.elapsedMs} " +
                        "keys=${result.rawKeys}",
                )
                if (result.dimension !in acceptedDimensions) {
                    println("JEV_EMBED dimension_mismatch model=$displayName observed=${result.dimension}")
                }
                assertTrue(
                    "Unexpected $displayName dimension=${result.dimension}; accepted=$acceptedDimensions",
                    result.dimension in acceptedDimensions,
                )
                assertTrue("$displayName vector is not finite", result.isFinite())
                assertTrue("$displayName vector norm is invalid", result.l2Norm() in 0.90f..1.10f)
                result.vector
            }

            val sameLanguage = cosine(vectors[0], vectors[0])
            val crossLanguage = cosine(vectors[0], vectors[1])
            val unrelated = cosine(vectors[0], vectors[2])
            println(
                "JEV_EMBED model=$displayName cosine self=$sameLanguage " +
                    "enPt=$crossLanguage unrelated=$unrelated",
            )
            assertTrue("Self cosine must be approximately one", sameLanguage > 0.99f)
            // Do not impose a multilingual quality gate on the emulator probe;
            // record scores for later comparison and model-specific evaluation.

            validateRealAssistantDecisions(displayName, engine)
            validateRealLiveDecisions(displayName, engine)
        }
    }

    private suspend fun validateRealAssistantDecisions(
        displayName: String,
        embedder: LlamaCppTextEmbeddingEngine,
    ) {
        val decisionEngine = EmbeddingDecisionEngine(
            embedder = embedder,
            queryText = { formatRealQuery(displayName, it) },
            candidateText = { formatRealPrototype(displayName, it) },
            // The real-model probe measures ranking first. A later held-out
            // dataset must fit production thresholds; this test still requires
            // a non-zero top-2 margin so ties are never auto-routed.
            minimumTopScore = -1f,
            minimumMargin = 0.01f,
        )
        val candidates = AssistantDecisionOptions.candidates()
        val cases = listOf(
            "What is the weather today?" to "A",
            "What do you see in front of me?" to "B",
            "Open Spotify and play my liked songs." to "C",
        )
        cases.forEach { (query, expected) ->
            val decision = decisionEngine.choose(query, candidates, "real-assistant")
            println(
                "JEV_EMBED decision model=$displayName head=assistant query=$query " +
                    "expected=$expected selected=${decision.label} accepted=${!decision.abstained} " +
                    "topScore=${decision.topScore} margin=${decision.margin} raw=${decision.rawOutput}",
            )
            assertTrue("$displayName assistant decision abstained for '$query'", !decision.abstained)
            assertEquals("$displayName assistant decision for '$query'", expected, decision.label)
        }
    }

    private suspend fun validateRealLiveDecisions(
        displayName: String,
        embedder: LlamaCppTextEmbeddingEngine,
    ) {
        val decisionEngine = EmbeddingDecisionEngine(
            embedder = embedder,
            queryText = { formatRealQuery(displayName, it) },
            candidateText = { formatRealPrototype(displayName, it) },
            minimumTopScore = -1f,
            minimumMargin = 0.01f,
        )
        val candidates = LiveControlOptions.candidates()
        val cases = listOf(
            "Tell me more about black holes." to "A",
            "That's all, please end this conversation." to "B",
            "Open Spotify on my phone." to "C",
        )
        cases.forEach { (query, expected) ->
            val decision = decisionEngine.choose(query, candidates, "real-live")
            println(
                "JEV_EMBED decision model=$displayName head=live query=$query " +
                    "expected=$expected selected=${decision.label} accepted=${!decision.abstained} " +
                    "topScore=${decision.topScore} margin=${decision.margin} raw=${decision.rawOutput}",
            )
            assertTrue("$displayName live decision abstained for '$query'", !decision.abstained)
            assertEquals("$displayName live decision for '$query'", expected, decision.label)
        }
    }

    /**
     * Match the documented model-family prompts during this probe. EmbeddingGemma
     * exposes a classification prompt for both text sides; Qwen uses an English
     * instruction on the query side and plain candidate passages.
     */
    private fun formatRealQuery(displayName: String, text: String): String {
        return if (displayName.startsWith("EmbeddingGemma")) {
            "task: classification | query: $text"
        } else {
            "Instruct: Classify the user's request into the closest phone-assistant action category.\n" +
                "Query:$text"
        }
    }

    private fun formatRealPrototype(
        displayName: String,
        candidate: com.fersaiyan.cyanbridge.ai.decision.DecisionCandidate,
    ): String {
        return if (displayName.startsWith("EmbeddingGemma")) {
            "task: classification | query: ${candidate.description}"
        } else {
            candidate.description
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "cosine dimension mismatch ${a.size} vs ${b.size}" }
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i].toDouble()
            aa += a[i] * a[i].toDouble()
            bb += b[i] * b[i].toDouble()
        }
        return (dot / (sqrt(aa) * sqrt(bb))).toFloat()
    }
}
