package com.fersaiyan.cyanbridge.localmodels.engine

import android.content.Context

/** Builds without the NPU runtime AAR (-PlocalLiteRtNpuAarPath): no CompiledModel NPU path. */
object LiteRtNpuModelFactory {
    const val AVAILABLE = false

    fun open(context: Context, modelPath: String): LiteRtNpuModel =
        throw UnsupportedOperationException("This build ships no NPU runtime")
}
