package com.fersaiyan.cyanbridge.localmodels.engine

import android.content.Context
import kotlin.math.roundToInt

/**
 * Float view of one per-SoC NPU build: quantizes float inputs and dequantizes outputs with the artifact's own tensor
 * specs, so a feature handles every vendor's build (float I/O on Tensor, int8 image input on MediaTek, int16 audio on
 * MediaTek WeSpeaker, ...) the same way. Not thread-safe; callers serialize access.
 */
internal class NpuFloatRunner private constructor(
    private val model: LiteRtNpuModel,
    val artifact: NpuArtifacts.Artifact,
) : AutoCloseable {

    /** Runs with float inputs in signature order; returns float outputs in signature order. */
    fun run(vararg inputs: FloatArray): List<FloatArray> {
        require(inputs.size == artifact.inputs.size) { "${artifact.localName} takes ${artifact.inputs.size} inputs" }
        val tensors = inputs.mapIndexed { index, values ->
            val spec = artifact.inputs[index]
            require(values.size == spec.elements) { "Input ${spec.name} has ${values.size} values, expected ${spec.elements}" }
            quantize(values, spec)
        }
        val outputs = model.runTensors(tensors, artifact.outputs.map { it.dtype })
        return outputs.mapIndexed { index, tensor -> dequantize(tensor, artifact.outputs[index]) }
    }

    override fun close() = model.close()

    companion object {
        fun open(context: Context, artifact: NpuArtifacts.Artifact, path: String): NpuFloatRunner =
            NpuFloatRunner(LiteRtNpuModelFactory.open(context, path), artifact)

        internal fun quantize(values: FloatArray, spec: NpuArtifacts.TensorSpec): NpuTensor = when (spec.dtype) {
            NpuDType.FLOAT32 -> NpuTensor.Floats(values)
            NpuDType.INT8 -> NpuTensor.Bytes(ByteArray(values.size) { q(values[it], spec, -128, 127).toByte() })
            NpuDType.UINT8 -> NpuTensor.Bytes(ByteArray(values.size) { q(values[it], spec, 0, 255).toByte() })
            NpuDType.INT16 -> NpuTensor.Shorts(ShortArray(values.size) { q(values[it], spec, -32768, 32767).toShort() })
        }

        internal fun dequantize(tensor: NpuTensor, spec: NpuArtifacts.TensorSpec): FloatArray = when (tensor) {
            is NpuTensor.Floats -> tensor.values
            is NpuTensor.Bytes -> {
                val unsigned = spec.dtype == NpuDType.UINT8
                FloatArray(tensor.values.size) {
                    val raw = tensor.values[it].toInt()
                    spec.scale * ((if (unsigned) raw and 0xFF else raw) - spec.zeroPoint)
                }
            }
            is NpuTensor.Shorts -> FloatArray(tensor.values.size) { spec.scale * (tensor.values[it] - spec.zeroPoint) }
        }

        private fun q(value: Float, spec: NpuArtifacts.TensorSpec, min: Int, max: Int): Int {
            val scale = if (spec.scale > 0f) spec.scale else 1f
            return ((value / scale).roundToInt() + spec.zeroPoint).coerceIn(min, max)
        }
    }
}
