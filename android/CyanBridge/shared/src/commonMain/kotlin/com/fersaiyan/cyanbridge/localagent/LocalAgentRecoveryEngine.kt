package com.fersaiyan.cyanbridge.localagent

import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger

/**
 * Diagnoses action failures using the current screen state and suggests
 * recovery actions to break out of stuck loops.
 *
 * Inspired by private-agent's RecoveryEngine but adapted for the Kotlin
 * observe→plan→act loop.
 */
object LocalAgentRecoveryEngine {

    private const val TAG = "LocalAgentRecovery"

    data class RecoveryAction(
        val action: LocalAgentAction,
        val description: String,
    )

    /**
     * Analyze the last failed action and current screen to suggest a recovery action.
     * Returns null if no specific recovery is warranted (let the brain decide).
     */
    fun diagnose(
        lastFailedAction: LocalAgentAction?,
        screenText: String?,
        consecutiveFailures: Int,
    ): RecoveryAction? {
        if (consecutiveFailures < 2) return null

        val lower = screenText?.lowercase().orEmpty()

        // 1. Loading/spinner detected → wait
        if (lower.contains("loading") || lower.contains("progress") ||
            lower.contains("spinner") || lower.contains("please wait")
        ) {
            PlatformLogger.i(TAG, "Recovery: detected loading state, waiting")
            return RecoveryAction(
                action = LocalAgentAction.Wait(2_000L),
                description = "App seems to be loading, waiting...",
            )
        }

        // 2. Keyboard covering elements → press back to dismiss
        if (lower.contains("gboard") || lower.contains("keyboard") ||
            lower.contains("swiftkey") || lower.contains("samsung keyboard")
        ) {
            PlatformLogger.i(TAG, "Recovery: keyboard detected, pressing back")
            return RecoveryAction(
                action = LocalAgentAction.GlobalBack,
                description = "Keyboard might be blocking the screen, dismissing it.",
            )
        }

        // 3. Last action was click and screen is scrollable → try scrolling
        if (lastFailedAction is LocalAgentAction.ClickText ||
            lastFailedAction is LocalAgentAction.ClickCoord
        ) {
            if (lower.contains("scrollable") || lower.contains("recyclerview") ||
                lower.contains("scroll")
            ) {
                PlatformLogger.i(TAG, "Recovery: click failed + scrollable, scrolling down")
                return RecoveryAction(
                    action = LocalAgentAction.Scroll(LocalAgentAction.Direction.DOWN),
                    description = "Click failed, trying to scroll down to find the target.",
                )
            } else {
                PlatformLogger.i(TAG, "Recovery: click failed + not scrollable, pressing back")
                return RecoveryAction(
                    action = LocalAgentAction.GlobalBack,
                    description = "Click failed and not scrollable, pressing back to retry.",
                )
            }
        }

        // 4. Last action was type_text → maybe need to tap the field first
        if (lastFailedAction is LocalAgentAction.TypeText) {
            PlatformLogger.i(TAG, "Recovery: type failed, pressing back to reset focus")
            return RecoveryAction(
                action = LocalAgentAction.GlobalBack,
                description = "Typing failed, pressing back to reset focus.",
            )
        }

        // 5. Last action was open_app → go home to try a different approach
        if (lastFailedAction is LocalAgentAction.OpenApp) {
            PlatformLogger.i(TAG, "Recovery: open_app failed, going home")
            return RecoveryAction(
                action = LocalAgentAction.GlobalHome,
                description = "Failed to open app, going home to try a different approach.",
            )
        }

        // 6. After many failures, try going home as a reset
        if (consecutiveFailures >= 4) {
            PlatformLogger.i(TAG, "Recovery: too many failures, going home to reset")
            return RecoveryAction(
                action = LocalAgentAction.GlobalHome,
                description = "Too many failures, going home to reset.",
            )
        }

        return null
    }
}
