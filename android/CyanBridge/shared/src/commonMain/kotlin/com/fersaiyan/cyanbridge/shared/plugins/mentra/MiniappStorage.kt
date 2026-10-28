package com.fersaiyan.cyanbridge.shared.plugins.mentra

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A miniapp's string key-value store (`session.storage.*`, and the polyfill's `localStorage`):
 * phone-local, per package, saved as one JSON object through [persist] after every change.
 */
class MiniappKeyValueStore(initialJson: String?, private val persist: (String) -> Unit) {
    private val lock = SynchronizedObject()
    private val values: LinkedHashMap<String, String> = LinkedHashMap<String, String>().apply {
        val obj = initialJson?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        obj?.forEach { (key, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.let { put(key, it.content) } }
    }
    private var bytes: Long = values.entries.sumOf { (it.key.length + it.value.length).toLong() * 2 }

    fun get(key: String): String? = synchronized(lock) { values[key] }

    fun has(key: String): Boolean = synchronized(lock) { key in values }

    fun keys(): List<String> = synchronized(lock) { values.keys.toList() }

    fun all(): Map<String, String> = synchronized(lock) { LinkedHashMap(values) }

    /** Sets [entries]; false (and nothing changes) when the store would grow past [MAX_BYTES]. */
    fun set(entries: Map<String, String>): Boolean {
        val snapshot = synchronized(lock) {
            var next = bytes
            entries.forEach { (key, value) ->
                next += (key.length + value.length).toLong() * 2 - (values[key]?.let { (key.length + it.length).toLong() * 2 } ?: 0L)
            }
            if (next > MAX_BYTES) return false
            values.putAll(entries)
            bytes = next
            encode()
        }
        persist(snapshot)
        return true
    }

    fun remove(key: String) {
        val snapshot = synchronized(lock) {
            val old = values.remove(key) ?: return
            bytes -= (key.length + old.length).toLong() * 2
            encode()
        }
        persist(snapshot)
    }

    fun clear() {
        synchronized(lock) {
            values.clear()
            bytes = 0
        }
        persist("{}")
    }

    private fun encode(): String = buildJsonObject { values.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString()

    companion object {
        /** Mentra asks apps to stay under about 1 MB; refuse past 4 MB (UTF-16 estimate). */
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
