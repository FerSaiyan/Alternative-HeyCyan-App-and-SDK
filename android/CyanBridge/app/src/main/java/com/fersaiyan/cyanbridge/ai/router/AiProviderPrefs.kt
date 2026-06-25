package com.fersaiyan.cyanbridge.ai.router

import android.content.Context

enum class AiProviderType(val wire: String, val label: String) {
    MOCK("mock", "Mock (local demo)"),
    COMPANY_BACKEND("company_backend", "Company Backend (stub)"),
    CLI_RELAY("cli_relay", "CLI Relay (Codex/Gemini)"),
    LOCAL_MODELS("local_models", "Local Models (on-device)");

    companion object {
        fun fromWire(value: String?): AiProviderType =
            entries.firstOrNull { it.wire == value } ?: MOCK
    }
}

enum class CliRelayBackend(val wire: String, val label: String) {
    GEMINI("gemini", "Gemini CLI"),
    CODEX("codex", "Codex CLI");

    companion object {
        fun fromWire(value: String?): CliRelayBackend =
            entries.firstOrNull { it.wire == value } ?: GEMINI
    }
}

object AiProviderPrefs {
    private const val PREFS_NAME = "ai_provider_prefs"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_RELAY_BASE_URL = "relay_base_url"
    private const val KEY_RELAY_BACKEND = "relay_backend"
    private const val LEGACY_PUBLIC_RELAY_URL = "https://carelens-wine.vercel.app"
    private const val DEFAULT_PUBLIC_RELAY_URL = "https://cyanbridge.vercel.app"

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
