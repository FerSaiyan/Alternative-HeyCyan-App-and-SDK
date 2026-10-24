package com.fersaiyan.cyanbridge.localagent

/**
 * Executor that [LocalAgentService] hands to [LocalAgentStepEngine]: reading the screen aloud is
 * handled in-app, every other action goes through Accessibility (with the optional Shizuku
 * fallback).
 */
class LocalAgentServiceActionExecutor(
    private val readScreenAloud: () -> Boolean,
    private val performAction: suspend (LocalAgentAction) -> Boolean,
) : LocalAgentStepEngine.LocalAgentActionExecutor {

    override suspend fun execute(action: LocalAgentAction): Boolean {
        return if (action == LocalAgentAction.ReadScreenAloud) {
            readScreenAloud()
        } else {
            performAction(action)
        }
    }

    override fun ensureNotCancelled() {
        // cooperative cancellation handled by coroutine.
    }
}
