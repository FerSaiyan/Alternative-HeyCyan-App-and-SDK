package com.fersaiyan.cyanbridge.shared.plugins.mentra

import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraSdkModel

/** What the user allowed one miniapp. [decided] are the declared permissions they were asked about. */
data class MiniappApprovals(
    val granted: Set<MiniappPermissionType> = emptySet(),
    val decided: Set<MiniappPermissionType> = emptySet(),
) {
    fun has(type: MiniappPermissionType) = type in granted

    companion object {
        fun all(manifest: MiniappManifest) = manifest.permissions.map { it.type }.toSet().let { MiniappApprovals(it, it) }
    }
}

/** Why a call is refused, as the SDK's error codes. */
enum class MiniappGate { ALLOWED, NOT_DECLARED, DENIED }

/**
 * The permission policy of the Mentra host (`LocalMiniappRuntime`): a miniapp may use a gated
 * feature only when its manifest declares the permission, and CyanBridge also requires the user's
 * consent for it (the Mentra App asks for every required permission before opening a miniapp).
 */
object MiniappPermissionPolicy {
    /** The miniapp may start once the user decided on every declared permission and granted the required ones. */
    fun canStart(manifest: MiniappManifest, approvals: MiniappApprovals): Boolean =
        manifest.permissions.all { it.type in approvals.decided } &&
            manifest.permissions.filter { it.required }.all { approvals.has(it.type) }

    fun needsConsent(manifest: MiniappManifest, approvals: MiniappApprovals): Boolean = !canStart(manifest, approvals)

    /** Permissions that satisfy [type]'s gate (LOCATION accepts BACKGROUND_LOCATION; notifications accept either kind). */
    private fun satisfying(type: MiniappPermissionType): Set<MiniappPermissionType> = when (type) {
        MiniappPermissionType.LOCATION -> setOf(MiniappPermissionType.LOCATION, MiniappPermissionType.BACKGROUND_LOCATION)
        else -> setOf(type)
    }

    fun gate(manifest: MiniappManifest, approvals: MiniappApprovals, type: MiniappPermissionType): MiniappGate {
        val accepted = satisfying(type)
        if (manifest.permissions.none { it.type in accepted }) return MiniappGate.NOT_DECLARED
        return if (accepted.any(approvals::has)) MiniappGate.ALLOWED else MiniappGate.DENIED
    }

    /** The permission a subscription stream needs (`LocalMiniappRuntime.ts:2069-2091`), or null. */
    fun permissionForStream(stream: String): MiniappPermissionType? {
        val base = stream.substringBefore(':')
        return when (base) {
            "transcription", "translation", "audio_chunk", "vad" -> MiniappPermissionType.MICROPHONE
            "location_update", "location_stream", "heading_update", "navigation_update", "navigation_route" -> MiniappPermissionType.LOCATION
            "phone_notification", "phone_notification_dismissed" -> MiniappPermissionType.READ_NOTIFICATIONS
            "photo_taken", "stream_status" -> MiniappPermissionType.CAMERA
            else -> null
        }
    }

    /** The permission a request type needs, or null. */
    fun permissionForRequest(type: String): MiniappPermissionType? = when {
        type.startsWith("miniapp_mic_") -> MiniappPermissionType.MICROPHONE
        type == "miniapp_photo" || type.startsWith("miniapp_camera_") || type.startsWith("miniapp_video_recording_") ||
            type.startsWith("miniapp_stream_preview_") -> MiniappPermissionType.CAMERA
        type == "miniapp_location_poll" || type.startsWith("miniapp_navigation_") -> MiniappPermissionType.LOCATION
        type == "miniapp_calendar_list_events" -> MiniappPermissionType.CALENDAR
        else -> null
    }

    /** The CONNECT_ACK `permissions` record: what the manifest declares, by the SDK's lowercase names. */
    fun permissionRecord(manifest: MiniappManifest): Map<String, Boolean> {
        val declared = manifest.permissions.map { it.type }.toSet()
        return linkedMapOf(
            "location" to (MiniappPermissionType.LOCATION in declared || MiniappPermissionType.BACKGROUND_LOCATION in declared),
            "microphone" to (MiniappPermissionType.MICROPHONE in declared),
            "camera" to (MiniappPermissionType.CAMERA in declared),
            "notifications" to (MiniappPermissionType.READ_NOTIFICATIONS in declared || MiniappPermissionType.POST_NOTIFICATIONS in declared),
            "calendar" to (MiniappPermissionType.CALENDAR in declared),
        )
    }
}

/** What the selected glasses offer a miniapp, derived from the family profile. */
data class MiniappHardwareSupport(
    val modelName: String,
    val display: Boolean,
    val microphone: Boolean,
    val speaker: Boolean,
    val camera: Boolean,
    val button: Boolean,
    val imu: Boolean,
) {
    fun has(hardware: MiniappHardware): Boolean = when (hardware) {
        MiniappHardware.DISPLAY -> display
        // The phone microphone and speaker stand in, as in the Mentra App.
        MiniappHardware.MICROPHONE -> true
        MiniappHardware.SPEAKER -> true
        MiniappHardware.CAMERA -> camera
        MiniappHardware.BUTTON -> button
        MiniappHardware.IMU -> imu
        MiniappHardware.LIGHT, MiniappHardware.WIFI -> false
    }

    /** REQUIRED hardware the glasses lack; empty when the miniapp may run. */
    fun missing(manifest: MiniappManifest): List<MiniappHardware> =
        manifest.hardware.filter { it.required && !has(it.type) }.map { it.type }

    companion object {
        fun of(profile: GlassesFamilyProfile): MiniappHardwareSupport = MiniappHardwareSupport(
            modelName = MentraSdkModel.forClass(profile.deviceClass)?.sdkDeviceType ?: profile.deviceClass.displayName(),
            display = profile.supports(GlassesCapability.TEXT_DISPLAY),
            microphone = profile.supports(GlassesCapability.MICROPHONE_AUDIO),
            speaker = profile.supports(GlassesCapability.SPEAKER_AUDIO),
            camera = profile.supports(GlassesCapability.PHOTO_CAPTURE),
            button = profile.supports(GlassesCapability.BUTTON_INPUT) || profile.supports(GlassesCapability.TOUCH_INPUT),
            imu = profile.supports(GlassesCapability.HEAD_GESTURE_INPUT),
        )
    }
}
