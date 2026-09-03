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
 *   "Answer only in X" so the model locks to the app language.
 * - Gemini Live is continuous and supports 97 languages; its
 *   systemInstruction must be permissive (default to app language but
 *   switch when the user asks). For Live callers use [baseQuestion] or
 *   [ImageQuestionPreferences.get().defaultQuestion] directly and let
 *   lib/gemini-live.ts buildLiveSystemInstruction handle language
 *   permissively. Do NOT embed the strict "Answer only in" line into the
 *   Live systemInstruction.
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

    fun questionForLanguage(languageTag: String): String = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "pt" -> "Dê-me uma descrição concisa da imagem"
        "es" -> "Dame una descripción concisa de la imagen"
        "de" -> "Gib mir eine kurze Beschreibung des Bildes"
        "fr" -> "Donnez-moi une description concise de l'image"
        "it" -> "Dammi una descrizione concisa dell'immagine"
        "zh" -> "请简洁地描述这张图片"
        "ko" -> "이미지를 간단히 설명해 주세요"
        "ru" -> "Дайте мне краткое описание изображения"
        else -> "Give me a concise description of the image"
    }

    fun responseLanguageInstruction(languageTag: String): String =
        "Answer only in ${languageLabel(languageTag)} ($languageTag)."

    private fun languageLabel(languageTag: String): String =
        Locale.forLanguageTag(languageTag)
            .getDisplayLanguage(Locale.ENGLISH)
            .ifBlank { languageTag }
}

object ImageQuestionPromptResolver {
    /**
     * Strict single-shot prompt: base question + "Answer only in X".
     * Use for CliRelayClient.imageQuery and local single-turn models.
     * For Gemini Live systemInstruction use [baseQuestion] instead so
     * Live can switch to any of its 97 languages on request.
     */
    fun resolve(
        settings: ImageQuestionSettings,
        userQuestion: String?,
    ): ResolvedImageQuestionPrompt {
        val languageTag = settings.appLanguageTag.ifBlank { "en" }
        val question = baseQuestion(settings, userQuestion)
        return ResolvedImageQuestionPrompt(
            text = "$question\n\n${ImageQuestionDefaults.responseLanguageInstruction(languageTag)}",
        )
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
            ?: settings.defaultQuestion.trim().ifBlank {
                ImageQuestionDefaults.questionForLanguage(languageTag)
            }
    }
}
