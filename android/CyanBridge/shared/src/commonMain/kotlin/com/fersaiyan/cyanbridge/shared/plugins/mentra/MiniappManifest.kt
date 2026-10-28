package com.fersaiyan.cyanbridge.shared.plugins.mentra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** A miniapp rejected while installing or updating; [message] says why (shown to the user). */
class MiniappException(message: String) : Exception(message)

/** Phone permissions a miniapp declares (`miniapp.schema.json` → `permissions[].type`). */
enum class MiniappPermissionType {
    MICROPHONE,
    CAMERA,
    PHONE_CAMERA,
    CALENDAR,
    LOCATION,
    BACKGROUND_LOCATION,
    READ_NOTIFICATIONS,
    POST_NOTIFICATIONS,
}

/** Glasses hardware a miniapp uses (`hardwareRequirements[].type`). */
enum class MiniappHardware { CAMERA, DISPLAY, MICROPHONE, SPEAKER, IMU, BUTTON, LIGHT, WIFI }

data class MiniappPermission(val type: MiniappPermissionType, val required: Boolean, val description: String?)

data class MiniappHardwareRequirement(val type: MiniappHardware, val required: Boolean, val description: String?)

/**
 * A validated `miniapp.json` (Mentra miniapp manifest, schema `miniapp/v1`). Only the fields a
 * host acts on are kept; `actions` are counted, not run (CyanBridge has no system agent that
 * calls them yet).
 */
data class MiniappManifest(
    val packageName: String,
    val version: String,
    val name: String,
    val description: String?,
    val icon: String?,
    /** False for `"type": "background"` miniapps, which have no WebView. */
    val hasUi: Boolean,
    val sdkVersion: String?,
    val minHostVersion: String?,
    val backgroundEntry: String,
    val uiEntry: String?,
    val permissions: List<MiniappPermission>,
    val hardware: List<MiniappHardwareRequirement>,
    val actionCount: Int,
) {
    fun declares(type: MiniappPermissionType) = permissions.any { it.type == type }

    fun requires(hardware: MiniappHardware) = this.hardware.any { it.type == hardware && it.required }

    companion object {
        const val FILE_NAME = "miniapp.json"
        private val json = Json { ignoreUnknownKeys = true }

        /** The schema's reverse-DNS rule (letters, digits and `_`; at least two labels). */
        val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        private val SEMVER = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")
        private const val MAX_PATH = 256

        /** Parses and validates manifest text; throws [MiniappException] with the reason. */
        fun parse(text: String): MiniappManifest {
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: throw MiniappException("miniapp.json is not a JSON object")
            return try {
                parse(root)
            } catch (error: IllegalArgumentException) {
                throw MiniappException("Invalid miniapp.json: ${error.message}")
            }
        }

        private fun parse(root: JsonObject): MiniappManifest {
            val packageName = root.requireString("packageName", 128)
            require(PACKAGE_NAME.matches(packageName)) { "packageName must be a reverse-DNS identifier" }
            val version = root.requireString("version", 64)
            require(SEMVER.matches(version)) { "version must be a semantic version" }
            val name = root.requireString("name", 80)
            val type = root.optionalString("type") ?: "standard"
            require(type == "standard" || type == "background") { "type must be standard or background" }
            val entry = root["entry"] as? JsonObject
                ?: throw IllegalArgumentException("entry is missing (only two-layer bundles are supported)")
            require(entry.keys.all { it == "background" || it == "ui" }) { "unknown entry field" }
            val background = entry.requireString("background", MAX_PATH).also(::requireSafePath)
            val ui = entry.optionalString("ui")?.also(::requireSafePath)
            require(ui == null || ui.lowercase().endsWith(".html")) { "entry.ui must be an HTML file" }
            val hasUi = type == "standard" && ui != null
            return MiniappManifest(
                packageName = packageName,
                version = version,
                name = name,
                description = root.optionalString("description")?.take(4_000),
                icon = root.optionalString("icon")?.takeIf { it.length <= MAX_PATH },
                hasUi = hasUi,
                sdkVersion = root.optionalString("sdkVersion"),
                minHostVersion = root.optionalString("minHostVersion"),
                backgroundEntry = background,
                uiEntry = ui.takeIf { hasUi },
                permissions = permissions(root["permissions"]),
                hardware = hardware(root["hardwareRequirements"]),
                actionCount = (root["actions"] as? JsonArray)?.size ?: 0,
            )
        }

        private fun permissions(value: JsonElement?): List<MiniappPermission> {
            if (value == null) return emptyList()
            val array = value as? JsonArray ?: throw IllegalArgumentException("permissions must be an array")
            val seen = mutableSetOf<MiniappPermissionType>()
            return array.map { element ->
                val entry = element as? JsonObject ?: throw IllegalArgumentException("a permission must be an object")
                val type = entry.optionalString("type")?.let { name -> MiniappPermissionType.entries.firstOrNull { it.name == name } }
                    ?: throw IllegalArgumentException("unknown permission ${entry["type"]}")
                require(seen.add(type)) { "duplicate permission $type" }
                val required = entry["required"]?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull }
                require(entry["required"] == null || required != null) { "$type.required must be a boolean" }
                MiniappPermission(type, required ?: true, entry.optionalString("description")?.take(300))
            }
        }

        private fun hardware(value: JsonElement?): List<MiniappHardwareRequirement> {
            if (value == null) return emptyList()
            val array = value as? JsonArray ?: throw IllegalArgumentException("hardwareRequirements must be an array")
            return array.map { element ->
                val entry = element as? JsonObject ?: throw IllegalArgumentException("a hardware requirement must be an object")
                val type = entry.optionalString("type")?.let { name -> MiniappHardware.entries.firstOrNull { it.name == name } }
                    ?: throw IllegalArgumentException("unknown hardware ${entry["type"]}")
                val level = entry.optionalString("level")
                require(level == "REQUIRED" || level == "OPTIONAL") { "$type.level must be REQUIRED or OPTIONAL" }
                MiniappHardwareRequirement(type, level == "REQUIRED", entry.optionalString("description")?.take(300))
            }
        }

        /** Bundle paths: relative, `/`-separated, no empty, `.` or `..` segments, no backslashes or controls. */
        fun isSafePath(path: String): Boolean {
            if (path.isEmpty() || path.length > MAX_PATH || path.startsWith("/") || '\\' in path) return false
            if (path.any { it.code < 0x20 || it.code == 0x7F || it == ':' }) return false
            return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
        }

        private fun requireSafePath(path: String) = require(isSafePath(path)) { "unsafe entry path $path" }

        private fun JsonObject.optionalString(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

        private fun JsonObject.requireString(key: String, maxLength: Int): String {
            val value = optionalString(key)
            require(value != null && value.length <= maxLength) { "$key must be a non-empty string of at most $maxLength characters" }
            return value
        }
    }
}
