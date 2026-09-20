package com.fersaiyan.cyanbridge.ai.live

import java.util.Locale

enum class GeminiLiveAnnouncement {
    FREE_SESSION_RESTARTING,
    SESSION_RESTARTING,
    FREE_IMAGE_SESSION_RESTARTING,
    IMAGE_SESSION_RESTARTING,
    IMAGE_LIMIT_REACHED,
    SESSION_ENDED,
    FREE_DAILY_LIMIT_REACHED,
    FREE_BUSY,
    PRIVATE_SESSION_BUSY,
    PRIVATE_RATE_LIMITED,
    GENERIC_FAILURE,
}

object GeminiLiveAnnouncementMessages {
    fun text(kind: GeminiLiveAnnouncement, languageTag: String): String {
        val language = Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
        return translations[language]?.get(kind) ?: translations.getValue("en").getValue(kind)
    }

    private val translations = mapOf(
        "en" to mapOf(
            GeminiLiveAnnouncement.FREE_SESSION_RESTARTING to "Your free Live session has ended. Starting a new free Gemini Live session.",
            GeminiLiveAnnouncement.SESSION_RESTARTING to "Your Live session has ended. Starting a new Gemini Live session.",
            GeminiLiveAnnouncement.FREE_IMAGE_SESSION_RESTARTING to "Image limit reached for this session. Starting a new free Gemini Live session.",
            GeminiLiveAnnouncement.IMAGE_SESSION_RESTARTING to "Image limit reached for this session. Starting a new Gemini Live session.",
            GeminiLiveAnnouncement.IMAGE_LIMIT_REACHED to "Image limit reached for this session. Trigger Live again to start a new one.",
            GeminiLiveAnnouncement.SESSION_ENDED to "Your Live session has ended. Trigger Live again to start a new one.",
            GeminiLiveAnnouncement.FREE_DAILY_LIMIT_REACHED to "You've used your 5 free Live sessions for today. Come back tomorrow, or upgrade to Pro for more access.",
            GeminiLiveAnnouncement.FREE_BUSY to "Live is busy. Please try again in a moment.",
            GeminiLiveAnnouncement.PRIVATE_SESSION_BUSY to "Another Live session is still active. End it before starting Private Live.",
            GeminiLiveAnnouncement.PRIVATE_RATE_LIMITED to "Too many Live session starts. Wait a few minutes before trying Private Live again.",
            GeminiLiveAnnouncement.GENERIC_FAILURE to "Gemini Live session failed. Please try again.",
        ),
        "pt" to mapOf(
            GeminiLiveAnnouncement.FREE_SESSION_RESTARTING to "Sua sessão gratuita do Live terminou. Iniciando uma nova sessão gratuita do Gemini Live.",
            GeminiLiveAnnouncement.SESSION_RESTARTING to "Sua sessão do Live terminou. Iniciando uma nova sessão do Gemini Live.",
            GeminiLiveAnnouncement.FREE_IMAGE_SESSION_RESTARTING to "O limite de imagens desta sessão foi atingido. Iniciando uma nova sessão gratuita do Gemini Live.",
            GeminiLiveAnnouncement.IMAGE_SESSION_RESTARTING to "O limite de imagens desta sessão foi atingido. Iniciando uma nova sessão do Gemini Live.",
            GeminiLiveAnnouncement.IMAGE_LIMIT_REACHED to "O limite de imagens desta sessão foi atingido. Acione o Live novamente para iniciar outra.",
            GeminiLiveAnnouncement.SESSION_ENDED to "Sua sessão do Live terminou. Acione o Live novamente para iniciar outra.",
            GeminiLiveAnnouncement.FREE_DAILY_LIMIT_REACHED to "Você usou suas 5 sessões gratuitas do Live hoje. Volte amanhã ou assine o Pro para ter mais acesso.",
            GeminiLiveAnnouncement.FREE_BUSY to "O Live está ocupado. Tente novamente em instantes.",
            GeminiLiveAnnouncement.PRIVATE_SESSION_BUSY to "Outra sessão Live ainda está ativa. Encerre-a antes de iniciar o Live Privado.",
            GeminiLiveAnnouncement.PRIVATE_RATE_LIMITED to "Muitas tentativas de iniciar o Live. Aguarde alguns minutos antes de tentar o Live Privado novamente.",
            GeminiLiveAnnouncement.GENERIC_FAILURE to "A sessão do Gemini Live falhou. Tente novamente.",
        ),
        "es" to mapOf(
            GeminiLiveAnnouncement.FREE_SESSION_RESTARTING to "Tu sesión gratuita de Live terminó. Iniciando una nueva sesión gratuita de Gemini Live.",
            GeminiLiveAnnouncement.SESSION_RESTARTING to "Tu sesión de Live terminó. Iniciando una nueva sesión de Gemini Live.",
            GeminiLiveAnnouncement.FREE_IMAGE_SESSION_RESTARTING to "Se alcanzó el límite de imágenes de esta sesión. Iniciando una nueva sesión gratuita de Gemini Live.",
            GeminiLiveAnnouncement.IMAGE_SESSION_RESTARTING to "Se alcanzó el límite de imágenes de esta sesión. Iniciando una nueva sesión de Gemini Live.",
            GeminiLiveAnnouncement.IMAGE_LIMIT_REACHED to "Se alcanzó el límite de imágenes de esta sesión. Activa Live de nuevo para iniciar otra.",
            GeminiLiveAnnouncement.SESSION_ENDED to "Tu sesión de Live terminó. Activa Live de nuevo para iniciar otra.",
            GeminiLiveAnnouncement.FREE_DAILY_LIMIT_REACHED to "Has usado tus 5 sesiones gratuitas de Live de hoy. Vuelve mañana o mejora a Pro para obtener más acceso.",
            GeminiLiveAnnouncement.FREE_BUSY to "Live está ocupado. Inténtalo de nuevo en un momento.",
            GeminiLiveAnnouncement.GENERIC_FAILURE to "La sesión de Gemini Live falló. Inténtalo de nuevo.",
        ),
        "de" to mapOf(
            GeminiLiveAnnouncement.FREE_SESSION_RESTARTING to "Deine kostenlose Live-Sitzung ist beendet. Eine neue kostenlose Gemini-Live-Sitzung wird gestartet.",
            GeminiLiveAnnouncement.SESSION_RESTARTING to "Deine Live-Sitzung ist beendet. Eine neue Gemini-Live-Sitzung wird gestartet.",
            GeminiLiveAnnouncement.FREE_IMAGE_SESSION_RESTARTING to "Das Bildlimit dieser Sitzung ist erreicht. Eine neue kostenlose Gemini-Live-Sitzung wird gestartet.",
            GeminiLiveAnnouncement.IMAGE_SESSION_RESTARTING to "Das Bildlimit dieser Sitzung ist erreicht. Eine neue Gemini-Live-Sitzung wird gestartet.",
            GeminiLiveAnnouncement.IMAGE_LIMIT_REACHED to "Das Bildlimit dieser Sitzung ist erreicht. Löse Live erneut aus, um eine neue Sitzung zu starten.",
            GeminiLiveAnnouncement.SESSION_ENDED to "Deine Live-Sitzung ist beendet. Löse Live erneut aus, um eine neue zu starten.",
            GeminiLiveAnnouncement.FREE_DAILY_LIMIT_REACHED to "Du hast heute deine 5 kostenlosen Live-Sitzungen verwendet. Komm morgen wieder oder wechsle zu Pro.",
            GeminiLiveAnnouncement.FREE_BUSY to "Live ist beschäftigt. Bitte versuche es gleich erneut.",
            GeminiLiveAnnouncement.GENERIC_FAILURE to "Die Gemini-Live-Sitzung ist fehlgeschlagen. Bitte versuche es erneut.",
        ),
        "fr" to mapOf(
            GeminiLiveAnnouncement.FREE_SESSION_RESTARTING to "Votre session Live gratuite est terminée. Démarrage d'une nouvelle session Gemini Live gratuite.",
            GeminiLiveAnnouncement.SESSION_RESTARTING to "Votre session Live est terminée. Démarrage d'une nouvelle session Gemini Live.",
            GeminiLiveAnnouncement.FREE_IMAGE_SESSION_RESTARTING to "La limite d'images de cette session est atteinte. Démarrage d'une nouvelle session Gemini Live gratuite.",
            GeminiLiveAnnouncement.IMAGE_SESSION_RESTARTING to "La limite d'images de cette session est atteinte. Démarrage d'une nouvelle session Gemini Live.",
            GeminiLiveAnnouncement.IMAGE_LIMIT_REACHED to "La limite d'images de cette session est atteinte. Déclenchez de nouveau Live pour en démarrer une autre.",
            GeminiLiveAnnouncement.SESSION_ENDED to "Votre session Live est terminée. Déclenchez de nouveau Live pour en démarrer une autre.",
            GeminiLiveAnnouncement.FREE_DAILY_LIMIT_REACHED to "Vous avez utilisé vos 5 sessions Live gratuites aujourd'hui. Revenez demain ou passez à Pro.",
            GeminiLiveAnnouncement.FREE_BUSY to "Live est occupé. Veuillez réessayer dans un instant.",
            GeminiLiveAnnouncement.GENERIC_FAILURE to "La session Gemini Live a échoué. Veuillez réessayer.",
        ),
        "it" to mapOf(
            GeminiLiveAnnouncement.FREE_SESSION_RESTARTING to "La sessione Live gratuita è terminata. Avvio di una nuova sessione Gemini Live gratuita.",
            GeminiLiveAnnouncement.SESSION_RESTARTING to "La sessione Live è terminata. Avvio di una nuova sessione Gemini Live.",
            GeminiLiveAnnouncement.FREE_IMAGE_SESSION_RESTARTING to "È stato raggiunto il limite di immagini per questa sessione. Avvio di una nuova sessione Gemini Live gratuita.",
            GeminiLiveAnnouncement.IMAGE_SESSION_RESTARTING to "È stato raggiunto il limite di immagini per questa sessione. Avvio di una nuova sessione Gemini Live.",
            GeminiLiveAnnouncement.IMAGE_LIMIT_REACHED to "È stato raggiunto il limite di immagini per questa sessione. Attiva di nuovo Live per avviarne una nuova.",
            GeminiLiveAnnouncement.SESSION_ENDED to "La sessione Live è terminata. Attiva di nuovo Live per avviarne una nuova.",
            GeminiLiveAnnouncement.FREE_DAILY_LIMIT_REACHED to "Hai utilizzato le 5 sessioni Live gratuite di oggi. Torna domani o passa a Pro.",
            GeminiLiveAnnouncement.FREE_BUSY to "Live è occupato. Riprova tra un momento.",
            GeminiLiveAnnouncement.GENERIC_FAILURE to "La sessione Gemini Live non è riuscita. Riprova.",
        ),
        "zh" to mapOf(
            GeminiLiveAnnouncement.FREE_SESSION_RESTARTING to "您的免费 Live 会话已结束。正在启动新的免费 Gemini Live 会话。",
            GeminiLiveAnnouncement.SESSION_RESTARTING to "您的 Live 会话已结束。正在启动新的 Gemini Live 会话。",
            GeminiLiveAnnouncement.FREE_IMAGE_SESSION_RESTARTING to "本次会话已达到图片上限。正在启动新的免费 Gemini Live 会话。",
            GeminiLiveAnnouncement.IMAGE_SESSION_RESTARTING to "本次会话已达到图片上限。正在启动新的 Gemini Live 会话。",
            GeminiLiveAnnouncement.IMAGE_LIMIT_REACHED to "本次会话已达到图片上限。请再次触发 Live 以启动新会话。",
            GeminiLiveAnnouncement.SESSION_ENDED to "您的 Live 会话已结束。请再次触发 Live 以启动新会话。",
            GeminiLiveAnnouncement.FREE_DAILY_LIMIT_REACHED to "您今天的 5 次免费 Live 会话已用完。请明天再来，或升级到 Pro。",
            GeminiLiveAnnouncement.FREE_BUSY to "Live 当前繁忙。请稍后重试。",
            GeminiLiveAnnouncement.GENERIC_FAILURE to "Gemini Live 会话失败。请重试。",
        ),
        "ko" to mapOf(
            GeminiLiveAnnouncement.FREE_SESSION_RESTARTING to "무료 Live 세션이 종료되었습니다. 새로운 무료 Gemini Live 세션을 시작합니다.",
            GeminiLiveAnnouncement.SESSION_RESTARTING to "Live 세션이 종료되었습니다. 새로운 Gemini Live 세션을 시작합니다.",
            GeminiLiveAnnouncement.FREE_IMAGE_SESSION_RESTARTING to "이 세션의 이미지 한도에 도달했습니다. 새로운 무료 Gemini Live 세션을 시작합니다.",
            GeminiLiveAnnouncement.IMAGE_SESSION_RESTARTING to "이 세션의 이미지 한도에 도달했습니다. 새로운 Gemini Live 세션을 시작합니다.",
            GeminiLiveAnnouncement.IMAGE_LIMIT_REACHED to "이 세션의 이미지 한도에 도달했습니다. 새 세션을 시작하려면 Live를 다시 실행하세요.",
            GeminiLiveAnnouncement.SESSION_ENDED to "Live 세션이 종료되었습니다. 새 세션을 시작하려면 Live를 다시 실행하세요.",
            GeminiLiveAnnouncement.FREE_DAILY_LIMIT_REACHED to "오늘 제공되는 무료 Live 세션 5회를 모두 사용했습니다. 내일 다시 이용하거나 Pro로 업그레이드하세요.",
            GeminiLiveAnnouncement.FREE_BUSY to "Live가 사용 중입니다. 잠시 후 다시 시도하세요.",
            GeminiLiveAnnouncement.GENERIC_FAILURE to "Gemini Live 세션에 실패했습니다. 다시 시도하세요.",
        ),
        "ru" to mapOf(
            GeminiLiveAnnouncement.FREE_SESSION_RESTARTING to "Бесплатный сеанс Live завершён. Запускается новый бесплатный сеанс Gemini Live.",
            GeminiLiveAnnouncement.SESSION_RESTARTING to "Сеанс Live завершён. Запускается новый сеанс Gemini Live.",
            GeminiLiveAnnouncement.FREE_IMAGE_SESSION_RESTARTING to "Достигнут лимит изображений для этого сеанса. Запускается новый бесплатный сеанс Gemini Live.",
            GeminiLiveAnnouncement.IMAGE_SESSION_RESTARTING to "Достигнут лимит изображений для этого сеанса. Запускается новый сеанс Gemini Live.",
            GeminiLiveAnnouncement.IMAGE_LIMIT_REACHED to "Достигнут лимит изображений для этого сеанса. Запустите Live снова, чтобы начать новый.",
            GeminiLiveAnnouncement.SESSION_ENDED to "Сеанс Live завершён. Запустите Live снова, чтобы начать новый.",
            GeminiLiveAnnouncement.FREE_DAILY_LIMIT_REACHED to "Сегодня вы использовали все 5 бесплатных сеансов Live. Возвращайтесь завтра или перейдите на Pro.",
            GeminiLiveAnnouncement.FREE_BUSY to "Live сейчас занят. Повторите попытку через некоторое время.",
            GeminiLiveAnnouncement.GENERIC_FAILURE to "Сеанс Gemini Live завершился с ошибкой. Повторите попытку.",
        ),
    )
}

