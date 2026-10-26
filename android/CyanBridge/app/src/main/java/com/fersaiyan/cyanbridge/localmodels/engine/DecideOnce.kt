package com.fersaiyan.cyanbridge.localmodels.engine

enum class DecideOnceRoute { ANSWER, CAMERA }

/**
 * Prototype "decide once" question turn (docs/QUESTION_ROUTING_AND_VOICE_PROFILE.md): one LiteRT-LM
 * conversation prefills the recorded question once. Its reply is constrained to start with a route
 * label: ⊲A⊳ continues straight into the spoken answer; ⊲B⊳ stops, [imageWhenNeeded] supplies the
 * photo (already taken on an image-mapped wake, or captured now), and the same conversation answers
 * with it. The question's audio is never prefilled twice and the user is never asked again.
 * Only engines that keep a conversation across turns honour it (LiteRT-LM).
 */
class DecideOnceRequest(
    val imageWhenNeeded: suspend () -> String?,
    val onRoute: (route: DecideOnceRoute, decisionMs: Long) -> Unit = { _, _ -> },
    /**
     * Streaming variant: the answer route continues straight from turn 1 (no second turn) and the
     * camera route starts fetching the photo as soon as ⊲B⊳ appears. Fold6 GPU: the two-turn answer
     * route took ~6.4 s against ~1.0 s for a plain audio answer.
     */
    val streamAnswer: Boolean = false,
    // The streaming variant keeps the two-step prompt: asking for "⊲A⊳ then the answer" biased the
    // model toward answering (AVD: visual → camera 2/15 vs 33/45). It writes an answer after ⊲A⊳ anyway.
    val routingInstruction: String = DecideOncePrompt.ROUTING_INSTRUCTION,
    val answerInstruction: String = DecideOncePrompt.ANSWER_INSTRUCTION,
    val imageInstruction: String = DecideOncePrompt.IMAGE_INSTRUCTION,
    val noImageInstruction: String = DecideOncePrompt.NO_IMAGE_INSTRUCTION,
)

/**
 * Turn 1 is a short constrained decision: the model first writes the question as it heard it
 * ("Q: …") and then the route label, so the decision is made on the words rather than on the raw
 * audio (host eval: zero-shot routing from raw audio alone misses most visual questions; deciding
 * after writing the question down is close to deciding on an external transcript). Turn 2 is
 * either a tiny "answer now" text turn or the photo, in the same conversation.
 */
object DecideOncePrompt {
    const val ANSWER_LABEL = "⊲A⊳"
    const val CAMERA_LABEL = "⊲B⊳"

    /**
     * Intended shape of turn 1 (passed as a llguidance regex). On the stock 0.16 AAR the constraint
     * was not enforced on the AVD (the model continued after the label), so [parseRoute] takes the
     * first label and [DECISION_MAX_TOKENS] bounds the turn.
     */
    const val RESPONSE_REGEX = "Q: [^\\n]{1,240}\\n⊲[AB]⊳"

    /** Enough for a question line and a label; anything the model adds after that is cut off. */
    const val DECISION_MAX_TOKENS = 32

    const val ROUTING_INSTRUCTION =
        "Do not answer yet. First write the user's question on one line starting with \"Q: \", in the " +
            "language they spoke. On the next line write $CAMERA_LABEL if answering needs to see what the " +
            "user is looking at, holding or pointing to (this, these, here, read this, what color, how much, " +
            "which one), or $ANSWER_LABEL if it can be answered without looking. Write nothing after the label."

    const val ANSWER_INSTRUCTION =
        "Now answer the user's question from the audio. Do not repeat the question or write a label."

    const val IMAGE_INSTRUCTION =
        "Here is a photo of what the user is looking at. Answer their question from the audio. " +
            "Do not repeat the question or write a label."

    const val NO_IMAGE_INSTRUCTION = "No photo could be taken. Answer their question as well as you can."

    /** The first route label in a turn-1 reply (text the model adds after it is ignored). */
    fun parseRoute(reply: String): DecideOnceRoute {
        val answer = reply.indexOf(ANSWER_LABEL).takeIf { it >= 0 } ?: Int.MAX_VALUE
        val camera = reply.indexOf(CAMERA_LABEL).takeIf { it >= 0 } ?: Int.MAX_VALUE
        return when {
            camera < answer -> DecideOnceRoute.CAMERA
            answer < camera -> DecideOnceRoute.ANSWER
            else -> error("Decision reply has no route label (shape ${shape(reply)})")
        }
    }

    /** Like [parseRoute], but a malformed decision (seen once on the Fold6: "⊲��⊳") answers without the camera. */
    fun parseRouteOrAnswer(reply: String): Pair<DecideOnceRoute, Boolean> =
        runCatching { parseRoute(reply) to true }.getOrDefault(DecideOnceRoute.ANSWER to false)

    /** Layout of a reply with letters and digits masked, for logs that must not contain speech. */
    fun shape(reply: String): String =
        reply.map { if (it.isLetterOrDigit()) 'x' else it }.joinToString("").replace(Regex("x+"), "x").replace("\n", "\\n")

    /** The question as the model heard it (for length logging and tests; never logged verbatim). */
    fun heardQuestion(reply: String): String =
        reply.substringAfter("Q:", "").substringBefore('\n').trim()

    fun stripLabel(text: String): String = text.removePrefix(ANSWER_LABEL).removePrefix(CAMERA_LABEL).trimStart()

    /** Removes an echoed "Q: …" line and route labels from the start of an answer. */
    fun cleanAnswer(text: String): String {
        var rest = text.trimStart()
        while (true) {
            val next = when {
                rest.startsWith("Q:") -> rest.substringAfter('\n', "")
                rest.startsWith(ANSWER_LABEL) || rest.startsWith(CAMERA_LABEL) -> rest.substring(ANSWER_LABEL.length)
                else -> return rest
            }.trimStart()
            if (next == rest) return rest
            rest = next
        }
    }

    /**
     * Wraps a token sink so an echoed "Q: …" line or route label at the start of the answer stream is
     * never spoken (the model imitates its decision-turn format). Holds text back only until the
     * start of the answer is known.
     */
    fun labelStrippingSink(onToken: (String) -> Unit): (String) -> Unit {
        val head = StringBuilder()
        var passthrough = false
        return { chunk ->
            if (passthrough) {
                onToken(chunk)
            } else {
                head.append(chunk)
                val cleaned = cleanAnswer(head.toString())
                val pendingEcho = cleaned.startsWith("Q") && !cleaned.contains('\n') &&
                    ("Q:".startsWith(cleaned.take(2)))
                val pendingLabel = cleaned.length < ANSWER_LABEL.length &&
                    (ANSWER_LABEL.startsWith(cleaned) || CAMERA_LABEL.startsWith(cleaned))
                if (cleaned.isNotEmpty() && !pendingEcho && !pendingLabel) {
                    passthrough = true
                    onToken(cleaned)
                }
            }
        }
    }
}
