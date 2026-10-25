package com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin

import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.WebPluginContract.Permission
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * One manifest permission. [scope] holds `device.events` types or `device.messaging` channels
 * (as decimal strings), sorted; it is null for unscoped permissions.
 */
data class WebPluginPermission(
    val name: String,
    val required: Boolean,
    val reason: String? = null,
    val scope: List<String>? = null,
) {
    val channels: List<Int> get() = scope?.mapNotNull(String::toIntOrNull).orEmpty()
}

/**
 * What the user granted: permission name to the granted scope (a subset of the declared one),
 * or an empty list for unscoped permissions. A missing key is not granted.
 */
data class WebPluginApprovals(val granted: Map<String, List<String>> = emptyMap()) {
    fun has(name: String) = name in granted
    fun scope(name: String): List<String> = granted[name].orEmpty()

    fun toJson(): JsonObject = buildJsonObject {
        granted.forEach { (name, scope) -> put(name, buildJsonArray { scope.forEach { add(JsonPrimitive(it)) } }) }
    }

    companion object {
        /** Reads stored approvals, keeping only what [declarations] still declare. */
        fun fromJson(json: JsonElement?, declarations: List<WebPluginPermission>): WebPluginApprovals {
            val obj = json as? JsonObject ?: return WebPluginApprovals()
            val granted = declarations.mapNotNull<WebPluginPermission, Pair<String, List<String>>> { declaration ->
                val stored = obj[declaration.name] as? JsonArray ?: return@mapNotNull null
                val values = stored.mapNotNull { (it as? JsonPrimitive)?.content }
                declaration.name to (declaration.scope?.filter { it in values } ?: emptyList())
            }.toMap()
            return WebPluginApprovals(granted)
        }

        /** Grants every declared permission with its full scope. */
        fun all(declarations: List<WebPluginPermission>) =
            WebPluginApprovals(declarations.associate { it.name to (it.scope ?: emptyList()) })
    }
}

/** The permission policy of the contract (`permission-policy.js`), ported. */
object WebPluginPolicy {
    private const val MAX_REASON_LENGTH = 200
    private const val MAX_CHANNELS = 64

