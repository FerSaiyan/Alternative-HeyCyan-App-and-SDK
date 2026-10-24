package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import com.fersaiyan.cyanbridge.audio.TtsSpeaker
import java.util.Locale

/** Process-scoped TTS for explicitly enabled notification announcements. */
object LocalAgentNotificationSpeaker {
    private const val MAX_ANNOUNCEMENT_CHARS = 600
    private const val TAG = "LocalAgentNotification"

    private val lock = Any()
    private var speaker: TtsSpeaker? = null

    fun speak(context: Context, text: String) {
        val clean = text.trim().take(MAX_ANNOUNCEMENT_CHARS)
        if (clean.isBlank()) return

        val current = synchronized(lock) {
            speaker ?: TtsSpeaker(context.applicationContext, TAG, onInit = ::onEngineInit).also { speaker = it }
        }
        // Until the engine is ready only the newest announcement is kept.
        current.whenReady { it.speak(clean, "local_agent_notification") }
    }

    fun stop() {
        val engine = synchronized(lock) { speaker.also { speaker = null } }
        engine?.shutdown()
    }

    private fun onEngineInit(engine: TtsSpeaker, ok: Boolean) {
        if (ok) {
            engine.setLanguage(Locale.US)
            return
        }
        // A failed engine is dropped so the next announcement creates a fresh one.
        val failed = synchronized(lock) { engine.takeIf { speaker === it }?.also { speaker = null } }
        failed?.shutdown()
    }
}
