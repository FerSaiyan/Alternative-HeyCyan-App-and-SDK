package com.fersaiyan.cyanbridge.plugins.walkingaid.ocr

/**
 * What the wearer wants from this Walking Aid session (owner design, 2026-10-28 / 2026-11-23): Gemma asks at the
 * start, then YOLO describes the surroundings, and in ride or bus mode the reader runs PP-OCR on car plates or bus
 * signs that YOLO finds, matching them against [target] when one is known.
 */
sealed class WalkingAidGoal {
    data object Describe : WalkingAidGoal()
    /** Waiting for an Uber/99 car; [plate] from what the wearer said or the ride app's notification. */
    data class Ride(val plate: String?) : WalkingAidGoal()
    /** Waiting for a bus; [route] is a line number or destination words. */
    data class Bus(val route: String?) : WalkingAidGoal()
}

object WalkingAidGoalParser {
    /** The instruction for the local model; the reply is one line `DESCRIBE`, `RIDE <plate>` or `BUS <route>`. */
    const val MODEL_INSTRUCTION =
        "A blind person using smart glasses answered the question \"What do you need help with?\". Classify the " +
            "answer. Reply with exactly one line and nothing else: DESCRIBE (describe the surroundings, walking, " +
            "anything else), RIDE <license plate or NONE> (waiting for an Uber, 99, taxi or ride-hailing car), or " +
            "BUS <line number or destination, or NONE> (waiting for a bus). Keep plates and line numbers as spoken."

    fun parseModelReply(reply: String): WalkingAidGoal? {
        val line = reply.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val word = line.substringBefore(' ').uppercase().trim('.', ':', '*')
        val arg = line.substringAfter(' ', "").trim().trim('.', '"', '*').takeUnless { it.isBlank() || it.equals("NONE", true) }
        return when (word) {
            "DESCRIBE" -> WalkingAidGoal.Describe
            "RIDE" -> WalkingAidGoal.Ride(arg?.let(::plateIn) ?: arg?.let(OcrText::normalize)?.takeIf { it.length in 6..8 })
            "BUS" -> WalkingAidGoal.Bus(arg)
            else -> null
        }
    }

    private val RIDE_WORDS = listOf("uber", "99", "taxi", "táxi", "corrida", "motorista", "ride", "cab", "driver", "carro", "car ")
    private val BUS_WORDS = listOf("ônibus", "onibus", "bus", "linha", "autobús", "autobus", "coletivo", "line ")

    /** Fallback without a model: keywords in the common app languages, a plate pattern, a route number. */
    fun parseKeywords(answer: String): WalkingAidGoal {
        val text = " ${answer.lowercase()} "
        plateIn(answer)?.let { return WalkingAidGoal.Ride(it) }
        return when {
            BUS_WORDS.any { it in text } -> WalkingAidGoal.Bus(Regex("\\b\\d{2,4}[A-Za-z]?\\b").find(answer)?.value)
            RIDE_WORDS.any { it in text } -> WalkingAidGoal.Ride(null)
            else -> WalkingAidGoal.Describe
        }
    }

    /** A Brazilian plate (old LLLNNNN or Mercosul LLLNLNN) spoken or written with separators. */
    fun plateIn(text: String): String? {
        val compact = text.uppercase().replace(Regex("[^A-Z0-9 ]"), "").replace(" ", "")
        return Regex("[A-Z]{3}[0-9][A-Z0-9][0-9]{2}").find(compact)?.value
    }
}
