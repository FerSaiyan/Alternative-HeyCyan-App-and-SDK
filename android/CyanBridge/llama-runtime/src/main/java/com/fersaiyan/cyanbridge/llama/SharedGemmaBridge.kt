package com.fersaiyan.cyanbridge.llama

/** Opt-in text-only prototype; chat and decision prefill share one model/context. */
object SharedGemmaBridge {
    const val REVISION = "0c1e57098bba43ac29e6e3b677cdceebdd22334f"
    init { System.loadLibrary("cyan_shared_gemma") }
    fun interface TokenSink { fun onToken(bytes: ByteArray) }
    external fun load(path: String, contextSize: Int, threads: Int): Long
    external fun release(handle: Long)
    external fun cancel(handle: Long)
    external fun tokenCount(handle: Long, text: ByteArray): Int
    /** Output: candidate logits in requested order, followed by full-vocabulary label mass. */
    external fun decide(handle: Long, userPrompt: ByteArray, labels: Array<String>): FloatArray
    external fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float,
        topP: Float, topK: Int, repeatPenalty: Float, seed: Int, grammar: String?, sink: TokenSink, stats: IntArray): ByteArray
}
