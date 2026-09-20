package com.fersaiyan.cyanbridge.localmodels.engine

/** Model-independent embedding contract. It is deliberately separate from text generation. */
interface TextEmbeddingEngine : AutoCloseable {
    suspend fun embed(text: String): TextEmbeddingResult

    override fun close() = Unit
}

data class TextEmbeddingResult(
    val vector: FloatArray,
    val modelPath: String,
    val elapsedMs: Long,
    val backend: String,
    val rawKeys: Set<String> = emptySet(),
) {
    val dimension: Int get() = vector.size

    fun l2Norm(): Float {
        var sum = 0.0
        vector.forEach { value -> sum += value.toDouble() * value.toDouble() }
        return kotlin.math.sqrt(sum).toFloat()
    }

    fun isFinite(): Boolean = vector.all { it.isFinite() }
}
