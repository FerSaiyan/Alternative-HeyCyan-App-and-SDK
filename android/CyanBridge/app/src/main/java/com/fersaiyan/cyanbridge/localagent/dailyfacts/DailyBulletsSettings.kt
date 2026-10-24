package com.fersaiyan.cyanbridge.localagent.dailyfacts

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs.DailyBullets
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

object DailyBulletsSettings {
    val DEFAULT_BULLET_PROMPT = """
        You summarize one mobile screen OCR event into exactly one bullet.

        The app package is provided below, and the app name may also appear inside the OCR text.

        APP_PACKAGE: ${'$'}{event.packageName}
        EVENT_TIME: ${'$'}{event.time}
        OCR_TEXT:
        ${'$'}{event.text}

        Return JSON only:
        {"skip": false, "bullet": "...", "confidence": 0.0}

        Rules:
        - Keep bullet factual and concise (max 26 words)
        - Preserve concrete details like person names, contact names, topics, or action context when visible
        - If OCR is too noisy or meaningless, set skip=true
        - Do not invent details outside OCR
    """.trimIndent()

    fun getCustomBulletPrompt(context: Context): String = DailyBullets.customBulletPrompt[context]

    fun getBulletPrompt(context: Context): String =
        getCustomBulletPrompt(context).ifBlank { DEFAULT_BULLET_PROMPT }

    fun setCustomBulletPrompt(context: Context, prompt: String) {
        DailyBullets.customBulletPrompt[context] = prompt
    }

    fun restoreDefaultBulletPrompt(context: Context) {
        setCustomBulletPrompt(context, DEFAULT_BULLET_PROMPT)
    }

    fun getMaxTokensPerBullet(context: Context): Int = DailyBullets.maxTokensPerBullet[context]

    fun setMaxTokensPerBullet(context: Context, tokens: Int) {
        DailyBullets.maxTokensPerBullet[context] = tokens.coerceAtLeast(0)
    }
}
