package com.fersaiyan.cyanbridge.devices.metarayban

internal data class MetaDatReadiness(
    val metaAiInstalled: Boolean,
    val bluetoothPermissionGranted: Boolean,
    val bluetoothEnabled: Boolean,
    val bondedDeviceCount: Int?,
    val bondedMetaDeviceCount: Int?,
    val developerConfiguration: Boolean,
)

enum class MetaAccessState {
    UNKNOWN,
    NEEDS_META_INVITE,
    READY,
    FAILED,
}

internal fun resolveMetaAccessState(
    initialized: Boolean,
    registrationState: MetaRaybanManager.RegistrationState,
    availableDeviceCount: Int,
    readiness: MetaDatReadiness,
    lastError: String?,
): MetaAccessState = when {
    !lastError.isNullOrBlank() -> MetaAccessState.FAILED
    !initialized -> MetaAccessState.UNKNOWN
    registrationState != MetaRaybanManager.RegistrationState.UNAVAILABLE -> MetaAccessState.READY
    !readiness.developerConfiguration &&
        availableDeviceCount == 0 &&
        readiness.metaAiInstalled &&
        readiness.bluetoothPermissionGranted &&
        readiness.bluetoothEnabled -> MetaAccessState.NEEDS_META_INVITE
    else -> MetaAccessState.FAILED
}

internal fun metaDatSetupGuidance(
    registrationState: MetaRaybanManager.RegistrationState,
    availableDeviceCount: Int,
    readiness: MetaDatReadiness,
): String? = when {
    // Invited + registered — show success even if Meta AI temporarily not detected (mock or background)
    registrationState == MetaRaybanManager.RegistrationState.REGISTERED && availableDeviceCount > 0 ->
        null
    registrationState == MetaRaybanManager.RegistrationState.REGISTERED && availableDeviceCount == 0 ->
        "You're registered for Meta access, but no Ray-Ban is paired in Meta AI. Pair your glasses in Meta AI first, keep them powered, unfolded, and connected there, then tap Refresh."
    registrationState == MetaRaybanManager.RegistrationState.AVAILABLE ->
        "Meta glasses detected. Tap Register to authorize CyanBridge in Meta AI."
    !readiness.metaAiInstalled ->
        "Install or update Meta AI, pair the glasses there, then return to CyanBridge."
    !readiness.bluetoothPermissionGranted ->
        "Grant Nearby devices/Bluetooth permission so DAT can access the Meta wearable connection."
    !readiness.bluetoothEnabled ->
        "Turn on Bluetooth, open Meta AI, and confirm the glasses are connected there."
    registrationState == MetaRaybanManager.RegistrationState.UNAVAILABLE &&
        readiness.developerConfiguration ->
        "Open Meta AI, confirm the glasses are linked to this account and Developer Mode is enabled for this device, then tap Register again."
    registrationState == MetaRaybanManager.RegistrationState.UNAVAILABLE &&
        !readiness.developerConfiguration ->
        "Your Meta account is not currently enabled for CyanBridge Meta access. Request access at https://cyanbridge.vercel.app/beta. If you're already invited, pair your glasses in Meta AI and tap Register."
    // No bonded Meta wearable at all — actionable after release-channel hints (kept for completeness)
    registrationState == MetaRaybanManager.RegistrationState.UNAVAILABLE &&
        readiness.bondedMetaDeviceCount == 0 ->
        "No Ray-Ban found in Meta AI. Pair your glasses in Meta AI first, then return to CyanBridge. Don't pair from CyanBridge's Bluetooth list."
    registrationState == MetaRaybanManager.RegistrationState.UNAVAILABLE ->
        "Meta DAT registration is unavailable. Confirm the glasses are linked and connected in Meta AI, then retry registration."
    else -> null
}
