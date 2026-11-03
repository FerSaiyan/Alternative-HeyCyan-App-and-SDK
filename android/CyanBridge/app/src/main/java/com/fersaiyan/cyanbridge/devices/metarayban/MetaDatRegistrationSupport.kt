package com.fersaiyan.cyanbridge.devices.metarayban

import com.fersaiyan.cyanbridge.shared.glasses.META_BETA_ACCESS_URL
import com.fersaiyan.cyanbridge.shared.glasses.MetaRegistrationPhase

internal data class MetaDatReadiness(
    val metaAiInstalled: Boolean,
    val bluetoothPermissionGranted: Boolean,
    val bluetoothEnabled: Boolean,
    val bondedDeviceCount: Int?,
    /** Bonded devices advertising the Meta glasses service UUID: the check DAT uses for AVAILABLE. */
    val bondedMetaDeviceCount: Int?,
    val developerConfiguration: Boolean,
    /** The user picked "I use Developer Mode" on the setup screen (hybrid registration). */
    val developerModeChosen: Boolean = false,
) {
    /** Registration is expected to go through Developer Mode: a `"0"` app id, or the user's choice. */
    val developerPath: Boolean get() = developerConfiguration || developerModeChosen
}

enum class MetaAccessState {
    UNKNOWN,
    /** Not set up on this phone yet: Meta AI, Bluetooth, or glasses paired in Meta AI is missing. */
    NEEDS_SETUP,
    /** Meta AI turned down a registration outside Developer Mode: the account may need an invite. */
    NEEDS_META_INVITE,
    READY,
    FAILED,
}

/**
 * Before registration DAT 0.8 reports UNAVAILABLE or AVAILABLE from Bluetooth alone: AVAILABLE when
 * a bonded device advertises the Meta glasses service UUID ([MetaDatReadiness.bondedMetaDeviceCount]).
 * UNAVAILABLE says nothing about invites. Meta AI decides access only after Register, and DAT then
 * reports a registration error ([registrationRejected]). Camera, session and stream errors after
 * registration are not access failures.
 */
internal fun resolveMetaAccessState(
    initialized: Boolean,
    registrationState: MetaRaybanManager.RegistrationState,
    readiness: MetaDatReadiness,
    lastError: String?,
    registrationRejected: Boolean = false,
): MetaAccessState = when {
    !initialized -> if (lastError.isNullOrBlank()) MetaAccessState.UNKNOWN else MetaAccessState.FAILED
    registrationState == MetaRaybanManager.RegistrationState.REGISTERED ||
        registrationState == MetaRaybanManager.RegistrationState.REGISTERING ||
        registrationState == MetaRaybanManager.RegistrationState.UNREGISTERING -> MetaAccessState.READY
    registrationRejected && !readiness.developerPath -> MetaAccessState.NEEDS_META_INVITE
    !lastError.isNullOrBlank() -> MetaAccessState.FAILED
    registrationState == MetaRaybanManager.RegistrationState.AVAILABLE -> MetaAccessState.READY
    else -> MetaAccessState.NEEDS_SETUP
}

internal fun metaDatSetupGuidance(
    registrationState: MetaRaybanManager.RegistrationState,
    availableDeviceCount: Int,
    readiness: MetaDatReadiness,
    registrationRejected: Boolean = false,
): String? = when {
    registrationState == MetaRaybanManager.RegistrationState.REGISTERED && availableDeviceCount > 0 ->
        null
    registrationState == MetaRaybanManager.RegistrationState.REGISTERED && availableDeviceCount == 0 ->
        "You're registered for Meta access, but no Ray-Ban is paired in Meta AI. Pair your glasses in Meta AI first, keep them powered, unfolded, and connected there, then tap Refresh."
    registrationRejected && readiness.developerPath ->
        "Meta AI did not register CyanBridge. Check that Developer Mode is on in Meta AI (Settings, App info, tap the app version 5 times), keep the glasses connected there, then tap Register again."
    registrationRejected ->
        "Meta AI did not register CyanBridge. If your Meta account is not invited yet, request access at $META_BETA_ACCESS_URL and accept Meta's invite email. If you use Developer Mode instead, choose \"I use Developer Mode\". Then tap Register again."
    registrationState == MetaRaybanManager.RegistrationState.AVAILABLE ->
        "Meta glasses detected. Tap Register to authorize CyanBridge in Meta AI."
    !readiness.metaAiInstalled ->
        "Install or update Meta AI, pair the glasses there, then return to CyanBridge."
    !readiness.bluetoothPermissionGranted ->
        "Grant Nearby devices/Bluetooth permission so DAT can access the Meta wearable connection."
    !readiness.bluetoothEnabled ->
        "Turn on Bluetooth, open Meta AI, and confirm the glasses are connected there."
    registrationState == MetaRaybanManager.RegistrationState.UNAVAILABLE &&
        readiness.bondedMetaDeviceCount == 0 ->
        "No Meta glasses are paired with this phone. Pair your glasses in Meta AI, keep them powered on and nearby, then return to CyanBridge. Don't pair from CyanBridge's Bluetooth list."
    registrationState == MetaRaybanManager.RegistrationState.UNAVAILABLE ->
        "Meta DAT does not see the paired glasses yet. Open Meta AI, confirm the glasses are connected there, then return to CyanBridge."
    else -> null
}

/** DAT registration states as the shared hybrid-registration logic sees them. */
internal fun MetaRaybanManager.RegistrationState.toMetaRegistrationPhase(): MetaRegistrationPhase = when (this) {
    MetaRaybanManager.RegistrationState.UNAVAILABLE, MetaRaybanManager.RegistrationState.AVAILABLE ->
        MetaRegistrationPhase.NOT_REGISTERED
    MetaRaybanManager.RegistrationState.REGISTERING -> MetaRegistrationPhase.REGISTERING
    MetaRaybanManager.RegistrationState.REGISTERED -> MetaRegistrationPhase.REGISTERED
    MetaRaybanManager.RegistrationState.UNREGISTERING -> MetaRegistrationPhase.UNREGISTERING
}
