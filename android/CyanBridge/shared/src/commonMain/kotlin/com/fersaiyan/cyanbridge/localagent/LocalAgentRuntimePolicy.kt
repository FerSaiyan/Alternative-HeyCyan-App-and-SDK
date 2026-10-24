package com.fersaiyan.cyanbridge.localagent

/** Small, deterministic guardrails around otherwise model-driven UI control. */
object LocalAgentRuntimePolicy {

    fun actionSignature(action: LocalAgentAction): String = action.toString()

    fun maxIdenticalExecutions(action: LocalAgentAction): Int {
        return when (action) {
            is LocalAgentAction.Wait -> 5
            is LocalAgentAction.Scroll,
            is LocalAgentAction.Swipe -> 3
            LocalAgentAction.PressEnter -> 2
            else -> 2
        }
    }

    fun settleDelayMs(action: LocalAgentAction?): Long {
        return when (action) {
            null -> 750L
            is LocalAgentAction.Wait -> 0L
            is LocalAgentAction.OpenApp -> 3_000L
            is LocalAgentAction.TypeText -> 2_000L
            LocalAgentAction.PressEnter -> 2_000L
            is LocalAgentAction.ClickText,
            is LocalAgentAction.ClickCoord,
            is LocalAgentAction.LongPress,
            is LocalAgentAction.MakeCall,
            is LocalAgentAction.SendSms,
            is LocalAgentAction.SendEmail,
            is LocalAgentAction.SetAlarm -> 1_500L
            is LocalAgentAction.Scroll,
            is LocalAgentAction.Swipe -> 1_000L
            else -> 500L
        }
    }

    fun repeatLimitMessage(action: LocalAgentAction): String {
        return "Blocked repeated ${action.javaClass.simpleName} action. Choose a different visible action."
    }
}
