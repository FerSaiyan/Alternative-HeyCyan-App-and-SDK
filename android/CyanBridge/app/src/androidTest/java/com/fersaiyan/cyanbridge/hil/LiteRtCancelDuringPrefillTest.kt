package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localmodels.engine.EngineLoadConfig
import com.fersaiyan.cyanbridge.localmodels.engine.GenerationConfig
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtLocalInferenceEngine
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Play vitals 2.3.0: SIGSEGV in liblitertlm_jni.so when a conversation was closed while its prefill ran
 * (the prefill callback calls RunDecodeAsync on the session ~Conversation has already cleared; upstream
 * google-ai-edge/LiteRT-LM#3771). Cancels turns at different points of their prefill and checks the process
 * survives. Needs a provisioned .litertlm: -e liteRtModelPath /data/local/tmp/model.litertlm
 */
@RunWith(AndroidJUnit4::class)
class LiteRtCancelDuringPrefillTest {
    private val args = InstrumentationRegistry.getArguments()
    private val path = args.getString("liteRtModelPath").orEmpty()
    private val prompt = (1..300).joinToString(" ") { "word$it" } + "\nSummarize the list above."

    @Test fun cancelledTurnsDoNotCloseUnderRunningPrefill() = runBlocking {
        assumeTrue("Requires -e liteRtModelPath", File(path).isFile)
        val engine = LiteRtLocalInferenceEngine(InstrumentationRegistry.getInstrumentation().targetContext)
        engine.loadModel(path, EngineLoadConfig(2048, 4, LocalComputeBackend.CPU, 0, speculativeDecoding = false))
        try {
            for (delayMs in CANCEL_DELAYS_MS) {
                val job = launch(Dispatchers.IO) {
                    runCatching { engine.generate(config()) {} }
                }
                delay(delayMs)
                job.cancel()
                job.join()
            }
            // The engine is still usable after the cancelled turns.
            check(engine.generate(config().copy(prompt = "Say hi.", maxTokens = 8)) {}.text.isNotEmpty())
        } finally {
            engine.unloadModel()
        }
    }

    private fun config() = GenerationConfig(
        prompt = prompt, temperature = 0.7, topP = 0.9, topK = 40, maxTokens = 64,
        repetitionPenalty = 1.0, seed = 0, structuredJson = false,
    )

    private companion object {
        val CANCEL_DELAYS_MS = listOf(5L, 20L, 50L, 100L, 200L, 400L, 800L)
    }
}
