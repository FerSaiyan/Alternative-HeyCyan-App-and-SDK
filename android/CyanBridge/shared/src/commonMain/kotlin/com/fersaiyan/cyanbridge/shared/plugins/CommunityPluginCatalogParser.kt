package com.fersaiyan.cyanbridge.shared.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Parses the relay `/plugins` response (port of CommunityPluginsActivity's parser). */
object CommunityPluginCatalogParser {
    fun parse(body: String): List<CommunityPluginCardData> {
        val root = Json.parseToJsonElement(body.trim())
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["plugins"] ?: root["data"]) as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return array.mapNotNull { element ->
            val plugin = element as? JsonObject ?: return@mapNotNull null
            val title = plugin.string("title", "name") ?: return@mapNotNull null
            CommunityPluginCardData(
                id = plugin.string("id", "slug") ?: title,
                title = title,
                author = plugin.string("author", "publisher") ?: "Unknown",
                description = plugin.string("description").orEmpty(),
                badge = plugin.string("badge", "category") ?: "Other",
                downloadsAll = plugin.metric("downloads", "all_time", "all", "allTime"),
                downloadsMonthly = plugin.metric("downloads", "monthly", "month"),
                downloadsWeekly = plugin.metric("downloads", "weekly", "week"),
                votesAll = plugin.metric("votes", "all_time", "all", "allTime"),
                votesMonthly = plugin.metric("votes", "monthly", "month"),
                votesWeekly = plugin.metric("votes", "weekly", "week"),
                trendAll = plugin.metric("trend", "all_time", "all", "allTime"),
                trendMonthly = plugin.metric("trend", "monthly", "month"),
                trendWeekly = plugin.metric("trend", "weekly", "week"),
                taskerNetLink = plugin.string("taskernet_link", "taskerNetLink", "taskernetLink"),
                downloadUrl = plugin.string("download_url", "downloadUrl"),
            )
        }
    }

    private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.int(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.intOrNull
    }

    private fun JsonObject.metric(key: String, vararg names: String): Int {
        (this[key] as? JsonObject)?.int(*names)?.let { return it }
        val flatKeys = names.map { "${key}_$it" } + names.map { key + it.replaceFirstChar(Char::uppercase) }
        return int(*flatKeys.toTypedArray()) ?: 0
    }
}
