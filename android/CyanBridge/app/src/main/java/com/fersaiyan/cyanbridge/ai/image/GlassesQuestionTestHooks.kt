package com.fersaiyan.cyanbridge.ai.image

import com.fersaiyan.cyanbridge.BuildConfig
import java.io.File

/**
 * Debug-build seams that let emulator tests drive the real glasses question flow (wake word or
 * photo button → question capture → routing → photo → local model → speech) with fixture audio
 * and photos instead of a microphone and glasses. Every hook is ignored in release builds.
 */
object GlassesQuestionTestHooks {
    /**
     * While set, supplies a 16 kHz mono PCM16 WAV in place of each recorded question; returning
     * null means "nobody spoke" (for example the follow-up window). The microphone is never used.
     */
    @Volatile var questionAudio: (() -> File?)? = null

    val questionAudioActive: Boolean get() = BuildConfig.DEBUG && questionAudio != null

    /** Supplies a JPEG in place of a glasses photo capture. */
    @Volatile var photo: (() -> File?)? = null

    /** Observes flow events such as "speak", "route", "image_query" and "reply". */
    @Volatile var events: ((event: String, detail: String) -> Unit)? = null

    fun takeQuestionAudio(cacheDir: File): File? {
        if (!BuildConfig.DEBUG) return null
        val fixture = questionAudio?.invoke() ?: return null
        val copy = File.createTempFile("question-test-", ".wav", File(cacheDir, "audio-questions").apply { mkdirs() })
        fixture.copyTo(copy, overwrite = true)
        return copy
    }

    fun takePhoto(cacheDir: File): File? {
        if (!BuildConfig.DEBUG) return null
        val fixture = photo?.invoke() ?: return null
        return File(cacheDir, "Test_AI_${System.currentTimeMillis()}.jpg").also { fixture.copyTo(it, overwrite = true) }
    }

    val photoActive: Boolean get() = BuildConfig.DEBUG && photo != null

    /** Drops the web search's AI answer and info box, leaving only the results (the no-AI-Overview path). */
    @Volatile var webResultsOnly: Boolean = false
        get() = BuildConfig.DEBUG && field

    fun emit(event: String, detail: String) {
        if (BuildConfig.DEBUG) events?.invoke(event, detail)
    }

    fun reset() {
        questionAudio = null
        photo = null
        events = null
        webResultsOnly = false
    }
}
