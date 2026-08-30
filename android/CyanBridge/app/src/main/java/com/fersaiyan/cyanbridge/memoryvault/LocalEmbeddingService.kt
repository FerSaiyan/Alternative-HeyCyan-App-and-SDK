package com.fersaiyan.cyanbridge.memoryvault

import com.fersaiyan.cyanbridge.data.local.entity.LocalEmbeddingStoreEntity
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale
import kotlin.math.sqrt

object LocalEmbeddingService {
    private const val DIM = 64
    private const val MODEL_VERSION = "local_hash_v1"

    suspend fun upsertEmbedding(
        memoryRef: String,
        text: String,
        tags: List<String> = extractTags(text),
        modelVersion: String = MODEL_VERSION,
    ) = withContext(Dispatchers.IO) {
        val vector = embed(text)
        val arr = JSONArray()
        vector.forEach { arr.put(it) }
        val tagArr = JSONArray()
        tags.forEach { tagArr.put(it) }

        MyApplication.database.memoryVaultDao().upsertEmbedding(
            LocalEmbeddingStoreEntity(
                memoryRef = memoryRef,
                embeddingJson = arr.toString(),
                tagsJson = tagArr.toString(),
                modelVersion = modelVersion,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    fun embed(text: String): FloatArray {
        val out = FloatArray(DIM)
        val tokens = text
            .lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .take(2048)

        if (tokens.isEmpty()) return out

        for (token in tokens) {
            val idx = (token.hashCode().toLong() and 0x7fffffffL).toInt() % DIM
            out[idx] += 1f
        }

        var norm = 0f
        for (v in out) norm += v * v
        norm = sqrt(norm)
        if (norm > 0f) {
            for (i in out.indices) out[i] = out[i] / norm
        }
        return out
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val n = minOf(a.size, b.size)
        var dot = 0f
        for (i in 0 until n) dot += a[i] * b[i]
        return dot
    }

    fun extractTags(text: String): List<String> {
        val counts = linkedMapOf<String, Int>()
        text
            .lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 4 }
            .forEach { token ->
                counts[token] = (counts[token] ?: 0) + 1
            }

        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(8)
    }
}
