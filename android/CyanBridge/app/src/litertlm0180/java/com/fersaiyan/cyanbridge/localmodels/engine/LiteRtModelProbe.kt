package com.fersaiyan.cyanbridge.localmodels.engine

import com.google.ai.edge.litertlm.LlmCapability
import com.google.ai.edge.litertlm.ModelInfo

/** LiteRT-LM 0.18 bundle introspection via ModelInfo (replaces 0.16 Capabilities). */
internal object LiteRtModelProbe {
    fun supportsSpeculativeDecoding(modelPath: String): Boolean =
        ModelInfo.from(modelPath).use { (it as? LlmCapability)?.hasSpeculativeDecodingSupport() == true }

    fun mediaSupport(modelPath: String): LiteRtMediaSupport =
        ModelInfo.from(modelPath).use { info ->
            val modalities = info.inputModalities()
            LiteRtMediaSupport(audio = modalities.audio, vision = modalities.vision)
        }
}
