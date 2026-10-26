package com.fersaiyan.cyanbridge.shared.assistant

import com.fersaiyan.cyanbridge.shared.ai.GlassesAssistantRoute
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.shared.settings.QuestionRoutingMode

/** What a glasses wake activation (wake word, AI button) starts. */
enum class WakeQuestion {
    /** The recording goes to the decision model, which answers or picks camera/search/phone/live. */
    SPOKEN_DECISION,

    /** The AI button's voice route (Classic: transcript router, or the external assistant). */
    VOICE_QUESTION,

    /** A photo question: photo plus the spoken question, no decision. */
    IMAGE_QUESTION,
}

/**
 * Which flow and provider answer a glasses question. Lambdas are evaluated only when the branch
 * needs them, in the order the Android flows always asked (some of them log or read the disk).
 */
object AssistantQuestionRouting {
    /**
     * A voice wake goes to the decision model whenever the answering model hears audio, unless
     * Settings ▸ Question routing is Classic. A photo-mapped wake keeps the photo flow.
     */
    fun wakeQuestion(
        wakeRoute: AiWakeWordRoute,
        routingMode: QuestionRoutingMode,
        questionAudioGoesToModel: () -> Boolean,
    ): WakeQuestion = when {
        wakeRoute == AiWakeWordRoute.VOICE_QUESTION &&
            routingMode != QuestionRoutingMode.CLASSIC &&
            questionAudioGoesToModel() -> WakeQuestion.SPOKEN_DECISION
        wakeRoute == AiWakeWordRoute.VOICE_QUESTION -> WakeQuestion.VOICE_QUESTION
        else -> WakeQuestion.IMAGE_QUESTION
    }

    /**
     * Audio is the default question input: the recording goes to a model that can hear it (a local
     * audio model, or the Pro questions model when the relay reports audio input). Speech-to-text
     * is only the fallback for models that cannot.
     */
    fun questionAudioGoesToModel(
        route: GlassesAssistantRoute,
        hasLocalAudioModel: () -> Boolean,
        proQuestionsModelHearsAudio: () -> Boolean,
    ): Boolean = when (route) {
        GlassesAssistantRoute.LOCAL -> hasLocalAudioModel()
        GlassesAssistantRoute.PRO -> proQuestionsModelHearsAudio()
        GlassesAssistantRoute.TASKER_EXTERNAL_UI, GlassesAssistantRoute.PHONE_ASSISTANT -> false
    }

    /** Where a recorded question goes when there is no transcript (speech-to-text skipped or failed). */
    fun spokenQuestionProvider(
        route: GlassesAssistantRoute,
        proQuestionsModelHearsAudio: () -> Boolean,
    ): AgentProviderType =
        if (route == GlassesAssistantRoute.PRO && proQuestionsModelHearsAudio()) {
            AgentProviderType.PRO_SUBSCRIPTION
        } else {
            AgentProviderType.LOCAL_AGENT
        }

    /** The provider of the AI button's voice question; null for the external routes. */
    fun voiceQuestionProvider(route: GlassesAssistantRoute): AgentProviderType? = when (route) {
        GlassesAssistantRoute.LOCAL -> AgentProviderType.LOCAL_AGENT
        GlassesAssistantRoute.PRO -> AgentProviderType.PRO_SUBSCRIPTION
        GlassesAssistantRoute.TASKER_EXTERNAL_UI, GlassesAssistantRoute.PHONE_ASSISTANT -> null
    }

    /**
     * The in-app provider of a photo question, or null when the phone assistant app gets the photo.
     * A recorded (untranscribed) question goes where recordings are heard.
     */
    fun imageQuestionProvider(
        route: GlassesAssistantRoute,
        recordedQuestion: Boolean,
        spokenProvider: () -> AgentProviderType,
        mediaProvider: () -> AgentProviderType,
    ): AgentProviderType? = when {
        recordedQuestion -> spokenProvider()
        route == GlassesAssistantRoute.LOCAL -> AgentProviderType.LOCAL_AGENT
        route == GlassesAssistantRoute.PRO || route == GlassesAssistantRoute.TASKER_EXTERNAL_UI -> mediaProvider()
        else -> null
    }

    /** The provider whose settings decide whether the photo button opens Gemini Live; null for the phone assistant. */
    fun liveCandidateProvider(
        route: GlassesAssistantRoute,
        mediaProvider: () -> AgentProviderType,
    ): AgentProviderType? = if (route == GlassesAssistantRoute.PHONE_ASSISTANT) null else mediaProvider()

    /** The phone's own assistant app answers (and plays) the question. */
    fun usesExternalAssistantUi(route: GlassesAssistantRoute): Boolean = route == GlassesAssistantRoute.PHONE_ASSISTANT
}

/** What to say (and show) for a relay photo answer. */
data class VisionReply(val spoken: String, val toast: String? = null)

/** Relay photo answers: failures and unusable replies become a short sentence, never the raw error. */
object VisionReplyPolicy {
    const val ANALYSIS_FAILED = "I couldn't analyze the image. Please try again."
    const val EMPTY_REPLY = "I couldn't analyze that image right now. Please try again."
    const val NO_ANSWER = "I couldn't generate an answer for that image. Please try again."
    const val VISION_FAILED_TOAST = "Vision model couldn't process image"

    /** A failed request: the quota message is spoken as is; anything else is a short apology. */
    fun relayFailure(isQuota: Boolean, localizedMessage: String, errorMessage: String?): VisionReply =
        if (isQuota) {
            VisionReply(localizedMessage, toast = localizedMessage)
        } else {
            VisionReply(ANALYSIS_FAILED, toast = "Vision error: ${errorMessage?.take(80) ?: "unknown error"}")
        }

