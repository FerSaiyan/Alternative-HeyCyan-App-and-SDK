package com.fersaiyan.cyanbridge.lensmoo

/** Shared by photo chat and hands-free inference, including cloud requests. */
internal fun lensMooMessages(messages: List<Map<String, String>>): List<Map<String, String>> =
    listOf(mapOf(
        "role" to "system",
        "content" to "Ты голосовой помощник в очках. Отвечай на русском языке, кратко и по существу. " +
            "Используй приложенное фото для ответа о том, что перед пользователем. " +
            "Меняй язык только по явной просьбе пользователя.",
    )) + messages
