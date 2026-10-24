package com.fersaiyan.cyanbridge.localmodels.remote

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs.RemoteOpenAi
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs.RemoteOpenAiSecrets
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.remove
import com.fersaiyan.cyanbridge.shared.prefs.set
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
    /** Base URL, e.g. "http://192.168.1.50:11434/v1" or "http://100.64.0.1:8080/v1". */
    fun getBaseUrl(context: Context): String = RemoteOpenAi.baseUrl[context].trim()

    fun setBaseUrl(context: Context, url: String) {
        RemoteOpenAi.baseUrl[context] = url.trim()
    }

    /** Optional API key (some servers like OpenAI-compatible proxies require one). */
    fun getApiKey(context: Context): String {
        val encrypted = RemoteOpenAiSecrets.apiKey[context].trim()
        if (encrypted.isNotBlank()) return encrypted

        // One-time migration from the legacy plaintext preference.
        val legacy = RemoteOpenAi.legacyApiKey[context].trim()
        if (legacy.isNotBlank()) {
            val migrated = RemoteOpenAiSecrets.edit(context, commit = true) { this[RemoteOpenAiSecrets.apiKey] = legacy }
            if (migrated) RemoteOpenAi.edit(context, commit = true) { RemoteOpenAi.legacyApiKey.remove(this) }
        }
        return legacy
    }

    fun setApiKey(context: Context, key: String) {
        RemoteOpenAiSecrets.apiKey[context] = key.trim()
        RemoteOpenAi.legacyApiKey.remove(context)
    }

    /** Model name to send in the request, e.g. "llama3", "qwen2.5:7b", "gpt-3.5-turbo". */
    fun getModel(context: Context): String = RemoteOpenAi.model[context].trim()

    fun setModel(context: Context, model: String) {
        RemoteOpenAi.model[context] = model.trim()
    }

    /** Whether the remote server is enabled as the active local-model backend. */
    fun isEnabled(context: Context): Boolean = RemoteOpenAi.enabled[context]

    fun setEnabled(context: Context, enabled: Boolean) {
        RemoteOpenAi.enabled[context] = enabled
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
    fun isBridgeEnabled(context: Context): Boolean = RemoteOpenAi.studioBridgeEnabled[context]

    fun setBridgeEnabled(context: Context, enabled: Boolean) {
        RemoteOpenAi.studioBridgeEnabled[context] = enabled
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
