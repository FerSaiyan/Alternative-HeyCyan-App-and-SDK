package com.fersaiyan.cyanbridge.localmodels.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.net.URI

/**
 * Stores configuration for a remote OpenAI-compatible inference server
 * (Ollama, llama.cpp server, vLLM, text-generation-inference, etc.).
 *
 * Users can point this at any server on their LAN or Tailnet that exposes
 * the POST /v1/chat/completions endpoint. Multimodal requests use OpenAI
 * image_url data URLs and input_audio base64 parts (WAV or MP3 audio).
 */
object RemoteOpenAiPrefs {
    private const val PREFS = "remote_openai_prefs"
    private const val SECRET_PREFS = "remote_openai_secrets"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BRIDGE_ENABLED = "studio_bridge_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            SECRET_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Base URL, e.g. "http://192.168.1.50:11434/v1" or "http://100.64.0.1:8080/v1". */
    fun getBaseUrl(context: Context): String {
        return prefs(context).getString(KEY_BASE_URL, "")?.trim().orEmpty()
    }

    fun setBaseUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_BASE_URL, url.trim()).apply()
    }

    /** Optional API key (some servers like OpenAI-compatible proxies require one). */
    fun getApiKey(context: Context): String {
        val encrypted = secretPrefs(context).getString(KEY_API_KEY, "")?.trim().orEmpty()
        if (encrypted.isNotBlank()) return encrypted

        // One-time migration from the legacy plaintext preference.
        val legacy = prefs(context).getString(KEY_API_KEY, "")?.trim().orEmpty()
        if (legacy.isNotBlank()) {
            val migrated = secretPrefs(context).edit().putString(KEY_API_KEY, legacy).commit()
            if (migrated) prefs(context).edit().remove(KEY_API_KEY).commit()
        }
        return legacy
    }

    fun setApiKey(context: Context, key: String) {
        secretPrefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
        prefs(context).edit().remove(KEY_API_KEY).apply()
    }

    /** Model name to send in the request, e.g. "llama3", "qwen2.5:7b", "gpt-3.5-turbo". */
    fun getModel(context: Context): String {
        return prefs(context).getString(KEY_MODEL, "")?.trim().orEmpty()
    }

    fun setModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_MODEL, model.trim()).apply()
    }

    /** Whether the remote server is enabled as the active local-model backend. */
    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Returns true if we have at least a base URL and model configured. */
    fun isConfigured(context: Context): Boolean {
        return getBaseUrl(context).isNotBlank() && getModel(context).isNotBlank()
    }

    /** Whether requests through Local Models should currently use the remote server. */
    fun isActive(context: Context): Boolean {
        return isRemoteOpenAiActive(
            enabled = isEnabled(context),
            baseUrl = getBaseUrl(context),
            model = getModel(context),
        )
    }

    /** Whether the Studio Bridge (approval notifications) is enabled. */
    fun isBridgeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_BRIDGE_ENABLED, false)
    }

    fun setBridgeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BRIDGE_ENABLED, enabled).apply()
    }

    /** Returns true if the bridge and its LLM classifier are configured. */
    fun isBridgeConfigured(context: Context): Boolean {
        return isBridgeEnabled(context) &&
            getBaseUrl(context).isNotBlank() &&
            getApiKey(context).isNotBlank() &&
            getModel(context).isNotBlank()
    }

    /** API keys may use cleartext only on loopback, LAN, or Tailscale addresses. */
    fun isCredentialTransportAllowed(baseUrl: String): Boolean {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return false
        if (uri.scheme.equals("https", ignoreCase = true)) return true
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        val host = uri.host?.lowercase().orEmpty()
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".ts.net")) return true
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 ||
            parts[0] == 127 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 100 && parts[1] in 64..127)
    }
}

internal fun isRemoteOpenAiActive(enabled: Boolean, baseUrl: String, model: String): Boolean {
    return enabled && baseUrl.isNotBlank() && model.isNotBlank()
}
