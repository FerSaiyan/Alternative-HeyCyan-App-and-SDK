package com.fersaiyan.cyanbridge.ai.decision

/**
 * Jev-like decision via constrained single/few-token decoding.
 *
 * The default llama.cpp Kotlin wrapper
 * (llamacpp-kotlin 0.4.0 via LlamaAndroid) only exposes prompt completion, not
 * raw logits. The opt-in [SharedGemmaDecisionEngine] provides true native
 * logit scoring; this fallback asks for a few tokens with temperature 0
 * and parses the first valid option letter, tolerating <think>/reasoning
 * preamble from thinking models.
 *
 * The [generate] lambda is injected so unit tests and the Linux-PC CI can run
 * without a model: production wires it to AgentInferenceRouter, tests wire it
 * to a fake.
 */
class SingleTokenDecisionEngine(
    private val generate: suspend (systemPrompt: String, userPrompt: String) -> String,
    private val maxCandidates: Int = 8,
    private val winnerLogit: Float = 3.0f,
    private val promptBuilder: (String, List<DecisionCandidate>) -> String =
        DecisionPromptBuilder::buildClassificationPrompt,
) : LocalDecisionEngine {

    override suspend fun choose(
        state: String,
        candidates: List<DecisionCandidate>,
        debugTag: String,
    ): LocalDecision {
        require(candidates.size in 2..maxCandidates) { "Need 2..$maxCandidates candidates" }
        val userPrompt = promptBuilder(state, candidates)
        val systemPrompt = DecisionPromptBuilder.systemPromptForSingleLetter()
        // JVM-safe logging: android.util.Log crashes plain JUnit ("not mocked").
        // println keeps Linux-PC CI logs precise without requiring Robolectric.
        println("JEV_CI [$debugTag] decide stateChars=${state.length} candidates=${candidates.map { it.label }}")
        val raw = try {
            generate(systemPrompt, userPrompt)
        } catch (t: Throwable) {
            println("JEV_CI [$debugTag] generation failed: ${t.message}")
            throw t
        }
        println("JEV_CI [$debugTag] raw='${raw.take(300)}'")
        val parsed = DecisionOutputParser.parse(raw, candidates)
        val probs = DecisionMath.pseudoProbabilities(parsed.index, candidates.size, winnerLogit)
        val confidence = DecisionMath.calibratedConfidence(probs[parsed.index])
        println("JEV_CI [$debugTag] choice=${parsed.label} conf=$confidence probs=${probs.joinToString(",")}")
        return LocalDecision(
            index = parsed.index,
            label = parsed.label,
            confidence = confidence,
            probabilities = probs,
            rawOutput = raw.take(1000),
        )
    }

    companion object {
        const val TAG = "SingleTokenDecision"
    }
}

/**
 * Legacy delegate alias; this class does not read native logits.
 *
 * Use [SharedGemmaDecisionEngine] for the opt-in real-logit shadow prototype.
 * SingleTokenDecisionEngine remains the decoding fallback for other runtimes.
 */
class LlamaLogitDecisionEngine(
    private val delegate: LocalDecisionEngine,
) : LocalDecisionEngine {
    override suspend fun choose(
        state: String,
        candidates: List<DecisionCandidate>,
        debugTag: String,
    ): LocalDecision = delegate.choose(state, candidates, debugTag)
}
