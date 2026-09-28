package com.fersaiyan.cyanbridge.localagent

/** A small, current-screen decision state; full dumps belong to the prose planner. */
object UiActionDecisionState {
    fun build(
        goal: String,
        observation: LocalAgentObservation,
        fallbackScreen: String,
        previousResult: String?,
    ): String {
        val keywords = LocalAgentScreenSnapshot.extractGoalKeywords(goal).toSet()
        val visible = observation.screenSnapshot?.nodes.orEmpty()
            .map { node ->
                node.text.ifBlank { node.contentDescription }.ifBlank { node.hintText }.trim()
            }
            .filter { it.isNotBlank() && it.length <= 90 && !it.contains("://") }
            .distinct()
            .sortedByDescending { text -> keywords.count { text.contains(it, ignoreCase = true) } }
            .take(8)
            .joinToString(" | ")
            .take(260)
            .ifBlank { fallbackScreen.trim().take(260) }
        return buildString {
            appendLine("Goal: ${goal.trim().take(150)}")
            appendLine("App: ${observation.packageName.orEmpty().take(70)}")
            appendLine("Visible: $visible")
            previousResult?.trim()?.takeIf { it.isNotBlank() }?.let {
                append("Previous: ${it.take(90)}")
            }
        }.trim().take(600)
    }
}
