package com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A glasses protocol a Web plugin needs (`deviceRequirements.protocols`). */
data class MmpkgProtocolRequirement(val id: String, val minVersion: String)

/** The glasses plugin a Web plugin pairs with; only MemoMind's official app can start it. */
data class MmpkgDeviceRequirements(
    val preferredPluginId: String?,
    val requiredPluginId: String?,
    val minPluginVersion: String?,
    val protocols: List<MmpkgProtocolRequirement>,
) {
    /** True when the stock Web Bridge glasses plugin satisfies the requirements. */
    val webBridgeCompatible: Boolean
        get() = (requiredPluginId == null || requiredPluginId == WEB_BRIDGE_PLUGIN_ID) &&
            protocols.all { it.id in WEB_BRIDGE_PROTOCOLS }

    companion object {
        const val WEB_BRIDGE_PLUGIN_ID = "com.gm.example.web-bridge"
        val WEB_BRIDGE_PROTOCOLS = setOf("gm.scene", "gm.scene-lz4", "gm.scene-atomic-frame", "gm.device-events")
    }
}

/** A validated final `manifest.json` (schemaVersion 2). */
data class MmpkgManifest(
    val id: String,
    val name: String,
    val version: String,
    val entry: String,
    val permissions: List<WebPluginPermission>,
    val deviceRequirements: MmpkgDeviceRequirements?,
    /** Payload path to lowercase SHA-256 hex. */
    val files: Map<String, String>,
) {
    fun declares(permission: String) = permissions.any { it.name == permission }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val PLUGIN_ID = Regex("^[a-zA-Z][a-zA-Z0-9_-]*(\\.[a-zA-Z0-9_-]+)+$")
        private val SEMVER = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")
        private val PROTOCOL_ID = Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$")
        private val NUMERIC_VERSION = Regex("^\\d+(?:\\.\\d+){0,3}$")
        private val HASH = Regex("^sha256:([0-9a-f]{64})$")

        /** Parses and validates manifest text; throws [MmpkgException] with the reason. */
        fun parse(text: String): MmpkgManifest {
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: throw MmpkgException("manifest.json is not a JSON object")
            return try {
                parse(root)
            } catch (error: IllegalArgumentException) {
                throw MmpkgException("Invalid manifest: ${error.message}")
            }
        }

        private fun parse(root: JsonObject): MmpkgManifest {
            require(root.int("schemaVersion") == WebPluginContract.SCHEMA_VERSION) { "schemaVersion must be 2 (rebuild the package with the current PhoneSDK)" }
            require(root.string("bridgeVersion") == WebPluginContract.BRIDGE_VERSION) { "bridgeVersion must be \"2.0\"" }
            require(root.int("permissionPolicyVersion") == WebPluginContract.PERMISSION_POLICY_VERSION) { "permissionPolicyVersion must be 1" }
            val id = root.requireString("id", 128)
            require(PLUGIN_ID.matches(id)) { "id must be a reverse-domain identifier" }
            val name = root.requireString("name", 80)
            val version = root.requireString("version", 64)
            require(SEMVER.matches(version)) { "version must be a semantic version" }
            val entry = root.requireString("entry", 256)
            require(MmpkgZip.isSafePath(entry)) { "entry is not a safe package path" }
            require(entry.lowercase().endsWith(".html")) { "entry must be an HTML file" }
            val permissions = WebPluginPolicy.normalize(root["permissions"])
            val filesObject = root["files"] as? JsonObject ?: throw IllegalArgumentException("files table is missing")
            val files = filesObject.mapValues { (path, value) ->
                require(MmpkgZip.isSafePath(path) && path != MANIFEST && path != SIGNATURE) { "unsafe files entry $path" }
                val hash = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(HASH::matchEntire)
                    ?: throw IllegalArgumentException("files.$path must be sha256:<hex>")
                hash.groupValues[1]
            }
            require(entry in files) { "entry $entry is not in the package" }
            return MmpkgManifest(id, name, version, entry, permissions, deviceRequirements(root["deviceRequirements"]), files)
        }

        private fun deviceRequirements(value: JsonElement?): MmpkgDeviceRequirements? {
            if (value == null) return null
            val obj = value as? JsonObject ?: throw IllegalArgumentException("deviceRequirements must be an object")
            fun pluginId(key: String) = obj[key]?.let {
                obj.requireString(key, 128).also { id -> require(PLUGIN_ID.matches(id)) { "invalid $key" } }
            }
            val minPluginVersion = obj["minPluginVersion"]?.let {
                obj.requireString("minPluginVersion", 32).also { v -> require(NUMERIC_VERSION.matches(v)) { "invalid minPluginVersion" } }
            }
            val protocols = obj["protocols"] as? JsonArray
            require(protocols != null && protocols.size <= 16) { "deviceRequirements.protocols must hold at most 16 entries" }
            val parsed = protocols.map { element ->
                val protocol = element as? JsonObject ?: throw IllegalArgumentException("a protocol must be an object")
                val id = protocol.requireString("id", 80)
                val minVersion = protocol.requireString("minVersion", 32)
                require(PROTOCOL_ID.matches(id) && NUMERIC_VERSION.matches(minVersion)) { "invalid protocol $id" }
                MmpkgProtocolRequirement(id, minVersion)
            }
            require(parsed.map { it.id }.toSet().size == parsed.size) { "duplicate device protocol" }
            return MmpkgDeviceRequirements(pluginId("preferredPluginId"), pluginId("requiredPluginId"), minPluginVersion, parsed)
        }

        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.requireString(key: String, maxLength: Int): String {
            val value = string(key)
            require(value != null && value.isNotBlank() && value.length <= maxLength) { "$key must be a non-empty string of at most $maxLength characters" }
            return value
        }

        const val MANIFEST = "manifest.json"
        const val SIGNATURE = "signature.sig"
    }
}

/** A verified package: its manifest, the raw manifest bytes, and every payload file. */
class MmpkgPackage(
    val manifest: MmpkgManifest,
    val manifestBytes: ByteArray,
    val files: Map<String, ByteArray>,
) {
    val payloadBytes: Long get() = files.values.sumOf { it.size.toLong() }

    companion object {
        /**
         * Reads and verifies an `.mmpkg`: container and size limits, manifest, and that `files`
         * covers every payload file exactly with matching SHA-256. `signature.sig` is ignored
         * (no release keys are published, so packages are treated as unsigned sideloads).
         */
        fun read(archive: ByteArray, inflater: RawInflater): MmpkgPackage {
            val entries = MmpkgZip.read(archive, inflater)
            val manifestBytes = entries.remove(MmpkgManifest.MANIFEST) ?: throw MmpkgException("manifest.json is missing")
            entries.remove(MmpkgManifest.SIGNATURE)
            val manifest = MmpkgManifest.parse(manifestBytes.decodeToString())
            val missing = manifest.files.keys - entries.keys
            if (missing.isNotEmpty()) throw MmpkgException("Files listed in the manifest are missing: ${missing.sorted().take(3)}")
            val unlisted = entries.keys - manifest.files.keys
            if (unlisted.isNotEmpty()) throw MmpkgException("Files not listed in the manifest: ${unlisted.sorted().take(3)}")
            entries.forEach { (path, bytes) ->
                if (Sha256.hex(bytes) != manifest.files.getValue(path)) throw MmpkgException("Hash mismatch for $path")
            }
            return MmpkgPackage(manifest, manifestBytes, entries)
        }
    }
}
