package com.fersaiyan.cyanbridge.llama

/** Small JNI surface backed by official llama.cpp/libmtmd, rather than the old RN wrapper. */
object UpstreamLlamaBridge {
    const val REVISION = "0c1e57098bba43ac29e6e3b677cdceebdd22334f"
    const val VISION = 1
    const val AUDIO = 2
    init { System.loadLibrary("cyan_llama") }

    fun interface TokenSink { fun onToken(bytes: ByteArray) }
    external fun load(modelPath: String, projectorPath: String?, contextSize: Int, threads: Int, embedding: Boolean): Long
    external fun release(handle: Long)
    external fun capabilities(handle: Long): Int
    external fun generate(handle: Long, prompt: ByteArray, imagePaths: Array<String>, audioPath: String?, maxTokens: Int,
        temperature: Float, topP: Float, topK: Int, repeatPenalty: Float, seed: Int, grammar: String?, sink: TokenSink, stats: IntArray): ByteArray
    external fun cancel(handle: Long)
    external fun tokenCount(handle: Long, text: ByteArray): Int
    external fun embed(handle: Long, text: ByteArray): FloatArray
}
