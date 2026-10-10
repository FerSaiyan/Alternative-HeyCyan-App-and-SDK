package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.benchmark
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Raw LiteRT-LM prefill/decode throughput per backend (native benchmark mode),
 * for comparison with end-to-end decision latency. Each repeat loads a fresh
 * engine, so the first repeat on GPU includes kernel compilation in init time.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtBackendBenchmarkTest {
    @OptIn(ExperimentalApi::class)
    @Test fun prefillAndDecodeThroughput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("benchmarkModelPath").orEmpty()
        assumeTrue("Requires an existing .litertlm", File(modelPath).isFile)
        val backends = (args.getString("benchmarkBackends") ?: "CPU,GPU").split(',')
        val prefillLengths = (args.getString("benchmarkPrefillTokens") ?: "512,1024").split(',').map(String::toInt)
        val decodeTokens = args.getString("benchmarkDecodeTokens")?.toInt() ?: 128
        val repeats = args.getString("benchmarkRepeats")?.toInt() ?: 3
        val cacheDir = instrumentation.targetContext.cacheDir.path
        for (name in backends) {
            for (prefill in prefillLengths) {
                repeat(repeats) { index ->
                    val backend = when (name) {
                        "CPU" -> Backend.CPU()
                        "GPU" -> Backend.GPU()
                        // Qualcomm NPU (needs the NPU runtime AAR build and a *_Qualcomm_<SoC> bundle).
                        "NPU" -> Backend.NPU(instrumentation.targetContext.applicationInfo.nativeLibraryDir)
                        else -> error("Unsupported backend $name")
                    }
                    val info = benchmark(modelPath, backend, prefill, decodeTokens, cacheDir)
                    println(
                        "LITERT_BENCH backend=$name repeat=$index prefillTokens=${info.lastPrefillTokenCount} " +
                            "prefillTps=${"%.1f".format(info.lastPrefillTokensPerSecond)} " +
                            "decodeTokens=${info.lastDecodeTokenCount} decodeTps=${"%.1f".format(info.lastDecodeTokensPerSecond)} " +
                            "ttftS=${"%.3f".format(info.timeToFirstTokenInSecond)} initS=${"%.2f".format(info.initTimeInSecond)}",
                    )
                }
            }
        }
    }
}
