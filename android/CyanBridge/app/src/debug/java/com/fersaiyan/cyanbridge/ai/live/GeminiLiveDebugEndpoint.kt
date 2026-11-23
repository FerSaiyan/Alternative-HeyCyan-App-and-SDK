package com.fersaiyan.cyanbridge.ai.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only: points Gemini Live at a local WebSocket (an emulator test's fake Live server)
 * instead of the relay, so tool calls and vision timing can be scripted. Release builds use the
 * no-op object in src/release.
 */
object GeminiLiveDebugEndpoint {
    private fun file(context: Context) = java.io.File(context.noBackupFilesDir, "gemini_live_debug_ws_url")

    fun tokenProvider(context: Context): GeminiLiveTokenProvider? {
        val url = runCatching { file(context).readText().trim() }.getOrNull()
            ?.takeIf { it.startsWith("ws://") || it.startsWith("wss://") } ?: return null
        Log.w("GeminiLiveDebug", "Gemini Live uses the debug endpoint $url")
        return object : GeminiLiveTokenProvider {
            override suspend fun requestToken(language: String, imagePrompt: String, tools: List<String>): LiveTokenConfig =
                LiveTokenConfig(
                    token = "",
                    model = "models/debug-fake-live",
                    websocketUrl = url,
                    expiresAtMs = System.currentTimeMillis() + 15 * 60 * 1000L,
                    reservationId = "direct",
                )
        }
    }

    internal fun setUrl(context: Context, url: String?) {
        if (url.isNullOrBlank()) file(context).delete() else file(context).writeText(url)
    }
}

/**
 * `adb shell am broadcast -n com.fersaiyan.cyanbridge/.ai.live.GeminiLiveDebugReceiver
 *   -a com.fersaiyan.cyanbridge.debug.GEMINI_LIVE --es url ws://10.0.2.2:18900 [--ei vision_mode 3] [--ez start true] [--ez stop true]`
 * (`--es url ""` clears the override).
 */
class GeminiLiveDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra("url")) GeminiLiveDebugEndpoint.setUrl(context, intent.getStringExtra("url"))
        // 0 = only the first image, 2 = a photo per speech turn, 3 = live video (latest frame on speech).
        if (intent.hasExtra("vision_mode")) GeminiLiveVisionPreferences.setImageDelaySeconds(context, intent.getIntExtra("vision_mode", 0))
        if (intent.getBooleanExtra("stop", false)) GeminiLiveForegroundService.stop(context)
        if (intent.getBooleanExtra("start", false)) {
            GeminiLiveForegroundService.start(context, language = "en", imagePrompt = "Describe what you can see.")
        }
        Log.i("GeminiLiveDebug", "debug command handled url=${intent.hasExtra("url")} start=${intent.getBooleanExtra("start", false)}")
    }
}
