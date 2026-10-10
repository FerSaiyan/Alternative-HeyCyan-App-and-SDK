package com.fersaiyan.cyanbridge.localmodels.engine

import com.google.ai.edge.litertlm.Capabilities

/** LiteRT-LM 0.16 bundle introspection. Media support is not exposed in this line. */
internal object LiteRtModelProbe {
    fun supportsSpeculativeDecoding(modelPath: String): Boolean =
        Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }

    /** Null: unknown; the engine probes a conversation instead. */
    fun mediaSupport(modelPath: String): LiteRtMediaSupport? = null
}
