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
}
