package com.fersaiyan.cyanbridge.shared.devices.mentra

/*
 * Mentra Live streaming: the SDK's `startStream` makes the glasses publish their camera over
 * their own Wi-Fi to a URL they can reach (RTMP, SRT or WHIP; the glasses pick the protocol from
 * the URL scheme). CyanBridge uses it two ways:
 *
 * - Live preview: the phone runs a small RTMP ingest on the glasses' network (their hotspot, or
 *   the same Wi-Fi when both are on it) and plays the video locally.
 * - Broadcast: the glasses publish straight to the user's own RTMP/SRT server over Wi-Fi with
 *   internet. The phone only starts, watches and stops the stream.
 *
 * This file holds the platform-free parts: URLs, route choice and status mapping.
 */

enum class MentraStreamProtocol(val scheme: String) {
    RTMP("rtmp"),
    RTMPS("rtmps"),
    SRT("srt"),
}

/** Why a broadcast URL was refused. The screen maps each to a message. */
enum class MentraBroadcastUrlProblem {
    EMPTY,
    UNSUPPORTED_SCHEME,
    MISSING_HOST,
    LOCAL_HOST,
    MISSING_STREAM_PATH,
    MISSING_PORT,
}

sealed interface MentraBroadcastUrlCheck {
    data class Valid(val url: String, val protocol: MentraStreamProtocol) : MentraBroadcastUrlCheck

    data class Invalid(val problem: MentraBroadcastUrlProblem) : MentraBroadcastUrlCheck
}

object MentraStreamUrls {
    /** The path the phone's preview ingest accepts; the key is a per-session secret. */
    const val PREVIEW_APP = "live"

    /** RTMP publish URL for the phone's preview ingest. StreamPack needs `/app/streamKey`. */
    fun previewPublishUrl(phoneIpv4: String, port: Int, streamKey: String): String =
        "rtmp://$phoneIpv4:$port/$PREVIEW_APP/$streamKey"

    /**
     * Checks a user-entered broadcast URL: `rtmp://`, `rtmps://` (with an app and stream key
     * path) or `srt://` (with a port). Loopback hosts are refused: the glasses would stream to
     * themselves.
     */
    fun checkBroadcastUrl(raw: String): MentraBroadcastUrlCheck {
        val url = raw.trim()
        if (url.isEmpty()) return MentraBroadcastUrlCheck.Invalid(MentraBroadcastUrlProblem.EMPTY)
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        val protocol = MentraStreamProtocol.entries.firstOrNull { it.scheme == scheme }
            ?: return MentraBroadcastUrlCheck.Invalid(MentraBroadcastUrlProblem.UNSUPPORTED_SCHEME)
        val rest = url.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringAfterLast('@')
        val host = hostOf(authority)
        if (host.isEmpty()) return MentraBroadcastUrlCheck.Invalid(MentraBroadcastUrlProblem.MISSING_HOST)
        if (isLoopback(host)) return MentraBroadcastUrlCheck.Invalid(MentraBroadcastUrlProblem.LOCAL_HOST)
        when (protocol) {
            MentraStreamProtocol.RTMP, MentraStreamProtocol.RTMPS -> {
                val path = rest.substringAfter('/', missingDelimiterValue = "").substringBefore('?')
                val segments = path.split('/').filter { it.isNotEmpty() }
                if (segments.size < 2) return MentraBroadcastUrlCheck.Invalid(MentraBroadcastUrlProblem.MISSING_STREAM_PATH)
            }
            MentraStreamProtocol.SRT -> {
                if (portOf(authority) == null) return MentraBroadcastUrlCheck.Invalid(MentraBroadcastUrlProblem.MISSING_PORT)
            }
        }
        return MentraBroadcastUrlCheck.Valid(url, protocol)
    }

    /**
     * The URL for logs and the screen: the RTMP stream key (last path segment) and SRT
     * `streamid`/`passphrase` values are masked, user info is dropped.
     */
    fun redacted(url: String): String {
        val scheme = url.substringBefore("://", missingDelimiterValue = "")
        if (scheme.isEmpty()) return MASK
        val rest = url.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?')
        val host = authority.substringAfterLast('@')
        val path = rest.removePrefix(authority).substringBefore('?')
        val query = rest.substringAfter('?', missingDelimiterValue = "")
        val maskedPath = if (path.count { it == '/' } >= 2) path.substringBeforeLast('/') + "/" + MASK else path
        val maskedQuery = query.split('&').filter { it.isNotEmpty() }.joinToString("&") { pair ->
            val name = pair.substringBefore('=')
            if (name.lowercase() in SECRET_QUERY_KEYS) "$name=$MASK" else pair
        }
        return "$scheme://$host$maskedPath" + if (maskedQuery.isNotEmpty()) "?$maskedQuery" else ""
    }

    private fun hostOf(authority: String): String =
        if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']') else authority.substringBefore(':')

    private fun portOf(authority: String): Int? {
        val afterHost = if (authority.startsWith("[")) authority.substringAfter(']', "") else authority
        return afterHost.substringAfter(':', missingDelimiterValue = "").toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private fun isLoopback(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "localhost" || lower == "::1" || lower == "0.0.0.0" || lower.startsWith("127.")
    }

    private const val MASK = "****"
    private val SECRET_QUERY_KEYS = setOf("streamid", "passphrase", "key", "token", "pkt_key")
}

/** IPv4 helpers for choosing how the glasses reach the phone. */
object MentraStreamRoute {
    fun parseIpv4(text: String): Long? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255 || part.length > 3) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    /** True when [a] and [b] are in the same IPv4 subnet of [prefixLength] bits. */
    fun sameSubnet(a: String, b: String, prefixLength: Int): Boolean {
        if (prefixLength !in 1..32) return false
        val first = parseIpv4(a) ?: return false
        val second = parseIpv4(b) ?: return false
        val mask = (0xFFFF_FFFFL shl (32 - prefixLength)) and 0xFFFF_FFFFL
        return (first and mask) == (second and mask)
    }
}

/** The broadcast's phase as the screen shows it, derived from the SDK's `stream_status` states. */
enum class MentraBroadcastPhase {
    IDLE,
    STARTING,
    LIVE,
    RECONNECTING,
    STOPPING,
    STOPPED,
    FAILED;

    val isActive: Boolean get() = this == STARTING || this == LIVE || this == RECONNECTING || this == STOPPING

    companion object {
        /**
         * Maps a `stream_status` state (`initializing`, `streaming`, `reconnecting`,
         * `reconnected`, `reconnect_failed`, `stopping`, `stopped`, `error`). An error the glasses
         * will retry ([willRetry]) is shown as reconnecting.
         */
        fun fromStreamState(state: String?, willRetry: Boolean? = null): MentraBroadcastPhase? =
            when (state?.lowercase()) {
                "initializing" -> STARTING
                "streaming", "reconnected" -> LIVE
                "reconnecting" -> RECONNECTING
                "stopping" -> STOPPING
                "stopped" -> STOPPED
                "reconnect_failed" -> FAILED
                "error" -> if (willRetry == true) RECONNECTING else FAILED
                else -> null
            }
    }
}
