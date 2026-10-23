package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelRequestPriority
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider

/** Translates focus text outside the safety-critical frame processing path. */
class WalkingAidFocusTranslator internal constructor(
    private val context: Context,
    private val completion: suspend (source: String, prompt: String) -> String,
) {
    suspend fun translateToEnglish(text: String, source: String): String {
        val original = text.trim().take(500)
        if (original.isBlank()) return ""
        WalkingAidPreferences.getCachedFocusTranslation(context, original, source)?.let { return it }

        return sanitizeTranslation(completion(source, translationPrompt(original)), original)
    }

    companion object {
        fun create(context: Context): WalkingAidFocusTranslator {
            val appContext = context.applicationContext
            val localModels = LocalModelsProvider()
            return WalkingAidFocusTranslator(appContext) { source, prompt ->
                if (source.equals("cloud", ignoreCase = true)) {
                    CliRelayClient.chat(
                        context = appContext,
                        chatId = "walking_aid_focus_${System.currentTimeMillis()}",
                        prompt = prompt,
                        messages = listOf(mapOf("role" to "user", "content" to prompt)),
                    ).getOrThrow()
                } else {
                    localModels.streamChat(
                        context = appContext,
                        messages = listOf(
                            mapOf("role" to "system", "content" to TRANSLATION_SYSTEM_PROMPT),
                            mapOf("role" to "user", "content" to prompt),
                        ),
                        requestPriority = LocalModelRequestPriority.LOW,
                        maxTokens = 128,
                    )
                }
            }
        }

        internal fun sanitizeTranslation(reply: String, original: String): String {
            val cleaned = reply
                .trim()
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
                .replace(Regex("^(translation|english)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
                .trim('"', '\'', '`')
            require(cleaned.isNotBlank()) { "Translation was empty" }
            require(cleaned.any { it in 'a'..'z' || it in 'A'..'Z' }) {
                "Translation did not contain English text"
            }
            val maximumUsefulLength = maxOf(300, original.length * 3)
            require(cleaned.length <= maximumUsefulLength) { "Translation was unexpectedly long" }
            return cleaned
        }

        private fun translationPrompt(text: String): String = buildString {
            appendLine(TRANSLATION_SYSTEM_PROMPT)
            appendLine("فقط ترجمهٔ انگلیسی را بدون گیومه یا توضیح برگردان.")
            append("متن کاربر: ")
            append(text)
        }

        private const val TRANSLATION_SYSTEM_PROMPT =
            "درخواست تمرکز کاربر برای کمک‌یار پیاده‌روی را به انگلیسی کوتاه و روان ترجمه کن. " +
                "همهٔ اشیا، دسته‌ها و مفاهیم ایمنی درخواستی را حفظ کن و چیزی اضافه نکن."
    }
}
