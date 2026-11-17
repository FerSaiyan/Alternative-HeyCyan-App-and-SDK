package com.fersaiyan.cyanbridge.shared.devices.firmware

import com.fersaiyan.cyanbridge.shared.customization.CustomizationFamily

/** Where an EyeVue/TuneBuds firmware image came from. */
enum class FirmwareOrigin {
    /** The vendor's update server (EyeVue). */
    OFFICIAL,

    /** A bare `.ufw` / `.fot` the user picked. */
    FILE,

    /** The CyanBridge custom-firmware `.zip` from the Pro customization email (it names its base version). */
    BUNDLE,
}

/** What the connected glasses reported, as the firmware checks need it. */
data class FamilyGlassesFirmware(
    val family: CustomizationFamily,
    /** EyeVue: the vendor model code (project + customer, e.g. `S3CY02`). TuneBuds: the model name. */
    val modelCode: String?,
    /** As the vendor shows it (`1.3.6`, `0.1.0.6`); null when unknown. */
    val firmwareVersion: String?,
    val batteryPercent: Int? = null,
)

/** An image about to be installed, with what its source says about it. */
class FirmwareCandidate(
    val family: CustomizationFamily,
    val fileName: String,
    val image: ByteArray,
    val sha256: String,
    val origin: FirmwareOrigin,
    /** A bundle's `base_ble_firmware_version` (the firmware it was built from). */
    val baseFirmwareVersion: String? = null,
    /** The version the image installs, when its source states it. */
    val targetVersion: String? = null,
)

/** The outcome of [FamilyFirmwareRules.check]. */
sealed interface FirmwareCheck {
    data class Accepted(
        val targetVersion: String?,
        /** True when the image is not newer than the glasses' firmware (a rollback or reinstall). */
        val notNewer: Boolean,
    ) : FirmwareCheck

    data class Refused(val reason: String) : FirmwareCheck
}

/** Why an EyeVue/TuneBuds firmware update cannot start right now. */
enum class FamilyOtaBlocker {
    NOT_CONNECTED,

    /** Below [FamilyFirmwareRules.minBatteryPercent]. */
    BATTERY_LOW,

    /** Media sync, live preview or another firmware update holds the glasses link. */
    GLASSES_BUSY,

    /** A voice question, Gemini Live or a recording is using the glasses audio. */
    AUDIO_BUSY,
}

/** A firmware image the EyeVue vendor published, as the owner mirrored it (firmware_dump `Pure_Firmware_Dumps`). */
data class KnownFirmwareImage(val modelCode: String, val version: String, val size: Long, val sha256: String)

object FamilyFirmwareRules {
    /**
     * The battery the glasses need before an update. EyeVue: the vendor app's own check
     * (`EyevueOtaActivity`: below 50% it refuses a non-mandatory update). TuneBuds: no vendor
     * threshold is known, so the EyeVue value is used.
     */
    fun minBatteryPercent(family: CustomizationFamily): Int = when (family) {
        CustomizationFamily.EYEVUE, CustomizationFamily.TUNEBUDS -> 50
        CustomizationFamily.HEYCYAN -> 0
    }

    /** Whether the family's vendor update server is used for "Update to the latest official firmware". */
    fun hasOfficialUpdates(family: CustomizationFamily): Boolean = family == CustomizationFamily.EYEVUE

    /** EyeVue BLE images the vendor published (sha256 of the files on its OSS bucket). */
    val knownEyevueImages: List<KnownFirmwareImage> = listOf(
        KnownFirmwareImage("S3CY02", "1.3.6", 1_813_600, "08ae9518bf7f18ddf2d4dcc04c2aec1b78a89735f78f90592722d967c464e050"),
        KnownFirmwareImage("S3AG66", "1.3.8", 1_805_408, "16ebb05700f39666062f5794b57266c9370cbb417b447636893d4336db7e3411"),
        KnownFirmwareImage("S3Q1", "1.3.6", 1_555_552, "53bde31b1baa6d3683f60e9af7597e5a7194e2823e8e7fda7b171261998f138b"),
        KnownFirmwareImage("S3S100", "1.4.3", 1_772_640, "fa7ffc7de135fe996f08a572961125db9ae37d17353790023217165e5c3b6f60"),
    )

