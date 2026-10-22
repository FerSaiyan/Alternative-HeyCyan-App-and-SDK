package com.fersaiyan.cyanbridge.localmodels.engine

import com.fersaiyan.cyanbridge.ai.decision.DecisionLogits
import java.lang.reflect.InvocationTargetException
import kotlin.math.exp

/** Optional fork API; keeps stock 0.16 builds usable for chat and device diagnostics. */
internal object LiteRtDecisionBinding {
    fun score(engine: Any, renderedPrompt: String, labels: List<String>): DecisionLogits {
        require(labels.size in 2..26 && labels.distinct().size == labels.size)
        require(labels.all { it.length == 1 && it[0] in 'A'..'Z' })
        val method = try {
            engine.javaClass.getMethod("scoreDecision", ByteArray::class.java, Array<String>::class.java)
        } catch (missing: NoSuchMethodException) {
            throw IllegalStateException("LiteRT decision scoring requires the patched 0.16.0 AAR", missing)
        }
        val values = try {
            method.invoke(engine, renderedPrompt.toByteArray(Charsets.UTF_8), labels.toTypedArray()) as FloatArray
        } catch (wrapped: InvocationTargetException) {
            throw wrapped.targetException
        }
        require(values.size == labels.size + 1) { "Invalid LiteRT decision score count" }
        val logProbabilities = values.copyOf(labels.size)
        require(logProbabilities.all { it.isFinite() && it <= 0f }) { "Invalid LiteRT log probabilities" }
        val mass = values.last()
        require(mass.isFinite() && mass in 0f..1f) { "Invalid LiteRT label mass" }
        val expectedMass = logProbabilities.sumOf { exp(it.toDouble()) }
        require(kotlin.math.abs(expectedMass - mass) <= 1e-4) { "Inconsistent LiteRT label mass" }
        // log p = logits - logsumexp(vocabulary); candidate softmax is identical.
        return DecisionLogits(logProbabilities, mass)
    }
}
