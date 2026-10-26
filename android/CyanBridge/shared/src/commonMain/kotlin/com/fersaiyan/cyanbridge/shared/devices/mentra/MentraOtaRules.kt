package com.fersaiyan.cyanbridge.shared.devices.mentra

/*
 * Mentra Live firmware update (OTA), platform-neutral part. The glasses own the install: the
 * phone checks the update manifest pinned to its Mentra SDK release, then sends `ota_start`
 * with that manifest; the glasses download every part over their own Wi-Fi and report
 * `ota_status` events while they install the glasses app (APK), the system software (MTK) and
 * the BES Bluetooth/audio firmware, restarting along the way.
 */

/** The Mentra Live glasses as the firmware update sees them. */
data class MentraOtaGlasses(
    val ready: Boolean = false,
    /** Null until the glasses report it. */
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    /** The network the glasses' Wi-Fi is joined to; null when it is not connected. */
    val wifiSsid: String? = null,
    /** An app feature streams the glasses microphone (Gemini Live, a voice question). */
    val microphoneInUse: Boolean = false,
    val versions: MentraGlassesVersions = MentraGlassesVersions(),
)

/** Software versions the glasses report; null when unknown. */
data class MentraGlassesVersions(
    val appVersion: String? = null,
    /** The glasses app (ASG client) version code. */
    val buildNumber: String? = null,
    val systemVersion: String? = null,
    val besVersion: String? = null,
)

/** What the update manifest pinned to the app's Mentra SDK release installs. */
data class MentraOtaManifest(
    /** The Mentra release the manifest belongs to (the SDK release identity). */
    val releaseVersion: String,
    val appVersionCode: Long? = null,
    val besVersion: String? = null,
    val includesSystemPatch: Boolean = false,
)

enum class MentraOtaComponent { APP, SYSTEM, BES }

/** An update the check found, and what it changes on these glasses. */
data class MentraOtaOffer(
    val manifest: MentraOtaManifest,
    val components: Set<MentraOtaComponent>,
    /** The glasses app on the glasses is newer than the manifest's: installing would go back. */
    val downgrade: Boolean,
)

/** Why an update cannot start right now. */
enum class MentraOtaBlocker {
    NOT_CONNECTED,
    BATTERY_LOW,
    WIFI_REQUIRED,
    MICROPHONE_IN_USE,
    GLASSES_BUSY,
    DOWNGRADE,

    /** A previous attempt ended on the phone side; the glasses may still be working. */
    COOLDOWN,
}

/** What a glasses-reported failure code means for the user. */
enum class MentraOtaErrorKind { NETWORK, STORAGE, INSTALL, BATTERY, UNKNOWN }

object MentraOtaRules {
    /** The official Mentra app refuses to start an update below this glasses battery level. */
    const val MIN_BATTERY_PERCENT = 25

    /** Glasses app builds below this use the legacy update protocol (the SDK picks its manifest). */
    const val LEGACY_PROTOCOL_BUILD = 39

    /**
     * Why an update cannot start. An unknown battery level does not block (the official app
     * and the glasses both fail open); the glasses refuse below 5% themselves.
     */
    fun blockers(
        glasses: MentraOtaGlasses,
        offer: MentraOtaOffer?,
        otherSessionActive: Boolean,
        cooldownUntilMs: Long,
        nowMs: Long,
    ): Set<MentraOtaBlocker> = buildSet {
        if (!glasses.ready) add(MentraOtaBlocker.NOT_CONNECTED)
        glasses.batteryPercent?.let { if (it < MIN_BATTERY_PERCENT) add(MentraOtaBlocker.BATTERY_LOW) }
        // The native SDK update is downloaded by the glasses over their own Wi-Fi.
        if (glasses.wifiSsid.isNullOrBlank()) add(MentraOtaBlocker.WIFI_REQUIRED)
        if (glasses.microphoneInUse) add(MentraOtaBlocker.MICROPHONE_IN_USE)
        if (otherSessionActive) add(MentraOtaBlocker.GLASSES_BUSY)
        if (offer?.downgrade == true) add(MentraOtaBlocker.DOWNGRADE)
        if (nowMs < cooldownUntilMs) add(MentraOtaBlocker.COOLDOWN)
    }

    /** Advice only: the official flow does not require charging. */
    fun chargingRecommended(glasses: MentraOtaGlasses): Boolean = glasses.ready && !glasses.charging

    /**
     * What [manifest] changes on glasses running [versions]. The app component compares
     * version codes; BES compares the reported version; the system patch is named only when
     * the SDK found an update that is neither of those (its applicability check is internal).
     */
    fun offer(manifest: MentraOtaManifest, versions: MentraGlassesVersions): MentraOtaOffer {
        val current = versions.buildNumber?.trim()?.toLongOrNull()
        val target = manifest.appVersionCode?.takeIf { it > 0 }
        val components = linkedSetOf<MentraOtaComponent>()
        if (target != null && (current == null || target > current)) components += MentraOtaComponent.APP
        val bes = manifest.besVersion?.trim().orEmpty()
        if (bes.isNotEmpty() && bes != versions.besVersion?.trim()) components += MentraOtaComponent.BES
        if (components.isEmpty() && manifest.includesSystemPatch) components += MentraOtaComponent.SYSTEM
        return MentraOtaOffer(
            manifest = manifest,
            components = components,
            downgrade = target != null && current != null && target < current,
        )
    }

    fun errorKind(code: String?): MentraOtaErrorKind = when (code?.trim()?.lowercase()) {
        null, "" -> MentraOtaErrorKind.UNKNOWN
        "battery_low" -> MentraOtaErrorKind.BATTERY
        "insufficient_storage" -> MentraOtaErrorKind.STORAGE
        "no_internet", "dns_failed", "connection_failed", "connect_timeout", "download_timeout",
        "http_error", "ssl_error", "clock_skew", "download_failed" -> MentraOtaErrorKind.NETWORK
        "install_failed", "apk_verify_failed", "firmware_verify_failed", "firmware_too_large",
        "apk_restart_guard_not_persisted" -> MentraOtaErrorKind.INSTALL
        else -> MentraOtaErrorKind.UNKNOWN
    }
}
