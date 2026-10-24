package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import com.fersaiyan.cyanbridge.shared.ai.DEFAULT_RELAY_URL

typealias AiProviderType = com.fersaiyan.cyanbridge.shared.ai.AiProviderType
typealias CliRelayBackend = com.fersaiyan.cyanbridge.shared.ai.CliRelayBackend

object AiProviderPrefs {
    private const val PREFS_NAME = "ai_provider_prefs"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_RELAY_BASE_URL = "relay_base_url"
    private const val KEY_RELAY_BACKEND = "relay_backend"
    private const val LEGACY_PUBLIC_RELAY_URL = "https://carelens-wine.vercel.app"
    private const val DEFAULT_PUBLIC_RELAY_URL = DEFAULT_RELAY_URL

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getProvider(context: Context): AiProviderType =
        AiProviderType.fromWire(prefs(context).getString(KEY_PROVIDER, AiProviderType.CLI_RELAY.wire))

    fun setProvider(context: Context, provider: AiProviderType) {
        prefs(context).edit().putString(KEY_PROVIDER, provider.wire).apply()
    }

    fun getRelayBaseUrl(context: Context): String =
        prefs(context).getString(KEY_RELAY_BASE_URL, DEFAULT_PUBLIC_RELAY_URL)
            ?.trim()
            .orEmpty()
            .let { current ->
                when {
                    current.isBlank() -> DEFAULT_PUBLIC_RELAY_URL
                    current == LEGACY_PUBLIC_RELAY_URL -> DEFAULT_PUBLIC_RELAY_URL
                    else -> current
                }
            }

    fun setRelayBaseUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_RELAY_BASE_URL, value.trim()).apply()
    }

    fun getRelayBackend(context: Context): CliRelayBackend =
        CliRelayBackend.fromWire(prefs(context).getString(KEY_RELAY_BACKEND, CliRelayBackend.GEMINI.wire))

    fun setRelayBackend(context: Context, backend: CliRelayBackend) {
        prefs(context).edit().putString(KEY_RELAY_BACKEND, backend.wire).apply()
    }
}
