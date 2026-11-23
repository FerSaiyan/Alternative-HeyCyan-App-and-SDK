package com.fersaiyan.cyanbridge.localmodels.engine

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds with the NPU runtime AAR: LiteRT 2.x CompiledModel with this phone's vendor dispatch library. */
object LiteRtNpuModelFactory {
    const val AVAILABLE = true

    fun open(context: Context, modelPath: String): LiteRtNpuModel {
        val env = Environment.create(
            mapOf(
                Environment.Option.DispatchLibraryDir to
                    LiteRtNpuSupport.dispatchDir(context, LiteRtNpuSupport.status(context)),
            ),
        )
        val model = try {
            CompiledModel.create(modelPath, CompiledModel.Options(Accelerator.NPU), env)
        } catch (error: Exception) {
            env.close()
            throw error
        }
        return CompiledNpuModel(env, model)
    }
}

private class CompiledNpuModel(
    private val env: Environment,
    private val model: CompiledModel,
) : LiteRtNpuModel {
    private val inputs: List<TensorBuffer> = model.createInputBuffers()
    private val outputs: List<TensorBuffer> = model.createOutputBuffers()

    override fun run(input: ByteBuffer, inputIsFloat: Boolean, outputIsFloat: List<Boolean>): List<ByteBuffer> {
        require(inputs.size == 1) { "Expected one input, model has ${inputs.size}" }
        require(outputIsFloat.size == outputs.size) { "Expected ${outputIsFloat.size} outputs, model has ${outputs.size}" }
        input.rewind()
        if (inputIsFloat) {
            val values = FloatArray(input.remaining() / Float.SIZE_BYTES)
            input.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer().get(values)
            inputs[0].writeFloat(values)
        } else {
            val bytes = ByteArray(input.remaining())
            input.duplicate().get(bytes)
            inputs[0].writeInt8(bytes)
        }
        model.run(inputs, outputs)
        return outputs.mapIndexed { index, output ->
            if (outputIsFloat[index]) {
                val values = output.readFloat()
                ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).also {
                    it.asFloatBuffer().put(values)
                }
            } else {
                ByteBuffer.wrap(output.readInt8()).order(ByteOrder.nativeOrder())
            }
        }
    }

    override fun runTensors(inputs: List<NpuTensor>, outputTypes: List<NpuDType>): List<NpuTensor> {
        require(inputs.size == this.inputs.size) { "Expected ${this.inputs.size} inputs, got ${inputs.size}" }
        require(outputTypes.size == outputs.size) { "Expected ${outputs.size} outputs, got ${outputTypes.size}" }
        inputs.forEachIndexed { index, tensor ->
            when (tensor) {
                is NpuTensor.Floats -> this.inputs[index].writeFloat(tensor.values)
                is NpuTensor.Bytes -> this.inputs[index].writeInt8(tensor.values)
                is NpuTensor.Shorts -> this.inputs[index].writeInt16(tensor.values)
            }
        }
        model.run(this.inputs, outputs)
        return outputs.mapIndexed { index, output ->
            when (outputTypes[index]) {
                NpuDType.FLOAT32 -> NpuTensor.Floats(output.readFloat())
                NpuDType.INT8, NpuDType.UINT8 -> NpuTensor.Bytes(output.readInt8())
                NpuDType.INT16 -> NpuTensor.Shorts(output.readInt16())
            }
        }
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
        env.close()
    }
}
