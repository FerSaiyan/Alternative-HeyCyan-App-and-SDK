package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.localmodels.engine.EngineLoadConfig
import com.fersaiyan.cyanbridge.localmodels.engine.GenerationConfig
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtLocalInferenceEngine
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Photo + spoken questions when the photo and the recording arrive in parallel (LiteRtLocalInferenceEngine
 * prepareFirstPart), on the app's engine with one loaded model. Per question (glasses_questions: color_of_object
 * with socks_yellow.jpg, read_sign with sign_no_parking.jpg, plant questions with plant_aloe.jpg; en + pt-BR):
 * - "together": both parts in one request after both arrived (2.5.0);
 * - "photo_first" / "audio_first": the first part prefilled, then the request when the other arrives, with and
 *   without the one-word model reply after the prefilled part ([ack]).
 * Times run from "the second part is ready" (the request) to the first token. Answers are logged for review.
 * Not part of CI: skips unless pq_model points at a .litertlm file. pq_backend GPU | NPU | CPU, pq_out.
 */
@RunWith(AndroidJUnit4::class)
class PhotoQuestionPrefillTest {
    @Test fun photoQuestionPrefill() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val source = args.getString("pq_model").orEmpty()
        val stageDir = File(context.getExternalFilesDir(null), "litert-eval-in").apply { mkdirs() }
        val model = if (source.startsWith("/data/local/tmp/")) File(stageDir, File(source).name).also { dst ->
            if (!dst.isFile) instrumentation.uiAutomation.executeShellCommand("cp $source ${dst.absolutePath}").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes() }
            }
        } else File(source)
        assumeTrue("Requires pq_model", model.isFile)
        val out = File(args.getString("pq_out") ?: File(context.getExternalFilesDir(null), "litert-eval/photo_prefill.jsonl").path)
        out.parentFile?.mkdirs()
        fun log(map: Map<String, Any?>) {
            val line = JSONObject(map).toString()
            println("PQ $line")
            out.appendText(line + "\n")
        }
        val assets = instrumentation.context.assets
        fun asset(name: String) = File(context.cacheDir, "pq_$name").also { f ->
            if (!f.isFile) assets.open("glasses_questions/$name").use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        val pairs = assets.list("glasses_questions").orEmpty().filter { it.endsWith(".wav") }.mapNotNull { wav ->
            val photo = when {
                "color_of_object" in wav -> "socks_yellow.jpg"
                "read_sign" in wav -> "sign_no_parking.jpg"
                else -> null
            } ?: return@mapNotNull null
            Triple(wav, asset(wav), asset(photo))
        }

        val backend = when (args.getString("pq_backend")) {
            "CPU" -> LocalComputeBackend.CPU
            "NPU" -> LocalComputeBackend.NPU_EXPERIMENTAL
            else -> LocalComputeBackend.GPU
        }
        val engine = LiteRtLocalInferenceEngine(context)
        val load = engine.loadModel(model.path, EngineLoadConfig(4096, 4, backend, -1, speculativeDecoding = false))
        log(mapOf("event" to "loaded", "backend" to load.activeBackend.name, "model" to model.name, "device" to android.os.Build.MODEL))
        val system = ImageQuestionDefaults.spokenQuestionInstruction("en")
        val clock = ImageQuestionDefaults.currentContextInstruction()
        fun config(image: File?, audio: File?) = GenerationConfig(
            prompt = "", temperature = 0.0, topP = 1.0, topK = 1, maxTokens = 48, repetitionPenalty = 1.0, seed = 0,
            structuredJson = false, imagePaths = listOfNotNull(image?.path), audioPath = audio?.path,
            systemInstruction = system, turnContext = clock,
        )
        // Warm-up (first request pays one-time costs), not logged.
        runCatching { engine.generate(config(pairs.first().third, pairs.first().second)) { } }

        val modes = listOf("together", "photo_first", "audio_first", "photo_first_noack", "audio_first_noack")
        for (mode in modes) {
            LiteRtLocalInferenceEngine.photoTurnAck = !mode.endsWith("noack")
            for ((name, wav, photo) in pairs) {
                var prefillS: Double? = null
                if (mode.startsWith("photo_first") || mode.startsWith("audio_first")) {
                    val t = System.nanoTime()
                    val ok = engine.prepareFirstPart(if (mode.startsWith("photo")) config(photo, null) else config(null, wav))
                    prefillS = (System.nanoTime() - t) / 1e9
                    if (!ok) log(mapOf("event" to "prefill_failed", "mode" to mode, "clip" to name))
                }
                val started = System.nanoTime()
                var firstToken = 0L
                val result = runCatching {
                    engine.generate(config(photo, wav)) { if (firstToken == 0L && it.isNotEmpty()) firstToken = System.nanoTime() }
                }
                log(mapOf(
                    "event" to "q", "mode" to mode, "clip" to name, "prefill_s" to prefillS,
                    "ttft_s" to if (firstToken > 0) (firstToken - started) / 1e9 else null,
                    "total_s" to (System.nanoTime() - started) / 1e9,
                    "reply" to result.getOrNull()?.text?.take(160), "error" to result.exceptionOrNull()?.toString(),
                ))
            }
        }
        LiteRtLocalInferenceEngine.photoTurnAck = true
        engine.unloadModel()
    }
}
