package com.fersaiyan.cyanbridge.ai.live

import android.content.Context

/** Release builds never override the Gemini Live endpoint (see the debug source set). */
object GeminiLiveDebugEndpoint {
    @Suppress("UNUSED_PARAMETER")
    fun tokenProvider(context: Context): GeminiLiveTokenProvider? = null
}
