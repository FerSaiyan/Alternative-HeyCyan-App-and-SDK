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

    fun initializingLocalModelCueForLanguage(languageTag: String): String = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "pt" -> "Inicializando o modelo local."
        "es" -> "Inicializando el modelo local."
        "de" -> "Lokales Modell wird initialisiert."
        "fr" -> "Initialisation du modèle local."
        "it" -> "Inizializzazione del modello locale."
        "zh" -> "正在初始化本地模型。"
        "ko" -> "로컬 모델을 초기화하고 있습니다."
        "ru" -> "Инициализация локальной модели."
        else -> "Initializing local model."
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

    /**
     * System instruction for a spoken question sent to the model as bare audio (optionally after the
     * glasses photo), with no text in the user turn. Instruction text after the audio ("answer the
     * question in the attached audio") made Gemma 4 E2B reply that no audio was attached; this layout
     * removed those refusals and the question rewrite on the Fold6 (QuestionPromptProbeTest, variant g).
     * [allowQuestionEcho] drops the "never repeat their words" rule for turns that must write the
     * question down (the decide-once routing turn).
     */
    fun spokenQuestionInstruction(languageTag: String, allowQuestionEcho: Boolean = false): String = buildString {
        append("You are the voice assistant in the user's smart glasses. The user talks to you out loud: their message ")
        append("is a recording of their voice, sometimes together with a photo from the glasses camera of what they are ")
        append("looking at. Reply directly to what they said, in the language they spoke (if unsure, in ")
        append("${languageLabel(languageTag)}). ")
        if (!allowQuestionEcho) append("Never repeat, quote or transcribe their words. ")
        append("Use the photo only when their words refer to something they see.")
    }

    /**
     * Route tags the spoken-question decision turn starts with (shared/assistant/SpokenDecision.kt). Kept in
     * sync with tools/benchmarks/eval_spoken_route_tags.py (ROUTES_V5A), where it is scored on the host
     * (2026-10-24: 119/142 greedy over 9 languages and 7 classes, v3 118). The search query is asked in
     * English for DuckDuckGo Search Assist (ai/search/WebSearchAssist.kt).
     */
    const val SPOKEN_DECISION_ROUTES =
        "Begin every reply with exactly one tag in square brackets, chosen by what answering needs:\n" +
            "[camera] and nothing else: the question is about something the user is looking at, holding or pointing to (\"this\", \"these\", \"that\", \"here\", read this, what color, how much is this, which one, what is this). You cannot see without the camera, so never guess what they see.\n" +
            "[search] then a short web search query in English: it needs current information from the internet (weather, news, prices, exchange rates, scores, opening hours, schedules, traffic).\n" +
            "[live] and nothing else: they want to start Gemini Live or live mode, a continuous conversation with you, or want you to keep watching and describing while they move. Starting Gemini Live is always [live], never [phone].\n" +
            "[phone] then the task in one short sentence: they ask you to do something on their phone (open an app, play music, call, send a message, set a timer or alarm, turn something on or off, navigate).\n" +
            "[ignore] and nothing else: they are not talking to you. They talk to another person (calling them by a name or a pet name like honey or mom, or talking on a phone call: greetings, goodbyes, \"I'll be there soon\"), or they want to stop (never mind, that's all, forget it). Do not reply to these.\n" +
            "[answer] then your answer: anything else you can answer from what they said, the current time below and what you know.\n" +
            "Examples (the user's words are written here; in the conversation they are spoken):\n" +
            "\"What color is this shirt?\" -> [camera]\n" +
            "\"What does this sign say?\" -> [camera]\n" +
            "\"What's 15 percent of 80?\" -> [answer] 12.\n" +
            "\"What day is it today?\" -> [answer] It's Friday.\n" +
            "\"Will it rain tomorrow?\" -> [search] rain forecast tomorrow\n" +
            "\"Play some jazz on YouTube.\" -> [phone] Play jazz on YouTube.\n" +
            "\"Turn on do not disturb.\" -> [phone] Turn on do not disturb.\n" +
            "\"Can you start Gemini Live?\" -> [live]\n" +
            "\"Let's just talk for a while.\" -> [live]\n" +
            "\"Sure, I'm leaving the office now, talk later.\" -> [ignore]\n" +
            "\"Pedro, pass me the salt.\" -> [ignore]\n" +
            "\"Okay, thanks, that's all.\" -> [ignore]"

    fun checkingWebCueForLanguage(languageTag: String): String = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "pt" -> "Vou pesquisar."
        "es" -> "Voy a buscarlo."
        "de" -> "Ich schaue nach."
        "fr" -> "Je vérifie."
        "it" -> "Controllo."
        "zh" -> "我查一下。"
        "ko" -> "찾아볼게요."
        "ru" -> "Сейчас проверю."
        else -> "Let me check."
    }

    /** Answer-turn note carrying what a web search found (an AI summary and/or result snippets). */
    fun webResultsInstruction(answer: String?, snippets: List<String>, infoBox: String? = null): String = buildString {
        append("A web search for their question found the following. Using the current local time you were given to pick ")
        append("what is next or latest, answer their question from it in one or two ")
        append("short sentences, in the language they spoke. If it doesn't contain the answer, say you couldn't find ")
        append("it; never offer to look it up. Keep numbers and units as written; a temperature written without a unit is ")
        append("just \"degrees\".\n")
        answer?.let { append("Search summary: ").append(it).append('\n') }
        infoBox?.let { append("Info box:\n").append(it).append('\n') }
        snippets.forEachIndexed { i, s -> append("Result ${i + 1}: ").append(s).append('\n') }
    }.trimEnd()

    /**
     * Spoken (without a model turn) when the web search for a `[search]` question could not run;
     * [offline] when the results page failed to load.
     */
    fun webSearchFailedCueForLanguage(languageTag: String, offline: Boolean): String = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "pt" -> if (offline) "Não consegui pesquisar na web: sem conexão com a internet." else "Não consegui pesquisar na web agora. Tente mais tarde."
        "es" -> if (offline) "No pude buscar en la web: no hay conexión a internet." else "No pude buscar en la web ahora. Inténtalo más tarde."
        "de" -> if (offline) "Ich konnte nicht im Web suchen: keine Internetverbindung." else "Ich konnte gerade nicht im Web suchen. Versuch es später noch einmal."
        "fr" -> if (offline) "Je n'ai pas pu chercher sur le web : pas de connexion internet." else "Je n'ai pas pu chercher sur le web pour l'instant. Réessayez plus tard."
        "it" -> if (offline) "Non ho potuto cercare sul web: nessuna connessione a internet." else "Non ho potuto cercare sul web adesso. Riprova più tardi."
        "zh" -> if (offline) "无法进行网络搜索：没有网络连接。" else "现在无法进行网络搜索，请稍后再试。"
        "ko" -> if (offline) "웹 검색을 할 수 없어요. 인터넷에 연결되어 있지 않습니다." else "지금은 웹 검색을 할 수 없어요. 나중에 다시 시도해 주세요."
        "ru" -> if (offline) "Не удалось выполнить поиск: нет подключения к интернету." else "Сейчас не удалось выполнить поиск в интернете. Попробуйте позже."
        else -> if (offline) "I couldn't search the web: there's no internet connection." else "I couldn't search the web right now. Please try again later."
    }

    /** Spoken (without a model turn) when the web search ran and found nothing to answer from. */
    fun webSearchEmptyCueForLanguage(languageTag: String): String = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "pt" -> "Pesquisei na web, mas não encontrei uma resposta para isso."
        "es" -> "Busqué en la web, pero no encontré una respuesta."
        "de" -> "Ich habe im Web gesucht, aber keine Antwort darauf gefunden."
        "fr" -> "J'ai cherché sur le web, mais je n'ai pas trouvé de réponse."
        "it" -> "Ho cercato sul web, ma non ho trovato una risposta."
        "zh" -> "我在网上搜索了，但没有找到答案。"
        "ko" -> "웹에서 찾아봤지만 답을 찾지 못했어요."
        "ru" -> "Я поискал в интернете, но не нашёл ответа."
        else -> "I searched the web but couldn't find an answer to that."
    }

    /** Spoken when a glasses question fails before any answer (model or engine error). */
    fun questionFailedCueForLanguage(languageTag: String): String = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "pt" -> "Não consegui processar essa pergunta. Tente de novo."
        "es" -> "No pude procesar esa pregunta. Inténtalo de nuevo."
        "de" -> "Ich konnte diese Frage nicht verarbeiten. Bitte versuch es noch einmal."
        "fr" -> "Je n'ai pas pu traiter cette question. Veuillez réessayer."
        "it" -> "Non sono riuscito a elaborare la domanda. Riprova."
        "zh" -> "我无法处理这个问题，请再试一次。"
        "ko" -> "질문을 처리하지 못했습니다. 다시 시도해 주세요."
        "ru" -> "Не удалось обработать вопрос. Попробуйте ещё раз."
        else -> "I couldn't process that question. Please try again."
    }

    /**
     * Offline time context for questions like "what day is it" or "how many days until Christmas"
     * (local models have no clock). Region comes from the device locale; no location is used.
     */
    fun currentContextInstruction(
        now: java.time.ZonedDateTime = java.time.ZonedDateTime.now(),
        region: String = Locale.getDefault().country,
    ): String {
        val stamp = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.US))
        val place = region.takeIf { it.isNotBlank() }?.let { ", device region $it" }.orEmpty()
        return "Current local time: $stamp (${now.zone.id})$place."
    }

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
