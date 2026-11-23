package com.fersaiyan.cyanbridge.localmodels.engine

import java.nio.ByteBuffer

/**
 * One AOT-compiled `.tflite` running on the Qualcomm NPU through LiteRT CompiledModel
 * (docs/NPU_ON_DEVICE.md). Created by LiteRtNpuModelFactory, which only exists in builds
 * with the NPU runtime AAR.
 */
interface LiteRtNpuModel : AutoCloseable {
    /**
     * Runs one inference on a single input. Quantized (8-bit) tensors carry their raw bytes;
     * float tensors carry native-order float32. Outputs come back in the model's signature
     * order, which matches the plain model's output tensor order for LiteRT AOT exports.
     */
    fun run(input: ByteBuffer, inputIsFloat: Boolean, outputIsFloat: List<Boolean>): List<ByteBuffer>

    /**
     * Runs one inference with typed tensors: [inputs] in signature order, outputs read as [outputTypes]. 8-bit tensors
     * carry raw bytes (uint8 and int8 alike), int16 tensors shorts, float tensors floats. See [NpuFloatRunner] for the
     * float view with (de)quantization.
     */
    fun runTensors(inputs: List<NpuTensor>, outputTypes: List<NpuDType>): List<NpuTensor>
}

enum class NpuDType { FLOAT32, INT8, UINT8, INT16 }

sealed class NpuTensor {
    class Floats(val values: FloatArray) : NpuTensor()
    class Bytes(val values: ByteArray) : NpuTensor()
    class Shorts(val values: ShortArray) : NpuTensor()
}
