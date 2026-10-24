package com.fersaiyan.cyanbridge.ai.live

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Mutable state of one Gemini Live conversation: lifecycle flags, token/setup inputs, resumption,
 * audio/image metering and the billing watermarks derived from them. It performs no I/O.
 */
internal class GeminiLiveSessionState {
    data class TurnUsage(val deltaInputMs: Long, val deltaOutputMs: Long, val deltaImages: Int)

    val active = AtomicBoolean(false)
    val setupComplete = AtomicBoolean(false)
    /** Local energy-detector speech state, retained even while Gemini is speaking. */
    val speechActive = AtomicBoolean(false)
    val modelSpeaking = AtomicBoolean(false)
    val meteredConnectionAttempted = AtomicBoolean(false)

    var state = GeminiLiveState.IDLE
    var tokenConfig: LiveTokenConfig? = null

    // Stored for building the Live setup systemInstruction to match the token's bidiGenerateContentSetup.
    // The token's setup is built server-side via buildLiveSystemInstruction(language, imagePrompt, systemPrompt)
    // and the Live setup we send must include the same systemInstruction + contextWindowCompression
    // to avoid 1008 or hanging in CONNECTING (seen when setup omitted these).
    var language: String = "en"
    var imagePrompt: String = ""
    var systemPrompt: String = ""

    var sessionResumptionHandle: String? = null
    var reconnectAttempt = 0
    var socketListeningStartedAtMs = 0L

    var inputAudioMs = 0L
    var outputAudioMs = 0L
    val visionDiagnostics = GeminiLiveVisionDiagnostics()
    val visualInputCount: Int get() = visionDiagnostics.snapshot().imagesQueued
    var visionReportId = UUID.randomUUID().toString()
    var visionReportConfig: LiveTokenConfig? = null

    private var lastBilledInputMs = 0L
    private var lastBilledOutputMs = 0L
    private var lastBilledImages = 0
    private var lastUsageTotalTokens = 0

    /** Active and past Gemini's setupComplete. */
    val isLive: Boolean get() = active.get() && setupComplete.get()

    /** Clears per-session metering and flags for a new user-started session. */
    fun resetForStart() {
        reconnectAttempt = 0
        meteredConnectionAttempted.set(false)
        inputAudioMs = 0L
        outputAudioMs = 0L
        visionDiagnostics.reset()
        visionReportId = UUID.randomUUID().toString()
        visionReportConfig = null
        lastBilledInputMs = 0L
        lastBilledOutputMs = 0L
        lastBilledImages = 0
        lastUsageTotalTokens = 0
        setupComplete.set(false)
        speechActive.set(false)
        modelSpeaking.set(false)
    }

    fun listeningDurationMs(nowMs: Long): Long =
        if (socketListeningStartedAtMs > 0L) nowMs - socketListeningStartedAtMs else 0L

    fun addInputAudioBytes(bytes: Int) {
        inputAudioMs += bytes.toLong() * 1_000L / (GeminiLiveProtocol.INPUT_SAMPLE_RATE_HZ * 2L)
    }

    fun addOutputAudioBytes(bytes: Int) {
        outputAudioMs += bytes.toLong() * 1_000L / (GeminiLiveProtocol.OUTPUT_SAMPLE_RATE_HZ * 2L)
    }

    /** True when [usage] carries billable tokens not already reported for this turn. */
    fun acceptUsageMetadata(usage: JSONObject): Boolean {
        val total = usage.optInt("totalTokenCount", 0)
        if (total <= 0) {
            // Fallback to prompt+response if total missing
            val prompt = usage.optInt("promptTokenCount", 0)
            val response = usage.optInt("responseTokenCount", 0)
            if (prompt <= 0 && response <= 0) return false
        }
        // Avoid double-billing same total within a turn
        val totalForDedup = usage.optInt("totalTokenCount", usage.optInt("promptTokenCount", 0) + usage.optInt("responseTokenCount", 0))
        if (totalForDedup == lastUsageTotalTokens && totalForDedup != 0) return false
        lastUsageTotalTokens = totalForDedup
        return true
    }

    /** Audio/image growth since the last billed turn, or null when nothing grew. */
    fun pendingTurnUsage(): TurnUsage? {
        val deltaInput = inputAudioMs - lastBilledInputMs
        val deltaOutput = outputAudioMs - lastBilledOutputMs
        val deltaImages = visualInputCount - lastBilledImages
        if (deltaInput <= 0 && deltaOutput <= 0 && deltaImages <= 0) return null
        return TurnUsage(deltaInput, deltaOutput, deltaImages)
    }

    fun markTurnBilled() {
        lastBilledInputMs = inputAudioMs
        lastBilledOutputMs = outputAudioMs
        lastBilledImages = visualInputCount
    }
}