object GeminiLiveReconnectAnnouncementPolicy {
    const val FREE_PROXY_ROTATION_ANNOUNCEMENT_AFTER_MS = 4 * 60 * 1000L + 45 * 1000L

    fun resolve(
        proxySession: Boolean,
        freeTier: Boolean,
        setupCompleted: Boolean,
        listeningDurationMs: Long,
        reason: String,
    ): GeminiLiveAnnouncement? {
        if (!proxySession || !setupCompleted) return null
        val imageLimit = reason.contains("image limit", ignoreCase = true)
        val sessionRotation = reason.contains("session_rotation", ignoreCase = true) ||
            listeningDurationMs >= FREE_PROXY_ROTATION_ANNOUNCEMENT_AFTER_MS
        return when {
            imageLimit && freeTier -> GeminiLiveAnnouncement.FREE_IMAGE_SESSION_RESTARTING
            imageLimit -> GeminiLiveAnnouncement.IMAGE_SESSION_RESTARTING
            sessionRotation && freeTier ->
                GeminiLiveAnnouncement.FREE_SESSION_RESTARTING
            sessionRotation ->
                GeminiLiveAnnouncement.SESSION_RESTARTING
            else -> null
        }
    }

    fun detail(announcement: GeminiLiveAnnouncement?): String? = when (announcement) {
        GeminiLiveAnnouncement.FREE_SESSION_RESTARTING -> "Starting a new free Gemini Live session"
        GeminiLiveAnnouncement.SESSION_RESTARTING -> "Starting a new Gemini Live session"
        GeminiLiveAnnouncement.FREE_IMAGE_SESSION_RESTARTING -> "Image limit reached; starting a new free Gemini Live session"
        GeminiLiveAnnouncement.IMAGE_SESSION_RESTARTING -> "Image limit reached; starting a new Gemini Live session"
        else -> null
    }
}