    /** A reply; [detectVisionFailure] also treats "please upload the image" style replies as failures. */
    fun relayReply(reply: String?, detectVisionFailure: Boolean): VisionReply {
        val text = reply?.trim().orEmpty()
        return when {
            text.isBlank() -> VisionReply(EMPTY_REPLY)
            detectVisionFailure && looksLikeVisionFailed(text) -> VisionReply(ANALYSIS_FAILED, toast = VISION_FAILED_TOAST)
            else -> VisionReply(text)
        }
    }

    /** The text spoken for a finished photo question. */
    fun spoken(reply: String): String = reply.ifBlank { NO_ANSWER }

    /** Detects when the vision model could not actually see the image (a server-side issue). */
    fun looksLikeVisionFailed(reply: String): Boolean {
        val lower = reply.lowercase()
        return lower.contains("upload") && lower.contains("image") ||
            lower.contains("please provide the image") ||
            lower.contains("i can't see") ||
            lower.contains("no image") && lower.contains("provided") ||
            lower.contains("attach") && lower.contains("image") ||
            lower.contains("invalid") && lower.contains("image") ||
            lower.contains("does not represent a valid image") ||
            lower.contains("image data") && lower.contains("invalid") ||
            lower.contains("vision") && lower.contains("failed") ||
            lower.contains("couldn't analyze") ||
            lower.contains("openrouter_image_failed")
    }
}

/** How a `[search]` question is answered from what the web search returned. */
sealed interface WebAnswerPlan {
    /** A model turn answers from the search text (see the platform's web-results instruction). */
    data class FromModel(val answer: String?, val snippets: List<String>, val infoBox: String?) : WebAnswerPlan

    /** The engine's own summary is read out, with no model turn. */
    data class ReadOut(val answer: String) : WebAnswerPlan

    /** A fixed sentence in the app language: nothing was found, or the search could not run. */
    data class Fixed(val nothingFound: Boolean, val offline: Boolean) : WebAnswerPlan
}

object WebAnswerPolicy {
    fun plan(web: WebSearchOutcome?, englishSpeaker: Boolean): WebAnswerPlan = when {
        // Weather and similar queries get the engine's info box, located by the phone's IP; AI answers
        // guess a place for those, so the box wins when both are there.
        web?.infoBox != null -> WebAnswerPlan.FromModel(null, web.snippets.take(2), web.infoBox)
        // The engine already summarised the results in their language (Google writes in `hl`,
        // DuckDuckGo mostly in English): read it out, no model turn.
        web?.answer != null && (web.fromGoogle || englishSpeaker) -> WebAnswerPlan.ReadOut(web.answer)
        // Other languages: the model says the short summary in the language they spoke.
        web?.answer != null -> WebAnswerPlan.FromModel(web.answer, emptyList(), null)
        // No AI answer: the model answers from the top results (title and description, no ads).
        web != null && web.snippets.isNotEmpty() -> WebAnswerPlan.FromModel(null, web.snippets, null)
        // Nothing found, or the search could not run (offline, bot challenge, no WebView, timeout):
        // a fixed sentence; the small model doesn't reliably say it.
        else -> WebAnswerPlan.Fixed(nothingFound = web != null && web.failure == null, offline = web?.offline == true)
    }
}

/** Picks the memory lines relevant to a question (the compact memory block of a text question). */
object AssistantMemorySelection {
    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "what", "when",
        "how", "who", "why", "are", "was", "were", "can", "could", "should", "would",
        "will", "just", "like", "your", "you", "about", "have", "has", "had", "then",
        "que", "para", "com", "uma", "nao", "não", "isso", "essa", "esse", "foi", "tem",
        "como", "porque", "por", "das", "dos", "uns", "umas",
    )
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(SEPARATORS)
            .map { it.trim() }
            .filter { it.length >= 3 && it !in STOPWORDS }
            .distinct()

    /** Items sharing the most words with [queryText]; without any match, the first two. */
    fun selectRelevant(items: List<String>, queryText: String, maxItems: Int): List<String> {
        val clean = items
            .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (clean.isEmpty()) return emptyList()
        val tokens = tokenize(queryText)
        if (tokens.isEmpty()) return clean.take(minOf(maxItems, 2))

        val hits = clean
            .map { item ->
                val hay = item.lowercase()
                item to tokens.count { hay.contains(it) }
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length })
            .map { it.first }
            .take(maxItems)
        return if (hits.isNotEmpty()) hits else clean.take(minOf(maxItems, 2))
    }
}

/**
 * The system turn of a glasses question. Sections are built only when included, in this order:
 * memory, language or spoken-question instruction (plus the decision routes), extra instruction,
 * the spoken-question memory core, current time.
 */
object AssistantSystemPrompt {
    fun build(
        memoryContext: String,
        /** The question is the recording itself (no user text). */
        spokenQuestion: Boolean,
        responseLanguageInstruction: () -> String,
        spokenQuestionInstruction: () -> String,
        /** Route-tag instructions of a decision turn; null otherwise. */
        decisionRoutes: String?,
        extraInstruction: String?,
        /** A spoken question has no text to search memory with: the compact, query-free core instead. */
        spokenMemory: () -> String,
        currentContext: () -> String,
    ): String = buildString {
        if (memoryContext.isNotBlank()) {
            append(memoryContext)
            append("\n\n")
        }
        if (!spokenQuestion) {
            append(responseLanguageInstruction())
        } else {
            append(spokenQuestionInstruction())
            if (decisionRoutes != null) append("\n\n").append(decisionRoutes)
        }
        extraInstruction?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
        if (spokenQuestion) {
            spokenMemory().takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
        }
        append("\n\n")
        append(currentContext())
    }
}
