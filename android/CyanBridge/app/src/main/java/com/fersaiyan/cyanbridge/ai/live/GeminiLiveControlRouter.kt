package com.fersaiyan.cyanbridge.ai.live

import com.fersaiyan.cyanbridge.ai.decision.DecisionCandidate
import com.fersaiyan.cyanbridge.ai.decision.HeuristicDecisionEngine
import com.fersaiyan.cyanbridge.ai.decision.LiveControlOptions
import com.fersaiyan.cyanbridge.ai.decision.LocalDecision
import com.fersaiyan.cyanbridge.ai.decision.LocalDecisionEngine

/**
 * Pure-Kotlin Live control router, testable on JVM / Linux-PC CI without Android.
 *
 * Contract (mirrors the proposal):
 * - Gemini inputTranscription fragments accumulate in [appendTranscript].
 * - NEVER classify partial fragments. Only [classifyAtUtteranceEnd] runs the
 *   decision engine, and callers must gate it on speech=false
 *   (GeminiLiveSpeechActivityDetector onUserSpeechActivity(false)).
 * - Thresholds: END needs high confidence (false positive = annoying hangup is
 *   cheap but disruptive); LOCAL_AGENT handoff needs high confidence too because
 *   it stops Live and starts phone control.
 */
class GeminiLiveControlRouter(
    private val engineProvider: (() -> LocalDecisionEngine?)? = null,
    private val endThreshold: Float = 0.75f,
    private val agentThreshold: Float = 0.75f,
) {
    private val buffer = StringBuilder()
    private var lastDecision: LocalDecision? = null

    fun appendTranscript(fragment: String) {
        if (fragment.isBlank()) return
        if (buffer.isNotEmpty()) buffer.append(' ')
        buffer.append(fragment.trim())
        // Keep the buffer bounded for long sessions.
        if (buffer.length > MAX_BUFFER_CHARS) {
            buffer.delete(0, buffer.length - MAX_BUFFER_CHARS)
        }
    }

    fun bufferedText(): String = buffer.toString()

    fun clear() {
        buffer.clear()
        lastDecision = null
    }

    fun lastDecision(): LocalDecision? = lastDecision

    suspend fun classifyAtUtteranceEnd(): LiveControlAction {
        val utterance = buffer.toString().trim()
        if (utterance.isBlank()) return LiveControlAction.CONTINUE
        val candidates = LiveControlOptions.candidates()

        // 1) Cheap deterministic pass for obvious phrases (also covers offline).
        HeuristicDecisionEngine.classifyLiveControl(utterance)?.let { idx ->
            val action = indexToAction(idx, confidence = 0.9f)
            if (action != LiveControlAction.CONTINUE) {
                lastDecision = fakeDecision(idx, candidates, 0.9f, "heuristic:$idx")
                return action
            }
        }

        // 2) Model path (Jev-like single-letter).
        val engine = runCatching { engineProvider?.invoke() }.getOrNull()
        if (engine == null) return LiveControlAction.CONTINUE
        val decision = runCatching {
            engine.choose(utterance, candidates, debugTag = "live-control")
        }.getOrNull() ?: return LiveControlAction.CONTINUE
        lastDecision = decision
        return actionForDecision(decision)
    }

    /** Synchronous heuristic-only variant for unit tests and offline service path. */
    fun classifyHeuristicNow(): LiveControlAction {
        val utterance = buffer.toString().trim()
        if (utterance.isBlank()) return LiveControlAction.CONTINUE
        val idx = HeuristicDecisionEngine.classifyLiveControl(utterance) ?: return LiveControlAction.CONTINUE
        return indexToAction(idx, 0.9f)
    }

    private fun actionForDecision(d: LocalDecision): LiveControlAction {
        if (d.abstained) return LiveControlAction.CONTINUE
        return when (d.index) {
        LiveControlOptions.END_LIVE -> if (d.confidence >= endThreshold) LiveControlAction.END_LIVE else LiveControlAction.CONTINUE
        LiveControlOptions.LOCAL_AGENT -> if (d.confidence >= agentThreshold) LiveControlAction.LOCAL_AGENT else LiveControlAction.CONTINUE
        else -> LiveControlAction.CONTINUE
        }
    }

    private fun indexToAction(idx: Int, confidence: Float): LiveControlAction = when (idx) {
        LiveControlOptions.END_LIVE -> if (confidence >= endThreshold) LiveControlAction.END_LIVE else LiveControlAction.CONTINUE
        LiveControlOptions.LOCAL_AGENT -> if (confidence >= agentThreshold) LiveControlAction.LOCAL_AGENT else LiveControlAction.CONTINUE
        else -> LiveControlAction.CONTINUE
    }

    private fun fakeDecision(idx: Int, candidates: List<DecisionCandidate>, conf: Float, raw: String): LocalDecision {
        val probs = FloatArray(candidates.size) { i -> if (i == idx) conf else (1f - conf) / (candidates.size - 1).coerceAtLeast(1) }
        return LocalDecision(idx, candidates[idx].label, conf, probs, raw, usedFallback = true)
    }

    companion object {
        const val MAX_BUFFER_CHARS = 2000
    }
}

enum class LiveControlAction {
    CONTINUE,
    END_LIVE,
    LOCAL_AGENT,
}
