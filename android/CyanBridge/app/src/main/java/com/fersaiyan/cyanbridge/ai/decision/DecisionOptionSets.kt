package com.fersaiyan.cyanbridge.ai.decision

/** Live-session control head: CONTINUE vs END vs hand off to the phone agent. */
/** Assistant front-door head: mirrors AssistantIntent order A/B/C/D. */
object AssistantDecisionOptions {
    fun candidates(): List<DecisionCandidate> = listOf(
        DecisionCandidate("A", "Answer a normal informational or conversational question; for example what is the weather today"),
        DecisionCandidate("B", "Analyze what the camera sees or inspect an image; for example what do you see in front of me"),
        DecisionCandidate("C", "Operate the phone or an installed application; for example open Spotify and play my liked songs"),
        DecisionCandidate("D", "Request is ambiguous or missing details; ask for clarification when the user only says help me"),
    )
}
