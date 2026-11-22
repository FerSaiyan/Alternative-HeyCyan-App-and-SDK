package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Small greedy prompt eval of one LiteRT-LM model on one backend (default NPU), for comparing NPU builds on real
 * phones (Device Run / RTL) against a host reference. Not part of CI: it skips unless pe_model points at a file.
 *
 * Paths under /data/local/tmp are copied into the app's external files dir first (shell user).
 * Arguments: pe_model (.litertlm; several joined by "|" run one after another, pe_context likewise), pe_prompts (JSONL rows {id, system, user, expect: [lowercase keywords],
 * max_tokens?}), pe_backend (NPU | GPU | CPU), pe_context (engine maxNumTokens, default 4096),
 * pe_out (results JSONL, default <prompts>.<model>.<backend>.results.jsonl).
 * Each row runs in a fresh conversation with greedy sampling, at most max_tokens output tokens (default 96).
 * A row is a hit when the output contains any expect keyword (case-insensitive). Prints PE_RESULT / PE_SUMMARY lines.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtPromptEvalTest {
    @OptIn(ExperimentalApi::class)
    @Test fun promptEval() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        // Files pushed under /data/local/tmp (Device Run clears the app's own storage before the run) are copied into
        // the app's external files dir by the shell user, which may read /data/local/tmp and write there.
        val stageDir = File(instrumentation.targetContext.getExternalFilesDir(null), "litert-eval-in").apply { mkdirs() }
        fun staged(path: String): String {
            if (!path.startsWith("/data/local/tmp/")) return path
            val dst = File(stageDir, File(path).name)
            if (!dst.isFile) {
                val t = System.nanoTime()
                instrumentation.uiAutomation.executeShellCommand("cp $path ${dst.absolutePath}").use { pfd ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
                }
                println("PE_STAGE $path -> $dst bytes=${dst.length()} s=${"%.1f".format((System.nanoTime() - t) / 1e9)}")
            }
            return dst.absolutePath
        }
        val models = args.getString("pe_model").orEmpty().split('|').map(String::trim).filter(String::isNotEmpty).map(::staged)
        val promptsPath = staged(args.getString("pe_prompts").orEmpty())
        // Always report the phone's SoC identity (catalog keys are Build.SOC_MODEL values).
        val socInfo = if (android.os.Build.VERSION.SDK_INT >= 31) {
            "${android.os.Build.SOC_MANUFACTURER}|${android.os.Build.SOC_MODEL}"
        } else {
            android.os.Build.HARDWARE
        }
        println("PE_DEVICE model=${android.os.Build.MODEL} soc=$socInfo board=${android.os.Build.BOARD}")
        File(instrumentation.targetContext.getExternalFilesDir(null), "litert-eval").apply { mkdirs() }
            .resolve("device.txt").writeText("${android.os.Build.MODEL}|$socInfo|${android.os.Build.BOARD}\n")
        assumeTrue("Requires pe_model and pe_prompts files", models.isNotEmpty() && models.all { File(it).isFile } && File(promptsPath).isFile)
        val backendName = args.getString("pe_backend") ?: "NPU"
        val contexts = (args.getString("pe_context") ?: "4096").split('|').map { it.trim().toInt() }
        val rows = File(promptsPath).readLines().filter { it.isNotBlank() }.map(::JSONObject)
        for ((i, modelPath) in models.withIndex()) {
            val context = contexts.getOrElse(i) { contexts.last() }
            val outFile = File(args.getString("pe_out") ?: "$promptsPath.${File(modelPath).nameWithoutExtension}.$backendName.results.jsonl")
            val backend = when (backendName) {
                "CPU" -> Backend.CPU()
                "GPU" -> Backend.GPU()
                "NPU" -> Backend.NPU(vendorDispatchDir(instrumentation.targetContext, args.getString("pe_dispatch_vendor")))
                else -> error("Unsupported backend $backendName")
            }
            runCatching { evalModel(instrumentation.targetContext.cacheDir.path, modelPath, backend, backendName, context, rows, outFile) }
                .onFailure {
                    println("PE_MODEL_FAILED model=${File(modelPath).name} error=$it")
                    outFile.appendText(JSONObject(mapOf("event" to "model_failed", "model" to File(modelPath).name, "error" to it.toString())).toString() + "\n")
                }
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun evalModel(
        cacheDir: String, modelPath: String, backend: Backend, backendName: String, context: Int,
        rows: List<JSONObject>, outFile: File,
    ) {
        val t0 = System.nanoTime()
        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = context,
                cacheDir = cacheDir,
            ),
        ).also { it.initialize() }
        val initS = (System.nanoTime() - t0) / 1e9
        println("PE_INIT model=${File(modelPath).name} backend=$backendName context=$context initS=${"%.2f".format(initS)}")
        outFile.appendText(JSONObject(mapOf("event" to "init", "model" to File(modelPath).name, "backend" to backendName,
            "context" to context, "init_s" to initS)).toString() + "\n")
        var hits = 0
        try {
            for (row in rows) {
                val expect = row.optJSONArray("expect") ?: JSONArray()
                val keywords = (0 until expect.length()).map { expect.getString(it).lowercase() }
                val sampler = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0)
                val start = System.nanoTime()
                var firstTokenS = -1.0
                val out = StringBuilder()
                val error = runCatching {
                    engine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(row.optString("system", "")),
                            samplerConfig = sampler,
                        ),
                    ).use { conversation ->
                        val done = java.util.concurrent.CountDownLatch(1)
                        var failure: Throwable? = null
                        conversation.sendMessageAsync(
                            Contents.of(row.getString("user")),
                            object : MessageCallback {
                                override fun onMessage(message: Message) {
                                    val text = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                                    if (firstTokenS < 0 && text.isNotEmpty()) firstTokenS = (System.nanoTime() - start) / 1e9
                                    synchronized(out) {
                                        val prev = out.toString()
                                        // Same rule as the app: tolerate cumulative or delta messages.
                                        out.append(
                                            when {
                                                prev.isEmpty() -> text
                                                text.startsWith(prev) -> text.substring(prev.length)
                                                prev.startsWith(text) -> ""
                                                else -> text
                                            },
                                        )
                                    }
                                }
                                override fun onDone() = done.countDown()
                                override fun onError(throwable: Throwable) { failure = throwable; done.countDown() }
                            },
                            emptyMap<String, Any>(),
                            maxOutputToken = row.optInt("max_tokens", 96),
                        )
                        check(done.await(600, java.util.concurrent.TimeUnit.SECONDS)) { "turn did not finish in 600 s" }
                        failure?.let { throw it }
                    }
                }.exceptionOrNull()
                val totalS = (System.nanoTime() - start) / 1e9
                val output = out.toString()
                val hit = keywords.isNotEmpty() && keywords.any { output.lowercase().contains(it) }
                if (hit) hits++
                val result = JSONObject(
                    mapOf(
                        "id" to row.optString("id"), "hit" to hit, "expect" to JSONArray(keywords), "output" to output,
                        "ttft_s" to firstTokenS, "total_s" to totalS, "error" to error?.toString(),
                    ),
                )
                outFile.appendText(result.toString() + "\n")
                println("PE_RESULT $result")
            }
        } finally {
            engine.close()
        }
        val summary = "PE_SUMMARY model=${File(modelPath).name} backend=$backendName hits=$hits/${rows.size} initS=${"%.2f".format(initS)}"
        outFile.appendText(JSONObject(mapOf("event" to "summary", "hits" to hits, "rows" to rows.size)).toString() + "\n")
        println(summary)
    }

    /**
     * LiteRT 2.3.0 loads the first libLiteRtDispatch_*.so it finds in the dispatch directory, and the multi-vendor NPU
     * AAR ships one per vendor. Build a directory of symlinks to every native library except the other vendors'
     * dispatch libraries. The vendor comes from [override] (Qualcomm | MediaTek | GoogleTensor | Samsung) or the SoC.
     */
    private fun vendorDispatchDir(context: android.content.Context, override: String?): String {
        val native = File(context.applicationInfo.nativeLibraryDir)
        val manufacturer = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MANUFACTURER.orEmpty() else ""
        val socModel = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL.orEmpty() else ""
        val vendor = override ?: when {
            manufacturer.contains("mediatek", true) || socModel.startsWith("MT", true) -> "MediaTek"
            manufacturer.contains("google", true) || socModel.contains("tensor", true) -> "GoogleTensor"
            manufacturer.contains("samsung", true) || socModel.startsWith("s5e", true) -> "Samsung"
            else -> "Qualcomm"
        }
        val keep = "libLiteRtDispatch_$vendor.so"
        val dir = File(context.noBackupFilesDir, "pe-dispatch/$vendor").apply { deleteRecursively(); mkdirs() }
        native.listFiles().orEmpty().filter { it.name.endsWith(".so") }
            .filter { !it.name.startsWith("libLiteRtDispatch_") || it.name == keep }
            .forEach { android.system.Os.symlink(it.absolutePath, File(dir, it.name).absolutePath) }
        val present = File(dir, keep).exists()
        println("PE_DISPATCH vendor=$vendor dir=$dir dispatchPresent=$present libs=${dir.list()?.size}")
        check(present) { "No $keep in ${native.path}: ${native.list()?.joinToString()}" }
        return dir.path
    }
}
