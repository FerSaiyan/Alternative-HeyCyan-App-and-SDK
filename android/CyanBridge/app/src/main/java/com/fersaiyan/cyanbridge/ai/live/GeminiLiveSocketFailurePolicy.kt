package com.fersaiyan.cyanbridge.ai.live

import org.json.JSONObject

/**
 * Classifies a Live WebSocket handshake/transport failure into the user-facing terminal outcomes
 * that must not be retried. [Retryable] falls through to the generic reconnect handling.
 */
internal object GeminiLiveSocketFailurePolicy {
    sealed interface Outcome {
        /** Free tier spent its daily Live minutes (HTTP 429 `live_free_daily_limit`). */
        data class FreeDailyLimit(val detail: String) : Outcome

        /** Economy proxy rejected the session with a 4xx. */
        data class EconomyRejected(val detail: String) : Outcome

        /** The free proxy is full (any 429); a null [detail] means use the localized queue text. */
        data class FreeQueued(val detail: String?) : Outcome

        object Retryable : Outcome
    }

    /** Only 429 bodies are read, mirroring what the socket listener consumes. */
    fun shouldReadBody(httpCode: Int?): Boolean = httpCode == 429

    fun classify(
        config: LiveTokenConfig,
        httpCode: Int?,
        responseBody: String,
        failureMessage: String?,
    ): Outcome {
        if (httpCode == 429 && config.freeTier && responseBody.contains("live_free_daily_limit")) {
            val detail = runCatching {
                JSONObject(responseBody.ifBlank { "{}" }).optString("message", "")
                    .takeIf { it.isNotBlank() }
            }.getOrNull() ?: "You've used your 25 free Live minutes for today. Come back tomorrow, or use Pro quota for more access."
            return Outcome.FreeDailyLimit(detail)
        }
        if (config.economy && httpCode != null && httpCode in 400..499) {
            return Outcome.EconomyRejected(
                when (httpCode) {
                    402 -> "Live quota exhausted. Please wait for quota reset or upgrade."
                    429 -> "Live is busy or another session is active. Please try again shortly."
                    else -> "Unable to start Economy Live. Check your Pro subscription and try again."
                },
            )
        }
        // Free queue: only the CyanBridge free proxy returns localized live_free_queued.
        if (httpCode == 429 && config.reservationId == "free-proxy") {
            val raw = responseBody
            val isQueued = raw.contains("live_free_queued") || raw.contains("live_rate_limited") || failureMessage?.contains("429") == true
            if (isQueued || raw.contains("live_free_queued")) {
                val queuedDetail = runCatching {
                    val json = JSONObject(raw.ifBlank { "{}" })
                    json.optString("message", "").takeIf { it.isNotBlank() }
                }.getOrNull()
                return Outcome.FreeQueued(queuedDetail)
            }
            // Also treat any 429 during free as queue for UX
            return Outcome.FreeQueued(null)
        }
        return Outcome.Retryable
    }

    /** Fallback free-queue text for the app language tag (lowercased prefix match). */
    fun freeQueueMessage(languageTag: String): String {
        val tag = languageTag.lowercase()
        return when {
            tag.startsWith("pt") -> "Muitos usuários gratuitos no modo Live agora. Você está na fila — tente novamente em instantes."
            tag.startsWith("es") -> "Demasiados usuarios gratuitos en el modo Live ahora mismo. Estás en cola — inténtalo de nuevo en un momento."
            tag.startsWith("de") -> "Zu viele kostenlose Nutzer im Live-Modus. Du bist in der Warteschlange — bitte versuche es gleich erneut."
            tag.startsWith("fr") -> "Trop d'utilisateurs gratuits en mode Live. Vous êtes en file d'attente — réessayez dans un instant."
            tag.startsWith("it") -> "Troppi utenti gratuiti in modalità Live. Sei in coda — riprova tra un momento."
            tag.startsWith("zh") -> "当前 Live 模式的免费用户过多，您已进入排队，请稍后重试。"
            tag.startsWith("ko") -> "현재 Live 모드에 무료 사용자가 많아 대기열에 있습니다. 잠시 후 다시 시도해 주세요."
            tag.startsWith("ru") -> "Слишком много бесплатных пользователей в режиме Live. Вы в очереди — попробуйте ещё раз через мгновение."
            else -> "Too many free users on Live mode right now, you are in queue. Please try again in a moment."
        }
    }
}
