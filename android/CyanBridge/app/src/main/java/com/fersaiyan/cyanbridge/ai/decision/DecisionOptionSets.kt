package com.fersaiyan.cyanbridge.ai.decision

/** Live-session control head: CONTINUE vs END vs hand off to the phone agent. */
object LiveControlOptions {
    const val CONTINUE = 0
    const val END_LIVE = 1
    const val LOCAL_AGENT = 2

    fun candidates(): List<DecisionCandidate> = listOf(
        DecisionCandidate("A", "Continue the Gemini conversation normally"),
        DecisionCandidate("B", "End the Gemini Live session now"),
        DecisionCandidate("C", "The user wants CyanBridge to operate the phone or an app"),
    )
}

/** Assistant front-door head: mirrors AssistantIntent order A/B/C/D. */
object AssistantDecisionOptions {
    fun candidates(): List<DecisionCandidate> = listOf(
        DecisionCandidate("A", "Answer a normal informational or conversational question"),
        DecisionCandidate("B", "Analyze what the camera sees or inspect an image"),
        DecisionCandidate("C", "Operate the phone or an installed application"),
        DecisionCandidate("D", "Request is ambiguous or missing details; ask for clarification"),
    )
}
