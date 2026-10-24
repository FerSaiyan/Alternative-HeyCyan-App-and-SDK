package com.fersaiyan.cyanbridge.ai.vision

import java.util.Locale

data class ImageQuestionSettings(
    val appLanguageTag: String,
    val defaultQuestion: String,
    val usesBuiltInDefault: Boolean,
)

enum class ImageQuestionRoute {
    PRO_RELAY,
    LOCAL_GEMMA,
    TASKER_GEMINI,
}

/**
 * Single source of truth for the default image question
 * ([ImageQuestionDefaults.questionForLanguage]) and its per-request
 * composition.
 *
 * - Single-shot flows (PRO_RELAY via CliRelayClient.imageQuery,
 *   LOCAL_GEMMA, TASKER_GEMINI) are strict one-turn requests and append
 *   a Persian-only response instruction.
 * - Gemini Live uses [baseQuestion] or
 *   [ImageQuestionPreferences.get().defaultQuestion] directly; its
 *   systemInstruction separately enforces Persian responses. Do NOT embed
 *   the single-shot response instruction into the Live image prompt.
 */
data class ResolvedImageQuestionPrompt(
    val text: String,
) {
    fun forRoute(route: ImageQuestionRoute): String = when (route) {
        ImageQuestionRoute.PRO_RELAY,
        ImageQuestionRoute.LOCAL_GEMMA,
        ImageQuestionRoute.TASKER_GEMINI
        -> text
    }
}

object ImageQuestionDefaults {
    const val RESPONSE_LANGUAGE_TAG = "fa-IR"

    fun initializingLiveCueForLanguage(languageTag: String): String = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "pt" -> "Inicializando o Live."
        "es" -> "Inicializando Live."
        "de" -> "Live wird initialisiert."
        "fr" -> "Initialisation de Live."
        "it" -> "Inizializzazione di Live."
        "zh" -> "正在初始化实时模式。"
        "ko" -> "라이브를 초기화하고 있습니다."
        "ru" -> "Инициализация Live."
        else -> "Initializing Live."
    }

    fun listeningCueForLanguage(languageTag: String): String = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "pt" -> "Estou ouvindo."
        "es" -> "Te escucho."
        "de" -> "Ich höre zu."
        "fr" -> "Je vous écoute."
        "it" -> "Ti ascolto."
        "zh" -> "我在听。"
        "ko" -> "듣고 있습니다."
        "ru" -> "Я слушаю."
        else -> "I am listening."
    }

    fun questionCueForLanguage(languageTag: String): String = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "pt" -> "Pergunte."
        "es" -> "Pregunta."
        "de" -> "Frag."
        "fr" -> "Demandez."
        "it" -> "Chiedi."
        "zh" -> "请提问。"
        "ko" -> "질문하세요."
        "ru" -> "Спросите."
        else -> "Ask."
    }

    @Suppress("UNUSED_PARAMETER")
    fun questionForLanguage(languageTag: String): String =
        "این تصویر را دقیق و جامع به زبان فارسی توصیف کن."

    @Suppress("UNUSED_PARAMETER")
    fun responseLanguageInstruction(languageTag: String): String =
        "همیشه و منحصراً به زبان فارسی (Farsi) پاسخ بده و توصیف کن."
}

object ImageQuestionPromptResolver {
    private const val LEGACY_ENGLISH_DEFAULT_QUESTION = "Give me a concise description of the image"

    /**
     * Strict single-shot prompt: base question + a Persian-only response instruction.
     * Use for CliRelayClient.imageQuery and local single-turn models.
     * For Gemini Live systemInstruction use [baseQuestion] instead; Live
     * adds its Persian-only response instruction separately.
     */
    fun resolve(
        settings: ImageQuestionSettings,
        userQuestion: String?,
    ): ResolvedImageQuestionPrompt {
        val languageTag = settings.appLanguageTag.ifBlank { "en" }
        val question = baseQuestion(settings, userQuestion)
        return ResolvedImageQuestionPrompt(text = "$question\n\n${ImageQuestionDefaults.responseLanguageInstruction(languageTag)}")
    }

    /**
     * Base question without language lock — single source for Live's
     * default image question. Live's systemInstruction adds a permissive
     * language instruction separately (see lib/gemini-live.ts).
     */
    fun baseQuestion(
        settings: ImageQuestionSettings,
        userQuestion: String?,
    ): String {
        val languageTag = settings.appLanguageTag.ifBlank { "en" }
        return userQuestion?.trim().takeUnless { it.isNullOrBlank() }
            ?: settings.defaultQuestion.trim().takeUnless {
                it == LEGACY_ENGLISH_DEFAULT_QUESTION
            }.orEmpty().ifBlank {
                ImageQuestionDefaults.questionForLanguage(languageTag)
            }
    }
}
