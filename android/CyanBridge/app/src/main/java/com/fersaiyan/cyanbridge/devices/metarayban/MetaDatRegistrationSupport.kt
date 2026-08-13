package com.fersaiyan.cyanbridge.devices.metarayban

internal data class MetaDatReadiness(
    val metaAiInstalled: Boolean,
    val bluetoothPermissionGranted: Boolean,
    val bluetoothEnabled: Boolean,
    val bondedDeviceCount: Int?,
    val bondedMetaDeviceCount: Int?,
    val developerConfiguration: Boolean,
)

internal fun metaDatSetupGuidance(
    registrationState: MetaRaybanManager.RegistrationState,
    availableDeviceCount: Int,
    readiness: MetaDatReadiness,
): String? = when {
    !readiness.metaAiInstalled ->
        "Install or update Meta AI, pair the glasses there, then return to CyanBridge."
    !readiness.bluetoothPermissionGranted ->
        "Grant Nearby devices/Bluetooth permission so DAT can find paired Meta glasses."
    !readiness.bluetoothEnabled ->
        "Turn on Bluetooth, open Meta AI, and confirm the glasses are connected."
    registrationState == MetaRaybanManager.RegistrationState.UNAVAILABLE &&
        readiness.bondedMetaDeviceCount == 0 ->
        "DAT cannot see a paired Meta wearable. Pair or re-pair supported glasses in Meta AI, then tap Register."
    registrationState == MetaRaybanManager.RegistrationState.UNAVAILABLE &&
        readiness.developerConfiguration ->
        "Enable Developer Mode for the glasses in Meta AI, then tap Register."
    registrationState == MetaRaybanManager.RegistrationState.UNAVAILABLE ->
        "This account and app build must be eligible for the CyanBridge release channel in Wearables Developer Center."
    registrationState == MetaRaybanManager.RegistrationState.AVAILABLE ->
        "Meta glasses detected. Tap Register to authorize CyanBridge in Meta AI."
    registrationState == MetaRaybanManager.RegistrationState.REGISTERED && availableDeviceCount == 0 ->
        "Registration is complete. Turn on and unfold the glasses, keep Meta AI running, and wait for DAT discovery."
    else -> null
}