    /** Validates and normalizes a manifest `permissions` array; throws [IllegalArgumentException]. */
    fun normalize(input: JsonElement?): List<WebPluginPermission> {
        val array = input as? JsonArray ?: throw IllegalArgumentException("permissions must be an array")
        require(array.size <= WebPluginContract.PERMISSIONS.size) { "too many permissions" }
        val seen = mutableSetOf<String>()
        return array.map { element ->
            val entry = element as? JsonObject ?: throw IllegalArgumentException("a permission must be an object")
            require(entry.keys.all { it in setOf("name", "required", "reason", "scope") }) { "unknown permission field" }
            val name = (entry["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(name != null && name in WebPluginContract.PERMISSIONS) { "unknown permission $name" }
            require(seen.add(name)) { "duplicate permission $name" }
            val required = (entry["required"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
            require(required == "true" || required == "false") { "$name.required must be a boolean" }
            val reason = entry["reason"]?.let { value ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                require(text != null && text.isNotBlank() && text.codePointCount() <= MAX_REASON_LENGTH) { "$name.reason is invalid" }
                text
            }
            WebPluginPermission(name, required == "true", reason, scopeOf(name, entry["scope"]))
        }.sortedBy { it.name }
    }

    private fun scopeOf(name: String, scope: JsonElement?): List<String>? {
        val key = scopeKey(name)
        if (key == null) {
            require(scope == null) { "$name takes no scope" }
            return null
        }
        val obj = scope as? JsonObject
        require(obj != null && obj.size == 1) { "$name needs scope.$key" }
        val values = obj[key] as? JsonArray
        require(values != null && values.isNotEmpty()) { "$name.scope.$key must be a non-empty array" }
        return if (key == "types") {
            val types = values.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            require(types.all { it in WebPluginContract.EVENT_TYPES } && types.toSet().size == types.size) { "invalid event types" }
            types.filterNotNull().sorted()
        } else {
            require(values.size <= MAX_CHANNELS) { "too many channels" }
            val channels = values.map { value ->
                (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 0..0xFFFF }
                    ?: throw IllegalArgumentException("channels must be integers 0..65535")
            }
            require(channels.toSet().size == channels.size) { "duplicate channels" }
            channels.sorted().map(Int::toString)
        }
    }

    private fun scopeKey(name: String) = when (name) {
        Permission.DEVICE_EVENTS -> "types"
        Permission.DEVICE_MESSAGING -> "channels"
        else -> null
    }

    /** True when every required permission is granted with its full declared scope (the launch condition). */
    fun canStart(declarations: List<WebPluginPermission>, approvals: WebPluginApprovals): Boolean =
        declarations.filter { it.required }.all { declaration ->
            approvals.has(declaration.name) && declaration.scope.orEmpty().all { it in approvals.scope(declaration.name) }
        }

    /**
     * Authorizes [method] like the contract's `authorize`: undeclared or ungranted permissions,
     * missing required grants, and event types or channels outside the granted scope are
     * PERMISSION_DENIED. [eventTypes] / [channel] are the already validated call arguments.
     */
    fun authorize(
        method: String,
        declarations: List<WebPluginPermission>,
        approvals: WebPluginApprovals,
        eventTypes: List<String>? = null,
        channel: Int? = null,
    ) {
        if (method !in WebPluginContract.METHOD_PERMISSIONS) bridgeError(WebPluginErrorCode.METHOD_NOT_FOUND, "METHOD_NOT_FOUND")
        fun requirePermission(name: String) {
            if (declarations.none { it.name == name }) bridgeError(WebPluginErrorCode.PERMISSION_DENIED, "UNDECLARED")
            if (!approvals.has(name)) bridgeError(WebPluginErrorCode.PERMISSION_DENIED, "NOT_GRANTED")
        }
        fun outOfScope(): Nothing = bridgeError(WebPluginErrorCode.PERMISSION_DENIED, "OUT_OF_SCOPE")

        WebPluginContract.METHOD_PERMISSIONS[method]?.let(::requirePermission)
        if (!canStart(declarations, approvals)) {
            declarations.filter { it.required }.forEach { requirePermission(it.name) }
            outOfScope()
        }
        if (eventTypes != null && eventTypes.any { it !in approvals.scope(Permission.DEVICE_EVENTS) }) outOfScope()
        if (channel != null) {
            if (channel.toString() !in approvals.scope(Permission.DEVICE_MESSAGING)) outOfScope()
            if (channel in WebPluginContract.DISPLAY_CHANNELS) requirePermission(Permission.DISPLAY)
            if (channel in 0x0100..0x0103) {
                requirePermission(Permission.DEVICE_EVENTS)
                if (WebPluginContract.EVENT_TYPES[channel - 0x0100] !in approvals.scope(Permission.DEVICE_EVENTS)) outOfScope()
            }
        }
    }

    /** Whether a plugin message on [channel] may be sent or delivered under [approvals]. */
    fun channelAllowed(channel: Int, declarations: List<WebPluginPermission>, approvals: WebPluginApprovals): Boolean =
        runCatching { authorize("plugin.sendMessage", declarations, approvals, channel = channel) }.isSuccess

    /** Registered methods whose permission is granted (the contract's `grantedMethods`). */
    fun grantedMethods(approvals: WebPluginApprovals): List<String> =
        WebPluginContract.METHOD_PERMISSIONS.filter { (_, permission) -> permission == null || approvals.has(permission) }.keys.toList()

    private fun String.codePointCount(): Int {
        var count = 0
        var index = 0
        while (index < length) {
            index += if (this[index].isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) 2 else 1
            count++
        }
        return count
    }
}
