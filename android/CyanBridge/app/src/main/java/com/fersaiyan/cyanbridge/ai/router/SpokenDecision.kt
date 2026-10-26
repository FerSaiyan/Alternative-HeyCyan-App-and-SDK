package com.fersaiyan.cyanbridge.ai.router

/**
 * Decision layer for glasses spoken questions. The model hears the recording (bare audio, system
 * instructions from ImageQuestionDefaults.spokenDecisionInstruction) and begins its reply with one tag,
 * so the app can act as soon as the tag is written:
 *
 *   [answer] <answer>   [camera]   [search] <query>   [phone] <task>   [live]   [ignore]
 */
enum class SpokenRoute(val tag: String, val hasPayload: Boolean) {
    ANSWER("answer", true),
    CAMERA("camera", false),
    SEARCH("search", true),
    PHONE("phone", true),
    LIVE("live", false),
    IGNORE("ignore", false),
    ;

    companion object {
        fun fromTag(tag: String): SpokenRoute? = entries.firstOrNull { it.tag == tag.trim().lowercase() }
    }
}

/** [tagged] is false when the reply had no recognizable tag; it is then treated as an answer. */
data class SpokenDecision(val route: SpokenRoute, val payload: String, val tagged: Boolean)

object SpokenDecisionParser {
    private val LEADING_TAG = Regex("""^\s*\[\s*([A-Za-z]+)\s*]""")
    // The model sometimes appends a second tag after its answer ("[answer] … [camera]").
    private val GEMINI_LIVE = Regex("""\bgemini\s*live\b|\blive\s*mode\b""", RegexOption.IGNORE_CASE)
    private val ANY_TAG = Regex("""\s*\[\s*(?:answer|camera|search|phone|live|ignore)\s*]\s*""", RegexOption.IGNORE_CASE)

    /** The route once the leading tag is complete, or null while it may still be streaming in. */
    fun routeOf(prefix: String): SpokenRoute? =
        LEADING_TAG.find(prefix)?.let { SpokenRoute.fromTag(it.groupValues[1]) }

    /** True once a leading tag is closed: further text can no longer change the route. */
    fun tagClosed(prefix: String): Boolean = LEADING_TAG.containsMatchIn(prefix)

    /**
     * True when nothing useful can follow: a route without payload, or a payload route (other than
     * ANSWER, which streams to the user) whose one-line payload has ended. Generation can stop here.
     */
    fun isComplete(prefix: String): Boolean {
        // No tag or an unknown one: the reply is treated as an answer, so it must run to the end.
        val route = routeOf(prefix) ?: return false
        if (route == SpokenRoute.ANSWER) return false
        if (!route.hasPayload) return true
        val rest = prefix.substring(LEADING_TAG.find(prefix)!!.range.last + 1).trimStart()
        return rest.isNotEmpty() && (rest.contains('\n') || ANY_TAG.containsMatchIn(rest))
    }

    fun parse(reply: String): SpokenDecision {
        val match = LEADING_TAG.find(reply)
        val route = match?.let { SpokenRoute.fromTag(it.groupValues[1]) }
        if (match == null || route == null) {
            // A tag after a stray prefix ("] [camera]", a quoted line, then "[ignore]") still decides.
            val late = ANY_TAG.find(reply)?.value?.let { tag -> SpokenRoute.fromTag(tag.trim().trim('[', ']')) }
            return if (late != null && late != SpokenRoute.ANSWER && !late.hasPayload) {
                SpokenDecision(late, "", tagged = true)
            } else {
                SpokenDecision(SpokenRoute.ANSWER, clean(reply), tagged = false)
            }
        }
        val rest = reply.substring(match.range.last + 1)
        val payload = when (route) {
            SpokenRoute.ANSWER -> clean(rest)
            // One line, up to any tag the model starts next ("[search] holidays in Brazil [answer] The next …").
            SpokenRoute.SEARCH, SpokenRoute.PHONE -> rest.trimStart().lineSequence().firstOrNull().orEmpty()
                .let { line -> ANY_TAG.find(line)?.let { line.substring(0, it.range.first) } ?: line }
                .let(::clean)
            else -> ""
        }
        // "Open Gemini Live" is a request for Live, which the model sometimes files as a phone task.
        if (route == SpokenRoute.PHONE && GEMINI_LIVE.containsMatchIn(payload)) return SpokenDecision(SpokenRoute.LIVE, "", tagged = true)
        return SpokenDecision(route, payload, tagged = true)
    }

    /** Removes stray tags anywhere in the text (an answer must never speak "[camera]"). */
    fun clean(text: String): String = ANY_TAG.replace(text, " ").replace(Regex("[ \\t]{2,}"), " ").trim()
}
