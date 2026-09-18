package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

enum class AssistantIntent {
    ANSWER_QUESTION,
    ANALYZE_IMAGE,
    EXECUTE_UI_TASK,
    CLARIFY,
}

enum class AssistantRequestSource {
    GLASSES_VOICE,
    GLASSES_IMAGE,
    CHAT,
    APP_UI,
}

data class AssistantRequest(
    val text: String,
    val source: AssistantRequestSource,
    val imageAttached: Boolean = false,
)

data class AssistantRoutingDecision(
    val intent: AssistantIntent,
    val confidence: Double,
    val normalizedGoal: String? = null,
    val clarification: String? = null,
)

class AssistantRequestRouter(
    /**
     * Jev-like fast path. When set, route() tries deterministic checks (blank /
     * imageAttached) -> decision engine (single-letter, ~1 token) -> legacy
     * English regex heuristics -> full 256-token JSON completion.
     * Null keeps the legacy behavior exactly (used by existing tests).
     */
    var decisionEngineProvider: (() -> com.fersaiyan.cyanbridge.ai.decision.LocalDecisionEngine?)? = null,
) {
    suspend fun route(
        context: Context,
        request: AssistantRequest,
        providerType: AgentProviderType,
    ): AssistantRoutingDecision {
        classifyHeuristicallyDeterministic(request)?.let { return it }
        tryDecisionEngine(request)?.let { return it }
        classifyHeuristically(request)?.let { return it }

        return runCatching {
            val raw = AgentInferenceRouter.complete(
                context = context,
                purpose = AgentInferencePurpose.CLASSIFICATION,
                sessionId = "assistant-route-${System.currentTimeMillis()}",
                systemPrompt = CLASSIFIER_SYSTEM_PROMPT,
                userPrompt = buildClassifierInput(request),
                providerType = providerType,
            )
            enforceConfidencePolicy(parseDecision(raw), request.text)
        }.getOrElse {
            // Classification failures must never accidentally start phone control.
            AssistantRoutingDecision(
                intent = AssistantIntent.ANSWER_QUESTION,
                confidence = 0.5,
            )
        }
    }

    /** Deterministic checks kept as primary: blank + explicit image attachment. */
    internal fun classifyHeuristicallyDeterministic(request: AssistantRequest): AssistantRoutingDecision? {
        val text = request.text.trim()
        if (text.isBlank()) {
            return AssistantRoutingDecision(
                intent = AssistantIntent.CLARIFY,
                confidence = 1.0,
                clarification = "What would you like me to do?",
            )
        }
        if (request.imageAttached) {
            return AssistantRoutingDecision(
                intent = AssistantIntent.ANALYZE_IMAGE,
                confidence = 1.0,
            )
        }
        return null
    }

    /**
     * Jev-like single-letter routing. Returns null when no engine is wired or
     * the engine fails, so route() falls through to legacy paths.
     */
    internal suspend fun tryDecisionEngine(request: AssistantRequest): AssistantRoutingDecision? {
        val provider = decisionEngineProvider ?: return null
        val engine = runCatching { provider.invoke() }.getOrNull() ?: return null
        val candidates = com.fersaiyan.cyanbridge.ai.decision.AssistantDecisionOptions.candidates()
        return try {
            val decision = engine.choose(request.text.trim(), candidates, debugTag = "assistant-route")
            mapDecisionToRouting(decision, request.text)
        } catch (t: Throwable) {
            android.util.Log.w("AssistantRequestRouter", "Jev-like decision failed, falling back: ${t.message}")
            null
        }
    }

    internal fun mapDecisionToRouting(
        decision: com.fersaiyan.cyanbridge.ai.decision.LocalDecision,
        originalText: String,
    ): AssistantRoutingDecision {
        val intent = when (decision.index) {
            0 -> AssistantIntent.ANSWER_QUESTION
            1 -> AssistantIntent.ANALYZE_IMAGE
            2 -> AssistantIntent.EXECUTE_UI_TASK
            else -> AssistantIntent.CLARIFY
        }
        val base = AssistantRoutingDecision(
            intent = intent,
            confidence = decision.confidence.toDouble().coerceIn(0.0, 1.0),
            normalizedGoal = if (intent == AssistantIntent.EXECUTE_UI_TASK) originalText.trim() else null,
            clarification = if (intent == AssistantIntent.CLARIFY) "Please say exactly what you want me to do on the phone." else null,
        )
        return enforceConfidencePolicy(base, originalText)
    }

    internal fun classifyHeuristically(request: AssistantRequest): AssistantRoutingDecision? {
        val text = request.text.trim()
        if (text.isBlank()) {
            return AssistantRoutingDecision(
                intent = AssistantIntent.CLARIFY,
                confidence = 1.0,
                clarification = "What would you like me to do?",
            )
        }

        if (request.imageAttached) {
            return AssistantRoutingDecision(
                intent = AssistantIntent.ANALYZE_IMAGE,
                confidence = 1.0,
            )
        }

        if (IMAGE_REQUEST_REGEX.containsMatchIn(text)) {
            return AssistantRoutingDecision(
                intent = AssistantIntent.ANALYZE_IMAGE,
                confidence = 0.95,
                normalizedGoal = text,
            )
        }

        if (
            DIRECT_ACTION_REGEX.containsMatchIn(text) ||
            COURTEOUS_ACTION_REGEX.containsMatchIn(text) ||
            SETTING_ACTION_REGEX.containsMatchIn(text)
        ) {
            return AssistantRoutingDecision(
                intent = AssistantIntent.EXECUTE_UI_TASK,
                confidence = 0.96,
                normalizedGoal = text,
            )
        }

        if (INFORMATIONAL_QUESTION_REGEX.containsMatchIn(text)) {
            return AssistantRoutingDecision(
                intent = AssistantIntent.ANSWER_QUESTION,
                confidence = 0.95,
            )
        }

        return null
    }

    internal fun parseDecision(raw: String): AssistantRoutingDecision {
        val jsonText = extractJsonObject(raw)
        val obj = try {
            JSONObject(JSONTokener(jsonText))
        } catch (e: JSONException) {
            throw IllegalArgumentException("Invalid assistant routing JSON", e)
        }

        val intent = runCatching {
            AssistantIntent.valueOf(obj.optString("intent", "").trim().uppercase())
        }.getOrNull() ?: throw IllegalArgumentException("Invalid assistant routing intent")

        return AssistantRoutingDecision(
            intent = intent,
            confidence = obj.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
            normalizedGoal = obj.optString("goal", "").trim().ifBlank { null },
            clarification = obj.optString("clarification", "").trim().ifBlank { null },
        )
    }

    private fun enforceConfidencePolicy(
        decision: AssistantRoutingDecision,
        originalText: String,
    ): AssistantRoutingDecision {
        if (decision.intent != AssistantIntent.EXECUTE_UI_TASK) return decision
        if (decision.confidence >= AUTO_ACTION_CONFIDENCE) {
            return decision.copy(normalizedGoal = decision.normalizedGoal ?: originalText.trim())
        }

        return AssistantRoutingDecision(
            intent = AssistantIntent.CLARIFY,
            confidence = decision.confidence,
            clarification = decision.clarification
                ?: "Please say exactly what you want me to do on the phone.",
        )
    }

    private fun buildClassifierInput(request: AssistantRequest): String = buildString {
        appendLine("Source: ${request.source.name}")
        appendLine("Image attached: ${request.imageAttached}")
        appendLine("User request: ${request.text.trim()}")
    }.trim()

    private fun extractJsonObject(raw: String): String {
        val text = raw.trim()
        val fenced = FENCED_JSON_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()
        if (!fenced.isNullOrBlank() && fenced.startsWith("{")) return fenced

        val start = text.indexOf('{')
        if (start < 0) throw IllegalArgumentException("Assistant routing response did not contain JSON")

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else {
                    when (c) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                    }
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }

        throw IllegalArgumentException("Assistant routing JSON braces were not balanced")
    }

    private companion object {
        private const val AUTO_ACTION_CONFIDENCE = 0.85

        private val ACTION_VERBS =
            "open|launch|start|close|go to|tap|click|press|scroll|type|write|read|search for|turn on|turn off|enable|disable|switch to|send|call"

        private val DIRECT_ACTION_REGEX = Regex(
            "^(?:please\\s+)?(?:$ACTION_VERBS)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val COURTEOUS_ACTION_REGEX = Regex(
            "^(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?(?:$ACTION_VERBS)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val SETTING_ACTION_REGEX = Regex(
            "^(?:please\\s+)?set\\s+(?:an?\\s+)?(?:alarm|timer|volume|brightness)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val INFORMATIONAL_QUESTION_REGEX = Regex(
            "^(?:what|who|when|where|why|how|explain|describe|define|tell me|is|are|do|does|did)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val IMAGE_REQUEST_REGEX = Regex(
            "\\b(?:what (?:am i|are we) looking at|what do you see|analy[sz]e (?:this )?(?:image|picture|photo)|describe (?:this )?(?:image|picture|photo)|read (?:this )?(?:image|picture|photo))\\b",
            RegexOption.IGNORE_CASE,
        )
        private val FENCED_JSON_REGEX =
            Regex("""```(?:json)?\s*([\s\S]*?)\s*```""", RegexOption.IGNORE_CASE)

        private val CLASSIFIER_SYSTEM_PROMPT = """
            You classify a user's assistant request. Return one JSON object only:
            {"intent":"ANSWER_QUESTION|ANALYZE_IMAGE|EXECUTE_UI_TASK|CLARIFY","confidence":0.0,"goal":null,"clarification":null}

            Rules:
            - ANSWER_QUESTION: knowledge, explanation, conversation, or asking how to do something.
            - ANALYZE_IMAGE: asks what the user sees or asks to inspect/read a picture.
            - EXECUTE_UI_TASK: explicitly asks the assistant to operate the phone or an app.
            - CLARIFY: ambiguous target, missing object, or unclear desired action.
            - "How do I open Settings?" is ANSWER_QUESTION.
            - "Open Settings" and "Can you open Settings for me?" are EXECUTE_UI_TASK.
            - For EXECUTE_UI_TASK, set goal to a concise faithful restatement. Never invent missing details.
            - Do not propose UI actions, coordinates, tools, or step-by-step plans.
        """.trimIndent()
    }
}
