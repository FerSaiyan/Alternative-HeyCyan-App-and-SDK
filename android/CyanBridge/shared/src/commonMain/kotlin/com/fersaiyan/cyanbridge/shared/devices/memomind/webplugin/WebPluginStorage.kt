package com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `storage.*` for one plugin: small JSON values private to the plugin ID, kept apart from the
 * app's own preferences. [persist] receives the whole store as JSON after every change.
 */
class WebPluginStorage(
    initialJson: String?,
    private val persist: (String) -> Unit,
    private val maxBytes: Int = MAX_BYTES,
) {
    private val values: MutableMap<String, JsonElement> =
        (runCatching { initialJson?.let(Json::parseToJsonElement) }.getOrNull() as? JsonObject)?.toMutableMap() ?: mutableMapOf()

    fun get(key: String): JsonElement? = values[validKey(key)]

    fun set(key: String, value: JsonElement) {
        val candidate = values.toMutableMap().apply { put(validKey(key), value) }
        val json = JsonObject(candidate).toString()
        if (json.encodeToByteArray().size > maxBytes) {
            bridgeError(WebPluginErrorCode.QUOTA_EXCEEDED, "Plugin storage is limited to ${maxBytes / 1024} KiB")
        }
        values[key] = value
        persist(json)
    }

    fun remove(key: String): Boolean = (values.remove(validKey(key)) != null).also { if (it) persist(JsonObject(values).toString()) }

    fun clear() {
        values.clear()
        persist("{}")
    }

    private fun validKey(key: String): String {
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH) invalidRequest("key must be 1..$MAX_KEY_LENGTH characters")
        return key
    }

    companion object {
        const val MAX_BYTES = 1024 * 1024
        const val MAX_KEY_LENGTH = 256
    }
}