    fun blockers(
        connected: Boolean,
        batteryPercent: Int?,
        family: CustomizationFamily,
        glassesBusy: Boolean,
        audioBusy: Boolean,
    ): Set<FamilyOtaBlocker> = buildSet {
        if (!connected) add(FamilyOtaBlocker.NOT_CONNECTED)
        // An unknown level does not block (the glasses refuse a low-battery update themselves).
        if (batteryPercent != null && batteryPercent < minBatteryPercent(family)) add(FamilyOtaBlocker.BATTERY_LOW)
        if (glassesBusy) add(FamilyOtaBlocker.GLASSES_BUSY)
        if (audioBusy) add(FamilyOtaBlocker.AUDIO_BUSY)
    }

    /**
     * Checks [candidate] against the connected [glasses]: the container's own integrity, the
     * family and model it was built for, and the base version a bundle names.
     */
    fun check(candidate: FirmwareCandidate, glasses: FamilyGlassesFirmware): FirmwareCheck {
        if (candidate.family != glasses.family) {
            return FirmwareCheck.Refused(
                "This firmware is for ${candidate.family.label} glasses, but ${glasses.family.label} glasses are connected.",
            )
        }
        val current = glasses.firmwareVersion
        candidate.baseFirmwareVersion?.let { base ->
            if (current == null || compareVersions(base, current) != 0) {
                return FirmwareCheck.Refused(
                    "This firmware was built for firmware $base, but the glasses run ${current ?: "an unknown version"}.",
                )
            }
        }
        return try {
            when (candidate.family) {
                CustomizationFamily.EYEVUE -> checkEyevue(candidate, glasses)
                CustomizationFamily.TUNEBUDS -> checkTuneBuds(candidate)
                CustomizationFamily.HEYCYAN -> FirmwareCheck.Refused("HeyCyan firmware is installed from the HeyCyan update options.")
            }
        } catch (error: FirmwareFileException) {
            FirmwareCheck.Refused(error.message ?: "The firmware file is not valid.")
        }
    }

    private fun checkEyevue(candidate: FirmwareCandidate, glasses: FamilyGlassesFirmware): FirmwareCheck {
        if (!candidate.fileName.endsWith(".ufw", ignoreCase = true)) {
            return FirmwareCheck.Refused("EyeVue firmware is a .ufw file (or the .zip from the CyanBridge email).")
        }
        val inspection = EyevueUfw.inspect(candidate.image)
        val known = knownEyevueImages.firstOrNull { it.sha256.equals(candidate.sha256, ignoreCase = true) }
        val fileModel = inspection.modelCode ?: known?.modelCode
        val glassesModel = glasses.modelCode?.let(::normalizeModel)
        if (glassesModel == null) {
            return FirmwareCheck.Refused("The glasses did not report their model. Reconnect them and try again.")
        }
        when {
            fileModel != null && normalizeModel(fileModel) != glassesModel ->
                return FirmwareCheck.Refused("This firmware is for EyeVue model $fileModel, but these glasses are $glassesModel.")
            // A custom bundle was built for this customer's glasses from their own base firmware.
            fileModel == null && candidate.origin != FirmwareOrigin.BUNDLE ->
                return FirmwareCheck.Refused(
                    "CyanBridge cannot confirm which EyeVue model this file is for, so it will not install it on $glassesModel.",
                )
        }
        val target = candidate.targetVersion ?: known?.version
        return FirmwareCheck.Accepted(target, notNewer = isNotNewer(target, glasses.firmwareVersion))
    }

    private fun checkTuneBuds(candidate: FirmwareCandidate): FirmwareCheck {
        val name = candidate.fileName.lowercase()
        if (listOf(".fot", ".fota", ".bin").none { name.endsWith(it) }) {
            return FirmwareCheck.Refused("TuneBuds firmware is a .fot file inside the .zip from the CyanBridge email.")
        }
        TuneBudsFot.inspect(candidate.image)
        // A bare .fot names neither its model nor its version anywhere CyanBridge can read, and
        // the glasses only report a model name; the bundle names the base firmware it was built from.
        if (candidate.origin != FirmwareOrigin.BUNDLE) {
            return FirmwareCheck.Refused(
                "CyanBridge cannot confirm which TuneBuds model a bare .fot file is for. Install the .zip from the CyanBridge firmware email instead.",
            )
        }
        return FirmwareCheck.Accepted(candidate.targetVersion, notNewer = false)
    }

    private fun isNotNewer(target: String?, current: String?): Boolean =
        target != null && current != null && compareVersions(target, current) <= 0

    fun normalizeModel(value: String): String = value.filter { it.isLetterOrDigit() }.uppercase()

    /** Numeric, part by part (`1.3.6` = `1.3.6.0`); non-numeric parts compare as 0. */
    fun compareVersions(a: String, b: String): Int {
        val left = a.trim().split('.').map { it.trim().toIntOrNull() ?: 0 }
        val right = b.trim().split('.').map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
