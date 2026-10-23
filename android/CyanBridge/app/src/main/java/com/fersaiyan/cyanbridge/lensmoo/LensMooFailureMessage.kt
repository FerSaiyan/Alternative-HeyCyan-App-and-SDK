package com.fersaiyan.cyanbridge.lensmoo

/** Only local diagnoses are spoken; never repeat server bodies, owner IDs or credentials. */
internal object LensMooFailureMessage {
    enum class Stage { CAPTURE, SAVE, REMOTE }
    fun forFailure(stage: Stage, error: Throwable): String = when (stage) {
        Stage.CAPTURE -> when {
            error.message == "Camera state timeout: 5710" ->
                "Очки не подтвердили выключение камеры. Ответ AI не запрашивался. Подключите очки заново."
            error.message == "Camera state timeout: 57B1" ->
                "Очки не подтвердили сохранение снимка. Ответ AI не запрашивался. Подключите очки заново."
            else -> "Не удалось получить снимок с очков. Ответ AI не запрашивался. Подключите очки заново."
        }
        Stage.SAVE -> "Не удалось сохранить фото на телефоне. Ответ AI не запрашивался."
        Stage.REMOTE -> "Не удалось получить ответ от настроенного AI-сервера. Проверьте сеть, модель и ключ доступа."
    }
}
