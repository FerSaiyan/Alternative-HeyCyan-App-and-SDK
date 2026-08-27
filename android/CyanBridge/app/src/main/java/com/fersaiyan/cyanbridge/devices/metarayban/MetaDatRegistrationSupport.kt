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
        "Your Meta account is not currently enabled for CyanBridge Meta access. Request access at https://cyanbridge.vercel.app/beta, then restart CyanBridge after accepting Meta's invitation."
    registrationState == MetaRaybanManager.RegistrationState.UNAVAILABLE &&
        readiness.bondedMetaDeviceCount == 0 ->
        "DAT cannot see a linked Meta wearable yet. Confirm the glasses are paired and connected inside Meta AI, then return to CyanBridge. Do not pair them from CyanBridge's Bluetooth device list."
    registrationState == MetaRaybanManager.RegistrationState.UNAVAILABLE ->
        "Meta DAT registration is unavailable. Confirm the glasses are linked and connected in Meta AI, then retry registration."
    registrationState == MetaRaybanManager.RegistrationState.AVAILABLE ->
        "Meta glasses detected. Tap Register to authorize CyanBridge in Meta AI."
    registrationState == MetaRaybanManager.RegistrationState.REGISTERED && availableDeviceCount == 0 ->
        "Registration is complete, but DAT has not exposed a device yet. Keep the glasses powered, unfolded and connected in Meta AI; if this persists, send Meta diagnostics."
    else -> null
}
