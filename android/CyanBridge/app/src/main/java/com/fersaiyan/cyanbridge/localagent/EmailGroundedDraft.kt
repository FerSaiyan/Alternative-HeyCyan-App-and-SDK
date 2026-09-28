package com.fersaiyan.cyanbridge.localagent

/** Small prose handoff for an observed article and an explicit email destination. */
internal object EmailGroundedDraft {
    data class Request(val to: String, val subject: String, val evidence: String, val prompt: LocalAgentUiControlProtocol.Prompt)

    fun from(goal: String, observation: LocalAgentObservation): Request? {
        val to = Regex("(?i)\\bemail\\s+to\\s+([A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,})")
            .find(goal)?.groupValues?.get(1) ?: return null
        val subject = Regex("(?i)\\b(?:exact\\s+)?subject\\s+'([^']{1,180})'")
            .find(goal)?.groupValues?.get(1) ?: return null
        val choices = UiActionCandidateBuilder.build(goal, observation)
        if (choices.keys.firstOrNull() != "detailed_planner" ||
            !choices.candidates.first().description.contains("grounded final answer")) return null

        val evidence = observation.screenSnapshot?.nodes.orEmpty()
            .map { it.text.ifBlank { it.contentDescription }.trim() }
            .filter { it.length >= 25 && !it.contains("://") &&
                !it.startsWith("Your connection to this site", ignoreCase = true) }
            .distinct().take(6).joinToString("\n").take(780)
        if (evidence.length < 80) return null
        val bodyInstructions = goal.substringAfter("The body should", "summarize the visible article")
            .substringBefore("Use the send_email action")
            .trim().trimEnd('.').take(260)
        val prompt = LocalAgentUiControlProtocol.Prompt(
            system = """Use only visible page evidence to write a concise factual email body. """ +
                """Reply with one JSON object: {"action":"send_email","params":{"to":"...","subject":"...","body":"..."},"is_complete":false}. """ +
                """No explanation, markdown, invented facts or claim that the email was already sent. CyanBridge handles approval and sending.""",
            user = """Destination: $to
Exact subject: $subject
Body instructions: $bodyInstructions
Visible source text:
$evidence
Compose the JSON send_email request now; this queues approval, not a UI Send tap.""",
        )
        return Request(to, subject, evidence, prompt)
    }

    fun parse(raw: String, request: Request): LocalAgentAction.SendEmail {
        val action = LocalAgentUiControlProtocol.parseDecision(raw).action
        require(action is LocalAgentUiControlProtocol.SendEmail) { "Expected a prepared send_email request" }
        require(action.to.trim().equals(request.to, ignoreCase = true) && action.subject.trim() == request.subject) {
            "Email destination or exact subject changed"
        }
        val body = action.body.trim()
        val evidenceWords = Regex("[\\p{L}\\p{N}]{5,}").findAll(request.evidence.lowercase())
            .map { it.value }.filterNot { it in setOf("cyanbridge", "article", "search", "result", "smartglasses") }
            .toSet()
        val overlap = evidenceWords.count { word -> Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])").containsMatchIn(body) }
        require(body.length >= 80 && overlap >= 2) { "Email body has insufficient observed source detail" }
        return LocalAgentAction.SendEmail(request.to, request.subject, body)
    }
}
