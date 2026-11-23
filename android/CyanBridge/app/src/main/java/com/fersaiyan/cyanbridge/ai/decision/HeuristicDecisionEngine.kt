package com.fersaiyan.cyanbridge.ai.decision

/**
 * Deterministic fallback used when no model is available (tests, Linux-PC CI,
 * offline devices). Multilingual keyword lists are intentionally small: this is
 * a safety net, not the primary router. The model path handles the long tail.
 *
 * Each function returns the winning candidate index or null when inconclusive.
 */
object HeuristicDecisionEngine {

    fun classifyAssistantRequest(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val lower = t.lowercase()

        if (IMAGE_HINT.containsMatchIn(lower)) return 1 // B = image
        if (ACTION_HINT.containsMatchIn(lower)) return 2 // C = operate phone/app
        if (QUESTION_HINT.containsMatchIn(lower)) return 0 // A = answer normally
        // "help with Spotify" etc: inconclusive -> let the model decide
        return null
    }

    // Keep these compact but multilingual (EN/PT/ES/FR/DE/IT/ZH/KO/RU).
    private val IMAGE_HINT = Regex(
        "what do you see|what am i looking at|analy[sz]e (this )?(image|picture|photo)|" +
            "describe (this )?(image|picture|photo)|o que (estou|estamos) vendo|o que você vê|" +
            "que vois-tu|was siehst du|cosa vedi|你看到什么|描述.*(图片|照片|图像)|" +
            "뭐가 보여|что ты видишь",
    )
    private val ACTION_HINT = Regex(
        "\\b(open|launch|start|close|tap|click|press|scroll|type|write|search|turn on|turn off|enable|disable|send|call|set\\b.*(alarm|timer))\\b|" +
            "\\b(abra|abre|abrir|toque|toque minha|ligue|desligue|envie|ligar para|definir alarme)|" +
            "\\b(abre|abrir|reproduce|pon|enciende|apaga|envía|llama)|" +
            "\\b(ouvre|ouvre|ouvre l|lance|allume|éteins|envoie|appelle)|" +
            "\\b(öffne|öffnen|starte|schalte|tippe|klicke|sende|anrufen)|" +
            "\\b(apri|aprire|riproduci|accendi|spegni|invia|chiama)|" +
            "打开|播放|关闭|点击|发送|打电话|设置闹钟|" +
            "열어|재생|꺼|보내|전화|알람",
    )
    private val QUESTION_HINT = Regex(
        "^(what|who|when|where|why|how|explain|describe|define|tell me|is|are|do|does|did|can you explain)\\b|" +
            "^(o que|quem|quando|onde|por que|porque|como|explique|me diga|é|e |voce sabe)|" +
            "^(qué|quién|cuándo|dónde|por qué|cómo|explica|dime)|" +
            "^(qu'est-ce|qui|quand|où|pourquoi|comment|explique)|" +
            "^(was|wer|wann|wo|warum|wie|erkläre)|" +
            "^(che cosa|chi|quando|dove|perché|come|spiega)|" +
            "^(什么是|谁|何时|哪里|为什么|如何|解释)",
    )
}
