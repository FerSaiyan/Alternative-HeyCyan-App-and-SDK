package com.fersaiyan.cyanbridge.hil

import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtEmbeddingFactory
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * EmbeddingGemma 2 `.litertlm` via LiteRT-LM 0.18: load time, per-embedding
 * latency over repeated requests, and optionally zero-shot request-only bounded
 * choices in the LiteRtBoundedDecisionEvalTest JSONL format (host reference:
 * tools/benchmarks/eval_bounded_specialist.py --backend embedding --request-only).
 */
@RunWith(AndroidJUnit4::class)
class LiteRtEmbeddingRuntimeTest {
    @Test fun embedsWithEmbeddingGemma2() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("embeddingModelPath").orEmpty()
        assumeTrue("Requires LiteRT-LM 0.18 and an existing embedding .litertlm",
            LiteRtEmbeddingFactory.AVAILABLE && File(modelPath).isFile)
        val backend = LocalComputeBackend.valueOf(args.getString("embeddingBackend") ?: "CPU")
        val repeats = args.getString("embeddingRepeats")?.toInt() ?: 20
        val context = instrumentation.targetContext
        val engine = LiteRtEmbeddingFactory.create(modelPath, backend, context.cacheDir.path,
            context.applicationInfo.nativeLibraryDir, args.getString("embeddingOutputSize")?.toInt())
        engine.use { embedder ->
            val prefix = "task: search result | query: "
            val document = "title: none | text: "
            val loadStart = SystemClock.elapsedRealtime()
            val first = embedder.embed(prefix + "turn on the flashlight")
            val coldMs = SystemClock.elapsedRealtime() - loadStart
            val related = embedder.embed(document + "Turn the phone flashlight on")
            val unrelated = embedder.embed(document + "Set future AI wake-word questions to image")
            assertEquals(first.dimension, related.dimension)
            assertTrue("Related text should score higher", dot(first.vector, related.vector) > dot(first.vector, unrelated.vector))
            val times = (1..repeats).map { embedder.embed(prefix + "request number $it: open the camera").elapsedMs }.sorted()
            println("LITERT_EMBEDDING_HIL backend=$backend dim=${first.dimension} coldFirstMs=$coldMs " +
                "p50Ms=${times[times.size / 2]} maxMs=${times.last()} pssKiB=${Debug.getPss()} " +
                "related=${dot(first.vector, related.vector)} unrelated=${dot(first.vector, unrelated.vector)}")
            val casesPath = args.getString("boundedDecisionCasesPath") ?: return@use
            val output = File(args.getString("boundedDecisionOutputPath") ?: "$casesPath.embedding.jsonl")
            output.bufferedWriter().use { writer ->
                writer.appendLine(JSONObject().put("kind", "header").put("model", modelPath)
                    .put("activeBackend", backend.name).put("variant", "request-only-zero-shot").toString())
                var scored = 0
                File(casesPath).forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val case = JSONObject(line)
                    val turn = case.getJSONObject("turn")
                    val options = turn.getJSONObject("options")
                    val keys = options.keys().asSequence().toList()
                    val request = JSONObject(turn.getString("query")).getString("request")
                    val started = SystemClock.elapsedRealtime()
                    val query = runBlocking { embedder.embed(prefix + request) }.vector
                    val scores = keys.map { key ->
                        dot(query, runBlocking { embedder.embed(document + options.getJSONObject(key).getString("label")) }.vector)
                    }
                    val ranked = scores.indices.sortedByDescending { scores[it] }
                    writer.appendLine(JSONObject().put("kind", "case").put("caseId", case.getString("caseId"))
                        .put("optionIds", JSONArray(keys)).put("selected", keys[ranked[0]])
                        .put("probabilities", JSONArray(scores.map(Float::toDouble)))
                        .put("margin", (scores[ranked[0]] - scores[ranked[1]]).toDouble())
                        .put("raw", "cosine").put("decisionMs", SystemClock.elapsedRealtime() - started).toString())
                    scored++
                }
                writer.appendLine(JSONObject().put("kind", "footer").put("cases", scored).toString())
            }
        }
        Unit
    }

    private fun dot(a: FloatArray, b: FloatArray): Float = a.indices.fold(0f) { sum, i -> sum + a[i] * b[i] }
}
