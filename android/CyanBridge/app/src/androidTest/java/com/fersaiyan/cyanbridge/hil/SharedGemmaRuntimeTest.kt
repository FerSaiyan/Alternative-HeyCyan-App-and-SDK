package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.ai.decision.DecisionCandidate
import com.fersaiyan.cyanbridge.ai.decision.SharedGemmaDecisionEngine
import com.fersaiyan.cyanbridge.localmodels.engine.EngineLoadConfig
import com.fersaiyan.cyanbridge.localmodels.engine.GenerationConfig
import com.fersaiyan.cyanbridge.localmodels.engine.SharedGemmaInferenceEngine
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.localmodels.templates.PromptMessage
import com.fersaiyan.cyanbridge.localmodels.templates.PromptTemplateRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit existing GGUF only. No download, Tasker execution or phone settings mutation. */
@RunWith(AndroidJUnit4::class)
class SharedGemmaRuntimeTest {
    @Test fun chatAndShadowDecisionsReuseOneLoadedModel() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("sharedGemmaModelPath").orEmpty()
        assumeTrue("Requires -PsharedGemmaPrototype=true and an existing GGUF", BuildConfig.SHARED_GEMMA_PROTOTYPE && File(path).isFile)
        val template = args.getString("sharedGemmaTemplate") ?: "gemma_it"
        val engine = SharedGemmaInferenceEngine()
        try {
            engine.loadModel(path, EngineLoadConfig(2048, 4, LocalComputeBackend.CPU, 0))
            val candidates = listOf(DecisionCandidate("A", "Tap the observed Search node 17"), DecisionCandidate("B", "Defer to planner"))
            val scorer = SharedGemmaDecisionEngine(engine)
            val state = "Goal: Search YouTube for Linus Tech Tips. Current screen: YouTube home, Search node 17 is clickable."
            val before = scorer.choose(state, candidates, "hil")
            val prompt = PromptTemplateRegistry.renderPrompt(template, "", listOf(PromptMessage("user", "Say hello briefly.")))
            val text = engine.generate(GenerationConfig(prompt, 0.0, 0.9, 40, 16, 1.0, 1, false)) {}
            assertTrue("Empty chat", text.tokenCount > 0 && text.text.isNotBlank())
            val after = scorer.choose(state, candidates, "hil")
            assertEquals(path, engine.loadedModelPath())
            assertArrayEquals(before.probabilities, after.probabilities, 1e-5f)
            assertTrue(before.abstained && after.abstained)
            println("SHARED_GEMMA_HIL model=$path choice=${after.label} probs=${after.probabilities.joinToString()} ${after.rawOutput}")
        } finally { engine.unloadModel() }
        assertFalse(engine.isModelLoaded())
        Unit
    }
}
