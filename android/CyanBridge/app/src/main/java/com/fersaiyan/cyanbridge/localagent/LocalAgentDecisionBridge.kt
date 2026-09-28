package com.fersaiyan.cyanbridge.localagent

import com.fersaiyan.cyanbridge.ai.decision.LocalDecisionEngine

/**
 * Static hook so the production brain can try the Jev-like candidate path
 * first and fall back to JSON generation. Tests / Linux-PC CI inject a fake
 * engine here without needing Android or a model.
 */
object LocalAgentDecisionBridge {
    @Volatile var engineProvider: (() -> LocalDecisionEngine?)? = null
    @Volatile var enabled: Boolean = true

    fun engine(): LocalDecisionEngine? = if (enabled) runCatching { engineProvider?.invoke() }.getOrNull() else null
}
