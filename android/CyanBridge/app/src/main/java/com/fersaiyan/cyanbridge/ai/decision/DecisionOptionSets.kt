package com.fersaiyan.cyanbridge.ai.decision

/** Live-session control head: CONTINUE vs END vs hand off to the phone agent. */
object LiveControlOptions {
    const val CONTINUE = 0
    const val END_LIVE = 1
    const val LOCAL_AGENT = 2

    fun candidates(): List<DecisionCandidate> = listOf(
        DecisionCandidate("A", "Continue the Gemini conversation normally; answer questions such as tell me more about black holes"),
        DecisionCandidate("B", "End the Gemini Live session now; the user says that is all or asks to stop the conversation"),
        DecisionCandidate("C", "The user wants CyanBridge to operate the phone or an app; for example open Spotify on my phone"),
    )
}

/** Assistant front-door head: mirrors AssistantIntent order A/B/C/D. */
object AssistantDecisionOptions {
    fun candidates(): List<DecisionCandidate> = listOf(
        DecisionCandidate("A", "Answer a normal informational or conversational question; for example what is the weather today"),
        DecisionCandidate("B", "Analyze what the camera sees or inspect an image; for example what do you see in front of me"),
        DecisionCandidate("C", "Operate the phone or an installed application; for example open Spotify and play my liked songs"),
        DecisionCandidate("D", "Request is ambiguous or missing details; ask for clarification when the user only says help me"),
    )
}
